/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.truffle.test;

import jdk.vm.ci.code.BailoutException;
import jdk.vm.ci.code.SourceStackTrace;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.oracle.graal.replacements.PEGraphDecoder;
import com.oracle.graal.truffle.OptimizedCallTarget;
import com.oracle.graal.truffle.test.nodes.AbstractTestNode;
import com.oracle.graal.truffle.test.nodes.AddTestNode;
import com.oracle.graal.truffle.test.nodes.BlockTestNode;
import com.oracle.graal.truffle.test.nodes.ConstantTestNode;
import com.oracle.graal.truffle.test.nodes.LambdaTestNode;
import com.oracle.graal.truffle.test.nodes.LoadLocalTestNode;
import com.oracle.graal.truffle.test.nodes.LoopTestNode;
import com.oracle.graal.truffle.test.nodes.NestedExplodedLoopTestNode;
import com.oracle.graal.truffle.test.nodes.NeverPartOfCompilationTestNode;
import com.oracle.graal.truffle.test.nodes.ObjectEqualsNode;
import com.oracle.graal.truffle.test.nodes.ObjectHashCodeNode;
import com.oracle.graal.truffle.test.nodes.RecursionTestNode;
import com.oracle.graal.truffle.test.nodes.RootTestNode;
import com.oracle.graal.truffle.test.nodes.StoreLocalTestNode;
import com.oracle.graal.truffle.test.nodes.StringEqualsNode;
import com.oracle.graal.truffle.test.nodes.TwoMergesExplodedLoopTestNode;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;

public class SimplePartialEvaluationTest extends PartialEvaluationTest {

    public static Object constant42() {
        return 42;
    }

    @Before
    public void before() {
        InstrumentationTestMode.set(true);
    }

    @Override
    @After
    public void after() {
        super.after();
        InstrumentationTestMode.set(false);
    }

    @Test
    public void constantValue() {
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new ConstantTestNode(42);
        assertPartialEvalEquals("constant42", new RootTestNode(fd, "constantValue", result));
    }

    @Test
    public void addConstants() {
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new AddTestNode(new ConstantTestNode(40), new ConstantTestNode(2));
        assertPartialEvalEquals("constant42", new RootTestNode(fd, "addConstants", result));
    }

    @Test
    public void neverPartOfCompilationTest() {
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode firstTree = new NeverPartOfCompilationTestNode(new ConstantTestNode(1), 2);
        assertPartialEvalEquals("constant42", new RootTestNode(fd, "neverPartOfCompilationTest", firstTree));

        AbstractTestNode secondTree = new NeverPartOfCompilationTestNode(new ConstantTestNode(1), 1);
        try {
            assertPartialEvalEquals("constant42", new RootTestNode(fd, "neverPartOfCompilationTest", secondTree));
            Assert.fail("Expected verification error!");
        } catch (SourceStackTrace t) {
            // Expected verification error occurred.
            StackTraceElement[] trace = t.getStackTrace();
            Assert.assertTrue(trace[0].toString().startsWith("com.oracle.graal.truffle.test.nodes.NeverPartOfCompilationTestNode.execute(NeverPartOfCompilationTestNode.java:"));
            Assert.assertTrue(trace[1].toString().startsWith("com.oracle.graal.truffle.test.nodes.RootTestNode.execute(RootTestNode.java:"));
        }
    }

    @Test
    public void nestedLoopExplosion() {
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new AddTestNode(new NestedExplodedLoopTestNode(5), new ConstantTestNode(17));
        assertPartialEvalEquals("constant42", new RootTestNode(fd, "nestedLoopExplosion", result));
    }

    @Test
    public void twoMergesLoopExplosion() {
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new AddTestNode(new TwoMergesExplodedLoopTestNode(5), new ConstantTestNode(37));
        assertPartialEvalEquals("constant42", new RootTestNode(fd, "nestedLoopExplosion", result));
    }

    @Test
    public void sequenceConstants() {
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new BlockTestNode(new AbstractTestNode[]{new ConstantTestNode(40), new ConstantTestNode(42)});
        assertPartialEvalEquals("constant42", new RootTestNode(fd, "sequenceConstants", result));
    }

    @Test
    public void localVariable() {
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new BlockTestNode(new AbstractTestNode[]{new StoreLocalTestNode("x", fd, new ConstantTestNode(42)), new LoadLocalTestNode("x", fd)});
        assertPartialEvalEquals("constant42", new RootTestNode(fd, "localVariable", result));
    }

