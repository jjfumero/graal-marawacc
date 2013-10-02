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
package com.oracle.graal.hotspot.sparc;

import static com.oracle.graal.hotspot.HotSpotGraalRuntime.*;
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.*;
import static com.oracle.graal.phases.GraalOptions.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.sparc.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.lir.sparc.SPARCControlFlow.ReturnOp;
import com.oracle.graal.sparc.*;

/**
 * Returns from a function.
 */
@Opcode("RETURN")
final class SPARCHotSpotReturnOp extends SPARCHotSpotEpilogueOp {

    @Use({REG, ILLEGAL}) protected Value value;
    private final boolean isStub;

    SPARCHotSpotReturnOp(Value value, boolean isStub) {
        this.value = value;
        this.isStub = isStub;
    }

    @Override
    public void emitCode(TargetMethodAssembler tasm, SPARCMacroAssembler masm) {
        if (!isStub && (tasm.frameContext != null || !OptEliminateSafepoints.getValue())) {
            // Using the same scratch register as LIR_Assembler::return_op
            // in c1_LIRAssembler_sparc.cpp
            SPARCHotSpotSafepointOp.emitCode(tasm, masm, graalRuntime().getConfig(), true, null, SPARC.l0);
        }
        ReturnOp.emitCodeHelper(tasm, masm);
    }
}
