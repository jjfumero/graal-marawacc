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
package jdk.internal.jvmci.hotspot;

import static jdk.internal.jvmci.compiler.Compiler.*;
import static jdk.internal.jvmci.debug.internal.MemUseTrackerImpl.*;

import java.io.*;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.jar.*;
import java.util.stream.*;

import jdk.internal.jvmci.compiler.*;
import jdk.internal.jvmci.compiler.CompilerThreadFactory.DebugConfigAccess;
import jdk.internal.jvmci.compiler.Compiler;
import jdk.internal.jvmci.debug.*;
import jdk.internal.jvmci.debug.internal.*;
import jdk.internal.jvmci.meta.*;
import jdk.internal.jvmci.options.*;
import jdk.internal.jvmci.options.OptionUtils.OptionConsumer;
import jdk.internal.jvmci.options.OptionValue.OverrideScope;
import jdk.internal.jvmci.runtime.*;

/**
 * This class implements compile-the-world functionality with JVMCI.
 */
public final class CompileTheWorld {

    /**
     * Magic token to trigger reading files from the boot class path.
     */
    public static final String SUN_BOOT_CLASS_PATH = "sun.boot.class.path";

    public static class Options {
        // @formatter:off
        @Option(help = "Compile all methods in all classes on given class path", type = OptionType.Debug)
        public static final OptionValue<String> CompileTheWorldClasspath = new OptionValue<>(SUN_BOOT_CLASS_PATH);
        @Option(help = "Verbose CompileTheWorld operation", type = OptionType.Debug)
        public static final OptionValue<Boolean> CompileTheWorldVerbose = new OptionValue<>(true);
        @Option(help = "The number of CompileTheWorld iterations to perform", type = OptionType.Debug)
        public static final OptionValue<Integer> CompileTheWorldIterations = new OptionValue<>(1);
        @Option(help = "Only compile methods matching this filter", type = OptionType.Debug)
        public static final OptionValue<String> CompileTheWorldMethodFilter = new OptionValue<>(null);
        @Option(help = "Exclude methods matching this filter from compilation", type = OptionType.Debug)
        public static final OptionValue<String> CompileTheWorldExcludeMethodFilter = new OptionValue<>(null);
        @Option(help = "First class to consider when using -XX:+CompileTheWorld", type = OptionType.Debug)
        public static final OptionValue<Integer> CompileTheWorldStartAt = new OptionValue<>(1);
        @Option(help = "Last class to consider when using -XX:+CompileTheWorld", type = OptionType.Debug)
        public static final OptionValue<Integer> CompileTheWorldStopAt = new OptionValue<>(Integer.MAX_VALUE);
        @Option(help = "Option value overrides to use during compile the world. For example, " +
                       "to disable inlining and partial escape analysis specify '-PartialEscapeAnalysis -Inline'. " +
                       "The format for each option is the same as on the command line just without the '-G:' prefix.", type = OptionType.Debug)
        public static final OptionValue<String> CompileTheWorldConfig = new OptionValue<>(null);

        @Option(help = "Run CTW using as many threads as there are processors on the system", type = OptionType.Debug)
        public static final OptionValue<Boolean> CompileTheWorldMultiThreaded = new OptionValue<>(false);
        @Option(help = "Number of threads to use for multithreaded CTW.  Defaults to Runtime.getRuntime().availableProcessors()", type = OptionType.Debug)
        public static final OptionValue<Integer> CompileTheWorldThreads = new OptionValue<>(0);
        // @formatter:on

        /**
         * Overrides {@link #CompileTheWorldStartAt} and {@link #CompileTheWorldStopAt} from
         * {@code -XX} HotSpot options of the same name if the latter have non-default values.
         */
        public static void overrideWithNativeOptions(HotSpotVMConfig c) {
            if (c.compileTheWorldStartAt != 1) {
                CompileTheWorldStartAt.setValue(c.compileTheWorldStartAt);
            }
            if (c.compileTheWorldStopAt != Integer.MAX_VALUE) {
                CompileTheWorldStopAt.setValue(c.compileTheWorldStopAt);
            }
        }
    }

    /**
     * A mechanism for overriding JVMCI options that affect compilation. A {@link Config} object
     * should be used in a try-with-resources statement to ensure overriding of options is scoped
     * properly. For example:
     *
     * <pre>
     *     Config config = ...;
     *     try (AutoCloseable s = config == null ? null : config.apply()) {
     *         // perform a JVMCI compilation
     *     }
     * </pre>
     */
    @SuppressWarnings("serial")
    public static class Config extends HashMap<OptionValue<?>, Object> implements OptionConsumer {
        /**
         * Creates a {@link Config} object by parsing a set of space separated override options.
         *
         * @param options a space separated set of option value settings with each option setting in
         *            a format compatible with
         *            {@link OptionUtils#parseOption(String, OptionConsumer)}. Ignored if null.
         */
        public Config(String options) {
            if (options != null) {
                for (String option : options.split("\\s+")) {
                    OptionUtils.parseOption(option, this);
                }
            }
        }

