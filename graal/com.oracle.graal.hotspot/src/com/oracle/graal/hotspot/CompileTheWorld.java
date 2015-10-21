/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.compiler.GraalCompilerOptions.ExitVMOnException;
import static com.oracle.graal.compiler.GraalCompilerOptions.PrintBailout;
import static com.oracle.graal.compiler.GraalCompilerOptions.PrintStackTraceOnException;
import static com.oracle.graal.hotspot.CompileTheWorldOptions.CompileTheWorldClasspath;
import static com.oracle.graal.hotspot.CompileTheWorldOptions.CompileTheWorldConfig;
import static com.oracle.graal.hotspot.CompileTheWorldOptions.CompileTheWorldExcludeMethodFilter;
import static com.oracle.graal.hotspot.CompileTheWorldOptions.CompileTheWorldMethodFilter;
import static com.oracle.graal.hotspot.CompileTheWorldOptions.CompileTheWorldStartAt;
import static com.oracle.graal.hotspot.CompileTheWorldOptions.CompileTheWorldStopAt;
import static com.oracle.graal.hotspot.CompileTheWorldOptions.CompileTheWorldVerbose;
import static jdk.vm.ci.hotspot.HotSpotVMConfig.config;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import jdk.vm.ci.hotspot.HotSpotCompilationRequest;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntimeProvider;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod;
import jdk.vm.ci.hotspot.HotSpotResolvedObjectType;
import jdk.vm.ci.hotspot.HotSpotVMConfig;
import jdk.vm.ci.meta.ConstantPool;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.options.OptionDescriptor;
import jdk.vm.ci.options.OptionValue;
import jdk.vm.ci.options.OptionValue.OverrideScope;
import jdk.vm.ci.options.OptionsParser;
import jdk.vm.ci.options.OptionsParser.OptionConsumer;
import jdk.vm.ci.runtime.JVMCI;
import jdk.vm.ci.runtime.JVMCICompiler;

import com.oracle.graal.bytecode.Bytecodes;
import com.oracle.graal.compiler.CompilerThreadFactory;
import com.oracle.graal.compiler.CompilerThreadFactory.DebugConfigAccess;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.DebugEnvironment;
import com.oracle.graal.debug.GraalDebugConfig;
import com.oracle.graal.debug.MethodFilter;
import com.oracle.graal.debug.TTY;
import com.oracle.graal.debug.internal.DebugScope;
import com.oracle.graal.debug.internal.MemUseTrackerImpl;

/**
 * This class implements compile-the-world functionality with JVMCI.
 */
public final class CompileTheWorld {

    private static final String JAVA_VERSION = System.getProperty("java.specification.version");

