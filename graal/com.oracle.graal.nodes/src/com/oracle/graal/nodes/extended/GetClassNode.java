/*
 * Copyright (c) 2011, 2015, Oracle and/or its affiliates. All rights reserved.
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

import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.Assumptions.AssumptionResult;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaType;

import com.oracle.graal.compiler.common.type.ObjectStamp;
import com.oracle.graal.compiler.common.type.Stamp;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.graph.spi.Canonicalizable;
import com.oracle.graal.graph.spi.CanonicalizerTool;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.ConstantNode;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.calc.FloatingNode;
import com.oracle.graal.nodes.spi.Lowerable;
import com.oracle.graal.nodes.spi.LoweringTool;
import com.oracle.graal.nodes.spi.Virtualizable;
import com.oracle.graal.nodes.spi.VirtualizerTool;
import com.oracle.graal.nodes.virtual.VirtualObjectNode;

/**
 * Loads an object's class (i.e., this node can be created for {@code object.getClass()}).
 */
@NodeInfo
public final class GetClassNode extends FloatingNode implements Lowerable, Canonicalizable, Virtualizable {

    public static final NodeClass<GetClassNode> TYPE = NodeClass.create(GetClassNode.class);
    @Input ValueNode object;

    public ValueNode getObject() {
        return object;
    }

    public GetClassNode(Stamp stamp, ValueNode object) {
        super(TYPE, stamp);
        this.object = object;
        assert ((ObjectStamp) object.stamp()).nonNull();
    }

    @Override
    public void lower(LoweringTool tool) {
        tool.getLowerer().lower(this, tool);
    }

    public static ValueNode tryFold(MetaAccessProvider metaAccess, ValueNode object) {
        if (metaAccess != null && object != null && object.stamp() instanceof ObjectStamp) {
            ObjectStamp objectStamp = (ObjectStamp) object.stamp();

            ResolvedJavaType exactType = null;
            if (objectStamp.isExactType()) {
                exactType = objectStamp.type();
            } else if (objectStamp.type() != null) {
                Assumptions assumptions = object.graph().getAssumptions();
                AssumptionResult<ResolvedJavaType> leafConcreteSubtype = objectStamp.type().findLeafConcreteSubtype();
                if (leafConcreteSubtype != null && leafConcreteSubtype.canRecordTo(assumptions)) {
                    leafConcreteSubtype.recordTo(assumptions);
                    exactType = leafConcreteSubtype.getResult();
                }
            }

            if (exactType != null) {
                return ConstantNode.forConstant(exactType.getJavaClass(), metaAccess);
            }
        }
        return null;
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        ValueNode folded = tryFold(tool.getMetaAccess(), getObject());
        return folded == null ? this : folded;
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode alias = tool.getAlias(getObject());
        if (alias instanceof VirtualObjectNode) {
            VirtualObjectNode virtual = (VirtualObjectNode) alias;
            Constant javaClass = virtual.type().getJavaClass();
            tool.replaceWithValue(ConstantNode.forConstant(stamp(), javaClass, tool.getMetaAccessProvider(), graph()));
        }
    }
}
