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
package com.oracle.graal.phases.common;

import com.oracle.graal.graph.iterators.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.util.*;

/**
 * Adds safepoints to loops.
 */
public class LoopSafepointInsertionPhase extends Phase {

    @Override
    protected void run(StructuredGraph graph) {
        nextLoop:
        for (LoopEndNode loopEnd : graph.getNodes(LoopEndNode.class)) {
            if (!loopEnd.canSafepoint()) {
                continue;
            }
            if (GraalOptions.OptSafepointElimination) {
                // We 'eliminate' safepoints by simply never placing them into loops that have at least one call
                NodeIterable<FixedNode> it = NodeIterators.dominators(loopEnd).until(loopEnd.loopBegin());
                for (FixedNode n : it) {
                    if (n instanceof Invoke) {
                        continue nextLoop;
                    }
                }
            }
            SafepointNode safepoint = graph.add(new SafepointNode());
            graph.addBeforeFixed(loopEnd, safepoint);
        }
    }
}
