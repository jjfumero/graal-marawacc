/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.graal.compiler;

import java.util.*;

import com.oracle.max.asm.*;
import com.oracle.max.cri.ci.*;
import com.oracle.max.cri.ri.*;
import com.oracle.max.cri.xir.*;
import com.oracle.max.criutils.*;
import com.oracle.max.graal.alloc.simple.*;
import com.oracle.max.graal.compiler.alloc.*;
import com.oracle.max.graal.compiler.asm.*;
import com.oracle.max.graal.compiler.gen.*;
import com.oracle.max.graal.compiler.lir.*;
import com.oracle.max.graal.compiler.observer.*;
import com.oracle.max.graal.compiler.phases.*;
import com.oracle.max.graal.compiler.phases.PhasePlan.PhasePosition;
import com.oracle.max.graal.compiler.schedule.*;
import com.oracle.max.graal.compiler.target.*;
import com.oracle.max.graal.cri.*;
import com.oracle.max.graal.debug.*;
import com.oracle.max.graal.graph.*;
import com.oracle.max.graal.nodes.*;

public class GraalCompiler {

    public final GraalContext context;

    /**
     * The target that this compiler has been configured for.
     */
    public final CiTarget target;

    /**
     * The runtime that this compiler has been configured for.
     */
    public final GraalRuntime runtime;

    /**
     * The XIR generator that lowers Java operations to machine operations.
     */
    public final RiXirGenerator xir;

    /**
     * The backend that this compiler has been configured for.
     */
    public final Backend backend;

    public GraalCompiler(GraalContext context, GraalRuntime runtime, CiTarget target, Backend backend, RiXirGenerator xirGen) {
        this.context = context;
        this.runtime = runtime;
        this.target = target;
        this.xir = xirGen;
        this.backend = backend;
    }

    public CiTargetMethod compileMethod(RiResolvedMethod method, int osrBCI, PhasePlan plan) {
        return compileMethod(method, new StructuredGraph(method), osrBCI, plan);
    }

    public CiTargetMethod compileMethod(final RiResolvedMethod method, final StructuredGraph graph, int osrBCI, final PhasePlan plan) {
        if (osrBCI != -1) {
            throw new CiBailout("No OSR supported");
        }
        final CiTargetMethod[] result = new CiTargetMethod[1];
        Debug.scope("CompileMethod", new Runnable() {
                public void run() {
                        TTY.Filter filter = new TTY.Filter(GraalOptions.PrintFilter, method);
                        CiAssumptions assumptions = GraalOptions.OptAssumptions ? new CiAssumptions() : null;
                        LIR lir = emitHIR(graph, assumptions, plan);
                        FrameMap frameMap = emitLIR(lir, graph, method);
                        result[0] = emitCode(assumptions, method, lir, frameMap);
                }
        }, method);
        return result[0];
    }

    /**
     * Builds the graph, optimizes it.
     */
    public LIR emitHIR(StructuredGraph graph, CiAssumptions assumptions, PhasePlan plan) {
        try {
            context.timers.startScope("HIR");

            if (graph.start().next() == null) {
                plan.runPhases(PhasePosition.AFTER_PARSING, graph);
                new DeadCodeEliminationPhase().apply(graph);
            } else {
                if (context.isObserved()) {
                    context.observable.fireCompilationEvent("initial state", graph);
                }
            }

            new PhiStampPhase().apply(graph);

            if (GraalOptions.ProbabilityAnalysis && graph.start().probability() == 0) {
                new ComputeProbabilityPhase().apply(graph);
            }

            if (GraalOptions.Intrinsify) {
                new IntrinsificationPhase(runtime).apply(graph);
            }

            if (GraalOptions.Inline && !plan.isPhaseDisabled(InliningPhase.class)) {
                new InliningPhase(target, runtime, null, assumptions, plan).apply(graph);
                new DeadCodeEliminationPhase().apply(graph);
                new PhiStampPhase().apply(graph);
            }

            if (GraalOptions.OptCanonicalizer) {
                new CanonicalizerPhase(target, runtime, assumptions).apply(graph);
            }

            plan.runPhases(PhasePosition.HIGH_LEVEL, graph);

            if (GraalOptions.OptLoops) {
                graph.mark();
                new FindInductionVariablesPhase().apply(graph);
                if (GraalOptions.OptCanonicalizer) {
                    new CanonicalizerPhase(target, runtime, true, assumptions).apply(graph);
                }
                new SafepointPollingEliminationPhase().apply(graph);
            }

            if (GraalOptions.EscapeAnalysis && !plan.isPhaseDisabled(EscapeAnalysisPhase.class)) {
                new EscapeAnalysisPhase(target, runtime, assumptions, plan).apply(graph);
                new PhiStampPhase().apply(graph);
                new CanonicalizerPhase(target, runtime, assumptions).apply(graph);
            }

            if (GraalOptions.OptGVN) {
                new GlobalValueNumberingPhase().apply(graph);
            }

            graph.mark();
            new LoweringPhase(runtime).apply(graph);
            new CanonicalizerPhase(target, runtime, true, assumptions).apply(graph);

            if (GraalOptions.OptLoops) {
                graph.mark();
                new RemoveInductionVariablesPhase().apply(graph);
                if (GraalOptions.OptCanonicalizer) {
                    new CanonicalizerPhase(target, runtime, true, assumptions).apply(graph);
                }
            }

            if (GraalOptions.Lower) {
                new FloatingReadPhase().apply(graph);
                if (GraalOptions.OptReadElimination) {
                    new ReadEliminationPhase().apply(graph);
                }
            }
            new RemovePlaceholderPhase().apply(graph);
            new DeadCodeEliminationPhase().apply(graph);

            plan.runPhases(PhasePosition.MID_LEVEL, graph);

            plan.runPhases(PhasePosition.LOW_LEVEL, graph);

            IdentifyBlocksPhase schedule = new IdentifyBlocksPhase(true, LIRBlock.FACTORY);
            schedule.apply(graph);

            if (context.isObserved()) {
                context.observable.fireCompilationEvent("After IdentifyBlocksPhase", graph, schedule);
            }

            List<Block> blocks = schedule.getBlocks();
            NodeMap<LIRBlock> valueToBlock = new NodeMap<>(graph);
            for (Block b : blocks) {
                for (Node i : b.getInstructions()) {
                    valueToBlock.set(i, (LIRBlock) b);
                }
            }
            LIRBlock startBlock = valueToBlock.get(graph.start());
            assert startBlock != null;
            assert startBlock.numberOfPreds() == 0;

            context.timers.startScope("Compute Linear Scan Order");
            try {
                ComputeLinearScanOrder clso = new ComputeLinearScanOrder(blocks.size(), schedule.loopCount(), startBlock);
                List<LIRBlock> linearScanOrder = clso.linearScanOrder();
                List<LIRBlock> codeEmittingOrder = clso.codeEmittingOrder();

                int z = 0;
                for (LIRBlock b : linearScanOrder) {
                    b.setLinearScanNumber(z++);
                }

                LIR lir = new LIR(startBlock, linearScanOrder, codeEmittingOrder, valueToBlock, schedule.loopCount());

                if (context.isObserved()) {
                    context.observable.fireCompilationEvent("After linear scan order", graph, lir);
                }
                return lir;
            } catch (AssertionError t) {
                    context.observable.fireCompilationEvent("AssertionError in ComputeLinearScanOrder", CompilationEvent.ERROR, graph);
                throw t;
            } catch (RuntimeException t) {
                    context.observable.fireCompilationEvent("RuntimeException in ComputeLinearScanOrder", CompilationEvent.ERROR, graph);
                throw t;
            } finally {
                context.timers.endScope();
            }
        } finally {
            context.timers.endScope();
        }
    }

