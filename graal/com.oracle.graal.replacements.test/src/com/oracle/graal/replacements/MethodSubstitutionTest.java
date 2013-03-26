/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.replacements;

import static org.junit.Assert.*;

import java.util.concurrent.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.replacements.*;
import com.oracle.graal.compiler.test.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.common.*;

/**
 * Tests if {@link MethodSubstitution}s are inlined correctly. Most test cases only assert that
 * there are no remaining invocations in the graph. This is sufficient if the method that is being
 * substituted is a native method. For Java methods, additional checks are necessary.
 */
public abstract class MethodSubstitutionTest extends GraalCompilerTest {

    protected StructuredGraph test(final String snippet) {
        return Debug.scope("MethodSubstitutionTest", runtime.lookupJavaMethod(getMethod(snippet)), new Callable<StructuredGraph>() {

            @Override
            public StructuredGraph call() {
                StructuredGraph graph = parse(snippet);
                PhasePlan phasePlan = getDefaultPhasePlan();
                Assumptions assumptions = new Assumptions(true);
                new ComputeProbabilityPhase().apply(graph);
                Debug.dump(graph, "Graph");
                new InliningPhase(runtime(), null, assumptions, null, phasePlan, OptimisticOptimizations.ALL).apply(graph);
                Debug.dump(graph, "Graph");
                new CanonicalizerPhase(runtime(), assumptions).apply(graph);
                new DeadCodeEliminationPhase().apply(graph);

                assertNotInGraph(graph, Invoke.class);
                return graph;
            }
        });
    }

    protected static StructuredGraph assertNotInGraph(StructuredGraph graph, Class<?> clazz) {
        for (Node node : graph.getNodes()) {
            if (clazz.isInstance(node)) {
                fail(node.toString());
            }
        }
        return graph;
    }

    protected static StructuredGraph assertInGraph(StructuredGraph graph, Class<?> clazz) {
        for (Node node : graph.getNodes()) {
            if (clazz.isInstance(node)) {
                return graph;
            }
        }
        fail("Graph does not contain a node of class " + clazz.getName());
        return graph;
    }
}
