/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.api.meta.test;

import static org.junit.Assert.*;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;

import org.junit.*;

import com.oracle.graal.api.meta.*;

/**
 * Tests for {@link ResolvedJavaMethod}.
 */
public class TestResolvedJavaMethod extends MethodUniverse {

    public TestResolvedJavaMethod() {
    }

    /**
     * @see ResolvedJavaMethod#getCode()
     */
    @Test
    public void getCodeTest() {
        for (Map.Entry<Method, ResolvedJavaMethod> e : methods.entrySet()) {
            ResolvedJavaMethod m = e.getValue();
            byte[] code = m.getCode();
            if (code == null) {
                assertTrue(m.getCodeSize() == 0);
            } else {
                if (m.isAbstract()) {
                    assertTrue(code.length == 0);
                } else if (!m.isNative()) {
                    assertTrue(code.length > 0);
                }
            }
        }
    }

    /**
     * @see ResolvedJavaMethod#getCodeSize()
     */
    @Test
    public void getCodeSizeTest() {
        for (Map.Entry<Method, ResolvedJavaMethod> e : methods.entrySet()) {
            ResolvedJavaMethod m = e.getValue();
            int codeSize = m.getCodeSize();
            if (m.isAbstract()) {
                assertTrue(codeSize == 0);
            } else if (!m.isNative()) {
                assertTrue(codeSize > 0);
            }
        }
    }

    @Test
    public void getModifiersTest() {
        for (Map.Entry<Method, ResolvedJavaMethod> e : methods.entrySet()) {
            ResolvedJavaMethod m = e.getValue();
            int expected = e.getKey().getModifiers() & Modifier.methodModifiers();
            int actual = m.getModifiers();
            assertEquals(expected, actual);
        }
    }

    /**
     * @see ResolvedJavaMethod#isClassInitializer()
     */
    @Test
    public void isClassInitializerTest() {
        for (Map.Entry<Method, ResolvedJavaMethod> e : methods.entrySet()) {
            // Class initializers are hidden from reflection
            ResolvedJavaMethod m = e.getValue();
            assertFalse(m.isClassInitializer());
        }
        for (Map.Entry<Constructor<?>, ResolvedJavaMethod> e : constructors.entrySet()) {
            ResolvedJavaMethod m = e.getValue();
            assertFalse(m.isClassInitializer());
        }
    }

    @Test
    public void isConstructorTest() {
        for (Map.Entry<Method, ResolvedJavaMethod> e : methods.entrySet()) {
            ResolvedJavaMethod m = e.getValue();
            assertFalse(m.isConstructor());
        }
        for (Map.Entry<Constructor<?>, ResolvedJavaMethod> e : constructors.entrySet()) {
            ResolvedJavaMethod m = e.getValue();
            assertTrue(m.isConstructor());
        }
    }

    @Test
    public void isSyntheticTest() {
        for (Map.Entry<Method, ResolvedJavaMethod> e : methods.entrySet()) {
            ResolvedJavaMethod m = e.getValue();
            assertEquals(e.getKey().isSynthetic(), m.isSynthetic());
        }
        for (Map.Entry<Constructor<?>, ResolvedJavaMethod> e : constructors.entrySet()) {
            ResolvedJavaMethod m = e.getValue();
            assertEquals(e.getKey().isSynthetic(), m.isSynthetic());
        }
    }

    @Test
    public void isSynchronizedTest() {
        for (Map.Entry<Method, ResolvedJavaMethod> e : methods.entrySet()) {
            ResolvedJavaMethod m = e.getValue();
            assertEquals(Modifier.isSynchronized(e.getKey().getModifiers()), m.isSynchronized());
        }
        for (Map.Entry<Constructor<?>, ResolvedJavaMethod> e : constructors.entrySet()) {
            ResolvedJavaMethod m = e.getValue();
            assertEquals(Modifier.isSynchronized(e.getKey().getModifiers()), m.isSynchronized());
        }
    }

