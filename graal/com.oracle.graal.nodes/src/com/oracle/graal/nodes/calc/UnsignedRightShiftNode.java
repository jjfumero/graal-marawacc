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

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.lir.gen.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

@NodeInfo(shortName = ">>>")
public class UnsignedRightShiftNode extends ShiftNode {

    public static UnsignedRightShiftNode create(ValueNode x, ValueNode y) {
        return new UnsignedRightShiftNode(x, y);
    }

    protected UnsignedRightShiftNode(ValueNode x, ValueNode y) {
        super(x, y);
    }

    @Override
    public boolean inferStamp() {
        return updateStamp(StampTool.unsignedRightShift(getX().stamp(), getY().stamp()));
    }

    private static JavaConstant evalConst(JavaConstant a, JavaConstant b) {
        if (a.getKind() == Kind.Int) {
            return JavaConstant.forInt(a.asInt() >>> b.asInt());
        } else {
            assert a.getKind() == Kind.Long;
            return JavaConstant.forLong(a.asLong() >>> b.asLong());
        }
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forX, ValueNode forY) {
        if (forX.isConstant() && forY.isConstant()) {
            return ConstantNode.forPrimitive(evalConst(forX.asJavaConstant(), forY.asJavaConstant()));
        } else if (forY.isConstant()) {
            int amount = forY.asJavaConstant().asInt();
            int originalAmout = amount;
            int mask = getShiftAmountMask();
            amount &= mask;
            if (amount == 0) {
                return forX;
            }
            if (forX instanceof ShiftNode) {
                ShiftNode other = (ShiftNode) forX;
                if (other.getY().isConstant()) {
                    int otherAmount = other.getY().asJavaConstant().asInt() & mask;
                    if (other instanceof UnsignedRightShiftNode) {
                        int total = amount + otherAmount;
                        if (total != (total & mask)) {
                            return ConstantNode.forIntegerKind(getKind(), 0);
                        }
                        return UnsignedRightShiftNode.create(other.getX(), ConstantNode.forInt(total));
                    } else if (other instanceof LeftShiftNode && otherAmount == amount) {
                        if (getKind() == Kind.Long) {
                            return AndNode.create(other.getX(), ConstantNode.forLong(-1L >>> amount));
                        } else {
                            assert getKind() == Kind.Int;
                            return AndNode.create(other.getX(), ConstantNode.forInt(-1 >>> amount));
                        }
                    }
                }
            }
            if (originalAmout != amount) {
                return UnsignedRightShiftNode.create(forX, ConstantNode.forInt(amount));
            }
        }
        return this;
    }

    @Override
    public void generate(NodeMappableLIRBuilder builder, ArithmeticLIRGenerator gen) {
        builder.setResult(this, gen.emitUShr(builder.operand(getX()), builder.operand(getY())));
    }
}
