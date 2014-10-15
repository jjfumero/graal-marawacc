/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.java;

import sun.misc.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.spi.*;

/**
 * Represents the lowered version of an atomic read-and-write operation like
 * {@link Unsafe#getAndSetInt(Object, long, int)} .
 */
@NodeInfo(allowedUsageTypes = {InputType.Memory})
public class LoweredAtomicReadAndWriteNode extends FixedAccessNode implements StateSplit, LIRLowerable, MemoryCheckpoint.Single {

    @Input ValueNode newValue;
    @OptionalInput(InputType.State) FrameState stateAfter;

    public static LoweredAtomicReadAndWriteNode create(ValueNode object, LocationNode location, ValueNode newValue, BarrierType barrierType) {
        return new LoweredAtomicReadAndWriteNode(object, location, newValue, barrierType);
    }

    protected LoweredAtomicReadAndWriteNode(ValueNode object, LocationNode location, ValueNode newValue, BarrierType barrierType) {
        super(object, location, newValue.stamp().unrestricted(), barrierType);
        this.newValue = newValue;
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

    public LocationIdentity getLocationIdentity() {
        return location().getLocationIdentity();
    }

    public void generate(NodeLIRBuilderTool gen) {
        Value address = location().generateAddress(gen, gen.getLIRGeneratorTool(), gen.operand(object()));
        Value result = gen.getLIRGeneratorTool().emitAtomicReadAndWrite(address, gen.operand(getNewValue()));
        gen.setResult(this, result);
    }

    public boolean canNullCheck() {
        return false;
    }

    public final ValueNode getNewValue() {
        return newValue;
    }
}
