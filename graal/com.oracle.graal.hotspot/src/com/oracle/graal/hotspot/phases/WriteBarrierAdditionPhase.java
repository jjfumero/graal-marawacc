/*
 * Copyright (c) 2013, 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.phases;

import static com.oracle.graal.hotspot.replacements.HotSpotReplacementsUtil.*;

import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.HeapAccess.BarrierType;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.phases.*;

public class WriteBarrierAdditionPhase extends Phase {

    public WriteBarrierAdditionPhase() {
    }

    @Override
    protected void run(StructuredGraph graph) {
        for (ReadNode node : graph.getNodes(ReadNode.class)) {
            addReadNodeBarriers(node, graph);
        }
        for (WriteNode node : graph.getNodes(WriteNode.class)) {
            addWriteNodeBarriers(node, graph);
        }
        for (CompareAndSwapNode node : graph.getNodes(CompareAndSwapNode.class)) {
            addCASBarriers(node, graph);
        }
        for (ArrayRangeWriteNode node : graph.getNodes(ArrayRangeWriteNode.class)) {
            if (node.isObjectArray()) {
                addArrayRangeBarriers(node, graph);
            }
        }
    }

    private static void addReadNodeBarriers(ReadNode node, StructuredGraph graph) {
        if (node.getBarrierType() == BarrierType.PRECISE) {
            assert useG1GC();
            G1PreWriteBarrier barrier = graph.add(new G1PreWriteBarrier(node.object(), node, node.location(), false, node.getNullCheck()));
            barrier.setDeoptimizationState(node.getDeoptimizationState());
            node.setNullCheck(false);
            graph.addAfterFixed(node, barrier);
        } else {
            assert node.getBarrierType() == BarrierType.NONE : "Non precise write barrier has been attached to read node.";
        }
    }

    private static void addWriteNodeBarriers(WriteNode node, StructuredGraph graph) {
        BarrierType barrierType = node.getBarrierType();
        if (barrierType == BarrierType.PRECISE) {
            if (useG1GC()) {
                if (node.isInitialized()) {
                    G1PreWriteBarrier preBarrier = graph.add(new G1PreWriteBarrier(node.object(), null, node.location(), true, node.getNullCheck()));
                    preBarrier.setDeoptimizationState(node.getDeoptimizationState());
                    node.setNullCheck(false);
                    graph.addBeforeFixed(node, preBarrier);
                }
                graph.addAfterFixed(node, graph.add(new G1PostWriteBarrier(node.object(), node.value(), node.location(), true)));
            } else {
                graph.addAfterFixed(node, graph.add(new SerialWriteBarrier(node.object(), node.location(), true)));
            }
        } else if (barrierType == BarrierType.IMPRECISE) {
            if (useG1GC()) {
                G1PreWriteBarrier preBarrier = graph.add(new G1PreWriteBarrier(node.object(), null, node.location(), true, node.getNullCheck()));
                preBarrier.setDeoptimizationState(node.getDeoptimizationState());
                node.setNullCheck(false);
                graph.addBeforeFixed(node, preBarrier);
                graph.addAfterFixed(node, graph.add(new G1PostWriteBarrier(node.object(), node.value(), node.location(), false)));
            } else {
                graph.addAfterFixed(node, graph.add(new SerialWriteBarrier(node.object(), node.location(), false)));
            }
        } else {
            assert barrierType == BarrierType.NONE;
        }

    }

    private static void addCASBarriers(CompareAndSwapNode node, StructuredGraph graph) {
        BarrierType barrierType = node.getBarrierType();
        if (barrierType == BarrierType.PRECISE) {
            if (useG1GC()) {
                graph.addBeforeFixed(node, graph.add(new G1PreWriteBarrier(node.object(), node.expected(), node.getLocation(), false, false)));
                graph.addAfterFixed(node, graph.add(new G1PostWriteBarrier(node.object(), node.newValue(), node.getLocation(), true)));
            } else {
                graph.addAfterFixed(node, graph.add(new SerialWriteBarrier(node.object(), node.getLocation(), true)));
            }
        } else if (barrierType == BarrierType.IMPRECISE) {
            if (useG1GC()) {
                graph.addBeforeFixed(node, graph.add(new G1PreWriteBarrier(node.object(), node.expected(), node.getLocation(), false, false)));
                graph.addAfterFixed(node, graph.add(new G1PostWriteBarrier(node.object(), node.newValue(), node.getLocation(), false)));
            } else {
                graph.addAfterFixed(node, graph.add(new SerialWriteBarrier(node.object(), node.getLocation(), false)));
            }
        } else {
            assert barrierType == BarrierType.NONE;
        }
    }

    private static void addArrayRangeBarriers(ArrayRangeWriteNode node, StructuredGraph graph) {
        if (useG1GC()) {
            if (node.isInitialized()) {
                G1ArrayRangePreWriteBarrier g1ArrayRangePreWriteBarrier = graph.add(new G1ArrayRangePreWriteBarrier(node.getArray(), node.getIndex(), node.getLength()));
                graph.addBeforeFixed(node, g1ArrayRangePreWriteBarrier);
            }
            G1ArrayRangePostWriteBarrier g1ArrayRangePostWriteBarrier = graph.add(new G1ArrayRangePostWriteBarrier(node.getArray(), node.getIndex(), node.getLength()));
            graph.addAfterFixed(node, g1ArrayRangePostWriteBarrier);
        } else {
            SerialArrayRangeWriteBarrier serialArrayRangeWriteBarrier = graph.add(new SerialArrayRangeWriteBarrier(node.getArray(), node.getIndex(), node.getLength()));
            graph.addAfterFixed(node, serialArrayRangeWriteBarrier);
        }
    }

}
