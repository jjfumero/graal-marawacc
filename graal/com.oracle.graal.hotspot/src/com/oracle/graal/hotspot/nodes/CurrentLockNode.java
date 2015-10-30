/*
 * Copyright (c) 2012, 2015, Oracle and/or its affiliates. All rights reserved.
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

import jdk.vm.ci.meta.Value;

import com.oracle.graal.compiler.common.type.StampFactory;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.hotspot.HotSpotLIRGenerator;
import com.oracle.graal.lir.VirtualStackSlot;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.FixedWithNextNode;
import com.oracle.graal.nodes.spi.LIRLowerable;
import com.oracle.graal.nodes.spi.NodeLIRBuilderTool;
import com.oracle.graal.word.Word;
import com.oracle.graal.word.WordTypes;

/**
 * Intrinsic for getting the lock in the current {@linkplain BeginLockScopeNode lock scope}.
 */
@NodeInfo
public final class CurrentLockNode extends FixedWithNextNode implements LIRLowerable {
    public static final NodeClass<CurrentLockNode> TYPE = NodeClass.create(CurrentLockNode.class);

    protected int lockDepth;

    public CurrentLockNode(@InjectedNodeParameter WordTypes wordTypes, int lockDepth) {
        super(TYPE, StampFactory.forKind(wordTypes.getWordKind()));
        this.lockDepth = lockDepth;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        assert lockDepth != -1;
        HotSpotLIRGenerator hsGen = (HotSpotLIRGenerator) gen.getLIRGeneratorTool();
        VirtualStackSlot slot = hsGen.getLockSlot(lockDepth);
        // The register allocator cannot handle stack -> register moves so we use an LEA here
        Value result = gen.getLIRGeneratorTool().emitAddress(slot);
        gen.setResult(this, result);
    }

    @NodeIntrinsic
    public static native Word currentLock(@ConstantNodeParameter int lockDepth);
}
