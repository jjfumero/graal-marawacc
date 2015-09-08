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
package com.oracle.graal.nodes.extended;

import jdk.internal.jvmci.meta.*;

import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.virtual.*;

@NodeInfo
public final class UnboxNode extends FixedWithNextNode implements Virtualizable, Lowerable, Canonicalizable.Unary<ValueNode> {

    public static final NodeClass<UnboxNode> TYPE = NodeClass.create(UnboxNode.class);
    @Input protected ValueNode value;
    protected final JavaKind boxingKind;

    public ValueNode getValue() {
        return value;
    }

    public UnboxNode(ValueNode value, JavaKind boxingKind) {
        super(TYPE, StampFactory.forKind(boxingKind.getStackKind()));
        this.value = value;
        this.boxingKind = boxingKind;
    }

    public static ValueNode create(MetaAccessProvider metaAccess, ConstantReflectionProvider constantReflection, ValueNode value, JavaKind boxingKind) {
        ValueNode synonym = findSynonym(metaAccess, constantReflection, value, boxingKind);
        if (synonym != null) {
            return synonym;
        }
        return new UnboxNode(value, boxingKind);
    }

    public JavaKind getBoxingKind() {
        return boxingKind;
    }

    @Override
    public void lower(LoweringTool tool) {
        tool.getLowerer().lower(this, tool);
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode alias = tool.getAlias(getValue());
        if (alias instanceof VirtualObjectNode) {
            VirtualObjectNode virtual = (VirtualObjectNode) alias;
            ResolvedJavaType objectType = virtual.type();
            ResolvedJavaType expectedType = tool.getMetaAccessProvider().lookupJavaType(boxingKind.toBoxedJavaClass());
            if (objectType.equals(expectedType)) {
                tool.replaceWithValue(tool.getEntry(virtual, 0));
            }
        }
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forValue) {
        if (tool.allUsagesAvailable() && hasNoUsages() && StampTool.isPointerNonNull(forValue)) {
            return null;
        }
        ValueNode synonym = findSynonym(tool.getMetaAccess(), tool.getConstantReflection(), forValue, boxingKind);
        if (synonym != null) {
            return synonym;
        }
        return this;
    }

    private static ValueNode findSynonym(MetaAccessProvider metaAccess, ConstantReflectionProvider constantReflection, ValueNode forValue, JavaKind boxingKind) {
        if (forValue.isConstant()) {
            JavaConstant constant = forValue.asJavaConstant();
            JavaConstant unboxed = constantReflection.unboxPrimitive(constant);
            if (unboxed != null && unboxed.getJavaKind() == boxingKind) {
                return ConstantNode.forConstant(unboxed, metaAccess);
            }
        } else if (forValue instanceof BoxNode) {
            BoxNode box = (BoxNode) forValue;
            if (boxingKind == box.getBoxingKind()) {
                return box.getValue();
            }
        }
        return null;
    }
}
