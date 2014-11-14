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

package com.oracle.graal.hotspot.test;

import static org.junit.Assert.*;

import org.junit.*;

import com.oracle.graal.compiler.test.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;

public class ClassSubstitutionsTests extends GraalCompilerTest {

    public Number instanceField;

    public Object[] arrayField;

    public String[] stringArrayField;

    protected StructuredGraph test(final String snippet) {
        try (Scope s = Debug.scope("ClassSubstitutionsTest", getMetaAccess().lookupJavaMethod(getMethod(snippet)))) {
            StructuredGraph graph = parseEager(snippet);
            compile(graph.method(), graph);
            assertNotInGraph(graph, Invoke.class);
            Debug.dump(graph, snippet);
            return graph;
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
    }

    protected static StructuredGraph assertNotInGraph(StructuredGraph graph, Class<?> clazz) {
        for (Node node : graph.getNodes()) {
            if (clazz.isInstance(node)) {
                fail(node.toString());
            }
        }
        return graph;
    }

    public boolean constantIsArray() {
        return "".getClass().isArray();
    }

    public boolean constantIsInterface() {
        return "".getClass().isInterface();
    }

    public boolean constantIsPrimitive() {
        return "".getClass().isPrimitive();
    }

    @Test
    public void testIsArray() {
        testConstantReturn("constantIsArray", 0);
    }

    @Test
    public void testIsInterface() {
        testConstantReturn("constantIsInterface", 0);
    }

    @Test
    public void testIsPrimitive() {
        testConstantReturn("constantIsPrimitive", 0);
    }

    public boolean fieldIsNotArray() {
        if (instanceField != null) {
            // The base type of instanceField is not Object or an Interface, so it's provably an
            // instance type, so isArray will always return false.
            return instanceField.getClass().isArray();
        }
        return false;
    }

    @Test
    public void testFieldIsNotArray() {
        testConstantReturn("fieldIsNotArray", 0);
    }

    public boolean foldComponentType() {
        return stringArrayField.getClass().getComponentType() == String.class;
    }

    @Test
    public void testFoldComponenetType() {
        testConstantReturn("foldComponentType", 1);
    }

    @Ignore("Can't constant fold LoadHubNode == 0 yet")
    @Test
    public void testFieldIsArray() {
        testConstantReturn("fieldIsArray", 1);
    }

    public boolean fieldIsArray() {
        if (arrayField != null) {
            // The base type of arrayField is an array of some sort so isArray will always return
            // true.
            return arrayField.getClass().isArray();
        }
        return true;
    }

    private void testConstantReturn(String name, Object value) {
        StructuredGraph result = test(name);
        ReturnNode ret = result.getNodes(ReturnNode.class).first();
        assertDeepEquals(1, result.getNodes(ReturnNode.class).count());

        assertDeepEquals(true, ret.result().isConstant());
        assertDeepEquals(value, ret.result().asJavaConstant().asBoxedPrimitive());
    }
}
