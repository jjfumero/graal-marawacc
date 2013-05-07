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
package com.oracle.graal.compiler.test;

import static com.oracle.graal.api.code.CodeUtil.*;
import static org.junit.Assert.*;

import java.lang.reflect.*;

import org.junit.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.CallingConvention.Type;
import com.oracle.graal.api.code.CompilationResult.Call;
import com.oracle.graal.api.code.CompilationResult.Infopoint;
import com.oracle.graal.compiler.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.test.*;

/**
 * Test that infopoints in {@link CompilationResult}s have correctly assigned reasons.
 */
public class InfopointReasonTest extends GraalCompilerTest {

    public static final String[] STRINGS = new String[]{"world", "everyone", "you"};

    public String testMethod() {
        StringBuilder sb = new StringBuilder("Hello ");
        for (String s : STRINGS) {
            sb.append(s).append(", ");
        }
        sb.replace(sb.length() - 2, sb.length(), "!");
        return sb.toString();
    }

    @Test
    public void callInfopoints() {
        final Method method = getMethod("testMethod");
        final StructuredGraph graph = parse(method);
        CallingConvention cc = getCallingConvention(runtime, Type.JavaCallee, graph.method(), false);
        final CompilationResult cr = GraalCompiler.compileMethod(runtime, replacements, backend, runtime.getTarget(), runtime.lookupJavaMethod(method), graph, cc, null, getDefaultPhasePlan(),
                        OptimisticOptimizations.ALL, new SpeculationLog());
        for (Infopoint sp : cr.getInfopoints()) {
            assertNotNull(sp.reason);
            if (sp instanceof Call) {
                assertEquals(InfopointReason.CALL, sp.reason);
            }
        }
    }

    @LongTest
    public void lineInfopoints() {
        final Method method = getMethod("testMethod");
        final StructuredGraph graph = parseDebug(method);
        int graphLineSPs = 0;
        for (InfopointNode ipn : graph.getNodes(InfopointNode.class)) {
            if (ipn.reason == InfopointReason.LINE_NUMBER) {
                ++graphLineSPs;
            }
        }
        assertTrue(graphLineSPs > 0);
        CallingConvention cc = getCallingConvention(runtime, Type.JavaCallee, graph.method(), false);
        final CompilationResult cr = GraalCompiler.compileMethod(runtime, replacements, backend, runtime.getTarget(), runtime.lookupJavaMethod(method), graph, cc, null, getDefaultPhasePlan(true),
                        OptimisticOptimizations.ALL, new SpeculationLog());
        int lineSPs = 0;
        for (Infopoint sp : cr.getInfopoints()) {
            assertNotNull(sp.reason);
            if (sp.reason == InfopointReason.LINE_NUMBER) {
                ++lineSPs;
            }
        }
        assertTrue(lineSPs > 0);
    }

}
