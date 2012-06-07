/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.cri.ri;

import java.lang.reflect.*;

import com.oracle.max.cri.ci.*;

/**
 * Encapsulates the main functionality of the runtime for the compiler, including access
 * to constant pools, OSR frames, inlining requirements, and runtime calls such as checkcast.
s */
public interface RiRuntime {

    /**
     * Get the size in bytes for locking information on the stack.
     */
    int sizeOfLockData();

    /**
     * Returns a disassembly of the given installed code.
     *
     * @param code the code that should be disassembled
     * @return a disassembly. This will be of length 0 if the runtime does not support disassembling.
     */
    String disassemble(RiCodeInfo code);

    /**
     * Returns the disassembly of the given method in a {@code javap}-like format.
     *
     * @param method the method that should be disassembled
     * @return the disassembly. This will be of length 0 if the runtime does not support disassembling.
     */
    String disassemble(RiResolvedMethod method);

    /**
     * Returns the RiType object representing the base type for the given kind.
     */
    RiResolvedType asRiType(CiKind kind);

    /**
     * Returns the type of the given constant object.
     *
     * @return {@code null} if {@code constant.isNull() || !constant.kind.isObject()}
     */
    RiResolvedType getTypeOf(RiConstant constant);


    RiResolvedType getType(Class<?> clazz);

    /**
     * Used by the canonicalizer to compare objects, since a given runtime might not want to expose the real objects to the compiler.
     *
     * @return true if the two parameters represent the same runtime object, false otherwise
     */
    boolean areConstantObjectsEqual(RiConstant x, RiConstant y);

    /**
     * Gets the register configuration to use when compiling a given method.
     *
     * @param method the top level method of a compilation
     */
    RiRegisterConfig getRegisterConfig(RiMethod method);

    RiRegisterConfig getGlobalStubRegisterConfig();

    /**
     * Custom area on the stack of each compiled method that the VM can use for its own purposes.
     * @return the size of the custom area in bytes
     */
    int getCustomStackAreaSize();

    /**
     * Minimum size of the stack area reserved for outgoing parameters. This area is reserved in all cases, even when
     * the compiled method has no regular call instructions.
     * @return the minimum size of the outgoing parameter area in bytes
     */
    int getMinimumOutgoingSize();

    /**
     * Gets the length of the array that is wrapped in a CiConstant object.
     */
    int getArrayLength(RiConstant array);

    /**
     * Performs any runtime-specific conversion on the object used to describe the target of a call.
     */
    Object asCallTarget(Object target);

    /**
     * Returns the maximum absolute offset of a runtime call target from any position in the code cache or -1
     * when not known or not applicable. Intended for determining the required size of address/offset fields.
     */
    long getMaxCallTargetOffset(CiRuntimeCall rtcall);

    /**
     * Provides the {@link RiMethod} for a {@link Method} obtained via reflection.
     */
    RiResolvedMethod getRiMethod(Method reflectionMethod);

    /**
     * Adds the given machine code as an implementation of the given method without making it the default implementation.
     * @param method a method to which the executable code is begin added
     * @param code the code to be added
     * @param info the object into which details of the installed code will be written.
     *        Ignored if null, otherwise the info is written to index 0 of this array.
     * @return a reference to the compiled and ready-to-run code
     */
    RiCompiledMethod addMethod(RiResolvedMethod method, CiTargetMethod code, RiCodeInfo[] info);

    /**
     * Encodes a deoptimization action and a deoptimization reason in an integer value.
     * @return the encoded value as an integer
     */
    int encodeDeoptActionAndReason(RiDeoptAction action, RiDeoptReason reason);

    /**
     * Converts a RiDeoptReason into an integer value.
     * @return An integer value representing the given RiDeoptReason.
     */
    int convertDeoptReason(RiDeoptReason reason);

    /**
     * Converts a RiDeoptAction into an integer value.
     * @return An integer value representing the given RiDeoptAction.
     */
    int convertDeoptAction(RiDeoptAction action);
}
