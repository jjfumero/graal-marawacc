/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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

import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.LocationIdentity;

import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.StateSplit;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.memory.AbstractWriteNode;
import com.oracle.graal.nodes.memory.MemoryAccess;
import com.oracle.graal.nodes.memory.MemoryCheckpoint;
import com.oracle.graal.nodes.memory.address.AddressNode;
import com.oracle.graal.nodes.spi.Lowerable;
import com.oracle.graal.nodes.spi.LoweringTool;

/**
 * Write a raw memory location according to Java field or array write semantics. It will perform
 * write barriers, implicit conversions and optionally oop compression.
 */
@NodeInfo(nameTemplate = "JavaWrite#{p#location/s}")
public final class JavaWriteNode extends AbstractWriteNode implements Lowerable, StateSplit, MemoryAccess, MemoryCheckpoint.Single {

    public static final NodeClass<JavaWriteNode> TYPE = NodeClass.create(JavaWriteNode.class);
    protected final JavaKind writeKind;
    protected final boolean compressible;

    public JavaWriteNode(JavaKind writeKind, AddressNode address, LocationIdentity location, ValueNode value, BarrierType barrierType, boolean compressible, boolean initialization) {
        super(TYPE, address, location, value, barrierType, initialization);
        this.writeKind = writeKind;
        this.compressible = compressible;
    }

    public void lower(LoweringTool tool) {
        tool.getLowerer().lower(this, tool);
    }

    public boolean canNullCheck() {
        return true;
    }

    public JavaKind getWriteKind() {
        return writeKind;
    }

    public boolean isCompressible() {
        return compressible;
    }
}
