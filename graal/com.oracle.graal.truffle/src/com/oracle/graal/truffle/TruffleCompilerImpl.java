/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
import static com.oracle.graal.truffle.TruffleCompilerOptions.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.Assumptions.Assumption;
import com.oracle.graal.api.code.CallingConvention.Type;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.runtime.*;
import com.oracle.graal.compiler.*;
import com.oracle.graal.compiler.CompilerThreadFactory.*;
import com.oracle.graal.compiler.target.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.debug.internal.*;
import com.oracle.graal.java.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.PhasePlan.PhasePosition;
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
public class TruffleCompilerImpl implements TruffleCompiler {

    private static final PrintStream OUT = TTY.out().out();

    private final Providers providers;
    private final Suites suites;
    private final PartialEvaluator partialEvaluator;
    private final Backend backend;
    private final ResolvedJavaType[] skippedExceptionTypes;
    private final RuntimeProvider runtime;
    private final TruffleCache truffleCache;
    private final ThreadPoolExecutor compileQueue;

    private static final Class[] SKIPPED_EXCEPTION_CLASSES = new Class[]{SlowPathException.class, UnexpectedResultException.class, ArithmeticException.class};

    public static final OptimisticOptimizations Optimizations = OptimisticOptimizations.ALL.remove(OptimisticOptimizations.Optimization.UseExceptionProbability,
                    OptimisticOptimizations.Optimization.RemoveNeverExecutedCode, OptimisticOptimizations.Optimization.UseTypeCheckedInlining, OptimisticOptimizations.Optimization.UseTypeCheckHints);

