/*
 * Copyright (c) 2009, 2015, Oracle and/or its affiliates. All rights reserved.
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

import jdk.internal.jvmci.code.BytecodeFrame;
import jdk.internal.jvmci.meta.LocationIdentity;

import com.oracle.graal.graph.IterableNodeType;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.graph.spi.Simplifiable;
import com.oracle.graal.graph.spi.SimplifierTool;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.extended.MonitorExit;
import com.oracle.graal.nodes.memory.MemoryCheckpoint;
import com.oracle.graal.nodes.spi.Lowerable;
import com.oracle.graal.nodes.spi.LoweringTool;
import com.oracle.graal.nodes.spi.Virtualizable;
import com.oracle.graal.nodes.spi.VirtualizerTool;
import com.oracle.graal.nodes.virtual.VirtualObjectNode;

/**
 * The {@code MonitorExitNode} represents a monitor release. If it is the release of the monitor of
 * a synchronized method, then the return value of the method will be referenced, so that it will be
 * materialized before releasing the monitor.
 */
@NodeInfo
public final class MonitorExitNode extends AccessMonitorNode implements Virtualizable, Simplifiable, Lowerable, IterableNodeType, MonitorExit, MemoryCheckpoint.Single {

    public static final NodeClass<MonitorExitNode> TYPE = NodeClass.create(MonitorExitNode.class);
    @OptionalInput ValueNode escapedReturnValue;

    public MonitorExitNode(ValueNode object, MonitorIdNode monitorId, ValueNode escapedReturnValue) {
        super(TYPE, object, monitorId);
        this.escapedReturnValue = escapedReturnValue;
    }

    public ValueNode getEscapedReturnValue() {
        return escapedReturnValue;
    }

    public void setEscapedReturnValue(ValueNode x) {
        updateUsages(escapedReturnValue, x);
        this.escapedReturnValue = x;
    }

    @Override
    public LocationIdentity getLocationIdentity() {
        return LocationIdentity.any();
    }

    @Override
    public void simplify(SimplifierTool tool) {
        if (escapedReturnValue != null && stateAfter() != null && stateAfter().bci != BytecodeFrame.AFTER_BCI) {
            ValueNode returnValue = escapedReturnValue;
            setEscapedReturnValue(null);
            tool.removeIfUnused(returnValue);
        }
    }

    @Override
    public void lower(LoweringTool tool) {
        tool.getLowerer().lower(this, tool);
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        // the monitor exit for a synchronized method should never be virtualized
        ValueNode alias = tool.getAlias(object());
        if (alias instanceof VirtualObjectNode) {
            VirtualObjectNode virtual = (VirtualObjectNode) alias;
            if (virtual.hasIdentity()) {
                MonitorIdNode removedLock = tool.removeLock(virtual);
                assert removedLock == getMonitorId() : "mismatch at " + this + ": " + removedLock + " vs. " + getMonitorId();
                tool.delete();
            }
        }
    }
}
