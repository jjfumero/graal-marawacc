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
package com.oracle.graal.nodes;

import jdk.vm.ci.code.BytecodePosition;
import jdk.vm.ci.code.InfopointReason;

import com.oracle.graal.graph.IterableNodeType;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.graph.spi.Simplifiable;
import com.oracle.graal.graph.spi.SimplifierTool;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.spi.LIRLowerable;
import com.oracle.graal.nodes.spi.NodeLIRBuilderTool;

@NodeInfo
public final class SimpleInfopointNode extends InfopointNode implements LIRLowerable, IterableNodeType, Simplifiable {
    public static final NodeClass<SimpleInfopointNode> TYPE = NodeClass.create(SimpleInfopointNode.class);
    protected BytecodePosition position;

    public SimpleInfopointNode(InfopointReason reason, BytecodePosition position) {
        super(TYPE, reason);
        this.position = position;
    }

    @Override
    public void generate(NodeLIRBuilderTool generator) {
        generator.visitSimpleInfopointNode(this);
    }

    public BytecodePosition getPosition() {
        return position;
    }

    public void addCaller(BytecodePosition caller) {
        this.position = position.addCaller(caller);
    }

    @Override
    public void simplify(SimplifierTool tool) {
        if (next() instanceof SimpleInfopointNode) {
            graph().removeFixed(this);
        }
    }

    public void setPosition(BytecodePosition position) {
        this.position = position;
    }

    @Override
    public boolean verify() {
        BytecodePosition pos = position;
        if (pos != null) {
            // Verify that the outermost position belongs to this graph.
            while (pos.getCaller() != null) {
                pos = pos.getCaller();
            }
            assert pos.getMethod().equals(graph().method());
        }
        return super.verify();
    }
}
