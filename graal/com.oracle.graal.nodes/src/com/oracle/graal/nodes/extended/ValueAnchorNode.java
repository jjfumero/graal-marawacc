/*
 * Copyright (c) 2011, 2015, Oracle and/or its affiliates. All rights reserved.
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

import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.util.*;

/**
 * The ValueAnchor instruction keeps non-CFG (floating) nodes above a certain point in the graph.
 */
@NodeInfo(allowedUsageTypes = {InputType.Anchor, InputType.Guard})
public final class ValueAnchorNode extends FixedWithNextNode implements LIRLowerable, Simplifiable, Virtualizable, AnchoringNode, GuardingNode {

    public static final NodeClass<ValueAnchorNode> TYPE = NodeClass.create(ValueAnchorNode.class);
    @OptionalInput(InputType.Guard) ValueNode anchored;

    public ValueAnchorNode(ValueNode value) {
        super(TYPE, StampFactory.forVoid());
        this.anchored = value;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        // Nothing to emit, since this node is used for structural purposes only.
    }

    public ValueNode getAnchoredNode() {
        return anchored;
    }

    @Override
    public void simplify(SimplifierTool tool) {
        while (next() instanceof ValueAnchorNode) {
            ValueAnchorNode nextAnchor = (ValueAnchorNode) next();
            if (nextAnchor.anchored == anchored || nextAnchor.anchored == null) {
                // two anchors for the same anchored -> coalesce
                // nothing anchored on the next anchor -> coalesce
                nextAnchor.replaceAtUsages(this);
                GraphUtil.removeFixedWithUnusedInputs(nextAnchor);
            } else {
                break;
            }
        }
        if (tool.allUsagesAvailable() && hasNoUsages() && next() instanceof FixedAccessNode) {
            FixedAccessNode currentNext = (FixedAccessNode) next();
            if (currentNext.getGuard() == anchored) {
                GraphUtil.removeFixedWithUnusedInputs(this);
                return;
            } else if (currentNext.getGuard() == null && anchored instanceof GuardNode && ((GuardNode) anchored).condition() instanceof IsNullNode) {
                // coalesce null check guards into subsequent read/write
                currentNext.setGuard((GuardingNode) anchored);
                tool.addToWorkList(next());
                return;
            }
        }

        if (anchored != null && (anchored.isConstant() || anchored instanceof FixedNode)) {
            // anchoring fixed nodes and constants is useless
            removeAnchoredNode();
        }

        if (anchored == null && hasNoUsages()) {
            // anchor is not necessary any more => remove.
            GraphUtil.removeFixedWithUnusedInputs(this);
        }
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        if (anchored != null && !(anchored instanceof AbstractBeginNode)) {
            State state = tool.getObjectState(anchored);
            if (state == null || state.getState() != EscapeState.Virtual) {
                return;
            }
        }
        tool.delete();
    }

    public void removeAnchoredNode() {
        this.updateUsages(anchored, null);
        this.anchored = null;
    }
}
