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

import com.oracle.graal.amd64.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.amd64.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.asm.*;

// @formatter:off
public enum AMD64Arithmetic {
    IADD, ISUB, IMUL, IDIV, IDIVREM, IREM, IUDIV, IUREM, IAND, IOR, IXOR, ISHL, ISHR, IUSHR,
    LADD, LSUB, LMUL, LDIV, LDIVREM, LREM, LUDIV, LUREM, LAND, LOR, LXOR, LSHL, LSHR, LUSHR,
    FADD, FSUB, FMUL, FDIV, FAND, FOR, FXOR,
    DADD, DSUB, DMUL, DDIV, DAND, DOR, DXOR,
    INEG, LNEG,
    I2L, L2I, I2B, I2C, I2S,
    F2D, D2F,
    I2F, I2D,
    L2F, L2D,
    MOV_I2F, MOV_L2D, MOV_F2I, MOV_D2L,

    /*
     * Converts a float/double to an int/long. The result of the conversion does not comply with Java semantics
     * when the input is a NaN, infinity or the conversion result is greater than Integer.MAX_VALUE/Long.MAX_VALUE.
     */
    F2I, D2I, F2L, D2L;

    /**
     * Unary operation with separate source and destination operand. 
     */
    public static class Unary2Op extends AMD64LIRInstruction {
        @Opcode private final AMD64Arithmetic opcode;
        @Def({REG}) protected AllocatableValue result;
        @Use({REG, STACK}) protected AllocatableValue x;

        public Unary2Op(AMD64Arithmetic opcode, AllocatableValue result, AllocatableValue x) {
            this.opcode = opcode;
            this.result = result;
            this.x = x;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            emit(tasm, masm, opcode, result, x, null);
        }
    }

    /**
     * Unary operation with single operand for source and destination. 
     */
    public static class Unary1Op extends AMD64LIRInstruction {
        @Opcode private final AMD64Arithmetic opcode;
        @Def({REG, HINT}) protected AllocatableValue result;
        @Use({REG, STACK}) protected AllocatableValue x;

        public Unary1Op(AMD64Arithmetic opcode, AllocatableValue result, AllocatableValue x) {
            this.opcode = opcode;
            this.result = result;
            this.x = x;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            AMD64Move.move(tasm, masm, result, x);
            emit(tasm, masm, opcode, result);
        }
    }

    /**
     * Binary operation with two operands. The first source operand is combined with the destination.
     * The second source operand may be a stack slot. 
     */
    public static class BinaryRegStack extends AMD64LIRInstruction {
        @Opcode private final AMD64Arithmetic opcode;
        @Def({REG, HINT}) protected AllocatableValue result;
        @Use({REG, STACK}) protected AllocatableValue x;
        @Alive({REG, STACK}) protected AllocatableValue y;

        public BinaryRegStack(AMD64Arithmetic opcode, AllocatableValue result, AllocatableValue x, AllocatableValue y) {
            this.opcode = opcode;
            this.result = result;
            this.x = x;
            this.y = y;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            AMD64Move.move(tasm, masm, result, x);
            emit(tasm, masm, opcode, result, y, null);
        }

        @Override
        public void verify() {
            super.verify();
            assert differentRegisters(result, y) || sameRegister(x, y);
            verifyKind(opcode, result, x, y);
        }
    }

    /**
     * Binary operation with two operands. The first source operand is combined with the destination.
     * The second source operand must be a register. 
     */
    public static class BinaryRegReg extends AMD64LIRInstruction {
        @Opcode private final AMD64Arithmetic opcode;
        @Def({REG, HINT}) protected AllocatableValue result;
        @Use({REG, STACK}) protected AllocatableValue x;
        @Alive({REG}) protected AllocatableValue y;

        public BinaryRegReg(AMD64Arithmetic opcode, AllocatableValue result, AllocatableValue x, AllocatableValue y) {
            this.opcode = opcode;
            this.result = result;
            this.x = x;
            this.y = y;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            AMD64Move.move(tasm, masm, result, x);
            emit(tasm, masm, opcode, result, y, null);
        }

