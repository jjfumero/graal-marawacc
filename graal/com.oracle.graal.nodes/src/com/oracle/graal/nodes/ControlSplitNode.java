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
package com.oracle.graal.nodes;

import java.util.*;

import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.type.*;

/**
 * The {@code ControlSplitNode} is a base class for all instructions that split the control flow (ie. have more than one successor).
 */
public abstract class ControlSplitNode extends FixedNode {
    @Successor private final NodeSuccessorList<BeginNode> blockSuccessors;
    protected double[] branchProbability;

    public BeginNode blockSuccessor(int index) {
        return blockSuccessors.get(index);
    }

    public void setBlockSuccessor(int index, BeginNode x) {
        blockSuccessors.set(index, x);
    }

    public int blockSuccessorCount() {
        return blockSuccessors.size();
    }

    public ControlSplitNode(Stamp stamp, BeginNode[] blockSuccessors, double[] branchProbability) {
        super(stamp);
        assert branchProbability.length == blockSuccessors.length;
        this.blockSuccessors = new NodeSuccessorList<>(this, blockSuccessors);
        this.branchProbability = branchProbability;
    }

    public double probability(int successorIndex) {
        return branchProbability[successorIndex];
    }

    public void setProbability(int successorIndex, double x) {
        branchProbability[successorIndex] = x;
    }

    public Iterable<BeginNode> blockSuccessors() {
        return new Iterable<BeginNode>() {
            @Override
            public Iterator<BeginNode> iterator() {
                return new Iterator<BeginNode>() {
                    int i = 0;
                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                    @Override
                    public BeginNode next() {
                        return ControlSplitNode.this.blockSuccessor(i++);
                    }

                    @Override
                    public boolean hasNext() {
                        return i < ControlSplitNode.this.blockSuccessorCount();
                    }
                };
            }
        };
    }

    public int blockSuccessorIndex(BeginNode successor) {
        int idx = blockSuccessors.indexOf(successor);
        if (idx < 0) {
            throw new IllegalArgumentException();
        }
        return idx;
    }

    @Override
    public ControlSplitNode clone(Graph into) {
        ControlSplitNode csn = (ControlSplitNode) super.clone(into);
        csn.branchProbability = Arrays.copyOf(branchProbability, branchProbability.length);
        return csn;
    }
}
