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
package com.oracle.max.graal.jtt.jdk;

import java.lang.reflect.*;

import org.junit.*;

import sun.misc.*;

/*
 */
public class UnsafeAccess01 {

    @SuppressWarnings("unused")
    private int field = 42;

    public static int test() throws SecurityException, NoSuchFieldException, IllegalAccessException {
        final Unsafe unsafe = getUnsafe();

        final UnsafeAccess01 object = new UnsafeAccess01();
        final Field field = UnsafeAccess01.class.getDeclaredField("field");
        final long offset = unsafe.objectFieldOffset(field);
        final int value = unsafe.getInt(object, offset);
        return value;
    }

    private static Unsafe getUnsafe() throws NoSuchFieldException, IllegalAccessException {
        final Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        return (Unsafe) unsafeField.get(null);
    }

    @Test
    public void run0() throws Throwable {
        Assert.assertEquals(42, test());
    }

}
