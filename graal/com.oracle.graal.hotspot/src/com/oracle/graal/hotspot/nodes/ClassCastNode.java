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
package com.oracle.graal.hotspot.nodes;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.hotspot.replacements.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.replacements.nodes.*;

/**
 * {@link MacroNode Macro node} for {@link Class#cast(Object)}.
 *
 * @see HotSpotClassSubstitutions#cast(Class, Object)
 */
@NodeInfo
public final class ClassCastNode extends MacroStateSplitNode implements Canonicalizable.Binary<ValueNode> {

    public static final NodeClass<ClassCastNode> TYPE = NodeClass.get(ClassCastNode.class);

    public ClassCastNode(Invoke invoke) {
        super(TYPE, invoke);
    }

    private ValueNode getJavaClass() {
        return arguments.get(0);
    }

    private ValueNode getObject() {
        return arguments.get(1);
    }

    public ValueNode getX() {
        return getJavaClass();
    }

    public ValueNode getY() {
        return getObject();
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forJavaClass, ValueNode forObject) {
        if (forJavaClass.isConstant()) {
            ResolvedJavaType type = tool.getConstantReflection().asJavaType(forJavaClass.asConstant());
            if (type != null && !type.isPrimitive()) {
                return new CheckCastNode(type, forObject, null, false);
            }
        }
        return this;
    }
}
