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
package com.oracle.graal.graph;

import java.util.LinkedList;
import java.util.List;


public class NodeIterator {
    public static NodeBitMap iterate(EdgeType e, Node start, NodeBitMap constraint, NodeVisitor visitor) {
        LinkedList<Node> nodes = new LinkedList<Node>();
        NodeBitMap nodeBitMap = start.graph.createNodeBitMap();

        add(nodes, nodeBitMap, start, constraint, null);
        while (nodes.size() > 0) {
            Node n = nodes.remove();
            if (visitor != null) {
                boolean followEdges = visitor.visit(n);
                if (!followEdges) {
                    continue;
                }
            }
            switch(e) {
                case INPUTS:
                    for (Node inputs : n.inputs()) {
                        add(nodes, nodeBitMap, inputs, constraint, n.usages());
                    }
                    break;
                case USAGES:
                    for (Node usage : n.usages()) {
                        add(nodes, nodeBitMap, usage, constraint, n.inputs());
                    }
                    break;
                case PREDECESSORS:
                    for (Node preds : n.predecessors()) {
                        add(nodes, nodeBitMap, preds, constraint, n.successors());
                    }
                    break;
                case SUCCESSORS:
                    for (Node succ : n.successors()) {
                        add(nodes, nodeBitMap, succ, constraint, n.predecessors());
                    }
                    break;
                default:
                    assert false : "unknown edge type";
            }
        }

        return nodeBitMap;
    }

    private static void add(List<Node> nodes, NodeBitMap nodeBitMap, Node node, NodeBitMap constraint, List<Node> others) {
        if (node != null && !nodeBitMap.isMarked(node) && (constraint == null || constraint.isMarked(node))) {
            nodes.add(node);
            nodeBitMap.mark(node);
        }
    }
}
