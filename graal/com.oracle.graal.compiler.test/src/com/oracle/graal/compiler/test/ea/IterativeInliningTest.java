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
package com.oracle.graal.compiler.test.ea;

import static org.junit.Assert.*;

import java.util.concurrent.*;

import org.junit.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.compiler.test.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.phases.tiers.*;
import com.oracle.graal.virtual.phases.ea.*;

public class IterativeInliningTest extends GraalCompilerTest {

    private StructuredGraph graph;

    public static class TestObject {

        public Callable<Integer> callable;

        public TestObject(Callable<Integer> callable) {
            this.callable = callable;
        }
    }

    public static class TestInt implements Callable<Integer> {

        public int x;
        public int y;

        public TestInt(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public Integer call() throws Exception {
            return new Integer(x);
        }
    }

    @SuppressWarnings("all")
    public static int testSimpleSnippet(int b) throws Exception {
        TestObject a = new TestObject(null);
        a.callable = new TestInt(b, 9);
        return a.callable.call();
    }

    @Test
    public void testSimple() {
        ValueNode result = getReturn("testSimpleSnippet").result();
        assertTrue(graph.getNodes(LoadFieldNode.class).isEmpty());
        assertEquals(graph.getLocal(0), result);
    }

    final ReturnNode getReturn(String snippet) {
        processMethod(snippet);
        assertEquals(1, graph.getNodes(ReturnNode.class).count());
        return graph.getNodes(ReturnNode.class).first();
    }

    private void processMethod(final String snippet) {
        graph = parse(snippet);
        HighTierContext context = new HighTierContext(runtime(), new Assumptions(false), replacements);
        new IterativeInliningPhase(replacements, null, getDefaultPhasePlan(), OptimisticOptimizations.ALL, new CanonicalizerPhase(true)).apply(graph, context);
    }
}
