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
package com.oracle.graal.nodes;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.util.*;

@NodeInfo(nameTemplate = "FixedGuard(!={p#negated}) {p#reason/s}")
public final class FixedGuardNode extends DeoptimizingFixedWithNextNode implements Simplifiable, Lowerable, Node.IterableNodeType, Negatable, GuardingNode {

    @Input private LogicNode condition;
    private final DeoptimizationReason reason;
    private final DeoptimizationAction action;
    private boolean negated;

    public LogicNode condition() {
        return condition;
    }

    public void setCondition(LogicNode x) {
        updateUsages(condition, x);
        condition = x;
    }

    public FixedGuardNode(LogicNode condition, DeoptimizationReason deoptReason, DeoptimizationAction action) {
        this(condition, deoptReason, action, false);
    }

    public FixedGuardNode(LogicNode condition, DeoptimizationReason deoptReason, DeoptimizationAction action, boolean negated) {
        super(StampFactory.dependency());
        this.action = action;
        this.negated = negated;
        this.condition = condition;
        this.reason = deoptReason;
    }

    public DeoptimizationReason getReason() {
        return reason;
    }

    public DeoptimizationAction getAction() {
        return action;
    }

    public boolean isNegated() {
        return negated;
    }

    @Override
    public String toString(Verbosity verbosity) {
        if (verbosity == Verbosity.Name && negated) {
            return "!" + super.toString(verbosity);
        } else {
            return super.toString(verbosity);
        }
    }

    @Override
    public void simplify(SimplifierTool tool) {
        if (condition instanceof LogicConstantNode) {
            LogicConstantNode c = (LogicConstantNode) condition;
            if (c.getValue() == negated) {
                FixedNode next = this.next();
                if (next != null) {
                    tool.deleteBranch(next);
                }

                DeoptimizeNode deopt = graph().add(new DeoptimizeNode(DeoptimizationAction.InvalidateRecompile, reason));
                deopt.setDeoptimizationState(getDeoptimizationState());
                setNext(deopt);
            }
            this.replaceAtUsages(BeginNode.prevBegin(this));
            graph().removeFixed(this);
        }
    }

    @Override
    public void lower(LoweringTool tool, LoweringType loweringType) {
        if (loweringType == LoweringType.BEFORE_GUARDS) {
            tool.getRuntime().lower(this, tool);
        } else {
            FixedNode next = next();
            setNext(null);
            DeoptimizeNode deopt = graph().add(new DeoptimizeNode(action, reason));
            deopt.setDeoptimizationState(getDeoptimizationState());
            IfNode ifNode;
            if (negated) {
                ifNode = graph().add(new IfNode(condition, deopt, next, 0));
            } else {
                ifNode = graph().add(new IfNode(condition, next, deopt, 1));
            }
            ((FixedWithNextNode) predecessor()).setNext(ifNode);
            GraphUtil.killWithUnusedFloatingInputs(this);
        }
    }

    @Override
    public Negatable negate(LogicNode cond) {
        assert cond == condition();
        negated = !negated;
        return this;
    }

    @Override
    public FixedGuardNode asNode() {
        return this;
    }

    @Override
    public boolean canDeoptimize() {
        return true;
    }

    @Override
    public DeoptimizationReason getDeoptimizationReason() {
        return reason;
    }
}
