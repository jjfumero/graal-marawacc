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
package com.oracle.graal.compiler.test;

import static org.junit.Assert.*;

import java.io.*;
import java.lang.reflect.*;

import org.junit.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.meta.ProfilingInfo.TriState;

public class ProfilingInfoTest extends GraalCompilerTest {

    private static final int N = 100;

    @Test
    public void testBranchTakenProbability() {
        ProfilingInfo info = profile("branchProbabilitySnippet", 0);
        assertEquals(0.0, info.getBranchTakenProbability(1));
        assertEquals(100, info.getExecutionCount(1));
        assertEquals(-1.0, info.getBranchTakenProbability(8));
        assertEquals(0, info.getExecutionCount(8));

        info = profile("branchProbabilitySnippet", 1);
        assertEquals(1.0, info.getBranchTakenProbability(1));
        assertEquals(100, info.getExecutionCount(1));
        assertEquals(0.0, info.getBranchTakenProbability(8));
        assertEquals(100, info.getExecutionCount(8));

        info = profile("branchProbabilitySnippet", 2);
        assertEquals(1.0, info.getBranchTakenProbability(1));
        assertEquals(100, info.getExecutionCount(1));
        assertEquals(1.0, info.getBranchTakenProbability(8));
        assertEquals(100, info.getExecutionCount(8));
    }

    public static int branchProbabilitySnippet(int value) {
        if (value == 0) {
            return -1;
        } else if (value == 1) {
            return -2;
        } else {
            return -3;
        }
    }

    @Test
    public void testSwitchProbabilities() {
        ProfilingInfo info = profile("switchProbabilitySnippet", 0);
        assertEquals(new double[]{1.0, 0.0, 0.0}, info.getSwitchProbabilities(1));

        info = profile("switchProbabilitySnippet", 1);
        assertEquals(new double[]{0.0, 1.0, 0.0}, info.getSwitchProbabilities(1));

        info = profile("switchProbabilitySnippet", 2);
        assertEquals(new double[]{0.0, 0.0, 1.0}, info.getSwitchProbabilities(1));
    }

    public static int switchProbabilitySnippet(int value) {
        switch (value) {
            case 0:
                return -1;
            case 1:
                return -2;
            default:
                return -3;
        }
    }

    @Test
    public void testTypeProfileInvokeVirtual() {
        testTypeProfile("invokeVirtualSnippet", 1);
    }

    public static int invokeVirtualSnippet(Object obj) {
        return obj.hashCode();
    }

    @Test
    public void testTypeProfileInvokeInterface() {
        testTypeProfile("invokeInterfaceSnippet", 1);
    }

    public static int invokeInterfaceSnippet(CharSequence a) {
        return a.length();
    }

    @Test
    public void testTypeProfileCheckCast() {
        testTypeProfile("checkCastSnippet", 1);
    }

    public static Serializable checkCastSnippet(Object obj) {
        return (Serializable) obj;
    }

    @Test
    public void testTypeProfileInstanceOf() {
        testTypeProfile("instanceOfSnippet", 1);
    }

    public static boolean instanceOfSnippet(Object obj) {
        return obj instanceof Serializable;
    }

    private void testTypeProfile(String methodName, int bci) {
        ResolvedJavaType stringType = runtime.lookupJavaType(String.class);
        ResolvedJavaType stringBuilderType = runtime.lookupJavaType(StringBuilder.class);

        ProfilingInfo info = profile(methodName, "ABC");
        JavaTypeProfile typeProfile = info.getTypeProfile(bci);
        assertEquals(0.0, typeProfile.getNotRecordedProbability());
        assertEquals(1, typeProfile.getTypes().length);
        assertEquals(stringType, typeProfile.getTypes()[0].getType());
        assertEquals(1.0, typeProfile.getTypes()[0].getProbability());

        continueProfiling(methodName, new StringBuilder());
        typeProfile = info.getTypeProfile(bci);
        assertEquals(0.0, typeProfile.getNotRecordedProbability());
        assertEquals(2, typeProfile.getTypes().length);
        assertEquals(stringType, typeProfile.getTypes()[0].getType());
        assertEquals(stringBuilderType, typeProfile.getTypes()[1].getType());
        assertEquals(0.5, typeProfile.getTypes()[0].getProbability());
        assertEquals(0.5, typeProfile.getTypes()[1].getProbability());
    }

