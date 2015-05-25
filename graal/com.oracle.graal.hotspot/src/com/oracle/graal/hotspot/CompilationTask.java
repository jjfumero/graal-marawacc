/*
 * Copyright (c) 2012, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot;

import static com.oracle.graal.api.code.CallingConvention.Type.*;
import static com.oracle.graal.api.code.CodeUtil.*;
import static com.oracle.graal.compiler.GraalCompiler.*;
import static com.oracle.graal.compiler.common.GraalOptions.*;
import static com.oracle.graal.debug.Debug.*;
import static com.oracle.graal.hotspot.HotSpotGraalRuntime.*;
import static com.oracle.graal.hotspot.jvmci.InitTimer.*;
import static com.oracle.graal.hotspot.meta.HotSpotSuitesProvider.*;
import static com.oracle.graal.nodes.StructuredGraph.*;
import static com.oracle.jvmci.common.UnsafeAccess.*;

import java.lang.management.*;
import java.util.concurrent.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.CallingConvention.Type;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.runtime.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.debug.internal.*;
import com.oracle.graal.hotspot.events.*;
import com.oracle.graal.hotspot.events.EventProvider.CompilationEvent;
import com.oracle.graal.hotspot.events.EventProvider.CompilerFailureEvent;
import com.oracle.graal.hotspot.jvmci.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.phases.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.lir.phases.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.StructuredGraph.AllowAssumptions;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.OptimisticOptimizations.Optimization;
import com.oracle.graal.phases.tiers.*;
import com.oracle.graal.printer.*;

//JaCoCo Exclude

public class CompilationTask {

    static {
        try (InitTimer t = timer("initialize CompilationTask")) {
            // Must be first to ensure any options accessed by the rest of the class
            // initializer are initialized from the command line.
            HotSpotOptions.initialize();
        }
    }

    private static final DebugMetric BAILOUTS = Debug.metric("Bailouts");

    private final HotSpotBackend backend;
    private final HotSpotResolvedJavaMethod method;
    private final int entryBCI;
    private final int id;

    /**
     * Specifies whether the compilation result is installed as the
     * {@linkplain HotSpotNmethod#isDefault() default} nmethod for the compiled method.
     */
    private final boolean installAsDefault;

    private StructuredGraph graph;

    /**
     * A {@link com.sun.management.ThreadMXBean} to be able to query some information about the
     * current compiler thread, e.g. total allocated bytes.
     */
    private static final com.sun.management.ThreadMXBean threadMXBean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    /**
     * The address of the GraalEnv associated with this compilation or 0L if no such object exists.
     */
    private final long graalEnv;

    public CompilationTask(HotSpotBackend backend, HotSpotResolvedJavaMethod method, int entryBCI, long graalEnv, int id, boolean installAsDefault) {
        this.backend = backend;
        this.method = method;
        this.entryBCI = entryBCI;
        this.id = id;
        this.graalEnv = graalEnv;
        this.installAsDefault = installAsDefault;
    }

    public ResolvedJavaMethod getMethod() {
        return method;
    }

    /**
     * Returns the compilation id of this task.
     *
     * @return compile id
     */
    public int getId() {
        return id;
    }

    public int getEntryBCI() {
        return entryBCI;
    }

    /**
     * Time spent in compilation.
     */
    private static final DebugTimer CompilationTime = Debug.timer("CompilationTime");

    /**
     * Meters the {@linkplain StructuredGraph#getBytecodeSize() bytecodes} compiled.
     */
    private static final DebugMetric CompiledBytecodes = Debug.metric("CompiledBytecodes");

    public static final DebugTimer CodeInstallationTime = Debug.timer("CodeInstallation");

    protected Suites getSuites(HotSpotProviders providers) {
        return providers.getSuites().getDefaultSuites();
    }

    protected LIRSuites getLIRSuites(HotSpotProviders providers) {
        return providers.getSuites().getDefaultLIRSuites();
    }

    protected PhaseSuite<HighTierContext> getGraphBuilderSuite(HotSpotProviders providers) {
        PhaseSuite<HighTierContext> suite = withSimpleDebugInfoIfRequested(providers.getSuites().getDefaultGraphBuilderSuite());

        boolean osrCompilation = entryBCI != StructuredGraph.INVOCATION_ENTRY_BCI;
        if (osrCompilation) {
            suite = suite.copy();
            suite.appendPhase(new OnStackReplacementPhase());
        }
        return suite;
    }

    protected OptimisticOptimizations getOptimisticOpts(ProfilingInfo profilingInfo) {
        return new OptimisticOptimizations(profilingInfo);
    }

    protected ProfilingInfo getProfilingInfo() {
        boolean osrCompilation = entryBCI != StructuredGraph.INVOCATION_ENTRY_BCI;
        return method.getCompilationProfilingInfo(osrCompilation);
    }

    public void runCompilation() {
        HotSpotVMConfig config = backend.getRuntime().getConfig();
        final long threadId = Thread.currentThread().getId();
        long startCompilationTime = System.nanoTime();
        HotSpotInstalledCode installedCode = null;
        final boolean isOSR = entryBCI != StructuredGraph.INVOCATION_ENTRY_BCI;

        // Log a compilation event.
        EventProvider eventProvider = Graal.getRequiredCapability(EventProvider.class);
        CompilationEvent compilationEvent = eventProvider.newCompilationEvent();

        // If there is already compiled code for this method on our level we simply return.
        // Graal compiles are always at the highest compile level, even in non-tiered mode so we
        // only need to check for that value.
        if (method.hasCodeAtLevel(entryBCI, config.compilationLevelFullOptimization)) {
            return;
        }

        try (DebugCloseable a = CompilationTime.start()) {
            CompilationStatistics stats = CompilationStatistics.create(method, isOSR);
            final boolean printCompilation = PrintCompilation.getValue() && !TTY.isSuppressed();
            if (printCompilation) {
                TTY.println(getMethodDescription() + "...");
            }

            CompilationResult result = null;
            TTY.Filter filter = new TTY.Filter(PrintFilter.getValue(), method);
            final long start = System.currentTimeMillis();
            final long allocatedBytesBefore = threadMXBean.getThreadAllocatedBytes(threadId);

            try (Scope s = Debug.scope("Compiling", new DebugDumpScope(String.valueOf(id), true))) {
                // Begin the compilation event.
                compilationEvent.begin();

                HotSpotProviders providers = backend.getProviders();
                graph = new StructuredGraph(method, entryBCI, AllowAssumptions.from(OptAssumptions.getValue()));
                if (shouldDisableMethodInliningRecording(config)) {
                    graph.disableInlinedMethodRecording();
                }
                CallingConvention cc = getCallingConvention(providers.getCodeCache(), Type.JavaCallee, graph.method(), false);
                if (graph.getEntryBCI() != StructuredGraph.INVOCATION_ENTRY_BCI) {
                    // for OSR, only a pointer is passed to the method.
                    JavaType[] parameterTypes = new JavaType[]{providers.getMetaAccess().lookupJavaType(long.class)};
                    CallingConvention tmp = providers.getCodeCache().getRegisterConfig().getCallingConvention(JavaCallee, providers.getMetaAccess().lookupJavaType(void.class), parameterTypes,
                                    backend.getTarget(), false);
                    cc = new CallingConvention(cc.getStackSize(), cc.getReturn(), tmp.getArgument(0));
                }
                Suites suites = getSuites(providers);
                LIRSuites lirSuites = getLIRSuites(providers);
                ProfilingInfo profilingInfo = getProfilingInfo();
                OptimisticOptimizations optimisticOpts = getOptimisticOpts(profilingInfo);
                if (isOSR) {
                    // In OSR compiles, we cannot rely on never executed code profiles, because
                    // all code after the OSR loop is never executed.
                    optimisticOpts.remove(Optimization.RemoveNeverExecutedCode);
                }
                result = compileGraph(graph, cc, method, providers, backend, backend.getTarget(), getGraphBuilderSuite(providers), optimisticOpts, profilingInfo, method.getSpeculationLog(), suites,
                                lirSuites, new CompilationResult(), CompilationResultBuilderFactory.Default);
                result.setId(getId());
                result.setEntryBCI(entryBCI);
            } catch (Throwable e) {
                throw Debug.handle(e);
            } finally {
                // End the compilation event.
                compilationEvent.end();

                filter.remove();
                final boolean printAfterCompilation = PrintAfterCompilation.getValue() && !TTY.isSuppressed();

                if (printAfterCompilation || printCompilation) {
                    final long stop = System.currentTimeMillis();
                    final int targetCodeSize = result != null ? result.getTargetCodeSize() : -1;
                    final long allocatedBytesAfter = threadMXBean.getThreadAllocatedBytes(threadId);
                    final long allocatedBytes = (allocatedBytesAfter - allocatedBytesBefore) / 1024;

                    if (printAfterCompilation) {
                        TTY.println(getMethodDescription() + String.format(" | %4dms %5dB %5dkB", stop - start, targetCodeSize, allocatedBytes));
                    } else if (printCompilation) {
                        TTY.println(String.format("%-6d Graal %-70s %-45s %-50s | %4dms %5dB %5dkB", id, "", "", "", stop - start, targetCodeSize, allocatedBytes));
                    }
                }
            }

            try (DebugCloseable b = CodeInstallationTime.start()) {
                installedCode = (HotSpotInstalledCode) installMethod(result);
                if (!isOSR) {
                    ProfilingInfo profile = method.getProfilingInfo();
                    profile.setCompilerIRSize(StructuredGraph.class, graph.getNodeCount());
                }
            }
            stats.finish(method, installedCode);
        } catch (BailoutException bailout) {
            BAILOUTS.increment();
            if (ExitVMOnBailout.getValue()) {
                TTY.cachedOut.println(method.format("Bailout in %H.%n(%p)"));
                bailout.printStackTrace(TTY.cachedOut);
                System.exit(-1);
            } else if (PrintBailout.getValue()) {
                TTY.cachedOut.println(method.format("Bailout in %H.%n(%p)"));
                bailout.printStackTrace(TTY.cachedOut);
            }
        } catch (Throwable t) {
            if (PrintStackTraceOnException.getValue() || ExitVMOnException.getValue()) {
                t.printStackTrace(TTY.cachedOut);
            }

            // Log a failure event.
            CompilerFailureEvent event = eventProvider.newCompilerFailureEvent();
            if (event.shouldWrite()) {
                event.setCompileId(getId());
                event.setMessage(t.getMessage());
                event.commit();
            }

            if (ExitVMOnException.getValue()) {
                System.exit(-1);
            }
        } finally {
            final int compiledBytecodes = graph.getBytecodeSize();
            CompiledBytecodes.add(compiledBytecodes);

            // Log a compilation event.
            if (compilationEvent.shouldWrite() && installedCode != null) {
                compilationEvent.setMethod(method.format("%H.%n(%p)"));
                compilationEvent.setCompileId(getId());
                compilationEvent.setCompileLevel(config.compilationLevelFullOptimization);
                compilationEvent.setSucceeded(true);
                compilationEvent.setIsOsr(isOSR);
                compilationEvent.setCodeSize(installedCode.getSize());
                compilationEvent.setInlinedBytes(compiledBytecodes);
                compilationEvent.commit();
            }

            if (graalEnv != 0) {
                long ctask = unsafe.getAddress(graalEnv + config.graalEnvTaskOffset);
                assert ctask != 0L;
                unsafe.putInt(ctask + config.compileTaskNumInlinedBytecodesOffset, compiledBytecodes);
            }
            long compilationTime = System.nanoTime() - startCompilationTime;
            if ((config.ciTime || config.ciTimeEach) && installedCode != null) {
                long timeUnitsPerSecond = TimeUnit.NANOSECONDS.convert(1, TimeUnit.SECONDS);
                CompilerToVM c2vm = backend.getRuntime().getCompilerToVM();
                c2vm.notifyCompilationStatistics(id, method, entryBCI != INVOCATION_ENTRY_BCI, compiledBytecodes, compilationTime, timeUnitsPerSecond, installedCode);
            }
        }
    }

    /**
     * Determines whether to {@linkplain StructuredGraph#disableInlinedMethodRecording() disable}
     * method inlining recording for the method being compiled.
     *
     * @see StructuredGraph#getBytecodeSize()
     */
    private boolean shouldDisableMethodInliningRecording(HotSpotVMConfig config) {
        if (config.ciTime || config.ciTimeEach || CompiledBytecodes.isEnabled()) {
            return false;
        }
        if (graalEnv == 0 || unsafe.getByte(graalEnv + config.graalEnvJvmtiCanHotswapOrPostBreakpointOffset) != 0) {
            return false;
        }
        return true;
    }

    private String getMethodDescription() {
        return String.format("%-6d Graal %-70s %-45s %-50s %s", id, method.getDeclaringClass().getName(), method.getName(), method.getSignature().toMethodDescriptor(),
                        entryBCI == StructuredGraph.INVOCATION_ENTRY_BCI ? "" : "(OSR@" + entryBCI + ") ");
    }

    private InstalledCode installMethod(final CompilationResult compResult) {
        final HotSpotCodeCacheProvider codeCache = backend.getProviders().getCodeCache();
        InstalledCode installedCode = null;
        try (Scope s = Debug.scope("CodeInstall", new DebugDumpScope(String.valueOf(id), true), codeCache, method)) {
            installedCode = codeCache.installMethod(method, compResult, graalEnv, installAsDefault);
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
        return installedCode;
    }

    @Override
    public String toString() {
        return "Compilation[id=" + id + ", " + method.format("%H.%n(%p)") + (entryBCI == StructuredGraph.INVOCATION_ENTRY_BCI ? "" : "@" + entryBCI) + "]";
    }

    /**
     * Schedules compilation of a metaspace Method.
     *
     * Called from the VM.
     *
     * @param metaspaceMethod
     * @param entryBCI
     * @param graalEnv address of native GraalEnv object
     * @param id CompileTask::_compile_id
     */
    @SuppressWarnings("unused")
    private static void compileMetaspaceMethod(long metaspaceMethod, int entryBCI, long graalEnv, int id) {
        // Ensure a Graal runtime is initialized prior to Debug being initialized as the former
        // may include processing command line options used by the latter.
        Graal.getRuntime();

        // Ensure a debug configuration for this thread is initialized
        if (Debug.isEnabled() && DebugScope.getConfig() == null) {
            DebugEnvironment.initialize(TTY.cachedOut);
        }

        HotSpotResolvedJavaMethod method = HotSpotResolvedJavaMethodImpl.fromMetaspace(metaspaceMethod);
        compileMethod(method, entryBCI, graalEnv, id);
    }

    /**
     * Compiles a method to machine code.
     */
    static void compileMethod(HotSpotResolvedJavaMethod method, int entryBCI, long graalEnv, int id) {
        HotSpotBackend backend = runtime().getHostBackend();
        CompilationTask task = new CompilationTask(backend, method, entryBCI, graalEnv, id, true);
        try (DebugConfigScope dcs = setConfig(new TopLevelDebugConfig())) {
            task.runCompilation();
        }
        return;
    }
}
