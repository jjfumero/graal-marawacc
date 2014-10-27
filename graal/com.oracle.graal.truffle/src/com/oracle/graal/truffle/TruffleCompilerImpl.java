/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.truffle;

import static com.oracle.graal.api.code.CodeUtil.*;
import static com.oracle.graal.compiler.GraalCompiler.*;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.Assumptions.Assumption;
import com.oracle.graal.api.code.CallingConvention.Type;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.runtime.*;
import com.oracle.graal.compiler.target.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.debug.DebugMemUseTracker.Closeable;
import com.oracle.graal.debug.internal.*;
import com.oracle.graal.java.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.tiers.*;
import com.oracle.graal.phases.util.*;
import com.oracle.graal.printer.*;
import com.oracle.graal.runtime.*;
import com.oracle.graal.truffle.nodes.*;
import com.oracle.truffle.api.*;
import com.oracle.truffle.api.nodes.*;

/**
 * Implementation of the Truffle compiler using Graal.
 */
public class TruffleCompilerImpl {

    private final Providers providers;
    private final Suites suites;
    private final PartialEvaluator partialEvaluator;
    private final Backend backend;
    private final GraphBuilderConfiguration config;
    private final RuntimeProvider runtime;
    private final TruffleCache truffleCache;
    private final GraalTruffleCompilationListener compilationNotify;

    private static final Class<?>[] SKIPPED_EXCEPTION_CLASSES = new Class[]{UnexpectedResultException.class, SlowPathException.class, ArithmeticException.class, IllegalArgumentException.class};

    public static final OptimisticOptimizations Optimizations = OptimisticOptimizations.ALL.remove(OptimisticOptimizations.Optimization.UseExceptionProbability,
                    OptimisticOptimizations.Optimization.RemoveNeverExecutedCode, OptimisticOptimizations.Optimization.UseTypeCheckedInlining, OptimisticOptimizations.Optimization.UseTypeCheckHints);

    public TruffleCompilerImpl() {
        GraalTruffleRuntime graalTruffleRuntime = ((GraalTruffleRuntime) Truffle.getRuntime());
        this.runtime = Graal.getRequiredCapability(RuntimeProvider.class);
        this.compilationNotify = graalTruffleRuntime.getCompilationNotify();
        this.backend = runtime.getHostBackend();
        Replacements truffleReplacements = graalTruffleRuntime.getReplacements();
        this.providers = backend.getProviders().copyWith(truffleReplacements);
        this.suites = backend.getSuites().getDefaultSuites();

        ResolvedJavaType[] skippedExceptionTypes = getSkippedExceptionTypes(providers.getMetaAccess());
        GraphBuilderConfiguration eagerConfig = GraphBuilderConfiguration.getEagerDefault().withSkippedExceptionTypes(skippedExceptionTypes);
        this.config = GraphBuilderConfiguration.getDefault().withSkippedExceptionTypes(skippedExceptionTypes);
        this.truffleCache = new TruffleCacheImpl(providers, eagerConfig, config, TruffleCompilerImpl.Optimizations);

        this.partialEvaluator = new PartialEvaluator(providers, truffleCache);

        if (Debug.isEnabled()) {
            DebugEnvironment.initialize(System.out);
        }
    }

    public static ResolvedJavaType[] getSkippedExceptionTypes(MetaAccessProvider metaAccess) {
        ResolvedJavaType[] skippedExceptionTypes = new ResolvedJavaType[SKIPPED_EXCEPTION_CLASSES.length];
        for (int i = 0; i < SKIPPED_EXCEPTION_CLASSES.length; i++) {
            skippedExceptionTypes[i] = metaAccess.lookupJavaType(SKIPPED_EXCEPTION_CLASSES[i]);
        }
        return skippedExceptionTypes;
    }

    public static final DebugTimer PartialEvaluationTime = Debug.timer("PartialEvaluationTime");
    public static final DebugTimer CompilationTime = Debug.timer("CompilationTime");
    public static final DebugTimer CodeInstallationTime = Debug.timer("CodeInstallation");

    public static final DebugMemUseTracker PartialEvaluationMemUse = Debug.memUseTracker("TrufflePartialEvaluationMemUse");
    public static final DebugMemUseTracker CompilationMemUse = Debug.memUseTracker("TruffleCompilationMemUse");
    public static final DebugMemUseTracker CodeInstallationMemUse = Debug.memUseTracker("TruffleCodeInstallationMemUse");

