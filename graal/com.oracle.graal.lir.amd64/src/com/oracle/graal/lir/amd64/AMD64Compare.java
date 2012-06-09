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

import java.util.*;

import com.oracle.max.asm.target.amd64.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.asm.*;

public enum AMD64Compare {
    ICMP, LCMP, ACMP, FCMP, DCMP;

    public static class CompareOp extends AMD64LIRInstruction {
        public CompareOp(AMD64Compare opcode, Value x, Value y) {
            super(opcode, LIRInstruction.NO_OPERANDS, null, new Value[] {x, y}, LIRInstruction.NO_OPERANDS, LIRInstruction.NO_OPERANDS);
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            Value x = input(0);
            Value y = input(1);
            emit(tasm, masm, (AMD64Compare) code, x, y);
        }

        @Override
        public EnumSet<OperandFlag> flagsFor(OperandMode mode, int index) {
            if (mode == OperandMode.Input && index == 0) {
                return EnumSet.of(OperandFlag.Register);
            } else if (mode == OperandMode.Input && index == 1) {
                return EnumSet.of(OperandFlag.Register, OperandFlag.Stack, OperandFlag.Constant);
            }
            throw GraalInternalError.shouldNotReachHere();
        }

        @Override
        protected void verify() {
            Value x = input(0);
            Value y = input(1);

            super.verify();
            assert (name().startsWith("I") && x.kind == Kind.Int && y.kind.stackKind() == Kind.Int)
                || (name().startsWith("I") && x.kind == Kind.Jsr && y.kind == Kind.Jsr)
                || (name().startsWith("L") && x.kind == Kind.Long && y.kind == Kind.Long)
                || (name().startsWith("A") && x.kind == Kind.Object && y.kind == Kind.Object)
                || (name().startsWith("F") && x.kind == Kind.Float && y.kind == Kind.Float)
                || (name().startsWith("D") && x.kind == Kind.Double && y.kind == Kind.Double);
        }
    }

    public static void emit(TargetMethodAssembler tasm, AMD64MacroAssembler masm, AMD64Compare opcode, Value x, Value y) {
        if (isRegister(y)) {
            switch (opcode) {
                case ICMP: masm.cmpl(asIntReg(x), asIntReg(y)); break;
                case LCMP: masm.cmpq(asLongReg(x), asLongReg(y)); break;
                case ACMP: masm.cmpptr(asObjectReg(x), asObjectReg(y)); break;
                case FCMP: masm.ucomiss(asFloatReg(x), asFloatReg(y)); break;
                case DCMP: masm.ucomisd(asDoubleReg(x), asDoubleReg(y)); break;
                default:   throw GraalInternalError.shouldNotReachHere();
            }
        } else if (isConstant(y)) {
            switch (opcode) {
                case ICMP: masm.cmpl(asIntReg(x), tasm.asIntConst(y)); break;
                case LCMP: masm.cmpq(asLongReg(x), tasm.asIntConst(y)); break;
                case ACMP:
                    if (((Constant) y).isNull()) {
                        masm.cmpq(asObjectReg(x), 0); break;
                    } else {
                        throw GraalInternalError.shouldNotReachHere("Only null object constants are allowed in comparisons");
                    }
                case FCMP: masm.ucomiss(asFloatReg(x), tasm.asFloatConstRef(y)); break;
                case DCMP: masm.ucomisd(asDoubleReg(x), tasm.asDoubleConstRef(y)); break;
                default:   throw GraalInternalError.shouldNotReachHere();
            }
        } else {
            switch (opcode) {
                case ICMP: masm.cmpl(asIntReg(x), tasm.asIntAddr(y)); break;
                case LCMP: masm.cmpq(asLongReg(x), tasm.asLongAddr(y)); break;
                case ACMP: masm.cmpptr(asObjectReg(x), tasm.asObjectAddr(y)); break;
                case FCMP: masm.ucomiss(asFloatReg(x), tasm.asFloatAddr(y)); break;
                case DCMP: masm.ucomisd(asDoubleReg(x), tasm.asDoubleAddr(y)); break;
                default:  throw GraalInternalError.shouldNotReachHere();
            }
        }
    }
}
