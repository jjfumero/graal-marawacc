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

import jdk.internal.jvmci.meta.ResolvedJavaMethod;

import org.junit.Test;

import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.StructuredGraph.AllowAssumptions;
import com.oracle.graal.nodes.java.MonitorExitNode;
import com.oracle.graal.nodes.java.RawMonitorEnterNode;
import com.oracle.graal.nodes.spi.LoweringTool;
import com.oracle.graal.phases.common.CanonicalizerPhase;
import com.oracle.graal.phases.common.DeadCodeEliminationPhase;
import com.oracle.graal.phases.common.LockEliminationPhase;
import com.oracle.graal.phases.common.LoweringPhase;
import com.oracle.graal.phases.common.ValueAnchorCleanupPhase;
import com.oracle.graal.phases.common.inlining.InliningPhase;
import com.oracle.graal.phases.tiers.HighTierContext;
import com.oracle.graal.phases.tiers.PhaseContext;

public class LockEliminationTest extends GraalCompilerTest {

    static class A {

        int value;

        public synchronized int getValue() {
            return value;
        }
    }

    static int field1;
    static int field2;

    public static void testSynchronizedSnippet(A x, A y) {
        synchronized (x) {
            field1 = x.value;
        }
        synchronized (x) {
            field2 = y.value;
        }
    }

    @Test
    public void testLock() {
        test("testSynchronizedSnippet", new A(), new A());

        StructuredGraph graph = getGraph("testSynchronizedSnippet");
        new CanonicalizerPhase().apply(graph, new PhaseContext(getProviders()));
        new LockEliminationPhase().apply(graph);
        assertDeepEquals(1, graph.getNodes().filter(RawMonitorEnterNode.class).count());
        assertDeepEquals(1, graph.getNodes().filter(MonitorExitNode.class).count());
    }

    public static void testSynchronizedMethodSnippet(A x) {
        int value1 = x.getValue();
        int value2 = x.getValue();
        field1 = value1;
        field2 = value2;
    }

    @Test
    public void testSynchronizedMethod() {
        test("testSynchronizedMethodSnippet", new A());

        StructuredGraph graph = getGraph("testSynchronizedMethodSnippet");
        new CanonicalizerPhase().apply(graph, new PhaseContext(getProviders()));
        new LockEliminationPhase().apply(graph);
        assertDeepEquals(1, graph.getNodes().filter(RawMonitorEnterNode.class).count());
        assertDeepEquals(1, graph.getNodes().filter(MonitorExitNode.class).count());
    }

    private StructuredGraph getGraph(String snippet) {
        ResolvedJavaMethod method = getResolvedJavaMethod(snippet);
        StructuredGraph graph = parseEager(method, AllowAssumptions.YES);
        HighTierContext context = getDefaultHighTierContext();
        new CanonicalizerPhase().apply(graph, context);
        new InliningPhase(new CanonicalizerPhase()).apply(graph, context);
        new CanonicalizerPhase().apply(graph, context);
        new DeadCodeEliminationPhase().apply(graph);
        new LoweringPhase(new CanonicalizerPhase(), LoweringTool.StandardLoweringStage.HIGH_TIER).apply(graph, context);
        new ValueAnchorCleanupPhase().apply(graph);
        new LockEliminationPhase().apply(graph);
        return graph;
    }

}
