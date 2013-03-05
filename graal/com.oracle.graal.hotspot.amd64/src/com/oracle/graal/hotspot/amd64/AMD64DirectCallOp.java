/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.nodes.java.MethodCallTargetNode.InvokeKind.*;

import com.oracle.graal.amd64.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.amd64.*;
import com.oracle.graal.hotspot.bridge.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.LIRInstruction.Opcode;
import com.oracle.graal.lir.amd64.*;
import com.oracle.graal.lir.amd64.AMD64Call.DirectCallOp;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.nodes.java.MethodCallTargetNode.InvokeKind;

/**
 * A direct call that complies with the conventions for such calls in HotSpot. In particular, for
 * calls using an inline cache, a MOVE instruction is emitted just prior to the aligned direct call.
 * This instruction (which moves 0L in RAX) is patched by the C++ Graal code to replace the 0L
 * constant with Universe::non_oop_word(), a special sentinel used for the initial value of the
 * Klass in an inline cache.
 */
@Opcode("CALL_DIRECT")
final class AMD64DirectCallOp extends DirectCallOp {

    private final InvokeKind invokeKind;

    AMD64DirectCallOp(InvokeTarget target, Value result, Value[] parameters, Value[] temps, LIRFrameState state, InvokeKind invokeKind) {
        super(target, result, parameters, temps, state);
        this.invokeKind = invokeKind;
        assert invokeKind == InvokeKind.Interface || invokeKind == InvokeKind.Virtual;
    }

    @Override
    public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
        // The mark for an invocation that uses an inline cache must be placed at the
        // instruction
        // that loads the Klass from the inline cache so that the C++ code can find it
        // and replace the inline 0L value with Universe::non_oop_word()
        tasm.recordMark(invokeKind == Virtual ? Marks.MARK_INVOKEVIRTUAL : Marks.MARK_INVOKEINTERFACE);
        AMD64Move.move(tasm, masm, AMD64.rax.asValue(Kind.Long), Constant.LONG_0);
        emitAlignmentForDirectCall(tasm, masm);
        AMD64Call.directCall(tasm, masm, callTarget, state);
    }
}
