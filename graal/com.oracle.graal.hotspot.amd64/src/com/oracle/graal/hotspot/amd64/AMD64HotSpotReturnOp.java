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
package com.oracle.graal.hotspot.amd64;

import static com.oracle.graal.hotspot.HotSpotGraalRuntime.*;
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.*;
import static com.oracle.graal.phases.GraalOptions.*;

import com.oracle.graal.amd64.*;
import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.amd64.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.asm.*;

/**
 * Returns from a function.
 */
@Opcode("RETURN")
final class AMD64HotSpotReturnOp extends AMD64HotSpotEpilogueOp {

    @Use({REG, ILLEGAL}) protected Value value;
    private final boolean isStub;

    AMD64HotSpotReturnOp(Value value, boolean isStub) {
        this.value = value;
        this.isStub = isStub;
    }

    private static Register findPollOnReturnScratchRegister() {
        RegisterConfig config = HotSpotGraalRuntime.graalRuntime().getRuntime().getRegisterConfig();
        for (Register r : config.getAllocatableRegisters(Kind.Long)) {
            if (r != config.getReturnRegister(Kind.Long) && r != AMD64.rbp) {
                return r;
            }
        }
        throw GraalInternalError.shouldNotReachHere();
    }

    private static final Register scratchForSafepointOnReturn = findPollOnReturnScratchRegister();

    @Override
    public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
        leaveFrameAndRestoreRbp(tasm, masm);
        if (!isStub && (tasm.frameContext != null || !OptEliminateSafepoints.getValue())) {
            AMD64HotSpotSafepointOp.emitCode(tasm, masm, graalRuntime().getConfig(), true, null, scratchForSafepointOnReturn);
        }
        masm.ret(0);
    }
}
