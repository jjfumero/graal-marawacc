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

package com.oracle.graal.alloc;

import java.util.*;

import com.oracle.graal.nodes.cfg.*;

/**
 * Computes an ordering of the block that can be used by the linear scan register allocator and the
 * machine code generator. The machine code generation order will start with the first block and
 * produce a straight sequence always following the most likely successor. Then it will continue
 * with the most likely path that was left out during this process. The process iteratively
 * continues until all blocks are scheduled. Additionally, it is guaranteed that all blocks of a
 * loop are scheduled before any block following the loop is scheduled.
 * 
 * The machine code generator order includes reordering of loop headers such that the backward jump
 * is a conditional jump if there is only one loop end block. Additionally, the target of loop
 * backward jumps are always marked as aligned. Aligning the target of conditional jumps does not
 * bring a measurable benefit and is therefore avoided to keep the code size small.
 * 
 * The linear scan register allocator order has an additional mechanism that prevents merge nodes
 * from being scheduled if there is at least one highly likely predecessor still unscheduled. This
 * increases the probability that the merge node and the corresponding predecessor are more closely
 * together in the schedule thus decreasing the probability for inserted phi moves. Also, the
 * algorithm sets the linear scan order number of the block that corresponds to its index in the
 * linear scan order.
 */
public final class ComputeBlockOrder {

    /**
     * The initial capacities of the worklists used for iteratively finding the block order.
     */
    private static final int INITIAL_WORKLIST_CAPACITY = 10;

    /**
     * Computes the block order used for the linear scan register allocator.
     * 
     * @return sorted list of blocks
     */
    public static List<Block> computeLinearScanOrder(int blockCount, Block startBlock) {
        List<Block> order = new ArrayList<>();
        BitSet visitedBlocks = new BitSet(blockCount);
        PriorityQueue<Block> worklist = initializeWorklist(startBlock, visitedBlocks);
        computeLinearScanOrder(order, worklist, visitedBlocks);
        assert checkOrder(order, blockCount);
        return order;
    }

    /**
     * Computes the block order used for code emission.
     * 
     * @return sorted list of blocks
     */
    public static List<Block> computeCodeEmittingOrder(int blockCount, Block startBlock) {
        List<Block> order = new ArrayList<>();
        BitSet visitedBlocks = new BitSet(blockCount);
        PriorityQueue<Block> worklist = initializeWorklist(startBlock, visitedBlocks);
        computeCodeEmittingOrder(order, worklist, visitedBlocks);
        assert checkOrder(order, blockCount);
        return order;
    }

    /**
     * Iteratively adds paths to the code emission block order.
     */
    private static void computeCodeEmittingOrder(List<Block> order, PriorityQueue<Block> worklist, BitSet visitedBlocks) {
        while (!worklist.isEmpty()) {
            Block nextImportantPath = worklist.poll();
            addPathToCodeEmittingOrder(nextImportantPath, order, worklist, visitedBlocks);
        }
    }

    /**
     * Iteratively adds paths to the linear scan block order.
     */
    private static void computeLinearScanOrder(List<Block> order, PriorityQueue<Block> worklist, BitSet visitedBlocks) {
        while (!worklist.isEmpty()) {
            Block nextImportantPath = worklist.poll();
            addPathToLinearScanOrder(nextImportantPath, order, worklist, visitedBlocks);
        }
    }

    /**
     * Initializes the priority queue used for the work list of blocks and adds the start block.
     */
    private static PriorityQueue<Block> initializeWorklist(Block startBlock, BitSet visitedBlocks) {
        PriorityQueue<Block> result = new PriorityQueue<>(INITIAL_WORKLIST_CAPACITY, blockComparator);
        result.add(startBlock);
        visitedBlocks.set(startBlock.getId());
        return result;
    }

    /**
     * Add a linear path to the linear scan order greedily following the most likely successor.
     */
    private static void addPathToLinearScanOrder(Block block, List<Block> order, PriorityQueue<Block> worklist, BitSet visitedBlocks) {
        block.setLinearScanNumber(order.size());
        order.add(block);
        enqueueSuccessors(block, worklist, visitedBlocks);
    }

