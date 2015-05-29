/*
 * Copyright (c) 2011, 2015, Oracle and/or its affiliates. All rights reserved.
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

import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.jvmci.code.*;

@NodeInfo(shortName = "/")
public class IntegerDivNode extends FixedBinaryNode implements Lowerable, LIRLowerable {
    public static final NodeClass<IntegerDivNode> TYPE = NodeClass.create(IntegerDivNode.class);

    public IntegerDivNode(ValueNode x, ValueNode y) {
        this(TYPE, x, y);
    }

    protected IntegerDivNode(NodeClass<? extends IntegerDivNode> c, ValueNode x, ValueNode y) {
        super(c, IntegerStamp.OPS.getDiv().foldStamp(x.stamp(), y.stamp()), x, y);
    }

    @Override
    public boolean inferStamp() {
        return updateStamp(IntegerStamp.OPS.getDiv().foldStamp(getX().stamp(), getY().stamp()));
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forX, ValueNode forY) {
        if (forX.isConstant() && forY.isConstant()) {
            @SuppressWarnings("hiding")
            long y = forY.asJavaConstant().asLong();
            if (y == 0) {
                return this; // this will trap, can not canonicalize
            }
            return ConstantNode.forIntegerStamp(stamp(), forX.asJavaConstant().asLong() / y);
        } else if (forY.isConstant()) {
            long c = forY.asJavaConstant().asLong();
            if (c == 1) {
                return forX;
            }
            if (c == -1) {
                return new NegateNode(forX);
            }
            long abs = Math.abs(c);
            if (CodeUtil.isPowerOf2(abs) && forX.stamp() instanceof IntegerStamp) {
                ValueNode dividend = forX;
                IntegerStamp stampX = (IntegerStamp) forX.stamp();
                int log2 = CodeUtil.log2(abs);
                // no rounding if dividend is positive or if its low bits are always 0
                if (stampX.canBeNegative() || (stampX.upMask() & (abs - 1)) != 0) {
                    int bits = PrimitiveStamp.getBits(stamp());
                    RightShiftNode sign = new RightShiftNode(forX, ConstantNode.forInt(bits - 1));
                    UnsignedRightShiftNode round = new UnsignedRightShiftNode(sign, ConstantNode.forInt(bits - log2));
                    dividend = BinaryArithmeticNode.add(dividend, round);
                }
                RightShiftNode shift = new RightShiftNode(dividend, ConstantNode.forInt(log2));
                if (c < 0) {
                    return new NegateNode(shift);
                }
                return shift;
            }
        }

        // Convert the expression ((a - a % b) / b) into (a / b).
        if (forX instanceof SubNode) {
            SubNode integerSubNode = (SubNode) forX;
            if (integerSubNode.getY() instanceof IntegerRemNode) {
                IntegerRemNode integerRemNode = (IntegerRemNode) integerSubNode.getY();
                if (integerSubNode.stamp().isCompatible(this.stamp()) && integerRemNode.stamp().isCompatible(this.stamp()) && integerSubNode.getX() == integerRemNode.getX() &&
                                forY == integerRemNode.getY()) {
                    return new IntegerDivNode(integerSubNode.getX(), forY);
                }
            }
        }

        if (next() instanceof IntegerDivNode) {
            NodeClass<?> nodeClass = getNodeClass();
            if (next().getClass() == this.getClass() && nodeClass.getInputEdges().areEqualIn(this, next()) && valueEquals(next())) {
                return next();
            }
        }

        return this;
    }

    @Override
    public void lower(LoweringTool tool) {
        tool.getLowerer().lower(this, tool);
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        gen.setResult(this, gen.getLIRGeneratorTool().emitDiv(gen.operand(getX()), gen.operand(getY()), gen.state(this)));
    }

    @Override
    public boolean canDeoptimize() {
        return !(getY().stamp() instanceof IntegerStamp) || ((IntegerStamp) getY().stamp()).contains(0);
    }
}