    public void compileMethod(final OptimizedCallTarget compilable) {
        StructuredGraph graph = null;

        compilationNotify.notifyCompilationStarted(compilable);

        try {
            Assumptions assumptions = new Assumptions(true);

            try (TimerCloseable a = PartialEvaluationTime.start(); Closeable c = PartialEvaluationMemUse.start()) {
                graph = partialEvaluator.createGraph(compilable, assumptions);
            }

            if (Thread.currentThread().isInterrupted()) {
                return;
            }

            compilationNotify.notifyCompilationTruffleTierFinished(compilable, graph);
            CompilationResult compilationResult = compileMethodHelper(graph, assumptions, compilable.toString(), compilable.getSpeculationLog(), compilable);
            compilationNotify.notifyCompilationSuccess(compilable, graph, compilationResult);
        } catch (Throwable t) {
            compilationNotify.notifyCompilationFailed(compilable, graph, t);
            throw t;
        }
    }

    public CompilationResult compileMethodHelper(StructuredGraph graph, Assumptions assumptions, String name, SpeculationLog speculationLog, InstalledCode predefinedInstalledCode) {
        try (Scope s = Debug.scope("TruffleFinal")) {
            Debug.dump(1, graph, "After TruffleTier");
        } catch (Throwable e) {
            throw Debug.handle(e);
        }

        CompilationResult result = null;
        try (TimerCloseable a = CompilationTime.start(); Scope s = Debug.scope("TruffleGraal.GraalCompiler", graph, providers.getCodeCache()); Closeable c = CompilationMemUse.start()) {
            CodeCacheProvider codeCache = providers.getCodeCache();
            CallingConvention cc = getCallingConvention(codeCache, Type.JavaCallee, graph.method(), false);
            CompilationResult compilationResult = new CompilationResult(name);
            result = compileGraph(graph, null, cc, graph.method(), providers, backend, codeCache.getTarget(), null, createGraphBuilderSuite(), Optimizations, getProfilingInfo(graph), speculationLog,
                            suites, compilationResult, CompilationResultBuilderFactory.Default);
        } catch (Throwable e) {
            throw Debug.handle(e);
        }

        compilationNotify.notifyCompilationGraalTierFinished((OptimizedCallTarget) predefinedInstalledCode, graph);

        List<AssumptionValidAssumption> validAssumptions = new ArrayList<>();
        Assumptions newAssumptions = new Assumptions(true);
        if (assumptions != null) {
            for (Assumption assumption : assumptions.getAssumptions()) {
                processAssumption(newAssumptions, assumption, validAssumptions);
            }
        }

        if (result.getAssumptions() != null) {
            for (Assumption assumption : result.getAssumptions().getAssumptions()) {
                processAssumption(newAssumptions, assumption, validAssumptions);
            }
        }

        result.setAssumptions(newAssumptions);

        InstalledCode installedCode;
        try (Scope s = Debug.scope("CodeInstall", providers.getCodeCache()); TimerCloseable a = CodeInstallationTime.start(); Closeable c = CodeInstallationMemUse.start()) {
            installedCode = providers.getCodeCache().addMethod(graph.method(), result, speculationLog, predefinedInstalledCode);
        } catch (Throwable e) {
            throw Debug.handle(e);
        }

        for (AssumptionValidAssumption a : validAssumptions) {
            a.getAssumption().registerInstalledCode(installedCode);
        }

        if (Debug.isLogEnabled()) {
            Debug.log(providers.getCodeCache().disassemble(result, installedCode));
        }
        return result;
    }

    private PhaseSuite<HighTierContext> createGraphBuilderSuite() {
        PhaseSuite<HighTierContext> suite = backend.getSuites().getDefaultGraphBuilderSuite().copy();
        ListIterator<BasePhase<? super HighTierContext>> iterator = suite.findPhase(GraphBuilderPhase.class);
        iterator.remove();
        iterator.add(new GraphBuilderPhase(config));
        return suite;
    }

    public void processAssumption(Assumptions newAssumptions, Assumption assumption, List<AssumptionValidAssumption> manual) {
        if (assumption != null) {
            if (assumption instanceof AssumptionValidAssumption) {
                AssumptionValidAssumption assumptionValidAssumption = (AssumptionValidAssumption) assumption;
                manual.add(assumptionValidAssumption);
            } else {
                newAssumptions.record(assumption);
            }
        }
    }

    public PartialEvaluator getPartialEvaluator() {
        return partialEvaluator;
    }
}
