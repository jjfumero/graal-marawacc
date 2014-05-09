/*
 * Copyright (c) 2011, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.phases.graph;

import java.util.*;

import com.oracle.graal.graph.*;
import com.oracle.graal.graph.util.*;
import com.oracle.graal.nodes.*;

/**
 * A SinglePassNodeIterator iterates the fixed nodes of the graph in post order starting from its
 * start node. Unlike in iterative dataflow analysis, a single pass is performed, which allows
 * keeping a smaller working set of pending {@link MergeableState}. This iteration scheme requires:
 * <ul>
 * <li>{@link MergeableState#merge(MergeNode, List)} to always return <code>true</code> (an
 * assertion checks this)</li>
 * <li>{@link #controlSplit(ControlSplitNode)} to always return all successors (otherwise, not all
 * associated {@link EndNode} will be visited. In turn, visiting all the end nodes for a given
 * {@link MergeNode} is a precondition before that merge node can be visited)</li>
 * </ul>
 *
 * <p>
 * For this iterator the CFG is defined by the classical CFG nodes (
 * {@link com.oracle.graal.nodes.ControlSplitNode}, {@link com.oracle.graal.nodes.MergeNode}...) and
 * the {@link com.oracle.graal.nodes.FixedWithNextNode#next() next} pointers of
 * {@link com.oracle.graal.nodes.FixedWithNextNode}.
 * </p>
 *
 * <p>
 * The lifecycle that single-pass node iterators go through is described in {@link #apply()}
 * </p>
 *
 * @param <T> the type of {@link MergeableState} handled by this SinglePassNodeIterator
 */
public abstract class SinglePassNodeIterator<T extends MergeableState<T>> {

    private final NodeBitMap visitedEnds;
    private final Deque<QElem<T>> nodeQueue;
    private final Map<FixedNode, T> nodeStates;
    private final StartNode start;

    protected T state;

    /**
     * An item queued in {@link #nodeQueue} can be used to continue with the single-pass visit after
     * the previous path can't be followed anymore. Such items are:
     * <ul>
     * <li>de-queued via {@link #nextQueuedNode()}</li>
     * <li>en-queued via {@link #queueMerge(EndNode)} and {@link #queueSuccessors(FixedNode)}</li>
     * </ul>
     *
     * <p>
     * Correspondingly each item may stand for:
     * <ul>
     * <li>a {@link MergeNode} whose pre-state results from merging those of its forward-ends, see
     * {@link #nextQueuedNode()}</li>
     * <li>the successor of a control-split node, in which case the pre-state of the successor in
     * question is also stored in the item, see {@link #nextQueuedNode()}</li>
     * </ul>
     * </p>
     */
    private static class QElem<U> {
        private final FixedNode node;
        private final U preState;

        private QElem(FixedNode node, U preState) {
            this.node = node;
            this.preState = preState;
            assert repOK();
        }

        private boolean repOK() {
            return (node instanceof MergeNode && preState == null) || (node instanceof BeginNode && preState != null);
        }
    }

    public SinglePassNodeIterator(StartNode start, T initialState) {
        StructuredGraph graph = start.graph();
        visitedEnds = graph.createNodeBitMap();
        nodeQueue = new ArrayDeque<>();
        nodeStates = CollectionsAccess.newNodeIdentityMap();
        this.start = start;
        this.state = initialState;
    }

    /**
     * Performs a single-pass iteration.
     *
     * <p>
     * After this method has been invoked, the {@link SinglePassNodeIterator} instance can't be used
     * again. This saves clearing up fields in {@link #finished()}, the assumption being that this
     * instance will be garbage-collected soon afterwards.
     * </p>
     */
    public void apply() {
        FixedNode current = start;

        do {
            if (current instanceof InvokeWithExceptionNode) {
                invoke((Invoke) current);
                queueSuccessors(current);
                current = nextQueuedNode();
            } else if (current instanceof LoopBeginNode) {
                state.loopBegin((LoopBeginNode) current);
                keepForLater(current, state);
                state = state.clone();
                loopBegin((LoopBeginNode) current);
                current = ((LoopBeginNode) current).next();
                assert current != null;
            } else if (current instanceof LoopEndNode) {
                loopEnd((LoopEndNode) current);
                finishLoopEnds((LoopEndNode) current);
                current = nextQueuedNode();
            } else if (current instanceof MergeNode) {
                merge((MergeNode) current);
                current = ((MergeNode) current).next();
                assert current != null;
            } else if (current instanceof FixedWithNextNode) {
                FixedNode next = ((FixedWithNextNode) current).next();
                assert next != null : current;
                node(current);
                current = next;
            } else if (current instanceof EndNode) {
                end((EndNode) current);
                queueMerge((EndNode) current);
                current = nextQueuedNode();
            } else if (current instanceof ControlSinkNode) {
                node(current);
                current = nextQueuedNode();
            } else if (current instanceof ControlSplitNode) {
                controlSplit((ControlSplitNode) current);
                queueSuccessors(current);
                current = nextQueuedNode();
            } else {
                assert false : current;
            }
        } while (current != null);
        finished();
    }

