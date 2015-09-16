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
package com.oracle.graal.code;

import jdk.internal.jvmci.code.CodeCacheProvider;
import jdk.internal.jvmci.code.CompilationResult;
import jdk.internal.jvmci.code.InstalledCode;

/**
 * Interface providing capability for disassembling machine code.
 */
public interface DisassemblerProvider {

    /**
     * Gets a textual disassembly of a given compilation result.
     *
     * @param codeCache the object used for code {@link CodeCacheProvider#addMethod code
     *            installation}
     * @param compResult a compilation result
     * @return a non-zero length string containing a disassembly of {@code compResult} or null it
     *         could not be disassembled
     */
    default String disassembleCompiledCode(CodeCacheProvider codeCache, CompilationResult compResult) {
        return null;
    }

    /**
     * Gets a textual disassembly of a given installed code.
     *
     * @param codeCache the object used for code {@link CodeCacheProvider#addMethod code
     *            installation}
     * @param compResult a compiled code that was installed to produce {@code installedCode}. This
     *            will be null if not available.
     * @param installedCode
     * @return a non-zero length string containing a disassembly of {@code code} or null if
     *         {@code code} is {@link InstalledCode#isValid() invalid} or it could not be
     *         disassembled for some other reason
     */
    default String disassembleInstalledCode(CodeCacheProvider codeCache, CompilationResult compResult, InstalledCode installedCode) {
        return null;
    }

    /**
     * Gets the name denoting the format of the disassmembly return by this object.
     */
    String getName();
}
