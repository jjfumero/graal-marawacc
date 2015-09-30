/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.phases.common.query;

import java.util.HashSet;
import java.util.Set;

import com.oracle.graal.nodeinfo.InputType;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.memory.MemoryAnchorNode;
import com.oracle.graal.nodes.spi.LoweringTool;
import com.oracle.graal.nodes.util.GraphUtil;
import com.oracle.graal.phases.BasePhase;
import com.oracle.graal.phases.common.CanonicalizerPhase;
import com.oracle.graal.phases.common.FloatingReadPhase;
import com.oracle.graal.phases.common.FrameStateAssignmentPhase;
import com.oracle.graal.phases.common.GuardLoweringPhase;
import com.oracle.graal.phases.common.LoweringPhase;
import com.oracle.graal.phases.common.query.nodes.GraalQueryNode;
import com.oracle.graal.phases.common.query.nodes.InstrumentationNode;
import com.oracle.graal.phases.tiers.LowTierContext;

public class InlineICGPhase extends BasePhase<LowTierContext> {

    @Override
    protected void run(StructuredGraph graph, LowTierContext context) {
        // ICG may be shared amongst multiple InstrumentationNode
        Set<StructuredGraph> icgs = new HashSet<>();
        for (InstrumentationNode instrumentationNode : graph.getNodes().filter(InstrumentationNode.class)) {
            icgs.add(instrumentationNode.icg());
        }
        for (StructuredGraph icg : icgs) {
            new GuardLoweringPhase().apply(icg, null);
            new FrameStateAssignmentPhase().apply(icg, false);
            new LoweringPhase(new CanonicalizerPhase(), LoweringTool.StandardLoweringStage.LOW_TIER).apply(icg, context);
            new FloatingReadPhase(true, true).apply(icg, false);

            MemoryAnchorNode anchor = icg.add(new MemoryAnchorNode());
            icg.start().replaceAtUsages(InputType.Memory, anchor);
            if (anchor.hasNoUsages()) {
                anchor.safeDelete();
            } else {
                icg.addAfterFixed(icg.start(), anchor);
            }
        }

        for (InstrumentationNode instrumentationNode : graph.getNodes().filter(InstrumentationNode.class)) {
            instrumentationNode.inlineAt(instrumentationNode);

            for (GraalQueryNode query : graph.getNodes().filter(GraalQueryNode.class)) {
                query.onInlineICG(instrumentationNode, instrumentationNode);
            }

            GraphUtil.unlinkFixedNode(instrumentationNode);
            instrumentationNode.clearInputs();
            GraphUtil.killCFG(instrumentationNode);
        }

        new CanonicalizerPhase().apply(graph, context);
    }

}
