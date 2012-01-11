/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.graal.compiler.lir;

import static com.oracle.max.cri.ci.CiValueUtil.*;

import java.util.*;

import com.oracle.max.cri.ci.*;
import com.oracle.max.cri.ci.CiCallingConvention.*;
import com.oracle.max.cri.ri.*;
import com.oracle.max.graal.compiler.stub.*;
import com.oracle.max.graal.compiler.util.*;

/**
 * This class is used to build the stack frame layout for a compiled method.
 * A {@link CiStackSlot} is used to index slots of the frame relative to the stack pointer.
 * The frame size is only fixed after register allocation when all spill slots have
 * been allocated. Both the outgoing argument area and the spill are can grow until then.
 * Therefore, outgoing arguments are indexed from the stack pointer, while spill slots
 * are indexed from the beginning of the frame (and the total frame size has to be added
 * to get the actual offset from the stack pointer).
 * <br>
 * This is the format of a stack frame:
 * <pre>
 *   Base       Contents
 *
 *            :                                :  -----
 *   caller   | incoming overflow argument n   |    ^
 *   frame    :     ...                        :    | positive
 *            | incoming overflow argument 0   |    | offsets
 *   ---------+--------------------------------+---------------------
 *            | return address                 |    |            ^
 *   current  +--------------------------------+    |            |    -----
 *   frame    |                                |    |            |      ^
 *            : callee save area               :    |            |      |
 *            |                                |    |            |      |
 *            +--------------------------------+    |            |      |
 *            | spill slot 0                   |    | negative   |      |
 *            :     ...                        :    v offsets    |      |
 *            | spill slot n                   |  -----        total  frame
 *            +--------------------------------+               frame  size
 *            | alignment padding              |               size     |
 *            +--------------------------------+  -----          |      |
 *            | outgoing overflow argument n   |    ^            |      |
 *            :     ...                        :    | positive   |      |
 *            | outgoing overflow argument 0   |    | offsets    v      v
 *    %sp-->  +--------------------------------+---------------------------
 *
 * </pre>
 * The spill slot area also includes stack allocated memory blocks (ALLOCA blocks). The size
 * of such a block may be greater than the size of a normal spill slot or the word size.
 * <br>
 * A runtime has two ways to reserve space in the stack frame for its own use: <ul>
 * <li>A memory block somewhere in the frame of size {@link RiRuntime#getCustomStackAreaSize()}. The offset
 *     to this block is returned in {@link CiTargetMethod#customStackAreaOffset()}.
 * <li>At the beginning of the overflow argument area: The calling convention can specify that the first
 *     overflow stack argument is not at offset 0, but at a specified offset o. Use
 *     {@link RiRuntime#getMinimumOutgoingSize()} to make sure that call-free methods also have this space
 *     reserved. Then the VM can use memory the memory at offset 0 relative to the stack pointer.
 * </ul>
 */
public final class FrameMap {
    public final RiRuntime runtime;
    public final CiTarget target;
    public final RiRegisterConfig registerConfig;

    /**
     * The final frame size, not including the size of the return address.
     * The value is only set after register allocation is complete, i.e., after all spill slots have been allocated.
     */
    private int frameSize;

    /**
     * Size of the area occupied by spill slots and other stack-allocated memory blocks.
     */
    private int spillSize;

    /**
     * Size of the area occupied by outgoing overflow arguments.
     * This value is adjusted as calling conventions for outgoing calls are retrieved.
     */
    private int outgoingSize;

    /**
     * The list of stack areas allocated in this frame that are present in every reference map.
     */
    private final List<CiStackSlot> objectStackBlocks;

    /**
     * The stack area reserved for use by the VM, or {@code null} if the VM does not request stack space.
     */
    private final CiStackSlot customArea;

    /**
     * Creates a new frame map for the specified method.
     */
    public FrameMap(RiRuntime runtime, CiTarget target, RiRegisterConfig registerConfig) {
        this.runtime = runtime;
        this.target = target;
        this.registerConfig = registerConfig;
        this.frameSize = -1;
        this.spillSize = returnAddressSize() + calleeSaveAreaSize();
        this.outgoingSize = runtime.getMinimumOutgoingSize();
        this.objectStackBlocks = new ArrayList<>();
        this.customArea = allocateStackBlock(runtime.getCustomStackAreaSize(), false);
    }


