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
package com.oracle.graal.nodes.java;

import jdk.internal.jvmci.meta.*;

import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.lir.gen.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.memory.*;
import com.oracle.graal.nodes.memory.address.*;
import com.oracle.graal.nodes.spi.*;

/**
 * Represents the lowered version of an atomic compare-and-swap operation{@code CompareAndSwapNode}.
 */
@NodeInfo(allowedUsageTypes = {InputType.Value, InputType.Memory})
public final class LoweredCompareAndSwapNode extends FixedAccessNode implements StateSplit, LIRLowerable, MemoryCheckpoint.Single {

    public static final NodeClass<LoweredCompareAndSwapNode> TYPE = NodeClass.create(LoweredCompareAndSwapNode.class);
    @Input ValueNode expectedValue;
    @Input ValueNode newValue;
    @OptionalInput(InputType.State) FrameState stateAfter;

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

    public ValueNode getExpectedValue() {
        return expectedValue;
    }

    public ValueNode getNewValue() {
        return newValue;
    }

    public LoweredCompareAndSwapNode(AddressNode address, LocationIdentity location, ValueNode expectedValue, ValueNode newValue, BarrierType barrierType) {
        super(TYPE, address, location, StampFactory.forKind(JavaKind.Boolean.getStackKind()), barrierType);
        assert expectedValue.getStackKind() == newValue.getStackKind();
        this.expectedValue = expectedValue;
        this.newValue = newValue;
    }

    public boolean canNullCheck() {
        return false;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        assert getNewValue().stamp().isCompatible(getExpectedValue().stamp());
        LIRGeneratorTool tool = gen.getLIRGeneratorTool();

        LIRKind resultKind = tool.getLIRKind(stamp());
        Value trueResult = tool.emitConstant(resultKind, JavaConstant.TRUE);
        Value falseResult = tool.emitConstant(resultKind, JavaConstant.FALSE);
        Value result = tool.emitCompareAndSwap(gen.operand(getAddress()), gen.operand(getExpectedValue()), gen.operand(getNewValue()), trueResult, falseResult);

        gen.setResult(this, result);
    }
}
