/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.amd64;

import static com.oracle.graal.amd64.AMD64.*;
import static com.oracle.graal.api.code.CallingConvention.Type.*;
import static com.oracle.graal.api.code.ValueUtil.*;
import static com.oracle.graal.phases.GraalOptions.*;

import java.lang.reflect.*;
import java.util.*;

import sun.misc.*;

import com.oracle.graal.amd64.*;
import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.asm.amd64.*;
import com.oracle.graal.asm.amd64.AMD64Assembler.ConditionFlag;
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.bridge.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.stubs.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.LIRInstruction.ValueProcedure;
import com.oracle.graal.lir.StandardOp.ParametersOp;
import com.oracle.graal.lir.amd64.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.cfg.*;
import com.oracle.graal.phases.*;

/**
 * HotSpot AMD64 specific backend.
 */
public class AMD64HotSpotBackend extends HotSpotBackend {

    private static final Unsafe unsafe = Unsafe.getUnsafe();

    public AMD64HotSpotBackend(HotSpotRuntime runtime, TargetDescription target) {
        super(runtime, target);
    }

    @Override
    public LIRGenerator newLIRGenerator(StructuredGraph graph, FrameMap frameMap, ResolvedJavaMethod method, LIR lir) {
        return new AMD64HotSpotLIRGenerator(graph, runtime(), target, frameMap, method, lir);
    }

    /**
     * Emits code to do stack overflow checking.
     * 
     * @param afterFrameInit specifies if the stack pointer has already been adjusted to allocate
     *            the current frame
     */
    protected static void emitStackOverflowCheck(TargetMethodAssembler tasm, boolean afterFrameInit) {
        if (GraalOptions.StackShadowPages > 0) {

            AMD64MacroAssembler asm = (AMD64MacroAssembler) tasm.asm;
            int frameSize = tasm.frameMap.frameSize();
            if (frameSize > 0) {
                int lastFramePage = frameSize / unsafe.pageSize();
                // emit multiple stack bangs for methods with frames larger than a page
                for (int i = 0; i <= lastFramePage; i++) {
                    int disp = (i + GraalOptions.StackShadowPages) * unsafe.pageSize();
                    if (afterFrameInit) {
                        disp -= frameSize;
                    }
                    tasm.blockComment("[stack overflow check]");
                    asm.movq(new AMD64Address(rsp, -disp), AMD64.rax);
                }
            }
        }
    }

    class HotSpotFrameContext implements FrameContext {

        final boolean isStub;

        HotSpotFrameContext(boolean isStub) {
            this.isStub = isStub;
        }

        @Override
        public void enter(TargetMethodAssembler tasm) {
            FrameMap frameMap = tasm.frameMap;
            int frameSize = frameMap.frameSize();

            AMD64MacroAssembler asm = (AMD64MacroAssembler) tasm.asm;
            if (!isStub) {
                emitStackOverflowCheck(tasm, false);
            }
            asm.decrementq(rsp, frameSize);
            if (GraalOptions.ZapStackOnMethodEntry) {
                final int intSize = 4;
                for (int i = 0; i < frameSize / intSize; ++i) {
                    asm.movl(new AMD64Address(rsp, i * intSize), 0xC1C1C1C1);
                }
            }
            CalleeSaveLayout csl = frameMap.registerConfig.getCalleeSaveLayout();
            if (csl != null && csl.size != 0) {
                int frameToCSA = frameMap.offsetToCalleeSaveArea();
                assert frameToCSA >= 0;
                asm.save(csl, frameToCSA);
            }
        }

        @Override
        public void leave(TargetMethodAssembler tasm) {
            int frameSize = tasm.frameMap.frameSize();
            AMD64MacroAssembler asm = (AMD64MacroAssembler) tasm.asm;
            CalleeSaveLayout csl = tasm.frameMap.registerConfig.getCalleeSaveLayout();

            if (csl != null && csl.size != 0) {
                tasm.compilationResult.setRegisterRestoreEpilogueOffset(asm.codeBuffer.position());
                // saved all registers, restore all registers
                int frameToCSA = tasm.frameMap.offsetToCalleeSaveArea();
                asm.restore(csl, frameToCSA);
            }

            asm.incrementq(rsp, frameSize);
        }
    }

    @Override
    protected AbstractAssembler createAssembler(FrameMap frameMap) {
        return new AMD64MacroAssembler(target, frameMap.registerConfig);
    }

