/*
 * Copyright (c) 2012, 2012, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.asm.sparc.SPARCAssembler.Andn;
import static com.oracle.graal.asm.sparc.SPARCAssembler.Ldsw;
import static com.oracle.graal.asm.sparc.SPARCAssembler.Ldx;
import static com.oracle.graal.asm.sparc.SPARCAssembler.Or;
import static com.oracle.graal.asm.sparc.SPARCAssembler.Popc;
import static com.oracle.graal.asm.sparc.SPARCAssembler.Srl;
import static com.oracle.graal.asm.sparc.SPARCAssembler.Srlx;
import static com.oracle.graal.asm.sparc.SPARCAssembler.Sub;
import static com.oracle.graal.asm.sparc.SPARCAssembler.isSimm13;
import static com.oracle.graal.asm.sparc.SPARCMacroAssembler.Mov;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.sparc.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.sparc.SPARC;

public class SPARCBitManipulationOp extends SPARCLIRInstruction {

    public enum IntrinsicOpcode {
        IPOPCNT, LPOPCNT, IBSR, LBSR, BSF;
    }

    @Opcode private final IntrinsicOpcode opcode;
    @Def protected AllocatableValue result;
    @Use({OperandFlag.REG, OperandFlag.STACK}) protected AllocatableValue input;

    public SPARCBitManipulationOp(IntrinsicOpcode opcode, AllocatableValue result, AllocatableValue input) {
        this.opcode = opcode;
        this.result = result;
        this.input = input;
    }

    @Override
    @SuppressWarnings("unused")
    public void emitCode(TargetMethodAssembler tasm, SPARCAssembler masm) {
        Register dst = ValueUtil.asIntReg(result);
        Register tmp = null;  // ??
        if (ValueUtil.isRegister(input)) {
            Register src = ValueUtil.asRegister(input);
            switch (opcode) {
                case IPOPCNT:
                    // clear upper word for 64 bit POPC
                    new Srl(masm, src, SPARC.g0, dst);
                    new Popc(masm, src, dst);
                    break;
                case LPOPCNT:
                    new Popc(masm, src, dst);
                    break;
                case BSF:
                    // countTrailingZerosI - bsfl
                    // countTrailingZerosL - masm.bsfq(dst, src);
                    Kind tkind = input.getKind();
                    if (tkind == Kind.Int) {
                        new Sub(masm, src, 1, dst);
                        new Andn(masm, dst, src, dst);
                        new Srl(masm, dst, SPARC.g0, dst);
                        new Popc(masm, dst, dst);
                    } else if (tkind == Kind.Long) {
                        new Sub(masm, src, 1, dst);
                        new Andn(masm, dst, src, dst);
                        new Popc(masm, dst, dst);
                    } else {
                        throw GraalInternalError.shouldNotReachHere("missing: " + tkind);
                    }
                    break;
                case IBSR:
                    // countLeadingZerosI_bsr masm.bsrq(dst, src);
                    // masm.bsrl(dst, src);
                    Kind ikind = input.getKind();
                    assert ikind == Kind.Int;
                    new Srl(masm, src, 1, tmp);
                    new Srl(masm, src, 0, dst);
                    new Or(masm, src, tmp, dst);
                    new Srl(masm, dst, 2, tmp);
                    new Or(masm, dst, tmp, dst);
                    new Srl(masm, dst, 4, tmp);
                    new Or(masm, dst, tmp, dst);
                    new Srl(masm, dst, 8, tmp);
                    new Or(masm, dst, tmp, dst);
                    new Srl(masm, dst, 16, tmp);
                    new Or(masm, dst, tmp, dst);
                    new Popc(masm, dst, dst);
                    new Mov(masm, ikind.getBitCount(), tmp);
                    new Sub(masm, tmp, dst, dst);
                    break;
                case LBSR:
                    // countLeadingZerosL_bsr masm.bsrq(dst, src);
                    // masm.bsrq(dst, src);
                    Kind lkind = input.getKind();
                    assert lkind == Kind.Int;
                    new Srlx(masm, src, 1, tmp);
                    new Or(masm, src, tmp, dst);
                    new Srlx(masm, dst, 2, tmp);
                    new Or(masm, dst, tmp, dst);
                    new Srlx(masm, dst, 4, tmp);
                    new Or(masm, dst, tmp, dst);
                    new Srlx(masm, dst, 8, tmp);
                    new Or(masm, dst, tmp, dst);
                    new Srlx(masm, dst, 16, tmp);
                    new Or(masm, dst, tmp, dst);
                    new Srlx(masm, dst, 32, tmp);
                    new Or(masm, dst, tmp, dst);
                    new Popc(masm, dst, dst);
                    new Mov(masm, lkind.getBitCount(), tmp);
                    new Sub(masm, tmp, dst, dst);
                    break;
                default:
                    throw GraalInternalError.shouldNotReachHere("missing: " + opcode);

            }
        } else if (ValueUtil.isConstant(input) && isSimm13(tasm.asIntConst(input))) {
            switch (opcode) {
                case IPOPCNT:
                    new Popc(masm, tasm.asIntConst(input), dst);
                    break;
                case LPOPCNT:
                    new Popc(masm, tasm.asIntConst(input), dst);
                    break;
                default:
                    throw GraalInternalError.shouldNotReachHere("missing: " + opcode);
            }
        } else {
            SPARCAddress src = (SPARCAddress) tasm.asAddress(input);
            switch (opcode) {
                case IPOPCNT:
                    new Ldsw(masm, src, tmp);
                    // clear upper word for 64 bit POPC
                    new Srl(masm, tmp, SPARC.g0, dst);
                    new Popc(masm, tmp, dst);
                    break;
                case LPOPCNT:
                    new Ldx(masm, src, tmp);
                    new Popc(masm, tmp, dst);
                    break;
                case BSF:
                    assert input.getKind() == Kind.Int;
                    new Ldsw(masm, src, tmp);
                    new Srl(masm, tmp, 1, tmp);
                    new Srl(masm, tmp, 0, dst);
                    new Or(masm, tmp, tmp, dst);
                    new Srl(masm, dst, 2, tmp);
                    new Or(masm, dst, tmp, dst);
                    new Srl(masm, dst, 4, tmp);
                    new Or(masm, dst, tmp, dst);
                    new Srl(masm, dst, 8, tmp);
                    new Or(masm, dst, tmp, dst);
                    new Srl(masm, dst, 16, tmp);
                    new Or(masm, dst, tmp, dst);
                    new Popc(masm, dst, dst);
                    new Mov(masm, Kind.Int.getBitCount(), tmp);
                    new Sub(masm, tmp, dst, dst);
                    break;
                case IBSR:
                    // masm.bsrl(dst, src);
                    // countLeadingZerosI_bsr masm.bsrq(dst, src);
                    // masm.bsrl(dst, src);
                case LBSR:
                    // masm.bsrq(dst, src);
                default:
                    throw GraalInternalError.shouldNotReachHere("missing: " + opcode);
            }
        }
    }

}
