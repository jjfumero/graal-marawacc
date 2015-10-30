/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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

import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.hotspot.HotSpotVMConfig;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.PlatformKind;

import com.oracle.graal.asm.amd64.AMD64Address;
import com.oracle.graal.asm.amd64.AMD64MacroAssembler;
import com.oracle.graal.lir.LIRInstructionClass;
import com.oracle.graal.lir.amd64.AMD64LIRInstruction;
import com.oracle.graal.lir.asm.CompilationResultBuilder;

public final class AMD64HotSpotCardTableShiftOp extends AMD64LIRInstruction {
    public static final LIRInstructionClass<AMD64HotSpotCardTableShiftOp> TYPE = LIRInstructionClass.create(AMD64HotSpotCardTableShiftOp.class);

    @Def({OperandFlag.REG, OperandFlag.ILLEGAL}) private AllocatableValue result;

    private final HotSpotVMConfig config;

    public AMD64HotSpotCardTableShiftOp(AllocatableValue result, HotSpotVMConfig config) {
        super(TYPE);
        this.result = result;
        this.config = config;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler asm) {
        PlatformKind wordKind = crb.target.arch.getWordKind();
        int alignment = JavaKind.Int.getBitCount() / Byte.SIZE;
        JavaConstant shift = JavaConstant.forPrimitiveInt(wordKind.getSizeInBytes() * 8, 0);
        // recordDataReferenceInCode forces the mov to be rip-relative
        asm.movq(ValueUtil.asRegister(result), (AMD64Address) crb.recordDataReferenceInCode(shift, alignment));
        crb.recordMark(config.MARKID_CARD_TABLE_SHIFT);
    }
}
