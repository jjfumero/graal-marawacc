/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.truffle.debug;

import static com.oracle.graal.compiler.GraalCompilerOptions.PrintBailout;

import java.util.LinkedHashMap;
import java.util.Map;

import jdk.vm.ci.code.BailoutException;

import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.truffle.GraalTruffleRuntime;
import com.oracle.graal.truffle.OptimizedCallTarget;

public final class TraceCompilationFailureListener extends AbstractDebugCompilationListener {

    private TraceCompilationFailureListener() {
    }

    public static void install(GraalTruffleRuntime runtime) {
        runtime.addCompilationListener(new TraceCompilationFailureListener());
    }

    @Override
    public void notifyCompilationFailed(OptimizedCallTarget target, StructuredGraph graph, Throwable t) {
        if (isPermanentBailout(t) || PrintBailout.getValue()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("Reason", t.toString());
            log(target, 0, "opt fail", target.toString(), properties);
        }
    }

    public static boolean isPermanentBailout(Throwable t) {
        return !(t instanceof BailoutException) || ((BailoutException) t).isPermanent();
    }

}
