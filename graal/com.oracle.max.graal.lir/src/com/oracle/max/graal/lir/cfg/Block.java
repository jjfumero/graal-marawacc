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
package com.oracle.max.graal.lir.cfg;

import java.util.*;

import com.oracle.max.graal.graph.*;
import com.oracle.max.graal.lir.*;
import com.oracle.max.graal.nodes.*;
import com.oracle.max.graal.nodes.java.*;

public class Block {
    protected int id;

    protected BeginNode beginNode;
    protected Node endNode;
    protected Loop loop;
    protected double probability;

    protected List<Block> predecessors;
    protected List<Block> successors;

    protected Block dominator;
    protected List<Block> dominated;
    protected Block postdominator;

    // Fields that still need to be worked on, try to remove them later.
    public List<LIRInstruction> lir;
    public boolean align;
    public int linearScanNumber;

    public Block() {
        id = ControlFlowGraph.BLOCK_ID_INITIAL;
    }

    public int getId() {
        assert id >= 0;
        return id;
    }

    public BeginNode getBeginNode() {
        return beginNode;
    }

    public Node getEndNode() {
        return endNode;
    }

    public Loop getLoop() {
        return loop;
    }

    public int getLoopDepth() {
        return loop == null ? 0 : loop.depth;
    }

    public boolean isLoopHeader() {
        return getBeginNode() instanceof LoopBeginNode;
    }

    public boolean isLoopEnd() {
        return getEndNode() instanceof LoopEndNode;
    }

    public boolean isExceptionEntry() {
        return getBeginNode().next() instanceof ExceptionObjectNode;
    }

    public List<Block> getPredecessors() {
        return predecessors;
    }

    public List<Block> getSuccessors() {
        return successors;
    }

    public Block getDominator() {
        return dominator;
    }

    public Block getEarliestPostDominated() {
        Block b = this;
        while (true) {
            Block dom = b.getDominator();
            if (dom != null && dom.getPostdominator() == b) {
                b = dom;
            } else {
                break;
            }
        }
        return b;
    }

    public List<Block> getDominated() {
        if (dominated == null) {
            return Collections.emptyList();
        }
        return dominated;
    }

    public Block getPostdominator() {
        return postdominator;
    }

    private class NodeIterator implements Iterator<Node> {
        private Node cur;

        public NodeIterator() {
            cur = getBeginNode();
        }

        @Override
        public boolean hasNext() {
            return cur != null;
        }

        @Override
        public Node next() {
            Node result = cur;
            if (cur == getEndNode()) {
                cur = null;
            } else {
                cur = ((FixedWithNextNode) cur).next();
            }
            assert !(cur instanceof BeginNode);
            return result;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    public Iterable<Node> getNodes() {
        return new Iterable<Node>() {
            @Override
            public Iterator<Node> iterator() {
                return new NodeIterator();
            }
        };
    }

    public int getFirstLirInstructionId() {
        int result = lir.get(0).id();
        assert result >= 0;
        return result;
    }

    public int getLastLirInstructionId() {
        int result = lir.get(lir.size() - 1).id();
        assert result >= 0;
        return result;
    }

    @Override
    public String toString() {
        return "B" + id;
    }


// to be inlined later on
    public int numberOfPreds() {
        return getPredecessors().size();
    }

    public int numberOfSux() {
        return getSuccessors().size();
    }

    public Block predAt(int i) {
        return getPredecessors().get(i);
    }

    public Block suxAt(int i) {
        return getSuccessors().get(i);
    }
// end to be inlined later on
}
