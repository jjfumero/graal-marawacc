/*
 * Copyright (c) 2011, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.graal.compiler.phases;

import com.oracle.max.graal.compiler.*;
import com.oracle.max.graal.graph.Graph;

public abstract class Phase {

    private final String name;
    private static final ThreadLocal<Phase> currentPhase = new ThreadLocal<Phase>();

    public Phase() {
        this.name = this.getClass().getSimpleName();
    }

    public Phase(String name) {
        this.name = name;
    }

    public final void apply(Graph graph) {
        assert graph != null;

        int startDeletedNodeCount = graph.getDeletedNodeCount();
        int startNodeCount = graph.getNodeCount();
        Phase oldCurrentPhase = null;
        if (GraalOptions.Time) {
            oldCurrentPhase = currentPhase.get();
            currentPhase.set(this);
            if (oldCurrentPhase != null) {
                GraalTimers.get(oldCurrentPhase.getName()).stop();
            }
            GraalTimers.get(getName()).start();
        }
        run(graph);
        if (GraalOptions.Time) {
            GraalTimers.get(getName()).stop();
            if (oldCurrentPhase != null) {
                GraalTimers.get(oldCurrentPhase.getName()).start();
            }
            currentPhase.set(oldCurrentPhase);
        }
        if (GraalOptions.Meter) {
            int deletedNodeCount = graph.getDeletedNodeCount() - startDeletedNodeCount;
            int createdNodeCount = graph.getNodeCount() - startNodeCount + deletedNodeCount;
            GraalMetrics.get(getName().concat(".executed")).increment();
            GraalMetrics.get(getName().concat(".deletedNodes")).increment(deletedNodeCount);
            GraalMetrics.get(getName().concat(".createdNodes")).increment(createdNodeCount);
        }

        // (Item|Graph|Phase|Value)
    }

    public final String getName() {
        return name;
    }

    protected abstract void run(Graph graph);
}
