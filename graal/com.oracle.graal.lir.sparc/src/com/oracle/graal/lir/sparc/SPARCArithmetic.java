/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
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
import static com.oracle.graal.asm.sparc.SPARCAssembler.*;
import static com.oracle.graal.asm.sparc.SPARCAssembler.Annul.*;
import static com.oracle.graal.asm.sparc.SPARCAssembler.BranchPredict.*;
import static com.oracle.graal.asm.sparc.SPARCAssembler.CC.*;
import static com.oracle.graal.asm.sparc.SPARCAssembler.ConditionFlag.*;
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.*;
import static com.oracle.graal.sparc.SPARC.*;
import static com.oracle.graal.sparc.SPARC.CPUFeature.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.asm.sparc.*;
import com.oracle.graal.asm.sparc.SPARCAssembler.Add;
import com.oracle.graal.asm.sparc.SPARCAssembler.Addcc;
import com.oracle.graal.asm.sparc.SPARCAssembler.And;
import com.oracle.graal.asm.sparc.SPARCAssembler.CC;
import com.oracle.graal.asm.sparc.SPARCAssembler.Faddd;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fadds;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fandd;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fcmp;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fdivd;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fdivs;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fdtoi;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fdtos;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fdtox;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fitod;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fitos;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fmuld;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fmuls;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fnegd;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fnegs;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fsmuld;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fstod;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fstoi;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fstox;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fsubd;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fsubs;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fxtod;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fxtos;
import com.oracle.graal.asm.sparc.SPARCAssembler.Mulx;
import com.oracle.graal.asm.sparc.SPARCAssembler.Opfs;
import com.oracle.graal.asm.sparc.SPARCAssembler.Or;
import com.oracle.graal.asm.sparc.SPARCAssembler.Sdivx;
import com.oracle.graal.asm.sparc.SPARCAssembler.Sll;
import com.oracle.graal.asm.sparc.SPARCAssembler.Sllx;
import com.oracle.graal.asm.sparc.SPARCAssembler.Sra;
import com.oracle.graal.asm.sparc.SPARCAssembler.Srax;
import com.oracle.graal.asm.sparc.SPARCAssembler.Srl;
import com.oracle.graal.asm.sparc.SPARCAssembler.Srlx;
import com.oracle.graal.asm.sparc.SPARCAssembler.Sub;
import com.oracle.graal.asm.sparc.SPARCAssembler.Subcc;
import com.oracle.graal.asm.sparc.SPARCAssembler.Udivx;
import com.oracle.graal.asm.sparc.SPARCAssembler.Umulxhi;
import com.oracle.graal.asm.sparc.SPARCAssembler.Wrccr;
import com.oracle.graal.asm.sparc.SPARCAssembler.Xor;
import com.oracle.graal.asm.sparc.SPARCAssembler.Xorcc;
import com.oracle.graal.asm.sparc.SPARCMacroAssembler.Cmp;
import com.oracle.graal.asm.sparc.SPARCMacroAssembler.Neg;
import com.oracle.graal.asm.sparc.SPARCMacroAssembler.Not;
import com.oracle.graal.asm.sparc.SPARCMacroAssembler.Setx;
import com.oracle.graal.asm.sparc.SPARCMacroAssembler.Signx;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.lir.gen.*;
import com.oracle.graal.sparc.*;

public enum SPARCArithmetic {
    // @formatter:off
    IADD, ISUB, IMUL, IUMUL, IDIV, IREM, IUDIV, IUREM, IAND, IOR, IXOR, ISHL, ISHR, IUSHR,
    LADD, LSUB, LMUL, LUMUL, LDIV, LREM, LUDIV, LUREM, LAND, LOR, LXOR, LSHL, LSHR, LUSHR,
    IADDCC, ISUBCC, IMULCC,
    LADDCC, LSUBCC, LMULCC,
    FADD, FSUB, FMUL, FDIV, FREM, FAND, FOR, FXOR,
    DADD, DSUB, DMUL, DDIV, DREM, DAND, DOR, DXOR,
    INEG, LNEG, FNEG, DNEG, INOT, LNOT,
    L2I, B2I, S2I, B2L, S2L, I2L,
    F2D, D2F,
    I2F, I2D, F2I, D2I,
    L2F, L2D, F2L, D2L;
    // @formatter:on

    /**
     * Unary operation with separate source and destination operand.
     */
    public static class Unary2Op extends SPARCLIRInstruction implements SPARCTailDelayedLIRInstruction {

        @Opcode private final SPARCArithmetic opcode;
        @Def({REG, HINT}) protected AllocatableValue result;
        @Use({REG}) protected AllocatableValue x;

        public Unary2Op(SPARCArithmetic opcode, AllocatableValue result, AllocatableValue x) {
            this.opcode = opcode;
            this.result = result;
            this.x = x;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            emitUnary(crb, masm, opcode, result, x, null, delayedControlTransfer);
        }
    }

