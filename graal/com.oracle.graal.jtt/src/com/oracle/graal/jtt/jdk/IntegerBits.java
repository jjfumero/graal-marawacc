/*
 * Copyright (c) 2012, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.jtt.jdk;

import org.junit.*;


public class IntegerBits {
    @SuppressWarnings("unused")
    private static int init = Integer.reverseBytes(42);
    private static int original = 0x01020304;
    private static int reversed = 0x04030201;
    private static int v = 0b1000;
    private static int zero = 0;

    public static int test(int o) {
        return Integer.reverseBytes(o);
    }

    public static int test2(int o) {
        return Integer.numberOfLeadingZeros(o);
    }

    public static int test3(int o) {
        return Integer.numberOfTrailingZeros(o);
    }

    public static int test4(int o) {
        return Integer.bitCount(o);
    }

    @Test
    public void run0() {
        Assert.assertEquals(reversed, test(original));
    }

    @Test
    public void run1() {
        Assert.assertEquals(3, test3(v));
    }

    @Test
    public void run2() {
        Assert.assertEquals(28, test2(v));
    }

    @Test
    public void run3() {
        Assert.assertEquals(32, test3(zero));
    }

    @Test
    public void run4() {
        Assert.assertEquals(32, test2(zero));
    }

    @Test
    public void run5() {
        Assert.assertEquals(reversed, test(0x01020304));
    }

    @Test
    public void run6() {
        Assert.assertEquals(3, test3(0b1000));
    }

    @Test
    public void run7() {
        Assert.assertEquals(28, test2(0b1000));
    }

    @Test
    public void run8() {
        Assert.assertEquals(32, test3(0));
    }

    @Test
    public void run9() {
        Assert.assertEquals(32, test2(0));
    }

    @Test
    public void run10() {
        Assert.assertEquals(32, test4(0xffffffff));
    }
}
