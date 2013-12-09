/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.truffle.hotspot.amd64;

import com.oracle.graal.amd64.*;
import com.oracle.graal.api.code.CallingConvention.Type;
import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.runtime.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.asm.amd64.*;
import com.oracle.graal.asm.amd64.AMD64Assembler.ConditionFlag;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.amd64.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.truffle.*;
import com.oracle.graal.truffle.hotspot.*;

@ServiceProvider(OptimizedCallTargetInstrumentationFactory.class)
public class AMD64OptimizedCallTargetInstrumentationFactory implements OptimizedCallTargetInstrumentationFactory {

    public CompilationResultBuilder createBuilder(CodeCacheProvider codeCache, ForeignCallsProvider foreignCalls, FrameMap frameMap, AbstractAssembler asm, FrameContext frameContext,
                    CompilationResult compilationResult) {
        return new OptimizedCallTargetInstrumentation(codeCache, foreignCalls, frameMap, asm, frameContext, compilationResult) {
            @Override
            protected void injectTailCallCode(HotSpotVMConfig config, HotSpotRegistersProvider registers) {
                @SuppressWarnings("hiding")
                AMD64MacroAssembler asm = (AMD64MacroAssembler) this.asm;
                Register thisRegister = codeCache.getRegisterConfig().getCallingConventionRegisters(Type.JavaCall, Kind.Object)[0];
                Register spillRegister = AMD64.r10; // TODO(mg): fix me
                AMD64Address nMethodAddress = new AMD64Address(thisRegister, getFieldOffset("installedCode", OptimizedCallTarget.class));
                int verifiedEntryPoint = asm.codeBuffer.position();
                if (config.useCompressedOops) {
                    asm.movl(spillRegister, nMethodAddress);
                    asm.nop(AMD64HotSpotBackend.PATCHED_VERIFIED_ENTRY_POINT_INSTRUCTION_SIZE - (asm.codeBuffer.position() - verifiedEntryPoint));
                    AMD64HotSpotMove.decodePointer(asm, spillRegister, registers.getHeapBaseRegister(), config.narrowOopBase, config.narrowOopShift, config.logMinObjAlignment());
                } else {
                    asm.movq(spillRegister, nMethodAddress);
                    asm.nop(AMD64HotSpotBackend.PATCHED_VERIFIED_ENTRY_POINT_INSTRUCTION_SIZE - (asm.codeBuffer.position() - verifiedEntryPoint));
                }
                Label doProlog = new Label();

                asm.cmpq(spillRegister, 0);
                asm.jcc(ConditionFlag.Equal, doProlog);

                AMD64Address codeBlobAddress = new AMD64Address(spillRegister, getFieldOffset("codeBlob", HotSpotInstalledCode.class));
                asm.movq(spillRegister, codeBlobAddress);
                asm.cmpq(spillRegister, 0);
                asm.jcc(ConditionFlag.Equal, doProlog);

                AMD64Address verifiedEntryPointAddress = new AMD64Address(spillRegister, config.nmethodEntryOffset);
                asm.movq(spillRegister, verifiedEntryPointAddress);
                asm.jmp(spillRegister);

                asm.bind(doProlog);
            }
        };
    }

    public void setInstrumentedMethod(ResolvedJavaMethod method) {
        HotSpotResolvedJavaMethod hsMethod = (HotSpotResolvedJavaMethod) method;
        hsMethod.setDontInline();
    }

    public String getArchitecture() {
        return "AMD64";
    }
}