    @Test
    public void canBeStaticallyBoundTest() {
        for (Map.Entry<Method, ResolvedJavaMethod> e : methods.entrySet()) {
            ResolvedJavaMethod m = e.getValue();
            assertEquals(m.canBeStaticallyBound(), canBeStaticallyBound(e.getKey()));
        }
        for (Map.Entry<Constructor<?>, ResolvedJavaMethod> e : constructors.entrySet()) {
            ResolvedJavaMethod m = e.getValue();
            assertEquals(m.canBeStaticallyBound(), canBeStaticallyBound(e.getKey()));
        }
    }

    private static boolean canBeStaticallyBound(Member method) {
        int modifiers = method.getModifiers();
        return (Modifier.isFinal(modifiers) || Modifier.isPrivate(modifiers) || Modifier.isStatic(modifiers) || Modifier.isFinal(method.getDeclaringClass().getModifiers())) &&
                        !Modifier.isAbstract(modifiers);
    }

    private static String methodWithExceptionHandlers(String p1, Object o2) {
        try {
            return p1.substring(100) + o2.toString();
        } catch (IndexOutOfBoundsException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            e.printStackTrace();
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Test
    public void getExceptionHandlersTest() throws NoSuchMethodException {
        ResolvedJavaMethod method = metaAccess.lookupJavaMethod(getClass().getDeclaredMethod("methodWithExceptionHandlers", String.class, Object.class));
        ExceptionHandler[] handlers = method.getExceptionHandlers();
        assertNotNull(handlers);
        assertEquals(handlers.length, 3);
        handlers[0].getCatchType().equals(metaAccess.lookupJavaType(IndexOutOfBoundsException.class));
        handlers[1].getCatchType().equals(metaAccess.lookupJavaType(NullPointerException.class));
        handlers[2].getCatchType().equals(metaAccess.lookupJavaType(RuntimeException.class));
    }

    private static String nullPointerExceptionOnFirstLine(Object o, String ignored) {
        return o.toString() + ignored;
    }

    @Test
    public void asStackTraceElementTest() throws NoSuchMethodException {
        try {
            nullPointerExceptionOnFirstLine(null, "ignored");
            Assert.fail("should not reach here");
        } catch (NullPointerException e) {
            StackTraceElement expected = e.getStackTrace()[0];
            ResolvedJavaMethod method = metaAccess.lookupJavaMethod(getClass().getDeclaredMethod("nullPointerExceptionOnFirstLine", Object.class, String.class));
            StackTraceElement actual = method.asStackTraceElement(0);
            assertEquals(expected, actual);
        }
    }

    @Test
    public void getConstantPoolTest() {
        for (Map.Entry<Method, ResolvedJavaMethod> e : methods.entrySet()) {
            ResolvedJavaMethod m = e.getValue();
            ConstantPool cp = m.getConstantPool();
            assertTrue(cp.length() > 0);
        }
    }

    @Test(timeout = 1000L)
    public void getAnnotationTest() throws NoSuchMethodException {
        ResolvedJavaMethod method = metaAccess.lookupJavaMethod(getClass().getDeclaredMethod("getAnnotationTest"));
        Test annotation = method.getAnnotation(Test.class);
        assertNotNull(annotation);
        assertEquals(1000L, annotation.timeout());
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface NonNull {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface Special {
    }

    private static native void methodWithAnnotatedParameters(@NonNull HashMap<String, String> p1, @Special @NonNull Class<? extends Annotation> p2);

    @Test
    public void getParameterAnnotationsTest() throws NoSuchMethodException {
        ResolvedJavaMethod method = metaAccess.lookupJavaMethod(getClass().getDeclaredMethod("methodWithAnnotatedParameters", HashMap.class, Class.class));
        Annotation[][] annotations = method.getParameterAnnotations();
        assertEquals(2, annotations.length);
        assertEquals(1, annotations[0].length);
        assertEquals(NonNull.class, annotations[0][0].annotationType());
        assertEquals(2, annotations[1].length);
        assertEquals(Special.class, annotations[1][0].annotationType());
        assertEquals(NonNull.class, annotations[1][1].annotationType());
    }

    @Test
    public void getGenericParameterTypesTest() throws NoSuchMethodException {
        ResolvedJavaMethod method = metaAccess.lookupJavaMethod(getClass().getDeclaredMethod("methodWithAnnotatedParameters", HashMap.class, Class.class));
        Type[] genericParameterTypes = method.getGenericParameterTypes();
        assertEquals(2, genericParameterTypes.length);
        assertEquals("java.util.HashMap<java.lang.String, java.lang.String>", genericParameterTypes[0].toString());
        assertEquals("java.lang.Class<? extends java.lang.annotation.Annotation>", genericParameterTypes[1].toString());
    }

    @Test
    public void getMaxLocalsTest() throws NoSuchMethodException {
        ResolvedJavaMethod method1 = metaAccess.lookupJavaMethod(getClass().getDeclaredMethod("methodWithAnnotatedParameters", HashMap.class, Class.class));
        ResolvedJavaMethod method2 = metaAccess.lookupJavaMethod(getClass().getDeclaredMethod("nullPointerExceptionOnFirstLine", Object.class, String.class));
        assertEquals(0, method1.getMaxLocals());
        assertEquals(2, method2.getMaxLocals());

    }

    @Test
    public void getMaxStackSizeTest() throws NoSuchMethodException {
        ResolvedJavaMethod method1 = metaAccess.lookupJavaMethod(getClass().getDeclaredMethod("methodWithAnnotatedParameters", HashMap.class, Class.class));
        ResolvedJavaMethod method2 = metaAccess.lookupJavaMethod(getClass().getDeclaredMethod("nullPointerExceptionOnFirstLine", Object.class, String.class));
        assertEquals(0, method1.getMaxStackSize());
        // some versions of javac produce bytecode with a stacksize of 2 for this method
        // JSR 292 also sometimes need one more stack slot
        int method2StackSize = method2.getMaxStackSize();
        assertTrue(2 <= method2StackSize && method2StackSize <= 4);
    }

    @Test
    public void isDefaultTest() {
        for (Map.Entry<Method, ResolvedJavaMethod> e : methods.entrySet()) {
            ResolvedJavaMethod m = e.getValue();
            assertEquals(e.getKey().isDefault(), m.isDefault());
        }
        for (Map.Entry<Constructor<?>, ResolvedJavaMethod> e : constructors.entrySet()) {
            ResolvedJavaMethod m = e.getValue();
            assertFalse(m.isDefault());
        }
    }

    private Method findTestMethod(Method apiMethod) {
        String testName = apiMethod.getName() + "Test";
        for (Method m : getClass().getDeclaredMethods()) {
            if (m.getName().equals(testName) && m.getAnnotation(Test.class) != null) {
                return m;
            }
        }
        return null;
    }

    // @formatter:off
    private static final String[] untestedApiMethods = {
        "invoke",
        "newInstance",
        "getDeclaringClass",
        "getEncoding",
        "getProfilingInfo",
        "reprofile",
        "getCompilerStorage",
        "canBeInlined",
        "shouldBeInlined",
        "getLineNumberTable",
        "getLocalVariableTable",
        "isInVirtualMethodTable",
        "toParameterTypes",
        "getParameterAnnotation"
    };
    // @formatter:on

    /**
     * Ensures that any new methods added to {@link ResolvedJavaMethod} either have a test written
     * for them or are added to {@link #untestedApiMethods}.
     */
    @Test
    public void testCoverage() {
        Set<String> known = new HashSet<>(Arrays.asList(untestedApiMethods));
        for (Method m : ResolvedJavaMethod.class.getDeclaredMethods()) {
            if (findTestMethod(m) == null) {
                assertTrue("test missing for " + m, known.contains(m.getName()));
            } else {
                assertFalse("test should be removed from untestedApiMethods" + m, known.contains(m.getName()));
            }
        }
    }
}
