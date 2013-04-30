/*
 * Copyright (c) 2011, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.lir.amd64;

import static com.oracle.graal.api.code.ValueUtil.*;
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.amd64.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.LIRInstruction.Opcode;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.nodes.spi.*;

public class AMD64Call {

    public abstract static class CallOp extends AMD64LIRInstruction {

        @Def({REG, ILLEGAL}) protected Value result;
        @Use({REG, STACK}) protected Value[] parameters;
        @Temp protected Value[] temps;
        @State protected LIRFrameState state;

        public CallOp(Value result, Value[] parameters, Value[] temps, LIRFrameState state) {
            this.result = result;
            this.parameters = parameters;
            this.state = state;
            this.temps = temps;
            assert temps != null;
        }

        @Override
        public boolean destroysCallerSavedRegisters() {
            return true;
        }
    }

    public abstract static class MethodCallOp extends CallOp {

        protected final ResolvedJavaMethod callTarget;

        public MethodCallOp(ResolvedJavaMethod callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState state) {
            super(result, parameters, temps, state);
            this.callTarget = callTarget;
        }

    }

    @Opcode("CALL_DIRECT")
    public static class DirectCallOp extends MethodCallOp {

        public DirectCallOp(ResolvedJavaMethod callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState state) {
            super(callTarget, result, parameters, temps, state);
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            directCall(tasm, masm, callTarget, null, true, state);
        }
    }

    @Opcode("CALL_INDIRECT")
    public static class IndirectCallOp extends MethodCallOp {

        @Use({REG}) protected Value targetAddress;

        public IndirectCallOp(ResolvedJavaMethod callTarget, Value result, Value[] parameters, Value[] temps, Value targetAddress, LIRFrameState state) {
            super(callTarget, result, parameters, temps, state);
            this.targetAddress = targetAddress;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            indirectCall(tasm, masm, asRegister(targetAddress), callTarget, state);
        }

        @Override
        protected void verify() {
            super.verify();
            assert isRegister(targetAddress) : "The current register allocator cannot handle variables to be used at call sites, it must be in a fixed register for now";
        }
    }

    public abstract static class RuntimeCallOp extends CallOp {

        protected final RuntimeCallTarget callTarget;

        public RuntimeCallOp(RuntimeCallTarget callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState state) {
            super(result, parameters, temps, state);
            this.callTarget = callTarget;
        }

        @Override
        public boolean destroysCallerSavedRegisters() {
            return callTarget.destroysRegisters();
        }
    }

    @Opcode("CALL_NEAR_RUNTIME")
    public static class DirectNearRuntimeCallOp extends RuntimeCallOp {

        public DirectNearRuntimeCallOp(RuntimeCallTarget callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState state) {
            super(callTarget, result, parameters, temps, state);
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            directCall(tasm, masm, callTarget, null, false, state);
        }
    }

    @Opcode("CALL_FAR_RUNTIME")
    public static class DirectFarRuntimeCallOp extends RuntimeCallOp {

        @Temp({REG}) protected AllocatableValue callTemp;

        public DirectFarRuntimeCallOp(LIRGeneratorTool gen, RuntimeCallTarget callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState state) {
            super(callTarget, result, parameters, temps, state);
            callTemp = gen.newVariable(Kind.Long);
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            directCall(tasm, masm, callTarget, ((RegisterValue) callTemp).getRegister(), false, state);
        }
    }

    public static void directCall(TargetMethodAssembler tasm, AMD64MacroAssembler masm, InvokeTarget callTarget, Register scratch, boolean align, LIRFrameState info) {
        if (align) {
            emitAlignmentForDirectCall(tasm, masm);
        }
        int before = masm.codeBuffer.position();
        if (scratch != null) {
            // offset might not fit a 32-bit immediate, generate an
            // indirect call with a 64-bit immediate
            masm.movq(scratch, 0L);
            masm.call(scratch);
        } else {
            masm.call();
        }
        int after = masm.codeBuffer.position();
        tasm.recordDirectCall(before, after, callTarget, info);
        tasm.recordExceptionHandlers(after, info);
        masm.ensureUniquePC();
    }

    protected static void emitAlignmentForDirectCall(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
        // make sure that the displacement word of the call ends up word aligned
        int offset = masm.codeBuffer.position();
        offset += tasm.target.arch.getMachineCodeCallDisplacementOffset();
        int modulus = tasm.target.wordSize;
        if (offset % modulus != 0) {
            masm.nop(modulus - offset % modulus);
        }
    }

    public static void directJmp(TargetMethodAssembler tasm, AMD64MacroAssembler masm, InvokeTarget target) {
        int before = masm.codeBuffer.position();
        masm.jmp(0, true);
        int after = masm.codeBuffer.position();
        tasm.recordDirectCall(before, after, target, null);
        masm.ensureUniquePC();
    }

    public static void indirectCall(TargetMethodAssembler tasm, AMD64MacroAssembler masm, Register dst, InvokeTarget callTarget, LIRFrameState info) {
        int before = masm.codeBuffer.position();
        masm.call(dst);
        int after = masm.codeBuffer.position();
        tasm.recordIndirectCall(before, after, callTarget, info);
        tasm.recordExceptionHandlers(after, info);
        masm.ensureUniquePC();
    }
}