    @Test
    public void longSequenceConstants() {
        FrameDescriptor fd = new FrameDescriptor();
        int length = 40;
        AbstractTestNode[] children = new AbstractTestNode[length];
        for (int i = 0; i < children.length; ++i) {
            children[i] = new ConstantTestNode(42);
        }

        AbstractTestNode result = new BlockTestNode(children);
        assertPartialEvalEquals("constant42", new RootTestNode(fd, "longSequenceConstants", result));
    }

    @Test
    public void longAddConstants() {
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new ConstantTestNode(2);
        for (int i = 0; i < 20; ++i) {
            result = new AddTestNode(result, new ConstantTestNode(2));
        }
        assertPartialEvalEquals("constant42", new RootTestNode(fd, "longAddConstants", result));
    }

    @Test
    public void mixLocalAndAdd() {
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new BlockTestNode(new AbstractTestNode[]{new StoreLocalTestNode("x", fd, new ConstantTestNode(40)),
                        new StoreLocalTestNode("x", fd, new AddTestNode(new LoadLocalTestNode("x", fd), new ConstantTestNode(2))), new LoadLocalTestNode("x", fd)});
        assertPartialEvalEquals("constant42", new RootTestNode(fd, "mixLocalAndAdd", result));
    }

    @Test
    public void loop() {
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new BlockTestNode(new AbstractTestNode[]{new StoreLocalTestNode("x", fd, new ConstantTestNode(0)),
                        new LoopTestNode(7, new StoreLocalTestNode("x", fd, new AddTestNode(new LoadLocalTestNode("x", fd), new ConstantTestNode(6))))});
        assertPartialEvalEquals("constant42", new RootTestNode(fd, "loop", result));
    }

    @Test
    public void longLoop() {
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new BlockTestNode(new AbstractTestNode[]{new StoreLocalTestNode("x", fd, new ConstantTestNode(0)),
                        new LoopTestNode(42, new StoreLocalTestNode("x", fd, new AddTestNode(new LoadLocalTestNode("x", fd), new ConstantTestNode(1))))});
        RootTestNode rootNode = new RootTestNode(fd, "loop", result);
        assertPartialEvalNoInvokes(rootNode);
        assertPartialEvalEquals("constant42", rootNode);
    }

    @Test
    public void lambda() {
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new LambdaTestNode();
        assertPartialEvalEquals("constant42", new RootTestNode(fd, "constantValue", result));
    }

    @Test
    public void allowedRecursion() {
        /* Recursion depth just below the threshold that reports it as too deep recursion. */
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new RecursionTestNode(PEGraphDecoder.Options.InliningDepthError.getValue() - 5);
        assertPartialEvalEquals("constant42", new RootTestNode(fd, "allowedRecursion", result));
    }

    @Test(expected = BailoutException.class)
    public void tooDeepRecursion() {
        /* Recursion depth just above the threshold that reports it as too deep recursion. */
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new RecursionTestNode(PEGraphDecoder.Options.InliningDepthError.getValue());
        assertPartialEvalEquals("constant42", new RootTestNode(fd, "tooDeepRecursion", result));
    }

    @Test
    public void intrinsicStatic() {
        /*
         * The intrinsic for String.equals() is inlined early during bytecode parsing, because we
         * call equals() on a value that has the static type String.
         */
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new StringEqualsNode("abc", "abf");
        RootNode rootNode = new RootTestNode(fd, "intrinsicStatic", result);
        OptimizedCallTarget compilable = compileHelper("intrinsicStatic", rootNode, new Object[0]);

        Assert.assertEquals(42, compilable.call(new Object[0]));
    }

    @Test
    public void intrinsicVirtual() {
        /*
         * The intrinsic for String.equals() is inlined late during Truffle partial evaluation,
         * because we call equals() on a value that has the static type Object, but during partial
         * evaluation the more precise type String is known.
         */
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new ObjectEqualsNode("abc", "abf");
        RootNode rootNode = new RootTestNode(fd, "intrinsicVirtual", result);
        OptimizedCallTarget compilable = compileHelper("intrinsicVirtual", rootNode, new Object[0]);

        Assert.assertEquals(42, compilable.call(new Object[0]));
    }

    @Test
    public void intrinsicHashCode() {
        /*
         * The intrinsic for Object.hashCode() is inlined late during Truffle partial evaluation,
         * because we call hashCode() on a value whose exact type Object is only known during
         * partial evaluation.
         */
        FrameDescriptor fd = new FrameDescriptor();
        Object testObject = new Object();
        AbstractTestNode result = new ObjectHashCodeNode(testObject);
        RootNode rootNode = new RootTestNode(fd, "intrinsicHashCode", result);
        OptimizedCallTarget compilable = compileHelper("intrinsicHashCode", rootNode, new Object[0]);

        int actual = (Integer) compilable.call(new Object[0]);
        int expected = testObject.hashCode();
        Assert.assertEquals(expected, actual);
    }
}
