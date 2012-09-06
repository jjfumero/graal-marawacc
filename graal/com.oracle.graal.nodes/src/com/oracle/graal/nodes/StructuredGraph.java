/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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
import java.util.concurrent.atomic.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.util.*;


/**
 * A graph that contains at least one distinguished node : the {@link #start() start} node.
 * This node is the start of the control flow of the graph.
 */
public class StructuredGraph extends Graph {

    public static final long INVALID_GRAPH_ID = -1;
    private static final AtomicLong uniqueGraphIds = new AtomicLong();
    private final StartNode start;
    private final ResolvedJavaMethod method;
    private final long graphId;

    /**
     * Creates a new Graph containing a single {@link BeginNode} as the {@link #start() start} node.
     */
    public StructuredGraph() {
        this(null, null);
    }

    /**
     * Creates a new Graph containing a single {@link BeginNode} as the {@link #start() start} node.
     */
    public StructuredGraph(String name) {
        this(name, null);
    }

    public StructuredGraph(String name, ResolvedJavaMethod method) {
        this(name, method, uniqueGraphIds.incrementAndGet());
    }

    private StructuredGraph(String name, ResolvedJavaMethod method, long graphId) {
        super(name);
        this.start = add(new StartNode());
        this.method = method;
        this.graphId = graphId;
    }

    public StructuredGraph(ResolvedJavaMethod method) {
        this(null, method);
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(getClass().getSimpleName() + ":" + graphId);
        String sep = "{";
        if (name != null) {
            buf.append(sep);
            buf.append(name);
            sep = ", ";
        }
        if (method != null) {
            buf.append(sep);
            buf.append(method);
            sep = ", ";
        }

        if (!sep.equals("{")) {
            buf.append("}");
        }
        return buf.toString();
    }

    public StartNode start() {
        return start;
    }

    public ResolvedJavaMethod method() {
        return method;
    }

    public long graphId() {
        return graphId;
    }

    @Override
    public StructuredGraph copy() {
        return copy(name);
    }

    @Override
    public StructuredGraph copy(String newName) {
        StructuredGraph copy = new StructuredGraph(newName, method, graphId);
        HashMap<Node, Node> replacements = new HashMap<>();
        replacements.put(start, copy.start);
        copy.addDuplicates(getNodes(), replacements);
        return copy;
    }

    public LocalNode getLocal(int index) {
        for (LocalNode local : getNodes(LocalNode.class)) {
            if (local.index() == index) {
                return local;
            }
        }
        return null;
    }