    /**
     * Magic token to trigger reading files from {@code rt.jar} if {@link #JAVA_VERSION} denotes a
     * JDK earlier than 9 otherwise from {@code java.base} module.
     */
    public static final String SUN_BOOT_CLASS_PATH = "sun.boot.class.path";

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
         *            a -G:<value> format but without the leading "-G:". Ignored if null.
         */
        public Config(String options) {
            if (options != null) {
                List<String> optionSettings = new ArrayList<>();
                for (String optionSetting : options.split("\\s+|#")) {
                    if (optionSetting.charAt(0) == '-') {
                        optionSettings.add(optionSetting.substring(1));
                        optionSettings.add("false");
                    } else if (optionSetting.charAt(0) == '+') {
                        optionSettings.add(optionSetting.substring(1));
                        optionSettings.add("true");
                    } else {
                        OptionsParser.parseOptionSettingTo(optionSetting, optionSettings);
                    }
                }
                OptionsParser.parseOptions(optionSettings.toArray(new String[optionSettings.size()]), this, null, null);
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

    private final HotSpotJVMCIRuntimeProvider jvmciRuntime;

    private final HotSpotGraalCompiler compiler;

    /**
     * Class path denoting classes to compile.
     *
     * @see CompileTheWorldOptions#CompileTheWorldClasspath
     */
    private final String inputClassPath;

    /**
     * Class index to start compilation at.
     *
     * @see CompileTheWorldOptions#CompileTheWorldStartAt
     */
    private final int startAt;

    /**
     * Class index to stop compilation at.
     *
     * @see CompileTheWorldOptions#CompileTheWorldStopAt
     */
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
    public CompileTheWorld(HotSpotJVMCIRuntimeProvider jvmciRuntime, HotSpotGraalCompiler compiler, String files, Config config, int startAt, int stopAt, String methodFilters,
                    String excludeMethodFilters, boolean verbose) {
        this.jvmciRuntime = jvmciRuntime;
        this.compiler = compiler;
        this.inputClassPath = files;
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
        config.putIfAbsent(HotSpotResolvedJavaMethod.Options.UseProfilingInformation, false);
    }

    public CompileTheWorld(HotSpotJVMCIRuntimeProvider jvmciRuntime, HotSpotGraalCompiler compiler) {
        this(jvmciRuntime, compiler, CompileTheWorldClasspath.getValue(), new Config(CompileTheWorldConfig.getValue()), CompileTheWorldStartAt.getValue(), CompileTheWorldStopAt.getValue(),
                        CompileTheWorldMethodFilter.getValue(), CompileTheWorldExcludeMethodFilter.getValue(), CompileTheWorldVerbose.getValue());
    }

    /**
     * Compiles all methods in all classes in {@link #inputClassPath}. If {@link #inputClassPath}
     * equals {@link #SUN_BOOT_CLASS_PATH} the boot class path is used.
     */
    public void compile() throws Throwable {
        // By default only report statistics for the CTW threads themselves
        if (GraalDebugConfig.Options.DebugValueThreadFilter.hasDefaultValue()) {
            GraalDebugConfig.Options.DebugValueThreadFilter.setValue("^CompileTheWorld");
        }
        if (SUN_BOOT_CLASS_PATH.equals(inputClassPath)) {
            final String[] entries = System.getProperty(SUN_BOOT_CLASS_PATH).split(File.pathSeparator);
            String bcpEntry = null;
            boolean useRtJar = JAVA_VERSION.compareTo("1.9") < 0;
            for (int i = 0; i < entries.length && bcpEntry == null; i++) {
                String entry = entries[i];
                File entryFile = new File(entry);
                if (useRtJar) {
                    // We stop at rt.jar, unless it is the first boot class path entry.
                    if (entryFile.getName().endsWith("rt.jar") && entryFile.isFile()) {
                        bcpEntry = entry;
                    }
                } else {
                    if (entryFile.getName().endsWith("java.base") && entryFile.isDirectory()) {
                        bcpEntry = entry;
                    } else if (entryFile.getName().equals("bootmodules.jimage")) {
                        bcpEntry = entry;
                    }
                }
            }
            compile(bcpEntry);
        } else {
            compile(inputClassPath);
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
     * Abstraction over different types of class path entries.
     */
    abstract static class ClassPathEntry implements Closeable {
        final String name;

        public ClassPathEntry(String name) {
            this.name = name;
        }

        /**
         * Creates a {@link ClassLoader} for loading classes from this entry.
         */
        public abstract ClassLoader createClassLoader() throws IOException;

        /**
         * Gets the list of classes available under this entry.
         */
        public abstract List<String> getClassNames() throws IOException;

        @Override
        public String toString() {
            return name;
        }

        public void close() throws IOException {
        }
    }

    /**
     * A class path entry that is a normal file system directory.
     */
    static class DirClassPathEntry extends ClassPathEntry {

        private final File dir;

        public DirClassPathEntry(String name) {
            super(name);
            dir = new File(name);
            assert dir.isDirectory();
        }

        @Override
        public ClassLoader createClassLoader() throws IOException {
            URL url = dir.toURI().toURL();
            return new URLClassLoader(new URL[]{url});
        }

        @Override
        public List<String> getClassNames() throws IOException {
            List<String> classNames = new ArrayList<>();
            String root = dir.getPath();
            SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isRegularFile()) {
                        File path = file.toFile();
                        if (path.getName().endsWith(".class")) {
                            String pathString = path.getPath();
                            assert pathString.startsWith(root);
                            String classFile = pathString.substring(root.length() + 1);
                            String className = classFile.replace(File.separatorChar, '.');
                            classNames.add(className.replace('/', '.').substring(0, className.length() - ".class".length()));
                        }
                    }
                    return super.visitFile(file, attrs);
                }
            };
            Files.walkFileTree(dir.toPath(), visitor);
            return classNames;
        }
    }

    /**
     * A class path entry that is a jar or zip file.
     */
    static class JarClassPathEntry extends ClassPathEntry {

        private final JarFile jarFile;

        public JarClassPathEntry(String name) throws IOException {
            super(name);
            jarFile = new JarFile(name);
        }

        @Override
        public ClassLoader createClassLoader() throws IOException {
            URL url = new URL("jar", "", "file:" + name + "!/");
            return new URLClassLoader(new URL[]{url});
        }

        @Override
        public List<String> getClassNames() throws IOException {
            Enumeration<JarEntry> e = jarFile.entries();
            List<String> classNames = new ArrayList<>(jarFile.size());
            while (e.hasMoreElements()) {
                JarEntry je = e.nextElement();
                if (je.isDirectory() || !je.getName().endsWith(".class")) {
                    continue;
                }
                String className = je.getName().substring(0, je.getName().length() - ".class".length());
                classNames.add(className.replace('/', '.'));
            }
            return classNames;
        }

        @Override
        public void close() throws IOException {
            jarFile.close();
        }
    }

    /**
     * A class path entry that is a jimage file.
     */
    static class ImageClassPathEntry extends ClassPathEntry {

        private final File jimage;

        public ImageClassPathEntry(String name) {
            super(name);
            jimage = new File(name);
            assert jimage.isFile();
        }

        @Override
        public ClassLoader createClassLoader() throws IOException {
            URL url = jimage.toURI().toURL();
            return new URLClassLoader(new URL[]{url});
        }

        @Override
        public List<String> getClassNames() throws IOException {
            List<String> classNames = new ArrayList<>();
            String[] entries = readJimageEntries();
            for (String e : entries) {
                if (e.endsWith(".class")) {
                    assert e.charAt(0) == '/' : e;
                    int endModule = e.indexOf('/', 1);
                    assert endModule != -1 : e;
                    // Strip the module prefix and convert to dotted form
                    String className = e.substring(endModule + 1).replace('/', '.');
                    // Strip ".class" suffix
                    className = className.replace('/', '.').substring(0, className.length() - ".class".length());
                    classNames.add(className);
                }
            }
            return classNames;
        }

        private String[] readJimageEntries() {
            try {
                // Use reflection so this can be compiled on JDK8
                Method open = Class.forName("jdk.internal.jimage.BasicImageReader").getDeclaredMethod("open", String.class);
                Object reader = open.invoke(null, name);
                Method getEntryNames = reader.getClass().getDeclaredMethod("getEntryNames");
                getEntryNames.setAccessible(true);
                String[] entries = (String[]) getEntryNames.invoke(reader);
                return entries;
            } catch (Exception e) {
                TTY.println("Error reading entries from " + name + ": " + e);
                return new String[0];
            }
        }
    }

    /**
     * Compiles all methods in all classes in a given class path.
     *
     * @param classPath class path denoting classes to compile
     * @throws IOException
     */
    @SuppressWarnings("try")
    private void compile(String classPath) throws IOException {
        final String[] entries = classPath.split(File.pathSeparator);
        long start = System.currentTimeMillis();

        CompilerThreadFactory factory = new CompilerThreadFactory("CompileTheWorld", new DebugConfigAccess() {
            public GraalDebugConfig getDebugConfig() {
                if (Debug.isEnabled() && DebugScope.getConfig() == null) {
                    return DebugEnvironment.initialize(System.out);
                }
                return null;
            }
        });

        try {
            // compile dummy method to get compiler initialized outside of the
            // config debug override.
            HotSpotResolvedJavaMethod dummyMethod = (HotSpotResolvedJavaMethod) JVMCI.getRuntime().getHostJVMCIBackend().getMetaAccess().lookupJavaMethod(
                            CompileTheWorld.class.getDeclaredMethod("dummy"));
            int entryBCI = JVMCICompiler.INVOCATION_ENTRY_BCI;
            CompilationTask task = new CompilationTask(jvmciRuntime, compiler, new HotSpotCompilationRequest(dummyMethod, entryBCI, 0L), false);
            task.runCompilation();
        } catch (NoSuchMethodException | SecurityException e1) {
            e1.printStackTrace();
        }

        /*
         * Always use a thread pool, even for single threaded mode since it simplifies the use of
         * DebugValueThreadFilter to filter on the thread names.
         */
        int threadCount = 1;
        if (CompileTheWorldOptions.CompileTheWorldMultiThreaded.getValue()) {
            threadCount = CompileTheWorldOptions.CompileTheWorldThreads.getValue();
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

                ClassPathEntry cpe;
                if (entry.endsWith(".zip") || entry.endsWith(".jar")) {
                    cpe = new JarClassPathEntry(entry);
                } else if (entry.endsWith(".jimage")) {
                    assert JAVA_VERSION.compareTo("1.9") >= 0;
                    if (!new File(entry).isFile()) {
                        println("CompileTheWorld : Skipped classes in " + entry);
                        println();
                        continue;
                    }
                    cpe = new ImageClassPathEntry(entry);
                } else {
                    if (!new File(entry).isDirectory()) {
                        println("CompileTheWorld : Skipped classes in " + entry);
                        println();
                        continue;
                    }
                    cpe = new DirClassPathEntry(entry);
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

                ClassLoader loader = cpe.createClassLoader();

                for (String className : cpe.getClassNames()) {

                    // Are we done?
                    if (classFileCounter >= stopAt) {
                        break;
                    }

                    classFileCounter++;

                    if (methodFilters != null && !MethodFilter.matchesClassName(methodFilters, className)) {
                        continue;
                    }
                    if (excludeMethodFilters != null && MethodFilter.matchesClassName(excludeMethodFilters, className)) {
                        continue;
                    }

                    if (className.startsWith("jdk.management.") || className.startsWith("jdk.internal.cmm.*")) {
                        continue;
                    }

                    try {
                        // Load and initialize class
                        Class<?> javaClass = Class.forName(className, true, loader);

                        // Pre-load all classes in the constant pool.
                        try {
                            HotSpotResolvedObjectType objectType = HotSpotResolvedObjectType.fromObjectClass(javaClass);
                            ConstantPool constantPool = objectType.getConstantPool();
                            for (int cpi = 1; cpi < constantPool.length(); cpi++) {
                                constantPool.loadReferencedType(cpi, Bytecodes.LDC);
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
                cpe.close();
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
        if (CompileTheWorldOptions.CompileTheWorldMultiThreaded.getValue()) {
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

    @SuppressWarnings("try")
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
            long allocatedAtStart = MemUseTrackerImpl.getCurrentThreadAllocatedBytes();
            int entryBCI = JVMCICompiler.INVOCATION_ENTRY_BCI;
            HotSpotCompilationRequest request = new HotSpotCompilationRequest(method, entryBCI, 0L);
            CompilationTask task = new CompilationTask(jvmciRuntime, compiler, request, false);
            task.runCompilation();

            memoryUsed.getAndAdd(MemUseTrackerImpl.getCurrentThreadAllocatedBytes() - allocatedAtStart);
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
        HotSpotVMConfig c = config();
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

    public static void main(String[] args) throws Throwable {
        HotSpotGraalCompiler compiler = (HotSpotGraalCompiler) HotSpotJVMCIRuntime.runtime().getCompiler();
        compiler.compileTheWorld();
    }
}
