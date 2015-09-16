/*
 * Copyright (c) 2011, 2012, Oracle and/or its affiliates. All rights reserved.
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.NodePosIterator;
import com.oracle.graal.nodes.AbstractBeginNode;
import com.oracle.graal.nodes.AbstractEndNode;
import com.oracle.graal.nodes.AbstractMergeNode;
import com.oracle.graal.nodes.EndNode;
import com.oracle.graal.nodes.FixedNode;
import com.oracle.graal.nodes.FixedWithNextNode;
import com.oracle.graal.nodes.LoopBeginNode;
import com.oracle.graal.nodes.LoopEndNode;
import com.oracle.graal.nodes.LoopExitNode;

public final class ReentrantNodeIterator {

    public static class LoopInfo<StateT> {

        public final Map<LoopEndNode, StateT> endStates;
        public final Map<LoopExitNode, StateT> exitStates;

        public LoopInfo(int endCount, int exitCount) {
            endStates = Node.newIdentityMap(endCount);
            exitStates = Node.newIdentityMap(exitCount);
        }
    }

    public abstract static class NodeIteratorClosure<StateT> {

        protected abstract StateT processNode(FixedNode node, StateT currentState);

        protected abstract StateT merge(AbstractMergeNode merge, List<StateT> states);

        protected abstract StateT afterSplit(AbstractBeginNode node, StateT oldState);

        protected abstract Map<LoopExitNode, StateT> processLoop(LoopBeginNode loop, StateT initialState);

        /**
         * Determine whether iteration should continue in the current state.
         *
         * @param currentState
         */
        protected boolean continueIteration(StateT currentState) {
            return true;
        }
    }

    private ReentrantNodeIterator() {
        // no instances allowed
    }

    public static <StateT> LoopInfo<StateT> processLoop(NodeIteratorClosure<StateT> closure, LoopBeginNode loop, StateT initialState) {
        Map<FixedNode, StateT> blockEndStates = apply(closure, loop, initialState, loop);

        LoopInfo<StateT> info = new LoopInfo<>(loop.loopEnds().count(), loop.loopExits().count());
        for (LoopEndNode end : loop.loopEnds()) {
            if (blockEndStates.containsKey(end)) {
                info.endStates.put(end, blockEndStates.get(end));
            }
        }
        for (LoopExitNode exit : loop.loopExits()) {
            if (blockEndStates.containsKey(exit)) {
                info.exitStates.put(exit, blockEndStates.get(exit));
            }
        }
        return info;
    }

    public static <StateT> void apply(NodeIteratorClosure<StateT> closure, FixedNode start, StateT initialState) {
        apply(closure, start, initialState, null);
    }

    private static <StateT> Map<FixedNode, StateT> apply(NodeIteratorClosure<StateT> closure, FixedNode start, StateT initialState, LoopBeginNode boundary) {
        assert start != null;
        Deque<AbstractBeginNode> nodeQueue = new ArrayDeque<>();
        Map<FixedNode, StateT> blockEndStates = Node.newIdentityMap();

        StateT state = initialState;
        FixedNode current = start;
        do {
            while (current instanceof FixedWithNextNode) {
                if (boundary != null && current instanceof LoopExitNode && ((LoopExitNode) current).loopBegin() == boundary) {
                    blockEndStates.put(current, state);
                    current = null;
                } else {
                    FixedNode next = ((FixedWithNextNode) current).next();
                    state = closure.processNode(current, state);
                    current = closure.continueIteration(state) ? next : null;
                }
            }

            if (current != null) {
                state = closure.processNode(current, state);

                if (closure.continueIteration(state)) {
                    NodePosIterator successors = current.successors().iterator();
                    if (!successors.hasNext()) {
                        if (current instanceof LoopEndNode) {
                            blockEndStates.put(current, state);
                        } else if (current instanceof EndNode) {
                            // add the end node and see if the merge is ready for processing
                            AbstractMergeNode merge = ((EndNode) current).merge();
                            if (merge instanceof LoopBeginNode) {
                                Map<LoopExitNode, StateT> loopExitState = closure.processLoop((LoopBeginNode) merge, state);
                                for (Map.Entry<LoopExitNode, StateT> entry : loopExitState.entrySet()) {
                                    blockEndStates.put(entry.getKey(), entry.getValue());
                                    nodeQueue.add(entry.getKey());
                                }
                            } else {
                                boolean endsVisited = true;
                                for (AbstractEndNode forwardEnd : merge.forwardEnds()) {
                                    if (forwardEnd != current && !blockEndStates.containsKey(forwardEnd)) {
                                        endsVisited = false;
                                        break;
                                    }
                                }
                                if (endsVisited) {
                                    ArrayList<StateT> states = new ArrayList<>(merge.forwardEndCount());
                                    for (int i = 0; i < merge.forwardEndCount(); i++) {
                                        AbstractEndNode forwardEnd = merge.forwardEndAt(i);
                                        assert forwardEnd == current || blockEndStates.containsKey(forwardEnd);
                                        StateT other = forwardEnd == current ? state : blockEndStates.remove(forwardEnd);
                                        states.add(other);
                                    }
                                    state = closure.merge(merge, states);
                                    current = closure.continueIteration(state) ? merge : null;
                                    continue;
                                } else {
                                    assert !blockEndStates.containsKey(current);
                                    blockEndStates.put(current, state);
                                }
                            }
                        }
                    } else {
                        FixedNode firstSuccessor = (FixedNode) successors.next();
                        if (!successors.hasNext()) {
                            current = firstSuccessor;
                            continue;
                        } else {
                            do {
                                AbstractBeginNode successor = (AbstractBeginNode) successors.next();
                                StateT successorState = closure.afterSplit(successor, state);
                                if (closure.continueIteration(successorState)) {
                                    blockEndStates.put(successor, successorState);
                                    nodeQueue.add(successor);
                                }
                            } while (successors.hasNext());

                            state = closure.afterSplit((AbstractBeginNode) firstSuccessor, state);
                            current = closure.continueIteration(state) ? firstSuccessor : null;
                            continue;
                        }
                    }
                }
            }

            // get next queued block
            if (nodeQueue.isEmpty()) {
                return blockEndStates;
            } else {
                current = nodeQueue.removeFirst();
                assert blockEndStates.containsKey(current);
                state = blockEndStates.remove(current);
                assert !(current instanceof AbstractMergeNode) && current instanceof AbstractBeginNode;
            }
        } while (true);
    }
}
