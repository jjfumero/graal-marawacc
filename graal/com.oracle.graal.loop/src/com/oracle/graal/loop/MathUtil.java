/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.loop;

import com.oracle.graal.compiler.common.type.IntegerStamp;
import com.oracle.graal.nodes.FixedNode;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.calc.BinaryArithmeticNode;
import com.oracle.graal.nodes.calc.IntegerDivNode;

/**
 * Utility methods to perform integer math with some obvious constant folding first.
 */
public class MathUtil {
    private static boolean isConstantOne(ValueNode v1) {
        return v1.isConstant() && v1.stamp() instanceof IntegerStamp && v1.asJavaConstant().asLong() == 1;
    }

    private static boolean isConstantZero(ValueNode v1) {
        return v1.isConstant() && v1.stamp() instanceof IntegerStamp && v1.asJavaConstant().asLong() == 0;
    }

    public static ValueNode add(StructuredGraph graph, ValueNode v1, ValueNode v2) {
        if (isConstantZero(v1)) {
            return v2;
        }
        if (isConstantZero(v2)) {
            return v1;
        }
        return BinaryArithmeticNode.add(graph, v1, v2);
    }

    public static ValueNode mul(StructuredGraph graph, ValueNode v1, ValueNode v2) {
        if (isConstantOne(v1)) {
            return v2;
        }
        if (isConstantOne(v2)) {
            return v1;
        }
        return BinaryArithmeticNode.mul(graph, v1, v2);
    }

    public static ValueNode sub(StructuredGraph graph, ValueNode v1, ValueNode v2) {
        if (isConstantZero(v2)) {
            return v1;
        }
        return BinaryArithmeticNode.sub(graph, v1, v2);
    }

    public static ValueNode divBefore(StructuredGraph graph, FixedNode before, ValueNode dividend, ValueNode divisor) {
        if (isConstantOne(divisor)) {
            return dividend;
        }
        IntegerDivNode div = graph.add(new IntegerDivNode(dividend, divisor));
        graph.addBeforeFixed(before, div);
        return div;
    }
}