    public FrameMap emitLIR(LIR lir, StructuredGraph graph, RiResolvedMethod method) {
        context.timers.startScope("LIR");
        try {
            if (GraalOptions.GenLIR) {
                context.timers.startScope("Create LIR");
                LIRGenerator lirGenerator = null;
                FrameMap frameMap;
                try {
                    frameMap = backend.newFrameMap(runtime.getRegisterConfig(method));

                    lirGenerator = backend.newLIRGenerator(context, graph, frameMap, method, lir, xir);

                    for (LIRBlock b : lir.linearScanOrder()) {
                        lirGenerator.doBlock(b);
                    }

                    for (LIRBlock b : lir.linearScanOrder()) {
                        if (b.phis != null) {
                            b.phis.fillInputs(lirGenerator);
                        }
                    }
                } finally {
                    context.timers.endScope();
                }

                if (context.isObserved()) {
                    context.observable.fireCompilationEvent("After LIR generation", graph, lir, lirGenerator);
                }
                if (GraalOptions.PrintLIR && !TTY.isSuppressed()) {
                    LIR.printLIR(lir.linearScanOrder());
                }

                if (GraalOptions.AllocSSA) {
                    new SpillAllAllocator(context, lir, frameMap).execute();
                } else {
                    new LinearScan(context, target, method, graph, lir, lirGenerator, frameMap).allocate();
                }
                return frameMap;
            } else {
                return null;
            }
        } catch (Error e) {
            if (context.isObserved() && GraalOptions.PlotOnError) {
                context.observable.fireCompilationEvent(e.getClass().getSimpleName() + " in emitLIR", CompilationEvent.ERROR, graph);
            }
            throw e;
        } catch (RuntimeException e) {
            if (context.isObserved() && GraalOptions.PlotOnError) {
                context.observable.fireCompilationEvent(e.getClass().getSimpleName() + " in emitLIR", CompilationEvent.ERROR, graph);
            }
            throw e;
        } finally {
            context.timers.endScope();
        }
    }

    private TargetMethodAssembler createAssembler(FrameMap frameMap, LIR lir) {
        AbstractAssembler masm = backend.newAssembler(frameMap.registerConfig);
        TargetMethodAssembler tasm = new TargetMethodAssembler(target, runtime, frameMap, lir.slowPaths, masm);
        tasm.setFrameSize(frameMap.frameSize());
        tasm.targetMethod.setCustomStackAreaOffset(frameMap.offsetToCustomArea());
        return tasm;
    }

    public CiTargetMethod emitCode(CiAssumptions assumptions, RiResolvedMethod method, LIR lir, FrameMap frameMap) {
        if (GraalOptions.GenLIR && GraalOptions.GenCode) {
            context.timers.startScope("Create Code");
            try {
                TargetMethodAssembler tasm = createAssembler(frameMap, lir);
                lir.emitCode(tasm);

                CiTargetMethod targetMethod = tasm.finishTargetMethod(method, false);
                if (assumptions != null && !assumptions.isEmpty()) {
                    targetMethod.setAssumptions(assumptions);
                }

                if (context.isObserved()) {
                    context.observable.fireCompilationEvent("After code generation", lir, targetMethod);
                }
                return targetMethod;
            } finally {
                context.timers.endScope();
            }
        }

        return null;
    }
}
