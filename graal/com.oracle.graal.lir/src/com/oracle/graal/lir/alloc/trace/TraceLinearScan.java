/*
 * Copyright (c) 2009, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.lir.alloc.trace;

import static com.oracle.graal.compiler.common.GraalOptions.*;
import static com.oracle.graal.lir.LIRValueUtil.*;
import static jdk.internal.jvmci.code.CodeUtil.*;
import static jdk.internal.jvmci.code.ValueUtil.*;

import java.util.*;

import jdk.internal.jvmci.code.*;
import jdk.internal.jvmci.common.*;
import jdk.internal.jvmci.meta.*;
import jdk.internal.jvmci.options.*;

import com.oracle.graal.compiler.common.alloc.*;
import com.oracle.graal.compiler.common.alloc.TraceBuilder.TraceBuilderResult;
import com.oracle.graal.compiler.common.cfg.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.LIRInstruction.OperandFlag;
import com.oracle.graal.lir.LIRInstruction.OperandMode;
import com.oracle.graal.lir.alloc.trace.TraceInterval.RegisterBinding;
import com.oracle.graal.lir.framemap.*;
import com.oracle.graal.lir.gen.*;
import com.oracle.graal.lir.gen.LIRGeneratorTool.SpillMoveFactory;
import com.oracle.graal.lir.phases.AllocationPhase.AllocationContext;

/**
 * An implementation of the linear scan register allocator algorithm described in <a
 * href="http://doi.acm.org/10.1145/1064979.1064998"
 * >"Optimized Interval Splitting in a Linear Scan Register Allocator"</a> by Christian Wimmer and
 * Hanspeter Moessenboeck.
 */
final class TraceLinearScan {

    public static class Options {
        // @formatter:off
        @Option(help = "Use simplified lifetime analysis.", type = OptionType.Debug)
        public static final OptionValue<Boolean> TraceRAsimpleLifetimeAnalysis = new OptionValue<>(true);
        // @formatter:on
    }

    public static class BlockData {

        /**
         * Bit map specifying which operands are live upon entry to this block. These are values
         * used in this block or any of its successors where such value are not defined in this
         * block. The bit index of an operand is its
         * {@linkplain TraceLinearScan#operandNumber(Value) operand number}.
         */
        public BitSet liveIn;

        /**
         * Bit map specifying which operands are live upon exit from this block. These are values
         * used in a successor block that are either defined in this block or were live upon entry
         * to this block. The bit index of an operand is its
         * {@linkplain TraceLinearScan#operandNumber(Value) operand number}.
         */
        public BitSet liveOut;

        /**
         * Bit map specifying which operands are used (before being defined) in this block. That is,
         * these are the values that are live upon entry to the block. The bit index of an operand
         * is its {@linkplain TraceLinearScan#operandNumber(Value) operand number}.
         */
        public BitSet liveGen;

        /**
         * Bit map specifying which operands are defined/overwritten in this block. The bit index of
         * an operand is its {@linkplain TraceLinearScan#operandNumber(Value) operand number}.
         */
        public BitSet liveKill;
    }

    public static final int DOMINATOR_SPILL_MOVE_ID = -2;
    private static final int SPLIT_INTERVALS_CAPACITY_RIGHT_SHIFT = 1;

    private final LIR ir;
    private final FrameMapBuilder frameMapBuilder;
    private final RegisterAttributes[] registerAttributes;
    private final Register[] registers;
    private final RegisterAllocationConfig regAllocConfig;
    private final SpillMoveFactory moveFactory;

    private final BlockMap<BlockData> blockData;

    /**
     * List of blocks in linear-scan order. This is only correct as long as the CFG does not change.
     */
    private final List<? extends AbstractBlockBase<?>> sortedBlocks;

    /** @see #intervals() */
    private TraceInterval[] intervals;

    /**
     * The number of valid entries in {@link #intervals}.
     */
    private int intervalsSize;

    /**
     * The index of the first entry in {@link #intervals} for a
     * {@linkplain #createDerivedInterval(TraceInterval) derived interval}.
     */
    private int firstDerivedIntervalIndex = -1;

    /**
     * Intervals sorted by {@link TraceInterval#from()}.
     */
    private TraceInterval[] sortedIntervals;

    /**
     * Map from an instruction {@linkplain LIRInstruction#id id} to the instruction. Entries should
     * be retrieved with {@link #instructionForId(int)} as the id is not simply an index into this
     * array.
     */
    private LIRInstruction[] opIdToInstructionMap;

