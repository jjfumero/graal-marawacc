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
package com.oracle.graal.lir.sparc;

import static com.oracle.graal.api.code.ValueUtil.*;
import static com.oracle.graal.asm.sparc.SPARCMacroAssembler.*;
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.*;
import static com.oracle.graal.sparc.SPARC.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.sparc.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.StandardOp.MoveOp;
import com.oracle.graal.lir.asm.*;

public class SPARCMove {

    @Opcode("MOVE")
    public static class MoveToRegOp extends SPARCLIRInstruction implements MoveOp {

        @Def({REG, HINT}) protected AllocatableValue result;
        @Use({REG, STACK, CONST}) protected Value input;

        public MoveToRegOp(AllocatableValue result, Value input) {
            this.result = result;
            this.input = input;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, SPARCMacroAssembler masm) {
            move(tasm, masm, getResult(), getInput());
        }

        @Override
        public Value getInput() {
            return input;
        }

        @Override
        public AllocatableValue getResult() {
            return result;
        }
    }

    @Opcode("MOVE")
    public static class MoveFromRegOp extends SPARCLIRInstruction implements MoveOp {

        @Def({REG, STACK}) protected AllocatableValue result;
        @Use({REG, CONST, HINT}) protected Value input;

        public MoveFromRegOp(AllocatableValue result, Value input) {
            this.result = result;
            this.input = input;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, SPARCMacroAssembler masm) {
            move(tasm, masm, getResult(), getInput());
        }

        @Override
        public Value getInput() {
            return input;
        }

        @Override
        public AllocatableValue getResult() {
            return result;
        }
    }

    public abstract static class MemOp extends SPARCLIRInstruction {

        protected final Kind kind;
        @Use({COMPOSITE}) protected SPARCAddressValue address;
        @State protected LIRFrameState state;

        public MemOp(Kind kind, SPARCAddressValue address, LIRFrameState state) {
            this.kind = kind;
            this.address = address;
            this.state = state;
        }

        protected abstract void emitMemAccess(SPARCMacroAssembler masm);

        @Override
        public void emitCode(TargetMethodAssembler tasm, SPARCMacroAssembler masm) {
            if (state != null) {
                tasm.recordImplicitException(masm.codeBuffer.position(), state);
            }
            emitMemAccess(masm);
        }
    }

    public static class LoadOp extends MemOp {

        @Def({REG}) protected AllocatableValue result;

        public LoadOp(Kind kind, AllocatableValue result, SPARCAddressValue address, LIRFrameState state) {
            super(kind, address, state);
            this.result = result;
        }

        @Override
        public void emitMemAccess(SPARCMacroAssembler masm) {
            SPARCAddress addr = address.toAddress();
            switch (kind) {
                case Byte:
                    new Ldsb(addr, asRegister(result)).emit(masm);
                    break;
                case Short:
                    new Ldsh(addr, asRegister(result)).emit(masm);
                    break;
                case Char:
                    new Lduw(addr, asRegister(result)).emit(masm);
                    break;
                case Int:
                    new Ldsw(addr, asRegister(result)).emit(masm);
                    break;
                case Long:
                    new Ldx(addr, asRegister(result)).emit(masm);
                    break;
                case Float:
                    new Ldf(addr, asRegister(result)).emit(masm);
                    break;
                case Double:
                    new Lddf(addr, asRegister(result)).emit(masm);
                    break;
                case Object:
                    new Ldx(addr, asRegister(result)).emit(masm);
                    break;
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        }
    }

    public static class MembarOp extends SPARCLIRInstruction {

        private final int barriers;

        public MembarOp(final int barriers) {
            this.barriers = barriers;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, SPARCMacroAssembler masm) {
            new Membar(barriers).emit(masm);
        }
    }

    public static class NullCheckOp extends SPARCLIRInstruction {

        @Use({REG}) protected AllocatableValue input;
        @State protected LIRFrameState state;

        public NullCheckOp(Variable input, LIRFrameState state) {
            this.input = input;
            this.state = state;
        }

        @Override
        @SuppressWarnings("unused")
        public void emitCode(TargetMethodAssembler tasm, SPARCMacroAssembler masm) {
            tasm.recordImplicitException(masm.codeBuffer.position(), state);
            new Ldx(new SPARCAddress(asRegister(input), 0), r0);
        }
    }

    public static class StackLoadAddressOp extends SPARCLIRInstruction {

        @Def({REG}) protected AllocatableValue result;
        @Use({STACK, UNINITIALIZED}) protected StackSlot slot;

