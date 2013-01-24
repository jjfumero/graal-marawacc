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
package com.oracle.graal.snippets;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.snippets.ClassSubstitution.MethodSubstitution;
import com.oracle.graal.snippets.nodes.*;

/**
 * Substitutions for {@link sun.misc.Unsafe} methods.
 */
@ClassSubstitution(sun.misc.Unsafe.class)
public class UnsafeSubstitutions {

    @MethodSubstitution(isStatic = false)
    public static boolean compareAndSwapObject(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset, Object expected, Object x) {
        return CompareAndSwapNode.compareAndSwap(o, 0, offset, expected, x);
    }

    @MethodSubstitution(isStatic = false)
    public static boolean compareAndSwapInt(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset, int expected, int x) {
        return CompareAndSwapNode.compareAndSwap(o, 0, offset, expected, x);
    }

    @MethodSubstitution(isStatic = false)
    public static boolean compareAndSwapLong(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset, long expected, long x) {
        return CompareAndSwapNode.compareAndSwap(o, 0, offset, expected, x);
    }

    @MethodSubstitution(isStatic = false)
    public static Object getObject(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset) {
        return UnsafeLoadNode.load(o, 0, offset, Kind.Object);
    }

    @MethodSubstitution(isStatic = false)
    public static Object getObjectVolatile(final Object thisObj, Object o, long offset) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_READ);
        Object result = getObject(thisObj, o, offset);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_READ);
        return result;
    }

    @MethodSubstitution(isStatic = false)
    public static void putObject(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset, Object x) {
        UnsafeStoreNode.store(o, 0, offset, x, Kind.Object);
    }

    @MethodSubstitution(isStatic = false)
    public static void putObjectVolatile(final Object thisObj, Object o, long offset, Object x) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_WRITE);
        putObject(thisObj, o, offset, x);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_WRITE);
    }

    @MethodSubstitution(isStatic = false)
    public static void putOrderedObject(final Object thisObj, Object o, long offset, Object x) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_WRITE);
        putObject(thisObj, o, offset, x);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_WRITE);
    }

    @MethodSubstitution(isStatic = false)
    public static int getInt(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset) {
        Integer value = UnsafeLoadNode.load(o, 0, offset, Kind.Int);
        return value;
    }

    @MethodSubstitution(isStatic = false)
    public static int getIntVolatile(final Object thisObj, Object o, long offset) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_READ);
        int result = getInt(thisObj, o, offset);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_READ);
        return result;
    }

    @MethodSubstitution(isStatic = false)
    public static void putInt(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset, int x) {
        UnsafeStoreNode.store(o, 0, offset, x, Kind.Int);
    }

    @MethodSubstitution(isStatic = false)
    public static void putIntVolatile(final Object thisObj, Object o, long offset, int x) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_WRITE);
        putInt(thisObj, o, offset, x);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_WRITE);
    }

    @MethodSubstitution(isStatic = false)
    public static void putOrderedInt(final Object thisObj, Object o, long offset, int x) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_WRITE);
        putInt(thisObj, o, offset, x);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_WRITE);
    }

    @MethodSubstitution(isStatic = false)
    public static boolean getBoolean(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset) {
        @JavacBug(id = 6995200)
        Boolean result = UnsafeLoadNode.load(o, 0, offset, Kind.Boolean);
        return result;
    }

    @MethodSubstitution(isStatic = false)
    public static boolean getBooleanVolatile(final Object thisObj, Object o, long offset) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_READ);
        boolean result = getBoolean(thisObj, o, offset);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_READ);
        return result;
    }

    @MethodSubstitution(isStatic = false)
    public static void putBoolean(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset, boolean x) {
        UnsafeStoreNode.store(o, 0, offset, x, Kind.Boolean);
    }

    @MethodSubstitution(isStatic = false)
    public static void putBooleanVolatile(final Object thisObj, Object o, long offset, boolean x) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_WRITE);
        putBoolean(thisObj, o, offset, x);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_WRITE);
    }

    @MethodSubstitution(isStatic = false)
    public static byte getByte(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset) {
        @JavacBug(id = 6995200)
        Byte result = UnsafeLoadNode.load(o, 0, offset, Kind.Byte);
        return result;
    }

    @MethodSubstitution(isStatic = false)
    public static byte getByteVolatile(final Object thisObj, Object o, long offset) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_READ);
        byte result = getByte(thisObj, o, offset);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_READ);
        return result;
    }

    @MethodSubstitution(isStatic = false)
    public static void putByte(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset, byte x) {
        UnsafeStoreNode.store(o, 0, offset, x, Kind.Byte);
    }

    @MethodSubstitution(isStatic = false)
    public static void putByteVolatile(final Object thisObj, Object o, long offset, byte x) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_WRITE);
        putByte(thisObj, o, offset, x);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_WRITE);
    }

    @MethodSubstitution(isStatic = false)
    public static short getShort(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset) {
        @JavacBug(id = 6995200)
        Short result = UnsafeLoadNode.load(o, 0, offset, Kind.Short);
        return result;
    }

    @MethodSubstitution(isStatic = false)
    public static short getShortVolatile(final Object thisObj, Object o, long offset) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_READ);
        short result = getShort(thisObj, o, offset);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_READ);
        return result;
    }

    @MethodSubstitution(isStatic = false)
    public static void putShort(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset, short x) {
        UnsafeStoreNode.store(o, 0, offset, x, Kind.Short);
    }

    @MethodSubstitution(isStatic = false)
    public static void putShortVolatile(final Object thisObj, Object o, long offset, short x) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_WRITE);
        putShort(thisObj, o, offset, x);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_WRITE);
    }

    @MethodSubstitution(isStatic = false)
    public static char getChar(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset) {
        @JavacBug(id = 6995200)
        Character result = UnsafeLoadNode.load(o, 0, offset, Kind.Char);
        return result;
    }

    @MethodSubstitution(isStatic = false)
    public static char getCharVolatile(final Object thisObj, Object o, long offset) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_READ);
        char result = getChar(thisObj, o, offset);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_READ);
        return result;
    }

    @MethodSubstitution(isStatic = false)
    public static void putChar(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset, char x) {
        UnsafeStoreNode.store(o, 0, offset, x, Kind.Char);
    }

    @MethodSubstitution(isStatic = false)
    public static void putCharVolatile(final Object thisObj, Object o, long offset, char x) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_WRITE);
        putChar(thisObj, o, offset, x);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_WRITE);
    }

    @MethodSubstitution(isStatic = false)
    public static long getLong(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset) {
        @JavacBug(id = 6995200)
        Long result = UnsafeLoadNode.load(o, 0, offset, Kind.Long);
        return result;
    }

    @MethodSubstitution(isStatic = false)
    public static long getLongVolatile(final Object thisObj, Object o, long offset) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_READ);
        long result = getLong(thisObj, o, offset);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_READ);
        return result;
    }

    @MethodSubstitution(isStatic = false)
    public static void putLong(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset, long x) {
        UnsafeStoreNode.store(o, 0, offset, x, Kind.Long);
    }

    @MethodSubstitution(isStatic = false)
    public static void putLongVolatile(final Object thisObj, Object o, long offset, long x) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_WRITE);
        putLong(thisObj, o, offset, x);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_WRITE);
    }

    @MethodSubstitution(isStatic = false)
    public static void putOrderedLong(final Object thisObj, Object o, long offset, long x) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_WRITE);
        putLong(thisObj, o, offset, x);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_WRITE);
    }

    @MethodSubstitution(isStatic = false)
    public static float getFloat(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset) {
        @JavacBug(id = 6995200)
        Float result = UnsafeLoadNode.load(o, 0, offset, Kind.Float);
        return result;
    }

    @MethodSubstitution(isStatic = false)
    public static float getFloatVolatile(final Object thisObj, Object o, long offset) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_READ);
        float result = getFloat(thisObj, o, offset);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_READ);
        return result;
    }

    @MethodSubstitution(isStatic = false)
    public static void putFloat(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset, float x) {
        UnsafeStoreNode.store(o, 0, offset, x, Kind.Float);
    }

    @MethodSubstitution(isStatic = false)
    public static void putFloatVolatile(final Object thisObj, Object o, long offset, float x) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_WRITE);
        putFloat(thisObj, o, offset, x);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_WRITE);
    }

    @MethodSubstitution(isStatic = false)
    public static double getDouble(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset) {
        @JavacBug(id = 6995200)
        Double result = UnsafeLoadNode.load(o, 0, offset, Kind.Double);
        return result;
    }

    @MethodSubstitution(isStatic = false)
    public static double getDoubleVolatile(final Object thisObj, Object o, long offset) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_READ);
        double result = getDouble(thisObj, o, offset);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_READ);
        return result;
    }

    @MethodSubstitution(isStatic = false)
    public static void putDouble(@SuppressWarnings("unused")
    final Object thisObj, Object o, long offset, double x) {
        UnsafeStoreNode.store(o, 0, offset, x, Kind.Double);
    }

    @MethodSubstitution(isStatic = false)
    public static void putDoubleVolatile(final Object thisObj, Object o, long offset, double x) {
        MembarNode.memoryBarrier(MemoryBarriers.JMM_PRE_VOLATILE_WRITE);
        putDouble(thisObj, o, offset, x);
        MembarNode.memoryBarrier(MemoryBarriers.JMM_POST_VOLATILE_WRITE);
    }

    @MethodSubstitution(isStatic = false)
    public static void putByte(@SuppressWarnings("unused")
    final Object thisObj, long address, byte value) {
        DirectStoreNode.store(address, value);
    }

    @MethodSubstitution(isStatic = false)
    public static void putShort(@SuppressWarnings("unused")
    final Object thisObj, long address, short value) {
        DirectStoreNode.store(address, value);
    }

    @MethodSubstitution(isStatic = false)
    public static void putChar(@SuppressWarnings("unused")
    final Object thisObj, long address, char value) {
        DirectStoreNode.store(address, value);
    }

    @MethodSubstitution(isStatic = false)
    public static void putInt(@SuppressWarnings("unused")
    final Object thisObj, long address, int value) {
        DirectStoreNode.store(address, value);
    }

    @MethodSubstitution(isStatic = false)
    public static void putLong(@SuppressWarnings("unused")
    final Object thisObj, long address, long value) {
        DirectStoreNode.store(address, value);
    }

    @MethodSubstitution(isStatic = false)
    public static void putFloat(@SuppressWarnings("unused")
    final Object thisObj, long address, float value) {
        DirectStoreNode.store(address, value);
    }

    @MethodSubstitution(isStatic = false)
    public static void putDouble(@SuppressWarnings("unused")
    final Object thisObj, long address, double value) {
        DirectStoreNode.store(address, value);
    }

    @MethodSubstitution(isStatic = false)
    public static byte getByte(@SuppressWarnings("unused")
    final Object thisObj, long address) {
        return DirectReadNode.read(address, Kind.Byte);
    }

    @MethodSubstitution(isStatic = false)
    public static short getShort(@SuppressWarnings("unused")
    final Object thisObj, long address) {
        return DirectReadNode.read(address, Kind.Short);
    }

    @MethodSubstitution(isStatic = false)
    public static char getChar(@SuppressWarnings("unused")
    final Object thisObj, long address) {
        return DirectReadNode.read(address, Kind.Char);
    }

    @MethodSubstitution(isStatic = false)
    public static int getInt(@SuppressWarnings("unused")
    final Object thisObj, long address) {
        return DirectReadNode.read(address, Kind.Int);
    }

    @MethodSubstitution(isStatic = false)
    public static long getLong(@SuppressWarnings("unused")
    final Object thisObj, long address) {
        return DirectReadNode.read(address, Kind.Long);
    }

    @MethodSubstitution(isStatic = false)
    public static float getFloat(@SuppressWarnings("unused")
    final Object thisObj, long address) {
        return DirectReadNode.read(address, Kind.Float);
    }

    @MethodSubstitution(isStatic = false)
    public static double getDouble(@SuppressWarnings("unused")
    final Object thisObj, long address) {
        return DirectReadNode.read(address, Kind.Double);
    }
}
