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
package com.oracle.graal.hotspot.ptx;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.nodes.*;

/**
 * HotSpot PTX specific backend.
 */
public class PTXHotSpotBackend extends HotSpotBackend {

    public PTXHotSpotBackend(HotSpotRuntime runtime, TargetDescription target) {
        super(runtime, target);
    }

    @Override
    public boolean shouldAllocateRegisters() {
        throw new InternalError("NYI");
    }

    @Override
    public FrameMap newFrameMap() {
        throw new InternalError("NYI");
    }

    @Override
    public TargetMethodAssembler newAssembler(LIRGenerator lirGen, CompilationResult compilationResult) {
        throw new InternalError("NYI");
    }

    @Override
    public void emitCode(TargetMethodAssembler tasm, LIRGenerator lirGen, ResolvedJavaMethod installedCodeOwner) {
        throw new InternalError("NYI");
    }

    @Override
    protected AbstractAssembler createAssembler(FrameMap frameMap) {
        throw new InternalError("NYI");
    }

    @Override
    public LIRGenerator newLIRGenerator(StructuredGraph graph, FrameMap frameMap, CallingConvention cc, LIR lir) {
        throw new InternalError("NYI");
    }
}
