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
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

/**
 * Writes a given {@linkplain #value() value} a {@linkplain AccessNode memory location}.
 */
public final class WriteNode extends AccessNode implements StateSplit, LIRLowerable, MemoryCheckpoint, Node.IterableNodeType {

    @Input private ValueNode value;
    @Input(notDataflow = true) private FrameState stateAfter;
    private final WriteBarrierType barrierType;

    /*
     * The types of write barriers attached to stores.
     */
    public enum WriteBarrierType {
        /*
         * Primitive stores which do not necessitate write barriers.
         */
        NONE,
        /*
         * Array object stores which necessitate precise write barriers.
         */
        PRECISE,
        /*
         * Field object stores which necessitate imprecise write barriers.
         */
        IMPRECISE
    }

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

    public WriteBarrierType getWriteBarrierType() {
        return barrierType;
    }

    public WriteNode(ValueNode object, ValueNode value, ValueNode location, WriteBarrierType barrierType) {
        super(object, location, StampFactory.forVoid());
        this.value = value;
        this.barrierType = barrierType;
    }

    @Override
    public void generate(LIRGeneratorTool gen) {
        Value address = location().generateAddress(gen, gen.operand(object()));
        gen.emitStore(location().getValueKind(), address, gen.operand(value()), this);
    }

    @NodeIntrinsic
    public static native void writeMemory(Object object, Object value, Object location, @ConstantNodeParameter WriteBarrierType barrierType);

    @Override
    public Object[] getLocationIdentities() {
        return new Object[]{location().locationIdentity()};
    }
}
