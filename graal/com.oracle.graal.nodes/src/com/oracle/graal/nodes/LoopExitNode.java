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
package com.oracle.graal.nodes;

import com.oracle.graal.graph.IterableNodeType;
import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.graph.spi.SimplifierTool;
import com.oracle.graal.nodeinfo.InputType;
import com.oracle.graal.nodeinfo.NodeInfo;

@NodeInfo(allowedUsageTypes = {InputType.Association})
public final class LoopExitNode extends BeginStateSplitNode implements IterableNodeType {

    public static final NodeClass<LoopExitNode> TYPE = NodeClass.create(LoopExitNode.class);
    @Input(InputType.Association) LoopBeginNode loopBegin;

    public LoopExitNode(LoopBeginNode loop) {
        super(TYPE);
        assert loop != null;
        loopBegin = loop;
    }

    public LoopBeginNode loopBegin() {
        return loopBegin;
    }

    @Override
    public void simplify(SimplifierTool tool) {
        Node prev = this.predecessor();
        while (tool.allUsagesAvailable() && prev instanceof BeginNode && prev.hasNoUsages()) {
            AbstractBeginNode begin = (AbstractBeginNode) prev;
            prev = prev.predecessor();
            graph().removeFixed(begin);
        }
    }
}
