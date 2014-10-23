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
package com.oracle.graal.truffle.hotspot;

import static com.oracle.graal.api.code.CodeUtil.*;
import static com.oracle.graal.compiler.GraalCompiler.*;
import static com.oracle.graal.graph.util.CollectionsAccess.*;
import static com.oracle.graal.hotspot.meta.HotSpotSuitesProvider.*;
import static com.oracle.graal.truffle.TruffleCompilerOptions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.CallingConvention.Type;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.runtime.*;
import com.oracle.graal.compiler.*;
import com.oracle.graal.compiler.target.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.java.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.common.inlining.*;
import com.oracle.graal.phases.tiers.*;
import com.oracle.graal.phases.util.*;
import com.oracle.graal.printer.*;
import com.oracle.graal.runtime.*;
import com.oracle.graal.truffle.*;
import com.oracle.graal.word.*;
import com.oracle.truffle.api.*;
import com.oracle.truffle.api.nodes.*;

/**
 * Implementation of the Truffle runtime when running on top of Graal.
 */
public final class HotSpotTruffleRuntime extends GraalTruffleRuntime {

    public static HotSpotTruffleRuntime makeInstance() {
        return new HotSpotTruffleRuntime();
    }

    private TruffleCompilerImpl truffleCompiler;
    private Replacements truffleReplacements;
    private Map<OptimizedCallTarget, Future<?>> compilations = newIdentityMap();
    private final ThreadPoolExecutor compileQueue;

    private final Map<RootCallTarget, Void> callTargets = Collections.synchronizedMap(new WeakHashMap<RootCallTarget, Void>());

    private HotSpotTruffleRuntime() {
        installOptimizedCallTargetCallMethod();
        installOptimizedCallTargetCallDirect();
        lookupCallMethods(getGraalProviders().getMetaAccess());

        // Create compilation queue.
        CompilerThreadFactory factory = new CompilerThreadFactory("TruffleCompilerThread", new CompilerThreadFactory.DebugConfigAccess() {
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
    }

    private static void installOptimizedCallTargetCallDirect() {
        if (TruffleCompilerOptions.TruffleFunctionInlining.getValue()) {
            ((HotSpotResolvedJavaMethod) getGraalProviders().getMetaAccess().lookupJavaMethod(OptimizedCallTarget.getCallDirectMethod())).setNotInlineable();
        }
    }

    @Override
    public String getName() {
        return "Graal Truffle Runtime";
    }

    @Override
    public RootCallTarget createCallTarget(RootNode rootNode) {
        return createCallTargetImpl(null, rootNode);
    }

    private RootCallTarget createCallTargetImpl(OptimizedCallTarget source, RootNode rootNode) {
        CompilationPolicy compilationPolicy;
        if (acceptForCompilation(rootNode)) {
            compilationPolicy = new CounterBasedCompilationPolicy();
        } else {
            compilationPolicy = new InterpreterOnlyCompilationPolicy();
        }
        OptimizedCallTarget target = new OptimizedCallTarget(source, rootNode, this, compilationPolicy, new HotSpotSpeculationLog());
        callTargets.put(target, null);
        return target;
    }

    @Override
    public RootCallTarget createClonedCallTarget(OptimizedCallTarget source, RootNode root) {
        return createCallTargetImpl(source, root);
    }

    @Override
    public Replacements getReplacements() {
        if (truffleReplacements == null) {
            truffleReplacements = HotSpotTruffleReplacements.makeInstance();
        }
        return truffleReplacements;
    }

    public static void installOptimizedCallTargetCallMethod() {
        Providers providers = getGraalProviders();
        MetaAccessProvider metaAccess = providers.getMetaAccess();
        ResolvedJavaType type = metaAccess.lookupJavaType(OptimizedCallTarget.class);
        for (ResolvedJavaMethod method : type.getDeclaredMethods()) {
            if (method.getAnnotation(TruffleCallBoundary.class) != null) {
                CompilationResult compResult = compileMethod(method);
                CodeCacheProvider codeCache = providers.getCodeCache();
                try (Scope s = Debug.scope("CodeInstall", codeCache, method)) {
                    codeCache.setDefaultMethod(method, compResult);
                } catch (Throwable e) {
                    throw Debug.handle(e);
                }
            }
        }
    }

    private static CompilationResultBuilderFactory getOptimizedCallTargetInstrumentationFactory(String arch, ResolvedJavaMethod method) {
        for (OptimizedCallTargetInstrumentationFactory factory : Services.load(OptimizedCallTargetInstrumentationFactory.class)) {
            if (factory.getArchitecture().equals(arch)) {
                factory.setInstrumentedMethod(method);
                return factory;
            }
        }
        // No specialization of OptimizedCallTarget on this platform.
        return CompilationResultBuilderFactory.Default;
    }

    private static CompilationResult compileMethod(ResolvedJavaMethod javaMethod) {
        Providers providers = getGraalProviders();
        MetaAccessProvider metaAccess = providers.getMetaAccess();
        SuitesProvider suitesProvider = Graal.getRequiredCapability(RuntimeProvider.class).getHostBackend().getSuites();
        Suites suites = suitesProvider.createSuites();
        removeInliningPhase(suites);
        StructuredGraph graph = new StructuredGraph(javaMethod);
        new GraphBuilderPhase.Instance(metaAccess, GraphBuilderConfiguration.getEagerDefault(), OptimisticOptimizations.ALL).apply(graph);
        PhaseSuite<HighTierContext> graphBuilderSuite = getGraphBuilderSuite(suitesProvider);
        CallingConvention cc = getCallingConvention(providers.getCodeCache(), Type.JavaCallee, graph.method(), false);
        Backend backend = Graal.getRequiredCapability(RuntimeProvider.class).getHostBackend();
        CompilationResultBuilderFactory factory = getOptimizedCallTargetInstrumentationFactory(backend.getTarget().arch.getName(), javaMethod);
        return compileGraph(graph, null, cc, javaMethod, providers, backend, providers.getCodeCache().getTarget(), null, graphBuilderSuite, OptimisticOptimizations.ALL, getProfilingInfo(graph), null,
                        suites, new CompilationResult(), factory);
    }

    private static Providers getGraalProviders() {
        RuntimeProvider runtimeProvider = Graal.getRequiredCapability(RuntimeProvider.class);
        return runtimeProvider.getHostBackend().getProviders();
    }

    private static PhaseSuite<HighTierContext> getGraphBuilderSuite(SuitesProvider suitesProvider) {
        PhaseSuite<HighTierContext> graphBuilderSuite = suitesProvider.getDefaultGraphBuilderSuite();
        return withSimpleDebugInfoIfRequested(graphBuilderSuite);
    }

    private static void removeInliningPhase(Suites suites) {
        ListIterator<BasePhase<? super HighTierContext>> inliningPhase = suites.getHighTier().findPhase(InliningPhase.class);
        if (inliningPhase != null) {
            inliningPhase.remove();
        }
    }

    @Override
    public void compile(OptimizedCallTarget optimizedCallTarget, boolean mayBeAsynchronous) {
        if (truffleCompiler == null) {
            truffleCompiler = new TruffleCompilerImpl();
        }
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try (Scope s = Debug.scope("Truffle", new TruffleDebugJavaMethod(optimizedCallTarget))) {
                    truffleCompiler.compileMethodImpl(optimizedCallTarget);
                    optimizedCallTarget.compilationFinished(null);
                } catch (Throwable e) {
                    optimizedCallTarget.compilationFinished(e);
                }
            }
        };
        Future<?> future = compileQueue.submit(r);
        this.compilations.put(optimizedCallTarget, future);

        if (!mayBeAsynchronous) {
            try {
                future.get();
            } catch (ExecutionException e) {
                if (TruffleCompilationExceptionsAreThrown.getValue() && !(e.getCause() instanceof BailoutException)) {
                    throw new RuntimeException(e.getCause());
                } else {
                    // silently ignored
                }
            } catch (InterruptedException e) {
                // silently ignored
            }
        }
    }

