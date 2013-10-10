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
package com.oracle.graal.api.code;

import com.oracle.graal.api.meta.*;

/**
 * A {@link CodeCacheProvider} that delegates to another {@link CodeCacheProvider}.
 */
public class DelegatingCodeCacheProvider implements CodeCacheProvider {

    private final CodeCacheProvider delegate;

    public DelegatingCodeCacheProvider(CodeCacheProvider delegate) {
        this.delegate = delegate;
    }

    protected CodeCacheProvider delegate() {
        return delegate;
    }

    public InstalledCode addMethod(ResolvedJavaMethod method, CompilationResult compResult) {
        return delegate().addMethod(method, compResult);
    }

    public String disassemble(CompilationResult compResult, InstalledCode installedCode) {
        return delegate().disassemble(compResult, installedCode);
    }

    public RegisterConfig lookupRegisterConfig() {
        return delegate().lookupRegisterConfig();
    }

    public int getMinimumOutgoingSize() {
        return delegate().getMinimumOutgoingSize();
    }

    public ForeignCallLinkage lookupForeignCall(ForeignCallDescriptor descriptor) {
        return delegate().lookupForeignCall(descriptor);
    }

    public boolean needsDataPatch(Constant constant) {
        return delegate().needsDataPatch(constant);
    }

    public TargetDescription getTarget() {
        return delegate().getTarget();
    }
}
