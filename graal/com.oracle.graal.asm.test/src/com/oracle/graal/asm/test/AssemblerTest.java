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
package com.oracle.graal.asm.test;

import java.lang.reflect.*;

import org.junit.*;

import com.oracle.graal.api.runtime.*;
import com.oracle.graal.phases.util.*;
import com.oracle.graal.runtime.*;
import com.oracle.graal.test.*;
import com.oracle.jvmci.code.*;
import com.oracle.jvmci.debug.*;
import com.oracle.jvmci.debug.Debug.Scope;
import com.oracle.jvmci.meta.*;
import com.oracle.jvmci.service.*;

public abstract class AssemblerTest extends GraalTest {

    private final MetaAccessProvider metaAccess;
    protected final CodeCacheProvider codeCache;

    public interface CodeGenTest {
        byte[] generateCode(CompilationResult compResult, TargetDescription target, RegisterConfig registerConfig, CallingConvention cc);
    }

    public AssemblerTest() {
        Providers providers = Graal.getRequiredCapability(RuntimeProvider.class).getHostBackend().getProviders();
        this.metaAccess = providers.getMetaAccess();
        this.codeCache = providers.getCodeCache();
    }

    public MetaAccessProvider getMetaAccess() {
        return metaAccess;
    }

    protected InstalledCode assembleMethod(Method m, CodeGenTest test) {
        ResolvedJavaMethod method = getMetaAccess().lookupJavaMethod(m);
        try (Scope s = Debug.scope("assembleMethod", method, codeCache)) {
            RegisterConfig registerConfig = codeCache.getRegisterConfig();
            CallingConvention cc = CodeUtil.getCallingConvention(codeCache, CallingConvention.Type.JavaCallee, method, false);

            CompilationResult compResult = new CompilationResult();
            byte[] targetCode = test.generateCode(compResult, codeCache.getTarget(), registerConfig, cc);
            compResult.setTargetCode(targetCode, targetCode.length);
            compResult.setTotalFrameSize(0);

            InstalledCode code = codeCache.addMethod(method, compResult, null, null);

            for (DisassemblerProvider dis : Services.load(DisassemblerProvider.class)) {
                String disasm = dis.disassemble(code);
                Assert.assertTrue(code.toString(), disasm == null || disasm.length() > 0);
            }
            return code;
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
    }

    protected Object runTest(String methodName, CodeGenTest test, Object... args) {
        Method method = getMethod(methodName);
        InstalledCode code = assembleMethod(method, test);
        try {
            return code.executeVarargs(args);
        } catch (InvalidInstalledCodeException e) {
            throw new RuntimeException(e);
        }
    }

    protected void assertReturn(String methodName, CodeGenTest test, Object expected, Object... args) {
        Object actual = runTest(methodName, test, args);
        Assert.assertEquals("unexpected return value", expected, actual);
    }
}
