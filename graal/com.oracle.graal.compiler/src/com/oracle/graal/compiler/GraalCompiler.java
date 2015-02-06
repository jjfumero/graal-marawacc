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
package com.oracle.graal.compiler;

import static com.oracle.graal.compiler.GraalCompiler.Options.*;
import static com.oracle.graal.compiler.MethodFilter.*;
import static com.oracle.graal.compiler.common.GraalOptions.*;
import static com.oracle.graal.phases.common.DeadCodeEliminationPhase.Optionality.*;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.CompilationResult.ConstantReference;
import com.oracle.graal.api.code.CompilationResult.DataPatch;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.meta.ProfilingInfo.TriState;
import com.oracle.graal.compiler.common.alloc.*;
import com.oracle.graal.compiler.common.cfg.*;
import com.oracle.graal.compiler.target.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.debug.internal.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.alloc.lsra.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.lir.constopt.*;
import com.oracle.graal.lir.framemap.*;
import com.oracle.graal.lir.gen.*;
import com.oracle.graal.lir.stackslotalloc.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.cfg.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.options.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.common.*;
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
                       "by IntrinsificationsDisabled. See MethodFilter class for pattern syntax.", type = OptionType.Debug)
        public static final OptionValue<String> IntrinsificationsEnabled = new OptionValue<>(null);
        /**
         * @see MethodFilter
         */
        @Option(help = "Pattern for method(s) to which intrinsification will not be applied. " +
                       "See MethodFilter class for pattern syntax.", type = OptionType.Debug)
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
     * Encapsulates all the inputs to a {@linkplain GraalCompiler#compile(Request) compilation}.
     */
    public static class Request<T extends CompilationResult> {
        public final StructuredGraph graph;
        public final CallingConvention cc;
        public final ResolvedJavaMethod installedCodeOwner;
        public final Providers providers;
        public final Backend backend;
        public final TargetDescription target;
        public final Map<ResolvedJavaMethod, StructuredGraph> cache;
        public final PhaseSuite<HighTierContext> graphBuilderSuite;
        public final OptimisticOptimizations optimisticOpts;
        public final ProfilingInfo profilingInfo;
        public final SpeculationLog speculationLog;
        public final Suites suites;
        public final T compilationResult;
        public final CompilationResultBuilderFactory factory;

        /**
         * @param graph the graph to be compiled
         * @param cc the calling convention for calls to the code compiled for {@code graph}
         * @param installedCodeOwner the method the compiled code will be associated with once
         *            installed. This argument can be null.
         * @param providers
         * @param backend
         * @param target
         * @param cache
         * @param graphBuilderSuite
         * @param optimisticOpts
         * @param profilingInfo
         * @param speculationLog
         * @param suites
         * @param compilationResult
         * @param factory
         */
        public Request(StructuredGraph graph, CallingConvention cc, ResolvedJavaMethod installedCodeOwner, Providers providers, Backend backend, TargetDescription target,
                        Map<ResolvedJavaMethod, StructuredGraph> cache, PhaseSuite<HighTierContext> graphBuilderSuite, OptimisticOptimizations optimisticOpts, ProfilingInfo profilingInfo,
                        SpeculationLog speculationLog, Suites suites, T compilationResult, CompilationResultBuilderFactory factory) {
            this.graph = graph;
            this.cc = cc;
            this.installedCodeOwner = installedCodeOwner;
            this.providers = providers;
            this.backend = backend;
            this.target = target;
            this.cache = cache;
            this.graphBuilderSuite = graphBuilderSuite;
            this.optimisticOpts = optimisticOpts;
            this.profilingInfo = profilingInfo;
            this.speculationLog = speculationLog;
            this.suites = suites;
            this.compilationResult = compilationResult;
            this.factory = factory;
        }

        /**
         * Executes this compilation request.
         *
         * @return the result of the compilation
         */
        public T execute() {
            return GraalCompiler.compile(this);
        }
    }

    /**
     * Requests compilation of a given graph.
     *
     * @param graph the graph to be compiled
     * @param cc the calling convention for calls to the code compiled for {@code graph}
     * @param installedCodeOwner the method the compiled code will be associated with once
     *            installed. This argument can be null.
     * @return the result of the compilation
     */
    public static <T extends CompilationResult> T compileGraph(StructuredGraph graph, CallingConvention cc, ResolvedJavaMethod installedCodeOwner, Providers providers, Backend backend,
                    TargetDescription target, Map<ResolvedJavaMethod, StructuredGraph> cache, PhaseSuite<HighTierContext> graphBuilderSuite, OptimisticOptimizations optimisticOpts,
                    ProfilingInfo profilingInfo, SpeculationLog speculationLog, Suites suites, T compilationResult, CompilationResultBuilderFactory factory) {
        return compile(new Request<>(graph, cc, installedCodeOwner, providers, backend, target, cache, graphBuilderSuite, optimisticOpts, profilingInfo, speculationLog, suites, compilationResult,
                        factory));
    }

    /**
     * Services a given compilation request.
     *
     * @return the result of the compilation
     */
    public static <T extends CompilationResult> T compile(Request<T> r) {
        assert !r.graph.isFrozen();
        try (Scope s0 = Debug.scope("GraalCompiler", r.graph, r.providers.getCodeCache())) {
            Assumptions assumptions = new Assumptions(OptAssumptions.getValue());
            SchedulePhase schedule = emitFrontEnd(r.providers, r.target, r.graph, assumptions, r.cache, r.graphBuilderSuite, r.optimisticOpts, r.profilingInfo, r.speculationLog, r.suites);
            emitBackEnd(r.graph, null, r.cc, r.installedCodeOwner, r.backend, r.target, r.compilationResult, r.factory, assumptions, schedule, null);
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
        return r.compilationResult;
    }

    public static ProfilingInfo getProfilingInfo(StructuredGraph graph) {
        if (graph.method() != null) {
            return graph.method().getProfilingInfo();
        } else {
            return DefaultProfilingInfo.get(TriState.UNKNOWN);
        }
    }

    /**
     * Builds the graph, optimizes it.
     */
    public static SchedulePhase emitFrontEnd(Providers providers, TargetDescription target, StructuredGraph graph, Assumptions assumptions, Map<ResolvedJavaMethod, StructuredGraph> cache,
                    PhaseSuite<HighTierContext> graphBuilderSuite, OptimisticOptimizations optimisticOpts, ProfilingInfo profilingInfo, SpeculationLog speculationLog, Suites suites) {
        try (Scope s = Debug.scope("FrontEnd"); TimerCloseable a = FrontEnd.start()) {
            if (speculationLog != null) {
                speculationLog.collectFailedSpeculations();
            }

            HighTierContext highTierContext = new HighTierContext(providers, assumptions, cache, graphBuilderSuite, optimisticOpts);
            if (graph.start().next() == null) {
                graphBuilderSuite.apply(graph, highTierContext);
                new DeadCodeEliminationPhase(Optional).apply(graph);
            } else {
                Debug.dump(graph, "initial state");
            }

            suites.getHighTier().apply(graph, highTierContext);
            graph.maybeCompress();

            MidTierContext midTierContext = new MidTierContext(providers, assumptions, target, optimisticOpts, profilingInfo, speculationLog);
            suites.getMidTier().apply(graph, midTierContext);
            graph.maybeCompress();

            LowTierContext lowTierContext = new LowTierContext(providers, assumptions, target);
            suites.getLowTier().apply(graph, lowTierContext);
            graph.maybeCompress();

            SchedulePhase schedule = new SchedulePhase();
            schedule.apply(graph);
            Debug.dump(schedule, "Final HIR schedule");
            return schedule;
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
    }

    public static <T extends CompilationResult> void emitBackEnd(StructuredGraph graph, Object stub, CallingConvention cc, ResolvedJavaMethod installedCodeOwner, Backend backend,
                    TargetDescription target, T compilationResult, CompilationResultBuilderFactory factory, Assumptions assumptions, SchedulePhase schedule, RegisterConfig registerConfig) {
        try (TimerCloseable a = BackEnd.start()) {
            LIRGenerationResult lirGen = null;
            lirGen = emitLIR(backend, target, schedule, graph, stub, cc, registerConfig);
            try (Scope s = Debug.scope("CodeGen", lirGen)) {
                emitCode(backend, assumptions, lirGen, compilationResult, installedCodeOwner, factory);
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
    }

    private static void emitBlock(NodeLIRBuilderTool nodeLirGen, LIRGenerationResult lirGenRes, Block b, StructuredGraph graph, BlockMap<List<ValueNode>> blockMap) {
        if (lirGenRes.getLIR().getLIRforBlock(b) == null) {
            for (Block pred : b.getPredecessors()) {
                if (!b.isLoopHeader() || !pred.isLoopEnd()) {
                    emitBlock(nodeLirGen, lirGenRes, pred, graph, blockMap);
                }
            }
            nodeLirGen.doBlock(b, graph, blockMap);
        }
    }

    public static LIRGenerationResult emitLIR(Backend backend, TargetDescription target, SchedulePhase schedule, StructuredGraph graph, Object stub, CallingConvention cc, RegisterConfig registerConfig) {
        List<Block> blocks = schedule.getCFG().getBlocks();
        Block startBlock = schedule.getCFG().getStartBlock();
        assert startBlock != null;
        assert startBlock.getPredecessorCount() == 0;

        LIR lir = null;
        List<Block> codeEmittingOrder = null;
        List<Block> linearScanOrder = null;
        try (Scope ds = Debug.scope("MidEnd")) {
            try (Scope s = Debug.scope("ComputeLinearScanOrder")) {
                codeEmittingOrder = ComputeBlockOrder.computeCodeEmittingOrder(blocks.size(), startBlock);
                linearScanOrder = ComputeBlockOrder.computeLinearScanOrder(blocks.size(), startBlock);

                lir = new LIR(schedule.getCFG(), linearScanOrder, codeEmittingOrder);
                Debug.dump(lir, "After linear scan order");
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
        try (Scope ds = Debug.scope("BackEnd", lir)) {
            FrameMapBuilder frameMapBuilder = backend.newFrameMapBuilder(registerConfig);
            LIRGenerationResult lirGenRes = backend.newLIRGenerationResult(lir, frameMapBuilder, graph.method(), stub);
            LIRGeneratorTool lirGen = backend.newLIRGenerator(cc, lirGenRes);
            NodeLIRBuilderTool nodeLirGen = backend.newNodeLIRBuilder(graph, lirGen);

            try (Scope s = Debug.scope("LIRGen", lirGen)) {
                for (Block b : linearScanOrder) {
                    emitBlock(nodeLirGen, lirGenRes, b, graph, schedule.getBlockToNodesMap());
                }
                lirGen.beforeRegisterAllocation();

                Debug.dump(lir, "After LIR generation");
            } catch (Throwable e) {
                throw Debug.handle(e);
            }

            try (Scope s0 = Debug.scope("HighTier")) {
                if (ConstantLoadOptimization.Options.ConstantLoadOptimization.getValue()) {
                    try (Scope s = Debug.scope("ConstantLoadOptimization", lir)) {
                        ConstantLoadOptimization.optimize(lirGenRes.getLIR(), lirGen);
                        Debug.dump(lir, "After constant load optimization");
                    } catch (Throwable e) {
                        throw Debug.handle(e);
                    }
                }
            }

            try (Scope s0 = Debug.scope("MidTier")) {
                try (Scope s = Debug.scope("Allocator", nodeLirGen)) {
                    if (backend.shouldAllocateRegisters()) {
                        LinearScan.allocate(target, lirGenRes);
                    }
                } catch (Throwable e) {
                    throw Debug.handle(e);
                }

                try (Scope s1 = Debug.scope("BuildFrameMap")) {
                    // build frame map
                    final StackSlotAllocator allocator;
                    if (LSStackSlotAllocator.Options.LSStackSlotAllocation.getValue()) {
                        allocator = new LSStackSlotAllocator();
                    } else {
                        allocator = new SimpleStackSlotAllocator();
                    }
                    lirGenRes.buildFrameMap(allocator);
                    Debug.dump(lir, "After FrameMap building");
                }
                try (Scope s1 = Debug.scope("MarkLocations")) {
                    if (backend.shouldAllocateRegisters()) {
                        // currently we mark locations only if we do register allocation
                        LocationMarker.markLocations(lirGenRes);
                    }
                }
            }

            try (Scope s = Debug.scope("LowTier")) {
                EdgeMoveOptimizer.optimize(lirGenRes);
                ControlFlowOptimizer.optimize(lir, codeEmittingOrder);
                if (lirGen.canEliminateRedundantMoves()) {
                    RedundantMoveElimination.optimize(lirGenRes);
                }
                NullCheckOptimizer.optimize(target, lirGenRes);

                Debug.dump(lir, "After control flow optimization");
            }

            return lirGenRes;
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
    }

    public static void emitCode(Backend backend, Assumptions assumptions, LIRGenerationResult lirGenRes, CompilationResult compilationResult, ResolvedJavaMethod installedCodeOwner,
                    CompilationResultBuilderFactory factory) {
        FrameMap frameMap = lirGenRes.getFrameMap();
        CompilationResultBuilder crb = backend.newCompilationResultBuilder(lirGenRes, frameMap, compilationResult, factory);
        backend.emitCode(crb, lirGenRes.getLIR(), installedCodeOwner);
        crb.finish();
        if (!assumptions.isEmpty()) {
            compilationResult.setAssumptions(assumptions);
        }

        if (Debug.isMeterEnabled()) {
            List<DataPatch> ldp = compilationResult.getDataPatches();
            Kind[] kindValues = Kind.values();
            DebugMetric[] dms = new DebugMetric[kindValues.length];
            for (int i = 0; i < dms.length; i++) {
                dms[i] = Debug.metric("DataPatches-%s", kindValues[i]);
            }

            for (DataPatch dp : ldp) {
                Kind kind = Kind.Illegal;
                if (dp.reference instanceof ConstantReference) {
                    VMConstant constant = ((ConstantReference) dp.reference).getConstant();
                    kind = ((JavaConstant) constant).getKind();
                }
                dms[kind.ordinal()].add(1);
            }

            Debug.metric("CompilationResults").increment();
            Debug.metric("CodeBytesEmitted").add(compilationResult.getTargetCodeSize());
            Debug.metric("InfopointsEmitted").add(compilationResult.getInfopoints().size());
            Debug.metric("DataPatches").add(ldp.size());
            Debug.metric("ExceptionHandlersEmitted").add(compilationResult.getExceptionHandlers().size());
        }

        if (Debug.isLogEnabled()) {
            Debug.log("%s", backend.getProviders().getCodeCache().disassemble(compilationResult, null));
        }

        Debug.dump(compilationResult, "After code generation");
    }
}