    /**
     * Map from an instruction {@linkplain LIRInstruction#id id} to the
     * {@linkplain AbstractBlockBase block} containing the instruction. Entries should be retrieved
     * with {@link #blockForId(int)} as the id is not simply an index into this array.
     */
    private AbstractBlockBase<?>[] opIdToBlockMap;

    /**
     * The {@linkplain #operandNumber(Value) number} of the first variable operand allocated.
     */
    private final int firstVariableNumber;
    protected final TraceBuilderResult<?> traceBuilderResult;

    protected TraceLinearScan(TargetDescription target, LIRGenerationResult res, SpillMoveFactory spillMoveFactory, RegisterAllocationConfig regAllocConfig,
                    List<? extends AbstractBlockBase<?>> sortedBlocks, TraceBuilderResult<?> traceBuilderResult) {
        this.ir = res.getLIR();
        this.moveFactory = spillMoveFactory;
        this.frameMapBuilder = res.getFrameMapBuilder();
        this.sortedBlocks = sortedBlocks;
        this.registerAttributes = regAllocConfig.getRegisterConfig().getAttributesMap();
        this.regAllocConfig = regAllocConfig;

        this.registers = target.arch.getRegisters();
        this.firstVariableNumber = getRegisters().length;
        this.blockData = new BlockMap<>(ir.getControlFlowGraph());
        this.traceBuilderResult = traceBuilderResult;
    }

    public int getFirstLirInstructionId(AbstractBlockBase<?> block) {
        int result = ir.getLIRforBlock(block).get(0).id();
        assert result >= 0;
        return result;
    }

    public int getLastLirInstructionId(AbstractBlockBase<?> block) {
        List<LIRInstruction> instructions = ir.getLIRforBlock(block);
        int result = instructions.get(instructions.size() - 1).id();
        assert result >= 0;
        return result;
    }

    public SpillMoveFactory getSpillMoveFactory() {
        return moveFactory;
    }

    protected TraceLocalMoveResolver createMoveResolver() {
        TraceLocalMoveResolver moveResolver = new TraceLocalMoveResolver(this);
        assert moveResolver.checkEmpty();
        return moveResolver;
    }

    public static boolean isVariableOrRegister(Value value) {
        return isVariable(value) || isRegister(value);
    }

    /**
     * Converts an operand (variable or register) to an index in a flat address space covering all
     * the {@linkplain Variable variables} and {@linkplain RegisterValue registers} being processed
     * by this allocator.
     */
    int operandNumber(Value operand) {
        if (isRegister(operand)) {
            int number = asRegister(operand).number;
            assert number < firstVariableNumber;
            return number;
        }
        assert isVariable(operand) : operand;
        return firstVariableNumber + ((Variable) operand).index;
    }

    /**
     * Gets the number of operands. This value will increase by 1 for new variable.
     */
    int operandSize() {
        return firstVariableNumber + ir.numVariables();
    }

    /**
     * Gets the highest operand number for a register operand. This value will never change.
     */
    int maxRegisterNumber() {
        return firstVariableNumber - 1;
    }

    public BlockData getBlockData(AbstractBlockBase<?> block) {
        return blockData.get(block);
    }

    void initBlockData(AbstractBlockBase<?> block) {
        blockData.put(block, new BlockData());
    }

    static final IntervalPredicate IS_PRECOLORED_INTERVAL = new IntervalPredicate() {

        @Override
        public boolean apply(TraceInterval i) {
            return isRegister(i.operand);
        }
    };

    static final IntervalPredicate IS_VARIABLE_INTERVAL = new IntervalPredicate() {

        @Override
        public boolean apply(TraceInterval i) {
            return isVariable(i.operand);
        }
    };

    static final IntervalPredicate IS_STACK_INTERVAL = new IntervalPredicate() {

        @Override
        public boolean apply(TraceInterval i) {
            return !isRegister(i.operand);
        }
    };

    /**
     * Gets an object describing the attributes of a given register according to this register
     * configuration.
     */
    public RegisterAttributes attributes(Register reg) {
        return registerAttributes[reg.number];
    }

