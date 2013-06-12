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
package com.oracle.graal.nodes.java;

import java.lang.reflect.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

/**
 * The {@code DynamicNewArrayNode} is used for allocation of arrays when the type is not a
 * compile-time constant.
 */
public class DynamicNewArrayNode extends FixedWithNextNode implements Canonicalizable, Lowerable, ArrayLengthProvider {

    @Input private ValueNode elementType;
    @Input private ValueNode length;
    private final boolean fillContents;

    public DynamicNewArrayNode(ValueNode elementType, ValueNode length) {
        this(elementType, length, true);
    }

    public DynamicNewArrayNode(ValueNode elementType, ValueNode length, boolean fillContents) {
        super(StampFactory.objectNonNull());
        this.length = length;
        this.elementType = elementType;
        this.fillContents = fillContents;
    }

    public ValueNode getElementType() {
        return elementType;
    }

    @Override
    public ValueNode length() {
        return length;
    }

    public boolean fillContents() {
        return fillContents;
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        if (elementType.isConstant()) {
            Class<?> elementClass = (Class<?>) elementType.asConstant().asObject();
            if (elementClass != null && !(elementClass.equals(void.class))) {
                ResolvedJavaType javaType = tool.runtime().lookupJavaType(elementClass);
                return graph().add(new NewArrayNode(javaType, length, fillContents));
            }
        }
        return this;
    }

    @Override
    public void lower(LoweringTool tool, LoweringType loweringType) {
        tool.getRuntime().lower(this, tool);
    }

    @NodeIntrinsic
    public static Object newArray(Class<?> componentType, int length) {
        return Array.newInstance(componentType, length);
    }
}