        /**
         * Applies the overrides represented by this object. The overrides are in effect until
         * {@link OverrideScope#close()} is called on the returned object.
         */
        OverrideScope apply() {
            return OptionValue.override(this);
        }

        public void set(OptionDescriptor desc, Object value) {
            put(desc.getOptionValue(), value);
        }
    }

    /** List of Zip/Jar files to compile (see {@link Options#CompileTheWorldClasspath}). */
    private final String files;

    /** Class index to start compilation at (see {@link Options#CompileTheWorldStartAt}). */
    private final int startAt;

    /** Class index to stop compilation at (see {@link Options#CompileTheWorldStopAt}). */
    private final int stopAt;

    /** Only compile methods matching one of the filters in this array if the array is non-null. */
    private final MethodFilter[] methodFilters;

    /** Exclude methods matching one of the filters in this array if the array is non-null. */
    private final MethodFilter[] excludeMethodFilters;

    // Counters
    private int classFileCounter = 0;
    private AtomicLong compiledMethodsCounter = new AtomicLong();
    private AtomicLong compileTime = new AtomicLong();
    private AtomicLong memoryUsed = new AtomicLong();

    private boolean verbose;
    private final Config config;

    /**
     * Signal that the threads should start compiling in multithreaded mode.
     */
    private boolean running;

    private ThreadPoolExecutor threadPool;

    /**
     * Creates a compile-the-world instance.
     *
     * @param files {@link File#pathSeparator} separated list of Zip/Jar files to compile
     * @param startAt index of the class file to start compilation at
     * @param stopAt index of the class file to stop compilation at
     * @param methodFilters
     * @param excludeMethodFilters
     */
    public CompileTheWorld(String files, Config config, int startAt, int stopAt, String methodFilters, String excludeMethodFilters, boolean verbose) {
        this.files = files;
        this.startAt = startAt;
        this.stopAt = stopAt;
        this.methodFilters = methodFilters == null || methodFilters.isEmpty() ? null : MethodFilter.parse(methodFilters);
        this.excludeMethodFilters = excludeMethodFilters == null || excludeMethodFilters.isEmpty() ? null : MethodFilter.parse(excludeMethodFilters);
        this.verbose = verbose;
        this.config = config;

        // We don't want the VM to exit when a method fails to compile...
        config.putIfAbsent(ExitVMOnException, false);

        // ...but we want to see exceptions.
        config.putIfAbsent(PrintBailout, true);
        config.putIfAbsent(PrintStackTraceOnException, true);
        config.putIfAbsent(HotSpotResolvedJavaMethodImpl.Options.UseProfilingInformation, false);
    }

    /**
     * Compiles all methods in all classes in the Zip/Jar archive files in
     * {@link Options#CompileTheWorldClasspath}. If {@link Options#CompileTheWorldClasspath}
     * contains the magic token {@link #SUN_BOOT_CLASS_PATH} passed up from HotSpot we take the
     * files from the boot class path.
     */
    public void compile() throws Throwable {
        // By default only report statistics for the CTW threads themselves
        if (JVMCIDebugConfig.DebugValueThreadFilter.hasDefaultValue()) {
            JVMCIDebugConfig.DebugValueThreadFilter.setValue("^CompileTheWorld");
        }

        if (SUN_BOOT_CLASS_PATH.equals(files)) {
            final String[] entries = System.getProperty(SUN_BOOT_CLASS_PATH).split(File.pathSeparator);
            String bcpFiles = "";
            for (int i = 0; i < entries.length; i++) {
                final String entry = entries[i];

                // We stop at rt.jar, unless it is the first boot class path entry.
                if (entry.endsWith("rt.jar") && (i > 0)) {
                    break;
                }
                if (i > 0) {
                    bcpFiles += File.pathSeparator;
                }
                bcpFiles += entry;
            }
            compile(bcpFiles);
        } else {
            compile(files);
        }
    }

    public void println() {
        println("");
    }

    public void println(String format, Object... args) {
        println(String.format(format, args));
    }

    public void println(String s) {
        if (verbose) {
            TTY.println(s);
        }
    }

    @SuppressWarnings("unused")
    private static void dummy() {
    }