    void assignSpillSlot(TraceInterval interval) {
        /*
         * Assign the canonical spill slot of the parent (if a part of the interval is already
         * spilled) or allocate a new spill slot.
         */
        if (interval.canMaterialize()) {
            interval.assignLocation(Value.ILLEGAL);
        } else if (interval.spillSlot() != null) {
            interval.assignLocation(interval.spillSlot());
        } else {
            VirtualStackSlot slot = frameMapBuilder.allocateSpillSlot(interval.kind());
            interval.setSpillSlot(slot);
            interval.assignLocation(slot);
        }
    }

    /**
     * Map from {@linkplain #operandNumber(Value) operand numbers} to intervals.
     */
    public TraceInterval[] intervals() {
        return intervals;
    }

    void initIntervals() {
        intervalsSize = operandSize();
        intervals = new TraceInterval[intervalsSize + (intervalsSize >> SPLIT_INTERVALS_CAPACITY_RIGHT_SHIFT)];
    }

    /**
     * Creates a new interval.
     *
     * @param operand the operand for the interval
     * @return the created interval
     */
    TraceInterval createInterval(AllocatableValue operand) {
        assert isLegal(operand);
        int operandNumber = operandNumber(operand);
        TraceInterval interval = new TraceInterval(operand, operandNumber);
        assert operandNumber < intervalsSize;
        assert intervals[operandNumber] == null;
        intervals[operandNumber] = interval;
        return interval;
    }

    /**
     * Creates an interval as a result of splitting or spilling another interval.
     *
     * @param source an interval being split of spilled
     * @return a new interval derived from {@code source}
     */
    TraceInterval createDerivedInterval(TraceInterval source) {
        if (firstDerivedIntervalIndex == -1) {
            firstDerivedIntervalIndex = intervalsSize;
        }
        if (intervalsSize == intervals.length) {
            intervals = Arrays.copyOf(intervals, intervals.length + (intervals.length >> SPLIT_INTERVALS_CAPACITY_RIGHT_SHIFT));
        }
        intervalsSize++;
        Variable variable = new Variable(source.kind(), ir.nextVariable());

        TraceInterval interval = createInterval(variable);
        assert intervals[intervalsSize - 1] == interval;
        return interval;
    }

    // access to block list (sorted in linear scan order)
    public int blockCount() {
        return sortedBlocks.size();
    }

    public AbstractBlockBase<?> blockAt(int index) {
        return sortedBlocks.get(index);
    }

    /**
     * Gets the size of the {@link BlockData#liveIn} and {@link BlockData#liveOut} sets for a basic
     * block. These sets do not include any operands allocated as a result of creating
     * {@linkplain #createDerivedInterval(TraceInterval) derived intervals}.
     */
    public int liveSetSize() {
        return firstDerivedIntervalIndex == -1 ? operandSize() : firstDerivedIntervalIndex;
    }

    int numLoops() {
        return ir.getControlFlowGraph().getLoops().size();
    }

    TraceInterval intervalFor(int operandNumber) {
        return intervals[operandNumber];
    }

    public TraceInterval intervalFor(Value operand) {
        int operandNumber = operandNumber(operand);
        assert operandNumber < intervalsSize;
        return intervals[operandNumber];
    }

    public TraceInterval getOrCreateInterval(AllocatableValue operand) {
        TraceInterval ret = intervalFor(operand);
        if (ret == null) {
            return createInterval(operand);
        } else {
            return ret;
        }
    }

    void initOpIdMaps(int numInstructions) {
        opIdToInstructionMap = new LIRInstruction[numInstructions];
        opIdToBlockMap = new AbstractBlockBase<?>[numInstructions];
    }

    void putOpIdMaps(int index, LIRInstruction op, AbstractBlockBase<?> block) {
        opIdToInstructionMap[index] = op;
        opIdToBlockMap[index] = block;
    }

    /**
     * Gets the highest instruction id allocated by this object.
     */
    int maxOpId() {
        assert opIdToInstructionMap.length > 0 : "no operations";
        return (opIdToInstructionMap.length - 1) << 1;
    }

    /**
     * Converts an {@linkplain LIRInstruction#id instruction id} to an instruction index. All LIR
     * instructions in a method have an index one greater than their linear-scan order predecessor
     * with the first instruction having an index of 0.
     */
    private static int opIdToIndex(int opId) {
        return opId >> 1;
    }

