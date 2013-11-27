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
package com.oracle.graal.compiler.ptx.test;

import java.lang.reflect.*;

import org.junit.*;

public class ControlPTXTest extends PTXTestBase {

    @Test
    public void testControl() {
        String testName = "testLoop";
        int argVal1 = 42;
        int argVal2;
        Integer ret = (Integer) invoke(compile(testName), argVal1);
        int jres = testLoop(argVal1);
        if (ret != null) {
            if (ret.intValue() == jres) {
                printReport(testName + ": PASSED");
            } else {
                printReport(testName + ": FAILED " + "Expected " + jres + " Got " + ret);
            }
        } else {
            printReport(testName + ": FAILED (null returned)");
        }

        testName = "testSwitchDefault1I";
        argVal1 = 3;
        ret = (Integer) invoke(compile(testName), argVal1);
        jres = testSwitchDefault1I(argVal1);
        if (ret != null) {
            if (ret.intValue() == jres) {
                printReport(testName + ": PASSED");
            } else {
                printReport(testName + ": FAILED " + "Expected " + jres + " Got " + ret);
            }
        } else {
            printReport(testName + ": FAILED (null returned)");
        }

        testName = "testSwitch1I";
        argVal1 = 2;
        ret = (Integer) invoke(compile(testName), argVal1);
        jres = testSwitch1I(argVal1);
        if (ret != null) {
            if (ret.intValue() == jres) {
                printReport(testName + ": PASSED");
            } else {
                printReport(testName + ": FAILED " + "Expected " + jres + " Got " + ret);
            }
        } else {
            printReport(testName + ": FAILED (null returned)");
        }

        testName = "testIfElse1I";
        argVal1 = 222;
        ret = (Integer) invoke(compile(testName), argVal1);
        jres = testIfElse1I(argVal1);
        if (ret != null) {
            if (ret.intValue() == jres) {
                printReport(testName + ": PASSED");
            } else {
                printReport(testName + ": FAILED " + "Expected " + jres + " Got " + ret);
            }
        } else {
            printReport(testName + ": FAILED (null returned)");
        }

        testName = "testIfElse2I";
        argVal1 = 19;
        argVal2 = 64;
        ret = (Integer) invoke(compile(testName), argVal1, argVal2);
        jres = testIfElse2I(argVal1, argVal2);
        if (ret != null) {
            if (ret.intValue() == jres) {
                printReport(testName + ": PASSED");
            } else {
                printReport(testName + ": FAILED " + "Expected " + jres + " Got " + ret);
            }
        } else {
            printReport(testName + ": FAILED " + "Expected " + jres + " Got " + ret);
        }

        Boolean bret = (Boolean) invoke(compile("testIntegerTestBranch2I"), 0xff00, 0x00ff);
        if (bret != null) {
            printReport("testIntegerTestBranch2I: " + bret);
            printReport("testIntegerTestBranch2I: actual: " + testIntegerTestBranch2I(0xff00, 0x00ff));
        } else {
            printReport("testIntegerTestBranch2I: no VALUE");
        }
        compile("testStatic");
        compile("testCall");
        compile("testLookupSwitch1I");
    }

    public static boolean testIntegerTestBranch2I(int x, int y) {
        return (x & y) == 0;
    }

    public static int testLoop(int n) {
        int sum = 0;

        for (int i = 0; i < n; i++) {
            sum++;
        }
        return sum;
    }

    public static int testIfElse1I(int n) {
        if (n > 22) {
            return 42;
        } else {
            return -42;
        }
    }

    public static int testIfElse2I(int c, int y) {
        if (c > 19) {
            return 'M';    // millenial
        } else if (y > 84) {
            return 'Y';    // young
        } else {
            return 'O';    // old
        }
    }

    public static int testSwitchDefault1I(int a) {
        switch (a) {
            default:
                return 4;
        }
    }

    public static int testSwitch1I(int a) {
        switch (a) {
            case 1:
                return 2;
            case 2:
                return 3;
            default:
                return 4;
        }
    }

    public static int testLookupSwitch1I(int a) {
        switch (a) {
            case 0:
                return 10;
            case 1:
                return 11;
            case 2:
                return 12;
            case 3:
                return 13;
            case 4:
                return 14;
            case 5:
                return 15;
            case 6:
                return 16;
            case 7:
                return 17;
            case 8:
                return 18;
            case 9:
                return 19;
            case 10:
                return 20;
            case 11:
                return 21;
            default:
                return 42;
        }
    }

    @SuppressWarnings("unused") private static Object foo = null;

    public static boolean testStatic(Object o) {
        foo = o;
        return true;
    }

    private static int method(int a, int b) {
        return a + b;
    }

    public static int testCall(int a, int b) {
        return method(a, b);
    }

    public static void main(String[] args) {
        ControlPTXTest test = new ControlPTXTest();
        for (Method m : ControlPTXTest.class.getMethods()) {
            String name = m.getName();
            if (m.getAnnotation(Test.class) == null && name.startsWith("test")) {
                printReport(name + ": \n" + new String(test.compile(name).getTargetCode()));
            }
        }
    }
}
