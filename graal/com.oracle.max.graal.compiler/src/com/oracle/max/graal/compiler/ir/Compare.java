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
package com.oracle.max.graal.compiler.ir;

import java.util.*;

import com.oracle.max.graal.compiler.*;
import com.oracle.max.graal.compiler.debug.*;
import com.oracle.max.graal.compiler.graph.*;
import com.oracle.max.graal.compiler.phases.CanonicalizerPhase.*;
import com.oracle.max.graal.compiler.util.*;
import com.oracle.max.graal.graph.*;
import com.sun.cri.ci.*;

/* (tw/gd) For high-level optimization purpose the compare node should be a boolean *value* (it is currently only a helper node)
 * But in the back-end the comparison should not always be materialized (for example in x86 the comparison result will not be in a register but in a flag)
 *
 * Compare should probably be made a value (so that it can be canonicalized for example) and in later stages some Compare usage should be transformed
 * into variants that do not materialize the value (CompareIf, CompareGuard...)
 *
 */
public final class Compare extends BooleanNode {
    @Input private Value x;
    @Input private Value y;

    public Value x() {
        return x;
    }

    public void setX(Value x) {
        updateUsages(this.x, x);
        this.x = x;
    }

    public Value y() {
        return y;
    }

    public void setY(Value x) {
        updateUsages(y, x);
        this.y = x;
    }

    private Condition condition;
    private boolean unorderedIsTrue;

    /**
     * Constructs a new Compare instruction.
     * @param x the instruction producing the first input to the instruction
     * @param condition the condition (comparison operation)
     * @param y the instruction that produces the second input to this instruction
     * @param graph
     */
    public Compare(Value x, Condition condition, Value y, Graph graph) {
        super(CiKind.Illegal, graph);
        assert (x == null && y == null) || Util.archKindsEqual(x, y);
        this.condition = condition;
        setX(x);
        setY(y);
    }

    @Override
    public boolean valueEqual(Node i) {
        if (i instanceof Compare) {
            Compare compare = (Compare) i;
            return compare.condition == condition && compare.unorderedIsTrue == unorderedIsTrue;
        }
        return super.valueEqual(i);
    }

    /**
     * Gets the condition (comparison operation) for this instruction.
     * @return the condition
     */
    public Condition condition() {
        return condition;
    }

    /**
     * Checks whether unordered inputs mean true or false.
     * @return {@code true} if unordered inputs produce true
     */
    public boolean unorderedIsTrue() {
        return unorderedIsTrue;
    }

    /**
     * Swaps the operands to this if and mirrors the condition (e.g. > becomes <).
     * @see Condition#mirror()
     */
    public void swapOperands() {
        condition = condition.mirror();
        Value t = x();
        setX(y());
        setY(t);
    }

    public void negate() {
        condition = condition.negate();
        unorderedIsTrue = !unorderedIsTrue;
    }

    @Override
    public void accept(ValueVisitor v) {
    }

    @Override
    public void print(LogStream out) {
        out.print("comp ").
        print(x()).
        print(' ').
        print(condition().operator).
        print(' ').
        print(y());
    }

