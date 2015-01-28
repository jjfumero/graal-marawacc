/*
 * Copyright (c) 2012, 2014, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.hotspot.HotSpotBackend.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.stubs.*;
import com.oracle.graal.hotspot.word.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;

/**
 * A call to the {@link NewArrayStub}.
 */
@NodeInfo
public final class NewArrayStubCall extends DeoptimizingStubCall implements LIRLowerable {

    private static final Stamp defaultStamp = StampFactory.objectNonNull();

    @Input ValueNode hub;
    @Input ValueNode length;

    public NewArrayStubCall(ValueNode hub, ValueNode length) {
        super(defaultStamp);
        this.hub = hub;
        this.length = length;
    }

    @Override
    public boolean inferStamp() {
        if (stamp() == defaultStamp && hub.isConstant()) {
            updateStamp(StampFactory.exactNonNull(((HotSpotMetaspaceConstant) hub.asJavaConstant()).asResolvedJavaType()));
            return true;
        }
        return false;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        ForeignCallLinkage linkage = gen.getLIRGeneratorTool().getForeignCalls().lookupForeignCall(NEW_ARRAY);
        Variable result = gen.getLIRGeneratorTool().emitForeignCall(linkage, gen.state(this), gen.operand(hub), gen.operand(length));
        gen.setResult(this, result);
    }

    @NodeIntrinsic
    public static native Object call(KlassPointer hub, int length);
}
