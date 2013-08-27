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

import static com.oracle.graal.phases.GraalOptions.*;

import java.util.concurrent.*;

import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.phases.common.util.*;
import com.oracle.graal.phases.graph.*;
import com.oracle.graal.phases.schedule.*;
import com.oracle.graal.phases.tiers.*;

public abstract class EffectsPhase<PhaseContextT extends PhaseContext> extends BasePhase<PhaseContextT> {

    public abstract static class Closure<T> extends ReentrantBlockIterator.BlockIteratorClosure<T> {

        public abstract boolean hasChanged();

        public abstract void applyEffects();
    }

    private final int maxIterations;

    public EffectsPhase(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    @Override
    protected void run(StructuredGraph graph, PhaseContextT context) {
        runAnalysis(graph, context);
    }

    public boolean runAnalysis(final StructuredGraph graph, final PhaseContextT context) {
        boolean changed = false;
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            boolean currentChanged = Debug.scope("iteration " + iteration, new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    SchedulePhase schedule = new SchedulePhase();
                    schedule.apply(graph, false);
                    Closure<?> closure = createEffectsClosure(context, schedule);
                    ReentrantBlockIterator.apply(closure, schedule.getCFG().getStartBlock());

                    if (!closure.hasChanged()) {
                        return false;
                    }

                    // apply the effects collected during this iteration
                    HashSetNodeChangeListener listener = new HashSetNodeChangeListener();
                    graph.trackInputChange(listener);
                    graph.trackUsagesDroppedZero(listener);
                    closure.applyEffects();
                    graph.stopTrackingInputChange();
                    graph.stopTrackingUsagesDroppedZero();

                    Debug.dump(graph, "after " + getName() + " iteration");

                    new DeadCodeEliminationPhase().apply(graph);

                    for (Node node : graph.getNodes()) {
                        if (node instanceof Simplifiable) {
                            listener.getChangedNodes().add(node);
                        }
                    }
                    new CanonicalizerPhase.Instance(context.getRuntime(), context.getAssumptions(), !AOTCompilation.getValue(), listener.getChangedNodes(), null).apply(graph);

                    return true;
                }
            });
            if (!currentChanged) {
                break;
            }
            changed |= currentChanged;
        }
        return changed;
    }

    protected abstract Closure<?> createEffectsClosure(PhaseContextT context, SchedulePhase schedule);
}
