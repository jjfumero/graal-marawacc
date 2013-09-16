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

import java.util.*;

import com.oracle.graal.graph.GraphEvent.NodeEvent;
import com.oracle.graal.graph.Node.ValueNumberable;
import com.oracle.graal.graph.iterators.*;

/**
 * This class is a graph container, it contains the set of nodes that belong to this graph.
 */
public class Graph {

    public final String name;

    private static final boolean TIME_TRAVEL = false;

    private final ArrayList<Node> nodes;

    /**
     * Records the modification count for nodes. This is only used in assertions.
     */
    private int[] nodeModCounts;

    /**
     * Records the modification count for nodes' usage lists. This is only used in assertions.
     */
    private int[] nodeUsageModCounts;

    // these two arrays contain one entry for each NodeClass, indexed by NodeClass.iterableId.
    // they contain the first and last pointer to a linked list of all nodes with this type.
    private final ArrayList<Node> nodeCacheFirst;
    private final ArrayList<Node> nodeCacheLast;
    private int deletedNodeCount;
    private GraphEventLog eventLog;

    NodeChangedListener inputChanged;
    NodeChangedListener usagesDroppedZero;
    private final HashMap<CacheEntry, Node> cachedNodes = new HashMap<>();

    private static final class CacheEntry {

        private final Node node;

        public CacheEntry(Node node) {
            assert node.getNodeClass().valueNumberable();
            assert node.getNodeClass().isLeafNode();
            this.node = node;
        }

