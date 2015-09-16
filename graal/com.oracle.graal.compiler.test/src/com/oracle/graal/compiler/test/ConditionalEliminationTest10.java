/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
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

import org.junit.Assert;
import org.junit.Test;

import com.oracle.graal.api.directives.GraalDirectives;
import com.oracle.graal.nodes.GuardNode;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.StructuredGraph.AllowAssumptions;
import com.oracle.graal.nodes.spi.LoweringTool;
import com.oracle.graal.phases.common.CanonicalizerPhase;
import com.oracle.graal.phases.common.DominatorConditionalEliminationPhase;
import com.oracle.graal.phases.common.LoweringPhase;
import com.oracle.graal.phases.tiers.PhaseContext;

/**
 * This test checks the combined action of
 * {@link com.oracle.graal.phases.common.DominatorConditionalEliminationPhase} and
 * {@link com.oracle.graal.phases.common.LoweringPhase}. The lowering phase needs to introduce the
 * null checks at the correct places for the dominator conditional elimination phase to pick them
 * up.
 */
public class ConditionalEliminationTest10 extends ConditionalEliminationTestBase {

    private static class TestClass {
        int x;
    }

    @SuppressWarnings("all")
    public static int testSnippet(int a, TestClass t) {
        int result = 0;
        if (a == 0) {
            GraalDirectives.controlFlowAnchor();
            result = t.x;
        }
        GraalDirectives.controlFlowAnchor();
        return result + t.x;
    }

    @Test
    public void test1() {
        StructuredGraph graph = parseEager("testSnippet", AllowAssumptions.YES);
        PhaseContext context = new PhaseContext(getProviders());
        new LoweringPhase(new CanonicalizerPhase(), LoweringTool.StandardLoweringStage.HIGH_TIER).apply(graph, context);
        Assert.assertEquals(2, graph.getNodes().filter(GuardNode.class).count());
        new DominatorConditionalEliminationPhase(true).apply(graph, context);
        Assert.assertEquals(1, graph.getNodes().filter(GuardNode.class).count());
    }
}
