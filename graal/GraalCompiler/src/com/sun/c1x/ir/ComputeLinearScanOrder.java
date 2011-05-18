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

import com.sun.c1x.*;
import com.sun.c1x.debug.*;
import com.sun.c1x.util.*;
import com.sun.cri.ci.*;

public final class ComputeLinearScanOrder {

    private final int maxBlockId; // the highest blockId of a block
    private int numBlocks; // total number of blocks (smaller than maxBlockId)
    private int numLoops; // total number of loops
    private boolean iterativeDominators; // method requires iterative computation of dominators

    List<BlockBegin> linearScanOrder; // the resulting list of blocks in correct order

    final CiBitMap visitedBlocks; // used for recursive processing of blocks
    final CiBitMap activeBlocks; // used for recursive processing of blocks
    final CiBitMap dominatorBlocks; // temporary BitMap used for computation of dominator
    final int[] forwardBranches; // number of incoming forward branches for each block
    final List<BlockBegin> loopEndBlocks; // list of all loop end blocks collected during countEdges
    BitMap2D loopMap; // two-dimensional bit set: a bit is set if a block is contained in a loop
    final List<BlockBegin> workList; // temporary list (used in markLoops and computeOrder)

    // accessors for visitedBlocks and activeBlocks
    private void initVisited() {
        activeBlocks.clearAll();
        visitedBlocks.clearAll();
    }

    private boolean isVisited(BlockBegin b) {
        return visitedBlocks.get(b.blockID);
    }

    private boolean isActive(BlockBegin b) {
        return activeBlocks.get(b.blockID);
    }

    private void setVisited(BlockBegin b) {
        assert !isVisited(b) : "already set";
        visitedBlocks.set(b.blockID);
    }

    private void setActive(BlockBegin b) {
        assert !isActive(b) : "already set";
        activeBlocks.set(b.blockID);
    }

    private void clearActive(BlockBegin b) {
        assert isActive(b) : "not already";
        activeBlocks.clear(b.blockID);
    }

    // accessors for forwardBranches
    private void incForwardBranches(BlockBegin b) {
        forwardBranches[b.blockID]++;
    }

    private int decForwardBranches(BlockBegin b) {
        return --forwardBranches[b.blockID];
    }

    // accessors for loopMap
    private boolean isBlockInLoop(int loopIdx, BlockBegin b) {
        return loopMap.at(loopIdx, b.blockID);
    }

    private void setBlockInLoop(int loopIdx, BlockBegin b) {
        loopMap.setBit(loopIdx, b.blockID);
    }

    private void clearBlockInLoop(int loopIdx, int blockId) {
        loopMap.clearBit(loopIdx, blockId);
    }

    // accessors for final result
    public List<BlockBegin> linearScanOrder() {
        return linearScanOrder;
    }

    public int numLoops() {
        return numLoops;
    }

    public ComputeLinearScanOrder(int maxBlockId, BlockBegin startBlock) {

        this.maxBlockId = maxBlockId;
        visitedBlocks = new CiBitMap(maxBlockId);
        activeBlocks = new CiBitMap(maxBlockId);
        dominatorBlocks = new CiBitMap(maxBlockId);
        forwardBranches = new int[maxBlockId];
        loopEndBlocks = new ArrayList<BlockBegin>(8);
        workList = new ArrayList<BlockBegin>(8);

        countEdges(startBlock, null);

        if (numLoops > 0) {
            markLoops();
            clearNonNaturalLoops(startBlock);
            assignLoopDepth(startBlock);
        }

        computeOrder(startBlock);

        printBlocks();
        assert verify();
    }