    @Test
    public void testExceptionSeen() {
        ProfilingInfo info = profile("nullPointerExceptionSnippet", (Object) null);
        assertEquals(TriState.TRUE, info.getExceptionSeen(1));

        info = profile("nullPointerExceptionSnippet", 5);
        assertEquals(TriState.FALSE, info.getExceptionSeen(1));

        info = profile("arrayIndexOutOfBoundsExceptionSnippet", new int[0]);
        assertEquals(TriState.TRUE, info.getExceptionSeen(2));

        info = profile("arrayIndexOutOfBoundsExceptionSnippet", new int[1]);
        assertEquals(TriState.FALSE, info.getExceptionSeen(2));

        info = profile("checkCastExceptionSnippet", 5);
        assertEquals(TriState.TRUE, info.getExceptionSeen(1));

        info = profile("checkCastExceptionSnippet", "ABC");
        assertEquals(TriState.FALSE, info.getExceptionSeen(1));

        info = profile("invokeWithExceptionSnippet", true);
        assertEquals(TriState.TRUE, info.getExceptionSeen(1));

        info = profile("invokeWithExceptionSnippet", false);
        assertEquals(TriState.FALSE, info.getExceptionSeen(1));
    }

    public static int nullPointerExceptionSnippet(Object obj) {
        try {
            return obj.hashCode();
        } catch (NullPointerException e) {
            return 1;
        }
    }

    public static int arrayIndexOutOfBoundsExceptionSnippet(int[] array) {
        try {
            return array[0];
        } catch (ArrayIndexOutOfBoundsException e) {
            return 1;
        }
    }

    public static int checkCastExceptionSnippet(Object obj) {
        try {
            return ((String) obj).length();
        } catch (ClassCastException e) {
            return 1;
        }
    }

    public static int invokeWithExceptionSnippet(boolean doThrow) {
        try {
            return throwException(doThrow);
        } catch (IllegalArgumentException e) {
            return 1;
        }
    }

    private static int throwException(boolean doThrow) {
        if (doThrow) {
            throw new IllegalArgumentException();
        } else {
            return 1;
        }
    }

    @Test
    public void testNullSeen() {
        ProfilingInfo info = profile("instanceOfSnippet", 1);
        assertEquals(TriState.FALSE, info.getNullSeen(1));

        continueProfiling("instanceOfSnippet", "ABC");
        assertEquals(TriState.FALSE, info.getNullSeen(1));

        continueProfiling("instanceOfSnippet", (Object) null);
        assertEquals(TriState.TRUE, info.getNullSeen(1));

        continueProfiling("instanceOfSnippet", 0.0);
        assertEquals(TriState.TRUE, info.getNullSeen(1));

        info = profile("instanceOfSnippet", (Object) null);
        assertEquals(TriState.TRUE, info.getNullSeen(1));
    }

    @Test
    public void testDeoptimizationCount() {
        // TODO (chaeubl): implement
    }

    private ProfilingInfo profile(String methodName, Object... args) {
        return profile(true, methodName, args);
    }

    private void continueProfiling(String methodName, Object... args) {
        profile(false, methodName, args);
    }

    private ProfilingInfo profile(boolean resetProfile, String methodName, Object... args) {
        Method method = getMethod(methodName);
        Assert.assertTrue(Modifier.isStatic(method.getModifiers()));

        ResolvedJavaMethod javaMethod = runtime.lookupJavaMethod(method);
        if (resetProfile) {
            javaMethod.reprofile();
        }

        for (int i = 0; i < N; ++i) {
            try {
                method.invoke(null, args);
            } catch (Throwable e) {
                fail("method should not throw an exception: " + e.toString());
            }
        }

        return javaMethod.getProfilingInfo();
    }
}
