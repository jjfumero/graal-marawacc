/*
 * Copyright (c) 2009, 2014, Oracle and/or its affiliates. All rights reserved.
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

import java.nio.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

/**
 * The {@code StoreIndexedNode} represents a write to an array element.
 */
@NodeInfo
public class StoreIndexedNode extends AccessIndexedNode implements StateSplit, Lowerable, Virtualizable {

    @Input ValueNode value;
    @OptionalInput(InputType.State) FrameState stateAfter;

    public FrameState stateAfter() {
        return stateAfter;
    }

    public void setStateAfter(FrameState x) {
        assert x == null || x.isAlive() : "frame state must be in a graph";
        updateUsages(stateAfter, x);
        stateAfter = x;
    }

    public boolean hasSideEffect() {
        return true;
    }

    public ValueNode value() {
        return value;
    }

    public StoreIndexedNode(ValueNode array, ValueNode index, Kind elementKind, ValueNode value) {
        super(StampFactory.forVoid(), array, index, elementKind);
        this.value = value;
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        State arrayState = tool.getObjectState(array());
        if (arrayState != null && arrayState.getState() == EscapeState.Virtual) {
            ValueNode indexValue = tool.getReplacedValue(index());
            int idx = indexValue.isConstant() ? indexValue.asJavaConstant().asInt() : -1;
            if (idx >= 0 && idx < arrayState.getVirtualObject().entryCount()) {
                ResolvedJavaType componentType = arrayState.getVirtualObject().type().getComponentType();
                // isStoreSafe prevents on Little Endian machines to not do an implicit cast of
                // primitives (Causes troubles with deoptimization)
                boolean isStoreSafe = !componentType.isPrimitive() || ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN || componentType.equals(StampTool.typeOrNull(value));
                if (isStoreSafe &&
                                (componentType.isPrimitive() || StampTool.isPointerAlwaysNull(value) || componentType.getSuperclass() == null || (StampTool.typeOrNull(value) != null && componentType.isAssignableFrom(StampTool.typeOrNull(value))))) {
                    tool.setVirtualEntry(arrayState, idx, value(), false);
                    tool.delete();
                }
            }
        }
    }

    public FrameState getState() {
        return stateAfter;
    }
}
