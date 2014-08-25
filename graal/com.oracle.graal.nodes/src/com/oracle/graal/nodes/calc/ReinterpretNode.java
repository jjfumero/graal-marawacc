/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
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
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.lir.gen.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;

/**
 * The {@code ReinterpretNode} class represents a reinterpreting conversion that changes the stamp
 * of a primitive value to some other incompatible stamp. The new stamp must have the same width as
 * the old stamp.
 */
@NodeInfo
public class ReinterpretNode extends UnaryNode implements ArithmeticLIRLowerable {

    public static ReinterpretNode create(Kind to, ValueNode value) {
        return USE_GENERATED_NODES ? new ReinterpretNodeGen(to, value) : new ReinterpretNode(to, value);
    }

    ReinterpretNode(Kind to, ValueNode value) {
        this(StampFactory.forKind(to), value);
    }

    public static ReinterpretNode create(Stamp to, ValueNode value) {
        return USE_GENERATED_NODES ? new ReinterpretNodeGen(to, value) : new ReinterpretNode(to, value);
    }

    protected ReinterpretNode(Stamp to, ValueNode value) {
        super(to, value);
        assert to instanceof PrimitiveStamp;
    }

    public Constant evalConst(Constant... inputs) {
        assert inputs.length == 1;
        Constant c = inputs[0];
        assert c.getKind().getBitCount() == ((PrimitiveStamp) stamp()).getBits();
        switch (c.getKind()) {
            case Int:
                if (stamp() instanceof FloatStamp) {
                    return Constant.forFloat(Float.intBitsToFloat(c.asInt()));
                } else {
                    return c;
                }
            case Long:
                if (stamp() instanceof FloatStamp) {
                    return Constant.forDouble(Double.longBitsToDouble(c.asLong()));
                } else {
                    return c;
                }
            case Float:
                if (stamp() instanceof IntegerStamp) {
                    return Constant.forInt(Float.floatToRawIntBits(c.asFloat()));
                } else {
                    return c;
                }
            case Double:
                if (stamp() instanceof IntegerStamp) {
                    return Constant.forLong(Double.doubleToRawLongBits(c.asDouble()));
                } else {
                    return c;
                }
        }
        throw GraalInternalError.shouldNotReachHere();
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forValue) {
        if (forValue.isConstant()) {
            return ConstantNode.forConstant(evalConst(forValue.asConstant()), null);
        }
        if (stamp().isCompatible(forValue.stamp())) {
            return forValue;
        }
        if (forValue instanceof ReinterpretNode) {
            ReinterpretNode reinterpret = (ReinterpretNode) forValue;
            return ReinterpretNode.create(stamp(), reinterpret.getValue());
        }
        return this;
    }

    @Override
    public void generate(NodeMappableLIRBuilder builder, ArithmeticLIRGenerator gen) {
        LIRKind kind = gen.getLIRKind(stamp());
        builder.setResult(this, gen.emitReinterpret(kind, builder.operand(getValue())));
    }

    public static ValueNode reinterpret(Kind toKind, ValueNode value) {
        return value.graph().unique(ReinterpretNode.create(toKind, value));
    }

    @NodeIntrinsic
    public static native float reinterpret(@ConstantNodeParameter Kind kind, int value);

    @NodeIntrinsic
    public static native int reinterpret(@ConstantNodeParameter Kind kind, float value);

    @NodeIntrinsic
    public static native double reinterpret(@ConstantNodeParameter Kind kind, long value);

    @NodeIntrinsic
    public static native long reinterpret(@ConstantNodeParameter Kind kind, double value);
}
