/*
 * Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
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

import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

/**
 * The {@code AbstractNewArrayNode} is used for all 1-dimensional array allocations.
 */
public class AbstractNewArrayNode extends DeoptimizingFixedWithNextNode implements Canonicalizable, Lowerable, ArrayLengthProvider {

    @Input private ValueNode length;
    private final boolean fillContents;

    @Override
    public ValueNode length() {
        return length;
    }

    /**
     * Constructs a new AbstractNewArrayNode.
     * 
     * @param stamp the stamp of the newly created array
     * @param length the node that produces the length for this allocation.
     * @param fillContents determines whether the array elements should be initialized to zero/null.
     */
    protected AbstractNewArrayNode(Stamp stamp, ValueNode length, boolean fillContents) {
        super(stamp);
        this.length = length;
        this.fillContents = fillContents;
    }

    /**
     * @return <code>true</code> if the elements of the array will be initialized.
     */
    public boolean fillContents() {
        return fillContents;
    }

    /**
     * The list of node which produce input for this instruction.
     */
    public ValueNode dimension(int index) {
        assert index == 0;
        return length();
    }

    /**
     * The rank of the array allocated by this node, i.e. how many array dimensions.
     */
    public int dimensionCount() {
        return 1;
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (usages().isEmpty()) {
            Stamp stamp = length.stamp();
            if (stamp instanceof IntegerStamp && ((IntegerStamp) stamp).isPositive()) {
                return null;
            }
        }
        return this;
    }

    @Override
    public void lower(LoweringTool tool) {
        tool.getCodeCache().lower(this, tool);
    }

    @Override
    public boolean canDeoptimize() {
        return true;
    }
}
