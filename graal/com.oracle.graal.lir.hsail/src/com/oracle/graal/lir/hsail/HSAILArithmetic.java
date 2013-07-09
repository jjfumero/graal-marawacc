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
package com.oracle.graal.lir.hsail;

import static com.oracle.graal.api.code.ValueUtil.*;
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.hsail.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.asm.*;

// @formatter:off
/**
 * Defines arithmetic instruction nodes.
 */
public enum HSAILArithmetic {
    IADD, OADD, ISUB, FSUB, DSUB, IMUL, FMUL,
    DMUL, IDIV, FDIV, DDIV, IUADD, IUSUB,
    IUMUL, IUDIV, LADD, DADD, FADD, LSUB,
    LMUL, LDIV, LUADD, LUSUB, LUMUL,
    LUDIV, IMAX, LMAX, IUMAX, LUMAX,
    IMIN, LMIN, IUMIN, LUMIN, IREM,
    LREM, FREM, DREM, IUREM, LUREM,
    ICARRY, LCARRY, IUCARRY, LUCARRY,
    IAND, INEG, IUSHR, I2B, I2S, I2L,
    F2D, F2I, F2L, D2F, I2F, I2D, D2I,
    L2F, D2L, MOV_F2I, MOV_D2L, L2D, MOV_I2F,
    MOV_L2D, ISHL, SQRT, UNDEF, CALL, L2I;

    public static class Op1Stack extends HSAILLIRInstruction {
        @Opcode private final HSAILArithmetic opcode;
        @Def({REG, HINT}) protected Value result;
        @Use({REG, STACK, CONST}) protected Value x;

        public Op1Stack(HSAILArithmetic opcode, Value result, Value x) {
            this.opcode = opcode;
            this.result = result;
            this.x = x;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, HSAILAssembler masm) {
            emit(tasm, masm, opcode, result, x, null);
        }
    }

    public static class Op2Stack extends HSAILLIRInstruction {
        @Opcode private final HSAILArithmetic opcode;
        @Def({REG, HINT}) protected Value result;
        @Use({REG, CONST}) protected Value x;
        @Alive({REG, CONST}) protected Value y;

        public Op2Stack(HSAILArithmetic opcode, Value result, Value x, Value y) {
            this.opcode = opcode;
            this.result = result;
            this.x = x;
            this.y = y;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, HSAILAssembler masm) {
            emit(tasm, masm, opcode, result, x, y, null);
        }

        @Override
        public void verify() {
            super.verify();
            verifyKind(opcode, result, x, y);
        }
    }

    public static class Op1Reg extends HSAILLIRInstruction {
        @Opcode private final HSAILArithmetic opcode;
        @Def({REG, HINT}) protected Value result;
        @Use({REG}) protected Value x;

        public Op1Reg(HSAILArithmetic opcode, Value result, Value x) {
            this.opcode = opcode;
            this.result = result;
            this.x = x;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, HSAILAssembler masm) {
            emit(tasm, masm, opcode, result, x, null);
        }
    }

    public static class Op2Reg extends HSAILLIRInstruction {
        @Opcode private final HSAILArithmetic opcode;
        @Def({REG, HINT}) protected Value result;
        @Use({REG, STACK, CONST}) protected Value x;
        @Alive({REG, CONST}) protected Value y;

        public Op2Reg(HSAILArithmetic opcode, Value result, Value x, Value y) {
            this.opcode = opcode;
            this.result = result;
            this.x = x;
            this.y = y;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, HSAILAssembler masm) {
            emit(tasm, masm, opcode, result, x, y, null);
        }

        @Override
        public void verify() {
            super.verify();
            verifyKind(opcode, result, x, y);
        }
    }

    public static class Op2RegCommutative extends HSAILLIRInstruction {
        @Opcode private final HSAILArithmetic opcode;
        @Def({REG, HINT}) protected Value result;
        @Use({REG, STACK, CONST}) protected Value x;
        @Use({REG, CONST}) protected Value y;

        public Op2RegCommutative(HSAILArithmetic opcode, Value result, Value x, Value y) {
            this.opcode = opcode;
            this.result = result;
            this.x = x;
            this.y = y;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, HSAILAssembler masm) {
            throw GraalInternalError.shouldNotReachHere();
        }

        @Override
        protected void verify() {
            super.verify();
            verifyKind(opcode, result, x, y);
        }
    }

    public static class ShiftOp extends HSAILLIRInstruction {
        @Opcode private final HSAILArithmetic opcode;
        @Def({REG, HINT}) protected Value result;
        @Use({REG, STACK, CONST}) protected Value x;
        @Alive({REG, CONST}) protected Value y;