    /**
     * Traverses the CFG to analyze block and edge info. The analysis performed is:
     *
     * 1. Count of total number of blocks.
     * 2. Count of all incoming edges and backward incoming edges.
     * 3. Number loop header blocks.
     * 4. Create a list with all loop end blocks.
     */
    private void countEdges(BlockBegin cur, BlockBegin parent) {
        if (C1XOptions.TraceLinearScanLevel >= 3) {
            TTY.println("Counting edges for block B%d%s", cur.blockID, parent == null ? "" : " coming from B" + parent.blockID);
        }

        if (isActive(cur)) {
            if (C1XOptions.TraceLinearScanLevel >= 3) {
                TTY.println("backward branch");
            }
            assert isVisited(cur) : "block must be visited when block is active";
            assert parent != null : "must have parent";

            cur.setBlockFlag(BlockBegin.BlockFlag.LinearScanLoopHeader);
            cur.setBlockFlag(BlockBegin.BlockFlag.BackwardBranchTarget);

            parent.setBlockFlag(BlockBegin.BlockFlag.LinearScanLoopEnd);

            // When a loop header is also the start of an exception handler, then the backward branch is
            // an exception edge. Because such edges are usually critical edges which cannot be split, the
            // loop must be excluded here from processing.
            if (cur.checkBlockFlag(BlockBegin.BlockFlag.ExceptionEntry)) {
                // Make sure that dominators are correct in this weird situation
                iterativeDominators = true;
                return;
            }

            loopEndBlocks.add(parent);
            return;
        }

        // increment number of incoming forward branches
        incForwardBranches(cur);

        if (isVisited(cur)) {
            if (C1XOptions.TraceLinearScanLevel >= 3) {
                TTY.println("block already visited");
            }
            return;
        }

        numBlocks++;
        setVisited(cur);
        setActive(cur);

        // recursive call for all successors
        int i;
        for (i = cur.numberOfSux() - 1; i >= 0; i--) {
            countEdges(cur.suxAt(i), cur);
        }
        for (i = cur.numberOfExceptionHandlers() - 1; i >= 0; i--) {
            countEdges(cur.exceptionHandlerAt(i), cur);
        }

        clearActive(cur);

        // Each loop has a unique number.
        // When multiple loops are nested, assignLoopDepth assumes that the
        // innermost loop has the lowest number. This is guaranteed by setting
        // the loop number after the recursive calls for the successors above
        // have returned.
        if (cur.checkBlockFlag(BlockBegin.BlockFlag.LinearScanLoopHeader)) {
            assert cur.loopIndex() == -1 : "cannot set loop-index twice";
            if (C1XOptions.TraceLinearScanLevel >= 3) {
                TTY.println("Block B%d is loop header of loop %d", cur.blockID, numLoops);
            }

            cur.setLoopIndex(numLoops);
            numLoops++;
        }

        if (C1XOptions.TraceLinearScanLevel >= 3) {
            TTY.println("Finished counting edges for block B%d", cur.blockID);
        }
    }

    private void markLoops() {
        if (C1XOptions.TraceLinearScanLevel >= 3) {
            TTY.println("----- marking loops");
        }

        loopMap = new BitMap2D(numLoops, maxBlockId);

        for (int i = loopEndBlocks.size() - 1; i >= 0; i--) {
            BlockBegin loopEnd = loopEndBlocks.get(i);
            BlockBegin loopStart = loopEnd.suxAt(0);
            int loopIdx = loopStart.loopIndex();

            if (C1XOptions.TraceLinearScanLevel >= 3) {
                TTY.println("Processing loop from B%d to B%d (loop %d):", loopStart.blockID, loopEnd.blockID, loopIdx);
            }
            assert loopEnd.checkBlockFlag(BlockBegin.BlockFlag.LinearScanLoopEnd) : "loop end flag must be set";
            assert loopStart.checkBlockFlag(BlockBegin.BlockFlag.LinearScanLoopHeader) : "loop header flag must be set";
            assert loopIdx >= 0 && loopIdx < numLoops : "loop index not set";
            assert workList.isEmpty() : "work list must be empty before processing";

            // add the end-block of the loop to the working list
            workList.add(loopEnd);
            setBlockInLoop(loopIdx, loopEnd);
            do {
                BlockBegin cur = workList.remove(workList.size() - 1);

                if (C1XOptions.TraceLinearScanLevel >= 3) {
                    TTY.println("    processing B%d", cur.blockID);
                }
                assert isBlockInLoop(loopIdx, cur) : "bit in loop map must be set when block is in work list";

                // recursive processing of all predecessors ends when start block of loop is reached
                if (cur != loopStart) {
                    for (int j = cur.numberOfPreds() - 1; j >= 0; j--) {
                        BlockBegin pred = cur.predAt(j).begin();

                        if (!isBlockInLoop(loopIdx, pred)) {
                            // this predecessor has not been processed yet, so add it to work list
                            if (C1XOptions.TraceLinearScanLevel >= 3) {
                                TTY.println("    pushing B%d", pred.blockID);
                            }
                            workList.add(pred);
                            setBlockInLoop(loopIdx, pred);
                        }
                    }
                }
            } while (!workList.isEmpty());
        }
    }