    private int returnAddressSize() {
        return target.arch.returnAddressSize;
    }

    private int calleeSaveAreaSize() {
        CiCalleeSaveLayout csl = registerConfig.getCalleeSaveLayout();
        return csl != null ? csl.size : 0;
    }

    /**
     * Gets the frame size of the compiled frame, not including the size of the return address.
     * @return The size of the frame (in bytes).
     */
    public int frameSize() {
        assert frameSize != -1 : "frame size not computed yet";
        return frameSize;
    }

    /**
     * Gets the total frame size of the compiled frame, including the size of the return address.
     * @return The total size of the frame (in bytes).
     */
    public int totalFrameSize() {
        return frameSize() + returnAddressSize();
    }

    /**
     * Sets the frame size for this frame.
     * @param frameSize The frame size (in bytes).
     */
    public void setFrameSize(int frameSize) {
        assert this.frameSize == -1 : "must only be set once";
        this.frameSize = frameSize;
    }

    /**
     * Computes the frame size for this frame. After this method has been called, methods that change the
     * frame size cannot be called anymore, e.g., no more spill slots or outgoing arguments can be requested.
     */
    public void finish() {
        setFrameSize(target.alignFrameSize(outgoingSize + spillSize - returnAddressSize()));
    }

    /**
     * Computes the offset of a stack slot relative to the frame register.
     * This is also the bit index of stack slots in the reference map.
     *
     * @param slot a stack slot
     * @return the offset of the stack slot
     */
    public int offsetForStackSlot(CiStackSlot slot) {
        assert (!slot.rawAddFrameSize() && slot.rawOffset() < outgoingSize) ||
            (slot.rawAddFrameSize() && slot.rawOffset() < 0 && -slot.rawOffset() <= spillSize) ||
            (slot.rawAddFrameSize() && slot.rawOffset() >= 0);
        return slot.offset(totalFrameSize());
    }

    /**
     * Gets the offset to the stack area where callee-saved registers are stored.
     * @return The offset to the callee save area (in bytes).
     */
    public int offsetToCalleeSaveArea() {
        return frameSize() - calleeSaveAreaSize();
    }

    /**
     * Gets the offset of the stack area stack block reserved for use by the VM, or -1 if the VM does not request stack space.
     * @return The offset to the custom area (in bytes).
     */
    public int offsetToCustomArea() {
        return customArea == null ? -1 : offsetForStackSlot(customArea);
    }

    /**
     * Informs the frame map that the compiled code calls a particular method, which
     * may need stack space for outgoing arguments.
     * @param cc The calling convention for the called method.
     * @param type The type of calling convention.
     */
    public void callsMethod(CiCallingConvention cc, Type type) {
        // TODO look at the actual stack offsets?
        assert type.out;
        reserveOutgoing(cc.stackSize);
    }

    /**
     * Informs the frame map that the compiled code uses a particular compiler stub, which
     * may need stack space for outgoing arguments.
     * @param stub The compiler stub.
     */
    public void usesStub(CompilerStub stub) {
        // TODO look at the actual stack slot offsets?
        int argsSize = stub.inArgs.length * target.wordSize;
        int resultSize = stub.resultKind.isVoid() ? 0 : target.wordSize;
        reserveOutgoing(Math.max(argsSize, resultSize));
    }

    /**
     * Reserves space for stack-based outgoing arguments.
     * @param argsSize The amount of space (in bytes) to reserve for stack-based outgoing arguments.
     */
    public void reserveOutgoing(int argsSize) {
        assert frameSize == -1 : "frame size must not yet be fixed";
        outgoingSize = Math.max(outgoingSize, argsSize);
    }

    private CiStackSlot getSlot(CiKind kind, int additionalOffset) {
        return CiStackSlot.get(kind, -spillSize + additionalOffset, true);
    }

    /**
     * Reserves a spill slot in the frame of the method being compiled. The returned slot is aligned on its natural alignment,
     * i.e., an 8-byte spill slot is aligned at an 8-byte boundary.
     * @param kind The kind of the spill slot to be reserved.
     * @return A spill slot denoting the reserved memory area.
     */
    public CiStackSlot allocateSpillSlot(CiKind kind) {
        int size = target.sizeInBytes(kind);
        spillSize = Util.roundUp(spillSize + size, size);
        return getSlot(kind, 0);
    }

