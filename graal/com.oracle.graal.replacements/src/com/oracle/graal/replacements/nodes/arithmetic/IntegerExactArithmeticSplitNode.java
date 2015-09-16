/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.replacements.nodes.arithmetic;

import jdk.internal.jvmci.meta.DeoptimizationAction;
import jdk.internal.jvmci.meta.DeoptimizationReason;
import jdk.internal.jvmci.meta.Value;

import com.oracle.graal.compiler.common.type.Stamp;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.AbstractBeginNode;
import com.oracle.graal.nodes.BeginNode;
import com.oracle.graal.nodes.ControlSplitNode;
import com.oracle.graal.nodes.DeoptimizeNode;
import com.oracle.graal.nodes.FixedNode;
import com.oracle.graal.nodes.FixedWithNextNode;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.calc.FloatingNode;
import com.oracle.graal.nodes.spi.LIRLowerable;
import com.oracle.graal.nodes.spi.LoweringTool;
import com.oracle.graal.nodes.spi.NodeLIRBuilderTool;

@NodeInfo
public abstract class IntegerExactArithmeticSplitNode extends ControlSplitNode implements LIRLowerable {
    public static final NodeClass<IntegerExactArithmeticSplitNode> TYPE = NodeClass.create(IntegerExactArithmeticSplitNode.class);

    @Successor AbstractBeginNode next;
    @Successor AbstractBeginNode overflowSuccessor;
    @Input ValueNode x;
    @Input ValueNode y;

    protected IntegerExactArithmeticSplitNode(NodeClass<? extends IntegerExactArithmeticSplitNode> c, Stamp stamp, ValueNode x, ValueNode y, AbstractBeginNode next, AbstractBeginNode overflowSuccessor) {
        super(c, stamp);
        this.x = x;
        this.y = y;
        this.overflowSuccessor = overflowSuccessor;
        this.next = next;
    }

    @Override
    public AbstractBeginNode getPrimarySuccessor() {
        return next;
    }

    @Override
    public double probability(AbstractBeginNode successor) {
        return successor == next ? 1 : 0;
    }

    public AbstractBeginNode getNext() {
        return next;
    }

    public AbstractBeginNode getOverflowSuccessor() {
        return overflowSuccessor;
    }

    public ValueNode getX() {
        return x;
    }

    public ValueNode getY() {
        return y;
    }

    @Override
    public void generate(NodeLIRBuilderTool generator) {
        generator.setResult(this, generateArithmetic(generator));
        generator.emitOverflowCheckBranch(getOverflowSuccessor(), getNext(), stamp, probability(getOverflowSuccessor()));
    }

    protected abstract Value generateArithmetic(NodeLIRBuilderTool generator);

    static void lower(LoweringTool tool, IntegerExactArithmeticNode node) {
        if (node.asNode().graph().getGuardsStage() == StructuredGraph.GuardsStage.FIXED_DEOPTS) {
            FloatingNode floatingNode = (FloatingNode) node;
            FixedWithNextNode previous = tool.lastFixedNode();
            FixedNode next = previous.next();
            previous.setNext(null);
            DeoptimizeNode deopt = floatingNode.graph().add(new DeoptimizeNode(DeoptimizationAction.InvalidateReprofile, DeoptimizationReason.ArithmeticException));
            AbstractBeginNode normalBegin = floatingNode.graph().add(new BeginNode());
            normalBegin.setNext(next);
            IntegerExactArithmeticSplitNode split = node.createSplit(normalBegin, BeginNode.begin(deopt));
            previous.setNext(split);
            floatingNode.replaceAndDelete(split);
        }
    }
}
