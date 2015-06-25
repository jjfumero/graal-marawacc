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
package com.oracle.graal.hotspot.meta;

import static com.oracle.graal.hotspot.HotSpotGraalRuntime.*;
import jdk.internal.jvmci.code.*;
import jdk.internal.jvmci.hotspot.*;
import jdk.internal.jvmci.service.*;

import com.oracle.graal.code.*;

/**
 * HotSpot implementation of {@link DisassemblerProvider}.
 */
@ServiceProvider(DisassemblerProvider.class)
public class HotSpotDisassemblerProvider implements DisassemblerProvider {

    public String disassembleCompiledCode(CodeCacheProvider codeCache, CompilationResult compResult) {
        return null;
    }

    @Override
    public String disassembleInstalledCode(CodeCacheProvider codeCache, CompilationResult compResult, InstalledCode code) {
        if (code.isValid()) {
            long codeBlob = ((HotSpotInstalledCode) code).getAddress();
            return runtime().getCompilerToVM().disassembleCodeBlob(codeBlob);
        }
        return null;
    }

    public String getName() {
        return "hsdis";
    }
}
