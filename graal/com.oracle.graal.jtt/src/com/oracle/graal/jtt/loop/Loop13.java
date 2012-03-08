/*
 * Copyright (c) 2011, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.jtt.loop;

import org.junit.*;

/*
 */
public class Loop13 {

    public static class Loop {

        private int index;
        private Object[] nodes = new Object[]{null, null, new Object(), null, null, new Object(), null};
        private int size = nodes.length;

        public Loop(int start) {
            index = start;
        }

        public void test0() {
            if (index < size) {
                do {
                    index++;
                } while (index < size && nodes[index] == null);
            }
        }

        public int getIndex() {
            return index;
        }

    }

    public static int test(int arg) {
        Loop loop = new Loop(arg);
        loop.test0();
        return loop.getIndex();
    }

    @Test
    public void run0() throws Throwable {
        Assert.assertEquals(2, test(0));
    }

    @Test
    public void run1() throws Throwable {
        Assert.assertEquals(2, test(1));
    }

    @Test
    public void run2() throws Throwable {
        Assert.assertEquals(5, test(3));
    }

    @Test
    public void run3() throws Throwable {
        Assert.assertEquals(7, test(6));
    }

    @Test
    public void run4() throws Throwable {
        Assert.assertEquals(7, test(7));
    }

}
