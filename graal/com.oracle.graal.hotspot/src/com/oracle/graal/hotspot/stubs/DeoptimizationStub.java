/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.hotspot.stubs;

import jdk.internal.jvmci.code.*;
import jdk.internal.jvmci.common.*;
import jdk.internal.jvmci.hotspot.*;
import static com.oracle.graal.hotspot.HotSpotBackend.*;
import static com.oracle.graal.hotspot.HotSpotBackend.Options.*;
import static com.oracle.graal.hotspot.nodes.DeoptimizationFetchUnrollInfoCallNode.*;
import static com.oracle.graal.hotspot.replacements.HotSpotReplacementsUtil.*;
import static com.oracle.graal.hotspot.stubs.UncommonTrapStub.*;

import com.oracle.graal.api.replacements.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.compiler.common.spi.*;
import com.oracle.graal.graph.Node.ConstantNodeParameter;
import com.oracle.graal.graph.Node.NodeIntrinsic;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.replacements.*;
import com.oracle.graal.replacements.Snippet.ConstantParameter;
import com.oracle.graal.word.*;

/**
 * Deoptimization stub.
 *
 * This is the entry point for code which is returning to a de-optimized frame.
 *
 * The steps taken by this frame are as follows:
 *
 * <li>push a dummy "register_save" and save the return values (O0, O1, F0/F1, G1) and all
 * potentially live registers (at a pollpoint many registers can be live).
 *
 * <li>call the C routine: Deoptimization::fetch_unroll_info (this function returns information
 * about the number and size of interpreter frames which are equivalent to the frame which is being
 * deoptimized)
 *
 * <li>deallocate the unpack frame, restoring only results values. Other volatile registers will now
 * be captured in the vframeArray as needed.
 *
 * <li>deallocate the deoptimization frame
 *
 * <li>in a loop using the information returned in the previous step push new interpreter frames
 * (take care to propagate the return values through each new frame pushed)
 *
 * <li>create a dummy "unpack_frame" and save the return values (O0, O1, F0)
 *
 * <li>call the C routine: Deoptimization::unpack_frames (this function lays out values on the
 * interpreter frame which was just created)
 *
 * <li>deallocate the dummy unpack_frame
 *
 * <li>ensure that all the return values are correctly set and then do a return to the interpreter
 * entry point
 *
 * <p>
 * <b>ATTENTION: We cannot do any complicated operations e.g. logging via printf in this snippet
 * because we change the current stack layout and so the code is very sensitive to register
 * allocation.</b>
 */
public class DeoptimizationStub extends SnippetStub {

    private final TargetDescription target;

    public DeoptimizationStub(HotSpotProviders providers, TargetDescription target, HotSpotForeignCallLinkage linkage) {
        super(DeoptimizationStub.class, "deoptimizationHandler", providers, linkage);
        this.target = target;
        assert PreferGraalStubs.getValue();
    }

    @Override
    public boolean preservesRegisters() {
        return false;
    }

    @Override
    protected Object getConstantParameterValue(int index, String name) {
        switch (index) {
            case 0:
                return providers.getRegisters().getThreadRegister();
            case 1:
                return providers.getRegisters().getStackPointerRegister();
            default:
                throw JVMCIError.shouldNotReachHere("unknown parameter " + name + " at index " + index);
        }
    }