    public Iterable<Invoke> getInvokes() {
        final Iterator<MethodCallTargetNode> callTargets = getNodes(MethodCallTargetNode.class).iterator();
        return new Iterable<Invoke>() {
            private Invoke next;

            @Override
            public Iterator<Invoke> iterator() {
                return new Iterator<Invoke>() {

                    @Override
                    public boolean hasNext() {
                        if (next == null) {
                            while (callTargets.hasNext()) {
                                Invoke i = callTargets.next().invoke();
                                if (i != null) {
                                    next = i;
                                    return true;
                                }
                            }
                            return false;
                        } else {
                            return true;
                        }
                    }

                    @Override
                    public Invoke next() {
                        try {
                            return next;
                        } finally {
                            next = null;
                        }
                    }

                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }

    public boolean hasLoops() {
        return getNodes(LoopBeginNode.class).isNotEmpty();
    }

    public void removeFloating(FloatingNode node) {
        assert node != null && node.isAlive() : "cannot remove " + node;
        node.safeDelete();
    }

    public void replaceFloating(FloatingNode node, ValueNode replacement) {
        assert node != null && replacement != null && node.isAlive() && replacement.isAlive() : "cannot replace " + node + " with " + replacement;
        node.replaceAtUsages(replacement);
        node.safeDelete();
    }

    /**
     * Unlinks a node from all its control flow neighbours and then removes it from its graph.
     * The node must have no {@linkplain Node#usages() usages}.
     *
     * @param node the node to be unlinked and removed
     */
    public void removeFixed(FixedWithNextNode node) {
        assert node != null;
        if (node instanceof BeginNode) {
            ((BeginNode) node).prepareDelete();
        }
        assert node.usages().isEmpty() : node + " " + node.usages();
        FixedNode next = node.next();
        node.setNext(null);
        node.replaceAtPredecessor(next);
        node.safeDelete();
    }

    public void replaceFixed(FixedWithNextNode node, Node replacement) {
        if (replacement instanceof FixedWithNextNode) {
            replaceFixedWithFixed(node, (FixedWithNextNode) replacement);
        } else {
            assert replacement != null : "cannot replace " + node + " with null";
            assert replacement instanceof FloatingNode : "cannot replace " + node + " with " + replacement;
            replaceFixedWithFloating(node, (FloatingNode) replacement);
        }
    }

    public void replaceFixedWithFixed(FixedWithNextNode node, FixedWithNextNode replacement) {
        assert node != null && replacement != null && node.isAlive() && replacement.isAlive() : "cannot replace " + node + " with " + replacement;
        replacement.setProbability(node.probability());
        FixedNode next = node.next();
        node.setNext(null);
        replacement.setNext(next);
        node.replaceAndDelete(replacement);
    }

    public void replaceFixedWithFloating(FixedWithNextNode node, FloatingNode replacement) {
        assert node != null && replacement != null && node.isAlive() && replacement.isAlive() : "cannot replace " + node + " with " + replacement;
        FixedNode next = node.next();
        node.setNext(null);
        node.replaceAtPredecessor(next);
        node.replaceAtUsages(replacement);
        node.safeDelete();
    }

    public void removeSplit(ControlSplitNode node, int survivingSuccessor) {
        assert node != null;
        assert node.usages().isEmpty();
        assert survivingSuccessor >= 0 && survivingSuccessor < node.blockSuccessorCount() : "invalid surviving successor " + survivingSuccessor + " for " + node;
        BeginNode begin = node.blockSuccessor(survivingSuccessor);
        for (int i = 0; i < node.blockSuccessorCount(); i++) {
            node.setBlockSuccessor(i, null);
        }
        node.replaceAtPredecessor(begin);
        node.safeDelete();
    }

    public void removeSplitPropagate(ControlSplitNode node, int survivingSuccessor) {
        assert node != null;
        assert node.usages().isEmpty();
        assert survivingSuccessor >= 0 && survivingSuccessor < node.blockSuccessorCount() : "invalid surviving successor " + survivingSuccessor + " for " + node;
        BeginNode begin = node.blockSuccessor(survivingSuccessor);
        for (int i = 0; i < node.blockSuccessorCount(); i++) {
            BeginNode successor = node.blockSuccessor(i);
            node.setBlockSuccessor(i, null);
            if (successor != null && successor != begin && successor.isAlive()) {
                GraphUtil.killCFG(successor);
            }
        }
        if (begin.isAlive()) {
            node.replaceAtPredecessor(begin);
            node.safeDelete();
        } else {
            assert node.isDeleted() : node + " " + begin;
        }
    }

    public void replaceSplit(ControlSplitNode node, Node replacement, int survivingSuccessor) {
        if (replacement instanceof FixedWithNextNode) {
            replaceSplitWithFixed(node, (FixedWithNextNode) replacement, survivingSuccessor);
        } else {
            assert replacement != null : "cannot replace " + node + " with null";
            assert replacement instanceof FloatingNode : "cannot replace " + node + " with " + replacement;
            replaceSplitWithFloating(node, (FloatingNode) replacement, survivingSuccessor);
        }
    }

    public void replaceSplitWithFixed(ControlSplitNode node, FixedWithNextNode replacement, int survivingSuccessor) {
        assert node != null && replacement != null && node.isAlive() && replacement.isAlive() : "cannot replace " + node + " with " + replacement;
        assert survivingSuccessor >= 0 && survivingSuccessor < node.blockSuccessorCount() : "invalid surviving successor " + survivingSuccessor + " for " + node;
        BeginNode begin = node.blockSuccessor(survivingSuccessor);
        for (int i = 0; i < node.blockSuccessorCount(); i++) {
            node.setBlockSuccessor(i, null);
        }
        replacement.setNext(begin);
        node.replaceAndDelete(replacement);
    }

    public void replaceSplitWithFloating(ControlSplitNode node, FloatingNode replacement, int survivingSuccessor) {
        assert node != null && replacement != null && node.isAlive() && replacement.isAlive() : "cannot replace " + node + " with " + replacement;
        assert survivingSuccessor >= 0 && survivingSuccessor < node.blockSuccessorCount() : "invalid surviving successor " + survivingSuccessor + " for " + node;
        BeginNode begin = node.blockSuccessor(survivingSuccessor);
        for (int i = 0; i < node.blockSuccessorCount(); i++) {
            node.setBlockSuccessor(i, null);
        }
        node.replaceAtPredecessor(begin);
        node.replaceAtUsages(replacement);
        node.safeDelete();
    }

    public void addAfterFixed(FixedWithNextNode node, FixedNode newNode) {
        assert node != null && newNode != null && node.isAlive() && newNode.isAlive() : "cannot add " + newNode + " after " + node;
        newNode.setProbability(node.probability());
        FixedNode next = node.next();
        node.setNext(newNode);
        if (next != null) {
            assert newNode instanceof FixedWithNextNode;
            FixedWithNextNode newFixedWithNext = (FixedWithNextNode) newNode;
            assert newFixedWithNext.next() == null;
            newFixedWithNext.setNext(next);
        }
    }

    public void addBeforeFixed(FixedNode node, FixedWithNextNode newNode) {
        assert node != null && newNode != null && node.isAlive() && newNode.isAlive() : "cannot add " + newNode + " before " + node;
        assert node.predecessor() != null && node.predecessor() instanceof FixedWithNextNode : "cannot add " + newNode + " before " + node;
        assert newNode.next() == null;
        newNode.setProbability(node.probability());
        FixedWithNextNode pred = (FixedWithNextNode) node.predecessor();
        pred.setNext(newNode);
        newNode.setNext(node);
    }

    public void reduceDegenerateLoopBegin(LoopBeginNode begin) {
        assert begin.loopEnds().isEmpty() : "Loop begin still has backedges";
        if (begin.forwardEndCount() == 1) { // bypass merge and remove
            reduceTrivialMerge(begin);
        } else { // convert to merge
            MergeNode merge = this.add(new MergeNode());
            merge.setProbability(begin.probability());
            this.replaceFixedWithFixed(begin, merge);
        }
    }

    public void reduceTrivialMerge(MergeNode merge) {
        assert merge.forwardEndCount() == 1;
        assert !(merge instanceof LoopBeginNode) || ((LoopBeginNode) merge).loopEnds().isEmpty();
        for (PhiNode phi : merge.phis().snapshot()) {
            assert phi.valueCount() == 1;
            ValueNode singleValue = phi.valueAt(0);
            phi.replaceAtUsages(singleValue);
            phi.safeDelete();
        }
        EndNode singleEnd = merge.forwardEndAt(0);
        FixedNode sux = merge.next();
        FrameState stateAfter = merge.stateAfter();
        // remove loop exits
        if (merge instanceof LoopBeginNode) {
            ((LoopBeginNode) merge).removeExits();
        }
        // evacuateGuards
        merge.prepareDelete((FixedNode) singleEnd.predecessor());
        merge.safeDelete();
        if (stateAfter != null && stateAfter.isAlive() && stateAfter.usages().isEmpty()) {
            GraphUtil.killWithUnusedFloatingInputs(stateAfter);
        }
        if (sux == null) {
            singleEnd.replaceAtPredecessor(null);
            singleEnd.safeDelete();
        } else {
            singleEnd.replaceAndDelete(sux);
        }
    }
}
