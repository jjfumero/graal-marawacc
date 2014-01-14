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
package com.oracle.graal.hotspot.hsail;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.runtime.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hsail.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.phases.tiers.*;
import com.oracle.graal.phases.util.*;

@ServiceProvider(HotSpotBackendFactory.class)
public class HSAILHotSpotBackendFactory implements HotSpotBackendFactory {

    @Override
    public HSAILHotSpotBackend createBackend(HotSpotGraalRuntime runtime, HotSpotBackend hostBackend) {
        HotSpotProviders host = hostBackend.getProviders();

        HotSpotRegisters registers = new HotSpotRegisters(Register.None, Register.None, Register.None);
        HotSpotMetaAccessProvider metaAccess = host.getMetaAccess();
        HSAILHotSpotCodeCacheProvider codeCache = new HSAILHotSpotCodeCacheProvider(runtime, createTarget());
        ConstantReflectionProvider constantReflection = host.getConstantReflection();
        HotSpotForeignCallsProvider foreignCalls = new HSAILHotSpotForeignCallsProvider(runtime, metaAccess, codeCache);
        LoweringProvider lowerer = new HSAILHotSpotLoweringProvider(runtime, metaAccess, foreignCalls, registers);
        // Replacements cannot have speculative optimizations since they have
        // to be valid for the entire run of the VM.
        Assumptions assumptions = new Assumptions(false);
        Providers p = new Providers(metaAccess, codeCache, constantReflection, foreignCalls, lowerer, null);
        Replacements replacements = new HSAILHotSpotReplacementsImpl(p, assumptions, codeCache.getTarget(), host.getReplacements());
        HotSpotDisassemblerProvider disassembler = host.getDisassembler();
        SuitesProvider suites = host.getSuites();
        HotSpotProviders providers = new HotSpotProviders(metaAccess, codeCache, constantReflection, foreignCalls, lowerer, replacements, disassembler, suites, registers);

        return new HSAILHotSpotBackend(runtime, providers);
    }

    protected TargetDescription createTarget() {
        final int stackFrameAlignment = 8;
        final int implicitNullCheckLimit = 0;
        final boolean inlineObjects = true;
        return new TargetDescription(new HSAIL(), true, stackFrameAlignment, implicitNullCheckLimit, inlineObjects);
    }

    public String getArchitecture() {
        return "HSAIL";
    }

    public String getGraalRuntimeName() {
        return "basic";
    }

    @Override
    public String toString() {
        return getGraalRuntimeName() + ":" + getArchitecture();
    }
}
