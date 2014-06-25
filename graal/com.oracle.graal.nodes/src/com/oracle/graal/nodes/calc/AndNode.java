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

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.lir.gen.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

@NodeInfo(shortName = "&")
public final class AndNode extends BitLogicNode implements Canonicalizable, NarrowableArithmeticNode {

    public AndNode(Stamp stamp, ValueNode x, ValueNode y) {
        super(stamp, x, y);
    }

    @Override
    public boolean inferStamp() {
        return updateStamp(StampTool.and(getX().stamp(), getY().stamp()));
    }

    @Override
    public Constant evalConst(Constant... inputs) {
        assert inputs.length == 2;
        return Constant.forPrimitiveInt(PrimitiveStamp.getBits(stamp()), inputs[0].asLong() & inputs[1].asLong());
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (getX() == getY()) {
            return getX();
        }
        if (getX().isConstant() && !getY().isConstant()) {
            return graph().unique(new AndNode(stamp(), getY(), getX()));
        }
        if (getX().isConstant()) {
            return ConstantNode.forPrimitive(stamp(), evalConst(getX().asConstant(), getY().asConstant()), graph());
        } else if (getY().isConstant()) {
            long rawY = getY().asConstant().asLong();
            long mask = IntegerStamp.defaultMask(PrimitiveStamp.getBits(stamp()));
            if ((rawY & mask) == mask) {
                return getX();
            }
            if ((rawY & mask) == 0) {
                return ConstantNode.forIntegerStamp(stamp(), 0, graph());
            }
            if (getX() instanceof SignExtendNode) {
                SignExtendNode ext = (SignExtendNode) getX();
                if (rawY == ((1L << ext.getInputBits()) - 1)) {
                    ValueNode result = graph().unique(new ZeroExtendNode(ext.getValue(), ext.getResultBits()));
                    return result;
                }
            }
            if (getX().stamp() instanceof IntegerStamp) {
                IntegerStamp xStamp = (IntegerStamp) getX().stamp();
                if (((xStamp.upMask() | xStamp.downMask()) & ~rawY) == 0) {
                    // No bits are set which are outside the mask, so the mask will have no effect.
                    return getX();
                }
            }

            return BinaryNode.reassociate(this, ValueNode.isConstantPredicate());
        }
        return this;
    }

    @Override
    public void generate(NodeMappableLIRBuilder builder, ArithmeticLIRGenerator gen) {
        builder.setResult(this, gen.emitAnd(builder.operand(getX()), builder.operand(getY())));
    }
}