    @Override
    public boolean cancelInstalledTask(OptimizedCallTarget optimizedCallTarget) {
        Future<?> codeTask = this.compilations.get(optimizedCallTarget);
        if (codeTask != null && isCompiling(optimizedCallTarget)) {
            this.compilations.remove(optimizedCallTarget);
            return codeTask.cancel(true);
        }
        return false;
    }

    @Override
    public void waitForCompilation(OptimizedCallTarget optimizedCallTarget, long timeout) throws ExecutionException, TimeoutException {
        Future<?> codeTask = this.compilations.get(optimizedCallTarget);
        if (codeTask != null && isCompiling(optimizedCallTarget)) {
            try {
                codeTask.get(timeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // ignore interrupted
            }
        }
    }

    @Override
    public boolean isCompiling(OptimizedCallTarget optimizedCallTarget) {
        Future<?> codeTask = this.compilations.get(optimizedCallTarget);
        if (codeTask != null) {
            if (codeTask.isCancelled() || codeTask.isDone()) {
                this.compilations.remove(optimizedCallTarget);
                return false;
            }
            return true;
        }
        return false;
    }

    @Override
    public void invalidateInstalledCode(OptimizedCallTarget optimizedCallTarget) {
        HotSpotGraalRuntime.runtime().getCompilerToVM().invalidateInstalledCode(optimizedCallTarget);
    }

    @Override
    public void reinstallStubs() {
        installOptimizedCallTargetCallMethod();
    }

    @Override
    public Collection<RootCallTarget> getCallTargets() {
        return Collections.unmodifiableSet(callTargets.keySet());
    }

    @Override
    public void notifyTransferToInterpreter() {
        CompilerAsserts.neverPartOfCompilation();
        if (TraceTruffleTransferToInterpreter.getValue()) {
            Word thread = CurrentJavaThreadNode.get(HotSpotGraalRuntime.runtime().getTarget().wordKind);
            boolean deoptimized = thread.readByte(HotSpotGraalRuntime.runtime().getConfig().pendingTransferToInterpreterOffset) != 0;
            if (deoptimized) {
                thread.writeByte(HotSpotGraalRuntime.runtime().getConfig().pendingTransferToInterpreterOffset, (byte) 0);

                logTransferToInterpreter();
            }
        }
    }

    private static void logTransferToInterpreter() {
        final int skip = 2;
        final int limit = TraceTruffleStackTraceLimit.getValue();
        StackTraceElement[] stackTrace = new Throwable().getStackTrace();
        String suffix = stackTrace.length > skip + limit ? "\n  ..." : "";
        TTY.out().out().println(Arrays.stream(stackTrace).skip(skip).limit(limit).map(StackTraceElement::toString).collect(Collectors.joining("\n  ", "", suffix)));
    }
}