    /**
     * Retrieves the {@link LIRInstruction} based on its {@linkplain LIRInstruction#id id}.
     *
     * @param opId an instruction {@linkplain LIRInstruction#id id}
     * @return the instruction whose {@linkplain LIRInstruction#id} {@code == id}
     */
    public LIRInstruction instructionForId(int opId) {
        assert isEven(opId) : "opId not even";
        LIRInstruction instr = opIdToInstructionMap[opIdToIndex(opId)];
        assert instr.id() == opId;
        return instr;
    }

    /**
     * Gets the block containing a given instruction.
     *
     * @param opId an instruction {@linkplain LIRInstruction#id id}
     * @return the block containing the instruction denoted by {@code opId}
     */
    public AbstractBlockBase<?> blockForId(int opId) {
        assert opIdToBlockMap.length > 0 && opId >= 0 && opId <= maxOpId() + 1 : "opId out of range";
        return opIdToBlockMap[opIdToIndex(opId)];
    }

    boolean isBlockBegin(int opId) {
        return opId == 0 || blockForId(opId) != blockForId(opId - 1);
    }

    boolean coversBlockBegin(int opId1, int opId2) {
        return blockForId(opId1) != blockForId(opId2);
    }

    /**
     * Determines if an {@link LIRInstruction} destroys all caller saved registers.
     *
     * @param opId an instruction {@linkplain LIRInstruction#id id}
     * @return {@code true} if the instruction denoted by {@code id} destroys all caller saved
     *         registers.
     */
    boolean hasCall(int opId) {
        assert isEven(opId) : "opId not even";
        return instructionForId(opId).destroysCallerSavedRegisters();
    }

    abstract static class IntervalPredicate {

        abstract boolean apply(TraceInterval i);
    }

    public boolean isProcessed(Value operand) {
        return !isRegister(operand) || attributes(asRegister(operand)).isAllocatable();
    }

    // * Phase 5: actual register allocation

    private static boolean isSorted(TraceInterval[] intervals) {
        int from = -1;
        for (TraceInterval interval : intervals) {
            assert interval != null;
            assert from <= interval.from();
            from = interval.from();
        }
        return true;
    }

    static TraceInterval addToList(TraceInterval first, TraceInterval prev, TraceInterval interval) {
        TraceInterval newFirst = first;
        if (prev != null) {
            prev.next = interval;
        } else {
            newFirst = interval;
        }
        return newFirst;
    }

    TraceInterval.Pair createUnhandledLists(IntervalPredicate isList1, IntervalPredicate isList2) {
        assert isSorted(sortedIntervals) : "interval list is not sorted";

        TraceInterval list1 = TraceInterval.EndMarker;
        TraceInterval list2 = TraceInterval.EndMarker;

        TraceInterval list1Prev = null;
        TraceInterval list2Prev = null;
        TraceInterval v;

        int n = sortedIntervals.length;
        for (int i = 0; i < n; i++) {
            v = sortedIntervals[i];
            if (v == null) {
                continue;
            }

            if (isList1.apply(v)) {
                list1 = addToList(list1, list1Prev, v);
                list1Prev = v;
            } else if (isList2 == null || isList2.apply(v)) {
                list2 = addToList(list2, list2Prev, v);
                list2Prev = v;
            }
        }

        if (list1Prev != null) {
            list1Prev.next = TraceInterval.EndMarker;
        }
        if (list2Prev != null) {
            list2Prev.next = TraceInterval.EndMarker;
        }

        assert list1Prev == null || list1Prev.next == TraceInterval.EndMarker : "linear list ends not with sentinel";
        assert list2Prev == null || list2Prev.next == TraceInterval.EndMarker : "linear list ends not with sentinel";

        return new TraceInterval.Pair(list1, list2);
    }

    protected void sortIntervalsBeforeAllocation() {
        int sortedLen = 0;
        for (TraceInterval interval : intervals) {
            if (interval != null) {
                sortedLen++;
            }
        }

        TraceInterval[] sortedList = new TraceInterval[sortedLen];
        int sortedIdx = 0;
        int sortedFromMax = -1;

        // special sorting algorithm: the original interval-list is almost sorted,
        // only some intervals are swapped. So this is much faster than a complete QuickSort
        for (TraceInterval interval : intervals) {
            if (interval != null) {
                int from = interval.from();

                if (sortedFromMax <= from) {
                    sortedList[sortedIdx++] = interval;
                    sortedFromMax = interval.from();
                } else {
                    // the assumption that the intervals are already sorted failed,
                    // so this interval must be sorted in manually
                    int j;
                    for (j = sortedIdx - 1; j >= 0 && from < sortedList[j].from(); j--) {
                        sortedList[j + 1] = sortedList[j];
                    }
                    sortedList[j + 1] = interval;
                    sortedIdx++;
                }
            }
        }
        sortedIntervals = sortedList;
    }

