/*
 * Copyright (c) 2013, 2013, Oracle and/or its affiliates. All rights reserved.
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

import java.util.*;

import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.cfg.*;
import com.oracle.graal.phases.*;

/**
 * This phase tries to find {@link DeoptimizeNode DeoptimizeNodes} which use the same
 * {@link FrameState} and merges them together.
 */
public class DeoptimizationGroupingPhase extends Phase {

    @Override
    protected void run(StructuredGraph graph) {
        ControlFlowGraph cfg = null;
        for (FrameState fs : graph.getNodes(FrameState.class)) {
            FixedNode target = null;
            List<DeoptimizeNode> obsoletes = null;
            for (DeoptimizeNode deopt : fs.usages().filter(DeoptimizeNode.class)) {
                if (target == null) {
                    target = deopt;
                } else {
                    if (cfg == null) {
                        cfg = ControlFlowGraph.compute(graph, true, true, false, false);
                    }
                    MergeNode merge;
                    if (target instanceof DeoptimizeNode) {
                        merge = graph.add(new MergeNode());
                        EndNode firstEnd = graph.add(new EndNode());
                        merge.addForwardEnd(firstEnd);
                        target.predecessor().replaceFirstSuccessor(target, firstEnd);
                        exitLoops((DeoptimizeNode) target, firstEnd, cfg);
                        merge.setNext(target);
                        obsoletes = new LinkedList<>();
                        target = merge;
                    } else {
                        merge = (MergeNode) target;
                    }
                    EndNode newEnd = graph.add(new EndNode());
                    merge.addForwardEnd(newEnd);
                    deopt.predecessor().replaceFirstSuccessor(deopt, newEnd);
                    exitLoops(deopt, newEnd, cfg);
                    obsoletes.add(deopt);
                }
            }
            if (obsoletes != null) {
                for (DeoptimizeNode obsolete : obsoletes) {
                    obsolete.safeDelete();
                }
            }
        }
    }

    private static void exitLoops(DeoptimizeNode deopt, EndNode end, ControlFlowGraph cfg) {
        Block block = cfg.blockFor(deopt);
        Loop loop = block.getLoop();
        while (loop != null) {
            end.graph().addBeforeFixed(end, end.graph().add(new LoopExitNode(loop.loopBegin())));
            loop = loop.parent;
        }
    }
}
