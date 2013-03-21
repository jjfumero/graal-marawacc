/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.compiler.test;

import static org.junit.Assert.*;

import org.junit.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.phases.common.*;

public class CompareCanonicalizerTest extends GraalCompilerTest {

    private StructuredGraph getCanonicalizedGraph(String name) {
        StructuredGraph graph = parse(name);
        new CanonicalizerPhase(runtime(), null).apply(graph);
        return graph;
    }

    private static ValueNode getResult(StructuredGraph graph) {
        assertTrue(graph.start().next() instanceof ReturnNode);
        ReturnNode ret = (ReturnNode) graph.start().next();
        return ret.result();
    }

    @Test
    public void testCanonicalComparison() {
        StructuredGraph referenceGraph = parse("referenceCanonicalComparison");
        for (int i = 1; i < 4; i++) {
            StructuredGraph graph = parse("canonicalCompare" + i);
            assertEquals(referenceGraph, graph);
        }
        Assumptions assumptions = new Assumptions(false);
        new CanonicalizerPhase(runtime(), assumptions).apply(referenceGraph);
        for (int i = 1; i < 4; i++) {
            StructuredGraph graph = getCanonicalizedGraph("canonicalCompare" + i);
            assertEquals(referenceGraph, graph);
        }
    }

    public static int referenceCanonicalComparison(int a, int b) {
        if (a < b) {
            return 1;
        } else {
            return 2;
        }
    }

    public static int canonicalCompare1(int a, int b) {
        if (a >= b) {
            return 2;
        } else {
            return 1;
        }
    }

    public static int canonicalCompare2(int a, int b) {
        if (b > a) {
            return 1;
        } else {
            return 2;
        }
    }

    public static int canonicalCompare3(int a, int b) {
        if (b <= a) {
            return 2;
        } else {
            return 1;
        }
    }

    @Test
    public void testIntegerTest() {
        for (int i = 1; i <= 4; i++) {
            StructuredGraph graph = getCanonicalizedGraph("integerTest" + i);

            ValueNode result = getResult(graph);
            assertTrue(result instanceof ConditionalNode);
            ConditionalNode mat = (ConditionalNode) result;
            assertTrue(mat.condition() instanceof IntegerTestNode);
            IntegerTestNode test = (IntegerTestNode) mat.condition();
            LocalNode local0 = graph.getLocal(0);
            LocalNode local1 = graph.getLocal(1);
            assertTrue((test.x() == local0 && test.y() == local1) || (test.x() == local1 && test.y() == local0));
        }
    }

    public static boolean integerTest1(int x, int y) {
        return (x & y) == 0;
    }

    public static boolean integerTest2(long x, long y) {
        return 0 == (x & y);
    }

    public static boolean integerTest3(long x, long y) {
        int c = 5;
        return (c - 5) == (x & y);
    }

    public static boolean integerTest4(int x, int y) {
        int c = 10;
        return (x & y) == (10 - c);
    }

    @Test
    public void testIntegerTestCanonicalization() {
        ValueNode result = getResult(getCanonicalizedGraph("integerTestCanonicalization1"));
        assertTrue(result.isConstant() && result.asConstant().asLong() == 1);
        result = getResult(getCanonicalizedGraph("integerTestCanonicalization2"));
        assertTrue(result.isConstant() && result.asConstant().asLong() == 1);
        result = getResult(getCanonicalizedGraph("integerTestCanonicalization3"));
        assertTrue(result instanceof ConditionalNode);
    }

    public static int integerTestCanonicalization1(boolean b) {
        int x = b ? 128 : 256;
        if ((x & 8) == 0) {
            return 1;
        } else {
            return 2;
        }
    }

    public static int integerTestCanonicalization2(boolean b) {
        int x = b ? 128 : 256;
        int y = b ? 32 : 64;
        if ((x & y) == 0) {
            return 1;
        } else {
            return 2;
        }
    }

    public static int integerTestCanonicalization3(boolean b) {
        int x = b ? 128 : 64;
        int y = b ? 32 : 64;
        if ((x & y) == 0) {
            return 1;
        } else {
            return 2;
        }
    }

}
