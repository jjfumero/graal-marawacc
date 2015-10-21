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
package com.oracle.graal.compiler.test;

import static com.oracle.graal.compiler.GraalCompiler.getProfilingInfo;
import static com.oracle.graal.compiler.GraalCompilerOptions.PrintCompilation;
import static com.oracle.graal.nodes.ConstantNode.getConstantNodes;
import static jdk.vm.ci.code.CodeUtil.getCallingConvention;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import jdk.vm.ci.code.Architecture;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.CallingConvention.Type;
import jdk.vm.ci.code.CodeCacheProvider;
import jdk.vm.ci.code.CompilationResult;
import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.common.JVMCIError;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ProfilingInfo;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.SpeculationLog;
import jdk.vm.ci.options.DerivedOptionValue;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.internal.AssumptionViolatedException;

import com.oracle.graal.api.directives.GraalDirectives;
import com.oracle.graal.api.replacements.SnippetReflectionProvider;
import com.oracle.graal.api.runtime.Graal;
import com.oracle.graal.compiler.GraalCompiler;
import com.oracle.graal.compiler.GraalCompiler.Request;
import com.oracle.graal.compiler.target.Backend;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.debug.DebugDumpScope;
import com.oracle.graal.debug.DebugEnvironment;
import com.oracle.graal.debug.TTY;
import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.NodeMap;
import com.oracle.graal.graphbuilderconf.GraphBuilderConfiguration;
import com.oracle.graal.graphbuilderconf.GraphBuilderConfiguration.Plugins;
import com.oracle.graal.graphbuilderconf.GraphBuilderContext;
import com.oracle.graal.graphbuilderconf.InvocationPlugin;
import com.oracle.graal.graphbuilderconf.InvocationPlugins;
import com.oracle.graal.java.ComputeLoopFrequenciesClosure;
import com.oracle.graal.java.GraphBuilderPhase;
import com.oracle.graal.lir.asm.CompilationResultBuilderFactory;
import com.oracle.graal.lir.phases.LIRSuites;
import com.oracle.graal.nodeinfo.Verbosity;
import com.oracle.graal.nodes.BreakpointNode;
import com.oracle.graal.nodes.ConstantNode;
import com.oracle.graal.nodes.InfopointNode;
import com.oracle.graal.nodes.ProxyNode;
import com.oracle.graal.nodes.ReturnNode;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.StructuredGraph.AllowAssumptions;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.cfg.Block;
import com.oracle.graal.nodes.spi.LoweringProvider;
import com.oracle.graal.nodes.spi.Replacements;
import com.oracle.graal.nodes.virtual.VirtualObjectNode;
import com.oracle.graal.phases.BasePhase;
import com.oracle.graal.phases.OptimisticOptimizations;
import com.oracle.graal.phases.Phase;
import com.oracle.graal.phases.PhaseSuite;
import com.oracle.graal.phases.common.CanonicalizerPhase;
import com.oracle.graal.phases.common.ConvertDeoptimizeToGuardPhase;
import com.oracle.graal.phases.schedule.SchedulePhase;
import com.oracle.graal.phases.schedule.SchedulePhase.SchedulingStrategy;
import com.oracle.graal.phases.tiers.HighTierContext;
import com.oracle.graal.phases.tiers.Suites;
import com.oracle.graal.phases.tiers.TargetProvider;
import com.oracle.graal.phases.util.Providers;
import com.oracle.graal.runtime.RuntimeProvider;
import com.oracle.graal.test.GraalTest;

/**
 * Base class for Graal compiler unit tests.
 * <p>
 * White box tests for Graal compiler transformations use this pattern:
 * <ol>
 * <li>Create a graph by {@linkplain #parseEager(String, AllowAssumptions) parsing} a method.</li>
 * <li>Manually modify the graph (e.g. replace a parameter node with a constant).</li>
 * <li>Apply a transformation to the graph.</li>
 * <li>Assert that the transformed graph is equal to an expected graph.</li>
 * </ol>
 * <p>
 * See {@link InvokeHintsTest} as an example of a white box test.
 * <p>
 * Black box tests use the {@link #test(String, Object...)} or
 * {@link #testN(int, String, Object...)} to execute some method in the interpreter and compare its
 * result against that produced by a Graal compiled version of the method.
 * <p>
 * These tests will be run by the {@code mx unittest} command.
 */
