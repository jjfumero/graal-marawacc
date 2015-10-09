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
package com.oracle.graal.lir.amd64;

import static jdk.vm.ci.code.ValueUtil.asRegister;
import jdk.vm.ci.amd64.AMD64Kind;
import jdk.vm.ci.common.JVMCIError;
import jdk.vm.ci.meta.Value;

import com.oracle.graal.asm.amd64.AMD64MacroAssembler;
import com.oracle.graal.lir.LIRInstructionClass;
import com.oracle.graal.lir.Opcode;
import com.oracle.graal.lir.asm.CompilationResultBuilder;

// @formatter:off
public final class AMD64MathIntrinsicOp extends AMD64LIRInstruction {
    public static final LIRInstructionClass<AMD64MathIntrinsicOp> TYPE = LIRInstructionClass.create(AMD64MathIntrinsicOp.class);
    public enum IntrinsicOpcode  {
        SIN, COS, TAN,
        LOG, LOG10
    }

    @Opcode private final IntrinsicOpcode opcode;
    @Def protected Value result;
    @Use protected Value input;

    public AMD64MathIntrinsicOp(IntrinsicOpcode opcode, Value result, Value input) {
        super(TYPE);
        this.opcode = opcode;
        this.result = result;
        this.input = input;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        switch (opcode) {
            case LOG:   masm.flog(asRegister(result, AMD64Kind.DOUBLE), asRegister(input, AMD64Kind.DOUBLE), false); break;
            case LOG10: masm.flog(asRegister(result, AMD64Kind.DOUBLE), asRegister(input, AMD64Kind.DOUBLE), true); break;
            case SIN:   masm.fsin(asRegister(result, AMD64Kind.DOUBLE), asRegister(input, AMD64Kind.DOUBLE)); break;
            case COS:   masm.fcos(asRegister(result, AMD64Kind.DOUBLE), asRegister(input, AMD64Kind.DOUBLE)); break;
            case TAN:   masm.ftan(asRegister(result, AMD64Kind.DOUBLE), asRegister(input, AMD64Kind.DOUBLE)); break;
            default:    throw JVMCIError.shouldNotReachHere();
        }
    }
}
