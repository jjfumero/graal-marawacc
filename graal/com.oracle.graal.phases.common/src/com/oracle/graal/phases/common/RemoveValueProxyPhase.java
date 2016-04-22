/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.phases.common;

import uk.ac.ed.marawacc.compilation.MarawaccGraalIR;

import com.oracle.graal.nodes.EntryProxyNode;
import com.oracle.graal.nodes.FrameState;
import com.oracle.graal.nodes.LoopExitNode;
import com.oracle.graal.nodes.ProxyNode;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.util.GraphUtil;
import com.oracle.graal.phases.Phase;

public class RemoveValueProxyPhase extends Phase {

    @Override
    protected void run(StructuredGraph graph) {
        for (ProxyNode vpn : graph.getNodes(ProxyNode.TYPE)) {
            if (!(vpn instanceof EntryProxyNode)) {
                graph.replaceFloating(vpn, vpn.value());
            }
        }
        for (LoopExitNode exit : graph.getNodes(LoopExitNode.TYPE)) {
            FrameState stateAfter = exit.stateAfter();
            if (stateAfter != null) {
                exit.setStateAfter(null);
                if (stateAfter.hasNoUsages()) {
                    GraphUtil.killWithUnusedFloatingInputs(stateAfter);
                }
            }
        }
        graph.setHasValueProxies(false);

        if (MarawaccGraalIR.getInstance().isCompiledGraph(graph.graphId())) {
            System.out.println("COMPILING FOR GPU!!! ");
            MarawaccGraalIR.getInstance().updateGraph(graph);

            StructuredGraph compiledGraph = MarawaccGraalIR.getInstance().getCompiledGraph(graph);
            System.out.println(" -------------- ");
            System.out.println(" >> " + compiledGraph);
            System.out.println(" -------------- ");

        }

    }
}
