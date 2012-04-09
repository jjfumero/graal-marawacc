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
package com.oracle.graal.lir.cfg;

import java.util.*;

import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;

public class ControlFlowGraph {

    public final StructuredGraph graph;

    private final NodeMap<Block> nodeToBlock;
    private Block[] reversePostOrder;
    private Loop[] loops;

    public static ControlFlowGraph compute(StructuredGraph graph, boolean connectBlocks, boolean computeLoops, boolean computeDominators, boolean computePostdominators) {
        ControlFlowGraph cfg = new ControlFlowGraph(graph);
        cfg.identifyBlocks();
        if (connectBlocks || computeLoops || computeDominators || computePostdominators) {
            cfg.connectBlocks();
        }
        if (computeLoops) {
            cfg.computeLoopInformation();
        }
        if (computeDominators) {
            cfg.computeDominators();
        }
        if (computePostdominators) {
            cfg.computePostdominators();
        }
        assert CFGVerifier.verify(cfg);
        return cfg;
    }

    protected ControlFlowGraph(StructuredGraph graph) {
        this.graph = graph;
        this.nodeToBlock = graph.createNodeMap();
    }

    public Block[] getBlocks() {
        return reversePostOrder;
    }

    public Block getStartBlock() {
        return reversePostOrder[0];
    }

    public NodeMap<Block> getNodeToBlock() {
        return nodeToBlock;
    }

    public Block blockFor(Node node) {
        return nodeToBlock.get(node);
    }

    public Loop[] getLoops() {
        return loops;
    }

    protected static final int BLOCK_ID_INITIAL = -1;
    protected static final int BLOCK_ID_VISITED = -2;

    private void identifyBlocks() {
        // Find all block headers
        int numBlocks = 0;
        for (Node node : graph.getNodes()) {
            if (node instanceof BeginNode) {
                Block block = new Block();
                numBlocks++;

                block.beginNode = (BeginNode) node;
                Node cur = node;
                do {
                    assert !cur.isDeleted();
                    block.endNode = cur;

                    assert nodeToBlock.get(cur) == null;
                    nodeToBlock.set(cur, block);
                    if (cur instanceof MergeNode) {
                        for (PhiNode phi : ((MergeNode) cur).phis()) {
                            nodeToBlock.set(phi, block);
                        }
                    }

                    if (cur instanceof FixedNode) {
                        double probability = ((FixedNode) cur).probability();
                        if (probability > block.probability) {
                            block.probability = probability;
                        }
                    }

                    Node next = null;
                    for (Node sux : cur.successors()) {
                        if (sux != null && !(sux instanceof BeginNode)) {
                            assert next == null;
                            next = sux;
                        }
                    }
                    cur = next;
                } while (cur != null);
            }
        }

        // Compute postorder.
        ArrayList<Block> postOrder = new ArrayList<>(numBlocks);
        ArrayList<Block> stack = new ArrayList<>();
        stack.add(blockFor(graph.start()));

        do {
            Block block = stack.get(stack.size() - 1);
            if (block.id == BLOCK_ID_INITIAL) {
                // First time we see this block: push all successors.
                for (Node suxNode : block.getEndNode().cfgSuccessors()) {
                    Block suxBlock = blockFor(suxNode);
                    if (suxBlock.id == BLOCK_ID_INITIAL) {
                        stack.add(suxBlock);
                    }
                }
                block.id = BLOCK_ID_VISITED;
            } else if (block.id == BLOCK_ID_VISITED) {
                // Second time we see this block: All successors have been processed, so add block to postorder list.
                stack.remove(stack.size() - 1);
                postOrder.add(block);
            } else {
                throw GraalInternalError.shouldNotReachHere();
            }
        } while (!stack.isEmpty());

        // Compute reverse postorder and number blocks.
        assert postOrder.size() <= numBlocks : "some blocks originally created can be unreachable, so actual block list can be shorter";
        numBlocks = postOrder.size();
        reversePostOrder = new Block[numBlocks];
        for (int i = 0; i < numBlocks; i++) {
            reversePostOrder[i] = postOrder.get(numBlocks - i - 1);
            reversePostOrder[i].id = i;
        }
    }

    // Connect blocks (including loop backward edges), but ignoring dead code (blocks with id < 0).
    private void connectBlocks() {
        for (Block block : reversePostOrder) {
            List<Block> predecessors = new ArrayList<>();
            for (Node predNode : block.getBeginNode().cfgPredecessors()) {
                Block predBlock = nodeToBlock.get(predNode);
                if (predBlock.id >= 0) {
                    predecessors.add(predBlock);
                }
            }
            if (block.getBeginNode() instanceof LoopBeginNode) {
                for (LoopEndNode predNode : ((LoopBeginNode) block.getBeginNode()).orderedLoopEnds()) {
                    Block predBlock = nodeToBlock.get(predNode);
                    if (predBlock.id >= 0) {
                        predecessors.add(predBlock);
                    }
                }
            }
            block.predecessors = predecessors;

            List<Block> successors = new ArrayList<>();
            for (Node suxNode : block.getEndNode().cfgSuccessors()) {
                Block suxBlock = nodeToBlock.get(suxNode);
                assert suxBlock.id >= 0;
                successors.add(suxBlock);
            }
            if (block.getEndNode() instanceof LoopEndNode) {
                Block suxBlock = nodeToBlock.get(((LoopEndNode) block.getEndNode()).loopBegin());
                assert suxBlock.id >= 0;
                successors.add(suxBlock);
            }
            block.successors = successors;
        }
    }

