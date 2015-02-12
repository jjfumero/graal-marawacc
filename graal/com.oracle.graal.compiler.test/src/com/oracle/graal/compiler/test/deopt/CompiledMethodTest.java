/*
 * Copyright (c) 2011, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.compiler.test.deopt;

import org.junit.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.test.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.StructuredGraph.AllowAssumptions;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.phases.tiers.*;

/**
 * In the following tests, the usages of local variable "a" are replaced with the integer constant
 * 0. Then canonicalization is applied and it is verified that the resulting graph is equal to the
 * graph of the method that just has a "return 1" statement in it.
 */
public class CompiledMethodTest extends GraalCompilerTest {

    public static Object testMethod(Object arg1, Object arg2, Object arg3) {
        return arg1 + " " + arg2 + " " + arg3;
    }

    Object f1;

    public Object testMethodVirtual(Object arg1, Object arg2, Object arg3) {
        return f1 + " " + arg1 + " " + arg2 + " " + arg3;
    }

    @Test
    public void test1() {
        final ResolvedJavaMethod javaMethod = getResolvedJavaMethod("testMethod");
        final StructuredGraph graph = parseEager(javaMethod, AllowAssumptions.NO);
        new CanonicalizerPhase(true).apply(graph, new PhaseContext(getProviders()));
        new DeadCodeEliminationPhase().apply(graph);

        for (ConstantNode node : ConstantNode.getConstantNodes(graph)) {
            if (node.getKind() == Kind.Object && " ".equals(getSnippetReflection().asObject(String.class, node.asJavaConstant()))) {
                node.replace(graph, ConstantNode.forConstant(getSnippetReflection().forObject("-"), getMetaAccess(), graph));
            }
        }

        InstalledCode compiledMethod = getCode(javaMethod, graph);
        try {
            Object result = compiledMethod.executeVarargs("1", "2", "3");
            Assert.assertEquals("1-2-3", result);
        } catch (InvalidInstalledCodeException t) {
            Assert.fail("method invalidated");
        }
    }

    @Test
    public void test3() {
        final ResolvedJavaMethod javaMethod = getResolvedJavaMethod("testMethod");
        InstalledCode compiledMethod = getCode(javaMethod);
        try {
            Object result = compiledMethod.executeVarargs("1", "2", "3");
            Assert.assertEquals("1 2 3", result);
        } catch (InvalidInstalledCodeException t) {
            Assert.fail("method invalidated");
        }
    }

    @Test
    public void test4() {
        final ResolvedJavaMethod javaMethod = getResolvedJavaMethod("testMethodVirtual");
        InstalledCode compiledMethod = getCode(javaMethod);
        try {
            f1 = "0";
            Object result = compiledMethod.executeVarargs(this, "1", "2", "3");
            Assert.assertEquals("0 1 2 3", result);
        } catch (InvalidInstalledCodeException t) {
            Assert.fail("method invalidated");
        }
    }
}
