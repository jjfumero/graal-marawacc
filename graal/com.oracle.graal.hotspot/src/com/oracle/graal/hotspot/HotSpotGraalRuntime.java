/*
 * Copyright (c) 2011, 2015, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.compiler.common.GraalOptions.*;
import static com.oracle.graal.debug.GraalDebugConfig.*;
import static jdk.internal.jvmci.inittimer.InitTimer.*;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.api.collections.*;
import com.oracle.graal.api.replacements.*;
import com.oracle.graal.api.runtime.*;
import com.oracle.graal.compiler.target.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.debug.*;
import com.oracle.graal.hotspot.logging.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.phases.tiers.*;
import com.oracle.graal.replacements.*;
import com.oracle.graal.runtime.*;

import jdk.internal.jvmci.code.*;
import jdk.internal.jvmci.code.stack.*;
import jdk.internal.jvmci.common.*;
import jdk.internal.jvmci.hotspot.*;
import jdk.internal.jvmci.inittimer.*;
import jdk.internal.jvmci.meta.*;
import jdk.internal.jvmci.runtime.*;

//JaCoCo Exclude

/**
 * Singleton class holding the instance of the {@link GraalRuntime}.
 */
public final class HotSpotGraalRuntime implements HotSpotGraalRuntimeProvider, HotSpotProxified {

    private static class Instance {
        private static final HotSpotGraalRuntime instance;

        static {
            try (InitTimer t0 = timer("HotSpotGraalRuntime.<clinit>")) {
                // initJvmciRuntime and initCompilerFactory are set by the JVMCI initialization code
                JVMCI.initialize();
                assert initJvmciRuntime != null && initCompilerFactory != null;

                try (InitTimer t = timer("HotSpotGraalRuntime.<init>")) {
                    instance = new HotSpotGraalRuntime(initJvmciRuntime, initCompilerFactory);
                }

                try (InitTimer t = timer("HotSpotGraalRuntime.completeInitialization")) {
                    // Why deferred initialization? See comment in completeInitialization().
                    instance.completeInitialization();
                }
            }
        }

        private static void forceStaticInitializer() {
        }
    }

    private static HotSpotJVMCIRuntime initJvmciRuntime;
    private static HotSpotGraalCompilerFactory initCompilerFactory;

    public static void initialize(HotSpotJVMCIRuntime runtime, HotSpotGraalCompilerFactory factory) {
        initJvmciRuntime = runtime;
        initCompilerFactory = factory;
        Instance.forceStaticInitializer();
    }

    /**
     * Gets the singleton {@link HotSpotGraalRuntime} object.
     */
    public static HotSpotGraalRuntime runtime() {
        assert Instance.instance != null;
        return Instance.instance;
    }

    @Override
    public HotSpotJVMCIRuntimeProvider getJVMCIRuntime() {
        return jvmciRuntime;
    }

    private boolean checkArrayIndexScaleInvariants() {
        assert getJVMCIRuntime().getArrayIndexScale(Kind.Byte) == 1;
        assert getJVMCIRuntime().getArrayIndexScale(Kind.Boolean) == 1;
        assert getJVMCIRuntime().getArrayIndexScale(Kind.Char) == 2;
        assert getJVMCIRuntime().getArrayIndexScale(Kind.Short) == 2;
        assert getJVMCIRuntime().getArrayIndexScale(Kind.Int) == 4;
        assert getJVMCIRuntime().getArrayIndexScale(Kind.Long) == 8;
        assert getJVMCIRuntime().getArrayIndexScale(Kind.Float) == 4;
        assert getJVMCIRuntime().getArrayIndexScale(Kind.Double) == 8;
        return true;
    }

    /**
     * Gets the kind of a word value on the {@linkplain #getHostBackend() host} backend.
     */
    public static Kind getHostWordKind() {
        return runtime().getHostBackend().getTarget().wordKind;
    }

    private final HotSpotBackend hostBackend;
    private DebugValuesPrinter debugValuesPrinter;

