/*
 * Copyright (c) 2009, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.jvmci.code;

import com.oracle.jvmci.meta.*;
import com.oracle.jvmci.code.CompilationResult.DataPatch;
import com.oracle.jvmci.code.DataSection.Data;

/**
 * Access to code cache related details and requirements.
 */
public interface CodeCacheProvider {

    /**
     * Adds the given compilation result as an implementation of the given method without making it
     * the default implementation.
     *
     * @param method a method to which the executable code is begin added
     * @param compResult the compilation result to be added
     * @param speculationLog the speculation log to be used
     * @return a reference to the compiled and ready-to-run code or throws a
     *         {@link BailoutException} if the code installation failed
     */
    InstalledCode addMethod(ResolvedJavaMethod method, CompilationResult compResult, SpeculationLog speculationLog, InstalledCode predefinedInstalledCode);

    /**
     * Sets the given compilation result as the default implementation of the given method.
     *
     * @param method a method to which the executable code is begin added
     * @param compResult the compilation result to be added
     * @return a reference to the compiled and ready-to-run code or null if the code installation
     *         failed
     */
    InstalledCode setDefaultMethod(ResolvedJavaMethod method, CompilationResult compResult);

    /**
     * Returns a disassembly of some compiled code.
     *
     * @param compResult some compiled code
     * @param installedCode the result of installing the code in {@code compResult} or null if the
     *            code has not yet been installed
     *
     * @return a disassembly. This will be of length 0 if the runtime does not support
     *         disassembling.
     */
    String disassemble(CompilationResult compResult, InstalledCode installedCode);

    /**
     * Gets the register configuration to use when compiling a given method.
     */
    RegisterConfig getRegisterConfig();

    /**
     * Minimum size of the stack area reserved for outgoing parameters. This area is reserved in all
     * cases, even when the compiled method has no regular call instructions.
     *
     * @return the minimum size of the outgoing parameter area in bytes
     */
    int getMinimumOutgoingSize();

    /**
     * Determines if a {@link DataPatch} should be created for a given primitive constant that is
     * part of a {@link CompilationResult}. A data patch is always created for an object constant.
     */
    boolean needsDataPatch(JavaConstant constant);

    /**
     * Create a {@link Data} item for a {@link Constant}, that can be used in a {@link DataPatch}.
     */
    Data createDataItem(Constant constant);

    /**
     * Gets a description of the target architecture.
     */
    TargetDescription getTarget();

    /**
     * Create a new speculation log for the target runtime.
     */
    SpeculationLog createSpeculationLog();
}
