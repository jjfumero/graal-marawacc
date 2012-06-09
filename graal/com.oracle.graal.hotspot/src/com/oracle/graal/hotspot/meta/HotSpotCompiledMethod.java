/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.reflect.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.hotspot.*;

/**
 * Implementation of RiCompiledMethod for HotSpot.
 * Stores a reference to the nmethod which contains the compiled code.
 * The nmethod also stores a weak reference to the HotSpotCompiledMethod
 * instance which is necessary to keep the nmethod from being unloaded.
 */
public class HotSpotCompiledMethod extends CompilerObject implements InstalledCode {

    private static final long serialVersionUID = 156632908220561612L;

    private final ResolvedJavaMethod method;
    private long nmethod;

    public HotSpotCompiledMethod(ResolvedJavaMethod method) {
        this.method = method;
    }

    @Override
    public ResolvedJavaMethod method() {
        return method;
    }

    @Override
    public boolean isValid() {
        return nmethod != 0;
    }

    @Override
    public String toString() {
        return "compiled method " + method + " @" + nmethod;
    }

    @Override
    public Object execute(Object arg1, Object arg2, Object arg3) {
        assert method.signature().argumentCount(!Modifier.isStatic(method.accessFlags())) == 3;
        assert method.signature().argumentKindAt(0) == Kind.Object;
        assert method.signature().argumentKindAt(1) == Kind.Object;
        assert !Modifier.isStatic(method.accessFlags()) || method.signature().argumentKindAt(2) == Kind.Object;
        return HotSpotGraalRuntime.getInstance().getCompilerToVM().executeCompiledMethod(this, arg1, arg2, arg3);
    }

    private boolean checkArgs(Object... args) {
        Kind[] sig = CodeUtil.signatureToKinds(method);
        assert args.length == sig.length : CodeUtil.format("%H.%n(%p): expected ", method) + sig.length + " args, got " + args.length;
        for (int i = 0; i < sig.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                assert sig[i].isObject() : CodeUtil.format("%H.%n(%p): expected arg ", method) + i + " to be Object, not " + sig[i];
            } else if (!sig[i].isObject()) {
                assert sig[i].toBoxedJavaClass() == arg.getClass() : CodeUtil.format("%H.%n(%p): expected arg ", method) + i + " to be " + sig[i] + ", not " + arg.getClass();
            }
        }
        return true;
    }

    @Override
    public Object executeVarargs(Object... args) {
        assert checkArgs(args);
        return HotSpotGraalRuntime.getInstance().getCompilerToVM().executeCompiledMethodVarargs(this, args);
    }
}
