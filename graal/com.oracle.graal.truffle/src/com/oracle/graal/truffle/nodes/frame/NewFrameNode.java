/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.truffle.nodes.frame;

import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.replacements.*;
import com.oracle.graal.api.runtime.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.nodes.virtual.*;
import com.oracle.graal.truffle.*;
import com.oracle.graal.truffle.nodes.*;
import com.oracle.truffle.api.frame.*;

/**
 * Intrinsic node representing the call for creating a frame in the {@link OptimizedCallTarget}
 * class.
 */
@NodeInfo
public final class NewFrameNode extends FixedWithNextNode implements IterableNodeType, VirtualizableAllocation, Canonicalizable {

    @Input ValueNode descriptor;
    @Input ValueNode arguments;

    public NewFrameNode(Stamp stamp, ValueNode descriptor, ValueNode arguments) {
        super(stamp);
        this.descriptor = descriptor;
        this.arguments = arguments;
    }

    public NewFrameNode(ResolvedJavaType frameType, ValueNode descriptor, ValueNode arguments) {
        this(StampFactory.exactNonNull(frameType), descriptor, arguments);
    }

    public ValueNode getDescriptor() {
        return descriptor;
    }

    public ValueNode getArguments() {
        return arguments;
    }

    private static SnippetReflectionProvider getSnippetReflection() {
        /*
         * This class requires access to the objects encapsulated in Constants, and therefore breaks
         * the compiler-VM separation of object constants.
         */
        return Graal.getRequiredCapability(SnippetReflectionProvider.class);
    }

    private FrameDescriptor getConstantFrameDescriptor() {
        assert descriptor.isConstant() && !descriptor.isNullConstant();
        return getSnippetReflection().asObject(FrameDescriptor.class, descriptor.asJavaConstant());
    }

    private int getFrameSize() {
        return getConstantFrameDescriptor().getSize();
    }

    private static ResolvedJavaField findField(ResolvedJavaField[] fields, String fieldName) {
        for (ResolvedJavaField field : fields) {
            if (field.getName().equals(fieldName)) {
                return field;
            }
        }
        throw new RuntimeException("Frame field not found: " + fieldName);
    }

    @NodeInfo
    public static final class VirtualOnlyInstanceNode extends VirtualInstanceNode {

        protected boolean allowMaterialization;

        public VirtualOnlyInstanceNode(ResolvedJavaType type, ResolvedJavaField[] fields) {
            super(type, fields, true);
        }

        @Override
        public ValueNode getMaterializedRepresentation(FixedNode fixed, ValueNode[] entries, LockState locks) {
            if (allowMaterialization) {
                return super.getMaterializedRepresentation(fixed, entries, locks);
            }
            return getMaterializedRepresentationHelper(this, fixed);
        }

        public void setAllowMaterialization(boolean b) {
            this.allowMaterialization = b;
        }
    }

