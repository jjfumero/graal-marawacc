/*
 * Copyright (c) 2011, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.calc;

import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

public class IntegerTestNode extends BooleanNode implements Canonicalizable, LIRLowerable {

    @Input private ValueNode x;
    @Input private ValueNode y;

    public ValueNode x() {
        return x;
    }

    public ValueNode y() {
        return y;
    }

    /**
     * Constructs a new Test instruction.
     * 
     * @param x the instruction producing the first input to the instruction
     * @param y the instruction that produces the second input to this instruction
     */
    public IntegerTestNode(ValueNode x, ValueNode y) {
        super(StampFactory.condition());
        assert (x == null && y == null) || x.kind() == y.kind();
        this.x = x;
        this.y = y;
    }

    @Override
    public void generate(LIRGeneratorTool gen) {
    }

    public ValueNode canonical(CanonicalizerTool tool) {
        if (x().isConstant() && y().isConstant()) {
            return ConstantNode.forBoolean((x().asConstant().asLong() & y().asConstant().asLong()) == 0, graph());
        }
        if ((x().integerStamp().mask() & y().integerStamp().mask()) == 0) {
            return ConstantNode.forBoolean(true, graph());
        }
        return this;
    }
}
