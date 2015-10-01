/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
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

import jdk.internal.jvmci.code.BailoutException;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.oracle.graal.api.directives.GraalDirectives;
import com.oracle.graal.truffle.test.nodes.AbstractTestNode;
import com.oracle.graal.truffle.test.nodes.RootTestNode;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;

public class TruffleEnsureVirtualizedTest extends PartialEvaluationTest {

    private abstract class TestNode extends AbstractTestNode {
        @Override
        public int execute(VirtualFrame frame) {
            executeVoid(frame);
            return 0;
        }

        public abstract void executeVoid(VirtualFrame frame);
    }

    private void testEnsureVirtualized(boolean bailoutExpected, TestNode node) {
        RootTestNode rootNode = new RootTestNode(new FrameDescriptor(), "ensureVirtualized", node);
        try {
            compileHelper("ensureVirtualized", rootNode, new Object[0]);
            if (bailoutExpected) {
                Assert.fail("Expected bailout exception due to ensureVirtualized");
            }
        } catch (BailoutException e) {
            if (!bailoutExpected) {
                throw e;
            }
        }
    }

    public static int intField;
    public static boolean booleanField;
    public static Object field;

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
    public void test1() {
        testEnsureVirtualized(false, new TestNode() {
            @Override
            public void executeVoid(VirtualFrame frame) {
                Integer object = new Integer(intField);
                GraalDirectives.ensureVirtualized(object);
            }
        });
    }

    @Test
    public void test2() {
        testEnsureVirtualized(true, new TestNode() {
            @Override
            public void executeVoid(VirtualFrame frame) {
                Integer object = new Integer(intField);
                GraalDirectives.ensureVirtualized(object);
                field = object; // assert here
            }
        });
    }

    @Test
    public void test3() {
        testEnsureVirtualized(true, new TestNode() {
            @Override
            public void executeVoid(VirtualFrame frame) {
                Integer object = new Integer(intField);
                field = object;
                GraalDirectives.ensureVirtualized(object); // assert here
            }
        });
    }

    @Test
    public void testHere1() {
        testEnsureVirtualized(false, new TestNode() {
            @Override
            public void executeVoid(VirtualFrame frame) {
                Integer object = new Integer(intField);
                GraalDirectives.ensureVirtualizedHere(object);
            }
        });
    }

    @Test
    public void testHere2() {
        testEnsureVirtualized(false, new TestNode() {
            @Override
            public void executeVoid(VirtualFrame frame) {
                Integer object = new Integer(intField);
                GraalDirectives.ensureVirtualizedHere(object);
                field = object;
            }
        });
    }

    @Test
    public void testHere3() {
        testEnsureVirtualized(true, new TestNode() {
            @Override
            public void executeVoid(VirtualFrame frame) {
                Integer object = new Integer(intField);
                field = object;
                GraalDirectives.ensureVirtualizedHere(object); // assert here
            }
        });
    }

    @Test
    public void testBoxing1() {
        testEnsureVirtualized(true, new TestNode() {
            @Override
            public void executeVoid(VirtualFrame frame) {
                Integer object = intField;
                GraalDirectives.ensureVirtualizedHere(object); // assert here
            }
        });
    }

    @Test
    public void testBoxing2() {
        testEnsureVirtualized(true, new TestNode() {
            @Override
            public void executeVoid(VirtualFrame frame) {
                Integer object = intField;
                GraalDirectives.ensureVirtualized(object); // assert here
                field = object;
            }
        });
    }

    @Test
    public void testControlFlow1() {
        testEnsureVirtualized(false, new TestNode() {
            @Override
            public void executeVoid(VirtualFrame frame) {
                Integer object = new Integer(intField);
                if (booleanField) {
                    GraalDirectives.ensureVirtualized(object);
                }
                field = object;
            }
        });
    }

    @Test
    public void testControlFlow2() {
        testEnsureVirtualized(true, new TestNode() {
            @Override
            public void executeVoid(VirtualFrame frame) {
                Integer object = new Integer(intField);
                if (booleanField) {
                    GraalDirectives.ensureVirtualized(object);
                } else {
                    GraalDirectives.ensureVirtualized(object);
                }
                field = object; // assert here
            }
        });
    }

    @Test
    public void testControlFlow3() {
        testEnsureVirtualized(true, new TestNode() {
            @Override
            public void executeVoid(VirtualFrame frame) {
                Integer object = new Integer(intField);
                GraalDirectives.ensureVirtualized(object);
                if (booleanField) {
                    field = 1;
                } else {
                    field = 2;
                }
                field = object; // assert here
            }
        });
    }

    @Test
    public void testControlFlow4() {
        testEnsureVirtualized(true, new TestNode() {
            @Override
            public void executeVoid(VirtualFrame frame) {
                Integer object = new Integer(intField);
                if (booleanField) {
                    field = object;
                } else {
                    field = 2;
                }
                GraalDirectives.ensureVirtualized(object); // assert here
            }
        });
    }

    @Test
    public void testControlFlow5() {
        testEnsureVirtualized(true, new TestNode() {
            @Override
            public void executeVoid(VirtualFrame frame) {
                Integer object = new Integer(intField);
                if (booleanField) {
                    field = object;
                } else {
                    field = 2;
                }
                GraalDirectives.ensureVirtualizedHere(object); // assert here
            }
        });
    }

    public static final class TestClass {
        Object a;
        Object b;
    }

    @Test
    public void testIndirect1() {
        testEnsureVirtualized(true, new TestNode() {
            @Override
            public void executeVoid(VirtualFrame frame) {
                Integer object = new Integer(intField);
                TestClass t = new TestClass();
                t.a = object;
                GraalDirectives.ensureVirtualized(object);

                if (booleanField) {
                    field = t; // assert here
                } else {
                    field = 2;
                }
            }
        });
    }

    @Test
    public void testIndirect2() {
        testEnsureVirtualized(false, new TestNode() {
            @Override
            public void executeVoid(VirtualFrame frame) {
                Integer object = new Integer(intField);
                TestClass t = new TestClass();
                t.a = object;
                GraalDirectives.ensureVirtualized(t);

                if (booleanField) {
                    field = object;
                } else {
                    field = 2;
                }
            }
        });
    }
}