    public static ValueNode getMaterializedRepresentationHelper(VirtualObjectNode virtualNode, FixedNode fixed) {
        if (fixed instanceof MaterializeFrameNode || fixed instanceof AbstractEndNode || fixed instanceof ForceMaterializeNode) {
            // We need to conservatively assume that a materialization of a virtual frame can also
            // happen at a merge point.
            return new AllocatedObjectNode(virtualNode);
        }
        String escapeReason;
        if (fixed instanceof StoreFieldNode) {
            escapeReason = "Must not store virtual frame object into a field.";
        } else if (fixed instanceof Invoke) {
            escapeReason = "Must not pass virtual frame object into an invoke that cannot be inlined.";
        } else {
            escapeReason = "Must not let virtual frame object escape at node " + fixed + ".";
        }

        Throwable exception = new GraalInternalError(escapeReason +
                        " Insert a call to VirtualFrame.materialize() to convert the instance to a materialized frame object (source position of following stack trace is approximate)");
        throw GraphUtil.approxSourceException(fixed, exception);
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        if (!descriptor.isConstant()) {
            return;
        }

        int frameSize = getFrameSize();

        ResolvedJavaType frameType = stamp().javaType(tool.getMetaAccessProvider());
        ResolvedJavaField[] frameFields = frameType.getInstanceFields(true);

        ResolvedJavaField descriptorField = findField(frameFields, "descriptor");
        ResolvedJavaField argumentsField = findField(frameFields, "arguments");
        ResolvedJavaField localsField = findField(frameFields, "locals");
        ResolvedJavaField primitiveLocalsField = findField(frameFields, "primitiveLocals");
        ResolvedJavaField tagsField = findField(frameFields, "tags");

        VirtualObjectNode virtualFrame = new VirtualOnlyInstanceNode(frameType, frameFields);
        VirtualObjectNode virtualFrameObjectArray = new VirtualArrayNode((ResolvedJavaType) localsField.getType().getComponentType(), frameSize);
        VirtualObjectNode virtualFramePrimitiveArray = new VirtualArrayNode((ResolvedJavaType) primitiveLocalsField.getType().getComponentType(), frameSize);
        VirtualObjectNode virtualFrameTagArray = new VirtualArrayNode((ResolvedJavaType) tagsField.getType().getComponentType(), frameSize);

        ValueNode[] objectArrayEntryState = new ValueNode[frameSize];
        ValueNode[] primitiveArrayEntryState = new ValueNode[frameSize];
        ValueNode[] tagArrayEntryState = new ValueNode[frameSize];

        if (frameSize > 0) {
            FrameDescriptor frameDescriptor = getConstantFrameDescriptor();
            ConstantNode objectDefault = ConstantNode.forConstant(getSnippetReflection().forObject(frameDescriptor.getDefaultValue()), tool.getMetaAccessProvider(), graph());
            ConstantNode tagDefault = ConstantNode.forByte((byte) 0, graph());
            Arrays.fill(objectArrayEntryState, objectDefault);
            Arrays.fill(tagArrayEntryState, tagDefault);
            for (int i = 0; i < frameSize; i++) {
                primitiveArrayEntryState[i] = initialPrimitiveValue(frameDescriptor.getSlots().get(i).getKind());
            }
            graph().getAssumptions().record(new AssumptionValidAssumption((OptimizedAssumption) frameDescriptor.getVersion()));
        }

        tool.createVirtualObject(virtualFrameObjectArray, objectArrayEntryState, Collections.<MonitorIdNode> emptyList());
        tool.createVirtualObject(virtualFramePrimitiveArray, primitiveArrayEntryState, Collections.<MonitorIdNode> emptyList());
        tool.createVirtualObject(virtualFrameTagArray, tagArrayEntryState, Collections.<MonitorIdNode> emptyList());

        assert frameFields.length == 5;
        ValueNode[] frameEntryState = new ValueNode[frameFields.length];
        List<ResolvedJavaField> frameFieldList = Arrays.asList(frameFields);
        frameEntryState[frameFieldList.indexOf(descriptorField)] = getDescriptor();
        frameEntryState[frameFieldList.indexOf(argumentsField)] = getArguments();
        frameEntryState[frameFieldList.indexOf(localsField)] = virtualFrameObjectArray;
        frameEntryState[frameFieldList.indexOf(primitiveLocalsField)] = virtualFramePrimitiveArray;
        frameEntryState[frameFieldList.indexOf(tagsField)] = virtualFrameTagArray;
        tool.createVirtualObject(virtualFrame, frameEntryState, Collections.<MonitorIdNode> emptyList());
        tool.replaceWithVirtual(virtualFrame);
    }

    private ValueNode initialPrimitiveValue(FrameSlotKind kind) {
        Kind graalKind = null;
        switch (kind) {
            case Boolean:
                graalKind = Kind.Boolean;
                break;
            case Byte:
                graalKind = Kind.Byte;
                break;
            case Int:
                graalKind = Kind.Int;
                break;
            case Double:
                graalKind = Kind.Double;
                break;
            case Float:
                graalKind = Kind.Float;
                break;
            case Long:
                graalKind = Kind.Long;
                break;
            case Object:
            case Illegal:
                // won't be stored in the primitive array, so default to long
                graalKind = Kind.Long;
                break;
            default:
                throw new IllegalStateException("Unexpected frame slot kind: " + kind);
        }

        return ConstantNode.defaultForKind(graalKind, graph());
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (hasNoUsages()) {
            return null;
        } else {
            return this;
        }
    }

    @NodeIntrinsic
    public static native FrameWithoutBoxing allocate(@ConstantNodeParameter Class<? extends VirtualFrame> frameType, FrameDescriptor descriptor, Object[] args);
}