    /**
     * Compiles all methods in all classes in the Zip/Jar files passed.
     *
     * @param fileList {@link File#pathSeparator} separated list of Zip/Jar files to compile
     * @throws IOException
     */
    private void compile(String fileList) throws IOException {
        final String[] entries = fileList.split(File.pathSeparator);
        long start = System.currentTimeMillis();

        CompilerThreadFactory factory = new CompilerThreadFactory("CompileTheWorld", new DebugConfigAccess() {
            public JVMCIDebugConfig getDebugConfig() {
                if (Debug.isEnabled() && DebugScope.getConfig() == null) {
                    return DebugEnvironment.initialize(System.out);
                }
                return null;
            }
        });

        try {
            // compile dummy method to get compiler initilized outside of the config debug override.
            HotSpotResolvedJavaMethod dummyMethod = (HotSpotResolvedJavaMethod) JVMCI.getRuntime().getHostJVMCIBackend().getMetaAccess().lookupJavaMethod(
                            CompileTheWorld.class.getDeclaredMethod("dummy"));
            CompilationTask task = new CompilationTask(dummyMethod, Compiler.INVOCATION_ENTRY_BCI, 0L, dummyMethod.allocateCompileId(Compiler.INVOCATION_ENTRY_BCI), false);
            task.runCompilation();
        } catch (NoSuchMethodException | SecurityException e1) {
            e1.printStackTrace();
        }

        /*
         * Always use a thread pool, even for single threaded mode since it simplifies the use of
         * DebugValueThreadFilter to filter on the thread names.
         */
        int threadCount = 1;
        if (Options.CompileTheWorldMultiThreaded.getValue()) {
            threadCount = Options.CompileTheWorldThreads.getValue();
            if (threadCount == 0) {
                threadCount = Runtime.getRuntime().availableProcessors();
            }
        } else {
            running = true;
        }
        threadPool = new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), factory);

        try (OverrideScope s = config.apply()) {
            for (int i = 0; i < entries.length; i++) {
                final String entry = entries[i];

                // For now we only compile all methods in all classes in zip/jar files.
                if (!entry.endsWith(".zip") && !entry.endsWith(".jar")) {
                    println("CompileTheWorld : Skipped classes in " + entry);
                    println();
                    continue;
                }

                if (methodFilters == null || methodFilters.length == 0) {
                    println("CompileTheWorld : Compiling all classes in " + entry);
                } else {
                    String include = Arrays.asList(methodFilters).stream().map(MethodFilter::toString).collect(Collectors.joining(", "));
                    println("CompileTheWorld : Compiling all methods in " + entry + " matching one of the following filters: " + include);
                }
                if (excludeMethodFilters != null && excludeMethodFilters.length > 0) {
                    String exclude = Arrays.asList(excludeMethodFilters).stream().map(MethodFilter::toString).collect(Collectors.joining(", "));
                    println("CompileTheWorld : Excluding all methods matching one of the following filters: " + exclude);
                }
                println();

                URL url = new URL("jar", "", "file:" + entry + "!/");
                ClassLoader loader = new URLClassLoader(new URL[]{url});

                JarFile jarFile = new JarFile(entry);
                Enumeration<JarEntry> e = jarFile.entries();

                while (e.hasMoreElements()) {
                    JarEntry je = e.nextElement();
                    if (je.isDirectory() || !je.getName().endsWith(".class")) {
                        continue;
                    }

                    // Are we done?
                    if (classFileCounter >= stopAt) {
                        break;
                    }

                    String className = je.getName().substring(0, je.getName().length() - ".class".length());
                    String dottedClassName = className.replace('/', '.');
                    classFileCounter++;

                    if (methodFilters != null && !MethodFilter.matchesClassName(methodFilters, dottedClassName)) {
                        continue;
                    }
                    if (excludeMethodFilters != null && MethodFilter.matchesClassName(excludeMethodFilters, dottedClassName)) {
                        continue;
                    }

                    if (dottedClassName.startsWith("jdk.management.") || dottedClassName.startsWith("jdk.internal.cmm.*")) {
                        continue;
                    }

                    try {
                        // Load and initialize class
                        Class<?> javaClass = Class.forName(dottedClassName, true, loader);

                        // Pre-load all classes in the constant pool.
                        try {
                            HotSpotResolvedObjectType objectType = HotSpotResolvedObjectTypeImpl.fromObjectClass(javaClass);
                            ConstantPool constantPool = objectType.constantPool();
                            for (int cpi = 1; cpi < constantPool.length(); cpi++) {
                                constantPool.loadReferencedType(cpi, HotSpotConstantPool.Bytecodes.LDC);
                            }
                        } catch (Throwable t) {
                            // If something went wrong during pre-loading we just ignore it.
                            println("Preloading failed for (%d) %s: %s", classFileCounter, className, t);
                        }

                        // Are we compiling this class?
                        MetaAccessProvider metaAccess = JVMCI.getRuntime().getHostJVMCIBackend().getMetaAccess();
                        if (classFileCounter >= startAt) {
                            println("CompileTheWorld (%d) : %s", classFileCounter, className);

                            // Compile each constructor/method in the class.
                            for (Constructor<?> constructor : javaClass.getDeclaredConstructors()) {
                                HotSpotResolvedJavaMethod javaMethod = (HotSpotResolvedJavaMethod) metaAccess.lookupJavaMethod(constructor);
                                if (canBeCompiled(javaMethod, constructor.getModifiers())) {
                                    compileMethod(javaMethod);
                                }
                            }
                            for (Method method : javaClass.getDeclaredMethods()) {
                                HotSpotResolvedJavaMethod javaMethod = (HotSpotResolvedJavaMethod) metaAccess.lookupJavaMethod(method);
                                if (canBeCompiled(javaMethod, method.getModifiers())) {
                                    compileMethod(javaMethod);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        println("CompileTheWorld (%d) : Skipping %s %s", classFileCounter, className, t.toString());
                        t.printStackTrace();
                    }
                }
                jarFile.close();
            }
        }

        if (!running) {
            startThreads();
        }
        int wakeups = 0;
        while (threadPool.getCompletedTaskCount() != threadPool.getTaskCount()) {
            if (wakeups % 15 == 0) {
                TTY.println("CompileTheWorld : Waiting for " + (threadPool.getTaskCount() - threadPool.getCompletedTaskCount()) + " compiles");
            }
            try {
                threadPool.awaitTermination(1, TimeUnit.SECONDS);
                wakeups++;
            } catch (InterruptedException e) {
            }
        }
        threadPool = null;

        long elapsedTime = System.currentTimeMillis() - start;

        println();
        if (Options.CompileTheWorldMultiThreaded.getValue()) {
            TTY.println("CompileTheWorld : Done (%d classes, %d methods, %d ms elapsed, %d ms compile time, %d bytes of memory used)", classFileCounter, compiledMethodsCounter.get(), elapsedTime,
                            compileTime.get(), memoryUsed.get());
        } else {
            TTY.println("CompileTheWorld : Done (%d classes, %d methods, %d ms, %d bytes of memory used)", classFileCounter, compiledMethodsCounter.get(), compileTime.get(), memoryUsed.get());
        }
    }

    private synchronized void startThreads() {
        running = true;
        // Wake up any waiting threads
        notifyAll();
    }

    private synchronized void waitToRun() {
        while (!running) {
            try {
                wait();
            } catch (InterruptedException e) {
            }
        }
    }

    private void compileMethod(HotSpotResolvedJavaMethod method) throws InterruptedException, ExecutionException {
        if (methodFilters != null && !MethodFilter.matches(methodFilters, method)) {
            return;
        }
        if (excludeMethodFilters != null && MethodFilter.matches(excludeMethodFilters, method)) {
            return;
        }
        Future<?> task = threadPool.submit(new Runnable() {
            public void run() {
                waitToRun();
                try (OverrideScope s = config.apply()) {
                    compileMethod(method, classFileCounter);
                }
            }
        });
        if (threadPool.getCorePoolSize() == 1) {
            task.get();
        }
    }

    /**
     * Compiles a method and gathers some statistics.
     */
    private void compileMethod(HotSpotResolvedJavaMethod method, int counter) {
        try {
            long start = System.currentTimeMillis();
            long allocatedAtStart = getCurrentThreadAllocatedBytes();

            CompilationTask task = new CompilationTask(method, Compiler.INVOCATION_ENTRY_BCI, 0L, method.allocateCompileId(Compiler.INVOCATION_ENTRY_BCI), false);
            task.runCompilation();

            memoryUsed.getAndAdd(getCurrentThreadAllocatedBytes() - allocatedAtStart);
            compileTime.getAndAdd(System.currentTimeMillis() - start);
            compiledMethodsCounter.incrementAndGet();
        } catch (Throwable t) {
            // Catch everything and print a message
            println("CompileTheWorld (%d) : Error compiling method: %s", counter, method.format("%H.%n(%p):%r"));
            t.printStackTrace(TTY.out);
        }
    }

    /**
     * Determines if a method should be compiled (Cf. CompilationPolicy::can_be_compiled).
     *
     * @return true if it can be compiled, false otherwise
     */
    private static boolean canBeCompiled(HotSpotResolvedJavaMethod javaMethod, int modifiers) {
        if (Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)) {
            return false;
        }
        HotSpotVMConfig c = HotSpotJVMCIRuntime.runtime().getConfig();
        if (c.dontCompileHugeMethods && javaMethod.getCodeSize() > c.hugeMethodLimit) {
            return false;
        }
        // Allow use of -XX:CompileCommand=dontinline to exclude problematic methods
        if (!javaMethod.canBeInlined()) {
            return false;
        }
        // Skip @Snippets for now
        for (Annotation annotation : javaMethod.getAnnotations()) {
            if (annotation.annotationType().getName().equals("com.oracle.graal.replacements.Snippet")) {
                return false;
            }
        }
        return true;
    }

}
