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
package com.oracle.max.graal.compiler.ir;

import java.util.*;

import com.oracle.max.graal.graph.*;
import com.sun.cri.ci.*;

/**
 * The {@code BlockEnd} instruction is a base class for all instructions that end a basic
 * block, including branches, switches, throws, and goto's.
 */
public abstract class ControlSplit extends FixedNode {

    private static final int INPUT_COUNT = 0;

    private static final int SUCCESSOR_COUNT = 0;
    private final int blockSuccessorCount;

    @Override
    protected int inputCount() {
        return super.inputCount() + INPUT_COUNT;
    }

    @Override
    protected int successorCount() {
        return super.successorCount() + blockSuccessorCount + SUCCESSOR_COUNT;
    }

    public FixedNode blockSuccessor(int index) {
        assert index >= 0 && index < blockSuccessorCount;
        return (FixedNode) successors().get(super.successorCount() + SUCCESSOR_COUNT + index);
    }

    public FixedNode setBlockSuccessor(int index, FixedNode n) {
        assert index >= 0 && index < blockSuccessorCount;
        return (FixedNode) successors().set(super.successorCount() + SUCCESSOR_COUNT + index, n);
    }

    public int blockSuccessorCount() {
        return blockSuccessorCount;
    }

    /**
     * Constructs a new block end with the specified value type.
     * @param kind the type of the value produced by this instruction
     * @param successors the list of successor blocks. If {@code null}, a new one will be created.
     */
    public ControlSplit(CiKind kind, List<? extends FixedNode> blockSuccessors, int inputCount, int successorCount, Graph graph) {
        this(kind, blockSuccessors.size(), inputCount, successorCount, graph);
        for (int i = 0; i < blockSuccessors.size(); i++) {
            setBlockSuccessor(i, blockSuccessors.get(i));
        }
    }

    public ControlSplit(CiKind kind, int blockSuccessorCount, int inputCount, int successorCount, Graph graph) {
        super(kind, inputCount + INPUT_COUNT, successorCount + blockSuccessorCount + SUCCESSOR_COUNT, graph);
        this.blockSuccessorCount = blockSuccessorCount;
    }

    /**
     * Gets the successor corresponding to the default (fall through) case.
     * @return the default successor
     */
    public FixedNode defaultSuccessor() {
        return blockSuccessor(blockSuccessorCount - 1);
    }

    public Iterable<FixedNode> blockSuccessors() {
        return new Iterable<FixedNode>() {
            @Override
            public Iterator<FixedNode> iterator() {
                return new Iterator<FixedNode>() {
                    int i = 0;
                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                    @Override
                    public FixedNode next() {
                        return ControlSplit.this.blockSuccessor(i++);
                    }

                    @Override
                    public boolean hasNext() {
                        return i < ControlSplit.this.blockSuccessorCount;
                    }
                };
            }
        };
    }
}
