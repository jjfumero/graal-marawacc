/*
 * Copyright (c) 2008, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.graal.jtt.bytecode;

import org.junit.*;

/*
 */
public class BC_dcmp10 {

    public static boolean test(int x) {
        double a = 0;
        double b = 0;
        switch (x) {
            case 0:
                a = Double.POSITIVE_INFINITY;
                b = 1;
                break;
            case 1:
                a = 1;
                b = Double.POSITIVE_INFINITY;
                break;
            case 2:
                a = Double.NEGATIVE_INFINITY;
                b = 1;
                break;
            case 3:
                a = 1;
                b = Double.NEGATIVE_INFINITY;
                break;
            case 4:
                a = Double.NEGATIVE_INFINITY;
                b = Double.NEGATIVE_INFINITY;
                break;
            case 5:
                a = Double.NEGATIVE_INFINITY;
                b = Double.POSITIVE_INFINITY;
                break;
            case 6:
                a = Double.NaN;
                b = Double.POSITIVE_INFINITY;
                break;
            case 7:
                a = 1;
                b = Double.NaN;
                break;
            case 8:
                a = 1;
                b = -0.0d / 0.0d;
                break;
        }
        return a <= b;
    }

    @Test
    public void run0() throws Throwable {
        Assert.assertEquals(false, test(0));
    }

    @Test
    public void run1() throws Throwable {
        Assert.assertEquals(true, test(1));
    }

    @Test
    public void run2() throws Throwable {
        Assert.assertEquals(true, test(2));
    }

    @Test
    public void run3() throws Throwable {
        Assert.assertEquals(false, test(3));
    }

    @Test
    public void run4() throws Throwable {
        Assert.assertEquals(true, test(4));
    }

    @Test
    public void run5() throws Throwable {
        Assert.assertEquals(true, test(5));
    }

    @Test
    public void run6() throws Throwable {
        Assert.assertEquals(false, test(6));
    }

    @Test
    public void run7() throws Throwable {
        Assert.assertEquals(false, test(7));
    }

    @Test
    public void run8() throws Throwable {
        Assert.assertEquals(false, test(8));
    }

}
