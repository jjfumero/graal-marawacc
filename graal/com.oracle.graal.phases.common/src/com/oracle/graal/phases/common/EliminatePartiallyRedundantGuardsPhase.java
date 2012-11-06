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

import java.util.*;
import java.util.Map.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.PhiNode.PhiType;
import com.oracle.graal.phases.*;


public class EliminatePartiallyRedundantGuardsPhase extends Phase {

    private static final DebugMetric metricPRGuardsEliminatedAtMerge = Debug.metric("PRGuardsEliminatedAtMerge");
    private static final DebugMetric metricPRGuardsEliminatedAtSplit = Debug.metric("PRGuardsEliminatedAtSplit");

    private static class Condition {
        final BooleanNode conditionNode;
        final boolean negated;
        public Condition(BooleanNode conditionNode, boolean negated) {
            this.conditionNode = conditionNode;
            this.negated = negated;
        }
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((conditionNode == null) ? 0 : conditionNode.hashCode());
            result = prime * result + (negated ? 1231 : 1237);
            return result;
        }
        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            Condition other = (Condition) obj;
            if (conditionNode == null) {
                if (other.conditionNode != null) {
                    return false;
                }
            } else if (!conditionNode.equals(other.conditionNode)) {
                return false;
            }
            if (negated != other.negated) {
                return false;
            }
            return true;
        }
    }

    @Override
    protected void run(StructuredGraph graph) {
        boolean hits;
        do {
            hits = false;
            for (MergeNode merge : graph.getNodes(MergeNode.class)) {
                hits |= eliminateAtMerge(merge);
            }
            for (ControlSplitNode controlSplit : graph.getNodes().filter(ControlSplitNode.class)) {
                hits |= eliminateAtControlSplit(controlSplit);
            }
        } while(hits);
    }

    private static boolean eliminateAtMerge(MergeNode merge) {
        if (merge.forwardEndCount() < 2) {
            return false;
        }
        Collection<GuardNode> hits = new LinkedList<>();
        for (GuardNode guard : merge.guards()) {
            if (guard.dependencies().size() != 1) {
                continue;
            }
            for (EndNode end : merge.forwardEnds()) {
                BeginNode begin = BeginNode.prevBegin(end);
                boolean found = false;
                for (GuardNode predecessorGuard : begin.guards()) {
                    if (predecessorGuard.dependencies().size() != 1) {
                        continue;
                    }
                    if (guard.condition() == predecessorGuard.condition() && guard.negated() == predecessorGuard.negated()) {
                        hits.add(guard);
                        found = true;
                        break;
                    }
                }
                if (found) {
                    break;
                }
            }
        }
        Graph graph = merge.graph();
        for (GuardNode guard : hits) {
            PhiNode phi = graph.add(new PhiNode(PhiType.Guard, merge));
            for (EndNode otherEnd : merge.forwardEnds()) {
                phi.addInput(graph.unique(new GuardNode(guard.condition(), BeginNode.prevBegin(otherEnd), guard.reason(), guard.action(), guard.negated(), guard.getLeafGraphId())));
            }
            guard.replaceAndDelete(phi);
            metricPRGuardsEliminatedAtMerge.increment();
        }
        return !hits.isEmpty();
    }

    private static boolean eliminateAtControlSplit(ControlSplitNode controlSplit) {
        Map<Condition, Collection<GuardNode>> conditionToGuard = new HashMap<>();
        for (BeginNode begin : controlSplit.blockSuccessors()) {
            for (GuardNode guard : begin.guards()) {
                if (guard.dependencies().size() != 1) {
                    continue;
                }
                Condition condition = new Condition(guard.condition(), guard.negated());
                Collection<GuardNode> guards = conditionToGuard.get(condition);
                if (guards == null) {
                    guards = new LinkedList<>();
                    conditionToGuard.put(condition, guards);
                }
                guards.add(guard);
            }
        }

        boolean hits = false;
        for (Entry<Condition, Collection<GuardNode>> entry : conditionToGuard.entrySet()) {
            Collection<GuardNode> guards = entry.getValue();
            if (guards.size() < 2) {
                continue;
            }
            DeoptimizationReason reason = null;
            DeoptimizationAction action = DeoptimizationAction.None;
            long leafGraphId = -1;
            Set<BeginNode> begins = new HashSet<>(3);
            for (GuardNode guard : guards) {
                BeginNode begin = (BeginNode) guard.dependencies().first();
                begins.add(begin);
                if (guard.action().ordinal() > action.ordinal()) {
                    action = guard.action();
                }
                if (reason == null) {
                    reason = guard.reason();
                } else if (reason != guard.reason()) {
                    reason = DeoptimizationReason.None;
                }
                if (leafGraphId == -1) {
                    leafGraphId = guard.getLeafGraphId();
                } else if (leafGraphId != guard.getLeafGraphId()) {
                    leafGraphId = -1;
                }
            }
            if (leafGraphId < 0) {
                continue;
            }
            if (begins.size() == controlSplit.blockSuccessors().count()) {
                hits = true;
                Condition condition = entry.getKey();
                GuardNode newGuard = controlSplit.graph().unique(new GuardNode(condition.conditionNode, BeginNode.prevBegin(controlSplit), reason, action, condition.negated, leafGraphId));
                for (GuardNode guard : guards) {
                    guard.replaceAndDelete(newGuard);
                    metricPRGuardsEliminatedAtSplit.increment();
                }
            }
        }
        return hits;
    }
}
