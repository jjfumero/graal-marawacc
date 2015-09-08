/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
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

import static jdk.internal.jvmci.code.ValueUtil.isStackSlotValue;

import java.util.List;

import jdk.internal.jvmci.code.TargetDescription;
import jdk.internal.jvmci.options.Option;
import jdk.internal.jvmci.options.OptionType;
import jdk.internal.jvmci.options.OptionValue;

import com.oracle.graal.compiler.common.alloc.RegisterAllocationConfig;
import com.oracle.graal.compiler.common.alloc.TraceBuilder;
import com.oracle.graal.compiler.common.alloc.TraceBuilder.TraceBuilderResult;
import com.oracle.graal.compiler.common.cfg.AbstractBlockBase;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.debug.DebugMetric;
import com.oracle.graal.debug.Indent;
import com.oracle.graal.lir.LIR;
import com.oracle.graal.lir.LIRInstruction;
import com.oracle.graal.lir.StandardOp.JumpOp;
import com.oracle.graal.lir.StandardOp.LabelOp;
import com.oracle.graal.lir.StandardOp.ValueMoveOp;
import com.oracle.graal.lir.alloc.trace.TraceAllocationPhase.TraceAllocationContext;
import com.oracle.graal.lir.gen.LIRGenerationResult;
import com.oracle.graal.lir.gen.LIRGeneratorTool.SpillMoveFactory;
import com.oracle.graal.lir.phases.AllocationPhase;
import com.oracle.graal.lir.ssi.SSIUtil;
import com.oracle.graal.lir.ssi.SSIVerifier;

public final class TraceRegisterAllocationPhase extends AllocationPhase {
    public static class Options {
        // @formatter:off
        @Option(help = "Use inter-trace register hints.", type = OptionType.Debug)
        public static final OptionValue<Boolean> TraceRAuseInterTraceHints = new OptionValue<>(true);
        @Option(help = "Use special allocator for trivial blocks.", type = OptionType.Debug)
        public static final OptionValue<Boolean> TraceRAtrivialBlockAllocator = new OptionValue<>(true);
        @Option(help = "Share information about spilled values to other traces.", type = OptionType.Debug)
        public static final OptionValue<Boolean> TraceRAshareSpillInformation = new OptionValue<>(true);
        // @formatter:on
    }

    static final int TRACE_DUMP_LEVEL = 3;
    private static final DebugMetric trivialTracesMetric = Debug.metric("TraceRA[trivialTraces]");
    private static final DebugMetric tracesMetric = Debug.metric("TraceRA[traces]");

    @Override
    @SuppressWarnings("try")
    protected <B extends AbstractBlockBase<B>> void run(TargetDescription target, LIRGenerationResult lirGenRes, List<B> codeEmittingOrder, List<B> linearScanOrder, SpillMoveFactory spillMoveFactory,
                    RegisterAllocationConfig registerAllocationConfig) {
        LIR lir = lirGenRes.getLIR();
        assert SSIVerifier.verify(lir) : "LIR not in SSI form.";
        B startBlock = linearScanOrder.get(0);
        assert startBlock.equals(lir.getControlFlowGraph().getStartBlock());
        TraceBuilderResult<B> resultTraces = TraceBuilder.computeTraces(startBlock, linearScanOrder);

        Debug.dump(lir, "Before TraceRegisterAllocation");
        int traceNumber = 0;
        for (List<B> trace : resultTraces.getTraces()) {
            try (Indent i = Debug.logAndIndent("Allocating Trace%d: %s", traceNumber, trace); Scope s = Debug.scope("AllocateTrace", trace)) {
                tracesMetric.increment();
                if (trivialTracesMetric.isEnabled() && isTrivialTrace(lir, trace)) {
                    trivialTracesMetric.increment();
                }
                Debug.dump(TRACE_DUMP_LEVEL, trace, "Trace" + traceNumber + ": " + trace);
                if (Options.TraceRAtrivialBlockAllocator.getValue() && isTrivialTrace(lir, trace)) {
                    new TraceTrivialAllocator(resultTraces).apply(target, lirGenRes, codeEmittingOrder, trace, new TraceAllocationContext(spillMoveFactory, registerAllocationConfig), false);
                } else {
                    TraceLinearScan allocator = new TraceLinearScan(target, lirGenRes, spillMoveFactory, registerAllocationConfig, trace, resultTraces);
                    allocator.allocate(target, lirGenRes, codeEmittingOrder, linearScanOrder, spillMoveFactory, registerAllocationConfig);
                }
                Debug.dump(TRACE_DUMP_LEVEL, trace, "After Trace" + traceNumber + ": " + trace);
                traceNumber++;
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
            unnumberInstructions(trace, lir);
        }
        Debug.dump(lir, "After trace allocation");

        new TraceGlobalMoveResolutionPhase(resultTraces).apply(target, lirGenRes, codeEmittingOrder, linearScanOrder, new TraceAllocationContext(spillMoveFactory, registerAllocationConfig));

        try (Scope s = Debug.scope("TraceRegisterAllocationFixup")) {
            if (replaceStackToStackMoves(lir, spillMoveFactory)) {
                Debug.dump(lir, "After fixing stack to stack moves");
            }
            /*
             * Incoming Values are needed for the RegisterVerifier, otherwise SIGMAs/PHIs where the
             * Out and In value matches (ie. there is no resolution move) are falsely detected as
             * errors.
             */
            for (AbstractBlockBase<?> block : lir.getControlFlowGraph().getBlocks()) {
                try (Indent i = Debug.logAndIndent("Fixup Block %s", block)) {
                    if (block.getPredecessorCount() != 0) {
                        SSIUtil.removeIncoming(lir, block);
                    } else {
                        assert lir.getControlFlowGraph().getStartBlock().equals(block);
                    }
                    SSIUtil.removeOutgoing(lir, block);
                }
            }
        }
    }

    static boolean isTrivialTrace(LIR lir, List<? extends AbstractBlockBase<?>> trace) {
        if (trace.size() != 1) {
            return false;
        }
        List<LIRInstruction> instructions = lir.getLIRforBlock(trace.iterator().next());
        if (instructions.size() != 2) {
            return false;
        }
        assert instructions.get(0) instanceof LabelOp : "First instruction not a LabelOp: " + instructions.get(0);
        /*
         * Now we need to check if the BlockEndOp has no special operand requirements (i.e.
         * stack-slot, register). For now we just check for JumpOp because we know that it doesn't.
         */
        return instructions.get(1) instanceof JumpOp;
    }

    /**
     * Fixup stack to stack moves introduced by stack arguments.
     *
     * TODO (je) find a better solution.
     */
    private static boolean replaceStackToStackMoves(LIR lir, SpillMoveFactory spillMoveFactory) {
        boolean changed = false;
        for (AbstractBlockBase<?> block : lir.getControlFlowGraph().getBlocks()) {
            List<LIRInstruction> instructions = lir.getLIRforBlock(block);
            for (int i = 0; i < instructions.size(); i++) {
                LIRInstruction inst = instructions.get(i);

                if (inst instanceof ValueMoveOp) {
                    ValueMoveOp move = (ValueMoveOp) inst;
                    if (isStackSlotValue(move.getInput()) && isStackSlotValue(move.getResult())) {
                        instructions.set(i, spillMoveFactory.createStackMove(move.getResult(), move.getInput()));
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    private static void unnumberInstructions(List<? extends AbstractBlockBase<?>> trace, LIR lir) {
        trace.stream().flatMap(b -> lir.getLIRforBlock(b).stream()).forEach(op -> op.setId(-1));
    }
}