    void sortIntervalsAfterAllocation() {
        if (firstDerivedIntervalIndex == -1) {
            // no intervals have been added during allocation, so sorted list is already up to date
            return;
        }

        TraceInterval[] oldList = sortedIntervals;
        TraceInterval[] newList = Arrays.copyOfRange(intervals, firstDerivedIntervalIndex, intervalsSize);
        int oldLen = oldList.length;
        int newLen = newList.length;

        // conventional sort-algorithm for new intervals
        Arrays.sort(newList, (TraceInterval a, TraceInterval b) -> a.from() - b.from());

        // merge old and new list (both already sorted) into one combined list
        TraceInterval[] combinedList = new TraceInterval[oldLen + newLen];
        int oldIdx = 0;
        int newIdx = 0;

        while (oldIdx + newIdx < combinedList.length) {
            if (newIdx >= newLen || (oldIdx < oldLen && oldList[oldIdx].from() <= newList[newIdx].from())) {
                combinedList[oldIdx + newIdx] = oldList[oldIdx];
                oldIdx++;
            } else {
                combinedList[oldIdx + newIdx] = newList[newIdx];
                newIdx++;
            }
        }

        sortedIntervals = combinedList;
    }

    // wrapper for Interval.splitChildAtOpId that performs a bailout in product mode
    // instead of returning null
    public TraceInterval splitChildAtOpId(TraceInterval interval, int opId, LIRInstruction.OperandMode mode) {
        TraceInterval result = interval.getSplitChildAtOpId(opId, mode, this);

        if (result != null) {
            if (Debug.isLogEnabled()) {
                Debug.log("Split child at pos %d of interval %s is %s", opId, interval, result);
            }
            return result;
        }

        throw new BailoutException("LinearScan: interval is null");
    }

    static StackSlotValue canonicalSpillOpr(TraceInterval interval) {
        assert interval.spillSlot() != null : "canonical spill slot not set";
        return interval.spillSlot();
    }

    boolean isMaterialized(AllocatableValue operand, int opId, OperandMode mode) {
        TraceInterval interval = intervalFor(operand);
        assert interval != null : "interval must exist";

        if (opId != -1) {
            /*
             * Operands are not changed when an interval is split during allocation, so search the
             * right interval here.
             */
            interval = splitChildAtOpId(interval, opId, mode);
        }

        return isIllegal(interval.location()) && interval.canMaterialize();
    }

    boolean isCallerSave(Value operand) {
        return attributes(asRegister(operand)).isCallerSave();
    }

