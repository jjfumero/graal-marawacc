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
package com.oracle.graal.ffi.amd64.test;

import java.lang.reflect.*;

import org.junit.*;

import sun.misc.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.runtime.*;
import com.oracle.graal.runtime.*;

public class LibCallTest {

    protected static final Unsafe unsafe = getUnsafe();
    public static final RuntimeProvider runtimeProvider = Graal.getRequiredCapability(RuntimeProvider.class);
    public static final NativeFunctionInterface ffi = runtimeProvider.getHostBackend().getNativeFunctionInterface();

    @Test
    public void test() {
        Long str = unsafe.allocateMemory(4);
        unsafe.putByte(str, (byte) 'A');
        unsafe.putByte(str + 1, (byte) 'B');
        unsafe.putByte(str + 2, (byte) 'C');
        unsafe.putByte(str + 3, (byte) '\0');
        NativeFunctionHandle handle = ffi.getFunctionHandle("puts", int.class, new Class[]{long.class});
        Object[] args = new Object[]{str};
        int result = (int) handle.call(args);
        Assert.assertTrue(0 < result);
    }

    protected long getDouble(double val) {
        Long d = unsafe.allocateMemory(8);
        unsafe.putDouble(d, val);
        return d;
    }

    protected long getLong(long val) {
        Long d = unsafe.allocateMemory(8);
        unsafe.putLong(d, val);
        return d;
    }

    protected long getInt(int val) {
        Long d = unsafe.allocateMemory(4);
        unsafe.putInt(d, val);
        return d;
    }

    protected void free(long p) {
        unsafe.freeMemory(p);
    }

    private static Unsafe getUnsafe() {
        try {
            return Unsafe.getUnsafe();
        } catch (SecurityException e) {
        }
        try {
            Field theUnsafeInstance = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeInstance.setAccessible(true);
            return (Unsafe) theUnsafeInstance.get(Unsafe.class);
        } catch (Exception e) {
            throw new RuntimeException("exception while trying to get Unsafe.theUnsafe via reflection:", e);
        }
    }

}
