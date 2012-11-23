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

package com.oracle.graal.hotspot.bridge;

import java.lang.reflect.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.meta.*;

/**
 * Entries into the HotSpot VM from Java code.
 */
public class CompilerToVMImpl implements CompilerToVM {

    @Override
    public native long getMetaspaceMethod(Method reflectionMethod, HotSpotResolvedJavaType[] resultHolder);

    @Override
    public native HotSpotResolvedJavaField getJavaField(Field reflectionMethod);

    @Override
    public native byte[] initializeBytecode(long metaspaceMethod, byte[] code);

    @Override
    public native String getSignature(long metaspaceMethod);

    @Override
    public native ExceptionHandler[] initializeExceptionHandlers(long metaspaceMethod, ExceptionHandler[] handlers);

    @Override
    public native boolean hasBalancedMonitors(long metaspaceMethod);

    @Override
    public native boolean isMethodCompilable(long metaspaceMethod);

    @Override
    public native long getUniqueConcreteMethod(long metaspaceMethod, HotSpotResolvedJavaType[] resultHolder);

    @Override
    public native int getInvocationCount(long metaspaceMethod);

    @Override
    public native JavaType lookupType(String name, HotSpotResolvedJavaType accessingClass, boolean eagerResolve);

    @Override
    public native Object lookupConstantInPool(HotSpotResolvedJavaType pool, int cpi);

    @Override
    public native JavaMethod lookupMethodInPool(HotSpotResolvedJavaType pool, int cpi, byte opcode);

    @Override
    public native JavaType lookupTypeInPool(HotSpotResolvedJavaType pool, int cpi);

    @Override
    public native void lookupReferencedTypeInPool(HotSpotResolvedJavaType pool, int cpi, byte opcode);

    @Override
    public native JavaField lookupFieldInPool(HotSpotResolvedJavaType pool, int cpi, byte opcode);

    @Override
    public native HotSpotInstalledCode installCode(HotSpotCompilationResult comp, HotSpotInstalledCode code, HotSpotCodeInfo info);

    @Override
    public native void initializeConfiguration(HotSpotVMConfig config);

    @Override
    public native JavaMethod resolveMethod(HotSpotResolvedJavaType klass, String name, String signature);

    @Override
    public native boolean isSubtypeOf(HotSpotResolvedJavaType klass, JavaType other);

    @Override
    public native JavaType getLeastCommonAncestor(HotSpotResolvedJavaType thisType, HotSpotResolvedJavaType otherType);

    @Override
    public native JavaType getUniqueConcreteSubtype(HotSpotResolvedJavaType klass);

    @Override
    public native boolean isTypeInitialized(HotSpotResolvedJavaType klass);

    @Override
    public native void initializeType(HotSpotResolvedJavaType klass);

    @Override
    public native void initializeMethod(long metaspaceMethod, HotSpotResolvedJavaMethod method);

    @Override
    public native void initializeMethodData(long metaspaceMethodData, HotSpotMethodData methodData);

    @Override
    public native ResolvedJavaType getResolvedType(Class<?> javaClass);

    @Override
    public int getArrayLength(Constant array) {
        return Array.getLength(array.asObject());
    }

    @Override
    public JavaType getJavaType(Constant constant) {
        Object o = constant.asObject();
        if (o == null) {
            return null;
        }
        return HotSpotResolvedJavaType.fromClass(o.getClass());
    }

    @Override
    public native HotSpotResolvedJavaField[] getInstanceFields(HotSpotResolvedJavaType klass);

    @Override
    public native int getCompiledCodeSize(long metaspaceMethod);

    @Override
    public native long getMaxCallTargetOffset(long stub);

    @Override
    public native String disassembleNative(byte[] code, long address);

    @Override
    public native StackTraceElement getStackTraceElement(long metaspaceMethod, int bci);

    @Override
    public native Object executeCompiledMethod(long metaspaceMethod, long nmethod, Object arg1, Object arg2, Object arg3);

    @Override
    public native Object executeCompiledMethodVarargs(long metaspaceMethod, long nmethod, Object... args);

    @Override
    public native int getVtableEntryOffset(long metaspaceMethod);

    @Override
    public native long[] getDeoptedLeafGraphIds();

    @Override
    public native String decodePC(long pc);

    @Override
    public native long getPrototypeMarkWord(HotSpotResolvedJavaType type);
}
