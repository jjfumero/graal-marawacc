/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes;

import jdk.internal.jvmci.code.InfopointReason;

import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.graph.spi.Simplifiable;
import com.oracle.graal.graph.spi.SimplifierTool;
import com.oracle.graal.nodeinfo.InputType;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.spi.LIRLowerable;
import com.oracle.graal.nodes.spi.NodeLIRBuilderTool;
import com.oracle.graal.nodes.spi.NodeWithState;

/**
 * Nodes of this type are inserted into the graph to denote points of interest to debugging.
 */
@NodeInfo
public final class FullInfopointNode extends InfopointNode implements LIRLowerable, NodeWithState, Simplifiable {
    public static final NodeClass<FullInfopointNode> TYPE = NodeClass.create(FullInfopointNode.class);
    @Input(InputType.State) FrameState state;
    @OptionalInput ValueNode escapedReturnValue;

    public FullInfopointNode(InfopointReason reason, FrameState state, ValueNode escapedReturnValue) {
        super(TYPE, reason);
        this.state = state;
        this.escapedReturnValue = escapedReturnValue;
    }

    private void setEscapedReturnValue(ValueNode x) {
        updateUsages(escapedReturnValue, x);
        escapedReturnValue = x;
    }

    @Override
    public void simplify(SimplifierTool tool) {
        if (escapedReturnValue != null && state != null && state.outerFrameState() != null) {
            ValueNode returnValue = escapedReturnValue;
            setEscapedReturnValue(null);
            tool.removeIfUnused(returnValue);
        }
    }

    @Override
    public void generate(NodeLIRBuilderTool generator) {
        generator.visitFullInfopointNode(this);
    }

    public FrameState getState() {
        return state;
    }

    @Override
    public boolean verify() {
        return state != null && super.verify();
    }

}
