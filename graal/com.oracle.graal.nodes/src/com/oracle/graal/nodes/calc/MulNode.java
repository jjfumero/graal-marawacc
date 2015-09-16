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

import jdk.internal.jvmci.code.CodeUtil;
import jdk.internal.jvmci.meta.Constant;
import jdk.internal.jvmci.meta.PrimitiveConstant;
import jdk.internal.jvmci.meta.Value;

import com.oracle.graal.compiler.common.type.ArithmeticOpTable;
import com.oracle.graal.compiler.common.type.ArithmeticOpTable.BinaryOp;
import com.oracle.graal.compiler.common.type.ArithmeticOpTable.BinaryOp.Mul;
import com.oracle.graal.compiler.common.type.Stamp;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.graph.spi.Canonicalizable.BinaryCommutative;
import com.oracle.graal.graph.spi.CanonicalizerTool;
import com.oracle.graal.lir.gen.ArithmeticLIRGenerator;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.ConstantNode;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.spi.NodeValueMap;

@NodeInfo(shortName = "*")
public class MulNode extends BinaryArithmeticNode<Mul> implements NarrowableArithmeticNode, BinaryCommutative<ValueNode> {

    public static final NodeClass<MulNode> TYPE = NodeClass.create(MulNode.class);

    public MulNode(ValueNode x, ValueNode y) {
        this(TYPE, x, y);
    }

    protected MulNode(NodeClass<? extends MulNode> c, ValueNode x, ValueNode y) {
        super(c, ArithmeticOpTable::getMul, x, y);
    }

    public static ValueNode create(ValueNode x, ValueNode y) {
        BinaryOp<Mul> op = ArithmeticOpTable.forStamp(x.stamp()).getMul();
        Stamp stamp = op.foldStamp(x.stamp(), y.stamp());
        ConstantNode tryConstantFold = tryConstantFold(op, x, y, stamp);
        if (tryConstantFold != null) {
            return tryConstantFold;
        } else {
            return new MulNode(x, y).maybeCommuteInputs();
        }
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forX, ValueNode forY) {
        ValueNode ret = super.canonical(tool, forX, forY);
        if (ret != this) {
            return ret;
        }

        if (forX.isConstant() && !forY.isConstant()) {
            return new MulNode(forY, forX);
        }
        if (forY.isConstant()) {
            BinaryOp<Mul> op = getOp(forX, forY);
            Constant c = forY.asConstant();
            if (op.isNeutral(c)) {
                return forX;
            }

            if (c instanceof PrimitiveConstant && ((PrimitiveConstant) c).getJavaKind().isNumericInteger()) {
                long i = ((PrimitiveConstant) c).asLong();
                if (i > 0 && CodeUtil.isPowerOf2(i)) {
                    return new LeftShiftNode(forX, ConstantNode.forInt(CodeUtil.log2(i)));
                }
            }

            if (op.isAssociative()) {
                // canonicalize expressions like "(a * 1) * 2"
                return reassociate(this, ValueNode.isConstantPredicate(), forX, forY);
            }
        }
        return this;
    }

    @Override
    public void generate(NodeValueMap nodeValueMap, ArithmeticLIRGenerator gen) {
        Value op1 = nodeValueMap.operand(getX());
        Value op2 = nodeValueMap.operand(getY());
        if (!getY().isConstant() && !BinaryArithmeticNode.livesLonger(this, getY(), nodeValueMap)) {
            Value tmp = op1;
            op1 = op2;
            op2 = tmp;
        }
        nodeValueMap.setResult(this, gen.emitMul(op1, op2, false));
    }
}
