/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

import java.io.*;
import java.util.function.*;

import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.compiler.common.type.ArithmeticOpTable.*;
import com.oracle.graal.compiler.common.type.ArithmeticOpTable.IntegerConvertOp.Narrow;
import com.oracle.graal.compiler.common.type.ArithmeticOpTable.IntegerConvertOp.SignExtend;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.lir.gen.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;

/**
 * The {@code NarrowNode} converts an integer to a narrower integer.
 */
@NodeInfo
public class NarrowNode extends IntegerConvertNode<Narrow, SignExtend> {

    public static NarrowNode create(ValueNode input, int resultBits) {
        int inputBits = PrimitiveStamp.getBits(input.stamp());
        assert 0 < resultBits && resultBits <= inputBits;
        return create(input, inputBits, resultBits);
    }

    public static NarrowNode create(ValueNode input, int inputBits, int resultBits) {
        return new NarrowNode(input, inputBits, resultBits);
    }

    protected NarrowNode(ValueNode input, int inputBits, int resultBits) {
        super((Function<ArithmeticOpTable, IntegerConvertOp<Narrow>> & Serializable) ArithmeticOpTable::getNarrow,
                        (Function<ArithmeticOpTable, IntegerConvertOp<SignExtend>> & Serializable) ArithmeticOpTable::getSignExtend, inputBits, resultBits, input);
    }

    @Override
    public boolean isLossless() {
        return false;
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forValue) {
        ValueNode ret = super.canonical(tool, forValue);
        if (ret != this) {
            return ret;
        }

        if (forValue instanceof NarrowNode) {
            // zzzzzzzz yyyyxxxx -(narrow)-> yyyyxxxx -(narrow)-> xxxx
            // ==> zzzzzzzz yyyyxxxx -(narrow)-> xxxx
            NarrowNode other = (NarrowNode) forValue;
            return NarrowNode.create(other.getValue(), other.getInputBits(), getResultBits());
        } else if (forValue instanceof IntegerConvertNode) {
            // SignExtendNode or ZeroExtendNode
            IntegerConvertNode<?, ?> other = (IntegerConvertNode<?, ?>) forValue;
            if (getResultBits() == other.getInputBits()) {
                // xxxx -(extend)-> yyyy xxxx -(narrow)-> xxxx
                // ==> no-op
                return other.getValue();
            } else if (getResultBits() < other.getInputBits()) {
                // yyyyxxxx -(extend)-> zzzzzzzz yyyyxxxx -(narrow)-> xxxx
                // ==> yyyyxxxx -(narrow)-> xxxx
                return NarrowNode.create(other.getValue(), other.getInputBits(), getResultBits());
            } else {
                if (other instanceof SignExtendNode) {
                    // sxxx -(sign-extend)-> ssssssss sssssxxx -(narrow)-> sssssxxx
                    // ==> sxxx -(sign-extend)-> sssssxxx
                    return SignExtendNode.create(other.getValue(), other.getInputBits(), getResultBits());
                } else if (other instanceof ZeroExtendNode) {
                    // xxxx -(zero-extend)-> 00000000 00000xxx -(narrow)-> 0000xxxx
                    // ==> xxxx -(zero-extend)-> 0000xxxx
                    return ZeroExtendNode.create(other.getValue(), other.getInputBits(), getResultBits());
                }
            }
        }
        return this;
    }

    @Override
    public void generate(NodeMappableLIRBuilder builder, ArithmeticLIRGenerator gen) {
        builder.setResult(this, gen.emitNarrow(builder.operand(getValue()), getResultBits()));
    }
}
