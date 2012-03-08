/*
 * Copyright (c) 2007, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.jtt.optimize;

import org.junit.*;

/*
 */
public class BC_imul_16 {

    public static int test(int i, int arg) {
        if (i == 0) {
            final int mult = 16;
            return arg * mult;
        }
        return arg * 16;
    }

    @Test
    public void run0() throws Throwable {
        Assert.assertEquals(0, test(0, 0));
    }

    @Test
    public void run1() throws Throwable {
        Assert.assertEquals(256, test(0, 16));
    }

    @Test
    public void run2() throws Throwable {
        Assert.assertEquals(272, test(0, 17));
    }

    @Test
    public void run3() throws Throwable {
        Assert.assertEquals(-16, test(0, -1));
    }

    @Test
    public void run4() throws Throwable {
        Assert.assertEquals(-256, test(0, -16));
    }

    @Test
    public void run5() throws Throwable {
        Assert.assertEquals(-272, test(0, -17));
    }

    @Test
    public void run6() throws Throwable {
        Assert.assertEquals(-16, test(0, 2147483647));
    }

    @Test
    public void run7() throws Throwable {
        Assert.assertEquals(0, test(0, -2147483648));
    }

    @Test
    public void run8() throws Throwable {
        Assert.assertEquals(0, test(1, 0));
    }

    @Test
    public void run9() throws Throwable {
        Assert.assertEquals(256, test(1, 16));
    }

    @Test
    public void run10() throws Throwable {
        Assert.assertEquals(272, test(1, 17));
    }

    @Test
    public void run11() throws Throwable {
        Assert.assertEquals(-16, test(1, -1));
    }

    @Test
    public void run12() throws Throwable {
        Assert.assertEquals(-256, test(1, -16));
    }

    @Test
    public void run13() throws Throwable {
        Assert.assertEquals(-272, test(1, -17));
    }

    @Test
    public void run14() throws Throwable {
        Assert.assertEquals(-16, test(1, 2147483647));
    }

    @Test
    public void run15() throws Throwable {
        Assert.assertEquals(0, test(1, -2147483648));
    }

}
