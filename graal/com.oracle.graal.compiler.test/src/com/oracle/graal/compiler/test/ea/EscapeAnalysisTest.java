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
package com.oracle.graal.compiler.test.ea;

import org.junit.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.loop.phases.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.virtual.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.phases.schedule.*;
import com.oracle.graal.virtual.phases.ea.*;

/**
 * The PartialEscapeAnalysisPhase is expected to remove all allocations and return the correct
 * values.
 */
public class EscapeAnalysisTest extends EATestBase {

    @Test
    public void test1() {
        testEscapeAnalysis("test1Snippet", Constant.forInt(101), false);
    }

    public static int test1Snippet() {
        Integer x = new Integer(101);
        return x.intValue();
    }

    @Test
    public void test2() {
        testEscapeAnalysis("test2Snippet", Constant.forInt(0), false);
    }

    public static int test2Snippet() {
        Integer[] x = new Integer[0];
        return x.length;
    }

    @Test
    public void test3() {
        testEscapeAnalysis("test3Snippet", Constant.forObject(null), false);
    }

    public static Object test3Snippet() {
        Integer[] x = new Integer[1];
        return x[0];
    }

    @Test
    public void testMonitor() {
        testEscapeAnalysis("testMonitorSnippet", Constant.forInt(0), false);
    }

    public static int testMonitorSnippet() {
        Integer x = new Integer(0);
        Double y = new Double(0);
        Object z = new Object();
        synchronized (x) {
            synchronized (y) {
                synchronized (z) {
                    notInlineable();
                }
            }
        }
        return x.intValue();
    }

    @Test
    public void testMonitor2() {
        testEscapeAnalysis("testMonitor2Snippet", Constant.forInt(0), false);
    }

    /**
     * This test case differs from the last one in that it requires inlining within a synchronized
     * region.
     */
    public static int testMonitor2Snippet() {
        Integer x = new Integer(0);
        Double y = new Double(0);
        Object z = new Object();
        synchronized (x) {
            synchronized (y) {
                synchronized (z) {
                    notInlineable();
                    return x.intValue();
                }
            }
        }
    }

    @Test
    public void testMerge() {
        testEscapeAnalysis("testMerge1Snippet", Constant.forInt(0), true);
    }

    public static int testMerge1Snippet(int a) {
        TestClassInt obj = new TestClassInt(1, 0);
        if (a < 0) {
            obj.x = obj.x + 1;
        } else {
            obj.x = obj.x + 2;
            obj.y = 0;
        }
        if (obj.x > 1000) {
            return 1;
        }
        return obj.y;
    }

    @Test
    public void testSimpleLoop() {
        testEscapeAnalysis("testSimpleLoopSnippet", Constant.forInt(1), false);
    }

    public int testSimpleLoopSnippet(int a) {
        TestClassInt obj = new TestClassInt(1, 2);
        for (int i = 0; i < a; i++) {
            notInlineable();
        }
        return obj.x;
    }

    @Test
    public void testModifyingLoop() {
        testEscapeAnalysis("testModifyingLoopSnippet", Constant.forInt(1), false);
    }

    public int testModifyingLoopSnippet(int a) {
        TestClassInt obj = new TestClassInt(1, 2);
        for (int i = 0; i < a; i++) {
            obj.x = 3;
            notInlineable();
        }
        return obj.x <= 3 ? 1 : 0;
    }

    @Test
    public void testCheckCast() {
        testEscapeAnalysis("testCheckCastSnippet", Constant.forObject(TestClassObject.class), false);
    }

    public Object testCheckCastSnippet() {
        TestClassObject obj = new TestClassObject(TestClassObject.class);
        TestClassObject obj2 = new TestClassObject(obj);
        return ((TestClassObject) obj2.x).x;
    }

    @Test
    public void testInstanceOf() {
        testEscapeAnalysis("testInstanceOfSnippet", Constant.forInt(1), false);
    }

    public boolean testInstanceOfSnippet() {
        TestClassObject obj = new TestClassObject(TestClassObject.class);
        TestClassObject obj2 = new TestClassObject(obj);
        return obj2.x instanceof TestClassObject;
    }

    @SuppressWarnings("unused")
    public static void testNewNodeSnippet() {
        new IntegerAddNode(Kind.Int, null, null);
    }

    /**
     * This test makes sure that the allocation of a {@link Node} can be removed. It therefore also
     * tests the intrinsification of {@link Object#getClass()}.
     */
    @Test
    public void testNewNode() {
        testEscapeAnalysis("testNewNodeSnippet", null, false);
    }

    private static final TestClassObject staticObj = new TestClassObject();

    public static Object testFullyUnrolledLoopSnippet() {
        /*
         * This tests a case that can appear if PEA is performed both before and after loop
         * unrolling/peeling: If the VirtualInstanceNode is not duplicated correctly with the loop,
         * the resulting object will reference itself, and not a second (different) object.
         */
        TestClassObject obj = staticObj;
        for (int i = 0; i < 2; i++) {
            obj = new TestClassObject(obj);
        }
        return obj.x;
    }

    @Test
    public void testFullyUnrolledLoop() {
        prepareGraph("testFullyUnrolledLoopSnippet", false);
        new LoopFullUnrollPhase(new CanonicalizerPhase(true)).apply(graph, context);
        new PartialEscapePhase(false, new CanonicalizerPhase(true)).apply(graph, context);
        Assert.assertTrue(returnNode.result() instanceof AllocatedObjectNode);
        CommitAllocationNode commit = ((AllocatedObjectNode) returnNode.result()).getCommit();
        Assert.assertEquals(2, commit.getValues().size());
        Assert.assertEquals(1, commit.getVirtualObjects().size());
        Assert.assertTrue("non-cyclic data structure expected", commit.getVirtualObjects().get(0) != commit.getValues().get(0));
    }

    @SuppressWarnings("unused") private static Object staticField;

    private static TestClassObject inlinedPart(TestClassObject obj) {
        TestClassObject ret = new TestClassObject(obj);
        staticField = null;
        return ret;
    }

    public static Object testPeeledLoopSnippet() {
        TestClassObject obj = staticObj;
        int i = 0;
        do {
            obj = inlinedPart(obj);
        } while (i++ < 10);
        staticField = obj;
        return obj.x;
    }

    @Test
    public void testPeeledLoop() {
        prepareGraph("testPeeledLoopSnippet", false);
        new LoopTransformHighPhase().apply(graph);
        new LoopTransformLowPhase().apply(graph);
        new SchedulePhase().apply(graph);
    }

    public static void testDeoptMonitorSnippetInner(Object o2, Object t, int i) {
        staticField = null;
        if (i == 0) {
            staticField = o2;
            Number n = (Number) t;
            n.toString();
        }
    }

    public static void testDeoptMonitorSnippet(Object t, int i) {
        TestClassObject o = new TestClassObject();
        TestClassObject o2 = new TestClassObject(o);

        synchronized (o) {
            testDeoptMonitorSnippetInner(o2, t, i);
        }
    }

    @Test
    public void testDeoptMonitor() {
        test("testDeoptMonitorSnippet", new Object(), 0);
    }
}
