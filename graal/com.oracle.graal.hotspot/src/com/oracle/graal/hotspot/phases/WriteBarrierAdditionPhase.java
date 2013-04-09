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

import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.phases.*;

public class WriteBarrierAdditionPhase extends Phase {

    public WriteBarrierAdditionPhase() {
    }

    @Override
    protected void run(StructuredGraph graph) {
        for (WriteNode node : graph.getNodes(WriteNode.class)) {
            addWriteNodeBarriers(node, graph);
        }
        for (CompareAndSwapNode node : graph.getNodes(CompareAndSwapNode.class)) {
            addCASBarriers(node, graph);
        }
        for (GenericArrayRangeWriteBarrier node : graph.getNodes(GenericArrayRangeWriteBarrier.class)) {
            addArrayRangeBarriers(node, graph);
        }
    }

    private static void addWriteNodeBarriers(WriteNode node, StructuredGraph graph) {
        if (node.needsWriteBarrier()) {
            graph.addAfterFixed(node, graph.add(new SerialWriteBarrier(node.object(), node.location(), node.usePreciseWriteBarriers())));
        }

    }

    private static void addCASBarriers(CompareAndSwapNode node, StructuredGraph graph) {
        if (node.needsWriteBarrier()) {
            LocationNode location = IndexedLocationNode.create(LocationNode.ANY_LOCATION, node.expected().kind(), node.displacement(), node.offset(), graph, 1);
            graph.addAfterFixed(node, graph.add(new SerialWriteBarrier(node.object(), location, node.usePreciseWriteBarriers())));
        }
    }

    private static void addArrayRangeBarriers(GenericArrayRangeWriteBarrier node, StructuredGraph graph) {
        SerialArrayRangeWriteBarrier serialArrayRangeWriteBarrier = graph.add(new SerialArrayRangeWriteBarrier(node.getDstObject(), node.getDstPos(), node.getLength()));
        graph.replaceFixedWithFixed(node, serialArrayRangeWriteBarrier);
    }

}
