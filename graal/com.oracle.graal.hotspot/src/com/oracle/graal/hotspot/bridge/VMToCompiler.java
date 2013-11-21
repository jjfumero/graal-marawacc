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

import java.io.*;
import java.util.concurrent.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.hotspot.debug.*;
import com.oracle.graal.hotspot.meta.*;

/**
 * Calls from HotSpot into Java.
 */
public interface VMToCompiler {

    /**
     * Compiles a method to machine code. This method is called from the VM
     * (VMToCompiler::compileMethod).
     */
    void compileMethod(long metaspaceMethod, HotSpotResolvedObjectType holder, int entryBCI, boolean blocking);

    /**
     * Notifies this object of statistics for a completed compilation.
     * 
     * @param id the identifier of the compilation
     * @param method the method compiled
     * @param osr specifies if the compilation was for on-stack-replacement
     * @param processedBytecodes the number of bytecodes processed during the compilation, including
     *            the bytecodes of all inlined methods
     * @param time the amount time spent compiling {@code method}
     * @param timeUnit the units of {@code time}
     * @param installedCode the nmethod installed as a result of the compilation
     */
    void notifyCompilationDone(int id, HotSpotResolvedJavaMethod method, boolean osr, int processedBytecodes, long time, TimeUnit timeUnit, HotSpotInstalledCode installedCode);

    void shutdownCompiler() throws Exception;

    void startCompiler(boolean bootstrapEnabled, long statsAddress) throws Throwable;

    void bootstrap() throws Throwable;

    void compileTheWorld() throws Throwable;

    PrintStream log();

    JavaMethod createUnresolvedJavaMethod(String name, String signature, JavaType holder);

    JavaField createJavaField(JavaType holder, String name, JavaType type, int offset, int flags, boolean internal);

    ResolvedJavaMethod createResolvedJavaMethod(JavaType holder, long metaspaceMethod);

    JavaType createPrimitiveJavaType(int basicType);

    JavaType createUnresolvedJavaType(String name);

    /**
     * Creates a resolved Java type.
     * 
     * @param metaspaceKlass the metaspace Klass object for the type
     * @param name the {@linkplain JavaType#getName() name} of the type
     * @param simpleName a simple, unqualified name for the type
     * @param javaMirror the {@link Class} mirror
     * @param sizeOrSpecies the size of an instance of the type, or
     *            {@link HotSpotResolvedObjectType#INTERFACE_SPECIES_VALUE} or
     *            {@link HotSpotResolvedObjectType#ARRAY_SPECIES_VALUE}
     * @return the resolved type associated with {@code javaMirror} which may not be the type
     *         instantiated by this call in the case of another thread racing to create the same
     *         type
     */
    ResolvedJavaType createResolvedJavaType(long metaspaceKlass, String name, String simpleName, Class javaMirror, int sizeOrSpecies);

    Constant createConstant(Kind kind, long value);

    Constant createConstantFloat(float value);

    Constant createConstantDouble(double value);

    Constant createConstantObject(Object object);

    LocalImpl createLocalImpl(String name, String type, HotSpotResolvedObjectType holder, int bciStart, int bciEnd, int slot);
}
