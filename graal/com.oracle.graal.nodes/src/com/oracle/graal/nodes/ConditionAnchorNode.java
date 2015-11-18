/*
 * Copyright (c) 2011, 2014, Oracle and/or its affiliates. All rights reserved.
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

import com.oracle.graal.compiler.common.type.StampFactory;
import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.graph.spi.Canonicalizable;
import com.oracle.graal.graph.spi.CanonicalizerTool;
import com.oracle.graal.nodeinfo.InputType;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodeinfo.Verbosity;
import com.oracle.graal.nodes.extended.GuardingNode;
import com.oracle.graal.nodes.extended.ValueAnchorNode;
import com.oracle.graal.nodes.spi.Lowerable;
import com.oracle.graal.nodes.spi.LoweringTool;

@NodeInfo(nameTemplate = "ConditionAnchor(!={p#negated})", allowedUsageTypes = {InputType.Guard})
public final class ConditionAnchorNode extends FixedWithNextNode implements Canonicalizable.Unary<Node>, Lowerable, GuardingNode {

    public static final NodeClass<ConditionAnchorNode> TYPE = NodeClass.create(ConditionAnchorNode.class);
    @Input(InputType.Condition) LogicNode condition;
    protected boolean negated;

    public ConditionAnchorNode(LogicNode condition) {
        this(condition, false);
    }

    public ConditionAnchorNode(LogicNode condition, boolean negated) {
        super(TYPE, StampFactory.forVoid());
        this.negated = negated;
        this.condition = condition;
    }

    public LogicNode condition() {
        return condition;
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

    public Node canonical(CanonicalizerTool tool, Node forValue) {
        if (forValue instanceof LogicNegationNode) {
            LogicNegationNode negation = (LogicNegationNode) forValue;
            return new ConditionAnchorNode(negation.getValue(), !negated);
        }
        if (forValue instanceof LogicConstantNode) {
            LogicConstantNode c = (LogicConstantNode) forValue;
            if (c.getValue() != negated) {
                return null;
            } else {
                return new ValueAnchorNode(null);
            }
        }
        if (tool.allUsagesAvailable() && this.hasNoUsages()) {
            return null;
        }
        return this;
    }

    @Override
    public void lower(LoweringTool tool) {
        if (graph().getGuardsStage() == StructuredGraph.GuardsStage.FIXED_DEOPTS) {
            ValueAnchorNode newAnchor = graph().add(new ValueAnchorNode(null));
            graph().replaceFixedWithFixed(this, newAnchor);
        }
    }

    public Node getValue() {
        return condition;
    }
}
