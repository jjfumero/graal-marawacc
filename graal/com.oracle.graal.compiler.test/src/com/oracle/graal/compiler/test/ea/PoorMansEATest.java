/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.compiler.test.ea;

import org.junit.Test;

import com.oracle.graal.compiler.test.GraalCompilerTest;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.debug.DebugDumpScope;
import com.oracle.graal.graph.Node;
import com.oracle.graal.nodes.FrameState;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.StructuredGraph.AllowAssumptions;
import com.oracle.graal.nodes.java.AbstractNewObjectNode;
import com.oracle.graal.nodes.java.NewInstanceNode;
import com.oracle.graal.nodes.spi.LoweringTool;
import com.oracle.graal.phases.common.CanonicalizerPhase;
import com.oracle.graal.phases.common.LoweringPhase;
import com.oracle.graal.phases.common.inlining.InliningPhase;
import com.oracle.graal.phases.tiers.HighTierContext;
import com.oracle.graal.phases.tiers.PhaseContext;

/**
 * Tests {@link AbstractNewObjectNode#simplify(com.oracle.graal.graph.spi.SimplifierTool)}.
 *
 */
public class PoorMansEATest extends GraalCompilerTest {
    public static class A {
        public A obj;
    }

    public static A test1Snippet() {
        A a = new A();
        a.obj = a;
        return null;
    }

    @Test
    public void test1() {
        test("test1Snippet");
    }

    @SuppressWarnings("try")
    private void test(final String snippet) {
        try (Scope s = Debug.scope("PoorMansEATest", new DebugDumpScope(snippet))) {
            StructuredGraph graph = parseEager(snippet, AllowAssumptions.NO);
            HighTierContext highTierContext = getDefaultHighTierContext();
            new InliningPhase(new CanonicalizerPhase()).apply(graph, highTierContext);
            PhaseContext context = new PhaseContext(getProviders());
            new LoweringPhase(new CanonicalizerPhase(), LoweringTool.StandardLoweringStage.HIGH_TIER).apply(graph, context);

            // remove framestates in order to trigger the simplification.
            cleanup: for (FrameState fs : graph.getNodes(FrameState.TYPE).snapshot()) {
                for (Node input : fs.inputs()) {
                    if (input instanceof NewInstanceNode) {
                        fs.replaceAtUsages(null);
                        fs.safeDelete();
                        continue cleanup;
                    }
                }
            }
            new CanonicalizerPhase().apply(graph, context);
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
    }
}
