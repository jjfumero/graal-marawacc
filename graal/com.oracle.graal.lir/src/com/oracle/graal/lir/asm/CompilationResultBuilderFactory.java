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
package com.oracle.graal.lir.asm;

import jdk.internal.jvmci.code.CodeCacheProvider;
import jdk.internal.jvmci.code.CompilationResult;

import com.oracle.graal.asm.Assembler;
import com.oracle.graal.compiler.common.spi.ForeignCallsProvider;
import com.oracle.graal.lir.framemap.FrameMap;

/**
 * Factory class for creating {@link CompilationResultBuilder}s.
 */
public interface CompilationResultBuilderFactory {

    /**
     * Creates a new {@link CompilationResultBuilder}.
     */
    CompilationResultBuilder createBuilder(CodeCacheProvider codeCache, ForeignCallsProvider foreignCalls, FrameMap frameMap, Assembler asm, FrameContext frameContext,
                    CompilationResult compilationResult);

    /**
     * The default factory creates a standard {@link CompilationResultBuilder}.
     */
    CompilationResultBuilderFactory Default = new CompilationResultBuilderFactory() {

        public CompilationResultBuilder createBuilder(CodeCacheProvider codeCache, ForeignCallsProvider foreignCalls, FrameMap frameMap, Assembler asm, FrameContext frameContext,
                        CompilationResult compilationResult) {
            return new CompilationResultBuilder(codeCache, foreignCalls, frameMap, asm, frameContext, compilationResult);
        }
    };
}
