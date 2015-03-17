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
import com.oracle.graal.asm.amd64.AMD64Assembler.AMD64RMOp;
import com.oracle.graal.asm.amd64.AMD64Assembler.OperandSize;
import com.oracle.graal.asm.amd64.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.StandardOp.ImplicitNullCheck;
import com.oracle.graal.lir.asm.*;

public class AMD64BinaryMemoryOp extends AMD64LIRInstruction implements ImplicitNullCheck {
    public static final LIRInstructionClass<AMD64BinaryMemoryOp> TYPE = LIRInstructionClass.create(AMD64BinaryMemoryOp.class);

    @Opcode private final AMD64RMOp opcode;
    private final OperandSize size;

    @Def({REG, HINT}) protected AllocatableValue result;
    @Use({REG, STACK}) protected AllocatableValue x;
    @Alive({COMPOSITE}) protected AMD64AddressValue y;

    @State protected LIRFrameState state;

    public AMD64BinaryMemoryOp(AMD64RMOp opcode, OperandSize size, AllocatableValue result, AllocatableValue x, AMD64AddressValue y, LIRFrameState state) {
        super(TYPE);
        this.opcode = opcode;
        this.size = size;

        this.result = result;
        this.x = x;
        this.y = y;

        this.state = state;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        AMD64Move.move(crb, masm, result, x);
        if (state != null) {
            crb.recordImplicitException(masm.position(), state);
        }
        opcode.emit(masm, size, asRegister(result), y.toAddress());
    }

    @Override
    public void verify() {
        super.verify();
        assert differentRegisters(result, y) || sameRegister(x, y);
    }

    @Override
    public boolean makeNullCheckFor(Value value, LIRFrameState nullCheckState, int implicitNullCheckLimit) {
        if (state == null && y.isValidImplicitNullCheckFor(value, implicitNullCheckLimit)) {
            state = nullCheckState;
            return true;
        }
        return false;
    }
}