    private void queueSuccessors(FixedNode x) {
        for (Node node : x.successors()) {
            if (node != null) {
                nodeQueue.addFirst(new QElem<>((BeginNode) node, state));
            }
        }
    }

    /**
     * This method is invoked upon not having a (single) next {@link FixedNode} to visit. This
     * method picks such next-node-to-visit from {@link #nodeQueue} and updates {@link #state} with
     * the pre-state for that node.
     *
     * <p>
     * Upon reaching a {@link MergeNode}, some entries are pruned from {@link #nodeStates} (ie, the
     * entries associated to forward-ends for that merge-node).
     * </p>
     */
    private FixedNode nextQueuedNode() {
        if (nodeQueue.isEmpty()) {
            return null;
        }
        QElem<T> elem = nodeQueue.removeFirst();
        if (elem.node instanceof MergeNode) {
            MergeNode merge = (MergeNode) elem.node;
            state = pruneEntry(merge.forwardEndAt(0)).clone();
            ArrayList<T> states = new ArrayList<>(merge.forwardEndCount() - 1);
            for (int i = 1; i < merge.forwardEndCount(); i++) {
                T other = pruneEntry(merge.forwardEndAt(i));
                states.add(other);
            }
            boolean ready = state.merge(merge, states);
            assert ready : "Not a single-pass iterator after all";
            return merge;
        } else {
            BeginNode begin = (BeginNode) elem.node;
            assert begin.predecessor() != null;
            state = elem.preState.clone();
            state.afterSplit(begin);
            return begin;
        }
    }

    /**
     * Once all loop-end-nodes for a given loop-node have been visited:
     * <ul>
     * <li>the state for that loop-node is updated based on the states of the loop-end-nodes</li>
     * <li>entries in {@link #nodeStates} are pruned for the loop (they aren't going to be looked up
     * again, anyway)</li>
     * </ul>
     */
    private void finishLoopEnds(LoopEndNode end) {
        assert !visitedEnds.isMarked(end);
        visitedEnds.mark(end);
        keepForLater(end, state);
        LoopBeginNode begin = end.loopBegin();
        boolean endsVisited = true;
        for (LoopEndNode le : begin.loopEnds()) {
            if (!visitedEnds.isMarked(le)) {
                endsVisited = false;
                break;
            }
        }
        if (endsVisited) {
            ArrayList<T> states = new ArrayList<>(begin.loopEnds().count());
            for (LoopEndNode le : begin.orderedLoopEnds()) {
                T leState = pruneEntry(le);
                states.add(leState);
            }
            T loopBeginState = pruneEntry(begin);
            loopBeginState.loopEnds(begin, states);
        }
    }

    /**
     * Once all end-nodes for a given merge-node have been visited, that merge-node is added to the
     * {@link #nodeQueue}
     *
     * <p>
     * {@link #nextQueuedNode()} is in charge of pruning entries for the states of forward-ends
     * inserted by this method.
     * </p>
     */
    private void queueMerge(EndNode end) {
        assert !visitedEnds.isMarked(end);
        visitedEnds.mark(end);
        keepForLater(end, state);
        MergeNode merge = end.merge();
        boolean endsVisited = true;
        for (int i = 0; i < merge.forwardEndCount(); i++) {
            if (!visitedEnds.isMarked(merge.forwardEndAt(i))) {
                endsVisited = false;
                break;
            }
        }
        if (endsVisited) {
            nodeQueue.add(new QElem<>(merge, null));
        }
    }

    protected abstract void node(FixedNode node);

    protected void end(EndNode endNode) {
        node(endNode);
    }

    protected void merge(MergeNode merge) {
        node(merge);
    }

    protected void loopBegin(LoopBeginNode loopBegin) {
        node(loopBegin);
    }

    protected void loopEnd(LoopEndNode loopEnd) {
        node(loopEnd);
    }

    protected void controlSplit(ControlSplitNode controlSplit) {
        node(controlSplit);
    }

    protected void invoke(Invoke invoke) {
        node(invoke.asNode());
    }

    /**
     * The lifecycle that single-pass node iterators go through is described in {@link #apply()}
     */
    protected void finished() {
        assert nodeQueue.isEmpty();
        assert nodeStates.isEmpty();
    }

    private void keepForLater(FixedNode x, T s) {
        assert !nodeStates.containsKey(x);
        nodeStates.put(x, s);
    }

    private T pruneEntry(FixedNode x) {
        T result = nodeStates.remove(x);
        assert result != null;
        return result;
    }
}
