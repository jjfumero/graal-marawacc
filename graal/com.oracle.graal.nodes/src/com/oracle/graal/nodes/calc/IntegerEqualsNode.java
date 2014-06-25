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
import com.oracle.graal.api.meta.ProfilingInfo.TriState;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.compiler.common.calc.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.util.*;

@NodeInfo(shortName = "==")
public final class IntegerEqualsNode extends CompareNode {

    /**
     * Constructs a new integer equality comparison node.
     *
     * @param x the instruction producing the first input to the instruction
     * @param y the instruction that produces the second input to this instruction
     */
    public IntegerEqualsNode(ValueNode x, ValueNode y) {
        super(x, y);
        assert !x.getKind().isNumericFloat() && x.getKind() != Kind.Object;
        assert !y.getKind().isNumericFloat() && y.getKind() != Kind.Object;
    }

    @Override
    public Condition condition() {
        return Condition.EQ;
    }

    @Override
    public boolean unorderedIsTrue() {
        return false;
    }

    @Override
    protected LogicNode optimizeNormalizeCmp(Constant constant, NormalizeCompareNode normalizeNode, boolean mirrored) {
        if (constant.getKind() == Kind.Int && constant.asInt() == 0) {
            ValueNode a = mirrored ? normalizeNode.getY() : normalizeNode.getX();
            ValueNode b = mirrored ? normalizeNode.getX() : normalizeNode.getY();

            if (normalizeNode.getX().getKind() == Kind.Double || normalizeNode.getX().getKind() == Kind.Float) {
                return graph().unique(new FloatEqualsNode(a, b));
            } else {
                return graph().unique(new IntegerEqualsNode(a, b));
            }
        }
        return this;
    }

    @Override
    protected CompareNode duplicateModified(ValueNode newX, ValueNode newY) {
        if (newX.stamp() instanceof FloatStamp && newY.stamp() instanceof FloatStamp) {
            return new FloatEqualsNode(newX, newY);
        } else if (newX.stamp() instanceof IntegerStamp && newY.stamp() instanceof IntegerStamp) {
            return new IntegerEqualsNode(newX, newY);
        }
        throw GraalInternalError.shouldNotReachHere();
    }

    @Override
    public TriState evaluate(ConstantReflectionProvider constantReflection, ValueNode forX, ValueNode forY) {
        if (GraphUtil.unproxify(forX) == GraphUtil.unproxify(forY)) {
            return TriState.TRUE;
        } else if (forX.stamp().alwaysDistinct(forY.stamp())) {
            return TriState.FALSE;
        }
        return super.evaluate(constantReflection, forX, forY);
    }

    @Override
    protected Node canonicalizeSymmetricConstant(CanonicalizerTool tool, Constant constant, ValueNode nonConstant, boolean mirrored) {
        if (constant.asLong() == 0) {
            if (nonConstant instanceof AndNode) {
                AndNode andNode = (AndNode) nonConstant;
                return graph().unique(new IntegerTestNode(andNode.getX(), andNode.getY()));
            } else if (nonConstant instanceof ShiftNode) {
                if (nonConstant instanceof LeftShiftNode) {
                    LeftShiftNode shift = (LeftShiftNode) nonConstant;
                    if (shift.getY().isConstant()) {
                        int mask = shift.getShiftAmountMask();
                        int amount = shift.getY().asConstant().asInt() & mask;
                        if (shift.getX().getKind() == Kind.Int) {
                            return graph().unique(new IntegerTestNode(shift.getX(), ConstantNode.forInt(-1 >>> amount, graph())));
                        } else {
                            assert shift.getX().getKind() == Kind.Long;
                            return graph().unique(new IntegerTestNode(shift.getX(), ConstantNode.forLong(-1L >>> amount, graph())));
                        }
                    }
                } else if (nonConstant instanceof RightShiftNode) {
                    RightShiftNode shift = (RightShiftNode) nonConstant;
                    if (shift.getY().isConstant() && ((IntegerStamp) shift.getX().stamp()).isPositive()) {
                        int mask = shift.getShiftAmountMask();
                        int amount = shift.getY().asConstant().asInt() & mask;
                        if (shift.getX().getKind() == Kind.Int) {
                            return graph().unique(new IntegerTestNode(shift.getX(), ConstantNode.forInt(-1 << amount, graph())));
                        } else {
                            assert shift.getX().getKind() == Kind.Long;
                            return graph().unique(new IntegerTestNode(shift.getX(), ConstantNode.forLong(-1L << amount, graph())));
                        }
                    }
                } else if (nonConstant instanceof UnsignedRightShiftNode) {
                    UnsignedRightShiftNode shift = (UnsignedRightShiftNode) nonConstant;
                    if (shift.getY().isConstant()) {
                        int mask = shift.getShiftAmountMask();
                        int amount = shift.getY().asConstant().asInt() & mask;
                        if (shift.getX().getKind() == Kind.Int) {
                            return graph().unique(new IntegerTestNode(shift.getX(), ConstantNode.forInt(-1 << amount, graph())));
                        } else {
                            assert shift.getX().getKind() == Kind.Long;
                            return graph().unique(new IntegerTestNode(shift.getX(), ConstantNode.forLong(-1L << amount, graph())));
                        }
                    }
                }
            }
        }
        return super.canonicalizeSymmetricConstant(tool, constant, nonConstant, mirrored);
    }
}
