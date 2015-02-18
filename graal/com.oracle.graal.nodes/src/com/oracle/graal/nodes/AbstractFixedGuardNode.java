/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.util.*;

@NodeInfo
public abstract class AbstractFixedGuardNode extends DeoptimizingFixedWithNextNode implements Simplifiable, GuardingNode {

    public static final NodeClass<AbstractFixedGuardNode> TYPE = NodeClass.get(AbstractFixedGuardNode.class);
    @Input(InputType.Condition) protected LogicNode condition;
    protected final DeoptimizationReason reason;
    protected final DeoptimizationAction action;
    protected boolean negated;

    public LogicNode condition() {
        return condition;
    }

    public void setCondition(LogicNode x) {
        updateUsages(condition, x);
        condition = x;
    }

    protected AbstractFixedGuardNode(NodeClass<? extends AbstractFixedGuardNode> c, LogicNode condition, DeoptimizationReason deoptReason, DeoptimizationAction action, boolean negated) {
        super(c, StampFactory.forVoid());
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
        while (condition instanceof LogicNegationNode) {
            LogicNegationNode negation = (LogicNegationNode) condition;
            setCondition(negation.getValue());
            negated = !negated;
        }
    }

    public DeoptimizeNode lowerToIf() {
        FixedNode currentNext = next();
        setNext(null);
        DeoptimizeNode deopt = graph().add(new DeoptimizeNode(action, reason));
        deopt.setStateBefore(stateBefore());
        IfNode ifNode;
        AbstractBeginNode noDeoptSuccessor;
        if (negated) {
            ifNode = graph().add(new IfNode(condition, deopt, currentNext, 0));
            noDeoptSuccessor = ifNode.falseSuccessor();
        } else {
            ifNode = graph().add(new IfNode(condition, currentNext, deopt, 1));
            noDeoptSuccessor = ifNode.trueSuccessor();
        }
        ((FixedWithNextNode) predecessor()).setNext(ifNode);
        this.replaceAtUsages(noDeoptSuccessor);
        GraphUtil.killWithUnusedFloatingInputs(this);

        return deopt;
    }

    @Override
    public boolean canDeoptimize() {
        return true;
    }
}
