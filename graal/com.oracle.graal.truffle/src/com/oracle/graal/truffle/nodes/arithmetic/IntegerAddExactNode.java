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
package com.oracle.graal.truffle.nodes.arithmetic;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.truffle.api.*;

/**
 * Node representing an exact integer addition that will throw an {@link ArithmeticException} in
 * case the addition would overflow the 32 bit range.
 */
@NodeInfo
public final class IntegerAddExactNode extends AddNode implements IntegerExactArithmeticNode {
    public static final NodeClass<IntegerAddExactNode> TYPE = NodeClass.create(IntegerAddExactNode.class);

    public IntegerAddExactNode(ValueNode x, ValueNode y) {
        super(TYPE, x, y);
        assert x.stamp().isCompatible(y.stamp()) && x.stamp() instanceof IntegerStamp;
    }

    @Override
    public boolean inferStamp() {
        // TODO Should probably use a specialized version which understands that it can't overflow
        return super.inferStamp();
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forX, ValueNode forY) {
        ValueNode result = findSynonym(forX, forY);
        if (result == null) {
            return this;
        } else {
            return result;
        }
    }

    private static ValueNode findSynonym(ValueNode forX, ValueNode forY) {
        if (forX.isConstant() && !forY.isConstant()) {
            return new IntegerAddExactNode(forY, forX);
        }
        if (forX.isConstant()) {
            ConstantNode constantNode = canonicalXconstant(forX, forY);
            if (constantNode != null) {
                return constantNode;
            }
        } else if (forY.isConstant()) {
            long c = forY.asJavaConstant().asLong();
            if (c == 0) {
                return forX;
            }
        }
        return null;
    }

    private static ConstantNode canonicalXconstant(ValueNode forX, ValueNode forY) {
        JavaConstant xConst = forX.asJavaConstant();
        JavaConstant yConst = forY.asJavaConstant();
        if (xConst != null && yConst != null) {
            assert xConst.getKind() == yConst.getKind();
            try {
                if (xConst.getKind() == Kind.Int) {
                    return ConstantNode.forInt(ExactMath.addExact(xConst.asInt(), yConst.asInt()));
                } else {
                    assert xConst.getKind() == Kind.Long;
                    return ConstantNode.forLong(ExactMath.addExact(xConst.asLong(), yConst.asLong()));
                }
            } catch (ArithmeticException ex) {
                // The operation will result in an overflow exception, so do not canonicalize.
            }
        }
        return null;
    }

    @Override
    public IntegerExactArithmeticSplitNode createSplit(AbstractBeginNode next, AbstractBeginNode deopt) {
        return graph().add(new IntegerAddExactSplitNode(stamp(), getX(), getY(), next, deopt));
    }

    @Override
    public void lower(LoweringTool tool) {
        IntegerExactArithmeticSplitNode.lower(tool, this);
    }
}
