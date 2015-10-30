/*
 * Copyright (c) 2014, 2014, Oracle and/or its affiliates. All rights reserved.
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

import java.util.List;

import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.MetaAccessProvider;

import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.DebugMetric;
import com.oracle.graal.graph.Node;
import com.oracle.graal.nodeinfo.InputType;
import com.oracle.graal.nodes.AbstractBeginNode;
import com.oracle.graal.nodes.AbstractDeoptimizeNode;
import com.oracle.graal.nodes.AbstractEndNode;
import com.oracle.graal.nodes.AbstractMergeNode;
import com.oracle.graal.nodes.BeginNode;
import com.oracle.graal.nodes.DeoptimizeNode;
import com.oracle.graal.nodes.DynamicDeoptimizeNode;
import com.oracle.graal.nodes.FixedNode;
import com.oracle.graal.nodes.IfNode;
import com.oracle.graal.nodes.LogicNode;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.ValuePhiNode;
import com.oracle.graal.nodes.calc.IsNullNode;
import com.oracle.graal.nodes.extended.NullCheckNode;
import com.oracle.graal.nodes.util.GraphUtil;
import com.oracle.graal.phases.BasePhase;
import com.oracle.graal.phases.tiers.LowTierContext;

public class UseTrappingNullChecksPhase extends BasePhase<LowTierContext> {

    private static final DebugMetric metricTrappingNullCheck = Debug.metric("TrappingNullCheck");
    private static final DebugMetric metricTrappingNullCheckUnreached = Debug.metric("TrappingNullCheckUnreached");
    private static final DebugMetric metricTrappingNullCheckDynamicDeoptimize = Debug.metric("TrappingNullCheckDynamicDeoptimize");

    @Override
    protected void run(StructuredGraph graph, LowTierContext context) {
        if (context.getTarget().implicitNullCheckLimit <= 0) {
            return;
        }
        assert graph.getGuardsStage().areFrameStatesAtDeopts();

        for (DeoptimizeNode deopt : graph.getNodes(DeoptimizeNode.TYPE)) {
            tryUseTrappingNullCheck(deopt, deopt.predecessor(), deopt.reason(), deopt.getSpeculation());
        }
        for (DynamicDeoptimizeNode deopt : graph.getNodes(DynamicDeoptimizeNode.TYPE)) {
            tryUseTrappingNullCheck(context.getMetaAccess(), deopt);
        }
    }

    private static void tryUseTrappingNullCheck(MetaAccessProvider metaAccessProvider, DynamicDeoptimizeNode deopt) {
        Node predecessor = deopt.predecessor();
        if (predecessor instanceof AbstractMergeNode) {
            AbstractMergeNode merge = (AbstractMergeNode) predecessor;

            // Process each predecessor at the merge, unpacking the reasons and speculations as
            // needed.
            ValueNode reason = deopt.getActionAndReason();
            ValuePhiNode reasonPhi = null;
            List<ValueNode> reasons = null;
            int expectedPhis = 0;

            if (reason instanceof ValuePhiNode) {
                reasonPhi = (ValuePhiNode) reason;
                if (reasonPhi.merge() != merge) {
                    return;
                }
                reasons = reasonPhi.values().snapshot();
                expectedPhis++;
            } else if (!reason.isConstant()) {
                return;
            }

            ValueNode speculation = deopt.getSpeculation();
            ValuePhiNode speculationPhi = null;
            List<ValueNode> speculations = null;
            if (speculation instanceof ValuePhiNode) {
                speculationPhi = (ValuePhiNode) speculation;
                if (speculationPhi.merge() != merge) {
                    return;
                }
                speculations = speculationPhi.values().snapshot();
                expectedPhis++;
            }

            if (merge.phis().count() != expectedPhis) {
                return;
            }

            int index = 0;
            for (AbstractEndNode end : merge.cfgPredecessors().snapshot()) {
                ValueNode thisReason = reasons != null ? reasons.get(index) : reason;
                ValueNode thisSpeculation = speculations != null ? speculations.get(index++) : speculation;
                if (!thisReason.isConstant() || !thisSpeculation.isConstant() || !thisSpeculation.asConstant().equals(JavaConstant.NULL_POINTER)) {
                    continue;
                }
                DeoptimizationReason deoptimizationReason = metaAccessProvider.decodeDeoptReason(thisReason.asJavaConstant());
                tryUseTrappingNullCheck(deopt, end.predecessor(), deoptimizationReason, null);
            }
        }
    }

    private static void tryUseTrappingNullCheck(AbstractDeoptimizeNode deopt, Node predecessor, DeoptimizationReason deoptimizationReason, JavaConstant speculation) {
        if (deoptimizationReason != DeoptimizationReason.NullCheckException && deoptimizationReason != DeoptimizationReason.UnreachedCode) {
            return;
        }
        if (speculation != null && !speculation.equals(JavaConstant.NULL_POINTER)) {
            return;
        }
        if (predecessor instanceof AbstractMergeNode) {
            AbstractMergeNode merge = (AbstractMergeNode) predecessor;
            if (merge.phis().isEmpty()) {
                for (AbstractEndNode end : merge.cfgPredecessors().snapshot()) {
                    checkPredecessor(deopt, end.predecessor(), deoptimizationReason);
                }
            }
        } else if (predecessor instanceof AbstractBeginNode) {
            checkPredecessor(deopt, predecessor, deoptimizationReason);
        }
    }

    private static void checkPredecessor(AbstractDeoptimizeNode deopt, Node predecessor, DeoptimizationReason deoptimizationReason) {
        Node current = predecessor;
        AbstractBeginNode branch = null;
        while (current instanceof AbstractBeginNode) {
            branch = (AbstractBeginNode) current;
            if (branch.anchored().isNotEmpty()) {
                // some input of the deopt framestate is anchored to this branch
                return;
            }
            current = current.predecessor();
        }
        if (current instanceof IfNode) {
            IfNode ifNode = (IfNode) current;
            if (branch != ifNode.trueSuccessor()) {
                return;
            }
            LogicNode condition = ifNode.condition();
            if (condition instanceof IsNullNode) {
                replaceWithTrappingNullCheck(deopt, ifNode, condition, deoptimizationReason);
            }
        }
    }

    private static void replaceWithTrappingNullCheck(AbstractDeoptimizeNode deopt, IfNode ifNode, LogicNode condition, DeoptimizationReason deoptimizationReason) {
        metricTrappingNullCheck.increment();
        if (deopt instanceof DynamicDeoptimizeNode) {
            metricTrappingNullCheckDynamicDeoptimize.increment();
        }
        if (deoptimizationReason == DeoptimizationReason.UnreachedCode) {
            metricTrappingNullCheckUnreached.increment();
        }
        IsNullNode isNullNode = (IsNullNode) condition;
        AbstractBeginNode nonTrappingContinuation = ifNode.falseSuccessor();
        AbstractBeginNode trappingContinuation = ifNode.trueSuccessor();
        NullCheckNode trappingNullCheck = deopt.graph().add(new NullCheckNode(isNullNode.getValue()));
        trappingNullCheck.setStateBefore(deopt.stateBefore());
        deopt.graph().replaceSplit(ifNode, trappingNullCheck, nonTrappingContinuation);

        /*
         * We now have the pattern NullCheck/BeginNode/... It's possible some node is using the
         * BeginNode as a guard input, so replace guard users of the Begin with the NullCheck and
         * then remove the Begin from the graph.
         */
        nonTrappingContinuation.replaceAtUsages(InputType.Guard, trappingNullCheck);
        if (nonTrappingContinuation instanceof BeginNode) {
            FixedNode next = nonTrappingContinuation.next();
            nonTrappingContinuation.clearSuccessors();
            trappingNullCheck.setNext(next);
            nonTrappingContinuation.safeDelete();
        } else {
            trappingNullCheck.setNext(nonTrappingContinuation);
        }

        GraphUtil.killCFG(trappingContinuation);
        if (isNullNode.hasNoUsages()) {
            GraphUtil.killWithUnusedFloatingInputs(isNullNode);
        }
    }
}
