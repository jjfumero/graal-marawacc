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
package com.oracle.graal.hotspot.amd64;

import static com.oracle.graal.asm.amd64.AMD64Assembler.OperandSize.DWORD;
import jdk.vm.ci.hotspot.HotSpotConstant;
import jdk.vm.ci.hotspot.HotSpotObjectConstant;
import jdk.vm.ci.meta.AllocatableValue;

import com.oracle.graal.asm.amd64.AMD64Assembler.AMD64MIOp;
import com.oracle.graal.asm.amd64.AMD64MacroAssembler;
import com.oracle.graal.lir.LIRFrameState;
import com.oracle.graal.lir.LIRInstructionClass;
import com.oracle.graal.lir.amd64.AMD64AddressValue;
import com.oracle.graal.lir.amd64.AMD64BinaryConsumer;
import com.oracle.graal.lir.asm.CompilationResultBuilder;

public class AMD64HotSpotBinaryConsumer {

    /**
     * Instruction that has one {@link AllocatableValue} operand and one {@link HotSpotConstant}
     * operand.
     */
    public static class ConstOp extends AMD64BinaryConsumer.ConstOp {
        public static final LIRInstructionClass<ConstOp> TYPE = LIRInstructionClass.create(ConstOp.class);

        protected final HotSpotConstant c;

        public ConstOp(AMD64MIOp opcode, AllocatableValue x, HotSpotConstant c) {
            super(TYPE, opcode, DWORD, x, 0xDEADDEAD);
            this.c = c;
            assert c.isCompressed();
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            assert crb.target.inlineObjects || !(c instanceof HotSpotObjectConstant);
            crb.recordInlineDataInCode(c);
            super.emitCode(crb, masm);
        }
    }

    /**
     * Instruction that has one {@link AMD64AddressValue memory} operand and one
     * {@link HotSpotConstant} operand.
     */
    public static class MemoryConstOp extends AMD64BinaryConsumer.MemoryConstOp {
        public static final LIRInstructionClass<MemoryConstOp> TYPE = LIRInstructionClass.create(MemoryConstOp.class);

        protected final HotSpotConstant c;

        public MemoryConstOp(AMD64MIOp opcode, AMD64AddressValue x, HotSpotConstant c, LIRFrameState state) {
            super(TYPE, opcode, DWORD, x, 0xDEADDEAD, state);
            this.c = c;
            assert c.isCompressed();
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            assert crb.target.inlineObjects || !(c instanceof HotSpotObjectConstant);
            crb.recordInlineDataInCode(c);
            super.emitCode(crb, masm);
        }
    }
}
