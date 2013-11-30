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
package com.oracle.graal.compiler;

import static com.oracle.graal.compiler.GraalCompiler.Options.*;
import static com.oracle.graal.compiler.MethodFilter.*;
import static com.oracle.graal.phases.GraalOptions.*;

import java.util.*;

import com.oracle.graal.alloc.*;
import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.alloc.*;
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.compiler.target.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.debug.internal.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.cfg.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.options.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.PhasePlan.PhasePosition;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.phases.graph.*;
import com.oracle.graal.phases.schedule.*;
import com.oracle.graal.phases.tiers.*;
import com.oracle.graal.phases.util.*;

/**
 * Static methods for orchestrating the compilation of a {@linkplain StructuredGraph graph}.
 */
public class GraalCompiler {

    private static final DebugTimer FrontEnd = Debug.timer("FrontEnd");
    private static final DebugTimer BackEnd = Debug.timer("BackEnd");

    /**
     * The set of positive filters specified by the {@code -G:IntrinsificationsEnabled} option. To
     * enable a fast path in {@link #shouldIntrinsify(JavaMethod)}, this field is {@code null} when
     * no enabling/disabling filters are specified.
     */
    private static final MethodFilter[] positiveIntrinsificationFilter;

    /**
     * The set of negative filters specified by the {@code -G:IntrinsificationsDisabled} option.
     */
    private static final MethodFilter[] negativeIntrinsificationFilter;

    static class Options {

        // @formatter:off
        /**
         * @see MethodFilter
         */
        @Option(help = "Pattern for method(s) to which intrinsification (if available) will be applied. " +
                       "By default, all available intrinsifications are applied except for methods matched " +
                       "by IntrinsificationsDisabled. See MethodFilter class for pattern syntax.")
        public static final OptionValue<String> IntrinsificationsEnabled = new OptionValue<>(null);
        /**
         * @see MethodFilter
         */
        @Option(help = "Pattern for method(s) to which intrinsification will not be applied. " +
                       "See MethodFilter class for pattern syntax.")
        public static final OptionValue<String> IntrinsificationsDisabled = new OptionValue<>(null);
        // @formatter:on

    }

    static {
        if (IntrinsificationsDisabled.getValue() != null) {
            negativeIntrinsificationFilter = parse(IntrinsificationsDisabled.getValue());
        } else {
            negativeIntrinsificationFilter = null;
        }

        if (Options.IntrinsificationsEnabled.getValue() != null) {
            positiveIntrinsificationFilter = parse(IntrinsificationsEnabled.getValue());
        } else if (negativeIntrinsificationFilter != null) {
            positiveIntrinsificationFilter = new MethodFilter[0];
        } else {
            positiveIntrinsificationFilter = null;
        }
    }

    /**
     * Determines if a given method should be intrinsified based on the values of
     * {@link Options#IntrinsificationsEnabled} and {@link Options#IntrinsificationsDisabled}.
     */
    public static boolean shouldIntrinsify(JavaMethod method) {
        if (positiveIntrinsificationFilter == null) {
            return true;
        }
        if (positiveIntrinsificationFilter.length == 0 || matches(positiveIntrinsificationFilter, method)) {
            return negativeIntrinsificationFilter == null || !matches(negativeIntrinsificationFilter, method);
        }
        return false;
    }

