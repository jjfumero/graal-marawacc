/*
 * Copyright (c) 2012, 2015, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.hotspot.HotSpotHostBackend.UNCOMMON_TRAP_HANDLER;

import com.oracle.graal.asm.amd64.AMD64MacroAssembler;
import com.oracle.graal.lir.LIRFrameState;
import com.oracle.graal.lir.LIRInstructionClass;
import com.oracle.graal.lir.Opcode;
import com.oracle.graal.lir.StandardOp.BlockEndOp;
import com.oracle.graal.lir.amd64.AMD64BlockEndOp;
import com.oracle.graal.lir.amd64.AMD64Call;
import com.oracle.graal.lir.asm.CompilationResultBuilder;

@Opcode("DEOPT")
final class AMD64DeoptimizeOp extends AMD64BlockEndOp implements BlockEndOp {
    public static final LIRInstructionClass<AMD64DeoptimizeOp> TYPE = LIRInstructionClass.create(AMD64DeoptimizeOp.class);

    @State private LIRFrameState info;

    AMD64DeoptimizeOp(LIRFrameState info) {
        super(TYPE);
        this.info = info;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        AMD64Call.directCall(crb, masm, crb.foreignCalls.lookupForeignCall(UNCOMMON_TRAP_HANDLER), null, false, info);
    }
}
