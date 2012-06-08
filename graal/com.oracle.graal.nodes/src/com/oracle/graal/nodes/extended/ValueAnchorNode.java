/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.graph.iterators.NodePredicates.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

/**
 * The ValueAnchor instruction keeps non-CFG (floating) nodes above a certain point in the graph.
 */

public final class ValueAnchorNode extends FixedWithNextNode implements Canonicalizable, LIRLowerable, Node.IterableNodeType {

    public ValueAnchorNode(ValueNode... values) {
        super(StampFactory.dependency(), values);
    }

    @Override
    public void generate(LIRGeneratorTool gen) {
        // Nothing to emit, since this node is used for structural purposes only.
    }

    public void addAnchoredNode(ValueNode value) {
        if (!this.dependencies().contains(value)) {
            this.dependencies().add(value);
        }
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        if (this.predecessor() instanceof ValueAnchorNode) {
            // transfer values and remove
            ValueAnchorNode previousAnchor = (ValueAnchorNode) this.predecessor();
            for (ValueNode node : dependencies().nonNull().distinct()) {
                previousAnchor.addAnchoredNode(node);
            }
            return null;
        }
        for (Node node : dependencies().nonNull().and(isNotA(BeginNode.class))) {
            if (node instanceof ConstantNode) {
                continue;
            }
            if (node instanceof IntegerDivNode || node instanceof IntegerRemNode) {
                ArithmeticNode arithmeticNode = (ArithmeticNode) node;
                if (arithmeticNode.y().isConstant()) {
                    Constant  constant = arithmeticNode.y().asConstant();
                    assert constant.kind == arithmeticNode.kind() : constant.kind + " != " + arithmeticNode.kind();
                    if (constant.asLong() != 0) {
                        continue;
                    }
                }
            }
            return this; // still necessary
        }
        return null; // no node which require an anchor found
    }
}
