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
package com.oracle.graal.hotspot.word;

import jdk.internal.jvmci.meta.Value;

import com.oracle.graal.compiler.common.type.Stamp;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.hotspot.word.HotSpotOperation.HotspotOpcode;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.calc.FloatingNode;
import com.oracle.graal.nodes.spi.LIRLowerable;
import com.oracle.graal.nodes.spi.NodeLIRBuilderTool;

/**
 * Cast between Word and metaspace pointers exposed by the {@link HotspotOpcode#FROM_POINTER} and
 * {@link HotspotOpcode#TO_KLASS_POINTER} operations.
 */
@NodeInfo
public final class PointerCastNode extends FloatingNode implements LIRLowerable {

    public static final NodeClass<PointerCastNode> TYPE = NodeClass.create(PointerCastNode.class);
    @Input ValueNode input;

    public PointerCastNode(Stamp stamp, ValueNode input) {
        super(TYPE, stamp);
        this.input = input;
    }

    public ValueNode getInput() {
        return input;
    }

    @Override
    public void generate(NodeLIRBuilderTool generator) {
        Value value = generator.operand(input);
        assert value.getLIRKind().equals(generator.getLIRGeneratorTool().getLIRKind(stamp())) : "PointerCastNode shouldn't change the LIRKind";

        generator.setResult(this, value);
    }
}
