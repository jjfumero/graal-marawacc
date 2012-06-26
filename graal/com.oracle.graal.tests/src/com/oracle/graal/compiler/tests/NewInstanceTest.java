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
package com.oracle.graal.compiler.tests;

import java.util.*;

import org.junit.*;

/**
 * Tests the implementation of {@code NEW}.
 */
public class NewInstanceTest extends GraalCompilerTest {

    @Override
    protected void assertEquals(Object expected, Object actual) {
        Assert.assertTrue(expected != null);
        Assert.assertTrue(actual != null);
        super.assertEquals(expected.getClass(), actual.getClass());
        if (expected.getClass() != Object.class) {
            try {
                expected.getClass().getDeclaredMethod("equals", Object.class);
                super.assertEquals(expected, actual);
            } catch (Exception e) {
            }
        }
    }

    @Test
    public void test1() {
        test("newObject");
        test("newBigObject");
        test("newSomeObject");
        test("newEmptyString");
        test("newString", "value");
        test("newHashMap", 31);
        test("newRegression", true);
    }

    public static Object newObject() {
        return new Object();
    }

    public static BigObject newBigObject() {
        return new BigObject();
    }

    public static SomeObject newSomeObject() {
        return new SomeObject();
    }

    public static String newEmptyString() {
        return new String();
    }

    public static String newString(String value) {
        return new String(value);
    }

    public static HashMap newHashMap(int initialCapacity) {
        return new HashMap(initialCapacity);
    }

    static class SomeObject {
        String name = "o1";
        HashMap<String, Object> map = new HashMap<>();


        public SomeObject() {
            map.put(name, this.getClass());
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof SomeObject) {
                SomeObject so = (SomeObject) obj;
                return so.name.equals(name) && so.map.equals(map);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

    static class BigObject {
        Object f01;
        Object f02;
        Object f03;
        Object f04;
        Object f05;
        Object f06;
        Object f07;
        Object f08;
        Object f09;
        Object f10;
        Object f12;
        Object f13;
        Object f14;
        Object f15;
        Object f16;
        Object f17;
        Object f18;
        Object f19;
        Object f20;
        Object f21;
        Object f22;
        Object f23;
        Object f24;
        Object f25;
        Object f26;
        Object f27;
        Object f28;
        Object f29;
        Object f30;
        Object f31;
        Object f32;
        Object f33;
        Object f34;
        Object f35;
        Object f36;
        Object f37;
        Object f38;
        Object f39;
        Object f40;
        Object f41;
        Object f42;
        Object f43;
        Object f44;
        Object f45;
    }

    /**
     * Tests that an earlier bug does not occur. The issue was that the loading of the TLAB
     * 'top' and 'end' values was being GVN'ed from each branch of the 'if' statement.
     * This meant that the allocated B object in the true branch overwrote the allocated
     * array. The cause is that RegisterNode was a floating node and the reads from it
     * were UnsafeLoads which are also floating. The fix was to make RegisterNode a fixed
     * node (which it should have been in the first place).
     */
    public static Object newRegression(boolean condition) {
        Object result;
        if (condition) {
            Object[] arr = {0, 1, 2, 3, 4, 5};
            result = new B();
            for (int i = 0; i < arr.length; ++i) {
                // If the bug exists, the values of arr will now be deadbeef values
                // and the virtual dispatch will cause a segfault. This can result in
                // either a VM crash or a spurious NullPointerException.
                if (arr[i].equals(Integer.valueOf(i))) {
                    return false;
                }
            }
        } else {
            result = new B();
        }
        return result;
    }

    static class B {
        long f1 = 0xdeadbeefdeadbe01L;
        long f2 = 0xdeadbeefdeadbe02L;
        long f3 = 0xdeadbeefdeadbe03L;
        long f4 = 0xdeadbeefdeadbe04L;
        long f5 = 0xdeadbeefdeadbe05L;
    }
}
