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
package com.oracle.graal.nodes.calc;

import com.oracle.graal.api.code.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

@NodeInfo(shortName = "/")
public class IntegerDivNode extends FixedBinaryNode implements Canonicalizable, Lowerable, LIRLowerable {

    public IntegerDivNode(ValueNode x, ValueNode y) {
        super(x.stamp().unrestricted(), x, y);
    }

    @Override
    public boolean inferStamp() {
        return updateStamp(StampTool.div(getX().stamp(), getY().stamp()));
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (getX().isConstant() && getY().isConstant()) {
            long y = getY().asConstant().asLong();
            if (y == 0) {
                return this; // this will trap, can not canonicalize
            }
            return ConstantNode.forIntegerStamp(stamp(), getX().asConstant().asLong() / y, graph());
        } else if (getY().isConstant()) {
            long c = getY().asConstant().asLong();
            if (c == 1) {
                return getX();
            }
            if (c == -1) {
                return graph().unique(new NegateNode(getX()));
            }
            long abs = Math.abs(c);
            if (CodeUtil.isPowerOf2(abs) && getX().stamp() instanceof IntegerStamp) {
                ValueNode dividend = getX();
                IntegerStamp stampX = (IntegerStamp) getX().stamp();
                int log2 = CodeUtil.log2(abs);
                // no rounding if dividend is positive or if its low bits are always 0
                if (stampX.canBeNegative() || (stampX.upMask() & (abs - 1)) != 0) {
                    int bits = PrimitiveStamp.getBits(stamp());
                    RightShiftNode sign = new RightShiftNode(getX(), ConstantNode.forInt(bits - 1));
                    UnsignedRightShiftNode round = new UnsignedRightShiftNode(sign, ConstantNode.forInt(bits - log2));
                    dividend = IntegerArithmeticNode.add(dividend, round);
                }
                RightShiftNode shift = new RightShiftNode(dividend, ConstantNode.forInt(log2));
                if (c < 0) {
                    return new NegateNode(shift);
                }
                return shift;
            }
        }

        // Convert the expression ((a - a % b) / b) into (a / b).
        if (getX() instanceof IntegerSubNode) {
            IntegerSubNode integerSubNode = (IntegerSubNode) getX();
            if (integerSubNode.getY() instanceof IntegerRemNode) {
                IntegerRemNode integerRemNode = (IntegerRemNode) integerSubNode.getY();
                if (integerSubNode.stamp().isCompatible(this.stamp()) && integerRemNode.stamp().isCompatible(this.stamp()) && integerSubNode.getX() == integerRemNode.getX() &&
                                this.getY() == integerRemNode.getY()) {
                    return graph().add(new IntegerDivNode(integerSubNode.getX(), this.getY()));
                }
            }
        }

        if (next() instanceof IntegerDivNode) {
            NodeClass nodeClass = NodeClass.get(this.getClass());
            if (next().getClass() == this.getClass() && nodeClass.inputsEqual(this, next()) && nodeClass.valueEqual(this, next())) {
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