    /**
     * Add a linear path to the code emission order greedily following the most likely successor.
     */
    private static void addPathToCodeEmittingOrder(Block block, List<Block> order, PriorityQueue<Block> worklist, BitSet visitedBlocks) {

        // Skip loop headers if there is only a single loop end block to make the backward jump be a
        // conditional jump.
        if (!skipLoopHeader(block)) {

            // Align unskipped loop headers as they are the target of the backward jump.
            if (block.isLoopHeader()) {
                block.setAlign(true);
            }
            addBlock(block, order);
        }

        Loop loop = block.getLoop();
        if (block.isLoopEnd() && skipLoopHeader(loop.header)) {

            // This is the only loop end of a skipped loop header. Add the header immediately
            // afterwards.
            addBlock(loop.header, order);

            // Make sure the loop successors of the loop header are aligned as they are the target
            // of the backward jump.
            for (Block successor : loop.header.getSuccessors()) {
                if (successor.getLoopDepth() == block.getLoopDepth()) {
                    successor.setAlign(true);
                }
            }
        }

        Block mostLikelySuccessor = findAndMarkMostLikelySuccessor(block, visitedBlocks);
        enqueueSuccessors(block, worklist, visitedBlocks);
        if (mostLikelySuccessor != null) {
            addPathToCodeEmittingOrder(mostLikelySuccessor, order, worklist, visitedBlocks);
        }
    }

    /**
     * Adds a block to the ordering.
     */
    private static void addBlock(Block header, List<Block> order) {
        assert !order.contains(header) : "Cannot insert block twice";
        order.add(header);
    }

    /**
     * Find the highest likely unvisited successor block of a given block.
     */
    private static Block findAndMarkMostLikelySuccessor(Block block, BitSet visitedBlocks) {
        Block result = null;
        for (Block successor : block.getSuccessors()) {
            assert successor.getProbability() >= 0.0 : "Probabilities must be positive";
            if (!visitedBlocks.get(successor.getId()) && successor.getLoopDepth() >= block.getLoopDepth() && (result == null || successor.getProbability() >= result.getProbability())) {
                result = successor;
            }
        }
        if (result != null) {
            visitedBlocks.set(result.getId());
        }
        return result;
    }

    /**
     * Add successor blocks into the given work list if they are not already marked as visited.
     */
    private static void enqueueSuccessors(Block block, PriorityQueue<Block> worklist, BitSet visitedBlocks) {
        for (Block successor : block.getSuccessors()) {
            if (!visitedBlocks.get(successor.getId())) {
                visitedBlocks.set(successor.getId());
                worklist.add(successor);
            }
        }
    }

    /**
     * Skip the loop header block if the loop consists of more than one block and it has only a
     * single loop end block.
     */
    private static boolean skipLoopHeader(Block block) {
        return (block.isLoopHeader() && !block.isLoopEnd() && block.getLoop().loopBegin().loopEnds().count() == 1);
    }

    /**
     * Checks that the ordering contains the expected number of blocks.
     */
    private static boolean checkOrder(List<Block> order, int expectedBlockCount) {
        assert order.size() == expectedBlockCount : String.format("Number of blocks in ordering (%d) does not match expected block count (%d)", order.size(), expectedBlockCount);
        return true;
    }

    /**
     * Comparator for sorting blocks based on loop depth and probability.
     */
    private static Comparator<Block> blockComparator = new Comparator<Block>() {

        @Override
        public int compare(Block a, Block b) {
            // Loop blocks before any loop exit block.
            int diff = b.getLoopDepth() - a.getLoopDepth();
            if (diff != 0) {
                return diff;
            }

            // Blocks with high probability before blocks with low probability.
            if (a.getProbability() > b.getProbability()) {
                return -1;
            } else {
                return 1;
            }
        }
    };
}
