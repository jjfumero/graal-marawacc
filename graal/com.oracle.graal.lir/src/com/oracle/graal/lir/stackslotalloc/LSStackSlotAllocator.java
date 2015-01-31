/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.lir.stackslotalloc;

import static com.oracle.graal.api.code.ValueUtil.*;

import java.util.*;
import java.util.function.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.cfg.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.LIRInstruction.OperandFlag;
import com.oracle.graal.lir.LIRInstruction.OperandMode;
import com.oracle.graal.lir.framemap.*;
import com.oracle.graal.lir.gen.*;
import com.oracle.graal.options.*;

/**
 * Linear Scan {@link StackSlotAllocator}.
 * <p>
 * <b>Remark:</b> The analysis works under the assumption that a stack slot is no longer live after
 * its last usage. If an {@link LIRInstruction instruction} transfers the raw address of the stack
 * slot to another location, e.g. a registers, and this location is referenced later on, the
 * {@link com.oracle.graal.lir.LIRInstruction.Use usage} of the stack slot must be marked with the
 * {@link OperandFlag#UNINITIALIZED}. Otherwise the stack slot might be reused and its content
 * destroyed.
 */
public final class LSStackSlotAllocator implements StackSlotAllocator {

    public static class Options {
        // @formatter:off
        @Option(help = "Use linear scan stack slot allocation.", type = OptionType.Debug)
        public static final OptionValue<Boolean> LSStackSlotAllocation = new OptionValue<>(true);
        // @formatter:on
    }

    public void allocateStackSlots(FrameMapBuilderTool builder, LIRGenerationResult res) {
        new Allocator(res.getLIR(), builder).allocate();
    }

    private static final class Allocator extends InstructionNumberer {
        private final LIR lir;
        private final FrameMapBuilderTool frameMapBuilder;
        private final StackInterval[] stackSlotMap;
        private PriorityQueue<StackInterval> unhandled;
        private PriorityQueue<StackInterval> active;

        private List<? extends AbstractBlock<?>> sortedBlocks;

        private Allocator(LIR lir, FrameMapBuilderTool frameMapBuilder) {
            this.lir = lir;
            this.frameMapBuilder = frameMapBuilder;
            this.stackSlotMap = new StackInterval[frameMapBuilder.getNumberOfStackSlots()];
        }

        private void allocate() {
            // create block ordering
            List<? extends AbstractBlock<?>> blocks = lir.getControlFlowGraph().getBlocks();
            assert blocks.size() > 0;

            sortedBlocks = lir.getControlFlowGraph().getBlocks();
            numberInstructions(lir, sortedBlocks);
            Debug.dump(lir, "After StackSlot numbering");

            long currentFrameSize = Debug.isMeterEnabled() ? frameMapBuilder.getFrameMap().currentFrameSize() : 0;
            // build intervals
            try (Scope s = Debug.scope("StackSlotAllocationBuildIntervals"); Indent indent = Debug.logAndIndent("BuildIntervals")) {
                buildIntervals();
            }
            if (Debug.isEnabled()) {
                verifyIntervals();
            }
            if (Debug.isDumpEnabled()) {
                dumpIntervals("Before stack slot allocation");
            }
            // allocate stack slots
            allocateStackSlots();
            if (Debug.isDumpEnabled()) {
                dumpIntervals("After stack slot allocation");
            }

            // assign stack slots
            assignStackSlots();
            Debug.dump(lir, "After StackSlot assignment");
            StackSlotAllocator.allocatedFramesize.add(frameMapBuilder.getFrameMap().currentFrameSize() - currentFrameSize);
        }

        private void buildIntervals() {
            new FixPointIntervalBuilder(lir, stackSlotMap, maxOpId()).build();
        }

        private StackInterval get(VirtualStackSlot stackSlot) {
            return stackSlotMap[stackSlot.getId()];
        }

        private void verifyIntervals() {
            forEachInterval(interval -> {
                assert interval.verify(maxOpId());
            });
        }

        private void forEachInterval(Consumer<StackInterval> proc) {
            for (StackInterval interval : stackSlotMap) {
                if (interval != null) {
                    proc.accept(interval);
                }
            }
        }

        public void dumpIntervals(String label) {
            Debug.dump(stackSlotMap, label);
        }

        private void createUnhandled() {
            Comparator<? super StackInterval> insertByFrom = (a, b) -> a.from() - b.from();
            Comparator<? super StackInterval> insertByTo = (a, b) -> a.to() - b.to();

            unhandled = new PriorityQueue<>(insertByFrom);
            active = new PriorityQueue<>(insertByTo);

            // add all intervals to unhandled list
            forEachInterval(unhandled::add);
        }

        private void allocateStackSlots() {
            // create interval lists
            createUnhandled();

            for (StackInterval current = activateNext(); current != null; current = activateNext()) {
                try (Indent indent = Debug.logAndIndent("allocate %s", current)) {
                    allocateSlot(current);
                }
            }

        }

