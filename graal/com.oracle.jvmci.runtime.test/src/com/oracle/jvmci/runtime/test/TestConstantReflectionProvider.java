/*
 * Copyright (c) 2012, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.jvmci.runtime.test;

import static org.junit.Assert.*;

import java.lang.reflect.*;
import java.util.*;

import org.junit.*;

import com.oracle.jvmci.meta.*;

/**
 * Tests for {@link ConstantReflectionProvider}. It assumes an implementation of the interface that
 * actually returns non-null results for access operations that are possible, i.e., the tests will
 * fail for an implementation that spuriously returns null (which is allowed by the specification).
 */
public class TestConstantReflectionProvider extends TypeUniverse {

    @Test
    public void constantEqualsTest() {
        for (ConstantValue c1 : constants()) {
            for (ConstantValue c2 : constants()) {
                // test symmetry
                assertEquals(constantReflection.constantEquals(c1.value, c2.value), constantReflection.constantEquals(c2.value, c1.value));
                if (c1.value.getKind() != Kind.Object && c2.value.getKind() != Kind.Object) {
                    assertEquals(c1.value.equals(c2.value), constantReflection.constantEquals(c2.value, c1.value));
                }
            }
        }
    }

    @Test
    public void readArrayLengthTest() {
        for (ConstantValue cv : constants()) {
            JavaConstant c = cv.value;
            Integer actual = constantReflection.readArrayLength(c);
            if (c.getKind() != Kind.Object || c.isNull() || !cv.boxed.getClass().isArray()) {
                assertNull(actual);
            } else {
                assertNotNull(actual);
                int actualInt = actual;
                assertEquals(Array.getLength(cv.boxed), actualInt);
            }
        }
    }

    static class PrimitiveConstants {
        static final long LONG_CONST = 42;
        static final int INT_CONST = 66;
        static final byte BYTE_CONST = 123;
        static final boolean BOOL_CONST = true;
    }

    static class BoxedConstants {
        static final Long LONG_CONST = 42L;
        static final Integer INT_CONST = 66;
        static final Byte BYTE_CONST = 123;
        static final Boolean BOOL_CONST = true;
    }

    @Test
    public void boxTest() {
        for (ConstantValue cv : constants()) {
            JavaConstant c = cv.value;
            JavaConstant boxed = constantReflection.boxPrimitive(c);
            if (boxed != null && c.getKind().isPrimitive()) {
                assertTrue(boxed.getKind().isObject());
                assertFalse(boxed.isNull());
            }
        }

        List<ConstantValue> primitiveConstants = readConstants(PrimitiveConstants.class);
        List<ConstantValue> boxedConstants = readConstants(BoxedConstants.class);
        for (int i = 0; i < primitiveConstants.size(); i++) {
            ConstantValue prim = primitiveConstants.get(i);
            ConstantValue box = boxedConstants.get(i);
            assertEquals(box.value, constantReflection.boxPrimitive(prim.value));
        }

        assertNull(constantReflection.boxPrimitive(JavaConstant.NULL_POINTER));
    }

    @Test
    public void unboxTest() {
        for (ConstantValue cv : constants()) {
            JavaConstant c = cv.value;
            JavaConstant unboxed = c.isNull() ? null : constantReflection.unboxPrimitive(c);
            if (unboxed != null) {
                assertFalse(unboxed.getKind().isObject());
            }
        }
        List<ConstantValue> primitiveConstants = readConstants(PrimitiveConstants.class);
        List<ConstantValue> boxedConstants = readConstants(BoxedConstants.class);
        for (int i = 0; i < primitiveConstants.size(); i++) {
            ConstantValue prim = primitiveConstants.get(i);
            ConstantValue box = boxedConstants.get(i);
            assert prim.getSimpleName().equals(box.getSimpleName());
            assertEquals(prim.value, constantReflection.unboxPrimitive(box.value));
        }

        assertNull(constantReflection.unboxPrimitive(JavaConstant.NULL_POINTER));
    }
}
