/*
 * Copyright (c) 2012, 2014, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.hotspot.HotSpotBackend.*;
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.*;
import static com.oracle.jvmci.amd64.AMD64.*;
import static com.oracle.jvmci.code.ValueUtil.*;

import com.oracle.graal.hotspot.stubs.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.StandardOp.BlockEndOp;
import com.oracle.graal.lir.amd64.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.jvmci.asm.amd64.*;
import com.oracle.jvmci.code.*;
import com.oracle.jvmci.meta.*;

/**
 * Removes the current frame and jumps to the {@link UnwindExceptionToCallerStub}.
 */
@Opcode("UNWIND")
final class AMD64HotSpotUnwindOp extends AMD64HotSpotEpilogueOp implements BlockEndOp {
    public static final LIRInstructionClass<AMD64HotSpotUnwindOp> TYPE = LIRInstructionClass.create(AMD64HotSpotUnwindOp.class);

    @Use({REG}) protected RegisterValue exception;

    AMD64HotSpotUnwindOp(RegisterValue exception, AllocatableValue savedRbp) {
        super(TYPE, savedRbp);
        this.exception = exception;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        leaveFrameAndRestoreRbp(crb, masm);

        ForeignCallLinkage linkage = crb.foreignCalls.lookupForeignCall(UNWIND_EXCEPTION_TO_CALLER);
        CallingConvention cc = linkage.getOutgoingCallingConvention();
        assert cc.getArgumentCount() == 2;
        assert exception.equals(cc.getArgument(0));

        // Get return address (is on top of stack after leave).
        Register returnAddress = asRegister(cc.getArgument(1));
        masm.movq(returnAddress, new AMD64Address(rsp, 0));

        AMD64Call.directJmp(crb, masm, linkage);
    }
}
