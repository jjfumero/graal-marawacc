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

import java.util.*;

import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.cfg.*;

public final class ReentrantBlockIterator {

    public static class LoopInfo<StateT> {

        public final List<StateT> endStates = new ArrayList<>();
        public final List<StateT> exitStates = new ArrayList<>();
    }

    public abstract static class BlockIteratorClosure<StateT> {

        protected abstract void processBlock(Block block, StateT currentState);

        protected abstract StateT merge(MergeNode merge, List<StateT> states);

        protected abstract StateT cloneState(StateT oldState);

        protected abstract List<StateT> processLoop(Loop loop, StateT initialState);
    }

    private ReentrantBlockIterator() {
        // no instances allowed
    }

    public static <StateT> LoopInfo<StateT> processLoop(BlockIteratorClosure<StateT> closure, Loop loop, StateT initialState) {
        IdentityHashMap<FixedNode, StateT> blockEndStates = apply(closure, loop.header, initialState, new HashSet<>(loop.blocks));

        LoopInfo<StateT> info = new LoopInfo<>();
        List<Block> predecessors = loop.header.getPredecessors();
        for (int i = 1; i < predecessors.size(); i++) {
            StateT endState = blockEndStates.get(predecessors.get(i).getEndNode());
            // make sure all end states are unique objects
            info.endStates.add(closure.cloneState(endState));
        }
        for (Block loopExit : loop.exits) {
            assert loopExit.getPredecessorCount() == 1;
            StateT exitState = blockEndStates.get(loopExit.getBeginNode());
            assert exitState != null;
            // make sure all exit states are unique objects
            info.exitStates.add(closure.cloneState(exitState));
        }
        return info;
    }

    public static <StateT> IdentityHashMap<FixedNode, StateT> apply(BlockIteratorClosure<StateT> closure, Block start, StateT initialState, Set<Block> boundary) {
        Deque<Block> blockQueue = new ArrayDeque<>();
        /*
         * States are stored on EndNodes before merges, and on BeginNodes after ControlSplitNodes.
         */
        IdentityHashMap<FixedNode, StateT> states = new IdentityHashMap<>();

        StateT state = initialState;
        Block current = start;

        while (true) {
            if (boundary == null || boundary.contains(current)) {
                closure.processBlock(current, state);

                if (current.getSuccessors().isEmpty()) {
                    // nothing to do...
                } else if (current.getSuccessors().size() == 1) {
                    Block successor = current.getSuccessors().get(0);
                    if (successor.isLoopHeader()) {
                        if (current.isLoopEnd()) {
                            // nothing to do... loop ends only lead to loop begins we've already
                            // visited
                            states.put(current.getEndNode(), state);
                        } else {
                            // recurse into the loop
                            Loop loop = successor.getLoop();
                            LoopBeginNode loopBegin = loop.loopBegin();
                            assert successor.getBeginNode() == loopBegin;

                            List<StateT> exitStates = closure.processLoop(loop, state);

                            int i = 0;
                            assert loop.exits.size() == exitStates.size();
                            for (Block exit : loop.exits) {
                                states.put(exit.getBeginNode(), exitStates.get(i++));
                                blockQueue.addFirst(exit);
                            }
                        }
                    } else {
                        if (successor.getBeginNode() instanceof LoopExitNode) {
                            assert successor.getPredecessors().size() == 1;
                            states.put(successor.getBeginNode(), state);
                            current = successor;
                            continue;
                        } else {
                            if (current.getEndNode() instanceof EndNode) {
                                assert successor.getPredecessors().size() > 1 : "invalid block schedule at " + successor.getBeginNode();
                                EndNode end = (EndNode) current.getEndNode();

                                // add the end node and see if the merge is ready for processing
                                assert !states.containsKey(end);
                                states.put(end, state);
                                MergeNode merge = end.merge();
                                boolean endsVisited = true;
                                for (EndNode forwardEnd : merge.forwardEnds()) {
                                    if (!states.containsKey(forwardEnd)) {
                                        endsVisited = false;
                                        break;
                                    }
                                }
                                if (endsVisited) {
                                    blockQueue.addFirst(successor);
                                }
                            } else {
                                assert successor.getPredecessors().size() == 1 : "invalid block schedule at " + successor.getBeginNode();
                                current = successor;
                                continue;
                            }
                        }
                    }
                } else {
                    assert current.getSuccessors().size() > 1;
                    for (int i = 0; i < current.getSuccessors().size(); i++) {
                        Block successor = current.getSuccessors().get(i);
                        blockQueue.addFirst(successor);
                        states.put(successor.getBeginNode(), i == 0 ? state : closure.cloneState(state));
                    }
                }
            }

            // get next queued block
            if (blockQueue.isEmpty()) {
                return states;
            } else {
                current = blockQueue.removeFirst();
                if (current.getPredecessors().size() == 1) {
                    state = states.get(current.getBeginNode());
                } else {
                    assert current.getPredecessors().size() > 1;
                    MergeNode merge = (MergeNode) current.getBeginNode();
                    ArrayList<StateT> mergedStates = new ArrayList<>(merge.forwardEndCount());
                    for (int i = 0; i < merge.forwardEndCount(); i++) {
                        StateT other = states.get(merge.forwardEndAt(i));
                        assert other != null;
                        mergedStates.add(other);
                    }
                    state = closure.merge(merge, mergedStates);
                }
                assert state != null;
            }
        }
    }
}
