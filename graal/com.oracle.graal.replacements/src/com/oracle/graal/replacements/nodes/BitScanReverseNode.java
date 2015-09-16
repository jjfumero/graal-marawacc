/*
 * Copyright (c) 2012, 2015, Oracle and/or its affiliates. All rights reserved.
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

import jdk.internal.jvmci.code.CodeUtil;
import jdk.internal.jvmci.meta.JavaConstant;
import jdk.internal.jvmci.meta.JavaKind;
import jdk.internal.jvmci.meta.Value;

import com.oracle.graal.compiler.common.type.IntegerStamp;
import com.oracle.graal.compiler.common.type.PrimitiveStamp;
import com.oracle.graal.compiler.common.type.StampFactory;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.graph.spi.CanonicalizerTool;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.ConstantNode;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.calc.UnaryNode;
import com.oracle.graal.nodes.spi.LIRLowerable;
import com.oracle.graal.nodes.spi.NodeLIRBuilderTool;

/**
 * Determines the index of the most significant "1" bit. Note that the result is undefined if the
 * input is zero.
 */
@NodeInfo
public final class BitScanReverseNode extends UnaryNode implements LIRLowerable {

    public static final NodeClass<BitScanReverseNode> TYPE = NodeClass.create(BitScanReverseNode.class);

    public BitScanReverseNode(ValueNode value) {
        super(TYPE, StampFactory.forInteger(JavaKind.Int, 0, ((PrimitiveStamp) value.stamp()).getBits()), value);
        assert value.getStackKind() == JavaKind.Int || value.getStackKind() == JavaKind.Long;
    }

    @Override
    public boolean inferStamp() {
        IntegerStamp valueStamp = (IntegerStamp) getValue().stamp();
        int min;
        int max;
        long mask = CodeUtil.mask(valueStamp.getBits());
        int lastAlwaysSetBit = scan(valueStamp.downMask() & mask);
        if (lastAlwaysSetBit == -1) {
            int firstMaybeSetBit = BitScanForwardNode.scan(valueStamp.upMask() & mask);
            min = firstMaybeSetBit;
        } else {
            min = lastAlwaysSetBit;
        }
        int lastMaybeSetBit = scan(valueStamp.upMask() & mask);
        max = lastMaybeSetBit;
        return updateStamp(StampFactory.forInteger(JavaKind.Int, min, max));
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forValue) {
        if (forValue.isConstant()) {
            JavaConstant c = forValue.asJavaConstant();
            if (c.asLong() != 0) {
                return ConstantNode.forInt(forValue.getStackKind() == JavaKind.Int ? scan(c.asInt()) : scan(c.asLong()));
            }
        }
        return this;
    }

    /**
     * Utility method with defined return value for 0.
     *
     * @param v
     * @return index of first set bit or -1 if {@code v} == 0.
     */
    public static int scan(long v) {
        return 63 - Long.numberOfLeadingZeros(v);
    }

    /**
     * Utility method with defined return value for 0.
     *
     * @param v
     * @return index of first set bit or -1 if {@code v} == 0.
     */
    public static int scan(int v) {
        return 31 - Integer.numberOfLeadingZeros(v);
    }

    /**
     * Raw intrinsic for bsr instruction.
     *
     * @param v
     * @return index of first set bit or an undefined value if {@code v} == 0.
     */
    @NodeIntrinsic
    public static native int unsafeScan(int v);

    /**
     * Raw intrinsic for bsr instruction.
     *
     * @param v
     * @return index of first set bit or an undefined value if {@code v} == 0.
     */
    @NodeIntrinsic
    public static native int unsafeScan(long v);

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        Value result = gen.getLIRGeneratorTool().emitBitScanReverse(gen.operand(getValue()));
        gen.setResult(this, result);
    }

}
