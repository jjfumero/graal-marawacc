/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.STACK;
import static jdk.vm.ci.code.ValueUtil.asRegister;
import static jdk.vm.ci.sparc.SPARC.STACK_BIAS;
import static jdk.vm.ci.sparc.SPARCKind.XWORD;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.LIRKind;
import jdk.vm.ci.meta.Value;

import com.oracle.graal.asm.sparc.SPARCAddress;
import com.oracle.graal.asm.sparc.SPARCMacroAssembler;
import com.oracle.graal.lir.LIRInstructionClass;
import com.oracle.graal.lir.Opcode;
import com.oracle.graal.lir.asm.CompilationResultBuilder;
import com.oracle.graal.lir.sparc.SPARCLIRInstruction;
import com.oracle.graal.lir.sparc.SPARCMove;
import com.oracle.graal.lir.sparc.SPARCTailDelayedLIRInstruction;

@Opcode("CRUNTIME_CALL_PROLOGUE")
final class SPARCHotSpotCRuntimeCallPrologueOp extends SPARCLIRInstruction implements SPARCTailDelayedLIRInstruction {
    public static final LIRInstructionClass<SPARCHotSpotCRuntimeCallPrologueOp> TYPE = LIRInstructionClass.create(SPARCHotSpotCRuntimeCallPrologueOp.class);
    public static final SizeEstimate SIZE = SizeEstimate.create(10);

    private final int threadLastJavaSpOffset;
    private final Register thread;
    private final Register stackPointer;
    @Def({REG, STACK}) protected Value threadTemp;
    @Temp({REG}) protected AllocatableValue spScratch;

    public SPARCHotSpotCRuntimeCallPrologueOp(int threadLastJavaSpOffset, Register thread, Register stackPointer, Value threadTemp, AllocatableValue spScratch) {
        super(TYPE, SIZE);
        this.threadLastJavaSpOffset = threadLastJavaSpOffset;
        this.thread = thread;
        this.stackPointer = stackPointer;
        this.threadTemp = threadTemp;
        this.spScratch = spScratch;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
        // Save last Java frame.
        Register scratchRegister = asRegister(spScratch);
        masm.add(stackPointer, STACK_BIAS, scratchRegister);
        masm.stx(scratchRegister, new SPARCAddress(thread, threadLastJavaSpOffset));

        // Save the thread register when calling out to the runtime.
        SPARCMove.move(crb, masm, threadTemp, thread.asValue(LIRKind.value(XWORD)), getDelayedControlTransfer());
    }
}