    /**
     * Binary operation with two operands. The first source operand is combined with the
     * destination. The second source operand must be a register.
     */
    public static class BinaryRegReg extends SPARCLIRInstruction implements SPARCTailDelayedLIRInstruction {

        @Opcode private final SPARCArithmetic opcode;
        @Def({REG, HINT}) protected Value result;
        @Use({REG}) protected Value x;
        @Alive({REG}) protected Value y;
        @State LIRFrameState state;

        public BinaryRegReg(SPARCArithmetic opcode, Value result, Value x, Value y) {
            this(opcode, result, x, y, null);
        }

        public BinaryRegReg(SPARCArithmetic opcode, Value result, Value x, Value y, LIRFrameState state) {
            this.opcode = opcode;
            this.result = result;
            this.x = x;
            this.y = y;
            this.state = state;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            emitRegReg(crb, masm, opcode, result, x, y, state, delayedControlTransfer);
        }

        @Override
        public void verify() {
            super.verify();
            verifyKind(opcode, result, x, y);
        }
    }

    /**
     * Binary operation with single source/destination operand and one constant.
     */
    public static class BinaryRegConst extends SPARCLIRInstruction implements SPARCTailDelayedLIRInstruction {

        @Opcode private final SPARCArithmetic opcode;
        @Def({REG, HINT}) protected AllocatableValue result;
        @Use({REG}) protected Value x;
        @State protected LIRFrameState state;
        protected JavaConstant y;

        public BinaryRegConst(SPARCArithmetic opcode, AllocatableValue result, Value x, JavaConstant y) {
            this(opcode, result, x, y, null);
        }

        public BinaryRegConst(SPARCArithmetic opcode, AllocatableValue result, Value x, JavaConstant y, LIRFrameState state) {
            this.opcode = opcode;
            this.result = result;
            this.x = x;
            this.y = y;
            this.state = state;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            emitRegConstant(crb, masm, opcode, result, x, y, null, delayedControlTransfer);
        }

        @Override
        public void verify() {
            super.verify();
            verifyKind(opcode, result, x, y);
        }
    }

    /**
     * Special LIR instruction as it requires a bunch of scratch registers.
     */
    public static class RemOp extends SPARCLIRInstruction implements SPARCTailDelayedLIRInstruction {

        @Opcode private final SPARCArithmetic opcode;
        @Def({REG}) protected Value result;
        @Alive({REG, CONST}) protected Value x;
        @Alive({REG, CONST}) protected Value y;
        @Temp({REG}) protected Value scratch1;
        @Temp({REG}) protected Value scratch2;
        @State protected LIRFrameState state;

        public RemOp(SPARCArithmetic opcode, Value result, Value x, Value y, LIRFrameState state, LIRGeneratorTool gen) {
            this.opcode = opcode;
            this.result = result;
            this.x = x;
            this.y = y;
            this.scratch1 = gen.newVariable(LIRKind.derive(x, y));
            this.scratch2 = gen.newVariable(LIRKind.derive(x, y));
            this.state = state;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            emitRem(crb, masm, opcode, result, x, y, scratch1, scratch2, state, delayedControlTransfer);
        }

        @Override
        public void verify() {
            super.verify();
            verifyKind(opcode, result, x, y);
        }
    }

    /**
     * Calculates the product and condition code for long multiplication of long values.
     */
    public static class SPARCLMulccOp extends SPARCLIRInstruction {
        @Def({REG}) protected Value result;
        @Alive({REG}) protected Value x;
        @Alive({REG}) protected Value y;
        @Temp({REG}) protected Value scratch1;
        @Temp({REG}) protected Value scratch2;

        public SPARCLMulccOp(Value result, Value x, Value y, LIRGeneratorTool gen) {
            this.result = result;
            this.x = x;
            this.y = y;
            this.scratch1 = gen.newVariable(LIRKind.derive(x, y));
            this.scratch2 = gen.newVariable(LIRKind.derive(x, y));
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            Label noOverflow = new Label();
            new Mulx(asLongReg(x), asLongReg(y), asLongReg(result)).emit(masm);

            // Calculate the upper 64 bit signed := (umulxhi product - (x{63}&y + y{63}&x))
            new Umulxhi(asLongReg(x), asLongReg(y), asLongReg(scratch1)).emit(masm);
            new Srax(asLongReg(x), 63, asLongReg(scratch2)).emit(masm);
            new And(asLongReg(scratch2), asLongReg(y), asLongReg(scratch2)).emit(masm);
            new Sub(asLongReg(scratch1), asLongReg(scratch2), asLongReg(scratch1)).emit(masm);

            new Srax(asLongReg(y), 63, asLongReg(scratch2)).emit(masm);
            new And(asLongReg(scratch2), asLongReg(x), asLongReg(scratch2)).emit(masm);
            new Sub(asLongReg(scratch1), asLongReg(scratch2), asLongReg(scratch1)).emit(masm);

            // Now construct the lower half and compare
            new Srax(asLongReg(result), 63, asLongReg(scratch2)).emit(masm);
            new Cmp(asLongReg(scratch1), asLongReg(scratch2)).emit(masm);
            masm.bpcc(Equal, NOT_ANNUL, noOverflow, Xcc, PREDICT_TAKEN);
            masm.nop();
            new Wrccr(g0, 1 << (CCR_XCC_SHIFT + CCR_V_SHIFT)).emit(masm);
            masm.bind(noOverflow);
        }
    }

