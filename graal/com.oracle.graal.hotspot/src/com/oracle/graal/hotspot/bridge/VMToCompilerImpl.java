/*
 * Copyright (c) 2011, 2012, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.graal.hotspot.bridge;

import static com.oracle.graal.compiler.GraalDebugConfig.*;
import static com.oracle.graal.graph.UnsafeAccess.*;
import static com.oracle.graal.hotspot.CompilationTask.*;
import static com.oracle.graal.hotspot.HotSpotGraalRuntime.*;
import static com.oracle.graal.java.GraphBuilderPhase.*;
import static com.oracle.graal.phases.GraalOptions.*;
import static com.oracle.graal.phases.common.InliningUtil.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.internal.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.debug.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.phases.*;
import com.oracle.graal.java.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.options.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.PhasePlan.PhasePosition;
import com.oracle.graal.printer.*;
import com.oracle.graal.replacements.*;

/**
 * Exits from the HotSpot VM into Java code.
 */
public class VMToCompilerImpl implements VMToCompiler {

    //@formatter:off
    @Option(help = "File to which compiler logging is sent")
    private static final OptionValue<String> LogFile = new OptionValue<>(null);

    @Option(help = "Print compilation queue activity periodically")
    private static final OptionValue<Boolean> PrintQueue = new OptionValue<>(false);

    @Option(help = "Time limit in milliseconds for bootstrap (-1 for no limit)")
    private static final OptionValue<Integer> TimedBootstrap = new OptionValue<>(-1);

    @Option(help = "Number of compilation threads to use")
    private static final StableOptionValue<Integer> Threads = new StableOptionValue<Integer>() {

        @Override
        public Integer initialValue() {
            return Runtime.getRuntime().availableProcessors();
        }
    };

    //@formatter:on

    private final HotSpotGraalRuntime runtime;

    public final HotSpotResolvedPrimitiveType typeBoolean;
    public final HotSpotResolvedPrimitiveType typeChar;
    public final HotSpotResolvedPrimitiveType typeFloat;
    public final HotSpotResolvedPrimitiveType typeDouble;
    public final HotSpotResolvedPrimitiveType typeByte;
    public final HotSpotResolvedPrimitiveType typeShort;
    public final HotSpotResolvedPrimitiveType typeInt;
    public final HotSpotResolvedPrimitiveType typeLong;
    public final HotSpotResolvedPrimitiveType typeVoid;

    private ThreadPoolExecutor compileQueue;
    private AtomicInteger compileTaskIds = new AtomicInteger();

    private volatile boolean bootstrapRunning;

    private PrintStream log = System.out;

    private long compilerStartTime;

    public VMToCompilerImpl(HotSpotGraalRuntime runtime) {
        this.runtime = runtime;

        typeBoolean = new HotSpotResolvedPrimitiveType(Kind.Boolean);
        typeChar = new HotSpotResolvedPrimitiveType(Kind.Char);
        typeFloat = new HotSpotResolvedPrimitiveType(Kind.Float);
        typeDouble = new HotSpotResolvedPrimitiveType(Kind.Double);
        typeByte = new HotSpotResolvedPrimitiveType(Kind.Byte);
        typeShort = new HotSpotResolvedPrimitiveType(Kind.Short);
        typeInt = new HotSpotResolvedPrimitiveType(Kind.Int);
        typeLong = new HotSpotResolvedPrimitiveType(Kind.Long);
        typeVoid = new HotSpotResolvedPrimitiveType(Kind.Void);
    }

    private static void initMirror(HotSpotResolvedPrimitiveType type, long offset) {
        Class<?> mirror = type.mirror();
        unsafe.putObject(mirror, offset, type);
        assert unsafe.getObject(mirror, offset) == type;
    }