        @Override
        public void verify() {
            super.verify();
            assert differentRegisters(result, y) || sameRegister(x, y);
            verifyKind(opcode, result, x, y);
        }
    }

    /**
     * Binary operation with single source/destination operand and one constant.
     */
    public static class BinaryRegConst extends AMD64LIRInstruction {
        @Opcode private final AMD64Arithmetic opcode;
        @Def({REG, HINT}) protected AllocatableValue result;
        @Use({REG, STACK}) protected AllocatableValue x;
        protected Constant y;

        public BinaryRegConst(AMD64Arithmetic opcode, AllocatableValue result, AllocatableValue x, Constant y) {
            this.opcode = opcode;
            this.result = result;
            this.x = x;
            this.y = y;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            AMD64Move.move(tasm, masm, result, x);
            emit(tasm, masm, opcode, result, y, null);
        }

        @Override
        public void verify() {
            super.verify();
            verifyKind(opcode, result, x, y);
        }
    }

    /**
     * Commutative binary operation with two operands. One of the operands is combined with the result.
     */
    public static class BinaryCommutative extends AMD64LIRInstruction {
        @Opcode private final AMD64Arithmetic opcode;
        @Def({REG, HINT}) protected AllocatableValue result;
        @Use({REG, STACK}) protected AllocatableValue x;
        @Use({REG, STACK}) protected AllocatableValue y;

        public BinaryCommutative(AMD64Arithmetic opcode, AllocatableValue result, AllocatableValue x, AllocatableValue y) {
            this.opcode = opcode;
            this.result = result;
            this.x = x;
            this.y = y;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            if (sameRegister(result, y)) {
                emit(tasm, masm, opcode, result, x, null);
            } else {
                AMD64Move.move(tasm, masm, result, x);
                emit(tasm, masm, opcode, result, y, null);
            }
        }

        @Override
        protected void verify() {
            super.verify();
            verifyKind(opcode, result, x, y);
        }
    }

    /**
     * Binary operation with separate source and destination and one constant operand.
     */
    public static class BinaryRegStackConst extends AMD64LIRInstruction {
        @Opcode private final AMD64Arithmetic opcode;
        @Def({REG}) protected AllocatableValue result;
        @Use({REG, STACK}) protected AllocatableValue x;
        protected Constant y;

        public BinaryRegStackConst(AMD64Arithmetic opcode, AllocatableValue result, AllocatableValue x, Constant y) {
            this.opcode = opcode;
            this.result = result;
            this.x = x;
            this.y = y;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            AMD64Move.move(tasm, masm, result, x);
            emit(tasm, masm, opcode, result, y, null);
        }

        @Override
        public void verify() {
            super.verify();
            verifyKind(opcode, result, x, y);
        }
    }

    public static class DivRemOp extends AMD64LIRInstruction {
        @Opcode private final AMD64Arithmetic opcode;
        @Def protected AllocatableValue divResult;
        @Def protected AllocatableValue remResult;
        @Use protected AllocatableValue x;
        @Alive protected AllocatableValue y;
        @State protected LIRFrameState state;

        public DivRemOp(AMD64Arithmetic opcode, AllocatableValue x, AllocatableValue y, LIRFrameState state) {
            this.opcode = opcode;
            this.divResult = AMD64.rax.asValue(x.getKind());
            this.remResult = AMD64.rdx.asValue(x.getKind());
            this.x = x;
            this.y = y;
            this.state = state;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            emit(tasm, masm, opcode, null, y, state);
        }

        @Override
        protected void verify() {
            super.verify();
            // left input in rax, right input in any register but rax and rdx, result quotient in rax, result remainder in rdx
            assert asRegister(x) == AMD64.rax;
            assert differentRegisters(y, AMD64.rax.asValue(), AMD64.rdx.asValue());
            verifyKind(opcode, divResult, x, y);
            verifyKind(opcode, remResult, x, y);
        }
    }