    private final Map<Class<? extends Architecture>, HotSpotBackend> backends = new HashMap<>();

    private final HotSpotJVMCIRuntime jvmciRuntime;

    private HotSpotGraalRuntime(HotSpotJVMCIRuntime jvmciRuntime, HotSpotGraalCompilerFactory compilerFactory) {

        this.jvmciRuntime = jvmciRuntime;

        HotSpotVMConfig config = getConfig();
        CompileTheWorld.Options.overrideWithNativeOptions(config);

        // Only set HotSpotPrintInlining if it still has its default value (false).
        if (HotSpotPrintInlining.getValue() == false) {
            HotSpotPrintInlining.setValue(config.printInlining);
        }

        if (Boolean.valueOf(System.getProperty("graal.printconfig"))) {
            printConfig(config);
        }

        CompilerConfiguration compilerConfiguration = compilerFactory.createCompilerConfiguration();

        JVMCIBackend hostJvmciBackend = jvmciRuntime.getHostJVMCIBackend();
        Architecture hostArchitecture = hostJvmciBackend.getTarget().arch;
        try (InitTimer t = timer("create backend:", hostArchitecture)) {
            HotSpotBackendFactory factory = compilerFactory.getBackendFactory(hostArchitecture);
            if (factory == null) {
                throw new JVMCIError("No backend available for host architecture \"%s\"", hostArchitecture);
            }
            hostBackend = registerBackend(factory.createBackend(this, compilerConfiguration, jvmciRuntime.getHostJVMCIBackend(), null));
        }

        for (JVMCIBackend jvmciBackend : jvmciRuntime.getBackends().values()) {
            if (jvmciBackend == hostJvmciBackend) {
                continue;
            }

            Architecture gpuArchitecture = jvmciBackend.getTarget().arch;
            HotSpotBackendFactory factory = compilerFactory.getBackendFactory(gpuArchitecture);
            if (factory == null) {
                throw new JVMCIError("No backend available for specified GPU architecture \"%s\"", gpuArchitecture);
            }
            try (InitTimer t = timer("create backend:", gpuArchitecture)) {
                registerBackend(factory.createBackend(this, compilerConfiguration, null, hostBackend));
            }
        }
    }

    /**
     * Do deferred initialization.
     */
    private void completeInitialization() {

        if (Log.getValue() == null && !areScopedMetricsOrTimersEnabled() && Dump.getValue() == null && Verify.getValue() == null) {
            if (MethodFilter.getValue() != null) {
                TTY.println("WARNING: Ignoring MethodFilter option since Log, Meter, Time, TrackMemUse, Dump and Verify options are all null");
            }
        }

        if (Debug.isEnabled()) {
            DebugEnvironment.initialize(TTY.out);

            String summary = DebugValueSummary.getValue();
            if (summary != null) {
                switch (summary) {
                    case "Name":
                    case "Partial":
                    case "Complete":
                    case "Thread":
                        break;
                    default:
                        throw new JVMCIError("Unsupported value for DebugSummaryValue: %s", summary);
                }
            }
        }

        if (Debug.areUnconditionalMetricsEnabled() || Debug.areUnconditionalTimersEnabled() || (Debug.isEnabled() && areScopedMetricsOrTimersEnabled())) {
            // This must be created here to avoid loading the DebugValuesPrinter class
            // during shutdown() which in turn can cause a deadlock
            debugValuesPrinter = new DebugValuesPrinter();
        }

        // Complete initialization of backends
        try (InitTimer st = timer(hostBackend.getTarget().arch.getName(), ".completeInitialization")) {
            hostBackend.completeInitialization();
        }
        for (HotSpotBackend backend : backends.values()) {
            if (backend != hostBackend) {
                try (InitTimer st = timer(backend.getTarget().arch.getName(), ".completeInitialization")) {
                    backend.completeInitialization();
                }
            }
        }

        BenchmarkCounters.initialize(getCompilerToVM());

        assert checkArrayIndexScaleInvariants();

        runtimeStartTime = System.nanoTime();
    }