    // check for non-natural loops (loops where the loop header does not dominate
    // all other loop blocks = loops with multiple entries).
    // such loops are ignored
    private void clearNonNaturalLoops(BlockBegin startBlock) {
        for (int i = numLoops - 1; i >= 0; i--) {
            if (isBlockInLoop(i, startBlock)) {
                // loop i contains the entry block of the method.
                // this is not a natural loop, so ignore it
                if (C1XOptions.TraceLinearScanLevel >= 2) {
                    TTY.println("Loop %d is non-natural, so it is ignored", i);
                }

                for (int blockId = maxBlockId - 1; blockId >= 0; blockId--) {
                    clearBlockInLoop(i, blockId);
                }
                iterativeDominators = true;
            }
        }
    }

    private void assignLoopDepth(BlockBegin startBlock) {
        if (C1XOptions.TraceLinearScanLevel >= 3) {
            TTY.println("----- computing loop-depth and weight");
        }
        initVisited();

        assert workList.isEmpty() : "work list must be empty before processing";
        workList.add(startBlock);

        do {
            BlockBegin cur = workList.remove(workList.size() - 1);

            if (!isVisited(cur)) {
                setVisited(cur);
                if (C1XOptions.TraceLinearScanLevel >= 4) {
                    TTY.println("Computing loop depth for block B%d", cur.blockID);
                }

                // compute loop-depth and loop-index for the block
                assert cur.loopDepth() == 0 : "cannot set loop-depth twice";
                int i;
                int loopDepth = 0;
                int minLoopIdx = -1;
                for (i = numLoops - 1; i >= 0; i--) {
                    if (isBlockInLoop(i, cur)) {
                        loopDepth++;
                        minLoopIdx = i;
                    }
                }
                cur.setLoopDepth(loopDepth);
                cur.setLoopIndex(minLoopIdx);

                // append all unvisited successors to work list
                for (i = cur.numberOfSux() - 1; i >= 0; i--) {
                    workList.add(cur.suxAt(i));
                }
                for (i = cur.numberOfExceptionHandlers() - 1; i >= 0; i--) {
                    workList.add(cur.exceptionHandlerAt(i));
                }
            }
        } while (!workList.isEmpty());
    }

    private int computeWeight(BlockBegin cur) {
        BlockBegin singleSux = null;
        if (cur.numberOfSux() == 1) {
            singleSux = cur.suxAt(0);
        }

        // limit loop-depth to 15 bit (only for security reason, it will never be so big)
        int weight = (cur.loopDepth() & 0x7FFF) << 16;

        int curBit = 15;

        // this is necessary for the (very rare) case that two successive blocks have
        // the same loop depth, but a different loop index (can happen for endless loops
        // with exception handlers)
        if (!cur.checkBlockFlag(BlockBegin.BlockFlag.LinearScanLoopHeader)) {
            weight |= (1 << curBit);
        }
        curBit--;

        // loop end blocks (blocks that end with a backward branch) are added
        // after all other blocks of the loop.
        if (!cur.checkBlockFlag(BlockBegin.BlockFlag.LinearScanLoopEnd)) {
            weight |= (1 << curBit);
        }
        curBit--;

        // critical edge split blocks are preferred because then they have a greater
        // probability to be completely empty
        if (cur.isCriticalEdgeSplit()) {
            weight |= (1 << curBit);
        }
        curBit--;

        // exceptions should not be thrown in normal control flow, so these blocks
        // are added as late as possible
        if (!(cur.end() instanceof Throw) && (singleSux == null || !(singleSux.end() instanceof Throw))) {
            weight |= (1 << curBit);
        }
        curBit--;
        if (!(cur.end() instanceof Return) && (singleSux == null || !(singleSux.end() instanceof Return))) {
            weight |= (1 << curBit);
        }
        curBit--;

        // exceptions handlers are added as late as possible
        if (!cur.checkBlockFlag(BlockBegin.BlockFlag.ExceptionEntry)) {
            weight |= (1 << curBit);
        }
        curBit--;

        // guarantee that weight is > 0
        weight |= 1;

        assert curBit >= 0 : "too many flags";
        assert weight > 0 : "weight cannot become negative";

        return weight;
    }

    private boolean readyForProcessing(BlockBegin cur) {
        // Discount the edge just traveled.
        // When the number drops to zero, all forward branches were processed
        if (decForwardBranches(cur) != 0) {
            return false;
        }

        assert !linearScanOrder.contains(cur) : "block already processed (block can be ready only once)";
        assert !workList.contains(cur) : "block already in work-list (block can be ready only once)";
        return true;
    }

