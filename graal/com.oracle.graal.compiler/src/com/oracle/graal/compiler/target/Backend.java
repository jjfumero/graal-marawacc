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
package com.oracle.graal.compiler.target;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.nodes.*;

/**
 * The {@code Backend} class represents a compiler backend for Graal.
 */
public abstract class Backend {

    private final CodeCacheProvider runtime;
    public final TargetDescription target;

    protected Backend(CodeCacheProvider runtime, TargetDescription target) {
        this.runtime = runtime;
        this.target = target;
    }

    public CodeCacheProvider runtime() {
        return runtime;
    }

    public FrameMap newFrameMap() {
        return new FrameMap(runtime, target, runtime.lookupRegisterConfig());
    }

    public abstract LIRGenerator newLIRGenerator(StructuredGraph graph, FrameMap frameMap, ResolvedJavaMethod method, LIR lir);

    public abstract TargetMethodAssembler newAssembler(LIRGenerator lirGen, CompilationResult compilationResult);

    /**
     * Emits the code for a given method. This includes any architecture/runtime specific
     * prefix/suffix. A prefix typically contains the code for setting up the frame, spilling
     * callee-save registers, stack overflow checking, handling multiple entry points etc. A suffix
     * may contain out-of-line stubs and method end guard instructions.
     */
    public abstract void emitCode(TargetMethodAssembler tasm, ResolvedJavaMethod method, LIRGenerator lirGen);
}
