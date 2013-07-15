/*
 * Copyright (c) 2013, 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.spi.*;

/**
 * A node that attaches a type profile to a proxied input node.
 */
public final class TypeProfileProxyNode extends FloatingNode implements Canonicalizable, Node.IterableNodeType, ValueProxy {

    @Input private ValueNode object;
    private final JavaTypeProfile profile;
    private transient ResolvedJavaType lastCheckedType;
    private transient JavaTypeProfile lastCheckedProfile;

    public ValueNode getObject() {
        return object;
    }

    public static ValueNode create(ValueNode object, JavaTypeProfile profile) {
        if (profile == null) {
            // No profile, so create no node.
            return object;
        }
        if (profile.getTypes().length == 0) {
            // Only null profiling is not beneficial enough to keep the node around.
            return object;
        }
        return object.graph().add(new TypeProfileProxyNode(object, profile));
    }

    private TypeProfileProxyNode(ValueNode object, JavaTypeProfile profile) {
        super(object.stamp());
        this.object = object;
        this.profile = profile;
    }

    public JavaTypeProfile getProfile() {
        return profile;
    }

    @Override
    public boolean inferStamp() {
        return updateStamp(object.stamp());
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        if (object.objectStamp().isExactType()) {
            // The profile is useless - we know the type!
            return object;
        } else if (object instanceof TypeProfileProxyNode) {
            TypeProfileProxyNode other = (TypeProfileProxyNode) object;
            JavaTypeProfile otherProfile = other.getProfile();
            if (otherProfile == lastCheckedProfile) {
                // We have already incorporated the knowledge about this profile => abort.
                return this;
            }
            lastCheckedProfile = otherProfile;
            JavaTypeProfile newProfile = this.profile.restrict(otherProfile);
            if (newProfile.equals(otherProfile)) {
                // We are useless - just use the other proxy node.
                Debug.log("Canonicalize with other proxy node.");
                return object;
            }
            if (newProfile != this.profile) {
                Debug.log("Improved profile via other profile.");
                return TypeProfileProxyNode.create(object, newProfile);
            }
        } else if (object.objectStamp().type() != null) {
            ResolvedJavaType type = object.objectStamp().type();
            ResolvedJavaType uniqueConcrete = type.findUniqueConcreteSubtype();
            if (uniqueConcrete != null) {
                // Profile is useless => remove.
                Debug.log("Profile useless, there is enough static type information available.");
                return object;
            }
            if (type == lastCheckedType) {
                // We have already incorporate the knowledge about this type => abort.
                return this;
            }
            lastCheckedType = type;
            JavaTypeProfile newProfile = this.profile.restrict(type, object.objectStamp().nonNull());
            if (newProfile != this.profile) {
                Debug.log("Improved profile via static type information.");
                if (newProfile.getTypes().length == 0) {
                    // Only null profiling is not beneficial enough to keep the node around.
                    return object;
                }
                return TypeProfileProxyNode.create(object, newProfile);
            }
        }
        return this;
    }

    public static void cleanFromGraph(StructuredGraph graph) {
        for (TypeProfileProxyNode proxy : graph.getNodes(TypeProfileProxyNode.class)) {
            graph.replaceFloating(proxy, proxy.getObject());
        }
        assert graph.getNodes(TypeProfileProxyNode.class).count() == 0;
    }

    @Override
    public ValueNode getOriginalValue() {
        return object;
    }
}
