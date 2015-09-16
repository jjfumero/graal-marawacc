/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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
//JaCoCo Exclude
package com.oracle.graal.nodes.java;

import jdk.internal.jvmci.meta.JavaKind;
import jdk.internal.jvmci.meta.MetaAccessProvider;
import jdk.internal.jvmci.meta.ResolvedJavaType;

import com.oracle.graal.compiler.common.type.Stamp;
import com.oracle.graal.compiler.common.type.StampFactory;
import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.graph.spi.Canonicalizable;
import com.oracle.graal.graph.spi.CanonicalizerTool;
import com.oracle.graal.graph.spi.SimplifierTool;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.FrameState;
import com.oracle.graal.nodes.ValueNode;

/**
 * The {@code DynamicNewArrayNode} is used for allocation of arrays when the type is not a
 * compile-time constant.
 */
@NodeInfo
public class DynamicNewArrayNode extends AbstractNewArrayNode implements Canonicalizable {
    public static final NodeClass<DynamicNewArrayNode> TYPE = NodeClass.create(DynamicNewArrayNode.class);

    @Input ValueNode elementType;

    /**
     * A non-null value indicating the worst case element type. Mainly useful for distinguishing
     * Object arrays from primitive arrays.
     */
    protected final JavaKind knownElementKind;

    public DynamicNewArrayNode(ValueNode elementType, ValueNode length, boolean fillContents) {
        this(TYPE, elementType, length, fillContents, null, null, null);
    }

    public DynamicNewArrayNode(@InjectedNodeParameter MetaAccessProvider metaAccess, ValueNode elementType, ValueNode length, boolean fillContents, JavaKind knownElementKind) {
        this(TYPE, elementType, length, fillContents, knownElementKind, null, metaAccess);
    }

    private static Stamp computeStamp(JavaKind knownElementKind, MetaAccessProvider metaAccess) {
        if (knownElementKind != null && metaAccess != null) {
            ResolvedJavaType arrayType = metaAccess.lookupJavaType(knownElementKind == JavaKind.Object ? Object.class : knownElementKind.toJavaClass()).getArrayClass();
            return StampFactory.declaredNonNull(arrayType);
        }
        return StampFactory.objectNonNull();
    }

    protected DynamicNewArrayNode(NodeClass<? extends DynamicNewArrayNode> c, ValueNode elementType, ValueNode length, boolean fillContents, JavaKind knownElementKind, FrameState stateBefore,
                    MetaAccessProvider metaAccess) {
        super(c, computeStamp(knownElementKind, metaAccess), length, fillContents, stateBefore);
        this.elementType = elementType;
        this.knownElementKind = knownElementKind;
        assert knownElementKind != JavaKind.Void && knownElementKind != JavaKind.Illegal;
    }

    public ValueNode getElementType() {
        return elementType;
    }

    public JavaKind getKnownElementKind() {
        return knownElementKind;
    }

    @Override
    public void simplify(SimplifierTool tool) {
        /*
         * Do not call the super implementation: we must not eliminate unused allocations because
         * throwing a NullPointerException or IllegalArgumentException is a possible side effect of
         * an unused allocation.
         */
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (elementType.isConstant()) {
            ResolvedJavaType type = tool.getConstantReflection().asJavaType(elementType.asConstant());
            if (type != null && !throwsIllegalArgumentException(type)) {
                return createNewArrayNode(type);
            }
        }
        return this;
    }

    /** Hook for subclasses to instantiate a subclass of {@link NewArrayNode}. */
    protected NewArrayNode createNewArrayNode(ResolvedJavaType type) {
        return new NewArrayNode(type, length(), fillContents(), stateBefore());
    }

    public static boolean throwsIllegalArgumentException(Class<?> elementType) {
        return elementType == void.class;
    }

    public static boolean throwsIllegalArgumentException(ResolvedJavaType elementType) {
        return elementType.getJavaKind() == JavaKind.Void;
    }

    @NodeIntrinsic
    private static native Object newArray(Class<?> componentType, int length, @ConstantNodeParameter boolean fillContents, @ConstantNodeParameter JavaKind knownElementKind);

    public static Object newArray(Class<?> componentType, int length, JavaKind knownElementKind) {
        return newArray(componentType, length, true, knownElementKind);
    }

    public static Object newUninitializedArray(Class<?> componentType, int length, JavaKind knownElementKind) {
        return newArray(componentType, length, false, knownElementKind);
    }

}