public abstract class GraalCompilerTest extends GraalTest {

    private final Providers providers;
    private final Backend backend;
    private final DerivedOptionValue<Suites> suites;
    private final DerivedOptionValue<LIRSuites> lirSuites;

    /**
     * Can be overridden by unit tests to verify properties of the graph.
     *
     * @param graph the graph at the end of HighTier
     */
    protected boolean checkHighTierGraph(StructuredGraph graph) {
        return true;
    }

    /**
     * Can be overridden by unit tests to verify properties of the graph.
     *
     * @param graph the graph at the end of MidTier
     */
    protected boolean checkMidTierGraph(StructuredGraph graph) {
        return true;
    }

    /**
     * Can be overridden by unit tests to verify properties of the graph.
     *
     * @param graph the graph at the end of LowTier
     */
    protected boolean checkLowTierGraph(StructuredGraph graph) {
        return true;
    }

    protected static void breakpoint() {
    }

    @SuppressWarnings("unused")
    protected static void breakpoint(int arg0) {
    }

    protected Suites createSuites() {
        Suites ret = backend.getSuites().createSuites();
        ListIterator<BasePhase<? super HighTierContext>> iter = ret.getHighTier().findPhase(ConvertDeoptimizeToGuardPhase.class);
        PhaseSuite.findNextPhase(iter, CanonicalizerPhase.class);
        iter.add(new Phase("ComputeLoopFrequenciesPhase") {

            @Override
            protected void run(StructuredGraph graph) {
                ComputeLoopFrequenciesClosure.compute(graph);
            }
        });
        ret.getHighTier().appendPhase(new Phase("CheckGraphPhase") {

            @Override
            protected void run(StructuredGraph graph) {
                assert checkHighTierGraph(graph) : "failed HighTier graph check";
            }
        });
        ret.getMidTier().appendPhase(new Phase("CheckGraphPhase") {

            @Override
            protected void run(StructuredGraph graph) {
                assert checkMidTierGraph(graph) : "failed MidTier graph check";
            }
        });
        ret.getLowTier().appendPhase(new Phase("CheckGraphPhase") {

            @Override
            protected void run(StructuredGraph graph) {
                assert checkLowTierGraph(graph) : "failed LowTier graph check";
            }
        });
        return ret;
    }

    protected LIRSuites createLIRSuites() {
        LIRSuites ret = backend.getSuites().createLIRSuites();
        return ret;
    }

    public GraalCompilerTest() {
        this.backend = Graal.getRequiredCapability(RuntimeProvider.class).getHostBackend();
        this.providers = getBackend().getProviders();
        this.suites = new DerivedOptionValue<>(this::createSuites);
        this.lirSuites = new DerivedOptionValue<>(this::createLIRSuites);
    }

    /**
     * Set up a test for a non-default backend. The test should check (via {@link #getBackend()} )
     * whether the desired backend is available.
     *
     * @param arch the name of the desired backend architecture
     */
    public GraalCompilerTest(Class<? extends Architecture> arch) {
        RuntimeProvider runtime = Graal.getRequiredCapability(RuntimeProvider.class);
        Backend b = runtime.getBackend(arch);
        if (b != null) {
            this.backend = b;
        } else {
            // Fall back to the default/host backend
            this.backend = runtime.getHostBackend();
        }
        this.providers = backend.getProviders();
        this.suites = new DerivedOptionValue<>(this::createSuites);
        this.lirSuites = new DerivedOptionValue<>(this::createLIRSuites);
    }

    @BeforeClass
    public static void initializeDebugging() {
        DebugEnvironment.initialize(System.out);
    }

    private Scope debugScope;

    @Before
    public void beforeTest() {
        assert debugScope == null;
        debugScope = Debug.scope(getClass());
    }

    @After
    public void afterTest() {
        if (debugScope != null) {
            debugScope.close();
        }
        debugScope = null;
    }

    protected void assertEquals(StructuredGraph expected, StructuredGraph graph) {
        assertEquals(expected, graph, false, true);
    }

    protected int countUnusedConstants(StructuredGraph graph) {
        int total = 0;
        for (ConstantNode node : getConstantNodes(graph)) {
            if (node.hasNoUsages()) {
                total++;
            }
        }
        return total;
    }

    protected int getNodeCountExcludingUnusedConstants(StructuredGraph graph) {
        return graph.getNodeCount() - countUnusedConstants(graph);
    }

