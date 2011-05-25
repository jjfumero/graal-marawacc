/*
 * Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.c1x.ir;

import java.util.*;

import com.oracle.graal.graph.*;
import com.sun.cri.ci.*;

/**
 * The {@code BlockEnd} instruction is a base class for all instructions that end a basic
 * block, including branches, switches, throws, and goto's.
 */
public abstract class BlockEnd extends Instruction {

    private static final int INPUT_COUNT = 0;

    private static final int SUCCESSOR_COUNT = 1;
    private static final int SUCCESSOR_STATE_AFTER = 0;
    private final int blockSuccessorCount;

    @Override
    protected int inputCount() {
        return super.inputCount() + INPUT_COUNT;
    }

    @Override
    protected int successorCount() {
        return super.successorCount() + blockSuccessorCount + SUCCESSOR_COUNT;
    }

    /**
     * The list of instructions that produce input for this instruction.
     */
    public Instruction blockSuccessor(int index) {
        assert index >= 0 && index < blockSuccessorCount;
        return (Instruction) successors().get(super.successorCount() + SUCCESSOR_COUNT + index);
    }

    public Instruction setBlockSuccessor(int index, Instruction n) {
        assert index >= 0 && index < blockSuccessorCount;
        assert n == null || n instanceof BlockBegin : "only BlockBegins, for now... " + n.getClass();
        return (BlockBegin) successors().set(super.successorCount() + SUCCESSOR_COUNT + index, n);
    }

    public int blockSuccessorCount() {
        return blockSuccessorCount;
    }

    /**
     * Constructs a new block end with the specified value type.
     * @param kind the type of the value produced by this instruction
     * @param successors the list of successor blocks. If {@code null}, a new one will be created.
     */
    public BlockEnd(CiKind kind, List<? extends Instruction> blockSuccessors, int inputCount, int successorCount, Graph graph) {
        this(kind, blockSuccessors.size(), inputCount, successorCount, graph);
        for (int i = 0; i < blockSuccessors.size(); i++) {
            setBlockSuccessor(i, blockSuccessors.get(i));
        }
    }

    public BlockEnd(CiKind kind, int blockSuccessorCount, int inputCount, int successorCount, Graph graph) {
        super(kind, inputCount + INPUT_COUNT, successorCount + blockSuccessorCount + SUCCESSOR_COUNT, graph);
        this.blockSuccessorCount = blockSuccessorCount;
    }

    public BlockEnd(CiKind kind, Graph graph) {
        this(kind, 2, 0, 0, graph);
    }

    /**
     * Gets the block begin associated with this block end.
     * @return the beginning of this basic block
     */
    public BlockBegin begin() {
        for (Node n : predecessors()) {
            if (n instanceof BlockBegin) {
                return (BlockBegin) n;
            }
        }
        return null;
    }

    /**
     * Substitutes a successor block in this block end's successor list. Note that
     * this method updates all occurrences in the list.
     * @param oldSucc the old successor to replace
     * @param newSucc the new successor
     */
    public int substituteSuccessor(BlockBegin oldSucc, BlockBegin newSucc) {
        assert newSucc != null;
        int count = 0;
        for (int i = 0; i < blockSuccessorCount; i++) {
            if (blockSuccessor(i) == oldSucc) {
                setBlockSuccessor(i, newSucc);
                count++;
            }
        }
        return count;
    }

    /**
     * Gets the successor corresponding to the default (fall through) case.
     * @return the default successor
     */
    public Instruction defaultSuccessor() {
        return blockSuccessor(blockSuccessorCount - 1);
    }

    /**
     * Searches for the specified successor and returns its index into the
     * successor list if found.
     * @param b the block to search for in the successor list
     * @return the index of the block in the list if found; <code>-1</code> otherwise
     */
    public int successorIndex(BlockBegin b) {
        for (int i = 0; i < blockSuccessorCount; i++) {
            if (blockSuccessor(i) == b) {
                return i;
            }
        }
        return -1;
    }

    /**
     * This method reorders the predecessors of the i-th successor in such a way that this BlockEnd is at position backEdgeIndex.
     */
    public void reorderSuccessor(int i, int backEdgeIndex) {
        assert i >= 0 && i < blockSuccessorCount;
        Instruction successor = blockSuccessor(i);
        if (successor != null) {
            successors().set(super.successorCount() + SUCCESSOR_COUNT + i, Node.Null);
            successors().set(super.successorCount() + SUCCESSOR_COUNT + i, successor, backEdgeIndex);
        }
    }

    /**
     * Gets this block end's list of successors.
     * @return the successor list
     */
    @SuppressWarnings({ "unchecked", "rawtypes"})
    public List<BlockBegin> blockSuccessors() {
        List<BlockBegin> list = (List) successors().subList(super.successorCount() + SUCCESSOR_COUNT, super.successorCount() + blockSuccessorCount + SUCCESSOR_COUNT);
        return Collections.unmodifiableList(list);
    }

    public void clearSuccessors() {
        for (int i = 0; i < blockSuccessorCount(); i++) {
            setBlockSuccessor(i, null);
        }
    }

}
