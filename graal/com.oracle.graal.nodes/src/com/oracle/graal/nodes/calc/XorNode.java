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

import jdk.internal.jvmci.code.*;
import jdk.internal.jvmci.meta.*;

import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.compiler.common.type.ArithmeticOpTable.BinaryOp;
import com.oracle.graal.compiler.common.type.ArithmeticOpTable.BinaryOp.Xor;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.Canonicalizable.BinaryCommutative;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.lir.gen.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.util.*;

@NodeInfo(shortName = "^")
public final class XorNode extends BinaryArithmeticNode<Xor> implements BinaryCommutative<ValueNode>, NarrowableArithmeticNode {

    public static final NodeClass<XorNode> TYPE = NodeClass.create(XorNode.class);

    public XorNode(ValueNode x, ValueNode y) {
        super(TYPE, ArithmeticOpTable::getXor, x, y);
        assert x.stamp().isCompatible(y.stamp());
    }

    public static ValueNode create(ValueNode x, ValueNode y) {
        BinaryOp<Xor> op = ArithmeticOpTable.forStamp(x.stamp()).getXor();
        Stamp stamp = op.foldStamp(x.stamp(), y.stamp());
        ConstantNode tryConstantFold = tryConstantFold(op, x, y, stamp);
        if (tryConstantFold != null) {
            return tryConstantFold;
        } else {
            return new XorNode(x, y).maybeCommuteInputs();
        }
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forX, ValueNode forY) {
        ValueNode ret = super.canonical(tool, forX, forY);
        if (ret != this) {
            return ret;
        }

        if (GraphUtil.unproxify(forX) == GraphUtil.unproxify(forY)) {
            return ConstantNode.forPrimitive(stamp(), getOp(forX, forY).getZero(forX.stamp()));
        }
        if (forX.isConstant() && !forY.isConstant()) {
            return new XorNode(forY, forX);
        }
        if (forY.isConstant()) {
            Constant c = forY.asConstant();
            if (getOp(forX, forY).isNeutral(c)) {
                return forX;
            }

            if (c instanceof PrimitiveConstant && ((PrimitiveConstant) c).getKind().isNumericInteger()) {
                long rawY = ((PrimitiveConstant) c).asLong();
                long mask = CodeUtil.mask(PrimitiveStamp.getBits(stamp()));
                if ((rawY & mask) == mask) {
                    return new NotNode(forX);
                }
            }
            return reassociate(this, ValueNode.isConstantPredicate(), forX, forY);
        }
        return this;
    }

    @Override
    public void generate(NodeValueMap nodeValueMap, ArithmeticLIRGenerator gen) {
        nodeValueMap.setResult(this, gen.emitXor(nodeValueMap.operand(getX()), nodeValueMap.operand(getY())));
    }
}