    protected void assertEquals(StructuredGraph expected, StructuredGraph graph, boolean excludeVirtual, boolean checkConstants) {
        String expectedString = getCanonicalGraphString(expected, excludeVirtual, checkConstants);
        String actualString = getCanonicalGraphString(graph, excludeVirtual, checkConstants);
        String mismatchString = compareGraphStrings(expected, expectedString, graph, actualString);

        if (!excludeVirtual && getNodeCountExcludingUnusedConstants(expected) != getNodeCountExcludingUnusedConstants(graph)) {
            Debug.dump(expected, "Node count not matching - expected");
            Debug.dump(graph, "Node count not matching - actual");
            Assert.fail("Graphs do not have the same number of nodes: " + expected.getNodeCount() + " vs. " + graph.getNodeCount() + "\n" + mismatchString);
        }
        if (!expectedString.equals(actualString)) {
            Debug.dump(expected, "mismatching graphs - expected");
            Debug.dump(graph, "mismatching graphs - actual");
            Assert.fail(mismatchString);
        }
    }

    private static String compareGraphStrings(StructuredGraph expectedGraph, String expectedString, StructuredGraph actualGraph, String actualString) {
        if (!expectedString.equals(actualString)) {
            String[] expectedLines = expectedString.split("\n");
            String[] actualLines = actualString.split("\n");
            int diffIndex = -1;
            int limit = Math.min(actualLines.length, expectedLines.length);
            String marker = " <<<";
            for (int i = 0; i < limit; i++) {
                if (!expectedLines[i].equals(actualLines[i])) {
                    diffIndex = i;
                    break;
                }
            }
            if (diffIndex == -1) {
                // Prefix is the same so add some space after the prefix
                diffIndex = limit;
                if (actualLines.length == limit) {
                    actualLines = Arrays.copyOf(actualLines, limit + 1);
                    actualLines[diffIndex] = "";
                } else {
                    assert expectedLines.length == limit;
                    expectedLines = Arrays.copyOf(expectedLines, limit + 1);
                    expectedLines[diffIndex] = "";
                }
            }
            // Place a marker next to the first line that differs
            expectedLines[diffIndex] = expectedLines[diffIndex] + marker;
            actualLines[diffIndex] = actualLines[diffIndex] + marker;
            String ediff = String.join("\n", expectedLines);
            String adiff = String.join("\n", actualLines);
            return "mismatch in graphs:\n========= expected (" + expectedGraph + ") =========\n" + ediff + "\n\n========= actual (" + actualGraph + ") =========\n" + adiff;
        } else {
            return "mismatch in graphs";
        }
    }

    protected void assertConstantReturn(StructuredGraph graph, int value) {
        String graphString = getCanonicalGraphString(graph, false, true);
        Assert.assertEquals("unexpected number of ReturnNodes: " + graphString, graph.getNodes(ReturnNode.TYPE).count(), 1);
        ValueNode result = graph.getNodes(ReturnNode.TYPE).first().result();
        Assert.assertTrue("unexpected ReturnNode result node: " + graphString, result.isConstant());
        Assert.assertEquals("unexpected ReturnNode result kind: " + graphString, result.asJavaConstant().getJavaKind(), JavaKind.Int);
        Assert.assertEquals("unexpected ReturnNode result: " + graphString, result.asJavaConstant().asInt(), value);
    }

