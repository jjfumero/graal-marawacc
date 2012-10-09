/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

import junit.framework.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.runtime.*;
import com.oracle.graal.compiler.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.Node.Verbosity;
import com.oracle.graal.java.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.cfg.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.PhasePlan.PhasePosition;
import com.oracle.graal.phases.schedule.*;

/**
 * Base class for Graal compiler unit tests. These are white box tests
 * for Graal compiler transformations. The general pattern for a test is:
 * <ol>
 * <li>Create a graph by {@linkplain #parse(String) parsing} a method.</li>
 * <li>Manually modify the graph (e.g. replace a parameter node with a constant).</li>
 * <li>Apply a transformation to the graph.</li>
 * <li>Assert that the transformed graph is equal to an expected graph.</li>
 * </ol>
 * <p>
 * See {@link InvokeHintsTest} as an example.
 * <p>
 * The tests can be run in Eclipse with the "Compiler Unit Test" Eclipse
 * launch configuration found in the top level of this project or by
 * running {@code mx unittest} on the command line.
 */
public abstract class GraalCompilerTest {

    protected final GraalCodeCacheProvider runtime;
    protected final GraalCompiler graalCompiler;

    public GraalCompilerTest() {
        Debug.enable();
        this.runtime = Graal.getRequiredCapability(GraalCodeCacheProvider.class);
        this.graalCompiler = Graal.getRequiredCapability(GraalCompiler.class);
    }

    protected void assertEquals(StructuredGraph expected, StructuredGraph graph) {
        String expectedString = getCanonicalGraphString(expected);
        String actualString = getCanonicalGraphString(graph);
        String mismatchString = "mismatch in graphs:\n========= expected =========\n" + expectedString + "\n\n========= actual =========\n" + actualString;

        if (expected.getNodeCount() != graph.getNodeCount()) {
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

    protected void assertConstantReturn(StructuredGraph graph, int value) {
        String graphString = getCanonicalGraphString(graph);
        Assert.assertEquals("unexpected number of ReturnNodes: " + graphString, graph.getNodes(ReturnNode.class).count(), 1);
        ValueNode result = graph.getNodes(ReturnNode.class).first().result();
        Assert.assertTrue("unexpected ReturnNode result node: " + graphString, result.isConstant());
        Assert.assertEquals("unexpected ReturnNode result kind: " + graphString, result.asConstant().getKind(), Kind.Int);
        Assert.assertEquals("unexpected ReturnNode result: " + graphString, result.asConstant().asInt(), value);
    }

    protected static String getCanonicalGraphString(StructuredGraph graph) {
        SchedulePhase schedule = new SchedulePhase();
        schedule.apply(graph);

        NodeMap<Integer> canonicalId = graph.createNodeMap();
        int nextId = 0;

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
                int id;
                if (canonicalId.get(node) != null) {
                    id = canonicalId.get(node);
                } else {
                    id = nextId++;
                    canonicalId.set(node, id);
                }
                String name = node instanceof ConstantNode ? node.toString(Verbosity.Name) : node.getClass().getSimpleName();
                result.append("  " + id + "|" + name + "    (" + node.usages().size() + ")\n");
            }
        }
        return result.toString();
    }

    protected GraalCodeCacheProvider runtime() {
        return runtime;
    }

    /**
     * Parses a Java method to produce a graph.
     *
     * @param methodName the name of the method in {@code this.getClass()} to be parsed
     */
    protected StructuredGraph parse(String methodName) {
        return parse(getMethod(methodName));
    }

    protected Method getMethod(String methodName) {
        Method found = null;
        for (Method m : this.getClass().getMethods()) {
            if (m.getName().equals(methodName)) {
                Assert.assertNull(found);
                found = m;
            }
        }
        if (found != null) {
            return found;
        } else {
            throw new RuntimeException("method not found: " + methodName);
        }
    }

    private static int compilationId = 0;

    protected void assertEquals(Object expected, Object actual) {
        Assert.assertEquals(expected, actual);
    }