    /**
     * Requests compilation of a given graph.
     * 
     * @param graph the graph to be compiled
     * @param cc the calling convention for calls to the code compiled for {@code graph}
     * @param installedCodeOwner the method the compiled code will be
     *            {@linkplain InstalledCode#getMethod() associated} with once installed. This
     *            argument can be null.
     * @return the result of the compilation
     */
    public static <T extends CompilationResult> T compileGraph(StructuredGraph graph, CallingConvention cc, ResolvedJavaMethod installedCodeOwner, Providers providers, Backend backend,
                    TargetDescription target, GraphCache cache, PhasePlan plan, OptimisticOptimizations optimisticOpts, SpeculationLog speculationLog, Suites suites, T compilationResult) {
        try (Scope s = Debug.scope("GraalCompiler", graph, providers.getCodeCache())) {
            compileGraphNoScope(graph, cc, installedCodeOwner, providers, backend, target, cache, plan, optimisticOpts, speculationLog, suites, compilationResult);
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
        return compilationResult;
    }

    /**
     * Same as {@link #compileGraph} but without entering a
     * {@linkplain Debug#scope(String, Object...) debug scope}.
     */
    public static <T extends CompilationResult> T compileGraphNoScope(StructuredGraph graph, CallingConvention cc, ResolvedJavaMethod installedCodeOwner, Providers providers, Backend backend,
                    TargetDescription target, GraphCache cache, PhasePlan plan, OptimisticOptimizations optimisticOpts, SpeculationLog speculationLog, Suites suites, T compilationResult) {
        Assumptions assumptions = new Assumptions(OptAssumptions.getValue());

        LIR lir = null;
        try (Scope s = Debug.scope("FrontEnd"); TimerCloseable a = FrontEnd.start()) {
            lir = emitHIR(providers, target, graph, assumptions, cache, plan, optimisticOpts, speculationLog, suites);
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
        try (TimerCloseable a = BackEnd.start()) {
            LIRGenerator lirGen = null;
            try (Scope s = Debug.scope("BackEnd", lir)) {
                lirGen = emitLIR(backend, target, lir, graph, cc);
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
            try (Scope s = Debug.scope("CodeGen", lirGen)) {
                emitCode(backend, getLeafGraphIdArray(graph), assumptions, lirGen, compilationResult, installedCodeOwner);
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
        } catch (Throwable e) {
            throw Debug.handle(e);
        }

        return compilationResult;
    }

    private static long[] getLeafGraphIdArray(StructuredGraph graph) {
        long[] leafGraphIdArray = new long[graph.getLeafGraphIds().size() + 1];
        int i = 0;
        leafGraphIdArray[i++] = graph.graphId();
        for (long id : graph.getLeafGraphIds()) {
            leafGraphIdArray[i++] = id;
        }
        return leafGraphIdArray;
    }

    /**
     * Builds the graph, optimizes it.
     */
    public static LIR emitHIR(Providers providers, TargetDescription target, StructuredGraph graph, Assumptions assumptions, GraphCache cache, PhasePlan plan, OptimisticOptimizations optimisticOpts,
                    SpeculationLog speculationLog, Suites suites) {

        if (speculationLog != null) {
            speculationLog.snapshot();
        }

        if (graph.start().next() == null) {
            plan.runPhases(PhasePosition.AFTER_PARSING, graph);
            new DeadCodeEliminationPhase().apply(graph);
        } else {
            Debug.dump(graph, "initial state");
        }

        HighTierContext highTierContext = new HighTierContext(providers, assumptions, cache, plan, optimisticOpts);
        suites.getHighTier().apply(graph, highTierContext);
        graph.maybeCompress();

        MidTierContext midTierContext = new MidTierContext(providers, assumptions, target, optimisticOpts);
        suites.getMidTier().apply(graph, midTierContext);
        graph.maybeCompress();

        LowTierContext lowTierContext = new LowTierContext(providers, assumptions, target);
        suites.getLowTier().apply(graph, lowTierContext);
        graph.maybeCompress();

        // we do not want to store statistics about OSR compilations because it may prevent inlining
        if (!graph.isOSR()) {
            InliningPhase.storeStatisticsAfterLowTier(graph);
        }

        SchedulePhase schedule = new SchedulePhase();
        schedule.apply(graph);
        Debug.dump(schedule, "final schedule");

        Block[] blocks = schedule.getCFG().getBlocks();
        Block startBlock = schedule.getCFG().getStartBlock();
        assert startBlock != null;
        assert startBlock.getPredecessorCount() == 0;

        try (Scope s = Debug.scope("ComputeLinearScanOrder")) {
            NodesToDoubles nodeProbabilities = new ComputeProbabilityClosure(graph).apply();
            List<Block> codeEmittingOrder = ComputeBlockOrder.computeCodeEmittingOrder(blocks.length, startBlock, nodeProbabilities);
            List<Block> linearScanOrder = ComputeBlockOrder.computeLinearScanOrder(blocks.length, startBlock, nodeProbabilities);

            LIR lir = new LIR(schedule.getCFG(), schedule.getBlockToNodesMap(), linearScanOrder, codeEmittingOrder);
            Debug.dump(lir, "After linear scan order");
            return lir;
        } catch (Throwable e) {
            throw Debug.handle(e);
        }

    }

    private static void emitBlock(LIRGenerator lirGen, Block b) {
        if (lirGen.lir.lir(b) == null) {
            for (Block pred : b.getPredecessors()) {
                if (!b.isLoopHeader() || !pred.isLoopEnd()) {
                    emitBlock(lirGen, pred);
                }
            }
            lirGen.doBlock(b);
        }
    }

    public static LIRGenerator emitLIR(Backend backend, TargetDescription target, LIR lir, StructuredGraph graph, CallingConvention cc) {
        FrameMap frameMap = backend.newFrameMap();
        LIRGenerator lirGen = backend.newLIRGenerator(graph, frameMap, cc, lir);

        try (Scope s = Debug.scope("LIRGen", lirGen)) {
            for (Block b : lir.linearScanOrder()) {
                emitBlock(lirGen, b);
            }

            Debug.dump(lir, "After LIR generation");
        } catch (Throwable e) {
            throw Debug.handle(e);
        }

        lirGen.beforeRegisterAllocation();

        try (Scope s = Debug.scope("Allocator")) {
            if (backend.shouldAllocateRegisters()) {
                new LinearScan(target, lir, lirGen, frameMap).allocate();
            }
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
        return lirGen;
    }

    public static void emitCode(Backend backend, long[] leafGraphIds, Assumptions assumptions, LIRGenerator lirGen, CompilationResult compilationResult, ResolvedJavaMethod installedCodeOwner) {
        TargetMethodAssembler tasm = backend.newAssembler(lirGen, compilationResult);
        backend.emitCode(tasm, lirGen, installedCodeOwner);
        CompilationResult result = tasm.finishTargetMethod(lirGen.getGraph());
        if (!assumptions.isEmpty()) {
            result.setAssumptions(assumptions);
        }
        result.setLeafGraphIds(leafGraphIds);

        if (Debug.isLogEnabled()) {
            Debug.log("%s", backend.getProviders().getCodeCache().disassemble(result, null));
        }

        Debug.dump(result, "After code generation");
    }
}