        @Override
        public int hashCode() {
            return node.getNodeClass().valueNumber(node);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj instanceof CacheEntry) {
                CacheEntry other = (CacheEntry) obj;
                NodeClass nodeClass = node.getNodeClass();
                if (other.node.getClass() == node.getClass()) {
                    return nodeClass.valueEqual(node, other.node);
                }
            }
            return false;
        }
    }

    /**
     * Creates an empty Graph with no name.
     */
    public Graph() {
        this(null);
    }

    static final boolean MODIFICATION_COUNTS_ENABLED = assertionsEnabled();

    /**
     * Determines if assertions are enabled for the {@link Graph} class.
     */
    @SuppressWarnings("all")
    private static boolean assertionsEnabled() {
        boolean enabled = false;
        assert enabled = true;
        return enabled;
    }

    /**
     * Creates an empty Graph with a given name.
     * 
     * @param name the name of the graph, used for debugging purposes
     */
    public Graph(String name) {
        nodes = new ArrayList<>(32);
        nodeCacheFirst = new ArrayList<>(NodeClass.cacheSize());
        nodeCacheLast = new ArrayList<>(NodeClass.cacheSize());
        this.name = name;
        if (MODIFICATION_COUNTS_ENABLED) {
            nodeModCounts = new int[nodes.size()];
            nodeUsageModCounts = new int[nodes.size()];
        }
    }

    int extractOriginalNodeId(Node node) {
        int id = node.id;
        if (id <= Node.DELETED_ID_START) {
            id = Node.DELETED_ID_START - id;
        }
        return id;
    }

    int modCount(Node node) {
        int id = extractOriginalNodeId(node);
        if (id >= 0 && id < nodeModCounts.length) {
            return nodeModCounts[id];
        }
        return 0;
    }

    void incModCount(Node node) {
        int id = extractOriginalNodeId(node);
        if (id >= 0) {
            if (id >= nodeModCounts.length) {
                nodeModCounts = Arrays.copyOf(nodeModCounts, id + 30);
            }
            nodeModCounts[id]++;
        } else {
            assert false;
        }
    }

    int usageModCount(Node node) {
        int id = extractOriginalNodeId(node);
        if (id >= 0 && id < nodeUsageModCounts.length) {
            return nodeUsageModCounts[id];
        }
        return 0;
    }

    void incUsageModCount(Node node) {
        int id = extractOriginalNodeId(node);
        if (id >= 0) {
            if (id >= nodeUsageModCounts.length) {
                nodeUsageModCounts = Arrays.copyOf(nodeUsageModCounts, id + 30);
            }
            nodeUsageModCounts[id]++;
        } else {
            assert false;
        }
    }

    /**
     * Creates a copy of this graph.
     */
    public Graph copy() {
        return copy(name);
    }

    /**
     * Creates a copy of this graph.
     * 
     * @param newName the name of the copy, used for debugging purposes (can be null)
     */
    public Graph copy(String newName) {
        Graph copy = new Graph(newName);
        copy.addDuplicates(getNodes(), this, this.getNodeCount(), (Map<Node, Node>) null);
        return copy;
    }

    @Override
    public String toString() {
        return name == null ? super.toString() : "Graph " + name;
    }

    /**
     * Gets the number of live nodes in this graph. That is the number of nodes which have been
     * added to the graph minus the number of deleted nodes.
     * 
     * @return the number of live nodes in this graph
     */
    public int getNodeCount() {
        return nodes.size() - getDeletedNodeCount();
    }

    /**
     * Gets the number of node which have been deleted from this graph.
     * 
     * @return the number of node which have been deleted from this graph
     */
    public int getDeletedNodeCount() {
        return deletedNodeCount;
    }

    /**
     * Adds a new node to the graph.
     * 
     * @param node the node to be added
     * @return the node which was added to the graph
     */
    public <T extends Node> T add(T node) {
        if (node.getNodeClass().valueNumberable()) {
            throw new IllegalStateException("Using add for value numberable node. Consider using either unique or addWithoutUnique.");
        }
        return addHelper(node);
    }

    public <T extends Node> T addWithoutUnique(T node) {
        return addHelper(node);
    }

    public <T extends Node> T addOrUnique(T node) {
        if (node.getNodeClass().valueNumberable()) {
            return uniqueHelper(node);
        }
        return add(node);
    }

    private <T extends Node> T addHelper(T node) {
        node.initialize(this);
        return node;
    }

    public interface NodeChangedListener {

        void nodeChanged(Node node);
    }

    public void trackInputChange(NodeChangedListener inputChangedListener) {
        assert this.inputChanged == null;
        this.inputChanged = inputChangedListener;
    }

    public void stopTrackingInputChange() {
        assert inputChanged != null;
        inputChanged = null;
    }

    public void trackUsagesDroppedZero(NodeChangedListener usagesDroppedZeroListener) {
        assert this.usagesDroppedZero == null;
        this.usagesDroppedZero = usagesDroppedZeroListener;
    }

    public void stopTrackingUsagesDroppedZero() {
        assert usagesDroppedZero != null;
        usagesDroppedZero = null;
    }

    /**
     * Adds a new node to the graph, if a <i>similar</i> node already exists in the graph, the
     * provided node will not be added to the graph but the <i>similar</i> node will be returned
     * instead.
     * 
     * @param node
     * @return the node which was added to the graph or a <i>similar</i> which was already in the
     *         graph.
     */
    public <T extends Node & ValueNumberable> T unique(T node) {
        assert checkValueNumberable(node);
        return uniqueHelper(node);
    }

    @SuppressWarnings("unchecked")
    <T extends Node> T uniqueHelper(T node) {
        assert node.getNodeClass().valueNumberable();
        Node other = this.findDuplicate(node);
        if (other != null) {
            return (T) other;
        } else {
            Node result = addHelper(node);
            if (node.getNodeClass().isLeafNode()) {
                putNodeIntoCache(result);
            }
            return (T) result;
        }
    }

    void putNodeIntoCache(Node node) {
        assert node.graph() == this || node.graph() == null;
        assert node.getNodeClass().valueNumberable();
        assert node.getNodeClass().isLeafNode() : node.getClass();
        cachedNodes.put(new CacheEntry(node), node);
    }

    Node findNodeInCache(Node node) {
        CacheEntry key = new CacheEntry(node);
        Node result = cachedNodes.get(key);
        if (result != null && result.isDeleted()) {
            cachedNodes.remove(key);
            return null;
        }
        return result;
    }

    public Node findDuplicate(Node node) {
        NodeClass nodeClass = node.getNodeClass();
        assert nodeClass.valueNumberable();
        if (nodeClass.isLeafNode()) {
            Node cachedNode = findNodeInCache(node);
            if (cachedNode != null) {
                return cachedNode;
            } else {
                return null;
            }
        } else {

            int minCount = Integer.MAX_VALUE;
            Node minCountNode = null;
            for (Node input : node.inputs()) {
                if (input != null) {
                    int estimate = input.getUsageCountUpperBound();
                    if (estimate == 0) {
                        return null;
                    } else if (estimate < minCount) {
                        minCount = estimate;
                        minCountNode = input;
                    }
                }
            }
            if (minCountNode != null) {
                for (Node usage : minCountNode.usages()) {
                    if (usage != node && nodeClass == usage.getNodeClass() && nodeClass.valueEqual(node, usage) && nodeClass.edgesEqual(node, usage)) {
                        return usage;
                    }
                }
                return null;
            }
            return null;
        }
    }

    private static boolean checkValueNumberable(Node node) {
        if (!node.getNodeClass().valueNumberable()) {
            throw new VerificationError("node is not valueNumberable").addContext(node);
        }
        return true;
    }

    /**
     * Gets a mark that can be used with {@link #getNewNodes(int)}.
     */
    public int getMark() {
        return nodeIdCount();
    }

    private class NodeIterator implements Iterator<Node> {

        private int index;

        public NodeIterator() {
            this(0);
        }

        public NodeIterator(int index) {
            this.index = index - 1;
            forward();
        }

        private void forward() {
            if (index < nodes.size()) {
                do {
                    index++;
                } while (index < nodes.size() && nodes.get(index) == null);
            }
        }

        @Override
        public boolean hasNext() {
            checkForDeletedNode();
            return index < nodes.size();
        }

        private void checkForDeletedNode() {
            if (index < nodes.size()) {
                while (index < nodes.size() && nodes.get(index) == null) {
                    index++;
                }
            }
        }

        @Override
        public Node next() {
            try {
                return nodes.get(index);
            } finally {
                forward();
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Returns an {@link Iterable} providing all nodes added since the last {@link Graph#getMark()
     * mark}.
     */
    public NodeIterable<Node> getNewNodes(int mark) {
        final int index = mark;
        return new AbstractNodeIterable<Node>() {

            @Override
            public Iterator<Node> iterator() {
                return new NodeIterator(index);
            }
        };
    }

    /**
     * Returns an {@link Iterable} providing all the live nodes.
     * 
     * @return an {@link Iterable} providing all the live nodes.
     */
    public NodeIterable<Node> getNodes() {
        return new AbstractNodeIterable<Node>() {

            @Override
            public Iterator<Node> iterator() {
                return new NodeIterator();
            }

            @Override
            public int count() {
                return getNodeCount();
            }
        };
    }

    private static final Node PLACE_HOLDER = new Node() {
    };

    private class TypedNodeIterator<T extends IterableNodeType> implements Iterator<T> {

        private final int[] ids;
        private final Node[] current;

        private int currentIdIndex;
        private boolean needsForward;

        public TypedNodeIterator(NodeClass clazz) {
            ids = clazz.iterableIds();
            currentIdIndex = 0;
            current = new Node[ids.length];
            Arrays.fill(current, PLACE_HOLDER);
            needsForward = true;
        }

        private Node findNext() {
            if (needsForward) {
                forward();
            } else {
                Node c = current();
                Node afterDeleted = skipDeleted(c);
                if (afterDeleted == null) {
                    needsForward = true;
                } else if (c != afterDeleted) {
                    setCurrent(afterDeleted);
                }
            }
            if (needsForward) {
                return null;
            }
            return current();
        }

        private Node skipDeleted(Node node) {
            Node n = node;
            while (n != null && n.isDeleted()) {
                n = n.typeCacheNext;
            }
            return n;
        }

        private void forward() {
            needsForward = false;
            int startIdx = currentIdIndex;
            while (true) {
                Node next;
                if (current() == PLACE_HOLDER) {
                    next = getStartNode(ids[currentIdIndex]);
                } else {
                    next = current().typeCacheNext;
                }
                next = skipDeleted(next);
                if (next == null) {
                    currentIdIndex++;
                    if (currentIdIndex >= ids.length) {
                        currentIdIndex = 0;
                    }
                    if (currentIdIndex == startIdx) {
                        needsForward = true;
                        return;
                    }
                } else {
                    setCurrent(next);
                    break;
                }
            }
        }

        private Node current() {
            return current[currentIdIndex];
        }

        private void setCurrent(Node n) {
            current[currentIdIndex] = n;
        }

        @Override
        public boolean hasNext() {
            return findNext() != null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T next() {
            Node result = findNext();
            if (result == null) {
                throw new NoSuchElementException();
            }
            needsForward = true;
            return (T) result;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Returns an {@link Iterable} providing all the live nodes whose type is compatible with
     * {@code type}.
     * 
     * @param type the type of node to return
     * @return an {@link Iterable} providing all the matching nodes
     */
    public <T extends Node & IterableNodeType> NodeIterable<T> getNodes(final Class<T> type) {
        final NodeClass nodeClass = NodeClass.get(type);
        return new AbstractNodeIterable<T>() {

            @Override
            public Iterator<T> iterator() {
                return new TypedNodeIterator<>(nodeClass);
            }
        };
    }

    /**
     * Returns whether the graph contains at least one node of the given type.
     * 
     * @param type the type of node that is checked for occurrence
     * @return whether there is at least one such node
     */
    public <T extends Node & IterableNodeType> boolean hasNode(final Class<T> type) {
        return getNodes(type).iterator().hasNext();
    }

    private Node getStartNode(int iterableId) {
        Node start = nodeCacheFirst.size() <= iterableId ? null : nodeCacheFirst.get(iterableId);
        return start;
    }

    public NodeBitMap createNodeBitMap() {
        return createNodeBitMap(false);
    }

    public NodeBitMap createNodeBitMap(boolean autoGrow) {
        return new NodeBitMap(this, autoGrow);
    }

    public <T> NodeMap<T> createNodeMap() {
        return createNodeMap(false);
    }

    public <T> NodeMap<T> createNodeMap(boolean autoGrow) {
        return new NodeMap<>(this, autoGrow);
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

    void register(Node node) {
        assert node.id() == Node.INITIAL_ID;
        int id = nodes.size();
        nodes.add(id, node);

        int nodeClassId = node.getNodeClass().iterableId();
        if (nodeClassId != NodeClass.NOT_ITERABLE) {
            while (nodeCacheFirst.size() <= nodeClassId) {
                nodeCacheFirst.add(null);
                nodeCacheLast.add(null);
            }
            Node prev = nodeCacheLast.get(nodeClassId);
            if (prev != null) {
                prev.typeCacheNext = node;
            } else {
                nodeCacheFirst.set(nodeClassId, node);
            }
            nodeCacheLast.set(nodeClassId, node);
        }

        node.id = id;
        logNodeAdded(node);
    }

    void logNodeAdded(Node node) {
        if (TIME_TRAVEL) {
            log(new GraphEvent.NodeEvent(node, GraphEvent.NodeEvent.Type.ADDED));
        }
    }

    void logNodeDeleted(Node node) {
        if (TIME_TRAVEL) {
            log(new GraphEvent.NodeEvent(node, GraphEvent.NodeEvent.Type.DELETED));
        }
    }

    private void log(NodeEvent nodeEvent) {
        if (eventLog == null) {
            eventLog = new GraphEventLog();
        }
        eventLog.add(nodeEvent);
    }

    public GraphEventLog getEventLog() {
        return eventLog;
    }

    void unregister(Node node) {
        assert !node.isDeleted() : "cannot delete a node twice! node=" + node;
        logNodeDeleted(node);
        nodes.set(node.id(), null);
        deletedNodeCount++;

        // nodes aren't removed from the type cache here - they will be removed during iteration
    }

    public boolean verify() {
        for (Node node : getNodes()) {
            try {
                try {
                    assert node.verify();
                } catch (AssertionError t) {
                    throw new GraalInternalError(t);
                } catch (RuntimeException t) {
                    throw new GraalInternalError(t);
                }
            } catch (GraalInternalError e) {
                throw e.addContext(node).addContext(this);
            }
        }
        return true;
    }

    Node getNode(int i) {
        return nodes.get(i);
    }

    /**
     * Returns the number of node ids generated so far.
     * 
     * @return the number of node ids generated so far
     */
    int nodeIdCount() {
        return nodes.size();
    }

    /**
     * Adds duplicates of the nodes in {@code nodes} to this graph. This will recreate any edges
     * between the duplicate nodes. The {@code replacement} map can be used to replace a node from
     * the source graph by a given node (which must already be in this graph). Edges between
     * duplicate and replacement nodes will also be recreated so care should be taken regarding the
     * matching of node types in the replacement map.
     * 
     * @param newNodes the nodes to be duplicated
     * @param replacementsMap the replacement map (can be null if no replacement is to be performed)
     * @return a map which associates the original nodes from {@code nodes} to their duplicates
     */
    public Map<Node, Node> addDuplicates(Iterable<Node> newNodes, final Graph oldGraph, int estimatedNodeCount, Map<Node, Node> replacementsMap) {
        DuplicationReplacement replacements;
        if (replacementsMap == null) {
            replacements = null;
        } else {
            replacements = new MapReplacement(replacementsMap);
        }
        return addDuplicates(newNodes, oldGraph, estimatedNodeCount, replacements);
    }

    public interface DuplicationReplacement {

        Node replacement(Node original);
    }

    private static final class MapReplacement implements DuplicationReplacement {

        private final Map<Node, Node> map;

        public MapReplacement(Map<Node, Node> map) {
            this.map = map;
        }

        @Override
        public Node replacement(Node original) {
            Node replacement = map.get(original);
            return replacement != null ? replacement : original;
        }

    }

    @SuppressWarnings("all")
    public Map<Node, Node> addDuplicates(Iterable<Node> newNodes, final Graph oldGraph, int estimatedNodeCount, DuplicationReplacement replacements) {
        return NodeClass.addGraphDuplicate(this, oldGraph, estimatedNodeCount, newNodes, replacements);
    }
}