    protected void testN(int n, final String name, final Object... args) {
        final Throwable[] errors = new Throwable[n];
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            Thread t = new Thread(i + ":" + name) {
                @Override
                public void run() {
                    try {
                        test(name, args);
                    } catch (Throwable e) {
                        errors[idx] = e;
                    }
                }
            };
            threads[i] = t;
            t.start();
        }
        int failed = 0;
        for (int i = 0; i < n; i++) {
            try {
                threads[i].join();
            } catch (InterruptedException e) {
                errors[i] = e;
            }
            if (errors[i] != null) {
                errors[i].printStackTrace();
                failed++;
            }
        }
        Assert.assertTrue(failed + " of " + n + " failed", failed == 0);
    }

    protected Object referenceInvoke(Method method, Object receiver, Object... args) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        return method.invoke(receiver, args);
    }

    static class Result {
        final Object returnValue;
        final Throwable exception;
        public Result(Object returnValue, Throwable exception) {
            this.returnValue = returnValue;
            this.exception = exception;
        }
    }

    protected Result executeExpected(Method method, Object receiver, Object... args) {
        try {
            // This gives us both the expected return value as well as ensuring that the method to be compiled is fully resolved
            return new Result(referenceInvoke(method, receiver, args), null);
        } catch (InvocationTargetException e) {
            return new Result(null, e.getTargetException());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected Result executeActual(Method method, Object receiver, Object... args) {
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

        InstalledCode compiledMethod = getCode(runtime.lookupJavaMethod(method), parse(method));
        try {
            return new Result(compiledMethod.executeVarargs(executeArgs), null);
        } catch (Throwable e) {
            return new Result(null, e);
        }
    }

    protected void test(String name, Object... args) {
        Method method = getMethod(name);
        Object receiver = Modifier.isStatic(method.getModifiers()) ? null : this;

        Result expect = executeExpected(method, receiver, args);
        if (runtime == null) {
            return;
        }
        Result actual = executeActual(method, receiver, args);

        if (expect.exception != null) {
            Assert.assertTrue("expected " + expect.exception, actual.exception != null);
            Assert.assertEquals(expect.exception.getClass(), actual.exception.getClass());
        } else {
            assertEquals(expect.returnValue, actual.returnValue);
        }
    }

    private Map<ResolvedJavaMethod, InstalledCode> cache = new HashMap<>();

    /**
     * Gets installed code for a given method and graph, compiling it first if necessary.
     */
    protected InstalledCode getCode(final ResolvedJavaMethod method, final StructuredGraph graph) {
        return getCode(method, graph, false);
    }

    /**
     * Can be overridden to modify the compilation phases applied for a test.
     *
     * @param method the method being compiled
     * @param graph the graph being compiled
     * @param phasePlan the phase plan to be edited
     */
    protected void editPhasePlan(ResolvedJavaMethod method, StructuredGraph graph, PhasePlan phasePlan) {
    }

    /**
     * Gets installed code for a given method and graph, compiling it first if necessary.
     *
     * @param forceCompile specifies whether to ignore any previous code cached for the (method, key) pair
     */
    protected InstalledCode getCode(final ResolvedJavaMethod method, final StructuredGraph graph, boolean forceCompile) {
        if (!forceCompile) {
            InstalledCode cached = cache.get(method);
            if (cached != null && cached.isValid()) {
                return cached;
            }
        }
        InstalledCode installedCode = Debug.scope("Compiling", new DebugDumpScope(String.valueOf(compilationId++), true), new Callable<InstalledCode>() {
            public InstalledCode call() throws Exception {
                PhasePlan phasePlan = new PhasePlan();
                GraphBuilderPhase graphBuilderPhase = new GraphBuilderPhase(runtime, GraphBuilderConfiguration.getDefault(), OptimisticOptimizations.ALL);
                phasePlan.addPhase(PhasePosition.AFTER_PARSING, graphBuilderPhase);
                editPhasePlan(method, graph, phasePlan);
                CompilationResult compResult = graalCompiler.compileMethod(method, graph, -1, null, phasePlan, OptimisticOptimizations.ALL);
                return addMethod(method, compResult);
            }
        });
        cache.put(method, installedCode);
        return installedCode;
    }

    protected InstalledCode addMethod(final ResolvedJavaMethod method, final CompilationResult compResult) {
        assert graalCompiler != null;
        return Debug.scope("CodeInstall", new Object[] {graalCompiler, method}, new Callable<InstalledCode>() {
            @Override
            public InstalledCode call() throws Exception {
                final CodeInfo[] info = Debug.isDumpEnabled() ? new CodeInfo[1] : null;
                InstalledCode installedMethod = runtime.addMethod(method, compResult, info);
                if (info != null) {
                    Debug.dump(new Object[] {compResult, info[0]}, "After code installation");
                }
                return installedMethod;
            }
        });
    }

    /**
     * Parses a Java method to produce a graph.
     *
     * @param methodName the name of the method in {@code this.getClass()} to be parsed
     */
    protected StructuredGraph parseProfiled(String methodName) {
        return parseProfiled(getMethod(methodName));
    }

    /**
     * Parses a Java method to produce a graph.
     */
    protected StructuredGraph parse(Method m) {
        ResolvedJavaMethod javaMethod = runtime.lookupJavaMethod(m);
        StructuredGraph graph = new StructuredGraph(javaMethod);
        new GraphBuilderPhase(runtime, GraphBuilderConfiguration.getSnippetDefault(), OptimisticOptimizations.ALL).apply(graph);
        return graph;
    }

    /**
     * Parses a Java method to produce a graph.
     */
    protected StructuredGraph parseProfiled(Method m) {
        ResolvedJavaMethod javaMethod = runtime.lookupJavaMethod(m);
        StructuredGraph graph = new StructuredGraph(javaMethod);
        new GraphBuilderPhase(runtime, GraphBuilderConfiguration.getDefault(), OptimisticOptimizations.ALL).apply(graph);
        return graph;
    }

    protected PhasePlan getDefaultPhasePlan() {
        PhasePlan plan = new PhasePlan();
        plan.addPhase(PhasePosition.AFTER_PARSING, new GraphBuilderPhase(runtime, GraphBuilderConfiguration.getSnippetDefault(), OptimisticOptimizations.ALL));
        return plan;
    }
}
