/*
 * Copyright (c) 2011, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.calc;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;

/* TODO (thomaswue/gdub) For high-level optimization purpose the compare node should be a boolean *value* (it is currently only a helper node)
 * But in the back-end the comparison should not always be materialized (for example in x86 the comparison result will not be in a register but in a flag)
 *
 * Compare should probably be made a value (so that it can be canonicalized for example) and in later stages some Compare usage should be transformed
 * into variants that do not materialize the value (CompareIf, CompareGuard...)
 */
public abstract class CompareNode extends LogicNode implements Canonicalizable, LIRLowerable {

    @Input private ValueNode x;
    @Input private ValueNode y;

    public ValueNode x() {
        return x;
    }

    public ValueNode y() {
        return y;
    }

    /**
     * Constructs a new Compare instruction.
     * 
     * @param x the instruction producing the first input to the instruction
     * @param y the instruction that produces the second input to this instruction
     */
    public CompareNode(ValueNode x, ValueNode y) {
        assert (x == null && y == null) || x.kind() == y.kind();
        this.x = x;
        this.y = y;
    }

    /**
     * Gets the condition (comparison operation) for this instruction.
     * 
     * @return the condition
     */
    public abstract Condition condition();

    /**
     * Checks whether unordered inputs mean true or false (only applies to float operations).
     * 
     * @return {@code true} if unordered inputs produce true
     */
    public abstract boolean unorderedIsTrue();

    @Override
    public void generate(LIRGeneratorTool gen) {
    }

    private LogicNode optimizeConditional(Constant constant, ConditionalNode conditionalNode, ConstantReflectionProvider constantReflection, Condition cond) {
        Constant trueConstant = conditionalNode.trueValue().asConstant();
        Constant falseConstant = conditionalNode.falseValue().asConstant();

        if (falseConstant != null && trueConstant != null && constantReflection != null) {
            boolean trueResult = cond.foldCondition(trueConstant, constant, constantReflection, unorderedIsTrue());
            boolean falseResult = cond.foldCondition(falseConstant, constant, constantReflection, unorderedIsTrue());

            if (trueResult == falseResult) {
                return LogicConstantNode.forBoolean(trueResult, graph());
            } else {
                if (trueResult) {
                    assert falseResult == false;
                    return conditionalNode.condition();
                } else {
                    assert falseResult == true;
                    return graph().unique(new LogicNegationNode(conditionalNode.condition()));

                }
            }
        }
        return this;
    }

    protected void setX(ValueNode x) {
        updateUsages(this.x, x);
        this.x = x;
    }

    protected void setY(ValueNode y) {
        updateUsages(this.y, y);
        this.y = y;
    }

    protected LogicNode optimizeNormalizeCmp(Constant constant, NormalizeCompareNode normalizeNode, boolean mirrored) {
        throw new GraalInternalError("NormalizeCompareNode connected to %s (%s %s %s)", this, constant, normalizeNode, mirrored);
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (x().isConstant() && y().isConstant() && tool.getMetaAccess() != null) {
            return LogicConstantNode.forBoolean(condition().foldCondition(x().asConstant(), y().asConstant(), tool.getConstantReflection(), unorderedIsTrue()), graph());
        }
        if (x().isConstant()) {
            if (y() instanceof ConditionalNode) {
                return optimizeConditional(x().asConstant(), (ConditionalNode) y(), tool.getConstantReflection(), condition().mirror());
            } else if (y() instanceof NormalizeCompareNode) {
                return optimizeNormalizeCmp(x().asConstant(), (NormalizeCompareNode) y(), true);
            }
        } else if (y().isConstant()) {
            if (x() instanceof ConditionalNode) {
                return optimizeConditional(y().asConstant(), (ConditionalNode) x(), tool.getConstantReflection(), condition());
            } else if (x() instanceof NormalizeCompareNode) {
                return optimizeNormalizeCmp(y().asConstant(), (NormalizeCompareNode) x(), false);
            }
        }
        if (x() instanceof ConvertNode && y() instanceof ConvertNode) {
            ConvertNode convertX = (ConvertNode) x();
            ConvertNode convertY = (ConvertNode) y();
            if (convertX.opcode.isLossless() && convertY.opcode.isLossless()) {
                setX(convertX.value());
                setY(convertY.value());
            }
        }
        return this;
    }

    public static CompareNode createCompareNode(StructuredGraph graph, Condition condition, ValueNode x, ValueNode y) {
        assert x.kind() == y.kind();
        assert condition.isCanonical() : "condition is not canonical: " + condition;
        assert x.kind() != Kind.Double && x.kind() != Kind.Float;

        CompareNode comparison;
        if (condition == Condition.EQ) {
            if (x.kind() == Kind.Object) {
                comparison = new ObjectEqualsNode(x, y);
            } else {
                assert x.kind().getStackKind() == Kind.Int || x.kind() == Kind.Long;
                comparison = new IntegerEqualsNode(x, y);
            }
        } else if (condition == Condition.LT) {
            assert x.kind().getStackKind() == Kind.Int || x.kind() == Kind.Long;
            comparison = new IntegerLessThanNode(x, y);
        } else {
            assert condition == Condition.BT;
            assert x.kind().getStackKind() == Kind.Int || x.kind() == Kind.Long;
            comparison = new IntegerBelowThanNode(x, y);
        }

        return graph.unique(comparison);
    }
}
