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
package com.oracle.graal.hotspot.nodes;

import static com.oracle.graal.hotspot.HotSpotBackend.UNCOMMON_TRAP;

import com.oracle.graal.compiler.common.spi.ForeignCallsProvider;
import com.oracle.graal.compiler.common.type.StampFactory;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.hotspot.HotSpotLIRGenerator;
import com.oracle.graal.lir.StandardOp.SaveRegistersOp;
import com.oracle.graal.nodeinfo.InputType;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.FixedWithNextNode;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.memory.MemoryCheckpoint;
import com.oracle.graal.nodes.spi.LIRLowerable;
import com.oracle.graal.nodes.spi.NodeLIRBuilderTool;
import com.oracle.graal.word.Word;

import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.LocationIdentity;
import jdk.vm.ci.meta.Value;

/**
 * A call to the runtime code implementing the uncommon trap logic.
 */
@NodeInfo(allowedUsageTypes = {InputType.Memory})
public final class UncommonTrapCallNode extends FixedWithNextNode implements LIRLowerable, MemoryCheckpoint.Single {

    public static final NodeClass<UncommonTrapCallNode> TYPE = NodeClass.create(UncommonTrapCallNode.class);
    @Input ValueNode trapRequest;
    @Input ValueNode mode;
    @Input SaveAllRegistersNode registerSaver;
    protected final ForeignCallsProvider foreignCalls;

    public UncommonTrapCallNode(@InjectedNodeParameter ForeignCallsProvider foreignCalls, ValueNode registerSaver, ValueNode trapRequest, ValueNode mode) {
        super(TYPE, StampFactory.forKind(JavaKind.fromJavaClass(UNCOMMON_TRAP.getResultType())));
        this.trapRequest = trapRequest;
        this.mode = mode;
        this.registerSaver = (SaveAllRegistersNode) registerSaver;
        this.foreignCalls = foreignCalls;
    }

    @Override
    public LocationIdentity getLocationIdentity() {
        return LocationIdentity.any();
    }

    public SaveRegistersOp getSaveRegistersOp() {
        return registerSaver.getSaveRegistersOp();
    }

    /**
     * Returns the node representing the exec_mode/unpack_kind used during this fetch_unroll_info
     * call.
     */
    public ValueNode getMode() {
        return mode;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        Value trapRequestValue = gen.operand(trapRequest);
        Value modeValue = gen.operand(getMode());
        Value result = ((HotSpotLIRGenerator) gen.getLIRGeneratorTool()).emitUncommonTrapCall(trapRequestValue, modeValue, getSaveRegistersOp());
        gen.setResult(this, result);
    }

    @NodeIntrinsic
    public static native Word uncommonTrap(long registerSaver, int trapRequest, int mode);
}
