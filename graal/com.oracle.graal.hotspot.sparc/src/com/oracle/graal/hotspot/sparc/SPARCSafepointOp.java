/*
 * Copyright (c) 2011, 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.sparc;

import static com.oracle.graal.asm.sparc.SPARCMacroAssembler.*;
import static com.oracle.graal.sparc.SPARC.*;
import static com.oracle.graal.phases.GraalOptions.*;
import sun.misc.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.sparc.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.bridge.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.sparc.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.nodes.spi.*;

/**
 * Emits a safepoint poll.
 */
@Opcode("SAFEPOINT")
public class SPARCSafepointOp extends SPARCLIRInstruction {

    private static final Unsafe unsafe = Unsafe.getUnsafe();

    @State protected LIRFrameState state;
    @Temp({OperandFlag.REG}) private AllocatableValue temp;

    private final HotSpotVMConfig config;

    public SPARCSafepointOp(LIRFrameState state, HotSpotVMConfig config, LIRGeneratorTool tool) {
        this.state = state;
        this.config = config;
        temp = tool.newVariable(tool.target().wordKind);
    }

    @Override
    public void emitCode(TargetMethodAssembler tasm, SPARCMacroAssembler masm) {
        final int pos = masm.codeBuffer.position();
        final int offset = SafepointPollOffset.getValue() % unsafe.pageSize();
        Register scratch = ((RegisterValue) temp).getRegister();
        new Setx(config.safepointPollingAddress + offset, scratch, scratch).emit(masm);
        tasm.recordMark(config.isPollingPageFar ? Marks.MARK_POLL_FAR : Marks.MARK_POLL_NEAR);
        tasm.recordInfopoint(pos, state, InfopointReason.SAFEPOINT);
        new Ldx(new SPARCAddress(scratch, 0), g0).emit(masm);
    }
}
