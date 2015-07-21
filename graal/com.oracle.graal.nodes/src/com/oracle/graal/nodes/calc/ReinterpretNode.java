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
package com.oracle.graal.nodes.calc;

import java.nio.*;

import jdk.internal.jvmci.meta.*;

import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
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
public final class ReinterpretNode extends UnaryNode implements ArithmeticLIRLowerable {

    public static final NodeClass<ReinterpretNode> TYPE = NodeClass.create(ReinterpretNode.class);

    public ReinterpretNode(Kind to, ValueNode value) {
        this(StampFactory.forKind(to), value);
    }

    public ReinterpretNode(Stamp to, ValueNode value) {
        super(TYPE, to, value);
        assert to instanceof ArithmeticStamp;
    }

    private SerializableConstant evalConst(SerializableConstant c) {
        /*
         * We don't care about byte order here. Either would produce the correct result.
         */
        ByteBuffer buffer = ByteBuffer.wrap(new byte[c.getSerializedSize()]).order(ByteOrder.nativeOrder());
        c.serialize(buffer);

        buffer.rewind();
        SerializableConstant ret = ((ArithmeticStamp) stamp()).deserialize(buffer);

        assert !buffer.hasRemaining();
        return ret;
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forValue) {
        if (forValue.isConstant()) {
            return ConstantNode.forConstant(stamp(), evalConst((SerializableConstant) forValue.asConstant()), null);
        }
        if (stamp().isCompatible(forValue.stamp())) {
            return forValue;
        }
        if (forValue instanceof ReinterpretNode) {
            ReinterpretNode reinterpret = (ReinterpretNode) forValue;
            return new ReinterpretNode(stamp(), reinterpret.getValue());
        }
        return this;
    }

    @Override
    public void generate(NodeValueMap nodeValueMap, ArithmeticLIRGenerator gen) {
        LIRKind kind = gen.getLIRKind(stamp());
        nodeValueMap.setResult(this, gen.emitReinterpret(kind, nodeValueMap.operand(getValue())));
    }

    public static ValueNode reinterpret(Kind toKind, ValueNode value) {
        return value.graph().unique(new ReinterpretNode(toKind, value));
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
