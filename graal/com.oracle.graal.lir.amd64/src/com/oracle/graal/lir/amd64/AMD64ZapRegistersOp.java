/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.lir.amd64.AMD64SaveRegistersOp.prune;

import java.util.Set;

import jdk.internal.jvmci.code.Register;
import jdk.internal.jvmci.code.RegisterSaveLayout;
import jdk.internal.jvmci.code.RegisterValue;
import jdk.internal.jvmci.meta.JavaConstant;
import jdk.internal.jvmci.meta.LIRKind;
import jdk.internal.jvmci.meta.PlatformKind;

import com.oracle.graal.asm.amd64.AMD64MacroAssembler;
import com.oracle.graal.lir.LIRInstructionClass;
import com.oracle.graal.lir.Opcode;
import com.oracle.graal.lir.StandardOp.SaveRegistersOp;
import com.oracle.graal.lir.asm.CompilationResultBuilder;
import com.oracle.graal.lir.framemap.FrameMap;

/**
 * Writes well known garbage values to registers.
 */
@Opcode("ZAP_REGISTER")
public final class AMD64ZapRegistersOp extends AMD64LIRInstruction implements SaveRegistersOp {
    public static final LIRInstructionClass<AMD64ZapRegistersOp> TYPE = LIRInstructionClass.create(AMD64ZapRegistersOp.class);

    /**
     * The registers that are zapped.
     */
    protected final Register[] zappedRegisters;

    /**
     * The garbage values that are written to the registers.
     */
    protected final JavaConstant[] zapValues;

    public AMD64ZapRegistersOp(Register[] zappedRegisters, JavaConstant[] zapValues) {
        super(TYPE);
        this.zappedRegisters = zappedRegisters;
        this.zapValues = zapValues;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        for (int i = 0; i < zappedRegisters.length; i++) {
            Register reg = zappedRegisters[i];
            if (reg != null) {
                PlatformKind kind = crb.target.arch.getLargestStorableKind(reg.getRegisterCategory());
                RegisterValue registerValue = reg.asValue(LIRKind.value(kind));
                AMD64Move.const2reg(crb, masm, registerValue, zapValues[i]);
            }
        }
    }

    public boolean supportsRemove() {
        return true;
    }

    public int remove(Set<Register> doNotSave) {
        return prune(doNotSave, zappedRegisters);
    }

    public RegisterSaveLayout getMap(FrameMap frameMap) {
        return new RegisterSaveLayout(new Register[0], new int[0]);
    }
}