    protected static String getCanonicalGraphString(StructuredGraph graph, boolean excludeVirtual, boolean checkConstants) {
        SchedulePhase schedule = new SchedulePhase(SchedulingStrategy.EARLIEST);
        schedule.apply(graph);

        NodeMap<Integer> canonicalId = graph.createNodeMap();
        int nextId = 0;

        List<String> constantsLines = new ArrayList<>();

        StringBuilder result = new StringBuilder();
        for (Block block : schedule.getCFG().getBlocks()) {
            result.append("Block " + block + " ");
            if (block == schedule.getCFG().getStartBlock()) {
                result.append("* ");
            }
            result.append("-> ");
            for (Block succ : block.getSuccessors()) {
                result.append(succ + " ");
            }
            result.append("\n");
            for (Node node : schedule.getBlockToNodesMap().get(block)) {
                if (node instanceof ValueNode && node.isAlive()) {
                    if (!excludeVirtual || !(node instanceof VirtualObjectNode || node instanceof ProxyNode || node instanceof InfopointNode)) {
                        if (node instanceof ConstantNode) {
                            String name = checkConstants ? node.toString(Verbosity.Name) : node.getClass().getSimpleName();
                            String str = name + (excludeVirtual ? "\n" : "    (" + node.getUsageCount() + ")\n");
                            constantsLines.add(str);
                        } else {
                            int id;
                            if (canonicalId.get(node) != null) {
                                id = canonicalId.get(node);
                            } else {
                                id = nextId++;
                                canonicalId.set(node, id);
                            }
                            String name = node.getClass().getSimpleName();
                            String str = "  " + id + "|" + name + (excludeVirtual ? "\n" : "    (" + node.getUsageCount() + ")\n");
                            result.append(str);
                        }
                    }
                }
            }
        }

        StringBuilder constantsLinesResult = new StringBuilder();
        constantsLinesResult.append(constantsLines.size() + " constants:\n");
        Collections.sort(constantsLines);
        for (String s : constantsLines) {
            constantsLinesResult.append(s);
            constantsLinesResult.append("\n");
        }

        return constantsLines.toString() + result.toString();
    }

    protected Backend getBackend() {
        return backend;
    }

    protected Suites getSuites() {
        return suites.getValue();
    }

    protected LIRSuites getLIRSuites() {
        return lirSuites.getValue();
    }

    protected Providers getProviders() {
        return providers;
    }

    protected HighTierContext getDefaultHighTierContext() {
        return new HighTierContext(getProviders(), getDefaultGraphBuilderSuite(), OptimisticOptimizations.ALL);
    }

    protected SnippetReflectionProvider getSnippetReflection() {
        return Graal.getRequiredCapability(SnippetReflectionProvider.class);
    }

    protected TargetDescription getTarget() {
        return getTargetProvider().getTarget();
    }

    protected TargetProvider getTargetProvider() {
        return getBackend();
    }

    protected CodeCacheProvider getCodeCache() {
        return getProviders().getCodeCache();
    }

    protected ConstantReflectionProvider getConstantReflection() {
        return getProviders().getConstantReflection();
    }

    protected MetaAccessProvider getMetaAccess() {
        return getProviders().getMetaAccess();
    }

    protected LoweringProvider getLowerer() {
        return getProviders().getLowerer();
    }

    private static AtomicInteger compilationId = new AtomicInteger();

    protected void testN(int n, final String name, final Object... args) {
        final List<Throwable> errors = new ArrayList<>(n);
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(i + ":" + name) {

                @Override
                public void run() {
                    try {
                        test(name, args);
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                }
            };
            threads[i] = t;
            t.start();
        }
        for (int i = 0; i < n; i++) {
            try {
                threads[i].join();
            } catch (InterruptedException e) {
                errors.add(e);
            }
        }
        if (!errors.isEmpty()) {
            throw new MultiCauseAssertionError(errors.size() + " failures", errors.toArray(new Throwable[errors.size()]));
        }
    }

