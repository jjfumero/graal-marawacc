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

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.asm.amd64.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.amd64.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.truffle.*;
import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;

/**
 * Subclass of {@link AMD64HotSpotBackend} that injects special code into
 * {@link OptimizedCallTarget#call(PackedFrame, Arguments)} for making a tail-call to the entry
 * point of the target callee.
 */
class AMD64HotSpotTruffleBackend extends AMD64HotSpotBackend {

    private ResolvedJavaMethod optimizedCallTargetCall;

    public AMD64HotSpotTruffleBackend(HotSpotGraalRuntime runtime, HotSpotProviders providers) {
        super(runtime, providers);
    }

    private ResolvedJavaMethod getInstrumentedMethod() throws GraalInternalError {
        if (optimizedCallTargetCall == null) {
            try {
                optimizedCallTargetCall = getProviders().getMetaAccess().lookupJavaMethod(OptimizedCallTarget.class.getDeclaredMethod("call", PackedFrame.class, Arguments.class));
            } catch (NoSuchMethodException | SecurityException e) {
                throw new GraalInternalError(e);
            }
        }
        return optimizedCallTargetCall;
    }

    @Override
    protected void emitCodePrefix(ResolvedJavaMethod installedCodeOwner, TargetMethodAssembler tasm, AMD64MacroAssembler asm, RegisterConfig regConfig, HotSpotVMConfig config, Label verifiedStub) {
        super.emitCodePrefix(installedCodeOwner, tasm, asm, regConfig, config, verifiedStub);
        if (getInstrumentedMethod().equals(installedCodeOwner)) {
            // TODO emit tailcall code
        }
    }
}
