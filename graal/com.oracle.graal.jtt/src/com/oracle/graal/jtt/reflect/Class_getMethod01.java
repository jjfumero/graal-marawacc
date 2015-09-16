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
package com.oracle.graal.jtt.reflect;

import org.junit.Test;

import com.oracle.graal.jtt.JTTTest;

/*
 */
public class Class_getMethod01 extends JTTTest {

    static String field;

    public static String test(String input) throws NoSuchMethodException {
        return Class_getMethod01.class.getMethod(input, String[].class).getName();
    }

    public static void main(String[] args) {
        field = args[0];
    }

    @Test
    public void run0() throws Throwable {
        runTest("test", "test");
    }

    @Test
    public void run1() throws Throwable {
        runTest("test", "main");
    }

    @Test
    public void run2() throws Throwable {
        runTest("test", "xx");
    }

}
