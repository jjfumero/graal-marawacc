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
package com.oracle.graal.replacements.nodes;

import java.util.Collections;

import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.Assumptions.AssumptionResult;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

import com.oracle.graal.compiler.common.type.ObjectStamp;
import com.oracle.graal.compiler.common.type.Stamp;
import com.oracle.graal.compiler.common.type.StampFactory;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.CallTargetNode.InvokeKind;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.java.LoadFieldNode;
import com.oracle.graal.nodes.java.MonitorIdNode;
import com.oracle.graal.nodes.spi.ArrayLengthProvider;
import com.oracle.graal.nodes.spi.VirtualizableAllocation;
import com.oracle.graal.nodes.spi.VirtualizerTool;
import com.oracle.graal.nodes.util.GraphUtil;
import com.oracle.graal.nodes.virtual.VirtualInstanceNode;
import com.oracle.graal.nodes.virtual.VirtualObjectNode;

@NodeInfo
public abstract class BasicObjectCloneNode extends MacroStateSplitNode implements VirtualizableAllocation, ArrayLengthProvider {

    public static final NodeClass<BasicObjectCloneNode> TYPE = NodeClass.create(BasicObjectCloneNode.class);

    public BasicObjectCloneNode(NodeClass<? extends MacroNode> c, InvokeKind invokeKind, ResolvedJavaMethod targetMethod, int bci, JavaType returnType, ValueNode... arguments) {
        super(c, invokeKind, targetMethod, bci, returnType, arguments);
    }

    @Override
    public boolean inferStamp() {
        Stamp objectStamp = getObject().stamp();
        if (objectStamp instanceof ObjectStamp) {
            objectStamp = objectStamp.join(StampFactory.objectNonNull());
        }
        return updateStamp(objectStamp);
    }

    public ValueNode getObject() {
        return arguments.get(0);
    }

    protected static boolean isCloneableType(ResolvedJavaType type, MetaAccessProvider metaAccess) {
        return metaAccess.lookupJavaType(Cloneable.class).isAssignableFrom(type);
    }

    /*
     * Looks at the given stamp and determines if it is an exact type (or can be assumed to be an
     * exact type) and if it is a cloneable type.
     *
     * If yes, then the exact type is returned, otherwise it returns null.
     */
    protected static ResolvedJavaType getConcreteType(Stamp stamp, Assumptions assumptions, MetaAccessProvider metaAccess) {
        if (!(stamp instanceof ObjectStamp)) {
            return null;
        }
        ObjectStamp objectStamp = (ObjectStamp) stamp;
        if (objectStamp.type() == null) {
            return null;
        } else if (objectStamp.isExactType()) {
            return isCloneableType(objectStamp.type(), metaAccess) ? objectStamp.type() : null;
        }

        AssumptionResult<ResolvedJavaType> leafConcreteSubtype = objectStamp.type().findLeafConcreteSubtype();
        if (leafConcreteSubtype != null && leafConcreteSubtype.canRecordTo(assumptions) && isCloneableType(leafConcreteSubtype.getResult(), metaAccess)) {
            leafConcreteSubtype.recordTo(assumptions);
            return leafConcreteSubtype.getResult();
        }
        return null;
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode originalAlias = tool.getAlias(getObject());
        if (originalAlias instanceof VirtualObjectNode) {
            VirtualObjectNode originalVirtual = (VirtualObjectNode) originalAlias;
            if (isCloneableType(originalVirtual.type(), tool.getMetaAccessProvider())) {
                ValueNode[] newEntryState = new ValueNode[originalVirtual.entryCount()];
                for (int i = 0; i < newEntryState.length; i++) {
                    newEntryState[i] = tool.getEntry(originalVirtual, i);
                }
                VirtualObjectNode newVirtual = originalVirtual.duplicate();
                tool.createVirtualObject(newVirtual, newEntryState, Collections.<MonitorIdNode> emptyList(), false);
                tool.replaceWithVirtual(newVirtual);
            }
        } else {
            ResolvedJavaType type = getConcreteType(originalAlias.stamp(), graph().getAssumptions(), tool.getMetaAccessProvider());
            if (type != null && !type.isArray()) {
                VirtualInstanceNode newVirtual = createVirtualInstanceNode(type, true);
                ResolvedJavaField[] fields = newVirtual.getFields();

                ValueNode[] state = new ValueNode[fields.length];
                final LoadFieldNode[] loads = new LoadFieldNode[fields.length];
                for (int i = 0; i < fields.length; i++) {
                    state[i] = loads[i] = new LoadFieldNode(originalAlias, fields[i]);
                    tool.addNode(loads[i]);
                }
                tool.createVirtualObject(newVirtual, state, Collections.<MonitorIdNode> emptyList(), false);
                tool.replaceWithVirtual(newVirtual);
            }
        }
    }

    protected VirtualInstanceNode createVirtualInstanceNode(ResolvedJavaType type, boolean hasIdentity) {
        return new VirtualInstanceNode(type, hasIdentity);
    }

    @Override
    public ValueNode length() {
        return GraphUtil.arrayLength(getObject());
    }
}
