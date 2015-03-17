/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
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

import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.asm.amd64.*;
import com.oracle.graal.asm.amd64.AMD64Assembler.AMD64RMIOp;
import com.oracle.graal.asm.amd64.AMD64Assembler.OperandSize;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.asm.*;

public class AMD64MulConstOp extends AMD64LIRInstruction {
    public static final LIRInstructionClass<AMD64MulConstOp> TYPE = LIRInstructionClass.create(AMD64MulConstOp.class);

    @Opcode private final AMD64RMIOp opcode;
    private final OperandSize size;

    @Def({REG}) protected AllocatableValue result;
    @Use({REG, STACK}) protected AllocatableValue x;
    protected JavaConstant y;

    public AMD64MulConstOp(AMD64RMIOp opcode, OperandSize size, AllocatableValue result, AllocatableValue x, JavaConstant y) {
        super(TYPE);
        this.opcode = opcode;
        this.size = size;

        this.result = result;
        this.x = x;
        this.y = y;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        assert NumUtil.isInt(y.asLong());
        int imm = (int) y.asLong();
        if (isRegister(x)) {
            opcode.emit(masm, size, asRegister(result), asRegister(x), imm);
        } else {
            assert isStackSlot(x);
            opcode.emit(masm, size, asRegister(result), (AMD64Address) crb.asAddress(x), imm);
        }
    }
}
