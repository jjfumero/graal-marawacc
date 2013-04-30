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
package com.oracle.graal.nodes.calc;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

@NodeInfo(shortName = "/")
public class IntegerDivNode extends FixedBinaryNode implements Canonicalizable, Lowerable, LIRLowerable {

    public IntegerDivNode(Kind kind, ValueNode x, ValueNode y) {
        super(kind, x, y);
    }

    @Override
    public boolean inferStamp() {
        return updateStamp(StampTool.div(x().integerStamp(), y().integerStamp()));
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        if (x().isConstant() && y().isConstant()) {
            long yConst = y().asConstant().asLong();
            if (yConst == 0) {
                return this; // this will trap, can not canonicalize
            }
            if (kind() == Kind.Int) {
                return ConstantNode.forInt(x().asConstant().asInt() / (int) yConst, graph());
            } else {
                assert kind() == Kind.Long;
                return ConstantNode.forLong(x().asConstant().asLong() / yConst, graph());
            }
        } else if (y().isConstant()) {
            long c = y().asConstant().asLong();
            if (c == 1) {
                return x();
            }
            if (c == -1) {
                return graph().unique(new NegateNode(x()));
            }
            long abs = Math.abs(c);
            if (CodeUtil.isPowerOf2(abs)) {
                ValueNode dividend = x();
                IntegerStamp stampX = x().integerStamp();
                int log2 = CodeUtil.log2(abs);
                // no rounding if dividend is positive or if its low bits are always 0
                if (stampX.canBeNegative() || (stampX.mask() & (abs - 1)) != 0) {
                    int bits;
                    if (kind().getStackKind() == Kind.Int) {
                        bits = 32;
                    } else {
                        assert kind() == Kind.Long;
                        bits = 64;
                    }
                    RightShiftNode sign = graph().unique(new RightShiftNode(kind(), x(), ConstantNode.forInt(bits - 1, graph())));
                    UnsignedRightShiftNode round = graph().unique(new UnsignedRightShiftNode(kind(), sign, ConstantNode.forInt(bits - log2, graph())));
                    dividend = IntegerArithmeticNode.add(dividend, round);
                }
                RightShiftNode shift = graph().unique(new RightShiftNode(kind(), dividend, ConstantNode.forInt(log2, graph())));
                if (c < 0) {
                    return graph().unique(new NegateNode(shift));
                }
                return shift;
            }
        }

        // Convert the expression ((a - a % b) / b) into (a / b).
        if (x() instanceof IntegerSubNode) {
            IntegerSubNode integerSubNode = (IntegerSubNode) x();
            if (integerSubNode.y() instanceof IntegerRemNode) {
                IntegerRemNode integerRemNode = (IntegerRemNode) integerSubNode.y();
                if (integerSubNode.kind() == this.kind() && integerRemNode.kind() == this.kind() && integerSubNode.x() == integerRemNode.x() && this.y() == integerRemNode.y()) {
                    return graph().add(new IntegerDivNode(kind(), integerSubNode.x(), this.y()));
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
    public void lower(LoweringTool tool, LoweringType loweringType) {
        tool.getRuntime().lower(this, tool);
    }

    @Override
    public void generate(LIRGeneratorTool gen) {
        gen.setResult(this, gen.emitDiv(gen.operand(x()), gen.operand(y()), this));
    }

    @Override
    public boolean canDeoptimize() {
        return y().integerStamp().contains(0);
    }
}