    public TruffleCompilerImpl() {
        this.runtime = Graal.getRequiredCapability(RuntimeProvider.class);
        this.backend = runtime.getHostBackend();
        Replacements truffleReplacements = ((GraalTruffleRuntime) Truffle.getRuntime()).getReplacements();
        this.providers = backend.getProviders().copyWith(truffleReplacements);
        this.suites = backend.getSuites().createSuites();
        this.skippedExceptionTypes = getSkippedExceptionTypes(providers.getMetaAccess());

        // Create compilation queue.
        CompilerThreadFactory factory = new CompilerThreadFactory("TruffleCompilerThread", new DebugConfigAccess() {
            public GraalDebugConfig getDebugConfig() {
                if (Debug.isEnabled()) {
                    GraalDebugConfig debugConfig = DebugEnvironment.initialize(TTY.out().out());
                    debugConfig.dumpHandlers().add(new TruffleTreeDumpHandler());
                    return debugConfig;
                } else {
                    return null;
                }
            }
        });
        compileQueue = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), factory);

        final GraphBuilderConfiguration config = GraphBuilderConfiguration.getEagerDefault();
        config.setSkippedExceptionTypes(skippedExceptionTypes);
        this.truffleCache = new TruffleCache(providers, config, TruffleCompilerImpl.Optimizations);

        this.partialEvaluator = new PartialEvaluator(runtime, providers, truffleCache);

        if (Debug.isEnabled()) {
            DebugEnvironment.initialize(System.out);
        }
    }

    static ResolvedJavaType[] getSkippedExceptionTypes(MetaAccessProvider metaAccess) {
        ResolvedJavaType[] skippedExceptionTypes = new ResolvedJavaType[SKIPPED_EXCEPTION_CLASSES.length];
        for (int i = 0; i < SKIPPED_EXCEPTION_CLASSES.length; i++) {
            skippedExceptionTypes[i] = metaAccess.lookupJavaType(SKIPPED_EXCEPTION_CLASSES[i]);
        }
        return skippedExceptionTypes;
    }

    public Future<InstalledCode> compile(final OptimizedCallTarget compilable) {
        return compileQueue.submit(new Callable<InstalledCode>() {
            @Override
            public InstalledCode call() throws Exception {
                try (Scope s = Debug.scope("Truffle", new TruffleDebugJavaMethod(compilable))) {
                    return compileMethodImpl(compilable);
                } catch (Throwable e) {
                    throw Debug.handle(e);
                }
            }
        });
    }

    public static final DebugTimer PartialEvaluationTime = Debug.timer("PartialEvaluationTime");
    public static final DebugTimer CompilationTime = Debug.timer("CompilationTime");
    public static final DebugTimer CodeInstallationTime = Debug.timer("CodeInstallation");

    private InstalledCode compileMethodImpl(final OptimizedCallTarget compilable) {
        final StructuredGraph graph;
        final GraphBuilderConfiguration config = GraphBuilderConfiguration.getDefault();
        config.setSkippedExceptionTypes(skippedExceptionTypes);
        GraphCache graphCache = runtime.getGraphCache();
        if (graphCache != null) {
            graphCache.removeStaleGraphs();
        }

        if (TraceTruffleInliningTree.getValue()) {
            printInlineTree(compilable.getRootNode());
        }

        long timeCompilationStarted = System.nanoTime();
        Assumptions assumptions = new Assumptions(true);
        try (TimerCloseable a = PartialEvaluationTime.start()) {
            graph = partialEvaluator.createGraph(compilable, assumptions);
        }
        if (Thread.interrupted()) {
            return null;
        }
        long timePartialEvaluationFinished = System.nanoTime();
        int nodeCountPartialEval = graph.getNodeCount();
        InstalledCode compiledMethod = compileMethodHelper(graph, config, assumptions);
        long timeCompilationFinished = System.nanoTime();
        int nodeCountLowered = graph.getNodeCount();

        if (compiledMethod == null) {
            throw new BailoutException("Could not install method, code cache is full!");
        }
        if (!compiledMethod.isValid()) {
            return null;
        }

        if (TraceTruffleCompilation.getValue()) {
            int nodeCountTruffle = NodeUtil.countNodes(compilable.getRootNode());
            byte[] code = compiledMethod.getCode();
            OUT.printf("[truffle] optimized %-50s %x |Nodes %7d |Time %5.0f(%4.0f+%-4.0f)ms |Nodes %5d/%5d |CodeSize %d\n", compilable.getRootNode(), compilable.hashCode(), nodeCountTruffle,
                            (timeCompilationFinished - timeCompilationStarted) / 1e6, (timePartialEvaluationFinished - timeCompilationStarted) / 1e6,
                            (timeCompilationFinished - timePartialEvaluationFinished) / 1e6, nodeCountPartialEval, nodeCountLowered, code != null ? code.length : 0);
        }
        return compiledMethod;
    }

    private void printInlineTree(RootNode rootNode) {
        OUT.println();
        OUT.println("Inlining tree for: " + rootNode);
        rootNode.accept(new InlineTreeVisitor());
    }

    private class InlineTreeVisitor implements NodeVisitor {

        public boolean visit(Node node) {
            if (node instanceof InlinedCallSite) {
                InlinedCallSite inlinedCallSite = (InlinedCallSite) node;
                int indent = this.indent(node);
                for (int i = 0; i < indent; ++i) {
                    OUT.print("   ");
                }
                OUT.println(inlinedCallSite.getCallTarget());
            }
            return true;
        }

        private int indent(Node n) {
            if (n instanceof RootNode) {
                return 0;
            } else if (n instanceof InlinedCallSite) {
                return indent(n.getParent()) + 1;
            } else {
                return indent(n.getParent());
            }
        }
    }

    public InstalledCode compileMethodHelper(final StructuredGraph graph, final GraphBuilderConfiguration config, final Assumptions assumptions) {
        final PhasePlan plan = createPhasePlan(config);

        try (Scope s = Debug.scope("TruffleFinal")) {
            Debug.dump(graph, "After TruffleTier");
        } catch (Throwable e) {
            throw Debug.handle(e);
        }

        CompilationResult result = null;
        try (TimerCloseable a = CompilationTime.start(); Scope s = Debug.scope("TruffleGraal.GraalCompiler", graph, providers.getCodeCache())) {
            CodeCacheProvider codeCache = providers.getCodeCache();
            CallingConvention cc = getCallingConvention(codeCache, Type.JavaCallee, graph.method(), false);
            CompilationResult compilationResult = new CompilationResult(graph.method().toString());
            result = GraalCompiler.compileGraph(graph, cc, graph.method(), providers, backend, codeCache.getTarget(), null, plan, OptimisticOptimizations.ALL, new SpeculationLog(), suites, false,
                            compilationResult);
        } catch (Throwable e) {
            throw Debug.handle(e);
        }

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

        InstalledCode compiledMethod = null;
        try (Scope s = Debug.scope("CodeInstall", providers.getCodeCache()); TimerCloseable a = CodeInstallationTime.start()) {
            InstalledCode installedCode = providers.getCodeCache().addMethod(graph.method(), result);
            if (installedCode != null) {
                Debug.dump(new Object[]{result, installedCode}, "After code installation");
            }
            compiledMethod = installedCode;
        } catch (Throwable e) {
            throw Debug.handle(e);
        }

        for (AssumptionValidAssumption a : validAssumptions) {
            a.getAssumption().registerInstalledCode(compiledMethod);
        }

        if (Debug.isLogEnabled()) {
            Debug.log(providers.getCodeCache().disassemble(result, compiledMethod));
        }
        return compiledMethod;
    }

    private PhasePlan createPhasePlan(final GraphBuilderConfiguration config) {
        final PhasePlan phasePlan = new PhasePlan();
        GraphBuilderPhase graphBuilderPhase = new GraphBuilderPhase(providers.getMetaAccess(), providers.getForeignCalls(), config, TruffleCompilerImpl.Optimizations);
        phasePlan.addPhase(PhasePosition.AFTER_PARSING, graphBuilderPhase);
        return phasePlan;
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
}