    @Override
    public TargetMethodAssembler newAssembler(LIRGenerator lirGen, CompilationResult compilationResult) {
        // Omit the frame if the method:
        // - has no spill slots or other slots allocated during register allocation
        // - has no callee-saved registers
        // - has no incoming arguments passed on the stack
        // - has no instructions with debug info
        AMD64HotSpotLIRGenerator gen = (AMD64HotSpotLIRGenerator) lirGen;
        FrameMap frameMap = gen.frameMap;
        LIR lir = gen.lir;
        boolean omitFrame = CanOmitFrame && !frameMap.frameNeedsAllocating() && !lir.hasArgInCallerFrame();

        Stub stub = runtime().asStub(lirGen.method());
        AbstractAssembler masm = createAssembler(frameMap);
        HotSpotFrameContext frameContext = omitFrame ? null : new HotSpotFrameContext(stub != null);
        TargetMethodAssembler tasm = new TargetMethodAssembler(target, runtime(), frameMap, masm, frameContext, compilationResult);
        tasm.setFrameSize(frameMap.frameSize());
        StackSlot deoptimizationRescueSlot = gen.deoptimizationRescueSlot;
        if (deoptimizationRescueSlot != null && stub == null) {
            tasm.compilationResult.setCustomStackAreaOffset(frameMap.offsetForStackSlot(deoptimizationRescueSlot));
        }

        if (stub != null) {

            final Set<Register> definedRegisters = gatherDefinedRegisters(lir);
            stub.initDefinedRegisters(definedRegisters);

            // Eliminate unnecessary register preservation and
            // record where preserved registers are saved
            for (Map.Entry<LIRFrameState, AMD64SaveRegistersOp> e : gen.calleeSaveInfo.entrySet()) {
                AMD64SaveRegistersOp save = e.getValue();
                save.updateAndDescribePreservation(definedRegisters, e.getKey().debugInfo(), frameMap);
            }
        }

        return tasm;
    }

    /**
     * Finds all the registers that are defined by some given LIR.
     * 
     * @param lir the LIR to examine
     * @return the registers that are defined by or used as temps for any instruction in {@code lir}
     */
    private static Set<Register> gatherDefinedRegisters(LIR lir) {
        final Set<Register> definedRegisters = new HashSet<>();
        ValueProcedure defProc = new ValueProcedure() {

            @Override
            public Value doValue(Value value) {
                if (ValueUtil.isRegister(value)) {
                    final Register reg = ValueUtil.asRegister(value);
                    definedRegisters.add(reg);
                }
                return value;
            }
        };
        for (Block block : lir.codeEmittingOrder()) {
            for (LIRInstruction op : lir.lir(block)) {
                if (op instanceof ParametersOp) {
                    // Don't consider this as a definition
                } else {
                    op.forEachTemp(defProc);
                    op.forEachOutput(defProc);
                }
            }
        }
        return definedRegisters;
    }

    @Override
    public void emitCode(TargetMethodAssembler tasm, ResolvedJavaMethod method, LIRGenerator lirGen) {
        AMD64MacroAssembler asm = (AMD64MacroAssembler) tasm.asm;
        FrameMap frameMap = tasm.frameMap;
        RegisterConfig regConfig = frameMap.registerConfig;
        HotSpotVMConfig config = runtime().config;
        boolean isStatic = Modifier.isStatic(method.getModifiers());
        Label unverifiedStub = isStatic ? null : new Label();

        // Emit the prefix

        if (!isStatic) {
            tasm.recordMark(Marks.MARK_UNVERIFIED_ENTRY);
            CallingConvention cc = regConfig.getCallingConvention(JavaCallee, null, new JavaType[]{runtime().lookupJavaType(Object.class)}, target, false);
            Register inlineCacheKlass = rax; // see definition of IC_Klass in
                                             // c1_LIRAssembler_x86.cpp
            Register receiver = asRegister(cc.getArgument(0));
            AMD64Address src = new AMD64Address(receiver, config.hubOffset);

            asm.cmpq(inlineCacheKlass, src);
            asm.jcc(ConditionFlag.NotEqual, unverifiedStub);
        }

        asm.align(config.codeEntryAlignment);
        tasm.recordMark(Marks.MARK_OSR_ENTRY);
        tasm.recordMark(Marks.MARK_VERIFIED_ENTRY);

        // Emit code for the LIR
        lirGen.lir.emitCode(tasm);

        HotSpotFrameContext frameContext = (HotSpotFrameContext) tasm.frameContext;
        if (frameContext != null && !frameContext.isStub) {
            tasm.recordMark(Marks.MARK_EXCEPTION_HANDLER_ENTRY);
            AMD64Call.directCall(tasm, asm, runtime().lookupRuntimeCall(EXCEPTION_HANDLER), null, false, null);
            tasm.recordMark(Marks.MARK_DEOPT_HANDLER_ENTRY);
            AMD64Call.directCall(tasm, asm, runtime().lookupRuntimeCall(DEOPT_HANDLER), null, false, null);
        } else {
            // No need to emit the stubs for entries back into the method since
            // it has no calls that can cause such "return" entries
            assert !frameMap.accessesCallerFrame() : method;
        }

        if (unverifiedStub != null) {
            asm.bind(unverifiedStub);
            AMD64Call.directJmp(tasm, asm, runtime().lookupRuntimeCall(IC_MISS_HANDLER));
        }
    }
}
