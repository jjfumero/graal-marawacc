/*
 * Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
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

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

@NodeInfo(nameTemplate = "Materialize {p#type/s}")
public final class MaterializeObjectNode extends FixedWithNextNode implements Lowerable, Node.IterableNodeType, Canonicalizable {

    @Input private final NodeInputList<ValueNode> values;
    private final ResolvedJavaType type;
    private final EscapeField[] fields;

    public MaterializeObjectNode(ResolvedJavaType type, EscapeField[] fields) {
        super(StampFactory.exactNonNull(type));
        this.type = type;
        this.fields = fields;
        this.values = new NodeInputList<>(this, fields.length);
    }

    public ResolvedJavaType type() {
        return type;
    }

    public EscapeField[] getFields() {
        return fields;
    }

    public NodeInputList<ValueNode> values() {
        return values;
    }

    @Override
    public void lower(LoweringTool tool) {
        StructuredGraph graph = (StructuredGraph) graph();
        if (type.isArrayClass()) {
            ResolvedJavaType element = type.componentType();
            NewArrayNode newArray;
            if (element.kind() == Kind.Object) {
                newArray = graph.add(new NewObjectArrayNode(element, ConstantNode.forInt(fields.length, graph), false));
            } else {
                newArray = graph.add(new NewPrimitiveArrayNode(element, ConstantNode.forInt(fields.length, graph), false));
            }
            this.replaceAtUsages(newArray);
            graph.addAfterFixed(this, newArray);

            FixedWithNextNode position = newArray;
            for (int i = 0; i < fields.length; i++) {
                StoreIndexedNode store = graph.add(new StoreIndexedNode(newArray, ConstantNode.forInt(i, graph), element.kind(), values.get(i), -1));
                graph.addAfterFixed(position, store);
                position = store;
            }

            graph.removeFixed(this);
        } else {
            NewInstanceNode newInstance = graph.add(new NewInstanceNode(type, false));
            this.replaceAtUsages(newInstance);
            graph.addAfterFixed(this, newInstance);

            FixedWithNextNode position = newInstance;
            for (int i = 0; i < fields.length; i++) {
                StoreFieldNode store = graph.add(new StoreFieldNode(newInstance, (ResolvedJavaField) fields[i].representation(), values.get(i), -1));
                graph.addAfterFixed(position, store);
                position = store;
            }

            graph.removeFixed(this);
        }
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        if (usages().isEmpty()) {
            return null;
        } else {
            return this;
        }
    }
}
