/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.virtual.*;

/**
 * Load of a value from a location specified as an offset relative to an object.
 * No null check is performed before the load.
 */
public class UnsafeLoadNode extends UnsafeAccessNode implements Lowerable, Virtualizable, Canonicalizable {

    public UnsafeLoadNode(ValueNode object, int displacement, ValueNode offset, boolean nonNull) {
        this(nonNull ? StampFactory.objectNonNull() : StampFactory.object(), object, displacement, offset, Kind.Object);
    }

    public UnsafeLoadNode(ValueNode object, int displacement, ValueNode offset, Kind accessKind) {
        this(StampFactory.forKind(accessKind.getStackKind()), object, displacement, offset, accessKind);
    }

    public UnsafeLoadNode(Stamp stamp, ValueNode object, int displacement, ValueNode offset, Kind accessKind) {
        super(stamp, object, displacement, offset, accessKind);
    }

    @Override
    public void lower(LoweringTool tool) {
        tool.getRuntime().lower(this, tool);
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        VirtualObjectNode virtual = tool.getVirtualState(object());
        if (virtual != null) {
            ValueNode indexValue = tool.getReplacedValue(offset());
            if (indexValue.isConstant()) {
                int fieldIndex = virtual.fieldIndexForOffset(indexValue.asConstant().asLong());
                if (fieldIndex != -1) {
                    ValueNode result = tool.getVirtualEntry(virtual, fieldIndex);
                    VirtualObjectNode virtualResult = tool.getVirtualState(result);
                    if (virtualResult != null) {
                        tool.replaceWithVirtual(virtualResult);
                    } else {
                        tool.replaceWithValue(result);
                    }
                }
            }
        }
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        if (offset().isConstant()) {
            long constantOffset = offset().asConstant().asLong();
            if (constantOffset != 0) {
                int intDisplacement = (int) (constantOffset + displacement());
                if (constantOffset == intDisplacement) {
                    Graph graph = this.graph();
                    return graph.add(new UnsafeLoadNode(this.stamp(), object(), intDisplacement, graph.unique(ConstantNode.forInt(0, graph)), accessKind()));
                }
            } else if (object().stamp() instanceof ObjectStamp) { // TODO (gd) remove that once UnsafeAccess only have an object base
                ObjectStamp receiverStamp = object().objectStamp();
                if (receiverStamp.nonNull()) {
                    ResolvedJavaType receiverType = receiverStamp.type();
                    ResolvedJavaField field = receiverType.findInstanceFieldWithOffset(displacement());
                    if (field != null) {
                        return this.graph().add(new LoadFieldNode(object(), field, StructuredGraph.INVALID_GRAPH_ID));
                    }
                }
            }
        }
        return this;
    }

    @NodeIntrinsic
    public static native <T> T load(Object object, @ConstantNodeParameter int displacement, long offset, @ConstantNodeParameter Kind kind);
}
