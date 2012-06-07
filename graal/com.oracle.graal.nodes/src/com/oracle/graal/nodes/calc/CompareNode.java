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

import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.max.cri.ri.*;

/* TODO (thomaswue/gdub) For high-level optimization purpose the compare node should be a boolean *value* (it is currently only a helper node)
 * But in the back-end the comparison should not always be materialized (for example in x86 the comparison result will not be in a register but in a flag)
 *
 * Compare should probably be made a value (so that it can be canonicalized for example) and in later stages some Compare usage should be transformed
 * into variants that do not materialize the value (CompareIf, CompareGuard...)
 */
public abstract class CompareNode extends BooleanNode implements Canonicalizable, LIRLowerable {

    @Input private ValueNode x;
    @Input private ValueNode y;

    public ValueNode x() {
        return x;
    }

    public ValueNode y() {
        return y;
    }

    /**
     * Constructs a new Compare instruction.
     *
     * @param x the instruction producing the first input to the instruction
     * @param y the instruction that produces the second input to this instruction
     */
    public CompareNode(ValueNode x, ValueNode y) {
        super(StampFactory.condition());
        assert (x == null && y == null) || x.kind() == y.kind();
        this.x = x;
        this.y = y;
    }

    /**
     * Gets the condition (comparison operation) for this instruction.
     *
     * @return the condition
     */
    public abstract Condition condition();

    /**
     * Checks whether unordered inputs mean true or false (only applies to float operations).
     *
     * @return {@code true} if unordered inputs produce true
     */
    public abstract boolean unorderedIsTrue();

    @Override
    public void generate(LIRGeneratorTool gen) {
    }


    private ValueNode optimizeMaterialize(RiConstant constant, MaterializeNode materializeNode, RiRuntime runtime, Condition cond) {
        RiConstant trueConstant = materializeNode.trueValue().asConstant();
        RiConstant falseConstant = materializeNode.falseValue().asConstant();

        if (falseConstant != null && trueConstant != null) {
            Boolean trueResult = cond.foldCondition(trueConstant, constant, runtime, unorderedIsTrue());
            Boolean falseResult = cond.foldCondition(falseConstant, constant, runtime, unorderedIsTrue());

            if (trueResult != null && falseResult != null) {
                boolean trueUnboxedResult = trueResult;
                boolean falseUnboxedResult = falseResult;
                if (trueUnboxedResult == falseUnboxedResult) {
                    return ConstantNode.forBoolean(trueUnboxedResult, graph());
                } else {
                    if (trueUnboxedResult) {
                        assert falseUnboxedResult == false;
                        return materializeNode.condition();
                    } else {
                        assert falseUnboxedResult == true;
                        negateUsages();
                        return materializeNode.condition();

                    }
                }
            }
        }
        return this;
    }

    protected ValueNode optimizeNormalizeCmp(RiConstant constant, NormalizeCompareNode normalizeNode, boolean mirrored) {
        throw new GraalInternalError("NormalizeCompareNode connected to %s (%s %s %s)", this, constant, normalizeNode, mirrored);
    }

    public ValueNode canonical(CanonicalizerTool tool) {
        if (x().isConstant() && y().isConstant()) {
            return ConstantNode.forBoolean(condition().foldCondition(x().asConstant(), y().asConstant(), tool.runtime(), unorderedIsTrue()), graph());
        }
        if (x().isConstant()) {
            if (y() instanceof MaterializeNode) {
                return optimizeMaterialize(x().asConstant(), (MaterializeNode) y(), tool.runtime(), condition().mirror());
            } else if (y() instanceof NormalizeCompareNode) {
                return optimizeNormalizeCmp(x().asConstant(), (NormalizeCompareNode) y(), true);
            }
        } else if (y().isConstant()) {
            if (x() instanceof MaterializeNode) {
                return optimizeMaterialize(y().asConstant(), (MaterializeNode) x(), tool.runtime(), condition());
            } else if (x() instanceof NormalizeCompareNode) {
                return optimizeNormalizeCmp(y().asConstant(), (NormalizeCompareNode) x(), false);
            }
        }
        return this;
    }
}