    private HotSpotBackend registerBackend(HotSpotBackend backend) {
        Class<? extends Architecture> arch = backend.getTarget().arch.getClass();
        HotSpotBackend oldValue = backends.put(arch, backend);
        assert oldValue == null : "cannot overwrite existing backend for architecture " + arch.getSimpleName();
        return backend;
    }

    private static void printConfig(HotSpotVMConfig config) {
        Field[] fields = config.getClass().getDeclaredFields();
        Map<String, Field> sortedFields = new TreeMap<>();
        for (Field f : fields) {
            f.setAccessible(true);
            sortedFields.put(f.getName(), f);
        }
        for (Field f : sortedFields.values()) {
            try {
                Logger.info(String.format("%9s %-40s = %s", f.getType().getSimpleName(), f.getName(), Logger.pretty(f.get(config))));
            } catch (Exception e) {
            }
        }
    }

    public HotSpotProviders getHostProviders() {
        return getHostBackend().getProviders();
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    private final NodeCollectionsProvider nodeCollectionsProvider = new DefaultNodeCollectionsProvider();

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getCapability(Class<T> clazz) {
        if (clazz == RuntimeProvider.class) {
            return (T) this;
        } else if (clazz == CollectionsProvider.class || clazz == NodeCollectionsProvider.class) {
            return (T) nodeCollectionsProvider;
        } else if (clazz == StackIntrospection.class) {
            return (T) this;
        } else if (clazz == SnippetReflectionProvider.class) {
            return (T) getHostProviders().getSnippetReflection();
        }
        return null;
    }

    public HotSpotBackend getHostBackend() {
        return hostBackend;
    }

    public <T extends Architecture> Backend getBackend(Class<T> arch) {
        assert arch != Architecture.class;
        return backends.get(arch);
    }

    public Map<Class<? extends Architecture>, HotSpotBackend> getBackends() {
        return Collections.unmodifiableMap(backends);
    }

    @Override
    public <T> T iterateFrames(ResolvedJavaMethod[] initialMethods, ResolvedJavaMethod[] matchingMethods, int initialSkip, InspectedFrameVisitor<T> visitor) {
        final HotSpotResolvedJavaMethodImpl[] initialMetaMethods = toHotSpotResolvedJavaMethodImpls(initialMethods);
        final HotSpotResolvedJavaMethodImpl[] matchingMetaMethods = toHotSpotResolvedJavaMethodImpls(matchingMethods);

        CompilerToVM compilerToVM = getCompilerToVM();
        HotSpotStackFrameReference current = compilerToVM.getNextStackFrame(null, initialMetaMethods, initialSkip);
        while (current != null) {
            T result = visitor.visitFrame(current);
            if (result != null) {
                return result;
            }
            current = compilerToVM.getNextStackFrame(current, matchingMetaMethods, 0);
        }
        return null;
    }

    private static HotSpotResolvedJavaMethodImpl[] toHotSpotResolvedJavaMethodImpls(ResolvedJavaMethod[] methods) {
        if (methods == null) {
            return null;
        } else if (methods instanceof HotSpotResolvedJavaMethodImpl[]) {
            return (HotSpotResolvedJavaMethodImpl[]) methods;
        } else {
            HotSpotResolvedJavaMethodImpl[] result = new HotSpotResolvedJavaMethodImpl[methods.length];
            for (int i = 0; i < result.length; i++) {
                result[i] = (HotSpotResolvedJavaMethodImpl) methods[i];
            }
            return result;
        }
    }

    private long runtimeStartTime;

    /**
     * Take action related to entering a new execution phase.
     *
     * @param phase the execution phase being entered
     */
    static void phaseTransition(String phase) {
        CompilationStatistics.clear(phase);
    }

    void shutdown() {
        if (debugValuesPrinter != null) {
            debugValuesPrinter.printDebugValues();
        }
        phaseTransition("final");

        SnippetCounter.printGroups(TTY.out().out());
        BenchmarkCounters.shutdown(getCompilerToVM(), runtimeStartTime);
    }
}
