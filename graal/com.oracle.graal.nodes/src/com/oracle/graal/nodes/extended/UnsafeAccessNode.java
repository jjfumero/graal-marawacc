/*
 * Copyright (c) 2012, 2015, Oracle and/or its affiliates. All rights reserved.
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

import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.LocationIdentity;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

import com.oracle.graal.compiler.common.type.Stamp;
import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.graph.spi.Canonicalizable;
import com.oracle.graal.graph.spi.CanonicalizerTool;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.FixedWithNextNode;
import com.oracle.graal.nodes.NamedLocationIdentity;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.type.StampTool;

@NodeInfo
public abstract class UnsafeAccessNode extends FixedWithNextNode implements Canonicalizable {

    public static final NodeClass<UnsafeAccessNode> TYPE = NodeClass.create(UnsafeAccessNode.class);
    @Input ValueNode object;
    @Input ValueNode offset;
    protected final JavaKind accessKind;
    protected final LocationIdentity locationIdentity;

    protected UnsafeAccessNode(NodeClass<? extends UnsafeAccessNode> c, Stamp stamp, ValueNode object, ValueNode offset, JavaKind accessKind, LocationIdentity locationIdentity) {
        super(c, stamp);
        assert accessKind != null;
        this.object = object;
        this.offset = offset;
        this.accessKind = accessKind;
        this.locationIdentity = locationIdentity;
    }

    public LocationIdentity getLocationIdentity() {
        return locationIdentity;
    }

    public ValueNode object() {
        return object;
    }

    public ValueNode offset() {
        return offset;
    }

    public JavaKind accessKind() {
        return accessKind;
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (this.getLocationIdentity().isAny() && offset().isConstant()) {
            long constantOffset = offset().asJavaConstant().asLong();

            // Try to canonicalize to a field access.
            ResolvedJavaType receiverType = StampTool.typeOrNull(object());
            if (receiverType != null) {
                ResolvedJavaField field = receiverType.findInstanceFieldWithOffset(constantOffset, accessKind());
                // No need for checking that the receiver is non-null. The field access includes
                // the null check and if a field is found, the offset is so small that this is
                // never a valid access of an arbitrary address.
                if (field != null && field.getJavaKind() == this.accessKind()) {
                    assert !graph().isAfterFloatingReadPhase() : "cannot add more precise memory location after floating read phase";
                    return cloneAsFieldAccess(field);
                }
            }
        }
        if (this.getLocationIdentity().isAny()) {
            ResolvedJavaType receiverType = StampTool.typeOrNull(object());
            // Try to build a better location identity.
            if (receiverType != null && receiverType.isArray()) {
                LocationIdentity identity = NamedLocationIdentity.getArrayLocation(receiverType.getComponentType().getJavaKind());
                assert !graph().isAfterFloatingReadPhase() : "cannot add more precise memory location after floating read phase";
                return cloneAsArrayAccess(offset(), identity);
            }
        }

        return this;
    }

    protected abstract ValueNode cloneAsFieldAccess(ResolvedJavaField field);

    protected abstract ValueNode cloneAsArrayAccess(ValueNode location, LocationIdentity identity);
}
