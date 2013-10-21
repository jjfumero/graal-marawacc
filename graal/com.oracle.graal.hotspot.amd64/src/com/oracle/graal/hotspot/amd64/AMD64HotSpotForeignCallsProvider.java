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
package com.oracle.graal.hotspot.amd64;

import static com.oracle.graal.amd64.AMD64.*;
import static com.oracle.graal.api.code.CallingConvention.Type.*;
import static com.oracle.graal.api.meta.LocationIdentity.*;
import static com.oracle.graal.api.meta.Value.*;
import static com.oracle.graal.hotspot.HotSpotBackend.*;
import static com.oracle.graal.hotspot.HotSpotForeignCallLinkage.*;
import static com.oracle.graal.hotspot.HotSpotForeignCallLinkage.RegisterEffect.*;
import static com.oracle.graal.hotspot.HotSpotForeignCallLinkage.Transition.*;
import static com.oracle.graal.hotspot.replacements.AESCryptSubstitutions.*;
import static com.oracle.graal.hotspot.replacements.CRC32Substitutions.*;
import static com.oracle.graal.hotspot.replacements.CipherBlockChainingSubstitutions.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.meta.*;

public class AMD64HotSpotForeignCallsProvider extends HotSpotHostForeignCallsProvider {

    private final Value[] nativeABICallerSaveRegisters;

    public AMD64HotSpotForeignCallsProvider(HotSpotGraalRuntime runtime, MetaAccessProvider metaAccess, CodeCacheProvider codeCache, Value[] nativeABICallerSaveRegisters) {
        super(runtime, metaAccess, codeCache);
        this.nativeABICallerSaveRegisters = nativeABICallerSaveRegisters;
    }

    @Override
    public void initialize(HotSpotProviders providers, HotSpotVMConfig config) {
        Kind word = providers.getCodeCache().getTarget().wordKind;

        // The calling convention for the exception handler stub is (only?) defined in
        // TemplateInterpreterGenerator::generate_throw_exception()
        // in templateInterpreter_x86_64.cpp around line 1923
        RegisterValue exception = rax.asValue(Kind.Object);
        RegisterValue exceptionPc = rdx.asValue(word);
        CallingConvention exceptionCc = new CallingConvention(0, ILLEGAL, exception, exceptionPc);
        register(new HotSpotForeignCallLinkage(EXCEPTION_HANDLER, 0L, PRESERVES_REGISTERS, LEAF, null, exceptionCc, NOT_REEXECUTABLE, ANY_LOCATION));
        register(new HotSpotForeignCallLinkage(EXCEPTION_HANDLER_IN_CALLER, JUMP_ADDRESS, PRESERVES_REGISTERS, LEAF, exceptionCc, null, NOT_REEXECUTABLE, ANY_LOCATION));

        // These stubs do callee saving
        registerForeignCall(ENCRYPT_BLOCK, config.aescryptEncryptBlockStub, NativeCall, PRESERVES_REGISTERS, LEAF, NOT_REEXECUTABLE, ANY_LOCATION);
        registerForeignCall(DECRYPT_BLOCK, config.aescryptDecryptBlockStub, NativeCall, PRESERVES_REGISTERS, LEAF, NOT_REEXECUTABLE, ANY_LOCATION);
        registerForeignCall(ENCRYPT, config.cipherBlockChainingEncryptAESCryptStub, NativeCall, PRESERVES_REGISTERS, LEAF, NOT_REEXECUTABLE, ANY_LOCATION);
        registerForeignCall(DECRYPT, config.cipherBlockChainingDecryptAESCryptStub, NativeCall, PRESERVES_REGISTERS, LEAF, NOT_REEXECUTABLE, ANY_LOCATION);
        registerForeignCall(UPDATE_BYTES_CRC32, config.updateBytesCRC32Stub, NativeCall, PRESERVES_REGISTERS, LEAF, NOT_REEXECUTABLE, ANY_LOCATION);

        super.initialize(providers, config);
    }

    @Override
    public Value[] getNativeABICallerSaveRegisters() {
        return nativeABICallerSaveRegisters;
    }

}
