/*
 * Copyright (c) 2012, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.replacements;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.spi.*;

/**
 * Count the number of trailing zeros.
 */
@NodeInfo
public class CountTrailingZerosNode extends UnaryNode implements LIRLowerable {

    public static CountTrailingZerosNode create(ValueNode value) {
        return new CountTrailingZerosNode(value);
    }

    protected CountTrailingZerosNode(ValueNode value) {
        super(StampFactory.forInteger(Kind.Int, 0, ((PrimitiveStamp) value.stamp()).getBits()), value);
        assert value.getKind() == Kind.Int || value.getKind() == Kind.Long;
    }

    @Override
    public boolean inferStamp() {
        IntegerStamp valueStamp = (IntegerStamp) getValue().stamp();
        long mask = CodeUtil.mask(valueStamp.getBits());
        int min = Long.numberOfTrailingZeros(valueStamp.upMask() & mask);
        int max = Long.numberOfTrailingZeros(valueStamp.downMask() & mask);
        return updateStamp(StampFactory.forInteger(Kind.Int, min, max));
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forValue) {
        if (forValue.isConstant()) {
            JavaConstant c = forValue.asJavaConstant();
            if (forValue.getKind() == Kind.Int) {
                return ConstantNode.forInt(Integer.numberOfTrailingZeros(c.asInt()));
            } else {
                return ConstantNode.forInt(Long.numberOfTrailingZeros(c.asLong()));
            }
        }
        return this;
    }

    /**
     * Raw intrinsic for tzcntq instruction.
     *
     * @param v
     * @return number of trailing zeros
     */
    @NodeIntrinsic
    public static native int count(long v);

    /**
     * Raw intrinsic for tzcntl instruction.
     *
     * @param v
     * @return number of trailing zeros
     */
    @NodeIntrinsic
    public static native int count(int v);

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        Value result = gen.getLIRGeneratorTool().emitCountTrailingZeros(gen.operand(getValue()));
        gen.setResult(this, result);
    }
}