    /**
     * Reserves a block of memory in the frame of the method being compiled. The returned block is aligned on a word boundary.
     * If the requested size is 0, the method returns {@code null}.
     *
     * @param size The size to reserve (in bytes).
     * @param refs Specifies if the block is all references. If true, the block will be in all reference maps for this method.
     *             The caller is responsible to initialize the memory block before the first instruction that uses a reference map.
     * @return A stack slot describing the begin of the memory block.
     */
    public CiStackSlot allocateStackBlock(int size, boolean refs) {
        if (size == 0) {
            return null;
        }
        spillSize = Util.roundUp(spillSize + size, target.wordSize);

        if (refs) {
            assert size % target.wordSize == 0;
            CiStackSlot result = getSlot(CiKind.Object, 0);
            objectStackBlocks.add(result);
            for (int i = target.wordSize; i < size; i += target.wordSize) {
                objectStackBlocks.add(getSlot(CiKind.Object, i));
            }
            return result;

        } else {
            return getSlot(target.wordKind, 0);
        }
    }


    private int frameRefMapIndex(CiStackSlot slot) {
        assert offsetForStackSlot(slot) % target.wordSize == 0;
        return offsetForStackSlot(slot) / target.wordSize;
    }

    /**
     * Initializes a reference map that covers all registers of the target architecture.
     */
    public CiBitMap initRegisterRefMap() {
        return new CiBitMap(target.arch.registerReferenceMapBitCount);
    }

    /**
     * Initializes a reference map. Initially, the size is large enough to cover all the
     * slots in the frame. If the method has incoming reference arguments on the stack,
     * the reference map might grow later when such a reference is set.
     */
    public CiBitMap initFrameRefMap() {
        CiBitMap frameRefMap = new CiBitMap(frameSize() / target.wordSize);
        for (CiStackSlot slot : objectStackBlocks) {
            setReference(slot, null, frameRefMap);
        }
        return frameRefMap;
    }

    /**
     * Marks the specified location as a reference in the reference map of the debug information.
     * The tracked location can be a {@link CiRegisterValue} or a {@link CiStackSlot}. Note that a
     * {@link CiConstant} is automatically tracked.
     *
     * @param location The location to be added to the reference map.
     * @param registerRefMap A register reference map, as created by {@link #initRegisterRefMap()}.
     * @param frameRefMap A frame reference map, as created by {@link #initFrameRefMap()}.
     */
    public void setReference(CiValue location, CiBitMap registerRefMap, CiBitMap frameRefMap) {
        if (location.kind == CiKind.Object) {
            if (isRegister(location)) {
                assert registerRefMap.size() == target.arch.registerReferenceMapBitCount;
                registerRefMap.set(asRegister(location).number);
            } else if (isStackSlot(location)) {
                int index = frameRefMapIndex(asStackSlot(location));
                frameRefMap.grow(index + 1);
                frameRefMap.set(index);
            } else {
                assert isConstant(location);
            }
        }
    }

    /**
     * Clears the specified location as a reference in the reference map of the debug information.
     * The tracked location can be a {@link CiRegisterValue} or a {@link CiStackSlot}. Note that a
     * {@link CiConstant} is automatically tracked.
     *
     * @param location The location to be removed from the reference map.
     * @param registerRefMap A register reference map, as created by {@link #initRegisterRefMap()}.
     * @param frameRefMap A frame reference map, as created by {@link #initFrameRefMap()}.
     */
    public void clearReference(CiValue location, CiBitMap registerRefMap, CiBitMap frameRefMap) {
        if (location.kind == CiKind.Object) {
            if (location instanceof CiRegisterValue) {
                assert registerRefMap.size() == target.arch.registerReferenceMapBitCount;
                registerRefMap.clear(asRegister(location).number);
            } else if (isStackSlot(location)) {
                int index = frameRefMapIndex(asStackSlot(location));
                if (index < frameRefMap.size()) {
                    frameRefMap.clear(index);
                }
            } else {
                assert isConstant(location);
            }
        }
    }

    public CiAddress asAddress(CiValue value) {
        if (isStackSlot(value)) {
            CiStackSlot slot = (CiStackSlot) value;
            return new CiAddress(slot.kind, registerConfig.getFrameRegister().asValue(), offsetForStackSlot(slot));
        }
        return (CiAddress) value;
    }
}
