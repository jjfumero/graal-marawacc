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
package com.oracle.graal.lir;

import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.cfg.*;

/**
 * This class implements the overall container for the LIR graph and directs its construction,
 * optimization, and finalization.
 */
public class LIR {

    public final ControlFlowGraph cfg;

    /**
     * The nodes for the blocks. TODO: This should go away, we want all nodes connected with a
     * next-pointer.
     */
    private final BlockMap<List<ScheduledNode>> blockToNodesMap;

    /**
     * The linear-scan ordered list of blocks.
     */
    private final List<Block> linearScanOrder;

    /**
     * The order in which the code is emitted.
     */
    private final List<Block> codeEmittingOrder;

    /**
     * Various out-of-line stubs to be emitted near the end of the method after all other LIR code
     * has been emitted.
     */
    public final List<Code> stubs;

    private int numVariables;

    public SpillMoveFactory spillMoveFactory;

    public final BlockMap<List<LIRInstruction>> lirInstructions;

    public interface SpillMoveFactory {

        LIRInstruction createMove(Value result, Value input);

        LIRInstruction createExchange(Value input1, Value input2);
    }

    private boolean hasArgInCallerFrame;

    /**
     * An opaque chunk of machine code.
     */
    public interface Code {

        void emitCode(TargetMethodAssembler tasm);

        /**
         * A description of this code stub useful for commenting the code in a disassembly.
         */
        String description();
    }

    /**
     * Creates a new LIR instance for the specified compilation.
     */
    public LIR(ControlFlowGraph cfg, BlockMap<List<ScheduledNode>> blockToNodesMap, List<Block> linearScanOrder, List<Block> codeEmittingOrder) {
        this.cfg = cfg;
        this.blockToNodesMap = blockToNodesMap;
        this.codeEmittingOrder = codeEmittingOrder;
        this.linearScanOrder = linearScanOrder;
        this.lirInstructions = new BlockMap<>(cfg);

        stubs = new ArrayList<>();
    }

    /**
     * Gets the nodes in a given block.
     */
    public List<ScheduledNode> nodesFor(Block block) {
        return blockToNodesMap.get(block);
    }

    /**
     * Determines if any instruction in the LIR has any debug info associated with it.
     */
    public boolean hasDebugInfo() {
        for (Block b : linearScanOrder()) {
            for (LIRInstruction op : lir(b)) {
                if (op.hasState()) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<LIRInstruction> lir(Block block) {
        return lirInstructions.get(block);
    }

    public void setLir(Block block, List<LIRInstruction> list) {
        assert lir(block) == null : "lir instruction list should only be initialized once";
        lirInstructions.put(block, list);
    }

    /**
     * Gets the linear scan ordering of blocks as a list.
     * 
     * @return the blocks in linear scan order
     */
    public List<Block> linearScanOrder() {
        return linearScanOrder;
    }

    public List<Block> codeEmittingOrder() {
        return codeEmittingOrder;
    }

    public int numVariables() {
        return numVariables;
    }

    public int nextVariable() {
        return numVariables++;
    }

    public void emitCode(TargetMethodAssembler tasm) {
        if (tasm.frameContext != null) {
            tasm.frameContext.enter(tasm);
        }

        for (Block b : codeEmittingOrder()) {
            emitBlock(tasm, b);
        }

        // generate code stubs
        for (Code c : stubs) {
            emitCodeStub(tasm, c);
        }
    }

    private void emitBlock(TargetMethodAssembler tasm, Block block) {
        if (Debug.isDumpEnabled()) {
            tasm.blockComment(String.format("block B%d %s", block.getId(), block.getLoop()));
        }

        for (LIRInstruction op : lir(block)) {
            if (Debug.isDumpEnabled()) {
                tasm.blockComment(String.format("%d %s", op.id(), op));
            }

            emitOp(tasm, op);
        }
    }

    private static void emitOp(TargetMethodAssembler tasm, LIRInstruction op) {
        try {
            try {
                op.emitCode(tasm);
            } catch (AssertionError t) {
                throw new GraalInternalError(t);
            } catch (RuntimeException t) {
                throw new GraalInternalError(t);
            }
        } catch (GraalInternalError e) {
            throw e.addContext("lir instruction", op);
        }
    }

    private static void emitCodeStub(TargetMethodAssembler tasm, Code code) {
        if (Debug.isDumpEnabled()) {
            tasm.blockComment(String.format("code stub: %s", code.description()));
        }
        code.emitCode(tasm);
    }

    public void setHasArgInCallerFrame() {
        hasArgInCallerFrame = true;
    }

    /**
     * Determines if any of the parameters to the method are passed via the stack where the
     * parameters are located in the caller's frame.
     */
    public boolean hasArgInCallerFrame() {
        return hasArgInCallerFrame;
    }
}
