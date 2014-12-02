/*
 * Copyright (c) 2014, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.replacements;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.runtime.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.runtime.*;

@NodeInfo(allowedUsageTypes = {InputType.Memory, InputType.Value})
public class CheckcastArrayCopyCallNode extends AbstractMemoryCheckpoint implements Lowerable, MemoryCheckpoint.Single {

    @Input ValueNode src;
    @Input ValueNode srcPos;
    @Input ValueNode dest;
    @Input ValueNode destPos;
    @Input ValueNode length;
    @Input ValueNode destElemKlass;
    @Input ValueNode superCheckOffset;

    protected final boolean uninit;

    protected final HotSpotGraalRuntimeProvider runtime;

    public static CheckcastArrayCopyCallNode create(@InjectedNodeParameter HotSpotGraalRuntimeProvider runtime, ValueNode src, ValueNode srcPos, ValueNode dest, ValueNode destPos, ValueNode length,
                    ValueNode superCheckOffset, ValueNode destElemKlass, boolean uninit) {
        return new CheckcastArrayCopyCallNode(src, srcPos, dest, destPos, length, superCheckOffset, destElemKlass, uninit, runtime);
    }

    protected CheckcastArrayCopyCallNode(ValueNode src, ValueNode srcPos, ValueNode dest, ValueNode destPos, ValueNode length, ValueNode superCheckOffset, ValueNode destElemKlass, boolean uninit,
                    HotSpotGraalRuntimeProvider runtime) {
        super(StampFactory.forKind(Kind.Int));
        this.src = src;
        this.srcPos = srcPos;
        this.dest = dest;
        this.destPos = destPos;
        this.length = length;
        this.superCheckOffset = superCheckOffset;
        this.destElemKlass = destElemKlass;
        this.uninit = uninit;
        this.runtime = runtime;
    }

    public ValueNode getSource() {
        return src;
    }

    public ValueNode getSourcePosition() {
        return srcPos;
    }

    public ValueNode getDestination() {
        return dest;
    }

    public ValueNode getDestinationPosition() {
        return destPos;
    }

    public ValueNode getLength() {
        return length;
    }

    public boolean isUninit() {
        return uninit;
    }

    private ValueNode computeBase(ValueNode base, ValueNode pos) {
        FixedWithNextNode basePtr = graph().add(GetObjectAddressNode.create(base));
        graph().addBeforeFixed(this, basePtr);
        ValueNode loc = IndexedLocationNode.create(getLocationIdentity(), Kind.Object, runtime.getArrayBaseOffset(Kind.Object), pos, graph(), runtime.getArrayIndexScale(Kind.Object));
        return graph().unique(ComputeAddressNode.create(basePtr, loc, StampFactory.forKind(Kind.Long)));
    }

    @Override
    public void lower(LoweringTool tool) {
        if (graph().getGuardsStage() == StructuredGraph.GuardsStage.AFTER_FSA) {
            ForeignCallDescriptor desc = HotSpotHostForeignCallsProvider.lookupCheckcastArraycopyDescriptor(isUninit());
            StructuredGraph graph = graph();
            ValueNode srcAddr = computeBase(getSource(), getSourcePosition());
            ValueNode destAddr = computeBase(getDestination(), getDestinationPosition());
            ValueNode len = getLength();
            if (len.stamp().getStackKind() != Kind.Long) {
                len = IntegerConvertNode.convert(len, StampFactory.forKind(Kind.Long), graph());
            }
            ForeignCallNode call = graph.add(ForeignCallNode.create(Graal.getRequiredCapability(RuntimeProvider.class).getHostBackend().getForeignCalls(), desc, srcAddr, destAddr, len,
                            superCheckOffset, destElemKlass));
            call.setStateAfter(stateAfter());
            graph.replaceFixedWithFixed(this, call);
        }
    }

    @Override
    public LocationIdentity getLocationIdentity() {
        return NamedLocationIdentity.getArrayLocation(Kind.Object);
    }

    @NodeIntrinsic
    public static native int checkcastArraycopy(Object src, int srcPos, Object dest, int destPos, int length, int superCheckOffset, Object destElemKlass, @ConstantNodeParameter boolean uninit);
}
