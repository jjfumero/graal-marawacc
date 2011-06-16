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
package com.oracle.max.graal.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class Graph {

    private final ArrayList<Node> nodes;
    private final StartNode start;
    int nextId;
    int deletedNodeCount;

    static int nextGraphId = 0;
    int id = nextGraphId++;

    @Override
    public String toString() {
        return "Graph " + id;
    }

    public Graph() {
        nodes = new ArrayList<Node>();
        start = new StartNode(this);
    }

    public int getDeletedNodeCount() {
        return deletedNodeCount;
    }

    public int getNodeCount() {
        return nodes.size() - getDeletedNodeCount();
    }

    public List<Node> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public class TypedNodeIterator<T> implements Iterator<T> {
        private final Class<T> type;
        private int index;

        public TypedNodeIterator(Class<T> type) {
            this.type = type;
            this.index = -1;
            forward();
        }

        private void forward() {
            if (index < nodes.size()) {
                do {
                    index++;
                } while (index < nodes.size() && !type.isInstance(nodes.get(index)));
                if (index >= nodes.size()) {
                    index = Integer.MAX_VALUE;
                }
            }
        }

        @Override
        public boolean hasNext() {
            return index < nodes.size();
        }

        @Override
        @SuppressWarnings("unchecked")
        public T next() {
            try {
                return (T) nodes.get(index);
            } finally {
                forward();
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    public <T extends Node> Iterable<T> getNodes(final Class<T> type) {
        return new Iterable<T>() {
            @Override
            public Iterator<T> iterator() {
                return new TypedNodeIterator<T>(type);
            }
        };
    }

    int register(Node node) {
        int id = nextId++;
        nodes.add(id, node);
        return id;
    }

    void unregister(Node node) {
        nodes.set(node.id(), Node.Null);
        deletedNodeCount++;
    }

    public StartNode start() {
        return start;
    }

    public NodeBitMap createNodeBitMap() {
        return new NodeBitMap(this);
    }

    public <T> NodeMap<T> createNodeMap() {
        return new NodeMap<T>(this);
    }

    public NodeFlood createNodeFlood() {
        return new NodeFlood(this);
    }

    public NodeWorkList createNodeWorkList() {
        return new NodeWorkList(this);
    }

    public NodeWorkList createNodeWorkList(boolean fill, int iterationLimitPerNode) {
        return new NodeWorkList(this, fill, iterationLimitPerNode);
    }

    public Map<Node, Node> addDuplicate(Collection<Node> nodes, Map<Node, Node> replacements) {
        Map<Node, Node> newNodes = new HashMap<Node, Node>();
        // create node duplicates
        for (Node node : nodes) {
            if (node != null && !replacements.containsKey(node)) {
                assert node.graph != this;
                assert !node.isDeleted() : "trying to duplicate deleted node";
                Node newNode = node.copy(this);
                assert newNode.getClass() == node.getClass();
                newNodes.put(node, newNode);
            }
        }
        // re-wire inputs
        for (Entry<Node, Node> entry : newNodes.entrySet()) {
            Node oldNode = entry.getKey();
            Node node = entry.getValue();
            for (int i = 0; i < oldNode.inputs().size(); i++) {
                Node input = oldNode.inputs().get(i);
                Node target = replacements.get(input);
                if (target == null) {
                    target = newNodes.get(input);
                }
                node.inputs().setOrExpand(i, target);
            }
        }
        for (Entry<Node, Node> entry : replacements.entrySet()) {
            Node oldNode = entry.getKey();
            Node node = entry.getValue();
            for (int i = 0; i < oldNode.inputs().size(); i++) {
                Node input = oldNode.inputs().get(i);
                if (newNodes.containsKey(input)) {
                    node.inputs().setOrExpand(i, newNodes.get(input));
                }
            }
        }

        // re-wire successors
        for (Entry<Node, Node> entry : newNodes.entrySet()) {
            Node oldNode = entry.getKey();
            Node node = entry.getValue();
            for (int i = 0; i < oldNode.successors().size(); i++) {
                Node succ = oldNode.successors().get(i);
                Node target = replacements.get(succ);
                if (target == null) {
                    target = newNodes.get(succ);
                }
                node.successors().setOrExpand(i, target);
            }
        }
        for (Entry<Node, Node> entry : replacements.entrySet()) {
            Node oldNode = entry.getKey();
            Node node = entry.getValue();
            for (int i = 0; i < oldNode.successors().size(); i++) {
                Node succ = oldNode.successors().get(i);
                if (newNodes.containsKey(succ)) {
                    node.successors().setOrExpand(i, newNodes.get(succ));
                }
            }
        }
        return newNodes;
    }
}
