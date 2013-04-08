/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.extended;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.Node.IterableNodeType;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.virtual.*;

public class BoxNode extends FixedWithNextNode implements VirtualizableAllocation, IterableNodeType, Lowerable, Canonicalizable {

    @Input private ValueNode value;
    private final Kind boxingKind;

    public BoxNode(Invoke invoke) {
        this(invoke.methodCallTarget().arguments().get(0), invoke.node().objectStamp().type(), invoke.methodCallTarget().targetMethod().getSignature().getParameterKind(0));
    }

    public BoxNode(ValueNode value, ResolvedJavaType resultType, Kind boxingKind) {
        super(StampFactory.exactNonNull(resultType));
        this.value = value;
        this.boxingKind = boxingKind;
    }

    public Kind getBoxingKind() {
        return boxingKind;
    }

    public ValueNode getValue() {
        return value;
    }

    @Override
    public void lower(LoweringTool tool) {
        tool.getRuntime().lower(this, tool);
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        if (value.isConstant()) {
            Constant sourceConstant = value.asConstant();
            switch (boxingKind) {
                case Boolean:
                    return ConstantNode.forObject(Boolean.valueOf(sourceConstant.asBoolean()), tool.runtime(), graph());
                case Byte:
                    return ConstantNode.forObject(Byte.valueOf((byte) sourceConstant.asInt()), tool.runtime(), graph());
                case Char:
                    return ConstantNode.forObject(Character.valueOf((char) sourceConstant.asInt()), tool.runtime(), graph());
                case Short:
                    return ConstantNode.forObject(Short.valueOf((short) sourceConstant.asInt()), tool.runtime(), graph());
                case Int:
                    return ConstantNode.forObject(Integer.valueOf(sourceConstant.asInt()), tool.runtime(), graph());
                case Long:
                    return ConstantNode.forObject(Long.valueOf(sourceConstant.asLong()), tool.runtime(), graph());
                case Float:
                    return ConstantNode.forObject(Float.valueOf(sourceConstant.asFloat()), tool.runtime(), graph());
                case Double:
                    return ConstantNode.forObject(Double.valueOf(sourceConstant.asDouble()), tool.runtime(), graph());
                default:
                    assert false : "Unexpected source kind for boxing";
                    break;

            }
        }
        if (usages().isEmpty()) {
            return null;
        }
        return this;
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode v = tool.getReplacedValue(getValue());
        ResolvedJavaType type = objectStamp().type();

        VirtualBoxingNode newVirtual = new VirtualBoxingNode(type, boxingKind);
        assert newVirtual.getFields().length == 1;

        tool.createVirtualObject(newVirtual, new ValueNode[]{v}, 0);
        tool.replaceWithVirtual(newVirtual);
    }

    /*
     * Normally, all these variants wouldn't be needed because this can be accomplished by using a
     * generic method with automatic unboxing. These intrinsics, however, are themselves used for
     * recognizing boxings, which means that there would be a circularity issue.
     */

    @NodeIntrinsic
    public static native Boolean box(boolean value, @ConstantNodeParameter Class<?> clazz, @ConstantNodeParameter Kind kind);

    @NodeIntrinsic
    public static native Byte box(byte value, @ConstantNodeParameter Class<?> clazz, @ConstantNodeParameter Kind kind);

    @NodeIntrinsic
    public static native Character box(char value, @ConstantNodeParameter Class<?> clazz, @ConstantNodeParameter Kind kind);

    @NodeIntrinsic
    public static native Double box(double value, @ConstantNodeParameter Class<?> clazz, @ConstantNodeParameter Kind kind);

    @NodeIntrinsic
    public static native Float box(float value, @ConstantNodeParameter Class<?> clazz, @ConstantNodeParameter Kind kind);

    @NodeIntrinsic
    public static native Integer box(int value, @ConstantNodeParameter Class<?> clazz, @ConstantNodeParameter Kind kind);

    @NodeIntrinsic
    public static native Long box(long value, @ConstantNodeParameter Class<?> clazz, @ConstantNodeParameter Kind kind);

    @NodeIntrinsic
    public static native Short box(short value, @ConstantNodeParameter Class<?> clazz, @ConstantNodeParameter Kind kind);
}
