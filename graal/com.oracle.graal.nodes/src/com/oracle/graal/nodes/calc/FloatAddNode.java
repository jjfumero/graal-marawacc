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
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

@NodeInfo(shortName = "+")
public final class FloatAddNode extends FloatArithmeticNode implements Canonicalizable {

    public FloatAddNode(Stamp stamp, ValueNode x, ValueNode y, boolean isStrictFP) {
        super(stamp, x, y, isStrictFP);
    }

    public Constant evalConst(Constant... inputs) {
        assert inputs.length == 2;
        assert inputs[0].getKind() == inputs[1].getKind();
        if (inputs[0].getKind() == Kind.Float) {
            return Constant.forFloat(inputs[0].asFloat() + inputs[1].asFloat());
        } else {
            assert inputs[0].getKind() == Kind.Double;
            return Constant.forDouble(inputs[0].asDouble() + inputs[1].asDouble());
        }
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (x().isConstant() && !y().isConstant()) {
            return graph().unique(new FloatAddNode(stamp(), y(), x(), isStrictFP()));
        }
        if (x().isConstant()) {
            return ConstantNode.forPrimitive(evalConst(x().asConstant(), y().asConstant()), graph());
        } else if (y().isConstant()) {
            Constant c = y().asConstant();
            if ((c.getKind() == Kind.Float && c.asFloat() == 0.0f) || (c.getKind() == Kind.Double && c.asDouble() == 0.0)) {
                return x();
            }
        }
        return this;
    }

    @Override
    public void generate(NodeMappableArithmeticLIRGenerator gen) {
        Value op1 = gen.operand(x());
        Value op2 = gen.operand(y());
        if (!y().isConstant() && !livesLonger(this, y(), gen)) {
            Value op = op1;
            op1 = op2;
            op2 = op;
        }
        gen.setResult(this, gen.emitAdd(op1, op2));
    }

    public static boolean livesLonger(ValueNode after, ValueNode value, NodeMappableArithmeticLIRGenerator gen) {
        for (Node usage : value.usages()) {
            if (usage != after && usage instanceof ValueNode && gen.operand(((ValueNode) usage)) != null) {
                return true;
            }
        }
        return false;
    }
}
