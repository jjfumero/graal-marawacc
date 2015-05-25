/*
 * Copyright (c) 2011, 2015, Oracle and/or its affiliates. All rights reserved.
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
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.*;
import static com.oracle.graal.sparc.SPARC.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.sparc.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.jvmci.common.*;

public class SPARCTestOp extends SPARCLIRInstruction {
    public static final LIRInstructionClass<SPARCTestOp> TYPE = LIRInstructionClass.create(SPARCTestOp.class);

    @Use({REG}) protected Value x;
    @Use({REG, CONST}) protected Value y;

    public SPARCTestOp(Value x, Value y) {
        super(TYPE);
        this.x = x;
        this.y = y;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
        if (isRegister(y)) {
            switch (x.getKind()) {
                case Short:
                case Byte:
                case Char:
                case Boolean:
                case Int:
                    masm.andcc(asIntReg(x), asIntReg(y), g0);
                    break;
                case Long:
                    masm.andcc(asLongReg(x), asLongReg(y), g0);
                    break;
                default:
                    throw JVMCIError.shouldNotReachHere();
            }
        } else if (isConstant(y)) {
            switch (x.getKind()) {
                case Short:
                case Byte:
                case Char:
                case Boolean:
                case Int:
                    masm.andcc(asIntReg(x), crb.asIntConst(y), g0);
                    break;
                case Long:
                    masm.andcc(asLongReg(x), crb.asIntConst(y), g0);
                    break;
                default:
                    throw JVMCIError.shouldNotReachHere();
            }
        } else {
            throw JVMCIError.shouldNotReachHere();
        }
    }

}