        public ShiftOp(HSAILArithmetic opcode, Value result, Value x, Value y) {
            this.opcode = opcode;
            this.result = result;
            this.x = x;
            this.y = y;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, HSAILAssembler masm) {
            emit(tasm, masm, opcode, result, x, y, null);
        }

        @Override
        public void verify() {
            super.verify();
            verifyKind(opcode, result, x, x);
            assert y.getKind().getStackKind() == Kind.Int;
        }
    }

    public static class DivOp extends HSAILLIRInstruction {
        @Opcode private final HSAILArithmetic opcode;
        @Def protected Value result;
        @Use protected Value x;
        @Alive protected Value y;
        @State protected LIRFrameState state;

        public DivOp(HSAILArithmetic opcode, Value result, Value x, Value y, LIRFrameState state) {
            this.opcode = opcode;
            this.result = result;
            this.x = x;
            this.y = y;
            this.state = state;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, HSAILAssembler masm) {
            emit(tasm, masm, opcode, result, y, state);
        }

        @Override
        protected void verify() {
            super.verify();
            verifyKind(opcode, result, x, y);
        }
    }

    @SuppressWarnings("unused")
    protected static void emit(TargetMethodAssembler tasm, HSAILAssembler masm, HSAILArithmetic opcode, Value result) {
        switch (opcode) {
            default:   throw GraalInternalError.shouldNotReachHere();
        }
    }

    public static void emit(TargetMethodAssembler tasm, HSAILAssembler masm, HSAILArithmetic opcode, Value dst, Value src, LIRFrameState info) {
        int exceptionOffset = -1;
        if (isRegister(src)) {
            switch (opcode) {
                case I2F:
                case I2D:
                case D2I:
                case I2L:
                case L2I:
                case F2D:
                case D2F: masm.emitConvert(dst, src); break;
                case SQRT: masm.emitArg1("sqrt", dst, src); break;
                case UNDEF: masm.undefined("undefined node"); break;
                case CALL: masm.undefined("undefined node CALL"); break;
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        }  else {
            throw GraalInternalError.shouldNotReachHere();
        }
        if (info != null) {
            assert exceptionOffset != -1;
            tasm.recordImplicitException(exceptionOffset, info);
        }
    }

    public static void emit(TargetMethodAssembler tasm, HSAILAssembler masm, HSAILArithmetic opcode, Value dst, Value src1, Value src2, LIRFrameState info) {
        int exceptionOffset = -1;
        switch (opcode) {
            case IADD:
            case LADD:
            case DADD:
            case FADD:
            case OADD:
                masm.emit("add", dst, src1, src2); break;
            case ISUB:
            case LSUB:
            case DSUB:
            case FSUB:
                masm.emit("sub", dst, src1, src2); break;
            case IMUL:
            case LMUL:
            case FMUL:
            case DMUL:
            case LUMUL:
                masm.emit("mul", dst, src1, src2); break;
            case IDIV:
            case LDIV:
            case FDIV:
            case DDIV:
                masm.emit("div", dst, src1, src2); break;
            case IMAX:
            case LMAX:
                masm.emit("max", dst, src1, src2); break;
            case IMIN:
            case LMIN:
                masm.emit("min", dst, src1, src2); break;
            case ISHL:
                masm.emit("shl", dst, src1, src2); break;
            case IREM:
                masm.emit("rem", dst, src1, src2); break;
            default:    throw GraalInternalError.shouldNotReachHere();
        }
        if (info != null) {
            assert exceptionOffset != -1;
            tasm.recordImplicitException(exceptionOffset, info);
        }
    }

    private static void verifyKind(HSAILArithmetic opcode, Value result, Value x, Value y) {
        assert (opcode.name().startsWith("I") && result.getKind() == Kind.Int && x.getKind().getStackKind() == Kind.Int && y.getKind().getStackKind() == Kind.Int)
        || (opcode.name().startsWith("L") && result.getKind() == Kind.Long && x.getKind() == Kind.Long && y.getKind() == Kind.Long)
        || (opcode.name().startsWith("LU") && result.getKind() == Kind.Long && x.getKind() == Kind.Long && y.getKind() == Kind.Int)
        || (opcode.name().startsWith("F") && result.getKind() == Kind.Float && x.getKind() == Kind.Float && y.getKind() == Kind.Float)
        || (opcode.name().startsWith("D") && result.getKind() == Kind.Double && x.getKind() == Kind.Double && y.getKind() == Kind.Double)
        || (opcode.name().startsWith("O") && result.getKind() == Kind.Object && x.getKind() == Kind.Object && (y.getKind() == Kind.Int || y.getKind() == Kind.Long)
                        );
    }
}
