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
package com.oracle.graal.graph;

import static com.oracle.graal.graph.Edges.Type.Inputs;
import static com.oracle.graal.graph.Edges.Type.Successors;
import static com.oracle.graal.graph.Graph.MODIFICATION_COUNTS_ENABLED;
import static com.oracle.graal.graph.UnsafeAccess.UNSAFE;

import java.lang.annotation.ElementType;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Formattable;
import java.util.FormattableFlags;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import jdk.vm.ci.code.BytecodePosition;
import sun.misc.Unsafe;

import com.oracle.graal.compiler.common.CollectionsFactory;
import com.oracle.graal.compiler.common.Fields;
import com.oracle.graal.debug.Fingerprint;
import com.oracle.graal.graph.Graph.NodeEvent;
import com.oracle.graal.graph.Graph.NodeEventListener;
import com.oracle.graal.graph.Graph.Options;
import com.oracle.graal.graph.iterators.NodeIterable;
import com.oracle.graal.graph.iterators.NodePredicate;
import com.oracle.graal.graph.spi.Simplifiable;
import com.oracle.graal.graph.spi.SimplifierTool;
import com.oracle.graal.nodeinfo.InputType;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodeinfo.Verbosity;

/**
 * This class is the base class for all nodes. It represents a node that can be inserted in a
 * {@link Graph}.
 * <p>
 * Once a node has been added to a graph, it has a graph-unique {@link #id()}. Edges in the
 * subclasses are represented with annotated fields. There are two kind of edges : {@link Input} and
 * {@link Successor}. If a field, of a type compatible with {@link Node}, annotated with either
 * {@link Input} and {@link Successor} is not null, then there is an edge from this node to the node
 * this field points to.
 * <p>
 * Nodes which are be value numberable should implement the {@link ValueNumberable} interface.
 *
 * <h1>Replay Compilation</h1>
 *
 * To enable deterministic replay compilation, node sets and node maps should be instantiated with
 * the following methods:
 * <ul>
 * <li>{@link #newSet()}</li>
 * <li>{@link #newSet(Collection)}</li>
 * <li>{@link #newMap()}</li>
 * <li>{@link #newMap(int)}</li>
 * <li>{@link #newMap(Map)}</li>
 * <li>{@link #newIdentityMap()}</li>
 * <li>{@link #newIdentityMap(int)}</li>
 * <li>{@link #newIdentityMap(Map)}</li>
 * </ul>
 *
 * <h1>Assertions and Verification</h1>
 *
 * The Node class supplies the {@link #assertTrue(boolean, String, Object...)} and
 * {@link #assertFalse(boolean, String, Object...)} methods, which will check the supplied boolean
 * and throw a VerificationError if it has the wrong value. Both methods will always either throw an
 * exception or return true. They can thus be used within an assert statement, so that the check is
 * only performed if assertions are enabled.
 */
@NodeInfo
public abstract class Node implements Cloneable, Formattable {

    public static final NodeClass<?> TYPE = null;
    public static final boolean USE_UNSAFE_TO_CLONE = Boolean.parseBoolean(System.getProperty("graal.node.useUnsafeToClone", "true"));

    static final int DELETED_ID_START = -1000000000;
    static final int INITIAL_ID = -1;
    static final int ALIVE_ID_START = 0;

    // The use of fully qualified class names here and in the rest
    // of this file works around a problem javac has resolving symbols

