/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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

import java.util.BitSet;

import jdk.vm.ci.code.VirtualStackSlot;
import jdk.vm.ci.meta.Value;

import com.oracle.graal.compiler.common.type.StampFactory;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.calc.FloatingNode;
import com.oracle.graal.nodes.spi.LIRLowerable;
import com.oracle.graal.nodes.spi.NodeLIRBuilderTool;
import com.oracle.graal.word.Word;
import com.oracle.graal.word.WordTypes;

/**
 * Node that is used to maintain a stack based counter of how many locks are currently held.
 */
@NodeInfo
public final class MonitorCounterNode extends FloatingNode implements LIRLowerable {
    public static final NodeClass<MonitorCounterNode> TYPE = NodeClass.create(MonitorCounterNode.class);

    public MonitorCounterNode(@InjectedNodeParameter WordTypes wordTypes) {
        super(TYPE, StampFactory.forKind(wordTypes.getWordKind()));
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        assert graph().getNodes().filter(MonitorCounterNode.class).count() == 1 : "monitor counters not canonicalized to single instance";
        VirtualStackSlot counter = gen.getLIRGeneratorTool().getResult().getFrameMapBuilder().allocateStackSlots(1, new BitSet(0), null);
        Value result = gen.getLIRGeneratorTool().emitAddress(counter);
        gen.setResult(this, result);
    }

    @NodeIntrinsic
    public static native Word counter();
}
