/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.test;

import static org.junit.Assert.assertEquals;
import jdk.vm.ci.meta.JavaConstant;

import org.junit.Before;
import org.junit.Test;

import com.oracle.graal.compiler.common.type.ArithmeticOpTable;
import com.oracle.graal.nodes.ConstantNode;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.StructuredGraph.AllowAssumptions;

/**
 * This class tests that the canonicalization for constant negate nodes cover all cases.
 */
public class NegateNodeCanonicalizationTest {

    private StructuredGraph graph;

    @Before
    public void before() {
        graph = new StructuredGraph(AllowAssumptions.YES);
    }

    @Test
    public void testByte() {
        byte[] a = new byte[]{Byte.MIN_VALUE, Byte.MIN_VALUE + 1, -1, 0, 1, Byte.MAX_VALUE - 1, Byte.MAX_VALUE};
        for (byte i : a) {
            ConstantNode node = ConstantNode.forByte(i, graph);
            JavaConstant expected = JavaConstant.forInt(-i);
            assertEquals(expected, ArithmeticOpTable.forStamp(node.stamp()).getNeg().foldConstant(node.asConstant()));
        }
    }

    @Test
    public void testChar() {
        char[] a = new char[]{Character.MIN_VALUE, Character.MIN_VALUE + 1, 0, 1, Character.MAX_VALUE - 1, Character.MAX_VALUE};
        for (char i : a) {
            ConstantNode node = ConstantNode.forChar(i, graph);
            JavaConstant expected = JavaConstant.forInt(-i);
            assertEquals(expected, ArithmeticOpTable.forStamp(node.stamp()).getNeg().foldConstant(node.asConstant()));
        }
    }

    @Test
    public void testShort() {
        short[] a = new short[]{Short.MIN_VALUE, Short.MIN_VALUE + 1, -1, 0, 1, Short.MAX_VALUE - 1, Short.MAX_VALUE};
        for (short i : a) {
            ConstantNode node = ConstantNode.forShort(i, graph);
            JavaConstant expected = JavaConstant.forInt(-i);
            assertEquals(expected, ArithmeticOpTable.forStamp(node.stamp()).getNeg().foldConstant(node.asConstant()));
        }
    }

    @Test
    public void testInt() {
        int[] a = new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -1, 0, 1, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        for (int i : a) {
            ConstantNode node = ConstantNode.forInt(i, graph);
            JavaConstant expected = JavaConstant.forInt(-i);
            assertEquals(expected, ArithmeticOpTable.forStamp(node.stamp()).getNeg().foldConstant(node.asConstant()));
        }
    }

    @Test
    public void testLong() {
        long[] a = new long[]{Long.MIN_VALUE, Long.MIN_VALUE + 1, -1, 0, 1, Long.MAX_VALUE - 1, Long.MAX_VALUE};
        for (long i : a) {
            ConstantNode node = ConstantNode.forLong(i, graph);
            JavaConstant expected = JavaConstant.forLong(-i);
            assertEquals(expected, ArithmeticOpTable.forStamp(node.stamp()).getNeg().foldConstant(node.asConstant()));
        }
    }

    @Test
    public void testFloat() {
        float[] a = new float[]{Float.MIN_VALUE, Float.MIN_VALUE + 1, -1, 0, 1, Float.MAX_VALUE - 1, Float.MAX_VALUE};
        for (float i : a) {
            ConstantNode node = ConstantNode.forFloat(i, graph);
            JavaConstant expected = JavaConstant.forFloat(-i);
            assertEquals(expected, ArithmeticOpTable.forStamp(node.stamp()).getNeg().foldConstant(node.asConstant()));
        }
    }

    @Test
    public void testDouble() {
        double[] a = new double[]{Double.MIN_VALUE, Double.MIN_VALUE + 1, -1, 0, 1, Double.MAX_VALUE - 1, Double.MAX_VALUE};
        for (double i : a) {
            ConstantNode node = ConstantNode.forDouble(i, graph);
            JavaConstant expected = JavaConstant.forDouble(-i);
            assertEquals(expected, ArithmeticOpTable.forStamp(node.stamp()).getNeg().foldConstant(node.asConstant()));
        }
    }

}