    protected Object referenceInvoke(ResolvedJavaMethod method, Object receiver, Object... args) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        return invoke(method, receiver, args);
    }

    protected static class Result {

        public final Object returnValue;
        public final Throwable exception;

        public Result(Object returnValue, Throwable exception) {
            this.returnValue = returnValue;
            this.exception = exception;
        }

        @Override
        public String toString() {
            return exception == null ? returnValue == null ? "null" : returnValue.toString() : "!" + exception;
        }
    }

    /**
     * Called before a test is executed.
     */
    protected void before(@SuppressWarnings("unused") ResolvedJavaMethod method) {
    }

    /**
     * Called after a test is executed.
     */
    protected void after() {
    }

    protected Result executeExpected(ResolvedJavaMethod method, Object receiver, Object... args) {
        before(method);
        try {
            // This gives us both the expected return value as well as ensuring that the method to
            // be compiled is fully resolved
            return new Result(referenceInvoke(method, receiver, args), null);
        } catch (InvocationTargetException e) {
            return new Result(null, e.getTargetException());
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            after();
        }
    }

    protected Result executeActual(ResolvedJavaMethod method, Object receiver, Object... args) {
        before(method);
        Object[] executeArgs = argsWithReceiver(receiver, args);

        checkArgs(method, executeArgs);

        InstalledCode compiledMethod = getCode(method);
        try {
            return new Result(compiledMethod.executeVarargs(executeArgs), null);
        } catch (Throwable e) {
            return new Result(null, e);
        } finally {
            after();
        }
    }

    protected void checkArgs(ResolvedJavaMethod method, Object[] args) {
        JavaType[] sig = method.toParameterTypes();
        Assert.assertEquals(sig.length, args.length);
        for (int i = 0; i < args.length; i++) {
            JavaType javaType = sig[i];
            JavaKind kind = javaType.getJavaKind();
            Object arg = args[i];
            if (kind == JavaKind.Object) {
                if (arg != null && javaType instanceof ResolvedJavaType) {
                    ResolvedJavaType resolvedJavaType = (ResolvedJavaType) javaType;
                    Assert.assertTrue(resolvedJavaType + " from " + getMetaAccess().lookupJavaType(arg.getClass()), resolvedJavaType.isAssignableFrom(getMetaAccess().lookupJavaType(arg.getClass())));
                }
            } else {
                Assert.assertNotNull(arg);
                Assert.assertEquals(kind.toBoxedJavaClass(), arg.getClass());
            }
        }
    }

    /**
     * Prepends a non-null receiver argument to a given list or args.
     *
     * @param receiver the receiver argument to prepend if it is non-null
     */
    protected Object[] argsWithReceiver(Object receiver, Object... args) {
        Object[] executeArgs;
        if (receiver == null) {
            executeArgs = args;
        } else {
            executeArgs = new Object[args.length + 1];
            executeArgs[0] = receiver;
            for (int i = 0; i < args.length; i++) {
                executeArgs[i + 1] = args[i];
            }
        }
        return applyArgSuppliers(executeArgs);
    }

    protected void test(String name, Object... args) {
        try {
            ResolvedJavaMethod method = getResolvedJavaMethod(name);
            Object receiver = method.isStatic() ? null : this;
            test(method, receiver, args);
        } catch (AssumptionViolatedException e) {
            // Suppress so that subsequent calls to this method within the
            // same Junit @Test annotated method can proceed.
        }
    }

    /**
     * Type denoting a lambda that supplies a fresh value each time it is called. This is useful
     * when supplying an argument to {@link GraalCompilerTest#test(String, Object...)} where the
     * test modifies the state of the argument (e.g., updates a field).
     */
    @FunctionalInterface
    public interface ArgSupplier extends Supplier<Object> {
    }

    /**
     * Convenience method for using an {@link ArgSupplier} lambda in a varargs list.
     */
    public static Object supply(ArgSupplier supplier) {
        return supplier;
    }

    protected void test(ResolvedJavaMethod method, Object receiver, Object... args) {
        Result expect = executeExpected(method, receiver, args);
        if (getCodeCache() == null) {
            return;
        }
        testAgainstExpected(method, expect, receiver, args);
    }

    /**
     * Process a given set of arguments, converting any {@link ArgSupplier} argument to the argument
     * it supplies.
     */
    protected Object[] applyArgSuppliers(Object... args) {
        Object[] res = args;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof ArgSupplier) {
                if (res == args) {
                    res = args.clone();
                }
                res[i] = ((ArgSupplier) args[i]).get();
            }
        }
        return res;
    }

    protected void testAgainstExpected(ResolvedJavaMethod method, Result expect, Object receiver, Object... args) {
        testAgainstExpected(method, expect, Collections.<DeoptimizationReason> emptySet(), receiver, args);
    }

    protected Result executeActualCheckDeopt(ResolvedJavaMethod method, Set<DeoptimizationReason> shouldNotDeopt, Object receiver, Object... args) {
        Map<DeoptimizationReason, Integer> deoptCounts = new EnumMap<>(DeoptimizationReason.class);
        ProfilingInfo profile = method.getProfilingInfo();
        for (DeoptimizationReason reason : shouldNotDeopt) {
            deoptCounts.put(reason, profile.getDeoptimizationCount(reason));
        }
        Result actual = executeActual(method, receiver, args);
        profile = method.getProfilingInfo(); // profile can change after execution
        for (DeoptimizationReason reason : shouldNotDeopt) {
            Assert.assertEquals((int) deoptCounts.get(reason), profile.getDeoptimizationCount(reason));
        }
        return actual;
    }

    protected void assertEquals(Result expect, Result actual) {
        if (expect.exception != null) {
            Assert.assertTrue("expected " + expect.exception, actual.exception != null);
            Assert.assertEquals("Exception class", expect.exception.getClass(), actual.exception.getClass());
            Assert.assertEquals("Exception message", expect.exception.getMessage(), actual.exception.getMessage());
        } else {
            if (actual.exception != null) {
                actual.exception.printStackTrace();
                Assert.fail("expected " + expect.returnValue + " but got an exception");
            }
            assertDeepEquals(expect.returnValue, actual.returnValue);
        }
    }

    protected void testAgainstExpected(ResolvedJavaMethod method, Result expect, Set<DeoptimizationReason> shouldNotDeopt, Object receiver, Object... args) {
        Result actual = executeActualCheckDeopt(method, shouldNotDeopt, receiver, args);
        assertEquals(expect, actual);
    }

    private Map<ResolvedJavaMethod, InstalledCode> cache = new HashMap<>();

    /**
     * Gets installed code for a given method, compiling it first if necessary. The graph is parsed
     * {@link #parseEager(ResolvedJavaMethod, AllowAssumptions) eagerly}.
     */
    protected InstalledCode getCode(ResolvedJavaMethod method) {
        return getCode(method, null);
    }

    /**
     * Gets installed code for a given method, compiling it first if necessary.
     *
     * @param installedCodeOwner the method the compiled code will be associated with when installed
     * @param graph the graph to be compiled. If null, a graph will be obtained from
     *            {@code installedCodeOwner} via {@link #parseForCompile(ResolvedJavaMethod)}.
     */
    protected InstalledCode getCode(ResolvedJavaMethod installedCodeOwner, StructuredGraph graph) {
        return getCode(installedCodeOwner, graph, false);
    }

    /**
     * Gets installed code for a given method and graph, compiling it first if necessary.
     *
     * @param installedCodeOwner the method the compiled code will be associated with when installed
     * @param graph the graph to be compiled. If null, a graph will be obtained from
     *            {@code installedCodeOwner} via {@link #parseForCompile(ResolvedJavaMethod)}.
     * @param forceCompile specifies whether to ignore any previous code cached for the (method,
     *            key) pair
     */
    @SuppressWarnings("try")
    protected InstalledCode getCode(final ResolvedJavaMethod installedCodeOwner, StructuredGraph graph, boolean forceCompile) {
        if (!forceCompile) {
            InstalledCode cached = cache.get(installedCodeOwner);
            if (cached != null) {
                if (cached.isValid()) {
                    return cached;
                }
            }
        }

        final int id = compilationId.incrementAndGet();

        InstalledCode installedCode = null;
        try (AllocSpy spy = AllocSpy.open(installedCodeOwner); Scope ds = Debug.scope("Compiling", new DebugDumpScope(String.valueOf(id), true))) {
            final boolean printCompilation = PrintCompilation.getValue() && !TTY.isSuppressed();
            if (printCompilation) {
                TTY.println(String.format("@%-6d Graal %-70s %-45s %-50s ...", id, installedCodeOwner.getDeclaringClass().getName(), installedCodeOwner.getName(), installedCodeOwner.getSignature()));
            }
            long start = System.currentTimeMillis();
            CompilationResult compResult = compile(installedCodeOwner, graph);
            if (printCompilation) {
                TTY.println(String.format("@%-6d Graal %-70s %-45s %-50s | %4dms %5dB", id, "", "", "", System.currentTimeMillis() - start, compResult.getTargetCodeSize()));
            }

            try (Scope s = Debug.scope("CodeInstall", getCodeCache(), installedCodeOwner)) {
                installedCode = addMethod(installedCodeOwner, compResult);
                if (installedCode == null) {
                    throw new JVMCIError("Could not install code for " + installedCodeOwner.format("%H.%n(%p)"));
                }
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
        } catch (Throwable e) {
            throw Debug.handle(e);
        }

        if (!forceCompile) {
            cache.put(installedCodeOwner, installedCode);
        }
        return installedCode;
    }

    /**
     * Used to produce a graph for a method about to be compiled by
     * {@link #compile(ResolvedJavaMethod, StructuredGraph)} if the second parameter to that method
     * is null.
     *
     * The default implementation in {@link GraalCompilerTest} is to call
     * {@link #parseEager(ResolvedJavaMethod, AllowAssumptions)}.
     */
    protected StructuredGraph parseForCompile(ResolvedJavaMethod method) {
        return parseEager(method, AllowAssumptions.YES);
    }

    /**
     * Compiles a given method.
     *
     * @param installedCodeOwner the method the compiled code will be associated with when installed
     * @param graph the graph to be compiled for {@code installedCodeOwner}. If null, a graph will
     *            be obtained from {@code installedCodeOwner} via
     *            {@link #parseForCompile(ResolvedJavaMethod)}.
     */
    @SuppressWarnings("try")
    protected CompilationResult compile(ResolvedJavaMethod installedCodeOwner, StructuredGraph graph) {
        StructuredGraph graphToCompile = graph == null ? parseForCompile(installedCodeOwner) : graph;
        lastCompiledGraph = graphToCompile;
        try (Scope s = Debug.scope("Compile", graphToCompile)) {
            CallingConvention cc = getCallingConvention(getCodeCache(), Type.JavaCallee, graphToCompile.method(), false);
            Request<CompilationResult> request = new Request<>(graphToCompile, cc, installedCodeOwner, getProviders(), getBackend(), getDefaultGraphBuilderSuite(), OptimisticOptimizations.ALL,
                            getProfilingInfo(graphToCompile), getSuites(), getLIRSuites(), new CompilationResult(), CompilationResultBuilderFactory.Default);
            return GraalCompiler.compile(request);
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
    }

    protected StructuredGraph lastCompiledGraph;

    protected SpeculationLog getSpeculationLog() {
        return null;
    }

    protected InstalledCode addMethod(final ResolvedJavaMethod method, final CompilationResult compResult) {
        return getCodeCache().addCode(method, compResult, null, null);
    }

    private final Map<ResolvedJavaMethod, Method> methodMap = new HashMap<>();

    /**
     * Converts a reflection {@link Method} to a {@link ResolvedJavaMethod}.
     */
    protected ResolvedJavaMethod asResolvedJavaMethod(Method method) {
        ResolvedJavaMethod javaMethod = getMetaAccess().lookupJavaMethod(method);
        methodMap.put(javaMethod, method);
        return javaMethod;
    }

    protected ResolvedJavaMethod getResolvedJavaMethod(String methodName) {
        return asResolvedJavaMethod(getMethod(methodName));
    }

    protected ResolvedJavaMethod getResolvedJavaMethod(Class<?> clazz, String methodName) {
        return asResolvedJavaMethod(getMethod(clazz, methodName));
    }

    protected ResolvedJavaMethod getResolvedJavaMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        return asResolvedJavaMethod(getMethod(clazz, methodName, parameterTypes));
    }

    /**
     * Gets the reflection {@link Method} from which a given {@link ResolvedJavaMethod} was created
     * or null if {@code javaMethod} does not correspond to a reflection method.
     */
    protected Method lookupMethod(ResolvedJavaMethod javaMethod) {
        return methodMap.get(javaMethod);
    }

    protected Object invoke(ResolvedJavaMethod javaMethod, Object receiver, Object... args) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        Method method = lookupMethod(javaMethod);
        Assert.assertTrue(method != null);
        if (!method.isAccessible()) {
            method.setAccessible(true);
        }
        return method.invoke(receiver, applyArgSuppliers(args));
    }

    /**
     * Parses a Java method in {@linkplain GraphBuilderConfiguration#getDefault default} mode to
     * produce a graph.
     *
     * @param methodName the name of the method in {@code this.getClass()} to be parsed
     */
    protected StructuredGraph parseProfiled(String methodName, AllowAssumptions allowAssumptions) {
        return parseProfiled(getResolvedJavaMethod(methodName), allowAssumptions);
    }

    /**
     * Parses a Java method in {@linkplain GraphBuilderConfiguration#getDefault default} mode to
     * produce a graph.
     */
    protected StructuredGraph parseProfiled(ResolvedJavaMethod m, AllowAssumptions allowAssumptions) {
        return parse1(m, getDefaultGraphBuilderSuite(), allowAssumptions);
    }

    /**
     * Parses a Java method in {@linkplain GraphBuilderConfiguration#getEagerDefault eager} mode to
     * produce a graph.
     *
     * @param methodName the name of the method in {@code this.getClass()} to be parsed
     */
    protected StructuredGraph parseEager(String methodName, AllowAssumptions allowAssumptions) {
        return parseEager(getResolvedJavaMethod(methodName), allowAssumptions);
    }

    /**
     * Parses a Java method in {@linkplain GraphBuilderConfiguration#getEagerDefault eager} mode to
     * produce a graph.
     */
    protected StructuredGraph parseEager(ResolvedJavaMethod m, AllowAssumptions allowAssumptions) {
        return parse1(m, getCustomGraphBuilderSuite(GraphBuilderConfiguration.getEagerDefault(getDefaultGraphBuilderPlugins())), allowAssumptions);
    }

    /**
     * Parses a Java method in {@linkplain GraphBuilderConfiguration#getFullDebugDefault full debug}
     * mode to produce a graph.
     */
    protected StructuredGraph parseDebug(ResolvedJavaMethod m, AllowAssumptions allowAssumptions) {
        return parse1(m, getCustomGraphBuilderSuite(GraphBuilderConfiguration.getFullDebugDefault(getDefaultGraphBuilderPlugins())), allowAssumptions);
    }

    @SuppressWarnings("try")
    private StructuredGraph parse1(ResolvedJavaMethod javaMethod, PhaseSuite<HighTierContext> graphBuilderSuite, AllowAssumptions allowAssumptions) {
        assert javaMethod.getAnnotation(Test.class) == null : "shouldn't parse method with @Test annotation: " + javaMethod;
        StructuredGraph graph = new StructuredGraph(javaMethod, allowAssumptions, getSpeculationLog());
        try (Scope ds = Debug.scope("Parsing", javaMethod, graph)) {
            graphBuilderSuite.apply(graph, getDefaultHighTierContext());
            return graph;
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
    }

    protected Plugins getDefaultGraphBuilderPlugins() {
        PhaseSuite<HighTierContext> suite = backend.getSuites().getDefaultGraphBuilderSuite();
        Plugins defaultPlugins = ((GraphBuilderPhase) suite.findPhase(GraphBuilderPhase.class).previous()).getGraphBuilderConfig().getPlugins();
        // defensive copying
        return new Plugins(defaultPlugins);
    }

    protected PhaseSuite<HighTierContext> getDefaultGraphBuilderSuite() {
        // defensive copying
        return backend.getSuites().getDefaultGraphBuilderSuite().copy();
    }

    protected PhaseSuite<HighTierContext> getCustomGraphBuilderSuite(GraphBuilderConfiguration gbConf) {
        PhaseSuite<HighTierContext> suite = getDefaultGraphBuilderSuite();
        ListIterator<BasePhase<? super HighTierContext>> iterator = suite.findPhase(GraphBuilderPhase.class);
        GraphBuilderConfiguration gbConfCopy = editGraphBuilderConfiguration(gbConf.copy());
        iterator.remove();
        iterator.add(new GraphBuilderPhase(gbConfCopy));
        return suite;
    }

    protected GraphBuilderConfiguration editGraphBuilderConfiguration(GraphBuilderConfiguration conf) {
        InvocationPlugins invocationPlugins = conf.getPlugins().getInvocationPlugins();
        invocationPlugins.register(new InvocationPlugin() {
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                b.add(new BreakpointNode());
                return true;
            }
        }, GraalCompilerTest.class, "breakpoint");
        invocationPlugins.register(new InvocationPlugin() {
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode arg0) {
                b.add(new BreakpointNode(arg0));
                return true;
            }
        }, GraalCompilerTest.class, "breakpoint", int.class);
        return conf;
    }

    protected Replacements getReplacements() {
        return getProviders().getReplacements();
    }

    /**
     * Inject a probability for a branch condition into the profiling information of this test case.
     *
     * @param p the probability that cond is true
     * @param cond the condition of the branch
     * @return cond
     */
    protected static boolean branchProbability(double p, boolean cond) {
        return GraalDirectives.injectBranchProbability(p, cond);
    }

    /**
     * Inject an iteration count for a loop condition into the profiling information of this test
     * case.
     *
     * @param i the iteration count of the loop
     * @param cond the condition of the loop
     * @return cond
     */
    protected static boolean iterationCount(double i, boolean cond) {
        return GraalDirectives.injectIterationCount(i, cond);
    }

    /**
     * Test if the current test runs on the given platform. The name must match the name given in
     * the {@link Architecture#getName()}.
     *
     * @param name The name to test
     * @return true if we run on the architecture given by name
     */
    protected boolean isArchitecture(String name) {
        return name.equals(backend.getTarget().arch.getName());
    }
}
