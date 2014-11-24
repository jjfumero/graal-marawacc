/*
 * Copyright (c) 2011, 2014, Oracle and/or its affiliates. All rights reserved.
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

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

@NodeInfo(shortName = "==")
public class ObjectEqualsNode extends PointerEqualsNode implements Virtualizable {

    /**
     * Constructs a new object equality comparison node.
     *
     * @param x the instruction producing the first input to the instruction
     * @param y the instruction that produces the second input to this instruction
     */
    public static ObjectEqualsNode create(ValueNode x, ValueNode y) {
        return new ObjectEqualsNode(x, y);
    }

    protected ObjectEqualsNode(ValueNode x, ValueNode y) {
        super(x, y);
        assert x.stamp() instanceof AbstractObjectStamp;
        assert y.stamp() instanceof AbstractObjectStamp;
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forX, ValueNode forY) {
        ValueNode result = super.canonical(tool, forX, forY);
        if (result != this) {
            return result;
        }
        if (StampTool.isObjectAlwaysNull(forX)) {
            return IsNullNode.create(forY);
        } else if (StampTool.isObjectAlwaysNull(forY)) {
            return IsNullNode.create(forX);
        }
        return this;
    }

    private void virtualizeNonVirtualComparison(State state, ValueNode other, VirtualizerTool tool) {
        if (!state.getVirtualObject().hasIdentity() && state.getVirtualObject().entryKind(0) == Kind.Boolean) {
            if (other.isConstant()) {
                JavaConstant otherUnboxed = tool.getConstantReflectionProvider().unboxPrimitive(other.asJavaConstant());
                if (otherUnboxed != null && otherUnboxed.getKind() == Kind.Boolean) {
                    int expectedValue = otherUnboxed.asBoolean() ? 1 : 0;
                    IntegerEqualsNode equals = IntegerEqualsNode.create(state.getEntry(0), ConstantNode.forInt(expectedValue, graph()));
                    tool.addNode(equals);
                    tool.replaceWithValue(equals);
                } else {
                    tool.replaceWithValue(LogicConstantNode.contradiction(graph()));
                }
            }
        } else {
            // one of them is virtual: they can never be the same objects
            tool.replaceWithValue(LogicConstantNode.contradiction(graph()));
        }
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        State stateX = tool.getObjectState(getX());
        State stateY = tool.getObjectState(getY());
        boolean xVirtual = stateX != null && stateX.getState() == EscapeState.Virtual;
        boolean yVirtual = stateY != null && stateY.getState() == EscapeState.Virtual;

        if (xVirtual && !yVirtual) {
            virtualizeNonVirtualComparison(stateX, stateY != null ? stateY.getMaterializedValue() : getY(), tool);
        } else if (!xVirtual && yVirtual) {
            virtualizeNonVirtualComparison(stateY, stateX != null ? stateX.getMaterializedValue() : getX(), tool);
        } else if (xVirtual && yVirtual) {
            boolean xIdentity = stateX.getVirtualObject().hasIdentity();
            boolean yIdentity = stateY.getVirtualObject().hasIdentity();
            if (xIdentity ^ yIdentity) {
                /*
                 * One of the two objects has identity, the other doesn't. In code, this looks like
                 * "Integer.valueOf(a) == new Integer(b)", which is always false.
                 *
                 * In other words: an object created via valueOf can never be equal to one created
                 * by new in the same compilation unit.
                 */
                tool.replaceWithValue(LogicConstantNode.contradiction(graph()));
            } else if (!xIdentity && !yIdentity) {
                ResolvedJavaType type = stateX.getVirtualObject().type();
                if (type.equals(stateY.getVirtualObject().type())) {
                    MetaAccessProvider metaAccess = tool.getMetaAccessProvider();
                    if (type.equals(metaAccess.lookupJavaType(Integer.class)) || type.equals(metaAccess.lookupJavaType(Long.class))) {
                        // both are virtual without identity: check contents
                        assert stateX.getVirtualObject().entryCount() == 1 && stateY.getVirtualObject().entryCount() == 1;
                        assert stateX.getVirtualObject().entryKind(0).getStackKind() == Kind.Int || stateX.getVirtualObject().entryKind(0) == Kind.Long;
                        IntegerEqualsNode equals = IntegerEqualsNode.create(stateX.getEntry(0), stateY.getEntry(0));
                        tool.addNode(equals);
                        tool.replaceWithValue(equals);
                    }
                }
            } else {
                // both are virtual with identity: check if they refer to the same object
                tool.replaceWithValue(LogicConstantNode.forBoolean(stateX == stateY, graph()));
            }
        }
    }

    @Override
    protected CompareNode duplicateModified(ValueNode newX, ValueNode newY) {
        return ObjectEqualsNode.create(newX, newY);
    }
}