    /**
     * Deoptimization handler for normal deoptimization
     * {@link HotSpotVMConfig#deoptimizationUnpackDeopt}.
     */
    @Snippet
    private static void deoptimizationHandler(@ConstantParameter Register threadRegister, @ConstantParameter Register stackPointerRegister) {
        final Word thread = registerAsWord(threadRegister);
        final long registerSaver = SaveAllRegistersNode.saveAllRegisters();

        final Word unrollBlock = fetchUnrollInfo(registerSaver);

        // Pop all the frames we must move/replace.
        //
        // Frame picture (youngest to oldest)
        // 1: self-frame
        // 2: deoptimizing frame
        // 3: caller of deoptimizing frame (could be compiled/interpreted).

        // Pop self-frame.
        LeaveCurrentStackFrameNode.leaveCurrentStackFrame(registerSaver);

        // Load the initial info we should save (e.g. frame pointer).
        final Word initialInfo = unrollBlock.readWord(deoptimizationUnrollBlockInitialInfoOffset());

        // Pop deoptimized frame.
        final int sizeOfDeoptimizedFrame = unrollBlock.readInt(deoptimizationUnrollBlockSizeOfDeoptimizedFrameOffset());
        LeaveDeoptimizedStackFrameNode.leaveDeoptimizedStackFrame(sizeOfDeoptimizedFrame, initialInfo);

        /*
         * Stack bang to make sure there's enough room for the interpreter frames. Bang stack for
         * total size of the interpreter frames plus shadow page size. Bang one page at a time
         * because large sizes can bang beyond yellow and red zones.
         *
         * @deprecated This code should go away as soon as JDK-8032410 hits the Graal repository.
         */
        final int totalFrameSizes = unrollBlock.readInt(deoptimizationUnrollBlockTotalFrameSizesOffset());
        final int bangPages = NumUtil.roundUp(totalFrameSizes, pageSize()) / pageSize() + stackShadowPages();
        Word stackPointer = readRegister(stackPointerRegister);

        for (int i = 1; i < bangPages; i++) {
            stackPointer.writeInt((-i * pageSize()) + stackBias(), 0, STACK_BANG_LOCATION);
        }

        // Load number of interpreter frames.
        final int numberOfFrames = unrollBlock.readInt(deoptimizationUnrollBlockNumberOfFramesOffset());

        // Load address of array of frame sizes.
        final Word frameSizes = unrollBlock.readWord(deoptimizationUnrollBlockFrameSizesOffset());

        // Load address of array of frame PCs.
        final Word framePcs = unrollBlock.readWord(deoptimizationUnrollBlockFramePcsOffset());

        /*
         * Get the current stack pointer (sender's original SP) before adjustment so that we can
         * save it in the skeletal interpreter frame.
         */
        Word senderSp = readRegister(stackPointerRegister);

        // Adjust old interpreter frame to make space for new frame's extra Java locals.
        final int callerAdjustment = unrollBlock.readInt(deoptimizationUnrollBlockCallerAdjustmentOffset());
        writeRegister(stackPointerRegister, readRegister(stackPointerRegister).subtract(callerAdjustment));

        for (int i = 0; i < numberOfFrames; i++) {
            final Word frameSize = frameSizes.readWord(i * wordSize());
            final Word framePc = framePcs.readWord(i * wordSize());

            // Push an interpreter frame onto the stack.
            PushInterpreterFrameNode.pushInterpreterFrame(frameSize, framePc, senderSp, initialInfo);

            // Get the current stack pointer (sender SP) and pass it to next frame.
            senderSp = readRegister(stackPointerRegister);
        }

        // Get final return address.
        final Word framePc = framePcs.readWord(numberOfFrames * wordSize());

        /*
         * Enter a frame to call out to unpack frames. Since we changed the stack pointer to an
         * unknown alignment we need to align it here before calling C++ code.
         */
        final Word senderFp = initialInfo;
        EnterUnpackFramesStackFrameNode.enterUnpackFramesStackFrame(framePc, senderSp, senderFp, registerSaver);

        // Pass unpack deopt mode to unpack frames.
        final int mode = deoptimizationUnpackDeopt();
        unpackFrames(UNPACK_FRAMES, thread, mode);

        LeaveUnpackFramesStackFrameNode.leaveUnpackFramesStackFrame(registerSaver);
    }

    /**
     * Reads the value of the passed register as a Word.
     */
    private static Word readRegister(Register register) {
        return registerAsWord(register, false, false);
    }

    /**
     * Writes the value of the passed register.
     *
     * @param value value the register should be set to
     */
    private static void writeRegister(Register register, Word value) {
        writeRegisterAsWord(register, value);
    }

    @Fold
    private static int stackShadowPages() {
        return config().useStackBanging ? config().stackShadowPages : 0;
    }

    /**
     * Returns the stack bias for the host architecture.
     *
     * @deprecated This method should go away as soon as JDK-8032410 hits the Graal repository.
     *
     * @return stack bias
     */
    @Deprecated
    @Fold
    private static int stackBias() {
        return config().stackBias;
    }

    @Fold
    private static int deoptimizationUnrollBlockSizeOfDeoptimizedFrameOffset() {
        return config().deoptimizationUnrollBlockSizeOfDeoptimizedFrameOffset;
    }

    @Fold
    private static int deoptimizationUnrollBlockCallerAdjustmentOffset() {
        return config().deoptimizationUnrollBlockCallerAdjustmentOffset;
    }

    @Fold
    private static int deoptimizationUnrollBlockNumberOfFramesOffset() {
        return config().deoptimizationUnrollBlockNumberOfFramesOffset;
    }

    @Fold
    private static int deoptimizationUnrollBlockTotalFrameSizesOffset() {
        return config().deoptimizationUnrollBlockTotalFrameSizesOffset;
    }

    @Fold
    private static int deoptimizationUnrollBlockFrameSizesOffset() {
        return config().deoptimizationUnrollBlockFrameSizesOffset;
    }

    @Fold
    private static int deoptimizationUnrollBlockFramePcsOffset() {
        return config().deoptimizationUnrollBlockFramePcsOffset;
    }

    @Fold
    private static int deoptimizationUnrollBlockInitialInfoOffset() {
        return config().deoptimizationUnrollBlockInitialInfoOffset;
    }

    @Fold
    private static int deoptimizationUnpackDeopt() {
        return config().deoptimizationUnpackDeopt;
    }

    @Fold
    private static int deoptimizationUnpackUncommonTrap() {
        return config().deoptimizationUnpackUncommonTrap;
    }

    @NodeIntrinsic(value = StubForeignCallNode.class, setStampFromReturnType = true)
    public static native int unpackFrames(@ConstantNodeParameter ForeignCallDescriptor unpackFrames, Word thread, int mode);
}