    protected <B extends AbstractBlockBase<B>> void allocate(TargetDescription target, LIRGenerationResult lirGenRes, List<B> codeEmittingOrder, List<B> linearScanOrder,
                    SpillMoveFactory spillMoveFactory, RegisterAllocationConfig registerAllocationConfig) {

        /*
         * This is the point to enable debug logging for the whole register allocation.
         */
        try (Indent indent = Debug.logAndIndent("LinearScan allocate")) {
            AllocationContext context = new AllocationContext(spillMoveFactory, registerAllocationConfig);

            createLifetimeAnalysisPhase().apply(target, lirGenRes, codeEmittingOrder, linearScanOrder, context, false);

            try (Scope s = Debug.scope("AfterLifetimeAnalysis", (Object) intervals())) {
                sortIntervalsBeforeAllocation();

                createRegisterAllocationPhase().apply(target, lirGenRes, codeEmittingOrder, linearScanOrder, context, false);

                if (com.oracle.graal.lir.alloc.lsra.LinearScan.Options.LIROptLSRAOptimizeSpillPosition.getValue()) {
                    createOptimizeSpillPositionPhase().apply(target, lirGenRes, codeEmittingOrder, linearScanOrder, context, false);
                }
                // resolve intra-trace data-flow
                TraceLinearScanResolveDataFlowPhase dataFlowPhase = createResolveDataFlowPhase();
                dataFlowPhase.apply(target, lirGenRes, codeEmittingOrder, linearScanOrder, context, false);
                Debug.dump(TraceRegisterAllocationPhase.TRACE_DUMP_LEVEL, sortedBlocks(), "%s", dataFlowPhase.getName());

                TraceLinearScanAssignLocationsPhase assignPhase = createAssignLocationsPhase();
                assignPhase.apply(target, lirGenRes, codeEmittingOrder, linearScanOrder, context, false);

                if (DetailedAsserts.getValue()) {
                    verifyIntervals();
                }
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
        }
    }

    protected TraceLinearScanLifetimeAnalysisPhase createLifetimeAnalysisPhase() {
        if (Options.TraceRAsimpleLifetimeAnalysis.getValue()) {
            return new TraceSimpleLifetimeAnalysisPhase(this, traceBuilderResult);
        }
        return new TraceLinearScanLifetimeAnalysisPhase(this, traceBuilderResult);
    }

    protected TraceLinearScanResolveDataFlowPhase createResolveDataFlowPhase() {
        return new TraceLinearScanResolveDataFlowPhase(this);
    }

    protected TraceLinearScanEliminateSpillMovePhase createSpillMoveEliminationPhase() {
        return new TraceLinearScanEliminateSpillMovePhase(this);
    }

    protected TraceLinearScanAssignLocationsPhase createAssignLocationsPhase() {
        return new TraceLinearScanAssignLocationsPhase(this, traceBuilderResult);
    }

    protected void beforeSpillMoveElimination() {
    }

    protected TraceLinearScanRegisterAllocationPhase createRegisterAllocationPhase() {
        return new TraceLinearScanRegisterAllocationPhase(this);
    }

    protected TraceLinearScanOptimizeSpillPositionPhase createOptimizeSpillPositionPhase() {
        return new TraceLinearScanOptimizeSpillPositionPhase(this);
    }

    public void printIntervals(String label) {
        if (Debug.isDumpEnabled(TraceRegisterAllocationPhase.TRACE_DUMP_LEVEL)) {
            if (Debug.isLogEnabled()) {
                try (Indent indent = Debug.logAndIndent("intervals %s", label)) {
                    for (TraceInterval interval : intervals) {
                        if (interval != null) {
                            Debug.log("%s", interval.logString(this));
                        }
                    }

                    try (Indent indent2 = Debug.logAndIndent("Basic Blocks")) {
                        for (int i = 0; i < blockCount(); i++) {
                            AbstractBlockBase<?> block = blockAt(i);
                            Debug.log("B%d [%d, %d, %s] ", block.getId(), getFirstLirInstructionId(block), getLastLirInstructionId(block), block.getLoop());
                        }
                    }
                }
            }
            Debug.dump(Arrays.copyOf(intervals, intervalsSize), label);
        }
    }

    public void printLir(String label, @SuppressWarnings("unused") boolean hirValid) {
        if (Debug.isDumpEnabled(TraceRegisterAllocationPhase.TRACE_DUMP_LEVEL)) {
            Debug.dump(TraceRegisterAllocationPhase.TRACE_DUMP_LEVEL, sortedBlocks(), label);
        }
    }

    boolean verify() {
        // (check that all intervals have a correct register and that no registers are overwritten)
        verifyIntervals();

        verifyRegisters();

        Debug.log("no errors found");

        return true;
    }

    private void verifyRegisters() {
        // Enable this logging to get output for the verification process.
        try (Indent indent = Debug.logAndIndent("verifying register allocation")) {
            RegisterVerifier verifier = new RegisterVerifier(this);
            verifier.verify(blockAt(0));
        }
    }

    protected void verifyIntervals() {
        try (Indent indent = Debug.logAndIndent("verifying intervals")) {
            int len = intervalsSize;

            for (int i = 0; i < len; i++) {
                TraceInterval i1 = intervals[i];
                if (i1 == null) {
                    continue;
                }

                i1.checkSplitChildren();

                if (i1.operandNumber != i) {
                    Debug.log("Interval %d is on position %d in list", i1.operandNumber, i);
                    Debug.log(i1.logString(this));
                    throw new JVMCIError("");
                }

                if (isVariable(i1.operand) && i1.kind().equals(LIRKind.Illegal)) {
                    Debug.log("Interval %d has no type assigned", i1.operandNumber);
                    Debug.log(i1.logString(this));
                    throw new JVMCIError("");
                }

                if (i1.location() == null) {
                    Debug.log("Interval %d has no register assigned", i1.operandNumber);
                    Debug.log(i1.logString(this));
                    throw new JVMCIError("");
                }

                if (i1.first() == Range.EndMarker) {
                    Debug.log("Interval %d has no Range", i1.operandNumber);
                    Debug.log(i1.logString(this));
                    throw new JVMCIError("");
                }

                for (Range r = i1.first(); r != Range.EndMarker; r = r.next) {
                    if (r.from >= r.to) {
                        Debug.log("Interval %d has zero length range", i1.operandNumber);
                        Debug.log(i1.logString(this));
                        throw new JVMCIError("");
                    }
                }

                for (int j = i + 1; j < len; j++) {
                    TraceInterval i2 = intervals[j];
                    if (i2 == null) {
                        continue;
                    }

                    // special intervals that are created in MoveResolver
                    // . ignore them because the range information has no meaning there
                    if (i1.from() == 1 && i1.to() == 2) {
                        continue;
                    }
                    if (i2.from() == 1 && i2.to() == 2) {
                        continue;
                    }
                    Value l1 = i1.location();
                    Value l2 = i2.location();
                    if (i1.intersects(i2) && !isIllegal(l1) && (l1.equals(l2))) {
                        if (DetailedAsserts.getValue()) {
                            Debug.log("Intervals %d and %d overlap and have the same register assigned", i1.operandNumber, i2.operandNumber);
                            Debug.log(i1.logString(this));
                            Debug.log(i2.logString(this));
                        }
                        throw new BailoutException("");
                    }
                }
            }
        }
    }

    class CheckConsumer implements ValueConsumer {

        boolean ok;
        TraceInterval curInterval;

        @Override
        public void visitValue(Value operand, OperandMode mode, EnumSet<OperandFlag> flags) {
            if (isRegister(operand)) {
                if (intervalFor(operand) == curInterval) {
                    ok = true;
                }
            }
        }
    }

    void verifyNoOopsInFixedIntervals() {
        try (Indent indent = Debug.logAndIndent("verifying that no oops are in fixed intervals *")) {
            CheckConsumer checkConsumer = new CheckConsumer();

            TraceInterval fixedIntervals;
            TraceInterval otherIntervals;
            fixedIntervals = createUnhandledLists(IS_PRECOLORED_INTERVAL, null).first;
            // to ensure a walking until the last instruction id, add a dummy interval
            // with a high operation id
            otherIntervals = new TraceInterval(Value.ILLEGAL, -1);
            otherIntervals.addRange(Integer.MAX_VALUE - 2, Integer.MAX_VALUE - 1);
            TraceIntervalWalker iw = new TraceIntervalWalker(this, fixedIntervals, otherIntervals);

            for (AbstractBlockBase<?> block : sortedBlocks) {
                List<LIRInstruction> instructions = ir.getLIRforBlock(block);

                for (int j = 0; j < instructions.size(); j++) {
                    LIRInstruction op = instructions.get(j);

                    if (op.hasState()) {
                        iw.walkBefore(op.id());
                        boolean checkLive = true;

                        /*
                         * Make sure none of the fixed registers is live across an oopmap since we
                         * can't handle that correctly.
                         */
                        if (checkLive) {
                            for (TraceInterval interval = iw.activeLists.get(RegisterBinding.Fixed); interval != TraceInterval.EndMarker; interval = interval.next) {
                                if (interval.currentTo() > op.id() + 1) {
                                    /*
                                     * This interval is live out of this op so make sure that this
                                     * interval represents some value that's referenced by this op
                                     * either as an input or output.
                                     */
                                    checkConsumer.curInterval = interval;
                                    checkConsumer.ok = false;

                                    op.visitEachInput(checkConsumer);
                                    op.visitEachAlive(checkConsumer);
                                    op.visitEachTemp(checkConsumer);
                                    op.visitEachOutput(checkConsumer);

                                    assert checkConsumer.ok : "fixed intervals should never be live across an oopmap point";
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public LIR getLIR() {
        return ir;
    }

    public FrameMapBuilder getFrameMapBuilder() {
        return frameMapBuilder;
    }

    public List<? extends AbstractBlockBase<?>> sortedBlocks() {
        return sortedBlocks;
    }

    public Register[] getRegisters() {
        return registers;
    }

    public RegisterAllocationConfig getRegisterAllocationConfig() {
        return regAllocConfig;
    }

    public boolean callKillsRegisters() {
        return regAllocConfig.getRegisterConfig().areAllAllocatableRegistersCallerSaved();
    }

}