    private void computeLoopInformation() {
        List<Loop> loopsList = new ArrayList<>();
        for (Block block : reversePostOrder) {
            Node beginNode = block.getBeginNode();
            if (beginNode instanceof LoopBeginNode) {
                Loop loop = new Loop(block.getLoop(), loopsList.size(), block);
                loopsList.add(loop);

                LoopBeginNode loopBegin = (LoopBeginNode) beginNode;
                for (LoopEndNode end : loopBegin.loopEnds()) {
                    Block endBlock = nodeToBlock.get(end);
                    computeLoopBlocks(endBlock, loop);
                }

                for (LoopExitNode exit : loopBegin.loopExits()) {
                    Block exitBlock = nodeToBlock.get(exit);
                    List<Block> predecessors = exitBlock.getPredecessors();
                    assert predecessors.size() == 1;
                    computeLoopBlocks(predecessors.get(0), loop);
                    loop.exits.add(exitBlock);
                }
                List<Block> unexpected = new LinkedList<>();
                for (Block b : loop.blocks) {
                    for (Block sux : b.getSuccessors()) {
                        if (sux.loop != loop) {
                            BeginNode begin = sux.getBeginNode();
                            if (!(begin instanceof LoopExitNode && ((LoopExitNode) begin).loopBegin() == loopBegin)) {
                                Debug.log("Unexpected loop exit with %s, including whole branch in the loop", sux);
                                unexpected.add(sux);
                            }
                        }
                    }
                }
                for (Block b : unexpected) {
                    addBranchToLoop(loop, b);
                }
            }
        }
        loops = loopsList.toArray(new Loop[loopsList.size()]);
    }

    private static void addBranchToLoop(Loop l, Block b) {
        if (l.blocks.contains(b)) {
            return;
        }
        l.blocks.add(b);
        b.loop = l;
        for (Block sux : b.getSuccessors()) {
            addBranchToLoop(l, sux);
        }
    }

    private static void computeLoopBlocks(Block block, Loop loop) {
        if (block.getLoop() == loop) {
            return;
        }
        assert block.loop == loop.parent;
        block.loop = loop;

        assert !loop.blocks.contains(block);
        loop.blocks.add(block);

        if (block != loop.header) {
            for (Block pred : block.getPredecessors()) {
                computeLoopBlocks(pred, loop);
            }
        }
    }

    private void computeDominators() {
        assert reversePostOrder[0].getPredecessors().size() == 0 : "start block has no predecessor and therefore no dominator";
        for (int i = 1; i < reversePostOrder.length; i++) {
            Block block = reversePostOrder[i];
            List<Block> predecessors = block.getPredecessors();
            assert predecessors.size() > 0;

            Block dominator = predecessors.get(0);
            for (int j = 1; j < predecessors.size(); j++) {
                Block pred = predecessors.get(j);
                if (!pred.isLoopEnd()) {
                    dominator = commonDominator(dominator, pred);
                }
            }
            setDominator(block, dominator);
        }
    }

    private static void setDominator(Block block, Block dominator) {
        block.dominator = dominator;
        if (dominator.dominated == null) {
            dominator.dominated = new ArrayList<>();
        }
        dominator.dominated.add(block);
    }

    public static Block commonDominator(Block a, Block b) {
        Block iterA = a;
        Block iterB = b;
        while (iterA != iterB) {
            if (iterA.getId() > iterB.getId()) {
                iterA = iterA.getDominator();
            } else {
                assert iterB.getId() > iterA.getId();
                iterB = iterB.getDominator();
            }
        }
        return iterA;
    }

    private void computePostdominators() {
        for (Block block : reversePostOrder) {
            if (block.isLoopEnd()) {
                // We do not want the loop header registered as the postdominator of the loop end.
                continue;
            }
            Block postdominator = null;
            for (Block sux : block.getSuccessors()) {
                if (sux.isExceptionEntry()) {
                    // We ignore exception handlers.
                } else if (postdominator == null) {
                    postdominator = sux;
                } else {
                    postdominator = commonPostdominator(postdominator, sux);
                }
            }
            block.postdominator = postdominator;
        }
    }

    private static Block commonPostdominator(Block a, Block b) {
        Block iterA = a;
        Block iterB = b;
        while (iterA != iterB) {
            if (iterA.getId() < iterB.getId()) {
                iterA = iterA.getPostdominator();
                if (iterA == null) {
                    return null;
                }
            } else {
                assert iterB.getId() < iterA.getId();
                iterB = iterB.getPostdominator();
                if (iterB == null) {
                    return null;
                }
            }
        }
        return iterA;
    }
}
