/*
 * Copyright (c) 2011, 2012, Oracle and/or its affiliates. All rights reserved.
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

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.LocationNode.Location;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.virtual.*;

/**
 * Writes a given {@linkplain #value() value} a {@linkplain AccessNode memory location}.
 */
public final class WriteNode extends AccessNode implements StateSplit, LIRLowerable, MemoryCheckpoint.Single, Node.IterableNodeType, Virtualizable {

    @Input private ValueNode value;
    @Input(notDataflow = true) private FrameState stateAfter;
    private final boolean initialized;

    public FrameState stateAfter() {
        return stateAfter;
    }

    public void setStateAfter(FrameState x) {
        assert x == null || x.isAlive() : "frame state must be in a graph";
        updateUsages(stateAfter, x);
        stateAfter = x;
    }

    public boolean hasSideEffect() {
        return true;
    }

    public ValueNode value() {
        return value;
    }

    /**
     * If {@link #isInitialized()} is true, the memory location contains a valid value. If
     * {@link #isInitialized()} is false, the memory location is uninitialized or zero.
     */
    public boolean isInitialized() {
        return initialized;
    }

    public WriteNode(ValueNode object, ValueNode value, ValueNode location, BarrierType barrierType, boolean compressible) {
        this(object, value, location, barrierType, compressible, true);
    }

    public WriteNode(ValueNode object, ValueNode value, ValueNode location, BarrierType barrierType, boolean compressible, boolean initialized) {
        super(object, location, StampFactory.forVoid(), barrierType, compressible);
        this.value = value;
        this.initialized = initialized;
    }

    @Override
    public void generate(LIRGeneratorTool gen) {
        Value address = location().generateAddress(gen, gen.operand(object()));
        gen.emitStore(location().getValueKind(), address, gen.operand(value()), this);
    }

    @NodeIntrinsic
    public static native void writeMemory(Object object, Object value, Location location, @ConstantNodeParameter BarrierType barrierType, @ConstantNodeParameter boolean compressible);

    @Override
    public LocationIdentity getLocationIdentity() {
        return location().getLocationIdentity();
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        if (location() instanceof ConstantLocationNode) {
            ConstantLocationNode constantLocation = (ConstantLocationNode) location();
            State state = tool.getObjectState(object());
            if (state != null && state.getState() == EscapeState.Virtual) {
                VirtualObjectNode virtual = state.getVirtualObject();
                int entryIndex = virtual.entryIndexForOffset(constantLocation.getDisplacement());
                if (entryIndex != -1 && virtual.entryKind(entryIndex) == constantLocation.getValueKind()) {
                    tool.setVirtualEntry(state, entryIndex, value());
                    tool.delete();
                }
            }
        }
    }
}
