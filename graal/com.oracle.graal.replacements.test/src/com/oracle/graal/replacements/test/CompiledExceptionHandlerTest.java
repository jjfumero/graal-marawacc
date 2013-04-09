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
package com.oracle.graal.replacements.test;

import java.lang.reflect.*;

import org.junit.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.test.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.common.*;

/**
 * Tests compilation of a hot exception handler.
 */
public class CompiledExceptionHandlerTest extends GraalCompilerTest {

    @Override
    protected void editPhasePlan(ResolvedJavaMethod method, StructuredGraph graph, PhasePlan phasePlan) {
        phasePlan.disablePhase(InliningPhase.class);
    }

    @Override
    protected StructuredGraph parse(Method m) {
        StructuredGraph graph = super.parse(m);
        int handlers = graph.getNodes().filter(ExceptionObjectNode.class).count();
        Assert.assertEquals(1, handlers);
        return graph;
    }

    private static void raiseException(String s) {
        throw new RuntimeException(s);
    }

    @Test
    public void test1() {
        // Ensure the profile shows a hot exception
        for (int i = 0; i < 10000; i++) {
            test1Snippet("");
            test1Snippet(null);
        }

        test("test1Snippet", "a string");
    }

    public static String test1Snippet(String message) {
        if (message != null) {
            try {
                raiseException(message);
            } catch (Exception e) {
                return message;
            }
        }
        return null;
    }
}