    private void sortIntoWorkList(BlockBegin cur) {
        assert !workList.contains(cur) : "block already in work list";

        int curWeight = computeWeight(cur);

        // the linearScanNumber is used to cache the weight of a block
        cur.setLinearScanNumber(curWeight);

        if (C1XOptions.StressLinearScan) {
            workList.add(0, cur);
            return;
        }

        workList.add(null); // provide space for new element

        int insertIdx = workList.size() - 1;
        while (insertIdx > 0 && workList.get(insertIdx - 1).linearScanNumber() > curWeight) {
            workList.set(insertIdx, workList.get(insertIdx - 1));
            insertIdx--;
        }
        workList.set(insertIdx, cur);

        if (C1XOptions.TraceLinearScanLevel >= 3) {
            TTY.println("Sorted B%d into worklist. new worklist:", cur.blockID);
            for (int i = 0; i < workList.size(); i++) {
                TTY.println(String.format("%8d B%02d  weight:%6x", i, workList.get(i).blockID, workList.get(i).linearScanNumber()));
            }
        }

        for (int i = 0; i < workList.size(); i++) {
            assert workList.get(i).linearScanNumber() > 0 : "weight not set";
            assert i == 0 || workList.get(i - 1).linearScanNumber() <= workList.get(i).linearScanNumber() : "incorrect order in worklist";
        }
    }

    private void appendBlock(BlockBegin cur) {
        if (C1XOptions.TraceLinearScanLevel >= 3) {
            TTY.println("appending block B%d (weight 0x%06x) to linear-scan order", cur.blockID, cur.linearScanNumber());
        }
        assert !linearScanOrder.contains(cur) : "cannot add the same block twice";

        // currently, the linear scan order and code emit order are equal.
        // therefore the linearScanNumber and the weight of a block must also
        // be equal.
        cur.setLinearScanNumber(linearScanOrder.size());
        linearScanOrder.add(cur);
    }

    private void computeOrder(BlockBegin startBlock) {
        if (C1XOptions.TraceLinearScanLevel >= 3) {
            TTY.println("----- computing final block order");
        }

        // the start block is always the first block in the linear scan order
        linearScanOrder = new ArrayList<BlockBegin>(numBlocks);

        // start processing with standard entry block
        assert workList.isEmpty() : "list must be empty before processing";

        if (readyForProcessing(startBlock)) {
            sortIntoWorkList(startBlock);
        } else {
            throw new CiBailout("the stdEntry must be ready for processing (otherwise, the method has no start block)");
        }

        do {
            BlockBegin cur = workList.remove(workList.size() - 1);
            appendBlock(cur);

            int i;
            int numSux = cur.numberOfSux();
            // changed loop order to get "intuitive" order of if- and else-blocks
            for (i = 0; i < numSux; i++) {
                BlockBegin sux = cur.suxAt(i);
                if (readyForProcessing(sux)) {
                    sortIntoWorkList(sux);
                }
            }
            numSux = cur.numberOfExceptionHandlers();
            for (i = 0; i < numSux; i++) {
                BlockBegin sux = cur.exceptionHandlerAt(i);
                if (readyForProcessing(sux)) {
                    sortIntoWorkList(sux);
                }
            }
        } while (workList.size() > 0);
    }

