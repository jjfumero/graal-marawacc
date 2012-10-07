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
package com.oracle.graal.compiler.test;

import org.junit.*;

import com.oracle.graal.compiler.phases.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.loop.phases.*;
import com.oracle.graal.nodes.*;

public class LoopUnswitchTest extends GraalCompilerTest {

    @SuppressWarnings("all")
    public static int referenceSnippet1(int a) {
        int sum = 0;
        if (a > 2) {
            for (int i = 0; i < 1000; i++) {
                sum += 2;
            }
        } else {
            for (int i = 0; i < 1000; i++) {
                sum += a;
            }
        }
        return sum;
    }

    @SuppressWarnings("all")
    public static int test1Snippet(int a) {
        int sum = 0;
        for (int i = 0; i < 1000; i++) {
            if (a > 2) {
                sum += 2;
            } else {
                sum += a;
            }
        }
        return sum;
    }

    @Test
    public void test1() {
        test("test1Snippet", "referenceSnippet1");
    }

    private void test(String snippet, String referenceSnippet) {
        final StructuredGraph graph = parse(snippet);
        final StructuredGraph referenceGraph = parse(referenceSnippet);

        new LoopTransformLowPhase().apply(graph);

        // Framestates create comparison problems
        for (Node stateSplit : graph.getNodes().filterInterface(StateSplit.class)) {
            ((StateSplit) stateSplit).setStateAfter(null);
        }
        for (Node stateSplit : referenceGraph.getNodes().filterInterface(StateSplit.class)) {
            ((StateSplit) stateSplit).setStateAfter(null);
        }

        new CanonicalizerPhase(null, runtime(), null).apply(graph);
        new CanonicalizerPhase(null, runtime(), null).apply(referenceGraph);
        Debug.scope("Test", new DebugDumpScope("Test:" + snippet), new Runnable() {
            @Override
            public void run() {
                assertEquals(referenceGraph, graph);
            }
        });
    }
}
