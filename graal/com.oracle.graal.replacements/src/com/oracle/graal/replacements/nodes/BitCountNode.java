/*
 * Copyright (c) 2012, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.replacements.nodes;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.spi.*;

public class BitCountNode extends UnaryNode implements LIRLowerable, Canonicalizable {

    public BitCountNode(ValueNode value) {
        super(StampFactory.forInteger(Kind.Int, 0, ((PrimitiveStamp) value.stamp()).getBits()), value);
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (getValue().isConstant()) {
            Constant c = getValue().asConstant();
            if (c.getKind() == Kind.Int) {
                return ConstantNode.forInt(Integer.bitCount(c.asInt()), graph());
            } else if (c.getKind() == Kind.Long) {
                return ConstantNode.forInt(Long.bitCount(c.asLong()), graph());
            }
        }
        return this;
    }

    @NodeIntrinsic
    public static int bitCount(int v) {
        return Integer.bitCount(v);
    }

    @NodeIntrinsic
    public static int bitCount(long v) {
        return Long.bitCount(v);
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        Value result = gen.getLIRGeneratorTool().emitBitCount(gen.operand(getValue()));
        gen.setResult(this, result);
    }
}