    public void printBlocks() {
        if (C1XOptions.TraceLinearScanLevel >= 2) {
            TTY.println("----- loop information:");
            for (BlockBegin cur : linearScanOrder) {
                TTY.print(String.format("%4d: B%02d: ", cur.linearScanNumber(), cur.blockID));
                for (int loopIdx = 0; loopIdx < numLoops; loopIdx++) {
                    TTY.print(String.format("%d = %b ", loopIdx, isBlockInLoop(loopIdx, cur)));
                }
                TTY.println(String.format(" . loopIndex: %2d, loopDepth: %2d", cur.loopIndex(), cur.loopDepth()));
            }
        }

        if (C1XOptions.TraceLinearScanLevel >= 1) {
            TTY.println("----- linear-scan block order:");
            for (BlockBegin cur : linearScanOrder) {
                TTY.print(String.format("%4d: B%02d    loop: %2d  depth: %2d", cur.linearScanNumber(), cur.blockID, cur.loopIndex(), cur.loopDepth()));

                TTY.print(cur.isExceptionEntry() ? " ex" : "   ");
                TTY.print(cur.isCriticalEdgeSplit() ? " ce" : "   ");
                TTY.print(cur.checkBlockFlag(BlockBegin.BlockFlag.LinearScanLoopHeader) ? " lh" : "   ");
                TTY.print(cur.checkBlockFlag(BlockBegin.BlockFlag.LinearScanLoopEnd) ? " le" : "   ");

                if (cur.numberOfPreds() > 0) {
                    TTY.print("    preds: ");
                    for (int j = 0; j < cur.numberOfPreds(); j++) {
                        BlockBegin pred = cur.predAt(j).begin();
                        TTY.print("B%d ", pred.blockID);
                    }
                }
                if (cur.numberOfSux() > 0) {
                    TTY.print("    sux: ");
                    for (int j = 0; j < cur.numberOfSux(); j++) {
                        BlockBegin sux = cur.suxAt(j);
                        TTY.print("B%d ", sux.blockID);
                    }
                }
                if (cur.numberOfExceptionHandlers() > 0) {
                    TTY.print("    ex: ");
                    for (int j = 0; j < cur.numberOfExceptionHandlers(); j++) {
                        BlockBegin ex = cur.exceptionHandlerAt(j);
                        TTY.print("B%d ", ex.blockID);
                    }
                }
                TTY.println();
            }
        }
    }

    private boolean verify() {
        assert linearScanOrder.size() == numBlocks : "wrong number of blocks in list";

        if (C1XOptions.StressLinearScan) {
            // blocks are scrambled when StressLinearScan is used
            return true;
        }

        // check that all successors of a block have a higher linear-scan-number
        // and that all predecessors of a block have a lower linear-scan-number
        // (only backward branches of loops are ignored)
        int i;
        for (i = 0; i < linearScanOrder.size(); i++) {
            BlockBegin cur = linearScanOrder.get(i);

            assert cur.linearScanNumber() == i : "incorrect linearScanNumber";
            assert cur.linearScanNumber() >= 0 && cur.linearScanNumber() == linearScanOrder.indexOf(cur) : "incorrect linearScanNumber";

            for (BlockBegin sux : cur.end().blockSuccessors()) {
                assert sux.linearScanNumber() >= 0 && sux.linearScanNumber() == linearScanOrder.indexOf(sux) : "incorrect linearScanNumber";
                if (!cur.checkBlockFlag(BlockBegin.BlockFlag.LinearScanLoopEnd)) {
                    assert cur.linearScanNumber() < sux.linearScanNumber() : "invalid order";
                }
                if (cur.loopDepth() == sux.loopDepth()) {
                    assert cur.loopIndex() == sux.loopIndex() || sux.checkBlockFlag(BlockBegin.BlockFlag.LinearScanLoopHeader) : "successing blocks with same loop depth must have same loop index";
                }
            }

            for (BlockEnd pred : cur.blockPredecessors()) {
                BlockBegin begin = pred.begin();
                assert begin.linearScanNumber() >= 0 && begin.linearScanNumber() == linearScanOrder.indexOf(begin) : "incorrect linearScanNumber";
                if (!cur.checkBlockFlag(BlockBegin.BlockFlag.LinearScanLoopHeader)) {
                    assert cur.linearScanNumber() > begin.linearScanNumber() : "invalid order";
                }
                if (cur.loopDepth() == begin.loopDepth()) {
                    assert cur.loopIndex() == begin.loopIndex() || cur.checkBlockFlag(BlockBegin.BlockFlag.LinearScanLoopHeader) : "successing blocks with same loop depth must have same loop index";
                }
            }
        }

        // check that all loops are continuous
        for (int loopIdx = 0; loopIdx < numLoops; loopIdx++) {
            int blockIdx = 0;
            assert !isBlockInLoop(loopIdx, linearScanOrder.get(blockIdx)) : "the first block must not be present in any loop";

            // skip blocks before the loop
            while (blockIdx < numBlocks && !isBlockInLoop(loopIdx, linearScanOrder.get(blockIdx))) {
                blockIdx++;
            }
            // skip blocks of loop
            while (blockIdx < numBlocks && isBlockInLoop(loopIdx, linearScanOrder.get(blockIdx))) {
                blockIdx++;
            }
            // after the first non-loop block : there must not be another loop-block
            while (blockIdx < numBlocks) {
                assert !isBlockInLoop(loopIdx, linearScanOrder.get(blockIdx)) : "loop not continuous in linear-scan order";
                blockIdx++;
            }
        }

        return true;
    }
}