    @Override
    public String shortName() {
        return "Comp " + condition.operator;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends Op> T lookup(Class<T> clazz) {
        if (clazz == CanonicalizerOp.class) {
            return (T) CANONICALIZER;
        }
        return super.lookup(clazz);
    }

    @Override
    public Map<Object, Object> getDebugProperties() {
        Map<Object, Object> properties = super.getDebugProperties();
        properties.put("unorderedIsTrue", unorderedIsTrue());
        return properties;
    }

    @Override
    public Node copy(Graph into) {
        Compare x = new Compare(null, condition, null, into);
        x.unorderedIsTrue = unorderedIsTrue;
        return x;
    }

    private static CanonicalizerOp CANONICALIZER = new CanonicalizerOp() {
        @Override
        public Node canonical(Node node, NotifyReProcess reProcess) {
            Compare compare = (Compare) node;
            if (compare.x().isConstant() && !compare.y().isConstant()) { // move constants to the left (y)
                compare.swapOperands();
            } else if (compare.x().isConstant() && compare.y().isConstant()) {
                CiConstant constX = compare.x().asConstant();
                CiConstant constY = compare.y().asConstant();
                Boolean result = compare.condition().foldCondition(constX, constY, ((CompilerGraph) node.graph()).runtime(), compare.unorderedIsTrue());
                if (result != null) {
                    if (GraalOptions.TraceCanonicalizer) {
                        TTY.println("folded condition " + constX + " " + compare.condition() + " " + constY);
                    }
                    return Constant.forBoolean(result, compare.graph());
                } else {
                    if (GraalOptions.TraceCanonicalizer) {
                        TTY.println("if not removed %s %s %s (%s %s)", constX, compare.condition(), constY, constX.kind, constY.kind);
                    }
                }
            }

            if (compare.y().isConstant()) {
                if (compare.x() instanceof MaterializeNode) {
                    return optimizeMaterialize(compare, compare.y().asConstant(), (MaterializeNode) compare.x());
                } else if (compare.x() instanceof NormalizeCompare) {
                    return optimizeNormalizeCmp(compare, compare.y().asConstant(), (NormalizeCompare) compare.x());
                }
            }

            if (compare.x() == compare.y() && compare.x().kind != CiKind.Float && compare.x().kind != CiKind.Double) {
                return Constant.forBoolean(compare.condition().check(1, 1), compare.graph());
            }
            if ((compare.condition == Condition.NE || compare.condition == Condition.EQ) && compare.x().kind == CiKind.Object) {
                Value object = null;
                if (compare.x().isNullConstant()) {
                    object = compare.y();
                } else if (compare.y().isNullConstant()) {
                    object = compare.x();
                }
                if (object != null) {
                    IsNonNull nonNull =  new IsNonNull(object, compare.graph());
                    if (compare.condition == Condition.NE) {
                        return nonNull;
                    } else {
                        assert compare.condition == Condition.EQ;
                        return new NegateBooleanNode(nonNull, compare.graph());
                    }
                }
            }
            boolean allUsagesNegate = true;
            for (Node usage : compare.usages()) {
                if (!(usage instanceof NegateBooleanNode)) {
                    allUsagesNegate = false;
                    break;
                }
            }
            if (allUsagesNegate) {
                compare.negate();
                for (Node usage : compare.usages().snapshot()) {
                    usage.replaceAtUsages(compare);
                }
            }
            return compare;
        }

        private Node optimizeMaterialize(Compare compare, CiConstant constant, MaterializeNode materializeNode) {
            if (constant.kind == CiKind.Int) {
                boolean isFalseCheck = (constant.asInt() == 0);
                if (compare.condition == Condition.EQ || compare.condition == Condition.NE) {
                    if (compare.condition == Condition.NE) {
                        isFalseCheck = !isFalseCheck;
                    }
                    BooleanNode result = materializeNode.condition();
                    if (isFalseCheck) {
                        result = new NegateBooleanNode(result, compare.graph());
                    }
                    if (GraalOptions.TraceCanonicalizer) {
                        TTY.println("Removed materialize replacing with " + result);
                    }
                    return result;
                }
            }
            return compare;
        }

        private Node optimizeNormalizeCmp(Compare compare, CiConstant constant, NormalizeCompare normalizeNode) {
            if (constant.kind == CiKind.Int && constant.asInt() == 0) {
                Condition condition = compare.condition();
                if (normalizeNode == compare.y()) {
                    condition = condition.mirror();
                }
                Compare result = new Compare(normalizeNode.x(), condition, normalizeNode.y(), compare.graph());
                boolean isLess = condition == Condition.LE || condition == Condition.LT || condition == Condition.BE || condition == Condition.BT;
                result.unorderedIsTrue = condition != Condition.EQ && (condition == Condition.NE || !(isLess ^ normalizeNode.isUnorderedLess()));
                if (GraalOptions.TraceCanonicalizer) {
                    TTY.println("Replaced Compare+NormalizeCompare with " + result);
                }
                return result;
            }
            return compare;
        }
    };
}