    /**
     * Denotes a non-optional (non-null) node input. This should be applied to exactly the fields of
     * a node that are of type {@link Node} or {@link NodeInputList}. Nodes that update fields of
     * type {@link Node} outside of their constructor should call
     * {@link Node#updateUsages(Node, Node)} just prior to doing the update of the input.
     */
    @java.lang.annotation.Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.FIELD)
    public static @interface Input {
        InputType value() default InputType.Value;
    }

    /**
     * Denotes an optional (nullable) node input. This should be applied to exactly the fields of a
     * node that are of type {@link Node} or {@link NodeInputList}. Nodes that update fields of type
     * {@link Node} outside of their constructor should call {@link Node#updateUsages(Node, Node)}
     * just prior to doing the update of the input.
     */
    @java.lang.annotation.Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.FIELD)
    public static @interface OptionalInput {
        InputType value() default InputType.Value;
    }

    @java.lang.annotation.Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.FIELD)
    public static @interface Successor {
    }

    /**
     * Denotes that a parameter of an {@linkplain NodeIntrinsic intrinsic} method must be a compile
     * time constant at all call sites to the intrinsic method.
     */
    @java.lang.annotation.Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.PARAMETER)
    public static @interface ConstantNodeParameter {
    }

    /**
     * Denotes an injected parameter in a {@linkplain NodeIntrinsic node intrinsic} constructor. If
     * the constructor is called as part of node intrinsification, the node intrinsifier will inject
     * an argument for the annotated parameter. Injected parameters must precede all non-injected
     * parameters in a constructor.
     */
    @java.lang.annotation.Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.PARAMETER)
    public static @interface InjectedNodeParameter {
    }

    /**
     * Annotates a method that can be replaced by a compiler intrinsic. A (resolved) call to the
     * annotated method can be replaced with an instance of the node class denoted by
     * {@link #value()}. For this reason, the signature of the annotated method must match the
     * signature (excluding a prefix of {@linkplain InjectedNodeParameter injected} parameters) of a
     * factory method named {@code "create"} in the node class.
     */
    @java.lang.annotation.Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.METHOD)
    public static @interface NodeIntrinsic {

        /**
         * Gets the {@link Node} subclass instantiated when intrinsifying a call to the annotated
         * method. If not specified, then the class in which the annotated method is declared is
         * used (and is assumed to be a {@link Node} subclass).
         */
        Class<?> value() default NodeIntrinsic.class;

        /**
         * Determines if the stamp of the instantiated intrinsic node has its stamp set from the
         * return type of the annotated method.
         * <p>
         * When it is set to true, the stamp that is passed in to the constructor of ValueNode is
         * ignored and can therefore safely be {@code null}.
         */
        boolean setStampFromReturnType() default false;
    }

    /**
     * Marker for a node that can be replaced by another node via global value numbering. A
     * {@linkplain NodeClass#isLeafNode() leaf} node can be replaced by another node of the same
     * type that has exactly the same {@linkplain NodeClass#getData() data} values. A non-leaf node
     * can be replaced by another node of the same type that has exactly the same data values as
     * well as the same {@linkplain Node#inputs() inputs} and {@linkplain Node#successors()
     * successors}.
     */
    public interface ValueNumberable {
    }

    private Graph graph;
    int id;

    // this next pointer is used in Graph to implement fast iteration over NodeClass types, it
    // therefore points to the next Node of the same type.
    Node typeCacheNext;

    static final int INLINE_USAGE_COUNT = 2;
    private static final Node[] NO_NODES = {};

    /**
     * Head of usage list. The elements of the usage list in order are {@link #usage0},
     * {@link #usage1} and {@link #extraUsages}. The first null entry terminates the list.
     */
    Node usage0;
    Node usage1;
    Node[] extraUsages;
    int extraUsagesCount;

    private Node predecessor;
    private NodeClass<? extends Node> nodeClass;

    public static final int NODE_LIST = -2;
    public static final int NOT_ITERABLE = -1;

    public Node(NodeClass<? extends Node> c) {
        init(c);
    }

    final void init(NodeClass<? extends Node> c) {
        assert c.getJavaClass() == this.getClass();
        this.nodeClass = c;
        id = INITIAL_ID;
        extraUsages = NO_NODES;
    }

    int id() {
        return id;
    }

    /**
     * @see CollectionsFactory#newSet()
     */
    public static <E extends Node> Set<E> newSet() {
        return CollectionsFactory.newSet();
    }

    /**
     * @see #newSet()
     */
    public static <E extends Node> Set<E> newSet(Collection<? extends E> c) {
        return CollectionsFactory.newSet(c);
    }

    public static <K extends Node, V> Map<K, V> newMap() {
        // Node.equals() and Node.hashCode() are final and are implemented
        // purely in terms of identity so HashMap and IdentityHashMap with
        // Node's as keys will behave the same. We choose to use the latter
        // due to its lighter memory footprint.
        return newIdentityMap();
    }

    public static <K extends Node, V> Map<K, V> newMap(Map<K, V> m) {
        // Node.equals() and Node.hashCode() are final and are implemented
        // purely in terms of identity so HashMap and IdentityHashMap with
        // Node's as keys will behave the same. We choose to use the latter
        // due to its lighter memory footprint.
        return newIdentityMap(m);
    }

    public static <K extends Node, V> Map<K, V> newMap(int expectedMaxSize) {
        // Node.equals() and Node.hashCode() are final and are implemented
        // purely in terms of identity so HashMap and IdentityHashMap with
        // Node's as keys will behave the same. We choose to use the latter
        // due to its lighter memory footprint.
        return newIdentityMap(expectedMaxSize);
    }

    public static <K extends Node, V> Map<K, V> newIdentityMap() {
        return CollectionsFactory.newIdentityMap();
    }

    public static <K extends Node, V> Map<K, V> newIdentityMap(Map<K, V> m) {
        return CollectionsFactory.newIdentityMap(m);
    }

    public static <K extends Node, V> Map<K, V> newIdentityMap(int expectedMaxSize) {
        return CollectionsFactory.newIdentityMap(expectedMaxSize);
    }

    /**
     * Gets the graph context of this node.
     */
    public Graph graph() {
        return graph;
    }

    /**
     * Returns an {@link NodeClassIterable iterable} which can be used to traverse all non-null
     * input edges of this node.
     *
     * @return an {@link NodeClassIterable iterable} for all non-null input edges.
     */
    public NodeClassIterable inputs() {
        return nodeClass.getInputEdges().getIterable(this);
    }

    /**
     * Applies the given consumer to all inputs of this node.
     *
     * @param consumer the consumer to be applied to the inputs
     */
    public void acceptInputs(BiConsumer<Node, Node> consumer) {
        nodeClass.getInputEdges().accept(this, consumer);
    }

    /**
     * Returns an {@link NodeClassIterable iterable} which can be used to traverse all non-null
     * successor edges of this node.
     *
     * @return an {@link NodeClassIterable iterable} for all non-null successor edges.
     */
    public NodeClassIterable successors() {
        return nodeClass.getSuccessorEdges().getIterable(this);
    }

    /**
     * Applies the given consumer to all successors of this node.
     *
     * @param consumer the consumer to be applied to the inputs
     */
    public void acceptSuccessors(BiConsumer<Node, Node> consumer) {
        nodeClass.getSuccessorEdges().accept(this, consumer);
    }

    /**
     * Gets the maximum number of usages this node has had at any point in time.
     */
    public int getUsageCount() {
        if (usage0 == null) {
            return 0;
        }
        if (usage1 == null) {
            return 1;
        }
        return 2 + extraUsagesCount;
    }

    /**
     * Gets the list of nodes that use this node (i.e., as an input).
     */
    public final NodeIterable<Node> usages() {
        return new NodeUsageIterable(this);
    }

    /**
     * Checks whether this node has no usages.
     */
    public final boolean hasNoUsages() {
        return this.usage0 == null;
    }

    /**
     * Checks whether this node has usages.
     */
    public final boolean hasUsages() {
        return this.usage0 != null;
    }

    void reverseUsageOrder() {
        List<Node> snapshot = this.usages().snapshot();
        for (Node n : snapshot) {
            this.removeUsage(n);
        }
        Collections.reverse(snapshot);
        for (Node n : snapshot) {
            this.addUsage(n);
        }
    }

    /**
     * Adds a given node to this node's {@linkplain #usages() usages}.
     *
     * @param node the node to add
     */
    private void addUsage(Node node) {
        incUsageModCount();
        if (usage0 == null) {
            usage0 = node;
        } else if (usage1 == null) {
            usage1 = node;
        } else {
            int length = extraUsages.length;
            if (length == 0) {
                extraUsages = new Node[4];
            } else if (extraUsagesCount == length) {
                Node[] newExtraUsages = new Node[length * 2 + 1];
                System.arraycopy(extraUsages, 0, newExtraUsages, 0, length);
                extraUsages = newExtraUsages;
            }
            extraUsages[extraUsagesCount++] = node;
        }
    }

    private void movUsageFromEndTo(int destIndex) {
        int lastIndex = this.getUsageCount() - 1;
        if (destIndex == 0) {
            if (lastIndex == 0) {
                usage0 = null;
                return;
            } else if (lastIndex == 1) {
                usage0 = usage1;
                usage1 = null;
                return;
            } else {
                usage0 = extraUsages[lastIndex - INLINE_USAGE_COUNT];
            }
        } else if (destIndex == 1) {
            if (lastIndex == 1) {
                usage1 = null;
                return;
            }
            usage1 = extraUsages[lastIndex - INLINE_USAGE_COUNT];
        } else {
            Node n = extraUsages[lastIndex - INLINE_USAGE_COUNT];
            extraUsages[destIndex - INLINE_USAGE_COUNT] = n;
        }
        extraUsages[lastIndex - INLINE_USAGE_COUNT] = null;
        this.extraUsagesCount--;
    }

    /**
     * Removes a given node from this node's {@linkplain #usages() usages}.
     *
     * @param node the node to remove
     * @return whether or not {@code usage} was in the usage list
     */
    public boolean removeUsage(Node node) {
        assert node != null;
        // It is critical that this method maintains the invariant that
        // the usage list has no null element preceding a non-null element
        incUsageModCount();
        if (usage0 == node) {
            this.movUsageFromEndTo(0);
            return true;
        }
        if (usage1 == node) {
            this.movUsageFromEndTo(1);
            return true;
        }
        for (int i = this.extraUsagesCount - 1; i >= 0; i--) {
            if (extraUsages[i] == node) {
                this.movUsageFromEndTo(i + INLINE_USAGE_COUNT);
                return true;
            }
        }
        return false;
    }

    public final Node predecessor() {
        return predecessor;
    }

    public final int modCount() {
        if (MODIFICATION_COUNTS_ENABLED && graph != null) {
            return graph.modCount(this);
        }
        return 0;
    }

    final void incModCount() {
        if (MODIFICATION_COUNTS_ENABLED && graph != null) {
            graph.incModCount(this);
        }
    }

    final int usageModCount() {
        if (MODIFICATION_COUNTS_ENABLED && graph != null) {
            return graph.usageModCount(this);
        }
        return 0;
    }

    final void incUsageModCount() {
        if (MODIFICATION_COUNTS_ENABLED && graph != null) {
            graph.incUsageModCount(this);
        }
    }

    public boolean isDeleted() {
        return id <= DELETED_ID_START;
    }

    public boolean isAlive() {
        return id >= ALIVE_ID_START;
    }

    /**
     * Updates the usages sets of the given nodes after an input slot is changed from
     * {@code oldInput} to {@code newInput} by removing this node from {@code oldInput}'s usages and
     * adds this node to {@code newInput}'s usages.
     */
    protected void updateUsages(Node oldInput, Node newInput) {
        assert isAlive() && (newInput == null || newInput.isAlive()) : "adding " + newInput + " to " + this + " instead of " + oldInput;
        if (oldInput != newInput) {
            if (oldInput != null) {
                boolean result = removeThisFromUsages(oldInput);
                assert assertTrue(result, "not found in usages, old input: %s", oldInput);
            }
            maybeNotifyInputChanged(this);
            if (newInput != null) {
                newInput.addUsage(this);
            }
            if (oldInput != null && oldInput.hasNoUsages()) {
                maybeNotifyZeroUsages(oldInput);
            }
        }
    }

    protected void updateUsagesInterface(NodeInterface oldInput, NodeInterface newInput) {
        updateUsages(oldInput == null ? null : oldInput.asNode(), newInput == null ? null : newInput.asNode());
    }

    /**
     * Updates the predecessor of the given nodes after a successor slot is changed from
     * oldSuccessor to newSuccessor: removes this node from oldSuccessor's predecessors and adds
     * this node to newSuccessor's predecessors.
     */
    protected void updatePredecessor(Node oldSuccessor, Node newSuccessor) {
        assert isAlive() && (newSuccessor == null || newSuccessor.isAlive()) : "adding " + newSuccessor + " to " + this + " instead of " + oldSuccessor;
        assert graph == null || !graph.isFrozen();
        if (oldSuccessor != newSuccessor) {
            if (oldSuccessor != null) {
                assert assertTrue(oldSuccessor.predecessor == this, "wrong predecessor in old successor (%s): %s, should be %s", oldSuccessor, oldSuccessor.predecessor, this);
                oldSuccessor.predecessor = null;
            }
            if (newSuccessor != null) {
                assert assertTrue(newSuccessor.predecessor == null, "unexpected non-null predecessor in new successor (%s): %s, this=%s", newSuccessor, newSuccessor.predecessor, this);
                newSuccessor.predecessor = this;
            }
        }
    }

    void initialize(Graph newGraph) {
        assert assertTrue(id == INITIAL_ID, "unexpected id: %d", id);
        this.graph = newGraph;
        newGraph.register(this);
        this.acceptInputs((n, i) -> {
            if (!i.isAlive()) {
                throw new IllegalStateException(String.format("Input %s of newly created node %s is not alive.", i, n));
            }
            n.updateUsages(null, i);
        });
        this.acceptSuccessors((n, s) -> {
            if (!s.isAlive()) {
                throw new IllegalStateException(String.format("Successor %s of newly created node %s is not alive.", s, n));
            }
            n.updatePredecessor(null, s);
        });
    }

    /**
     * Per node information tracking extra context for a node. Can be used to track the original
     * source of a node (e.g., method and bytecode position) or any other piece of information. It's
     * typed as {@link Object} because not all information to track is under our control so
     * requiring a particular interface would require wrapping the object, adding overhead. It's
     * also possible to capture multiple different types of information so the value may be an
     * {@code Object[]} when multiple objects have been captured.
     */
    Object nodeContext;

    /**
     * @return the uninterpreted context for this node
     */
    public Object getRawNodeContext() {
        return nodeContext;
    }

    /**
     * Gets the context information of exact type {@code theClass} stored by this node or null if it
     * doesn't exist.
     *
     * @param theClass the exact type of the context being requested
     * @return a context of exact type {@code theClass} or {@code null}
     */
    @SuppressWarnings("unchecked")
    public <T> T getNodeContext(Class<T> theClass) {
        if (nodeContext == null) {
            return null;
        }
        if (nodeContext.getClass() == theClass) {
            return (T) nodeContext;
        }
        if (nodeContext instanceof Object[]) {
            for (Object info : (Object[]) nodeContext) {
                if (info.getClass() == theClass) {
                    return (T) info;
                }
            }
        }
        return null;
    }

    /**
     * Replaces the entire current context with {@code context}.
     */
    public void setNodeContext(Object context) {
        nodeContext = context;
    }

    /**
     * Adds some given context to this node. Existing context of the same exact type as
     * {@code newContext} will be replaced by the latter. All other context will be merged with the
     * existing context.
     */
    public void updateNodeContext(Object newContext) {
        // This is the common case with a single type of information captured
        if (nodeContext == null || nodeContext.getClass() == newContext.getClass()) {
            nodeContext = newContext;
            return;
        }
        if (newContext instanceof Object[]) {
            for (Object context : (Object[]) newContext) {
                updateNodeContext(context);
            }
            return;
        }
        if (nodeContext instanceof Object[]) {
            Object[] contextArray = (Object[]) nodeContext;
            for (int i = 0; i < contextArray.length; i++) {
                if (contextArray[i].getClass() == newContext.getClass()) {
                    contextArray[i] = newContext;
                    return;
                }
            }
            contextArray = Arrays.copyOf(contextArray, contextArray.length + 1);
            contextArray[contextArray.length - 1] = newContext;
        } else {
            Object[] contextArray = new Object[2];
            contextArray[0] = nodeContext;
            contextArray[1] = newContext;
            nodeContext = contextArray;
        }
    }

    public final NodeClass<? extends Node> getNodeClass() {
        return nodeClass;
    }

    public boolean isAllowedUsageType(InputType type) {
        return getNodeClass().getAllowedUsageTypes().contains(type);
    }

    private boolean checkReplaceWith(Node other) {
        assert assertTrue(graph == null || !graph.isFrozen(), "cannot modify frozen graph");
        assert assertFalse(other == this, "cannot replace a node with itself");
        assert assertFalse(isDeleted(), "cannot replace deleted node");
        assert assertTrue(other == null || !other.isDeleted(), "cannot replace with deleted node %s", other);
        return true;
    }

    public final void replaceAtUsages(Node other) {
        replaceAtUsages(other, null);
    }

    public final void replaceAtUsages(Node other, Predicate<Node> filter) {
        assert checkReplaceWith(other);
        int i = 0;
        while (i < this.getUsageCount()) {
            Node usage = this.getUsageAt(i);
            if (filter == null || filter.test(usage)) {
                boolean result = usage.getNodeClass().getInputEdges().replaceFirst(usage, this, other);
                assert assertTrue(result, "not found in inputs, usage: %s", usage);
                maybeNotifyInputChanged(usage);
                if (other != null) {
                    other.addUsage(usage);
                }
                this.movUsageFromEndTo(i);
            } else {
                ++i;
            }
        }
    }

    public Node getUsageAt(int index) {
        if (index == 0) {
            return this.usage0;
        } else if (index == 1) {
            return this.usage1;
        } else {
            return this.extraUsages[index - INLINE_USAGE_COUNT];
        }
    }

    public void replaceAtMatchingUsages(Node other, NodePredicate usagePredicate) {
        assert checkReplaceWith(other);
        int index = 0;
        while (index < this.getUsageCount()) {
            Node usage = getUsageAt(index);
            if (usagePredicate.apply(usage)) {
                boolean result = usage.getNodeClass().getInputEdges().replaceFirst(usage, this, other);
                assert assertTrue(result, "not found in inputs, usage: %s", usage);
                if (other != null) {
                    maybeNotifyInputChanged(usage);
                    other.addUsage(usage);
                }
                this.movUsageFromEndTo(index);
            } else {
                index++;
            }
        }
    }

    public void replaceAtUsages(InputType type, Node other) {
        assert checkReplaceWith(other);
        for (Node usage : usages().snapshot()) {
            NodePosIterator iter = usage.inputs().iterator();
            while (iter.hasNext()) {
                Position pos = iter.nextPosition();
                if (pos.getInputType() == type && pos.get(usage) == this) {
                    pos.set(usage, other);
                }
            }
        }
    }

    private void maybeNotifyInputChanged(Node node) {
        if (graph != null) {
            assert !graph.isFrozen();
            NodeEventListener listener = graph.nodeEventListener;
            if (listener != null) {
                listener.inputChanged(node);
            }
            if (Fingerprint.ENABLED) {
                Fingerprint.submit("%s: %s", NodeEvent.INPUT_CHANGED, node);
            }
        }
    }

    private void maybeNotifyZeroUsages(Node node) {
        if (graph != null) {
            assert !graph.isFrozen();
            NodeEventListener listener = graph.nodeEventListener;
            if (listener != null && node.isAlive()) {
                listener.usagesDroppedToZero(node);
            }
            if (Fingerprint.ENABLED) {
                Fingerprint.submit("%s: %s", NodeEvent.ZERO_USAGES, node);
            }
        }
    }

    public void replaceAtPredecessor(Node other) {
        assert checkReplaceWith(other);
        if (predecessor != null) {
            boolean result = predecessor.getNodeClass().getSuccessorEdges().replaceFirst(predecessor, this, other);
            assert assertTrue(result, "not found in successors, predecessor: %s", predecessor);
            predecessor.updatePredecessor(this, other);
        }
    }

    public void replaceAndDelete(Node other) {
        assert checkReplaceWith(other);
        assert other != null;
        clearInputs();
        clearSuccessors();
        replaceAtUsages(other);
        replaceAtPredecessor(other);
        safeDelete();
    }

    public void replaceFirstSuccessor(Node oldSuccessor, Node newSuccessor) {
        if (nodeClass.getSuccessorEdges().replaceFirst(this, oldSuccessor, newSuccessor)) {
            updatePredecessor(oldSuccessor, newSuccessor);
        }
    }

    public void replaceFirstInput(Node oldInput, Node newInput) {
        if (nodeClass.getInputEdges().replaceFirst(this, oldInput, newInput)) {
            updateUsages(oldInput, newInput);
        }
    }

    private void unregisterInputs() {
        acceptInputs((node, input) -> {
            node.removeThisFromUsages(input);
            if (input.hasNoUsages()) {
                node.maybeNotifyZeroUsages(input);
            }
        });
    }

    public void clearInputs() {
        assert assertFalse(isDeleted(), "cannot clear inputs of deleted node");

        unregisterInputs();
        getNodeClass().getInputEdges().clear(this);
    }

    private boolean removeThisFromUsages(Node n) {
        return n.removeUsage(this);
    }

    private void unregisterSuccessors() {
        this.acceptSuccessors((n, successor) -> successor.predecessor = null);
    }

    public void clearSuccessors() {
        assert assertFalse(isDeleted(), "cannot clear successors of deleted node");

        unregisterSuccessors();
        getNodeClass().getSuccessorEdges().clear(this);
    }

    private boolean checkDeletion() {
        assertTrue(hasNoUsages(), "cannot delete node %s because of usages: %s", this, usages());
        assertTrue(predecessor == null, "cannot delete node %s because of predecessor: %s", this, predecessor);
        return true;
    }

    /**
     * Removes this node from its graph. This node must have no {@linkplain Node#usages() usages}
     * and no {@linkplain #predecessor() predecessor}.
     */
    public void safeDelete() {
        assert checkDeletion();
        unregisterInputs();
        unregisterSuccessors();
        markDeleted();
    }

    public void markDeleted() {
        graph.unregister(this);
        id = DELETED_ID_START - id;
        assert isDeleted();
    }

    public final Node copyWithInputs() {
        return copyWithInputs(true);
    }

    public final Node copyWithInputs(boolean insertIntoGraph) {
        Node newNode = clone(insertIntoGraph ? graph : null, WithOnlyInputEdges);
        if (insertIntoGraph) {
            for (Node input : inputs()) {
                input.addUsage(newNode);
            }
        }
        return newNode;
    }

    /**
     * Must be overridden by subclasses that implement {@link Simplifiable}. The implementation in
     * {@link Node} exists to obviate the need to cast a node before invoking
     * {@link Simplifiable#simplify(SimplifierTool)}.
     *
     * @param tool
     */
    public void simplify(SimplifierTool tool) {
        throw new UnsupportedOperationException();
    }

    /**
     * @param newNode the result of cloning this node or {@link Unsafe#allocateInstance(Class) raw
     *            allocating} a copy of this node
     * @param type the type of edges to process
     * @param edgesToCopy if {@code type} is in this set, the edges are copied otherwise they are
     *            cleared
     */
    private void copyOrClearEdgesForClone(Node newNode, Edges.Type type, EnumSet<Edges.Type> edgesToCopy) {
        if (edgesToCopy.contains(type)) {
            getNodeClass().getEdges(type).copy(this, newNode);
        } else {
            if (USE_UNSAFE_TO_CLONE) {
                // The direct edges are already null
                getNodeClass().getEdges(type).initializeLists(newNode, this);
            } else {
                getNodeClass().getEdges(type).clear(newNode);
            }
        }
    }

    public static final EnumSet<Edges.Type> WithNoEdges = EnumSet.noneOf(Edges.Type.class);
    public static final EnumSet<Edges.Type> WithAllEdges = EnumSet.allOf(Edges.Type.class);
    public static final EnumSet<Edges.Type> WithOnlyInputEdges = EnumSet.of(Inputs);
    public static final EnumSet<Edges.Type> WithOnlySucessorEdges = EnumSet.of(Successors);

    /**
     * Makes a copy of this node in(to) a given graph.
     *
     * @param into the graph in which the copy will be registered (which may be this node's graph)
     *            or null if the copy should not be registered in a graph
     * @param edgesToCopy specifies the edges to be copied. The edges not specified in this set are
     *            initialized to their default value (i.e., {@code null} for a direct edge, an empty
     *            list for an edge list)
     * @return the copy of this node
     */
    final Node clone(Graph into, EnumSet<Edges.Type> edgesToCopy) {
        final NodeClass<? extends Node> nodeClassTmp = getNodeClass();
        boolean useIntoLeafNodeCache = false;
        if (into != null) {
            if (nodeClassTmp.valueNumberable() && nodeClassTmp.isLeafNode()) {
                useIntoLeafNodeCache = true;
                Node otherNode = into.findNodeInCache(this);
                if (otherNode != null) {
                    return otherNode;
                }
            }
        }

        Node newNode = null;
        try {
            if (USE_UNSAFE_TO_CLONE) {
                newNode = (Node) UNSAFE.allocateInstance(getClass());
                newNode.nodeClass = nodeClassTmp;
                nodeClassTmp.getData().copy(this, newNode);
                copyOrClearEdgesForClone(newNode, Inputs, edgesToCopy);
                copyOrClearEdgesForClone(newNode, Successors, edgesToCopy);
            } else {
                newNode = (Node) this.clone();
                newNode.typeCacheNext = null;
                newNode.usage0 = null;
                newNode.usage1 = null;
                newNode.predecessor = null;
                newNode.extraUsagesCount = 0;
                copyOrClearEdgesForClone(newNode, Inputs, edgesToCopy);
                copyOrClearEdgesForClone(newNode, Successors, edgesToCopy);
            }
        } catch (Exception e) {
            throw new GraalGraphJVMCIError(e).addContext(this);
        }
        newNode.graph = into;
        newNode.id = INITIAL_ID;
        if (into != null) {
            into.register(newNode);
        }
        newNode.extraUsages = NO_NODES;

        if (into != null && useIntoLeafNodeCache) {
            into.putNodeIntoCache(newNode);
        }
        if (graph != null && into != null) {
            newNode.nodeContext = nodeContext;
        }
        newNode.afterClone(this);
        return newNode;
    }

    protected void afterClone(@SuppressWarnings("unused") Node other) {
    }

    public boolean verifyInputs() {
        for (Node input : inputs()) {
            assertFalse(input.isDeleted(), "input was deleted");
            assertTrue(input.isAlive(), "input is not alive yet, i.e., it was not yet added to the graph");
        }
        return true;
    }

    public boolean verify() {
        assertTrue(isAlive(), "cannot verify inactive nodes (id=%d)", id);
        assertTrue(graph() != null, "null graph");
        if (Options.VerifyGraalGraphEdges.getValue()) {
            verifyEdges();
        }
        return true;
    }

    /**
     * Perform expensive verification of inputs, usages, predecessors and successors.
     *
     * @return true
     */
    public boolean verifyEdges() {
        verifyInputs();
        for (Node input : inputs()) {
            assertTrue(input.usages().contains(this), "missing usage in input %s", input);
        }
        for (Node successor : successors()) {
            assertTrue(successor.predecessor() == this, "missing predecessor in %s (actual: %s)", successor, successor.predecessor());
            assertTrue(successor.graph() == graph(), "mismatching graph in successor %s", successor);
        }
        for (Node usage : usages()) {
            assertFalse(usage.isDeleted(), "usage %s must never be deleted", usage);
            assertTrue(usage.inputs().contains(this), "missing input in usage %s", usage);
            NodePosIterator iterator = usage.inputs().iterator();
            while (iterator.hasNext()) {
                Position pos = iterator.nextPosition();
                if (pos.get(usage) == this && pos.getInputType() != InputType.Unchecked) {
                    assertTrue(isAllowedUsageType(pos.getInputType()), "invalid input of type " + pos.getInputType() + " from " + usage + " to " + this + " (" + pos.getName() + ")");
                }
            }
        }
        NodePosIterator iterator = inputs().withNullIterator();
        while (iterator.hasNext()) {
            Position pos = iterator.nextPosition();
            assert pos.isInputOptional() || pos.get(this) != null : "non-optional input " + pos.getName() + " cannot be null in " + this + " (fix nullness or use @OptionalInput)";
        }
        if (predecessor != null) {
            assertFalse(predecessor.isDeleted(), "predecessor %s must never be deleted", predecessor);
            assertTrue(predecessor.successors().contains(this), "missing successor in predecessor %s", predecessor);
        }
        return true;
    }

    public boolean assertTrue(boolean condition, String message, Object... args) {
        if (condition) {
            return true;
        } else {
            throw fail(message, args);
        }
    }

    public boolean assertFalse(boolean condition, String message, Object... args) {
        if (condition) {
            throw fail(message, args);
        } else {
            return true;
        }
    }

    protected VerificationError fail(String message, Object... args) throws GraalGraphJVMCIError {
        throw new VerificationError(message, args).addContext(this);
    }

    public Iterable<? extends Node> cfgPredecessors() {
        if (predecessor == null) {
            return Collections.emptySet();
        } else {
            return Collections.singleton(predecessor);
        }
    }

    /**
     * Returns an iterator that will provide all control-flow successors of this node. Normally this
     * will be the contents of all fields marked as NodeSuccessor, but some node classes (like
     * EndNode) may return different nodes. Note that the iterator may generate null values if the
     * fields contain them.
     */
    public Iterable<? extends Node> cfgSuccessors() {
        return successors();
    }

    /**
     * Nodes always use an {@linkplain System#identityHashCode(Object) identity} hash code.
     */
    @Override
    public final int hashCode() {
        return System.identityHashCode(this);
    }

    /**
     * Equality tests must rely solely on identity.
     */
    @Override
    public final boolean equals(Object obj) {
        return super.equals(obj);
    }

    /**
     * Provides a {@link Map} of properties of this node for use in debugging (e.g., to view in the
     * ideal graph visualizer).
     */
    public final Map<Object, Object> getDebugProperties() {
        return getDebugProperties(new HashMap<>());
    }

    /**
     * Fills a {@link Map} with properties of this node for use in debugging (e.g., to view in the
     * ideal graph visualizer). Subclasses overriding this method should also fill the map using
     * their superclass.
     *
     * @param map
     */
    public Map<Object, Object> getDebugProperties(Map<Object, Object> map) {
        Fields properties = getNodeClass().getData();
        for (int i = 0; i < properties.getCount(); i++) {
            map.put(properties.getName(i), properties.get(this, i));
        }
        BytecodePosition pos = getNodeContext(BytecodePosition.class);
        if (pos != null) {
            map.put("debugPosition", pos);
        }
        return map;
    }

    /**
     * This method is a shortcut for {@link #toString(Verbosity)} with {@link Verbosity#Short}.
     */
    @Override
    public final String toString() {
        return toString(Verbosity.Short);
    }

    /**
     * Creates a String representation for this node with a given {@link Verbosity}.
     */
    public String toString(Verbosity verbosity) {
        switch (verbosity) {
            case Id:
                return Integer.toString(id);
            case Name:
                return getNodeClass().shortName();
            case Short:
                return toString(Verbosity.Id) + "|" + toString(Verbosity.Name);
            case Long:
                return toString(Verbosity.Short);
            case Debugger:
            case All: {
                StringBuilder str = new StringBuilder();
                str.append(toString(Verbosity.Short)).append(" { ");
                for (Map.Entry<Object, Object> entry : getDebugProperties().entrySet()) {
                    str.append(entry.getKey()).append("=").append(entry.getValue()).append(", ");
                }
                str.append(" }");
                return str.toString();
            }
            default:
                throw new RuntimeException("unknown verbosity: " + verbosity);
        }
    }

    public int getId() {
        return id;
    }

    @Override
    public void formatTo(Formatter formatter, int flags, int width, int precision) {
        if ((flags & FormattableFlags.ALTERNATE) == FormattableFlags.ALTERNATE) {
            formatter.format("%s", toString(Verbosity.Id));
        } else if ((flags & FormattableFlags.UPPERCASE) == FormattableFlags.UPPERCASE) {
            // Use All here since Long is only slightly longer than Short.
            formatter.format("%s", toString(Verbosity.All));
        } else {
            formatter.format("%s", toString(Verbosity.Short));
        }

        boolean neighborsAlternate = ((flags & FormattableFlags.LEFT_JUSTIFY) == FormattableFlags.LEFT_JUSTIFY);
        int neighborsFlags = (neighborsAlternate ? FormattableFlags.ALTERNATE | FormattableFlags.LEFT_JUSTIFY : 0);
        if (width > 0) {
            if (this.predecessor != null) {
                formatter.format(" pred={");
                this.predecessor.formatTo(formatter, neighborsFlags, width - 1, 0);
                formatter.format("}");
            }

            NodePosIterator inputIter = inputs().iterator();
            while (inputIter.hasNext()) {
                Position position = inputIter.nextPosition();
                Node input = position.get(this);
                if (input != null) {
                    formatter.format(" ");
                    formatter.format(position.getName());
                    formatter.format("={");
                    input.formatTo(formatter, neighborsFlags, width - 1, 0);
                    formatter.format("}");
                }
            }
        }

        if (precision > 0) {
            if (!hasNoUsages()) {
                formatter.format(" usages={");
                int z = 0;
                for (Node usage : usages()) {
                    if (z != 0) {
                        formatter.format(", ");
                    }
                    usage.formatTo(formatter, neighborsFlags, 0, precision - 1);
                    ++z;
                }
                formatter.format("}");
            }

            NodePosIterator succIter = successors().iterator();
            while (succIter.hasNext()) {
                Position position = succIter.nextPosition();
                Node successor = position.get(this);
                if (successor != null) {
                    formatter.format(" ");
                    formatter.format(position.getName());
                    formatter.format("={");
                    successor.formatTo(formatter, neighborsFlags, 0, precision - 1);
                    formatter.format("}");
                }
            }
        }
    }

    /**
     * Determines if this node's {@link NodeClass#getData() data} fields are equal to the data
     * fields of another node of the same type. Primitive fields are compared by value and
     * non-primitive fields are compared by {@link Objects#equals(Object, Object)}.
     *
     * The result of this method undefined if {@code other.getClass() != this.getClass()}.
     *
     * @param other a node of exactly the same type as this node
     * @return true if the data fields of this object and {@code other} are equal
     */
    public boolean valueEquals(Node other) {
        return getNodeClass().dataEquals(this, other);
    }

    public final void pushInputs(NodeStack stack) {
        getNodeClass().getInputEdges().pushAll(this, stack);
    }
}