    @SuppressWarnings("unused")
    protected static void emit(TargetMethodAssembler tasm, AMD64MacroAssembler masm, AMD64Arithmetic opcode, AllocatableValue result) {
        switch (opcode) {
            case INEG: masm.negl(asIntReg(result)); break;
            case LNEG: masm.negq(asLongReg(result)); break;
            case L2I:  masm.andl(asIntReg(result), 0xFFFFFFFF); break;
            case I2C:  masm.andl(asIntReg(result), 0xFFFF); break;
            default:   throw GraalInternalError.shouldNotReachHere();
        }
    }

    public static void emit(TargetMethodAssembler tasm, AMD64MacroAssembler masm, AMD64Arithmetic opcode, Value dst, Value src, LIRFrameState info) {
        int exceptionOffset = -1;
        if (isRegister(src)) {
            switch (opcode) {
                case IADD: masm.addl(asIntReg(dst),  asIntReg(src)); break;
                case ISUB: masm.subl(asIntReg(dst),  asIntReg(src)); break;
                case IAND: masm.andl(asIntReg(dst),  asIntReg(src)); break;
                case IMUL: masm.imull(asIntReg(dst), asIntReg(src)); break;
                case IOR:  masm.orl(asIntReg(dst),   asIntReg(src)); break;
                case IXOR: masm.xorl(asIntReg(dst),  asIntReg(src)); break;
                case ISHL: assert asIntReg(src) == AMD64.rcx; masm.shll(asIntReg(dst)); break;
                case ISHR: assert asIntReg(src) == AMD64.rcx; masm.sarl(asIntReg(dst)); break;
                case IUSHR: assert asIntReg(src) == AMD64.rcx; masm.shrl(asIntReg(dst)); break;

                case LADD: masm.addq(asLongReg(dst),  asLongReg(src)); break;
                case LSUB: masm.subq(asLongReg(dst),  asLongReg(src)); break;
                case LMUL: masm.imulq(asLongReg(dst), asLongReg(src)); break;
                case LAND: masm.andq(asLongReg(dst),  asLongReg(src)); break;
                case LOR:  masm.orq(asLongReg(dst),   asLongReg(src)); break;
                case LXOR: masm.xorq(asLongReg(dst),  asLongReg(src)); break;
                case LSHL: assert asIntReg(src) == AMD64.rcx; masm.shlq(asLongReg(dst)); break;
                case LSHR: assert asIntReg(src) == AMD64.rcx; masm.sarq(asLongReg(dst)); break;
                case LUSHR: assert asIntReg(src) == AMD64.rcx; masm.shrq(asLongReg(dst)); break;

                case FADD: masm.addss(asFloatReg(dst), asFloatReg(src)); break;
                case FSUB: masm.subss(asFloatReg(dst), asFloatReg(src)); break;
                case FMUL: masm.mulss(asFloatReg(dst), asFloatReg(src)); break;
                case FDIV: masm.divss(asFloatReg(dst), asFloatReg(src)); break;
                case FAND: masm.andps(asFloatReg(dst), asFloatReg(src)); break;
                case FOR:  masm.orps(asFloatReg(dst),  asFloatReg(src)); break;
                case FXOR: masm.xorps(asFloatReg(dst), asFloatReg(src)); break;

                case DADD: masm.addsd(asDoubleReg(dst), asDoubleReg(src)); break;
                case DSUB: masm.subsd(asDoubleReg(dst), asDoubleReg(src)); break;
                case DMUL: masm.mulsd(asDoubleReg(dst), asDoubleReg(src)); break;
                case DDIV: masm.divsd(asDoubleReg(dst), asDoubleReg(src)); break;
                case DAND: masm.andpd(asDoubleReg(dst), asDoubleReg(src)); break;
                case DOR:  masm.orpd(asDoubleReg(dst),  asDoubleReg(src)); break;
                case DXOR: masm.xorpd(asDoubleReg(dst), asDoubleReg(src)); break;

                case I2B: masm.movsxb(asIntReg(dst), asIntReg(src)); break;
                case I2S: masm.movsxw(asIntReg(dst), asIntReg(src)); break;
                case I2L: masm.movslq(asLongReg(dst), asIntReg(src)); break;
                case F2D: masm.cvtss2sd(asDoubleReg(dst), asFloatReg(src)); break;
                case D2F: masm.cvtsd2ss(asFloatReg(dst), asDoubleReg(src)); break;
                case I2F: masm.cvtsi2ssl(asFloatReg(dst), asIntReg(src)); break;
                case I2D: masm.cvtsi2sdl(asDoubleReg(dst), asIntReg(src)); break;
                case L2F: masm.cvtsi2ssq(asFloatReg(dst), asLongReg(src)); break;
                case L2D: masm.cvtsi2sdq(asDoubleReg(dst), asLongReg(src)); break;
                case F2I:
                    masm.cvttss2sil(asIntReg(dst), asFloatReg(src));
                    break;
                case D2I:
                    masm.cvttsd2sil(asIntReg(dst), asDoubleReg(src));
                    break;
                case F2L:
                    masm.cvttss2siq(asLongReg(dst), asFloatReg(src));
                    break;
                case D2L:
                    masm.cvttsd2siq(asLongReg(dst), asDoubleReg(src));
                    break;
                case MOV_I2F: masm.movdl(asFloatReg(dst), asIntReg(src)); break;
                case MOV_L2D: masm.movdq(asDoubleReg(dst), asLongReg(src)); break;
                case MOV_F2I: masm.movdl(asIntReg(dst), asFloatReg(src)); break;
                case MOV_D2L: masm.movdq(asLongReg(dst), asDoubleReg(src)); break;

                case IDIVREM:
                case IDIV:
                case IREM:
                    masm.cdql();
                    exceptionOffset = masm.codeBuffer.position();
                    masm.idivl(asRegister(src));
                    break;

                case LDIVREM:
                case LDIV:
                case LREM:
                    masm.cdqq();
                    exceptionOffset = masm.codeBuffer.position();
                    masm.idivq(asRegister(src));
                    break;

                case IUDIV:
                case IUREM:
                    // Must zero the high 64-bit word (in RDX) of the dividend
                    masm.xorq(AMD64.rdx, AMD64.rdx);
                    exceptionOffset = masm.codeBuffer.position();
                    masm.divl(asRegister(src));
                    break;

                case LUDIV:
                case LUREM:
                    // Must zero the high 64-bit word (in RDX) of the dividend
                    masm.xorq(AMD64.rdx, AMD64.rdx);
                    exceptionOffset = masm.codeBuffer.position();
                    masm.divq(asRegister(src));
                    break;
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        } else if (isConstant(src)) {
            switch (opcode) {
                case IADD: masm.incrementl(asIntReg(dst), tasm.asIntConst(src)); break;
                case ISUB: masm.decrementl(asIntReg(dst), tasm.asIntConst(src)); break;
                case IMUL: masm.imull(asIntReg(dst), asIntReg(dst), tasm.asIntConst(src)); break;
                case IAND: masm.andl(asIntReg(dst), tasm.asIntConst(src)); break;
                case IOR:  masm.orl(asIntReg(dst),  tasm.asIntConst(src)); break;
                case IXOR: masm.xorl(asIntReg(dst), tasm.asIntConst(src)); break;
                case ISHL: masm.shll(asIntReg(dst), tasm.asIntConst(src) & 31); break;
                case ISHR: masm.sarl(asIntReg(dst), tasm.asIntConst(src) & 31); break;
                case IUSHR:masm.shrl(asIntReg(dst), tasm.asIntConst(src) & 31); break;

                case LADD: masm.addq(asLongReg(dst), tasm.asIntConst(src)); break;
                case LSUB: masm.subq(asLongReg(dst), tasm.asIntConst(src)); break;
                case LMUL: masm.imulq(asLongReg(dst), asLongReg(dst), tasm.asIntConst(src)); break;
                case LAND: masm.andq(asLongReg(dst), tasm.asIntConst(src)); break;
                case LOR:  masm.orq(asLongReg(dst),  tasm.asIntConst(src)); break;
                case LXOR: masm.xorq(asLongReg(dst), tasm.asIntConst(src)); break;
                case LSHL: masm.shlq(asLongReg(dst), tasm.asIntConst(src) & 63); break;
                case LSHR: masm.sarq(asLongReg(dst), tasm.asIntConst(src) & 63); break;
                case LUSHR:masm.shrq(asLongReg(dst), tasm.asIntConst(src) & 63); break;

                case FADD: masm.addss(asFloatReg(dst), (AMD64Address) tasm.asFloatConstRef(src)); break;
                case FSUB: masm.subss(asFloatReg(dst), (AMD64Address) tasm.asFloatConstRef(src)); break;
                case FMUL: masm.mulss(asFloatReg(dst), (AMD64Address) tasm.asFloatConstRef(src)); break;
                case FAND: masm.andps(asFloatReg(dst), (AMD64Address) tasm.asFloatConstRef(src, 16)); break;
                case FOR:  masm.orps(asFloatReg(dst),  (AMD64Address) tasm.asFloatConstRef(src, 16)); break;
                case FXOR: masm.xorps(asFloatReg(dst), (AMD64Address) tasm.asFloatConstRef(src, 16)); break;
                case FDIV: masm.divss(asFloatReg(dst), (AMD64Address) tasm.asFloatConstRef(src)); break;

                case DADD: masm.addsd(asDoubleReg(dst), (AMD64Address) tasm.asDoubleConstRef(src)); break;
                case DSUB: masm.subsd(asDoubleReg(dst), (AMD64Address) tasm.asDoubleConstRef(src)); break;
                case DMUL: masm.mulsd(asDoubleReg(dst), (AMD64Address) tasm.asDoubleConstRef(src)); break;
                case DDIV: masm.divsd(asDoubleReg(dst), (AMD64Address) tasm.asDoubleConstRef(src)); break;
                case DAND: masm.andpd(asDoubleReg(dst), (AMD64Address) tasm.asDoubleConstRef(src, 16)); break;
                case DOR:  masm.orpd(asDoubleReg(dst),  (AMD64Address) tasm.asDoubleConstRef(src, 16)); break;
                case DXOR: masm.xorpd(asDoubleReg(dst), (AMD64Address) tasm.asDoubleConstRef(src, 16)); break;
                default:   throw GraalInternalError.shouldNotReachHere();
            }
        } else {
            switch (opcode) {
                case IADD: masm.addl(asIntReg(dst), (AMD64Address) tasm.asIntAddr(src)); break;
                case ISUB: masm.subl(asIntReg(dst), (AMD64Address) tasm.asIntAddr(src)); break;
                case IAND: masm.andl(asIntReg(dst), (AMD64Address) tasm.asIntAddr(src)); break;
                case IMUL: masm.imull(asIntReg(dst), (AMD64Address) tasm.asIntAddr(src)); break;
                case IOR:  masm.orl(asIntReg(dst),  (AMD64Address) tasm.asIntAddr(src)); break;
                case IXOR: masm.xorl(asIntReg(dst), (AMD64Address) tasm.asIntAddr(src)); break;

                case LADD: masm.addq(asLongReg(dst), (AMD64Address) tasm.asLongAddr(src)); break;
                case LSUB: masm.subq(asLongReg(dst), (AMD64Address) tasm.asLongAddr(src)); break;
                case LMUL: masm.imulq(asLongReg(dst), (AMD64Address) tasm.asLongAddr(src)); break;
                case LAND: masm.andq(asLongReg(dst), (AMD64Address) tasm.asLongAddr(src)); break;
                case LOR:  masm.orq(asLongReg(dst),  (AMD64Address) tasm.asLongAddr(src)); break;
                case LXOR: masm.xorq(asLongReg(dst), (AMD64Address) tasm.asLongAddr(src)); break;

                case FADD: masm.addss(asFloatReg(dst), (AMD64Address) tasm.asFloatAddr(src)); break;
                case FSUB: masm.subss(asFloatReg(dst), (AMD64Address) tasm.asFloatAddr(src)); break;
                case FMUL: masm.mulss(asFloatReg(dst), (AMD64Address) tasm.asFloatAddr(src)); break;
                case FDIV: masm.divss(asFloatReg(dst), (AMD64Address) tasm.asFloatAddr(src)); break;

                case DADD: masm.addsd(asDoubleReg(dst), (AMD64Address) tasm.asDoubleAddr(src)); break;
                case DSUB: masm.subsd(asDoubleReg(dst), (AMD64Address) tasm.asDoubleAddr(src)); break;
                case DMUL: masm.mulsd(asDoubleReg(dst), (AMD64Address) tasm.asDoubleAddr(src)); break;
                case DDIV: masm.divsd(asDoubleReg(dst), (AMD64Address) tasm.asDoubleAddr(src)); break;

                case I2B: masm.movsxb(asIntReg(dst), (AMD64Address) tasm.asIntAddr(src)); break;
                case I2S: masm.movsxw(asIntReg(dst), (AMD64Address) tasm.asIntAddr(src)); break;
                case I2L: masm.movslq(asLongReg(dst), (AMD64Address) tasm.asIntAddr(src)); break;
                case F2D: masm.cvtss2sd(asDoubleReg(dst), (AMD64Address) tasm.asFloatAddr(src)); break;
                case D2F: masm.cvtsd2ss(asFloatReg(dst), (AMD64Address) tasm.asDoubleAddr(src)); break;
                case I2F: masm.cvtsi2ssl(asFloatReg(dst), (AMD64Address) tasm.asIntAddr(src)); break;
                case I2D: masm.cvtsi2sdl(asDoubleReg(dst), (AMD64Address) tasm.asIntAddr(src)); break;
                case L2F: masm.cvtsi2ssq(asFloatReg(dst), (AMD64Address) tasm.asLongAddr(src)); break;
                case L2D: masm.cvtsi2sdq(asDoubleReg(dst), (AMD64Address) tasm.asLongAddr(src)); break;
                case F2I:
                    masm.cvttss2sil(asIntReg(dst), (AMD64Address) tasm.asFloatAddr(src));
                    break;
                case D2I:
                    masm.cvttsd2sil(asIntReg(dst), (AMD64Address) tasm.asDoubleAddr(src));
                    break;
                case F2L:
                    masm.cvttss2siq(asLongReg(dst), (AMD64Address) tasm.asFloatAddr(src));
                    break;
                case D2L:
                    masm.cvttsd2siq(asLongReg(dst), (AMD64Address) tasm.asDoubleAddr(src));
                    break;
                case MOV_I2F: masm.movss(asFloatReg(dst), (AMD64Address) tasm.asIntAddr(src)); break;
                case MOV_L2D: masm.movsd(asDoubleReg(dst), (AMD64Address) tasm.asLongAddr(src)); break;
                case MOV_F2I: masm.movl(asIntReg(dst), (AMD64Address) tasm.asFloatAddr(src)); break;
                case MOV_D2L: masm.movq(asLongReg(dst), (AMD64Address) tasm.asDoubleAddr(src)); break;

                default:   throw GraalInternalError.shouldNotReachHere();
            }
        }

        if (info != null) {
            assert exceptionOffset != -1;
            tasm.recordImplicitException(exceptionOffset, info);
        }
    }

    private static void verifyKind(AMD64Arithmetic opcode, Value result, Value x, Value y) {
        assert (opcode.name().startsWith("I") && result.getKind() == Kind.Int && x.getKind().getStackKind() == Kind.Int && y.getKind().getStackKind() == Kind.Int)
            || (opcode.name().startsWith("L") && result.getKind() == Kind.Long && x.getKind() == Kind.Long && y.getKind() == Kind.Long)
            || (opcode.name().startsWith("F") && result.getKind() == Kind.Float && x.getKind() == Kind.Float && y.getKind() == Kind.Float)
            || (opcode.name().startsWith("D") && result.getKind() == Kind.Double && x.getKind() == Kind.Double && y.getKind() == Kind.Double)
            || (opcode.name().matches(".U?SH.") && result.getKind() == x.getKind() && y.getKind() == Kind.Int && (isConstant(y) || asRegister(y) == AMD64.rcx));
    }
}