        public StackLoadAddressOp(AllocatableValue result, StackSlot slot) {
            this.result = result;
            this.slot = slot;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, SPARCMacroAssembler masm) {
            new Ldx((SPARCAddress) tasm.asAddress(slot), asLongReg(result)).emit(masm);
        }
    }

    public static class StoreOp extends MemOp {

        @Use({REG}) protected AllocatableValue input;

        public StoreOp(Kind kind, SPARCAddressValue address, AllocatableValue input, LIRFrameState state) {
            super(kind, address, state);
            this.input = input;
        }

        @Override
        public void emitMemAccess(SPARCMacroAssembler masm) {
            assert isRegister(input);
            SPARCAddress addr = address.toAddress();
            switch (kind) {
                case Byte:
                    new Stb(asRegister(input), addr).emit(masm);
                    break;
                case Short:
                    new Sth(asRegister(input), addr).emit(masm);
                    break;
                case Int:
                    new Stw(asRegister(input), addr).emit(masm);
                    break;
                case Long:
                    new Stx(asRegister(input), addr).emit(masm);
                    break;
                case Object:
                    new Stx(asRegister(input), addr).emit(masm);
                    break;
                case Float:
                case Double:
                default:
                    throw GraalInternalError.shouldNotReachHere("missing: " + address.getKind());
            }
        }
    }

    public static void move(TargetMethodAssembler tasm, SPARCMacroAssembler masm, Value result, Value input) {
        if (isRegister(input)) {
            if (isRegister(result)) {
                reg2reg(masm, result, input);
            } else if (isStackSlot(result)) {
                reg2stack(tasm, masm, result, input);
            } else {
                throw GraalInternalError.shouldNotReachHere();
            }
        } else if (isStackSlot(input)) {
            if (isRegister(result)) {
                stack2reg(tasm, masm, result, input);
            } else {
                throw GraalInternalError.shouldNotReachHere();
            }
        } else if (isConstant(input)) {
            if (isRegister(result)) {
                const2reg(tasm, masm, result, (Constant) input);
            } else {
                throw GraalInternalError.shouldNotReachHere();
            }
        } else {
            throw GraalInternalError.shouldNotReachHere();
        }
    }

    private static void reg2reg(SPARCAssembler masm, Value result, Value input) {
        if (asRegister(input).equals(asRegister(result))) {
            return;
        }
        switch (input.getKind()) {
            case Int:
                new Mov(asRegister(input), asRegister(result)).emit(masm);
                break;
            case Long:
                new Mov(asRegister(input), asRegister(result)).emit(masm);
                break;
            case Float:
            case Double:
            case Object:
            default:
                throw GraalInternalError.shouldNotReachHere("missing: " + input.getKind());
        }
    }

    private static void reg2stack(TargetMethodAssembler tasm, SPARCMacroAssembler masm, Value result, Value input) {
        SPARCAddress dest = (SPARCAddress) tasm.asAddress(result);
        switch (input.getKind()) {
            case Int:
                new Stw(asRegister(input), dest).emit(masm);
                break;
            case Long:
            case Float:
            case Double:
            case Object:
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    private static void stack2reg(TargetMethodAssembler tasm, SPARCMacroAssembler masm, Value result, Value input) {
        SPARCAddress src = (SPARCAddress) tasm.asAddress(input);
        switch (input.getKind()) {
            case Int:
                new Ldsw(src, asRegister(result)).emit(masm);
                break;
            case Long:
            case Float:
            case Double:
            case Object:
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    private static void const2reg(TargetMethodAssembler tasm, SPARCMacroAssembler masm, Value result, Constant input) {
        switch (input.getKind().getStackKind()) {
            case Int:
                if (tasm.runtime.needsDataPatch(input)) {
                    tasm.recordDataReferenceInCode(input, 0, true);
                }
                new Setuw(input.asInt(), asRegister(result)).emit(masm);
                break;
            case Long:
                if (tasm.runtime.needsDataPatch(input)) {
                    tasm.recordDataReferenceInCode(input, 0, true);
                }
                new Setx(input.asLong(), null, asRegister(result)).emit(masm);
                break;
            case Object:
                if (input.isNull()) {
                    new Clr(asRegister(result)).emit(masm);
                } else if (tasm.target.inlineObjects) {
                    tasm.recordDataReferenceInCode(input, 0, true);
                    new Setx(0xDEADDEADDEADDEADL, null, asRegister(result)).emit(masm);
                } else {
                    throw new InternalError("NYI");
                }
                break;
            default:
                throw GraalInternalError.shouldNotReachHere("missing: " + input.getKind());
        }
    }
}