    public void startCompiler(boolean bootstrapEnabled) throws Throwable {

        FastNodeClassRegistry.initialize();

        bootstrapRunning = bootstrapEnabled;

        final HotSpotVMConfig config = runtime.getConfig();
        long offset = config.graalMirrorInClassOffset;
        initMirror(typeBoolean, offset);
        initMirror(typeChar, offset);
        initMirror(typeFloat, offset);
        initMirror(typeDouble, offset);
        initMirror(typeByte, offset);
        initMirror(typeShort, offset);
        initMirror(typeInt, offset);
        initMirror(typeLong, offset);
        initMirror(typeVoid, offset);

        if (LogFile.getValue() != null) {
            try {
                final boolean enableAutoflush = true;
                log = new PrintStream(new FileOutputStream(LogFile.getValue()), enableAutoflush);
            } catch (FileNotFoundException e) {
                throw new RuntimeException("couldn't open log file: " + LogFile.getValue(), e);
            }
        }

        TTY.initialize(log);

        if (Log.getValue() == null && Meter.getValue() == null && Time.getValue() == null && Dump.getValue() == null) {
            if (MethodFilter.getValue() != null) {
                TTY.println("WARNING: Ignoring MethodFilter option since Log, Meter, Time and Dump options are all null");
            }
        }

        if (config.ciTime) {
            BytecodesParsed.setConditional(false);
            InlinedBytecodes.setConditional(false);
            CompilationTime.setConditional(false);
        }

        if (Debug.isEnabled()) {
            DebugEnvironment.initialize(log);

            String summary = DebugValueSummary.getValue();
            if (summary != null) {
                switch (summary) {
                    case "Name":
                    case "Partial":
                    case "Complete":
                    case "Thread":
                        break;
                    default:
                        throw new GraalInternalError("Unsupported value for DebugSummaryValue: %s", summary);
                }
            }
        }

        final HotSpotProviders hostProviders = runtime.getHostProviders();
        assert VerifyOptionsPhase.checkOptions(hostProviders.getMetaAccess(), hostProviders.getForeignCalls());

        // Install intrinsics.
        if (Intrinsify.getValue()) {
            Debug.scope("RegisterReplacements", new Object[]{new DebugDumpScope("RegisterReplacements")}, new Runnable() {

                @Override
                public void run() {

                    List<LoweringProvider> initializedLowerers = new ArrayList<>();
                    List<ForeignCallsProvider> initializedForeignCalls = new ArrayList<>();

                    for (Map.Entry<?, HotSpotBackend> e : runtime.getBackends().entrySet()) {
                        HotSpotBackend backend = e.getValue();
                        HotSpotProviders providers = backend.getProviders();

                        HotSpotForeignCallsProvider foreignCalls = providers.getForeignCalls();
                        if (!initializedForeignCalls.contains(foreignCalls)) {
                            initializedForeignCalls.add(foreignCalls);
                            foreignCalls.initialize(providers, config);
                        }
                        HotSpotLoweringProvider lowerer = (HotSpotLoweringProvider) providers.getLowerer();
                        if (!initializedLowerers.contains(lowerer)) {
                            initializedLowerers.add(lowerer);
                            initializeLowerer(providers, lowerer);
                        }
                    }
                }

                private void initializeLowerer(HotSpotProviders providers, HotSpotLoweringProvider lowerer) {
                    final Replacements replacements = providers.getReplacements();
                    ServiceLoader<ReplacementsProvider> sl = ServiceLoader.loadInstalled(ReplacementsProvider.class);
                    TargetDescription target = providers.getCodeCache().getTarget();
                    for (ReplacementsProvider replacementsProvider : sl) {
                        replacementsProvider.registerReplacements(providers.getMetaAccess(), lowerer, replacements, target);
                    }
                    lowerer.initialize(providers, config);
                    if (BootstrapReplacements.getValue()) {
                        for (ResolvedJavaMethod method : replacements.getAllReplacements()) {
                            replacements.getMacroSubstitution(method);
                            replacements.getMethodSubstitution(method);
                            replacements.getSnippet(method);
                        }
                    }
                }
            });

        }

        // Create compilation queue.
        compileQueue = new ThreadPoolExecutor(Threads.getValue(), Threads.getValue(), 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), CompilerThread.FACTORY);