    private static void emitRegConstant(CompilationResultBuilder crb, SPARCMacroAssembler masm, SPARCArithmetic opcode, Value dst, Value src1, JavaConstant src2, LIRFrameState info,
                    SPARCDelayedControlTransfer delaySlotLir) {
        assert isSimm13(crb.asIntConst(src2)) : src2;
        int constant = crb.asIntConst(src2);
        int exceptionOffset = -1;
        delaySlotLir.emitControlTransfer(crb, masm);
        switch (opcode) {
            case IADD:
                new Add(asIntReg(src1), constant, asIntReg(dst)).emit(masm);
                break;
            case IADDCC:
                new Addcc(asIntReg(src1), constant, asIntReg(dst)).emit(masm);
                break;
            case ISUB:
                new Sub(asIntReg(src1), constant, asIntReg(dst)).emit(masm);
                break;
            case ISUBCC:
                new Subcc(asIntReg(src1), constant, asIntReg(dst)).emit(masm);
                break;
            case IMUL:
                new Mulx(asIntReg(src1), constant, asIntReg(dst)).emit(masm);
                break;
            case IMULCC:
                throw GraalInternalError.unimplemented();
            case IDIV:
                new Sdivx(asIntReg(src1), constant, asIntReg(dst)).emit(masm);
                break;
            case IUDIV:
                new Udivx(asIntReg(src1), constant, asIntReg(dst)).emit(masm);
                break;
            case IAND:
                new And(asIntReg(src1), constant, asIntReg(dst)).emit(masm);
                break;
            case ISHL:
                new Sll(asIntReg(src1), constant, asIntReg(dst)).emit(masm);
                break;
            case ISHR:
                new Sra(asIntReg(src1), constant, asIntReg(dst)).emit(masm);
                break;
            case IUSHR:
                new Srl(asIntReg(src1), constant, asIntReg(dst)).emit(masm);
                break;
            case IOR:
                new Or(asIntReg(src1), constant, asIntReg(dst)).emit(masm);
                break;
            case IXOR:
                new Xor(asIntReg(src1), constant, asIntReg(dst)).emit(masm);
                break;
            case LADD:
                new Add(asLongReg(src1), constant, asLongReg(dst)).emit(masm);
                break;
            case LADDCC:
                new Addcc(asLongReg(src1), constant, asLongReg(dst)).emit(masm);
                break;
            case LSUB:
                new Sub(asLongReg(src1), constant, asLongReg(dst)).emit(masm);
                break;
            case LSUBCC:
                new Subcc(asLongReg(src1), constant, asLongReg(dst)).emit(masm);
                break;
            case LMUL:
                new Mulx(asLongReg(src1), constant, asLongReg(dst)).emit(masm);
                break;
            case LDIV:
                exceptionOffset = masm.position();
                new Sdivx(asLongReg(src1), constant, asLongReg(dst)).emit(masm);
                break;
            case LUDIV:
                exceptionOffset = masm.position();
                new Udivx(asLongReg(src1), constant, asLongReg(dst)).emit(masm);
                break;
            case LAND:
                new And(asLongReg(src1), constant, asLongReg(dst)).emit(masm);
                break;
            case LOR:
                new Or(asLongReg(src1), constant, asLongReg(dst)).emit(masm);
                break;
            case LXOR:
                new Xor(asLongReg(src1), constant, asLongReg(dst)).emit(masm);
                break;
            case LSHL:
                new Sllx(asLongReg(src1), constant, asLongReg(dst)).emit(masm);
                break;
            case LSHR:
                new Srax(asLongReg(src1), constant, asLongReg(dst)).emit(masm);
                break;
            case LUSHR:
                new Srlx(asLongReg(src1), constant, asLongReg(dst)).emit(masm);
                break;
            case DAND: // Has no constant implementation in SPARC
            case FADD:
            case FMUL:
            case FDIV:
            case DADD:
            case DMUL:
            case DDIV:
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        if (info != null) {
            assert exceptionOffset != -1;
            crb.recordImplicitException(exceptionOffset, info);
        }
    }

    public static void emitRegReg(CompilationResultBuilder crb, SPARCMacroAssembler masm, SPARCArithmetic opcode, Value dst, Value src1, Value src2, LIRFrameState info,
                    SPARCDelayedControlTransfer delaySlotLir) {
        int exceptionOffset = -1;
        assert !isConstant(src1) : src1;
        assert !isConstant(src2) : src2;
        switch (opcode) {
            case IADD:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Add(asIntReg(src1), asIntReg(src2), asIntReg(dst)).emit(masm);
                break;
            case IADDCC:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Addcc(asIntReg(src1), asIntReg(src2), asIntReg(dst)).emit(masm);
                break;
            case ISUB:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Sub(asIntReg(src1), asIntReg(src2), asIntReg(dst)).emit(masm);
                break;
            case ISUBCC:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Subcc(asIntReg(src1), asIntReg(src2), asIntReg(dst)).emit(masm);
                break;
            case IMUL:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Mulx(asIntReg(src1), asIntReg(src2), asIntReg(dst)).emit(masm);
                break;
            case IMULCC:
                try (SPARCScratchRegister tmpScratch = SPARCScratchRegister.get()) {
                    Register tmp = tmpScratch.getRegister();
                    new Mulx(asIntReg(src1), asIntReg(src2), asIntReg(dst)).emit(masm);
                    Label noOverflow = new Label();
                    new Sra(asIntReg(dst), 0, tmp).emit(masm);
                    new Xorcc(SPARC.g0, SPARC.g0, SPARC.g0).emit(masm);
                    if (masm.hasFeature(CBCOND)) {
                        masm.cbcondx(Equal, tmp, asIntReg(dst), noOverflow);
                        // Is necessary, otherwise we will have a penalty of 5 cycles in S3
                        masm.nop();
                    } else {
                        new Cmp(tmp, asIntReg(dst)).emit(masm);
                        masm.bpcc(Equal, NOT_ANNUL, noOverflow, Xcc, PREDICT_TAKEN);
                        masm.nop();
                    }
                    new Wrccr(SPARC.g0, 1 << (SPARCAssembler.CCR_ICC_SHIFT + SPARCAssembler.CCR_V_SHIFT)).emit(masm);
                    masm.bind(noOverflow);
                }
                break;
            case IDIV:
                new Signx(asIntReg(src1), asIntReg(src1)).emit(masm);
                new Signx(asIntReg(src2), asIntReg(src2)).emit(masm);
                delaySlotLir.emitControlTransfer(crb, masm);
                exceptionOffset = masm.position();
                new Sdivx(asIntReg(src1), asIntReg(src2), asIntReg(dst)).emit(masm);
                break;
            case IUDIV:
                new Signx(asIntReg(src1), asIntReg(src1)).emit(masm);
                new Signx(asIntReg(src2), asIntReg(src2)).emit(masm);
                delaySlotLir.emitControlTransfer(crb, masm);
                exceptionOffset = masm.position();
                new Udivx(asIntReg(src1), asIntReg(src2), asIntReg(dst)).emit(masm);
                break;
            case IAND:
                delaySlotLir.emitControlTransfer(crb, masm);
                new And(asIntReg(src1), asIntReg(src2), asIntReg(dst)).emit(masm);
                break;
            case IOR:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Or(asIntReg(src1), asIntReg(src2), asIntReg(dst)).emit(masm);
                break;
            case IXOR:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Xor(asIntReg(src1), asIntReg(src2), asIntReg(dst)).emit(masm);
                break;
            case ISHL:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Sll(asIntReg(src1), asIntReg(src2), asIntReg(dst)).emit(masm);
                break;
            case ISHR:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Sra(asIntReg(src1), asIntReg(src2), asIntReg(dst)).emit(masm);
                break;
            case IUSHR:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Srl(asIntReg(src1), asIntReg(src2), asIntReg(dst)).emit(masm);
                break;
            case IREM:
                throw GraalInternalError.unimplemented();
            case LADD:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Add(asLongReg(src1), asLongReg(src2), asLongReg(dst)).emit(masm);
                break;
            case LADDCC:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Addcc(asLongReg(src1), asLongReg(src2), asLongReg(dst)).emit(masm);
                break;
            case LSUB:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Sub(asLongReg(src1), asLongReg(src2), asLongReg(dst)).emit(masm);
                break;
            case LSUBCC:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Subcc(asLongReg(src1), asLongReg(src2), asLongReg(dst)).emit(masm);
                break;
            case LMUL:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Mulx(asLongReg(src1), asLongReg(src2), asLongReg(dst)).emit(masm);
                break;
            case LMULCC:
                throw GraalInternalError.unimplemented();
            case LDIV:
                delaySlotLir.emitControlTransfer(crb, masm);
                exceptionOffset = masm.position();
                new Sdivx(asLongReg(src1), asLongReg(src2), asLongReg(dst)).emit(masm);
                break;
            case LUDIV:
                delaySlotLir.emitControlTransfer(crb, masm);
                exceptionOffset = masm.position();
                new Udivx(asLongReg(src1), asLongReg(src2), asLongReg(dst)).emit(masm);
                break;
            case LAND:
                delaySlotLir.emitControlTransfer(crb, masm);
                new And(asLongReg(src1), asLongReg(src2), asLongReg(dst)).emit(masm);
                break;
            case LOR:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Or(asLongReg(src1), asLongReg(src2), asLongReg(dst)).emit(masm);
                break;
            case LXOR:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Xor(asLongReg(src1), asLongReg(src2), asLongReg(dst)).emit(masm);
                break;
            case LSHL:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Sllx(asLongReg(src1), asIntReg(src2), asLongReg(dst)).emit(masm);
                break;
            case LSHR:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Srax(asLongReg(src1), asIntReg(src2), asLongReg(dst)).emit(masm);
                break;
            case LUSHR:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Srlx(asLongReg(src1), asIntReg(src2), asLongReg(dst)).emit(masm);
                break;
            case FADD:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Fadds(asFloatReg(src1), asFloatReg(src2), asFloatReg(dst)).emit(masm);
                break;
            case FSUB:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Fsubs(asFloatReg(src1), asFloatReg(src2), asFloatReg(dst)).emit(masm);
                break;
            case FMUL:
                delaySlotLir.emitControlTransfer(crb, masm);
                if (dst.getPlatformKind() == Kind.Double) {
                    new Fsmuld(asFloatReg(src1), asFloatReg(src2), asDoubleReg(dst)).emit(masm);
                } else if (dst.getPlatformKind() == Kind.Float) {
                    new Fmuls(asFloatReg(src1), asFloatReg(src2), asFloatReg(dst)).emit(masm);
                }
                break;
            case FDIV:
                delaySlotLir.emitControlTransfer(crb, masm);
                exceptionOffset = masm.position();
                new Fdivs(asFloatReg(src1), asFloatReg(src2), asFloatReg(dst)).emit(masm);
                break;
            case FREM:
                throw GraalInternalError.unimplemented();
            case DADD:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Faddd(asDoubleReg(src1), asDoubleReg(src2), asDoubleReg(dst)).emit(masm);
                break;
            case DSUB:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Fsubd(asDoubleReg(src1), asDoubleReg(src2), asDoubleReg(dst)).emit(masm);
                break;
            case DMUL:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Fmuld(asDoubleReg(src1), asDoubleReg(src2), asDoubleReg(dst)).emit(masm);
                break;
            case DDIV:
                delaySlotLir.emitControlTransfer(crb, masm);
                exceptionOffset = masm.position();
                new Fdivd(asDoubleReg(src1), asDoubleReg(src2), asDoubleReg(dst)).emit(masm);
                break;
            case DREM:
                throw GraalInternalError.unimplemented();
            case DAND:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Fandd(asDoubleReg(src1), asDoubleReg(src2), asDoubleReg(dst)).emit(masm);
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        if (info != null) {
            assert exceptionOffset != -1;
            crb.recordImplicitException(exceptionOffset, info);
        }
    }

    public static void emitRem(CompilationResultBuilder crb, SPARCMacroAssembler masm, SPARCArithmetic opcode, Value dst, Value src1, Value src2, Value scratch1, Value scratch2, LIRFrameState info,
                    SPARCDelayedControlTransfer delaySlotLir) {
        int exceptionOffset = -1;
        if (!isConstant(src1) && isConstant(src2)) {
            assert isSimm13(crb.asIntConst(src2));
            assert !src1.equals(scratch1);
            assert !src1.equals(scratch2);
            assert !src2.equals(scratch1);
            switch (opcode) {
                case IREM:
                    new Sra(asIntReg(src1), 0, asIntReg(dst)).emit(masm);
                    exceptionOffset = masm.position();
                    new Sdivx(asIntReg(dst), crb.asIntConst(src2), asIntReg(scratch1)).emit(masm);
                    new Mulx(asIntReg(scratch1), crb.asIntConst(src2), asIntReg(scratch2)).emit(masm);
                    delaySlotLir.emitControlTransfer(crb, masm);
                    new Sub(asIntReg(dst), asIntReg(scratch2), asIntReg(dst)).emit(masm);
                    break;
                case LREM:
                    exceptionOffset = masm.position();
                    new Sdivx(asLongReg(src1), crb.asIntConst(src2), asLongReg(scratch1)).emit(masm);
                    new Mulx(asLongReg(scratch1), crb.asIntConst(src2), asLongReg(scratch2)).emit(masm);
                    delaySlotLir.emitControlTransfer(crb, masm);
                    new Sub(asLongReg(src1), asLongReg(scratch2), asLongReg(dst)).emit(masm);
                    break;
                case LUREM:
                    exceptionOffset = masm.position();
                    new Udivx(asLongReg(src1), crb.asIntConst(src2), asLongReg(scratch1)).emit(masm);
                    new Mulx(asLongReg(scratch1), crb.asIntConst(src2), asLongReg(scratch2)).emit(masm);
                    delaySlotLir.emitControlTransfer(crb, masm);
                    new Sub(asLongReg(src1), asLongReg(scratch2), asLongReg(dst)).emit(masm);
                    break;
                case IUREM:
                    GraalInternalError.unimplemented();
                    break;
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        } else if (isRegister(src1) && isRegister(src2)) {
            Value srcLeft = src1;
            switch (opcode) {
                case LREM:
                    if (isConstant(src1)) {
                        new Setx(crb.asLongConst(src1), asLongReg(scratch2), false).emit(masm);
                        srcLeft = scratch2;
                    }
                    assert !asLongReg(srcLeft).equals(asLongReg(scratch1));
                    assert !asLongReg(src2).equals(asLongReg(scratch1));
                    // But src2 can be scratch2
                    exceptionOffset = masm.position();
                    new Sdivx(asLongReg(srcLeft), asLongReg(src2), asLongReg(scratch1)).emit(masm);
                    new Mulx(asLongReg(scratch1), asLongReg(src2), asLongReg(scratch1)).emit(masm);
                    delaySlotLir.emitControlTransfer(crb, masm);
                    new Sub(asLongReg(srcLeft), asLongReg(scratch1), asLongReg(dst)).emit(masm);
                    break;
                case LUREM:
                    if (isConstant(src1)) {
                        new Setx(crb.asLongConst(src1), asLongReg(scratch2), false).emit(masm);
                        srcLeft = scratch2;
                    }
                    assert !asLongReg(srcLeft).equals(asLongReg(scratch1));
                    assert !asLongReg(src2).equals(asLongReg(scratch1));
                    exceptionOffset = masm.position();
                    new Udivx(asLongReg(srcLeft), asLongReg(src2), asLongReg(scratch1)).emit(masm);
                    new Mulx(asLongReg(scratch1), asLongReg(src2), asLongReg(scratch1)).emit(masm);
                    delaySlotLir.emitControlTransfer(crb, masm);
                    new Sub(asLongReg(srcLeft), asLongReg(scratch1), asLongReg(dst)).emit(masm);
                    break;
                case IREM:
                    if (isConstant(src1)) {
                        new Setx(crb.asIntConst(src1), asIntReg(scratch2), false).emit(masm);
                        srcLeft = scratch2;
                    }
                    assert !asIntReg(srcLeft).equals(asIntReg(scratch1));
                    assert !asIntReg(src2).equals(asIntReg(scratch1));
                    new Sra(asIntReg(src1), 0, asIntReg(scratch1)).emit(masm);
                    new Sra(asIntReg(src2), 0, asIntReg(scratch2)).emit(masm);
                    exceptionOffset = masm.position();
                    new Sdivx(asIntReg(scratch1), asIntReg(scratch2), asIntReg(dst)).emit(masm);
                    new Mulx(asIntReg(dst), asIntReg(scratch2), asIntReg(dst)).emit(masm);
                    delaySlotLir.emitControlTransfer(crb, masm);
                    new Sub(asIntReg(scratch1), asIntReg(dst), asIntReg(dst)).emit(masm);
                    break;
                case IUREM:
                    assert !asIntReg(dst).equals(asIntReg(scratch1));
                    assert !asIntReg(dst).equals(asIntReg(scratch2));
                    new Srl(asIntReg(src1), 0, asIntReg(scratch1)).emit(masm);
                    new Srl(asIntReg(src2), 0, asIntReg(dst)).emit(masm);
                    exceptionOffset = masm.position();
                    new Udivx(asIntReg(scratch1), asIntReg(dst), asIntReg(scratch2)).emit(masm);
                    new Mulx(asIntReg(scratch2), asIntReg(dst), asIntReg(dst)).emit(masm);
                    delaySlotLir.emitControlTransfer(crb, masm);
                    new Sub(asIntReg(scratch1), asIntReg(dst), asIntReg(dst)).emit(masm);
                    break;
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        } else {
            throw GraalInternalError.shouldNotReachHere();
        }
        if (info != null) {
            assert exceptionOffset != -1;
            crb.recordImplicitException(exceptionOffset, info);
        }
    }

    public static void emitUnary(CompilationResultBuilder crb, SPARCMacroAssembler masm, SPARCArithmetic opcode, Value dst, Value src, LIRFrameState info, SPARCDelayedControlTransfer delaySlotLir) {
        int exceptionOffset = -1;
        Label notOrdered = new Label();
        switch (opcode) {
            case INEG:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Neg(asIntReg(src), asIntReg(dst)).emit(masm);
                break;
            case LNEG:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Neg(asLongReg(src), asLongReg(dst)).emit(masm);
                break;
            case INOT:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Not(asIntReg(src), asIntReg(dst)).emit(masm);
                break;
            case LNOT:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Not(asLongReg(src), asLongReg(dst)).emit(masm);
                break;
            case D2F:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Fdtos(asDoubleReg(src), asFloatReg(dst)).emit(masm);
                break;
            case L2D:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Fxtod(asDoubleReg(src), asDoubleReg(dst)).emit(masm);
                break;
            case L2F:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Fxtos(asDoubleReg(src), asFloatReg(dst)).emit(masm);
                break;
            case I2D:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Fitod(asFloatReg(src), asDoubleReg(dst)).emit(masm);
                break;
            case I2L:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Signx(asIntReg(src), asLongReg(dst)).emit(masm);
                break;
            case L2I:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Signx(asLongReg(src), asIntReg(dst)).emit(masm);
                break;
            case B2L:
                new Sll(asIntReg(src), 24, asLongReg(dst)).emit(masm);
                delaySlotLir.emitControlTransfer(crb, masm);
                new Sra(asLongReg(dst), 24, asLongReg(dst)).emit(masm);
                break;
            case B2I:
                new Sll(asIntReg(src), 24, asIntReg(dst)).emit(masm);
                delaySlotLir.emitControlTransfer(crb, masm);
                new Sra(asIntReg(dst), 24, asIntReg(dst)).emit(masm);
                break;
            case S2L:
                new Sll(asIntReg(src), 16, asLongReg(dst)).emit(masm);
                delaySlotLir.emitControlTransfer(crb, masm);
                new Sra(asLongReg(dst), 16, asLongReg(dst)).emit(masm);
                break;
            case S2I:
                new Sll(asIntReg(src), 16, asIntReg(dst)).emit(masm);
                delaySlotLir.emitControlTransfer(crb, masm);
                new Sra(asIntReg(dst), 16, asIntReg(dst)).emit(masm);
                break;
            case I2F:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Fitos(asFloatReg(src), asFloatReg(dst)).emit(masm);
                break;
            case F2D:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Fstod(asFloatReg(src), asDoubleReg(dst)).emit(masm);
                break;
            case F2L:
                new Fcmp(CC.Fcc0, Opfs.Fcmps, asFloatReg(src), asFloatReg(src)).emit(masm);
                masm.fbpcc(F_Ordered, ANNUL, notOrdered, Fcc0, PREDICT_TAKEN);
                new Fstox(asFloatReg(src), asDoubleReg(dst)).emit(masm);
                new Fsubd(asDoubleReg(dst), asDoubleReg(dst), asDoubleReg(dst)).emit(masm);
                masm.bind(notOrdered);
                break;
            case F2I:
                new Fcmp(CC.Fcc0, Opfs.Fcmps, asFloatReg(src), asFloatReg(src)).emit(masm);
                masm.fbpcc(F_Ordered, ANNUL, notOrdered, Fcc0, PREDICT_TAKEN);
                new Fstoi(asFloatReg(src), asFloatReg(dst)).emit(masm);
                new Fitos(asFloatReg(dst), asFloatReg(dst)).emit(masm);
                new Fsubs(asFloatReg(dst), asFloatReg(dst), asFloatReg(dst)).emit(masm);
                masm.bind(notOrdered);
                break;
            case D2L:
                new Fcmp(CC.Fcc0, Opfs.Fcmpd, asDoubleReg(src), asDoubleReg(src)).emit(masm);
                masm.fbpcc(F_Ordered, ANNUL, notOrdered, Fcc0, PREDICT_TAKEN);
                new Fdtox(asDoubleReg(src), asDoubleReg(dst)).emit(masm);
                new Fxtod(asDoubleReg(dst), asDoubleReg(dst)).emit(masm);
                new Fsubd(asDoubleReg(dst), asDoubleReg(dst), asDoubleReg(dst)).emit(masm);
                masm.bind(notOrdered);
                break;
            case D2I:
                new Fcmp(CC.Fcc0, Opfs.Fcmpd, asDoubleReg(src), asDoubleReg(src)).emit(masm);
                masm.fbpcc(F_Ordered, ANNUL, notOrdered, Fcc0, PREDICT_TAKEN);
                new Fdtoi(asDoubleReg(src), asFloatReg(dst)).emit(masm);
                new Fsubs(asFloatReg(dst), asFloatReg(dst), asFloatReg(dst)).emit(masm);
                new Fstoi(asFloatReg(dst), asFloatReg(dst)).emit(masm);
                masm.bind(notOrdered);
                break;
            case FNEG:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Fnegs(asFloatReg(src), asFloatReg(dst)).emit(masm);
                break;
            case DNEG:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Fnegd(asDoubleReg(src), asDoubleReg(dst)).emit(masm);
                break;
            default:
                throw GraalInternalError.shouldNotReachHere("missing: " + opcode);
        }
        if (info != null) {
            assert exceptionOffset != -1;
            crb.recordImplicitException(exceptionOffset, info);
        }
    }

    private static void verifyKind(SPARCArithmetic opcode, Value result, Value x, Value y) {
        Kind rk;
        Kind xk;
        Kind yk;
        Kind xsk;
        Kind ysk;

        switch (opcode) {
            case IADD:
            case IADDCC:
            case ISUB:
            case ISUBCC:
            case IMUL:
            case IMULCC:
            case IDIV:
            case IREM:
            case IAND:
            case IOR:
            case IXOR:
            case ISHL:
            case ISHR:
            case IUSHR:
            case IUDIV:
            case IUREM:
                rk = result.getKind().getStackKind();
                xsk = x.getKind().getStackKind();
                ysk = y.getKind().getStackKind();
                boolean valid = false;
                for (Kind k : new Kind[]{Kind.Int, Kind.Short, Kind.Byte, Kind.Char}) {
                    valid |= rk == k && xsk == k && ysk == k;
                }
                assert valid : "rk: " + rk + " xsk: " + xsk + " ysk: " + ysk;
                break;
            case LADD:
            case LADDCC:
            case LSUB:
            case LSUBCC:
            case LMUL:
            case LMULCC:
            case LDIV:
            case LREM:
            case LAND:
            case LOR:
            case LXOR:
            case LUDIV:
            case LUREM:
                rk = result.getKind();
                xk = x.getKind();
                yk = y.getKind();
                assert rk == Kind.Long && xk == Kind.Long && yk == Kind.Long;
                break;
            case LSHL:
            case LSHR:
            case LUSHR:
                rk = result.getKind();
                xk = x.getKind();
                yk = y.getKind();
                assert rk == Kind.Long && xk == Kind.Long && (yk == Kind.Int || yk == Kind.Long);
                break;
            case FADD:
            case FSUB:
            case FMUL:
            case FDIV:
            case FREM:
                rk = result.getKind();
                xk = x.getKind();
                yk = y.getKind();
                assert (rk == Kind.Float || rk == Kind.Double) && xk == Kind.Float && yk == Kind.Float;
                break;
            case DAND:
            case DADD:
            case DSUB:
            case DMUL:
            case DDIV:
            case DREM:
                rk = result.getKind();
                xk = x.getKind();
                yk = y.getKind();
                assert rk == Kind.Double && xk == Kind.Double && yk == Kind.Double : "opcode=" + opcode + ", result kind=" + rk + ", x kind=" + xk + ", y kind=" + yk;
                break;
            default:
                throw GraalInternalError.shouldNotReachHere("missing: " + opcode);
        }
    }

    public static class MulHighOp extends SPARCLIRInstruction {

        @Opcode private final SPARCArithmetic opcode;
        @Def({REG}) public AllocatableValue result;
        @Alive({REG}) public AllocatableValue x;
        @Alive({REG}) public AllocatableValue y;
        @Temp({REG}) public AllocatableValue scratch;

        public MulHighOp(SPARCArithmetic opcode, AllocatableValue x, AllocatableValue y, AllocatableValue result, AllocatableValue scratch) {
            this.opcode = opcode;
            this.x = x;
            this.y = y;
            this.scratch = scratch;
            this.result = result;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            assert isRegister(x) && isRegister(y) && isRegister(result) && isRegister(scratch);
            switch (opcode) {
                case IMUL:
                    new Mulx(asIntReg(x), asIntReg(y), asIntReg(result)).emit(masm);
                    new Srax(asIntReg(result), 32, asIntReg(result)).emit(masm);
                    break;
                case IUMUL:
                    assert !asIntReg(scratch).equals(asIntReg(result));
                    new Srl(asIntReg(x), 0, asIntReg(scratch)).emit(masm);
                    new Srl(asIntReg(y), 0, asIntReg(result)).emit(masm);
                    new Mulx(asIntReg(result), asIntReg(scratch), asIntReg(result)).emit(masm);
                    new Srlx(asIntReg(result), 32, asIntReg(result)).emit(masm);
                    break;
                case LMUL:
                    assert !asLongReg(scratch).equals(asLongReg(result));
                    new Umulxhi(asLongReg(x), asLongReg(y), asLongReg(result)).emit(masm);

                    new Srlx(asLongReg(x), 63, asLongReg(scratch)).emit(masm);
                    new Mulx(asLongReg(scratch), asLongReg(y), asLongReg(scratch)).emit(masm);
                    new Sub(asLongReg(result), asLongReg(scratch), asLongReg(result)).emit(masm);

                    new Srlx(asLongReg(y), 63, asLongReg(scratch)).emit(masm);
                    new Mulx(asLongReg(scratch), asLongReg(x), asLongReg(scratch)).emit(masm);
                    new Sub(asLongReg(result), asLongReg(scratch), asLongReg(result)).emit(masm);
                    break;
                case LUMUL:
                    new Umulxhi(asLongReg(x), asLongReg(y), asLongReg(result)).emit(masm);
                    break;
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        }
    }
}
