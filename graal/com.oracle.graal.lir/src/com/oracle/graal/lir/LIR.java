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

import com.oracle.graal.lir.LIRInstruction.StateProcedure;
import com.oracle.graal.lir.StandardOp.BlockEndOp;
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

    private int firstVariableNumber;

    private int numVariables;

    public final BlockMap<List<LIRInstruction>> lirInstructions;

    private boolean hasArgInCallerFrame;

    /**
     * Creates a new LIR instance for the specified compilation.
     */
    public LIR(ControlFlowGraph cfg, BlockMap<List<ScheduledNode>> blockToNodesMap, List<Block> linearScanOrder, List<Block> codeEmittingOrder) {
        this.cfg = cfg;
        this.blockToNodesMap = blockToNodesMap;
        this.codeEmittingOrder = codeEmittingOrder;
        this.linearScanOrder = linearScanOrder;
        this.lirInstructions = new BlockMap<>(cfg);
    }

    /**
     * Gets the nodes in a given block.
     */
    public List<ScheduledNode> nodesFor(Block block) {
        return blockToNodesMap.get(block);
    }

    /**
     * Determines if any instruction in the LIR has debug info associated with it.
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
        return firstVariableNumber + numVariables++;
    }

    public void setFirstVariableNumber(int num) {
        firstVariableNumber = num;
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

    /**
     * Gets the exception edge (if any) originating at a given operation.
     */
    public static LabelRef getExceptionEdge(LIRInstruction op) {
        final LabelRef[] exceptionEdge = {null};
        op.forEachState(new StateProcedure() {
            @Override
            protected void doState(LIRFrameState state) {
                if (state.exceptionEdge != null) {
                    assert exceptionEdge[0] == null;
                    exceptionEdge[0] = state.exceptionEdge;
                }
            }
        });
        return exceptionEdge[0];
    }

    /**
     * The maximum distance an operation with an {@linkplain #getExceptionEdge(LIRInstruction)
     * exception edge} can be from the last instruction of a LIR block. The value of 3 is based on a
     * non-void call operation that has an exception edge. Such a call may move the result to
     * another register and then spill it.
     * <p>
     * The rationale for such a constant is to limit the search for an insertion point when adding
     * move operations at the end of a block. Such moves must be inserted before all control flow
     * instructions.
     */
    public static final int MAX_EXCEPTION_EDGE_OP_DISTANCE_FROM_END = 3;

    public static boolean verifyBlock(LIR lir, Block block) {
        List<LIRInstruction> ops = lir.lir(block);
        if (ops.size() == 0) {
            return false;
        }
        LIRInstruction opWithExceptionEdge = null;
        int index = 0;
        int lastIndex = ops.size() - 1;
        for (LIRInstruction op : ops.subList(0, lastIndex)) {
            assert !(op instanceof BlockEndOp) : op.getClass();
            LabelRef exceptionEdge = getExceptionEdge(op);
            if (exceptionEdge != null) {
                assert opWithExceptionEdge == null : "multiple ops with an exception edge not allowed";
                opWithExceptionEdge = op;
                int distanceFromEnd = lastIndex - index;
                assert distanceFromEnd <= MAX_EXCEPTION_EDGE_OP_DISTANCE_FROM_END;
            }
            index++;
        }
        LIRInstruction end = ops.get(lastIndex);
        assert end instanceof BlockEndOp : end.getClass();
        return true;
    }

    public static boolean verifyBlocks(LIR lir, List<Block> blocks) {
        for (Block block : blocks) {
            for (Block sux : block.getSuccessors()) {
                assert blocks.contains(sux) : "missing successor from: " + block + "to: " + sux;
            }
            for (Block pred : block.getPredecessors()) {
                assert blocks.contains(pred) : "missing predecessor from: " + block + "to: " + pred;
            }
            if (!verifyBlock(lir, block)) {
                return false;
            }
        }
        return true;
    }
}