        // Create queue status printing thread.
        if (PrintQueue.getValue()) {
            Thread t = new Thread() {

                @Override
                public void run() {
                    while (true) {
                        TTY.println(compileQueue.toString());
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                        }
                    }
                }
            };
            t.setDaemon(true);
            t.start();
        }

        BenchmarkCounters.initialize(runtime.getCompilerToVM());

        compilerStartTime = System.nanoTime();
    }

    /**
     * A fast-path for {@link NodeClass} retrieval using {@link HotSpotResolvedObjectType}.
     */
    static class FastNodeClassRegistry extends NodeClass.Registry {

        @SuppressWarnings("unused")
        static void initialize() {
            new FastNodeClassRegistry();
        }

        private static HotSpotResolvedObjectType type(Class<? extends Node> key) {
            return (HotSpotResolvedObjectType) HotSpotResolvedObjectType.fromClass(key);
        }

        @Override
        public NodeClass get(Class<? extends Node> key) {
            return type(key).getNodeClass();
        }

        @Override
        protected void registered(Class<? extends Node> key, NodeClass value) {
            type(key).setNodeClass(value);
        }
    }

    /**
     * Take action related to entering a new execution phase.
     * 
     * @param phase the execution phase being entered
     */
    protected void phaseTransition(String phase) {
        CompilationStatistics.clear(phase);
        if (runtime.getConfig().ciTime) {
            parsedBytecodesPerSecond = MetricRateInPhase.snapshot(phase, parsedBytecodesPerSecond, BytecodesParsed, CompilationTime, TimeUnit.SECONDS);
            inlinedBytecodesPerSecond = MetricRateInPhase.snapshot(phase, inlinedBytecodesPerSecond, InlinedBytecodes, CompilationTime, TimeUnit.SECONDS);
        }
    }

    /**
     * This method is the first method compiled during bootstrapping. Put any code in there that
     * warms up compiler paths that are otherwise not exercised during bootstrapping and lead to
     * later deoptimization when application code is compiled.
     */
    @SuppressWarnings("unused")
    @Deprecated
    private synchronized void compileWarmup() {
        // Method is synchronized to exercise the synchronization code in the compiler.
    }

    public void bootstrap() throws Throwable {
        TTY.print("Bootstrapping Graal");
        TTY.flush();
        long startTime = System.currentTimeMillis();

        boolean firstRun = true;
        do {
            // Initialize compile queue with a selected set of methods.
            Class<Object> objectKlass = Object.class;
            if (firstRun) {
                enqueue(getClass().getDeclaredMethod("compileWarmup"));
                enqueue(objectKlass.getDeclaredMethod("equals", Object.class));
                enqueue(objectKlass.getDeclaredMethod("toString"));
                firstRun = false;
            } else {
                for (int i = 0; i < 100; i++) {
                    enqueue(getClass().getDeclaredMethod("bootstrap"));
                }
            }

            // Compile until the queue is empty.
            int z = 0;
            while (true) {
                try {
                    assert !CompilationTask.withinEnqueue.get();
                    CompilationTask.withinEnqueue.set(Boolean.TRUE);
                    if (compileQueue.getCompletedTaskCount() >= Math.max(3, compileQueue.getTaskCount())) {
                        break;
                    }
                } finally {
                    CompilationTask.withinEnqueue.set(Boolean.FALSE);
                }

                Thread.sleep(100);
                while (z < compileQueue.getCompletedTaskCount() / 100) {
                    ++z;
                    TTY.print(".");
                    TTY.flush();
                }

                // Are we out of time?
                final int timedBootstrap = TimedBootstrap.getValue();
                if (timedBootstrap != -1) {
                    if ((System.currentTimeMillis() - startTime) > timedBootstrap) {
                        break;
                    }
                }
            }
        } while ((System.currentTimeMillis() - startTime) <= TimedBootstrap.getValue());

        phaseTransition("bootstrap");

        bootstrapRunning = false;

        TTY.println(" in %d ms (compiled %d methods)", System.currentTimeMillis() - startTime, compileQueue.getCompletedTaskCount());
        if (runtime.getGraphCache() != null) {
            runtime.getGraphCache().clear();
        }
        System.gc();
        phaseTransition("bootstrap2");

        if (CompileTheWorld.getValue() != null) {
            new CompileTheWorld().compile();
            System.exit(0);
        }
    }

    private MetricRateInPhase parsedBytecodesPerSecond;
    private MetricRateInPhase inlinedBytecodesPerSecond;

    private void enqueue(Method m) throws Throwable {
        JavaMethod javaMethod = runtime.getHostProviders().getMetaAccess().lookupJavaMethod(m);
        assert !Modifier.isAbstract(((HotSpotResolvedJavaMethod) javaMethod).getModifiers()) && !Modifier.isNative(((HotSpotResolvedJavaMethod) javaMethod).getModifiers()) : javaMethod;
        compileMethod((HotSpotResolvedJavaMethod) javaMethod, StructuredGraph.INVOCATION_ENTRY_BCI, false);
    }

    private static void shutdownCompileQueue(ThreadPoolExecutor queue) throws InterruptedException {
        if (queue != null) {
            queue.shutdown();
            if (Debug.isEnabled() && Dump.getValue() != null) {
                // Wait 2 seconds to flush out all graph dumps that may be of interest
                queue.awaitTermination(2, TimeUnit.SECONDS);
            }
        }
    }

    public void shutdownCompiler() throws Throwable {
        try {
            assert !CompilationTask.withinEnqueue.get();
            CompilationTask.withinEnqueue.set(Boolean.TRUE);
            shutdownCompileQueue(compileQueue);
        } finally {
            CompilationTask.withinEnqueue.set(Boolean.FALSE);
        }

        if (Debug.isEnabled() && areMetricsOrTimersEnabled()) {
            List<DebugValueMap> topLevelMaps = DebugValueMap.getTopLevelMaps();
            List<DebugValue> debugValues = KeyRegistry.getDebugValues();
            if (debugValues.size() > 0) {
                ArrayList<DebugValue> sortedValues = new ArrayList<>(debugValues);
                Collections.sort(sortedValues);

                String summary = DebugValueSummary.getValue();
                if (summary == null) {
                    summary = "Complete";
                }
                switch (summary) {
                    case "Name":
                        printSummary(topLevelMaps, sortedValues);
                        break;
                    case "Partial": {
                        DebugValueMap globalMap = new DebugValueMap("Global");
                        for (DebugValueMap map : topLevelMaps) {
                            flattenChildren(map, globalMap);
                        }
                        globalMap.normalize();
                        printMap(new DebugValueScope(null, globalMap), sortedValues);
                        break;
                    }
                    case "Complete": {
                        DebugValueMap globalMap = new DebugValueMap("Global");
                        for (DebugValueMap map : topLevelMaps) {
                            globalMap.addChild(map);
                        }
                        globalMap.group();
                        globalMap.normalize();
                        printMap(new DebugValueScope(null, globalMap), sortedValues);
                        break;
                    }
                    case "Thread":
                        for (DebugValueMap map : topLevelMaps) {
                            TTY.println("Showing the results for thread: " + map.getName());
                            map.group();
                            map.normalize();
                            printMap(new DebugValueScope(null, map), sortedValues);
                        }
                        break;
                    default:
                        throw new GraalInternalError("Unknown summary type: %s", summary);
                }
            }
        }
        phaseTransition("final");

        if (runtime.getConfig().ciTime) {
            parsedBytecodesPerSecond.printAll("ParsedBytecodesPerSecond", System.out);
            inlinedBytecodesPerSecond.printAll("InlinedBytecodesPerSecond", System.out);
        }

        SnippetCounter.printGroups(TTY.out().out());
        BenchmarkCounters.shutdown(runtime.getCompilerToVM(), compilerStartTime);
    }

    private void flattenChildren(DebugValueMap map, DebugValueMap globalMap) {
        globalMap.addChild(map);
        for (DebugValueMap child : map.getChildren()) {
            flattenChildren(child, globalMap);
        }
        map.clearChildren();
    }

    private static void printSummary(List<DebugValueMap> topLevelMaps, List<DebugValue> debugValues) {
        DebugValueMap result = new DebugValueMap("Summary");
        for (int i = debugValues.size() - 1; i >= 0; i--) {
            DebugValue debugValue = debugValues.get(i);
            int index = debugValue.getIndex();
            long total = collectTotal(topLevelMaps, index);
            result.setCurrentValue(index, total);
        }
        printMap(new DebugValueScope(null, result), debugValues);
    }

    static long collectTotal(DebugValue value) {
        List<DebugValueMap> maps = DebugValueMap.getTopLevelMaps();
        long total = 0;
        for (int i = 0; i < maps.size(); i++) {
            DebugValueMap map = maps.get(i);
            int index = value.getIndex();
            total += map.getCurrentValue(index);
            total += collectTotal(map.getChildren(), index);
        }
        return total;
    }

    private static long collectTotal(List<DebugValueMap> maps, int index) {
        long total = 0;
        for (int i = 0; i < maps.size(); i++) {
            DebugValueMap map = maps.get(i);
            total += map.getCurrentValue(index);
            total += collectTotal(map.getChildren(), index);
        }
        return total;
    }

    /**
     * Tracks the scope when printing a {@link DebugValueMap}, allowing "empty" scopes to be
     * omitted. An empty scope is one in which there are no (nested) non-zero debug values.
     */
    static class DebugValueScope {

        final DebugValueScope parent;
        final int level;
        final DebugValueMap map;
        private boolean printed;

        public DebugValueScope(DebugValueScope parent, DebugValueMap map) {
            this.parent = parent;
            this.map = map;
            this.level = parent == null ? 0 : parent.level + 1;
        }

        public void print() {
            if (!printed) {
                printed = true;
                if (parent != null) {
                    parent.print();
                }
                printIndent(level);
                TTY.println("%s", map.getName());
            }
        }
    }

    private static void printMap(DebugValueScope scope, List<DebugValue> debugValues) {

        for (DebugValue value : debugValues) {
            long l = scope.map.getCurrentValue(value.getIndex());
            if (l != 0 || !SuppressZeroDebugValues.getValue()) {
                scope.print();
                printIndent(scope.level + 1);
                TTY.println(value.getName() + "=" + value.toString(l));
            }
        }

        for (DebugValueMap child : scope.map.getChildren()) {
            printMap(new DebugValueScope(scope, child), debugValues);
        }
    }

    private static void printIndent(int level) {
        for (int i = 0; i < level; ++i) {
            TTY.print("    ");
        }
        TTY.print("|-> ");
    }

    @Override
    public void compileMethod(long metaspaceMethod, final HotSpotResolvedObjectType holder, final int entryBCI, boolean blocking) throws Throwable {
        HotSpotResolvedJavaMethod method = holder.createMethod(metaspaceMethod);
        compileMethod(method, entryBCI, blocking);
    }

    /**
     * Compiles a method to machine code.
     */
    public void compileMethod(final HotSpotResolvedJavaMethod method, final int entryBCI, boolean blocking) throws Throwable {
        boolean osrCompilation = entryBCI != StructuredGraph.INVOCATION_ENTRY_BCI;
        if (osrCompilation && bootstrapRunning) {
            // no OSR compilations during bootstrap - the compiler is just too slow at this point,
            // and we know that there are no endless loops
            return;
        }

        if (CompilationTask.withinEnqueue.get()) {
            // This is required to avoid deadlocking a compiler thread. The issue is that a
            // java.util.concurrent.BlockingQueue is used to implement the compilation worker
            // queues. If a compiler thread triggers a compilation, then it may be blocked trying
            // to add something to its own queue.
            return;
        }

        CompilationTask.withinEnqueue.set(Boolean.TRUE);
        try {
            if (method.tryToQueueForCompilation()) {
                assert method.isQueuedForCompilation();

                final OptimisticOptimizations optimisticOpts = new OptimisticOptimizations(method);
                int id = compileTaskIds.incrementAndGet();
                HotSpotBackend backend = runtime.getHostBackend();
                CompilationTask task = CompilationTask.create(backend, createPhasePlan(backend.getProviders(), optimisticOpts, osrCompilation), optimisticOpts, method, entryBCI, id);

                if (blocking) {
                    task.runCompilation();
                } else {
                    try {
                        method.setCurrentTask(task);
                        compileQueue.execute(task);
                    } catch (RejectedExecutionException e) {
                        // The compile queue was already shut down.
                    }
                }
            }
        } finally {
            CompilationTask.withinEnqueue.set(Boolean.FALSE);
        }
    }

    @Override
    public JavaMethod createUnresolvedJavaMethod(String name, String signature, JavaType holder) {
        return new HotSpotMethodUnresolved(name, signature, holder);
    }

    @Override
    public JavaField createJavaField(JavaType holder, String name, JavaType type, int offset, int flags, boolean internal) {
        if (offset != -1) {
            HotSpotResolvedObjectType resolved = (HotSpotResolvedObjectType) holder;
            return resolved.createField(name, type, offset, flags, internal);
        }
        return new HotSpotUnresolvedField(holder, name, type);
    }

    @Override
    public ResolvedJavaMethod createResolvedJavaMethod(JavaType holder, long metaspaceMethod) {
        HotSpotResolvedObjectType type = (HotSpotResolvedObjectType) holder;
        return type.createMethod(metaspaceMethod);
    }

    @Override
    public ResolvedJavaType createPrimitiveJavaType(int basicType) {
        switch (basicType) {
            case 4:
                return typeBoolean;
            case 5:
                return typeChar;
            case 6:
                return typeFloat;
            case 7:
                return typeDouble;
            case 8:
                return typeByte;
            case 9:
                return typeShort;
            case 10:
                return typeInt;
            case 11:
                return typeLong;
            case 14:
                return typeVoid;
            default:
                throw new IllegalArgumentException("Unknown basic type: " + basicType);
        }
    }

    @Override
    public HotSpotUnresolvedJavaType createUnresolvedJavaType(String name) {
        int dims = 0;
        int startIndex = 0;
        while (name.charAt(startIndex) == '[') {
            startIndex++;
            dims++;
        }

        // Decode name if necessary.
        if (name.charAt(name.length() - 1) == ';') {
            assert name.charAt(startIndex) == 'L';
            return new HotSpotUnresolvedJavaType(name, name.substring(startIndex + 1, name.length() - 1), dims);
        } else {
            return new HotSpotUnresolvedJavaType(HotSpotUnresolvedJavaType.getFullName(name, dims), name, dims);
        }
    }

    @Override
    public HotSpotResolvedObjectType createResolvedJavaType(long metaspaceKlass, String name, String simpleName, Class javaMirror, int sizeOrSpecies) {
        HotSpotResolvedObjectType type = new HotSpotResolvedObjectType(metaspaceKlass, name, simpleName, javaMirror, sizeOrSpecies);

        long offset = runtime().getConfig().graalMirrorInClassOffset;
        if (!unsafe.compareAndSwapObject(javaMirror, offset, null, type)) {
            // lost the race - return the existing value instead
            type = (HotSpotResolvedObjectType) unsafe.getObject(javaMirror, offset);
        }
        return type;
    }

    @Override
    public Constant createConstant(Kind kind, long value) {
        if (kind == Kind.Long) {
            return Constant.forLong(value);
        } else if (kind == Kind.Int) {
            return Constant.forInt((int) value);
        } else if (kind == Kind.Short) {
            return Constant.forShort((short) value);
        } else if (kind == Kind.Char) {
            return Constant.forChar((char) value);
        } else if (kind == Kind.Byte) {
            return Constant.forByte((byte) value);
        } else if (kind == Kind.Boolean) {
            return (value == 0) ? Constant.FALSE : Constant.TRUE;
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public Constant createConstantFloat(float value) {
        return Constant.forFloat(value);
    }

    @Override
    public Constant createConstantDouble(double value) {
        return Constant.forDouble(value);
    }

    @Override
    public Constant createConstantObject(Object object) {
        return Constant.forObject(object);
    }

    @Override
    public LocalImpl createLocalImpl(String name, String type, HotSpotResolvedObjectType holder, int bciStart, int bciEnd, int slot) {
        return new LocalImpl(name, type, holder, bciStart, bciEnd, slot);
    }

    public PhasePlan createPhasePlan(HotSpotProviders providers, OptimisticOptimizations optimisticOpts, boolean onStackReplacement) {
        PhasePlan phasePlan = new PhasePlan();
        MetaAccessProvider metaAccess = providers.getMetaAccess();
        ForeignCallsProvider foreignCalls = providers.getForeignCalls();
        phasePlan.addPhase(PhasePosition.AFTER_PARSING, new GraphBuilderPhase(metaAccess, foreignCalls, GraphBuilderConfiguration.getDefault(), optimisticOpts));
        if (onStackReplacement) {
            phasePlan.addPhase(PhasePosition.AFTER_PARSING, new OnStackReplacementPhase());
        }
        return phasePlan;
    }

    @Override
    public PrintStream log() {
        return log;
    }
}
