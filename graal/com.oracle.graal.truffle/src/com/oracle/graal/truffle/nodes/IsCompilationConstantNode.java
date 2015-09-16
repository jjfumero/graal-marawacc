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
package com.oracle.graal.truffle.nodes;

import jdk.internal.jvmci.meta.JavaKind;

import com.oracle.graal.compiler.common.type.StampFactory;
import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.graph.spi.Canonicalizable;
import com.oracle.graal.graph.spi.CanonicalizerTool;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.ConstantNode;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.calc.FloatingNode;
import com.oracle.graal.nodes.extended.BoxNode;
import com.oracle.graal.nodes.spi.Lowerable;
import com.oracle.graal.nodes.spi.LoweringTool;

@NodeInfo
public final class IsCompilationConstantNode extends FloatingNode implements Lowerable, Canonicalizable {

    public static final NodeClass<IsCompilationConstantNode> TYPE = NodeClass.create(IsCompilationConstantNode.class);

    @Input ValueNode value;

    public IsCompilationConstantNode(ValueNode value) {
        super(TYPE, StampFactory.forKind(JavaKind.Boolean));
        this.value = value;
    }

    @Override
    public void lower(LoweringTool tool) {
        graph().replaceFloating(this, ConstantNode.forBoolean(false, graph()));
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        ValueNode arg0 = value;
        if (arg0 instanceof BoxNode) {
            arg0 = ((BoxNode) arg0).getValue();
        }
        if (arg0.isConstant()) {
            return ConstantNode.forBoolean(true);
        }
        return this;
    }

    @NodeIntrinsic
    public static native boolean check(Object value);
}
