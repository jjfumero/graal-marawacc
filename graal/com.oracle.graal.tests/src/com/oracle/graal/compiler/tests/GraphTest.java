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
package com.oracle.graal.compiler.tests;

import java.lang.reflect.*;
import java.util.concurrent.*;

import junit.framework.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.*;
import com.oracle.graal.compiler.phases.*;
import com.oracle.graal.compiler.phases.PhasePlan.PhasePosition;
import com.oracle.graal.compiler.schedule.*;
import com.oracle.graal.cri.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.Node.Verbosity;
import com.oracle.graal.java.*;
import com.oracle.graal.lir.cfg.*;
import com.oracle.graal.nodes.*;
import com.oracle.max.cri.ci.*;

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
 * See {@link InvokeTest} as an example.
 * <p>
 * The tests can be run in Eclipse with the "Compiler Unit Test" Eclipse
 * launch configuration found in the top level of this project or by
 * running {@code mx unittest} on the command line.
 */
public abstract class GraphTest {

    protected final GraalCompiler graalCompiler;
    protected final ExtendedRiRuntime runtime;

    public GraphTest() {
        Debug.enable();
        this.graalCompiler = GraalAccess.getGraalCompiler();
        this.runtime = graalCompiler.runtime;
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

    private static String getCanonicalGraphString(StructuredGraph graph) {
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

    protected ExtendedRiRuntime runtime() {
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

    protected RiCompiledMethod compile(final RiResolvedMethod method, final StructuredGraph graph) {
        return Debug.scope("Compiling", new DebugDumpScope(String.valueOf(compilationId++), true), new Callable<RiCompiledMethod>() {
            public RiCompiledMethod call() throws Exception {
                CiTargetMethod targetMethod = runtime.compile(method, graph);
                return addMethod(method, targetMethod);
            }
        });
    }

    protected RiCompiledMethod addMethod(final RiResolvedMethod method, final CiTargetMethod tm) {
        return Debug.scope("CodeInstall", new Object[] {graalCompiler, method}, new Callable<RiCompiledMethod>() {
            @Override
            public RiCompiledMethod call() throws Exception {
                final RiCodeInfo[] info = Debug.isDumpEnabled() ? new RiCodeInfo[1] : null;
                RiCompiledMethod installedMethod = runtime.addMethod(method, tm, info);
                if (info != null) {
                    Debug.dump(info[0], "After code installation");
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
        RiResolvedMethod riMethod = runtime.getRiMethod(m);
        StructuredGraph graph = new StructuredGraph(riMethod);
        new GraphBuilderPhase(runtime, GraphBuilderConfiguration.getSnippetDefault(), OptimisticOptimizations.ALL).apply(graph);
        return graph;
    }

    /**
     * Parses a Java method to produce a graph.
     */
    protected StructuredGraph parseProfiled(Method m) {
        RiResolvedMethod riMethod = runtime.getRiMethod(m);
        StructuredGraph graph = new StructuredGraph(riMethod);
        new GraphBuilderPhase(runtime, GraphBuilderConfiguration.getDefault(), OptimisticOptimizations.ALL).apply(graph);
        return graph;
    }

    protected PhasePlan getDefaultPhasePlan() {
        PhasePlan plan = new PhasePlan();
        plan.addPhase(PhasePosition.AFTER_PARSING, new GraphBuilderPhase(runtime, GraphBuilderConfiguration.getSnippetDefault(), OptimisticOptimizations.ALL));
        return plan;
    }
}
