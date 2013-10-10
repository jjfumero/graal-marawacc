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

import static com.oracle.graal.graph.UnsafeAccess.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

/**
 * Load of a value from a location specified as an offset relative to an object. No null check is
 * performed before the load.
 */
public class UnsafeLoadNode extends UnsafeAccessNode implements Lowerable, Virtualizable {
    @Input private LogicNode guardingCondition;

    public UnsafeLoadNode(ValueNode object, ValueNode offset, Kind accessKind) {
        this(object, offset, accessKind, LocationIdentity.ANY_LOCATION, null);
    }

    public UnsafeLoadNode(ValueNode object, ValueNode offset, Kind accessKind, LocationIdentity locationIdentity, LogicNode condition) {
        super(StampFactory.forKind(accessKind.getStackKind()), object, offset, accessKind, locationIdentity);
        this.guardingCondition = condition;
    }

    public LogicNode getGuardingCondition() {
        return guardingCondition;
    }

    @Override
    public void lower(LoweringTool tool) {
        tool.getCodeCache().lower(this, tool);
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        State state = tool.getObjectState(object());
        if (state != null && state.getState() == EscapeState.Virtual) {
            ValueNode offsetValue = tool.getReplacedValue(offset());
            if (offsetValue.isConstant()) {
                long offset = offsetValue.asConstant().asLong();
                int entryIndex = state.getVirtualObject().entryIndexForOffset(offset);
                if (entryIndex != -1 && state.getVirtualObject().entryKind(entryIndex) == accessKind()) {
                    tool.replaceWith(state.getEntry(entryIndex));
                }
            }
        }
    }

    @Override
    protected ValueNode cloneAsFieldAccess(ResolvedJavaField field) {
        return this.graph().add(new LoadFieldNode(object(), field));
    }

    @SuppressWarnings("unchecked")
    @NodeIntrinsic
    public static <T> T load(Object object, long offset, @ConstantNodeParameter Kind kind) {
        if (kind == Kind.Boolean) {
            return (T) (Boolean) unsafe.getBoolean(object, offset);
        }
        if (kind == Kind.Byte) {
            return (T) (Byte) unsafe.getByte(object, offset);
        }
        if (kind == Kind.Short) {
            return (T) (Short) unsafe.getShort(object, offset);
        }
        if (kind == Kind.Char) {
            return (T) (Character) unsafe.getChar(object, offset);
        }
        if (kind == Kind.Int) {
            return (T) (Integer) unsafe.getInt(object, offset);
        }
        if (kind == Kind.Float) {
            return (T) (Float) unsafe.getFloat(object, offset);
        }
        if (kind == Kind.Long) {
            return (T) (Long) unsafe.getLong(object, offset);
        }
        if (kind == Kind.Double) {
            return (T) (Double) unsafe.getDouble(object, offset);
        }
        assert kind == Kind.Object;
        return (T) unsafe.getObject(object, offset);
    }
}
