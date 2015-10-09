/*
 * Copyright (c) 2012, 2015, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.lir.LIRInstruction.OperandFlag.REG;
import static jdk.vm.ci.code.ValueUtil.asRegister;
import static jdk.vm.ci.sparc.SPARC.g0;
import static jdk.vm.ci.sparc.SPARC.i0;
import static jdk.vm.ci.sparc.SPARC.i1;
import static jdk.vm.ci.sparc.SPARC.i2;
import static jdk.vm.ci.sparc.SPARC.l7;
import static jdk.vm.ci.sparc.SPARC.sp;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.AllocatableValue;

import com.oracle.graal.asm.sparc.SPARCAddress;
import com.oracle.graal.asm.sparc.SPARCAssembler.CC;
import com.oracle.graal.asm.sparc.SPARCAssembler.ConditionFlag;
import com.oracle.graal.asm.sparc.SPARCMacroAssembler;
import com.oracle.graal.asm.sparc.SPARCMacroAssembler.ScratchRegister;
import com.oracle.graal.lir.LIRInstructionClass;
import com.oracle.graal.lir.Opcode;
import com.oracle.graal.lir.asm.CompilationResultBuilder;

/**
 * Sets up the arguments for an exception handler in the callers frame, removes the current frame
 * and jumps to the handler.
 */
@Opcode("JUMP_TO_EXCEPTION_HANDLER_IN_CALLER")
final class SPARCHotSpotJumpToExceptionHandlerInCallerOp extends SPARCHotSpotEpilogueOp {

    public static final LIRInstructionClass<SPARCHotSpotJumpToExceptionHandlerInCallerOp> TYPE = LIRInstructionClass.create(SPARCHotSpotJumpToExceptionHandlerInCallerOp.class);
    public static final SizeEstimate SIZE = SizeEstimate.create(5);

    @Use(REG) AllocatableValue handlerInCallerPc;
    @Use(REG) AllocatableValue exception;
    @Use(REG) AllocatableValue exceptionPc;
    private final Register thread;
    private final int isMethodHandleReturnOffset;

    SPARCHotSpotJumpToExceptionHandlerInCallerOp(AllocatableValue handlerInCallerPc, AllocatableValue exception, AllocatableValue exceptionPc, int isMethodHandleReturnOffset, Register thread) {
        super(TYPE, SIZE);
        this.handlerInCallerPc = handlerInCallerPc;
        this.exception = exception;
        this.exceptionPc = exceptionPc;
        this.isMethodHandleReturnOffset = isMethodHandleReturnOffset;
        this.thread = thread;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
        // Move the values up one level to be the input for the next call.
        masm.mov(asRegister(handlerInCallerPc), i2);
        masm.mov(asRegister(exception), i0);
        masm.mov(asRegister(exceptionPc), i1);
        leaveFrame(crb);

        // Restore SP from L7 if the exception PC is a method handle call site.
        SPARCAddress dst = new SPARCAddress(thread, isMethodHandleReturnOffset);
        try (ScratchRegister scratch = masm.getScratchRegister()) {
            Register scratchReg = scratch.getRegister();
            masm.lduw(dst, scratchReg);
            masm.cmp(scratchReg, scratchReg);
            masm.movcc(ConditionFlag.NotZero, CC.Icc, l7, sp);
        }

        masm.jmpl(asRegister(handlerInCallerPc), 0, g0);
        masm.nop();
    }
}
