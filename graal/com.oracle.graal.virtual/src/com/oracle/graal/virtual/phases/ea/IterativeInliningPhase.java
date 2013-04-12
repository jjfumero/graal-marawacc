/*
 * Copyright (c) 2011, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.virtual.phases.ea;

import java.util.*;
import java.util.concurrent.*;

import com.oracle.graal.debug.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.phases.tiers.*;

public class IterativeInliningPhase extends BasePhase<HighTierContext> {

    private final PhasePlan plan;

    private final Replacements replacements;
    private final GraphCache cache;
    private final OptimisticOptimizations optimisticOpts;
    private final boolean readElimination;

    public IterativeInliningPhase(Replacements replacements, GraphCache cache, PhasePlan plan, OptimisticOptimizations optimisticOpts, boolean readElimination) {
        this.replacements = replacements;
        this.cache = cache;
        this.plan = plan;
        this.optimisticOpts = optimisticOpts;
        this.readElimination = readElimination;
    }

    public static final void trace(String format, Object... obj) {
        if (GraalOptions.TraceEscapeAnalysis) {
            Debug.log(format, obj);
        }
    }

    @Override
    protected void run(final StructuredGraph graph, final HighTierContext context) {
        runIterations(graph, true, context);
        runIterations(graph, false, context);
    }

    private void runIterations(final StructuredGraph graph, final boolean simple, final HighTierContext context) {
        Boolean continueIteration = true;
        for (int iteration = 0; iteration < GraalOptions.EscapeAnalysisIterations && continueIteration; iteration++) {
            continueIteration = Debug.scope("iteration " + iteration, new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    boolean progress = false;
                    PartialEscapeAnalysisPhase ea = new PartialEscapeAnalysisPhase(false, readElimination);
                    boolean eaResult = ea.runAnalysis(graph, context);
                    progress |= eaResult;

                    Map<Invoke, Double> hints = GraalOptions.PEAInliningHints ? PartialEscapeAnalysisPhase.getHints(graph) : null;

                    InliningPhase inlining = new InliningPhase(context.getRuntime(), hints, replacements, context.getAssumptions(), cache, plan, optimisticOpts);
                    inlining.setMaxMethodsPerInlining(simple ? 1 : Integer.MAX_VALUE);
                    inlining.apply(graph);
                    progress |= inlining.getInliningCount() > 0;

                    new DeadCodeEliminationPhase().apply(graph);

                    if (GraalOptions.ConditionalElimination && GraalOptions.OptCanonicalizer) {
                        new CanonicalizerPhase().apply(graph, context);
                        new IterativeConditionalEliminationPhase(context.getRuntime(), context.getAssumptions()).apply(graph);
                    }

                    return progress;
                }
            });
        }
    }
}
