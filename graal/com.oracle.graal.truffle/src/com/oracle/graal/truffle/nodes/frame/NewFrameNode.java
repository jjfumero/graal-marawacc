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
package com.oracle.graal.truffle.nodes.frame;

import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.runtime.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.nodes.virtual.*;
import com.oracle.graal.truffle.*;
import com.oracle.graal.truffle.nodes.*;
import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;

/**
 * Intrinsic node representing the call for creating a frame in the {@link OptimizedCallTarget}
 * class.
 */
public class NewFrameNode extends FixedWithNextNode implements IterableNodeType, VirtualizableAllocation, Canonicalizable {

    static final ResolvedJavaType FRAME_TYPE = Graal.getRequiredCapability(MetaAccessProvider.class).lookupJavaType(FrameWithoutBoxing.class);

    @Input private ValueNode descriptor;
    @Input private ValueNode caller;
    @Input private ValueNode arguments;

    public NewFrameNode(Stamp stamp, ValueNode descriptor, ValueNode caller, ValueNode arguments) {
        super(stamp);
        this.descriptor = descriptor;
        this.caller = caller;
        this.arguments = arguments;
    }

    public NewFrameNode(ValueNode descriptor, ValueNode caller, ValueNode arguments) {
        this(StampFactory.exactNonNull(FRAME_TYPE), descriptor, caller, arguments);
    }

    public ValueNode getDescriptor() {
        return descriptor;
    }

    public ValueNode getCaller() {
        return caller;
    }

    public ValueNode getArguments() {
        return arguments;
    }

    private FrameDescriptor getConstantFrameDescriptor() {
        assert descriptor.isConstant() && !descriptor.isNullConstant();
        return (FrameDescriptor) descriptor.asConstant().asObject();
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

    public static class VirtualOnlyInstanceNode extends VirtualInstanceNode {

        private boolean allowMaterialization;

        public VirtualOnlyInstanceNode(ResolvedJavaType type, ResolvedJavaField[] fields) {
            super(type, fields, false);
        }

        @Override
        public ValueNode getMaterializedRepresentation(FixedNode fixed, ValueNode[] entries, int[] locks) {
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
        if (fixed instanceof MaterializeFrameNode || fixed instanceof AbstractEndNode) {
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
        int frameSize = getFrameSize();

        ResolvedJavaType frameType = stamp().javaType(tool.getMetaAccessProvider());
        ResolvedJavaField[] frameFields = frameType.getInstanceFields(true);

        ResolvedJavaField descriptorField = findField(frameFields, "descriptor");
        ResolvedJavaField callerField = findField(frameFields, "caller");
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
            ConstantNode objectDefault = ConstantNode.forObject(frameDescriptor.getTypeConversion().getDefaultValue(), tool.getMetaAccessProvider(), graph());
            ConstantNode tagDefault = ConstantNode.forByte((byte) 0, graph());
            for (int i = 0; i < frameSize; i++) {
                objectArrayEntryState[i] = objectDefault;
                primitiveArrayEntryState[i] = initialValue(frameDescriptor.getSlots().get(i).getKind());
                tagArrayEntryState[i] = tagDefault;
            }
            tool.getAssumptions().record(new AssumptionValidAssumption((OptimizedAssumption) frameDescriptor.getVersion()));
        }

        tool.createVirtualObject(virtualFrameObjectArray, objectArrayEntryState, null);
        tool.createVirtualObject(virtualFramePrimitiveArray, primitiveArrayEntryState, null);
        tool.createVirtualObject(virtualFrameTagArray, tagArrayEntryState, null);

        assert frameFields.length == 6;
        ValueNode[] frameEntryState = new ValueNode[frameFields.length];
        List<ResolvedJavaField> frameFieldList = Arrays.asList(frameFields);
        frameEntryState[frameFieldList.indexOf(descriptorField)] = getDescriptor();
        frameEntryState[frameFieldList.indexOf(callerField)] = getCaller();
        frameEntryState[frameFieldList.indexOf(argumentsField)] = getArguments();
        frameEntryState[frameFieldList.indexOf(localsField)] = virtualFrameObjectArray;
        frameEntryState[frameFieldList.indexOf(primitiveLocalsField)] = virtualFramePrimitiveArray;
        frameEntryState[frameFieldList.indexOf(tagsField)] = virtualFrameTagArray;
        tool.createVirtualObject(virtualFrame, frameEntryState, null);
        tool.replaceWithVirtual(virtualFrame);
    }

    private ValueNode initialValue(FrameSlotKind kind) {
        Kind graalKind = Kind.Long;
        switch (kind) {
            case Int:
                graalKind = Kind.Int;
                break;
            case Double:
                graalKind = Kind.Double;
                break;
            case Float:
                graalKind = Kind.Float;
                break;
            case Boolean:
                graalKind = Kind.Boolean;
                break;
        }

        return ConstantNode.defaultForKind(graalKind, graph());
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        if (usages().isEmpty()) {
            return null;
        } else {
            return this;
        }
    }

    @NodeIntrinsic
    public static native FrameWithoutBoxing allocate(FrameDescriptor descriptor, PackedFrame caller, Arguments args);
}