        private void allocateSlot(StackInterval current) {
            VirtualStackSlot virtualSlot = current.getOperand();
            final StackSlot location;
            if (virtualSlot instanceof VirtualStackSlotRange) {
                // No reuse of ranges (yet).
                VirtualStackSlotRange slotRange = (VirtualStackSlotRange) virtualSlot;
                location = frameMapBuilder.getFrameMap().allocateStackSlots(slotRange.getSlots(), slotRange.getObjects());
                StackSlotAllocator.virtualFramesize.add(frameMapBuilder.getFrameMap().spillSlotRangeSize(slotRange.getSlots()));
                StackSlotAllocator.allocatedSlots.increment();
            } else {
                assert virtualSlot instanceof SimpleVirtualStackSlot : "Unexpected VirtualStackSlot type: " + virtualSlot;
                StackSlot slot = findFreeSlot((SimpleVirtualStackSlot) virtualSlot);
                if (slot != null) {
                    /*
                     * Free stack slot available. Note that we create a new one because the kind
                     * might not match.
                     */
                    location = StackSlot.get(current.kind(), slot.getRawOffset(), slot.getRawAddFrameSize());
                    StackSlotAllocator.reusedSlots.increment();
                    Debug.log(1, "Reuse stack slot %s (reallocated from %s) for virtual stack slot %s", location, slot, virtualSlot);
                } else {
                    // Allocate new stack slot.
                    location = frameMapBuilder.getFrameMap().allocateSpillSlot(virtualSlot.getLIRKind());
                    StackSlotAllocator.virtualFramesize.add(frameMapBuilder.getFrameMap().spillSlotSize(virtualSlot.getLIRKind()));
                    StackSlotAllocator.allocatedSlots.increment();
                    Debug.log(1, "New stack slot %s for virtual stack slot %s", location, virtualSlot);
                }
            }
            Debug.log("Allocate location %s for interval %s", location, current);
            current.setLocation(location);
        }

        private static enum SlotSize {
            Size1,
            Size2,
            Size4,
            Size8,
            Illegal;
        }

        private SlotSize forKind(LIRKind kind) {
            switch (frameMapBuilder.getFrameMap().spillSlotSize(kind)) {
                case 1:
                    return SlotSize.Size1;
                case 2:
                    return SlotSize.Size2;
                case 4:
                    return SlotSize.Size4;
                case 8:
                    return SlotSize.Size8;
                default:
                    return SlotSize.Illegal;
            }
        }

        private EnumMap<SlotSize, LinkedList<StackSlot>> freeSlots = new EnumMap<>(SlotSize.class);

        private StackSlot findFreeSlot(SimpleVirtualStackSlot slot) {
            assert slot != null;
            SlotSize size = forKind(slot.getLIRKind());
            LinkedList<StackSlot> freeList = size == SlotSize.Illegal ? null : freeSlots.get(size);
            if (freeList == null) {
                return null;
            }
            return freeList.pollFirst();
        }

        private void freeSlot(StackSlot slot) {
            SlotSize size = forKind(slot.getLIRKind());
            if (size == SlotSize.Illegal) {
                return;
            }
            LinkedList<StackSlot> freeList = freeSlots.get(size);
            if (freeList == null) {
                freeList = new LinkedList<>();
                freeSlots.put(size, freeList);
            }
            freeList.add(slot);
        }

        private StackInterval activateNext() {
            if (unhandled.isEmpty()) {
                return null;
            }
            StackInterval next = unhandled.poll();
            for (int id = next.from(); activePeekId() < id;) {
                finished(active.poll());
            }
            Debug.log("active %s", next);
            active.add(next);
            return next;
        }

        private int activePeekId() {
            StackInterval first = active.peek();
            if (first == null) {
                return Integer.MAX_VALUE;
            }
            return first.to();
        }

        private void finished(StackInterval interval) {
            StackSlot location = interval.location();
            Debug.log("finished %s (freeing %s)", interval, location);
            freeSlot(location);
        }

        private void assignStackSlots() {
            for (AbstractBlock<?> block : sortedBlocks) {
                lir.getLIRforBlock(block).forEach(op -> {
                    op.forEachInput(this::assignSlot);
                    op.forEachAlive(this::assignSlot);
                    op.forEachState(this::assignSlot);

                    op.forEachTemp(this::assignSlot);
                    op.forEachOutput(this::assignSlot);
                });
            }
        }

        /**
         * @see ValueProcedure
         * @param value
         * @param mode
         * @param flags
         */
        private Value assignSlot(Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            if (isVirtualStackSlot(value)) {
                VirtualStackSlot slot = asVirtualStackSlot(value);
                StackInterval interval = get(slot);
                assert interval != null;
                return interval.location();
            }
            return value;
        }
    }
}
