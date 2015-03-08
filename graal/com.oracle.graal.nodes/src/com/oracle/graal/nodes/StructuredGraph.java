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
package com.oracle.graal.nodes;

import java.util.*;
import java.util.concurrent.atomic.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.Assumptions.Assumption;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.util.*;

/**
 * A graph that contains at least one distinguished node : the {@link #start() start} node. This
 * node is the start of the control flow of the graph.
 */
public class StructuredGraph extends Graph {

    /**
     * The different stages of the compilation of a {@link Graph} regarding the status of
     * {@link GuardNode guards}, {@link DeoptimizingNode deoptimizations} and {@link FrameState
     * framestates}. The stage of a graph progresses monotonously.
     *
     */
    public static enum GuardsStage {
        /**
         * During this stage, there can be {@link FloatingNode floating} {@link DeoptimizingNode}
         * such as {@link GuardNode GuardNodes}. New {@link DeoptimizingNode DeoptimizingNodes} can
         * be introduced without constraints. {@link FrameState} nodes are associated with
         * {@link StateSplit} nodes.
         */
        FLOATING_GUARDS,
        /**
         * During this stage, all {@link DeoptimizingNode DeoptimizingNodes} must be
         * {@link FixedNode fixed} but new {@link DeoptimizingNode DeoptimizingNodes} can still be
         * introduced. {@link FrameState} nodes are still associated with {@link StateSplit} nodes.
         */
        FIXED_DEOPTS,
        /**
         * During this stage, all {@link DeoptimizingNode DeoptimizingNodes} must be
         * {@link FixedNode fixed}. New {@link DeoptimizingNode DeoptimizingNodes} can not be
         * introduced any more. {@link FrameState} nodes are now associated with
         * {@link DeoptimizingNode} nodes.
         */
        AFTER_FSA;

        public boolean allowsFloatingGuards() {
            return this == FLOATING_GUARDS;
        }

        public boolean areFrameStatesAtDeopts() {
            return this == AFTER_FSA;
        }

        public boolean areFrameStatesAtSideEffects() {
            return !this.areFrameStatesAtDeopts();
        }
    }

    /**
     * Constants denoting whether or not {@link Assumption}s can be made while processing a graph.
     */
    public enum AllowAssumptions {
        YES,
        NO;
        public static AllowAssumptions from(boolean flag) {
            return flag ? YES : NO;
        }
    }

    public static final int INVOCATION_ENTRY_BCI = -1;
    public static final long INVALID_GRAPH_ID = -1;

    private static final AtomicLong uniqueGraphIds = new AtomicLong();

    private StartNode start;
    private final ResolvedJavaMethod method;
    private final long graphId;
    private final int entryBCI;
    private GuardsStage guardsStage = GuardsStage.FLOATING_GUARDS;
    private boolean isAfterFloatingReadPhase = false;
    private boolean hasValueProxies = true;

    /**
     * The assumptions made while constructing and transforming this graph.
     */
    private final Assumptions assumptions;

    /**
     * The methods that were inlined while constructing this graph.
     */
    private Set<ResolvedJavaMethod> inlinedMethods = new HashSet<>();

    /**
     * Creates a new Graph containing a single {@link AbstractBeginNode} as the {@link #start()
     * start} node.
     */
    public StructuredGraph(AllowAssumptions allowAssumptions) {
        this(null, null, allowAssumptions);
    }

    /**
     * Creates a new Graph containing a single {@link AbstractBeginNode} as the {@link #start()
     * start} node.
     */
    public StructuredGraph(String name, ResolvedJavaMethod method, AllowAssumptions allowAssumptions) {
        this(name, method, uniqueGraphIds.incrementAndGet(), INVOCATION_ENTRY_BCI, allowAssumptions);
    }

    public StructuredGraph(ResolvedJavaMethod method, AllowAssumptions allowAssumptions) {
        this(null, method, uniqueGraphIds.incrementAndGet(), INVOCATION_ENTRY_BCI, allowAssumptions);
    }

    public StructuredGraph(ResolvedJavaMethod method, int entryBCI, AllowAssumptions allowAssumptions) {
        this(null, method, uniqueGraphIds.incrementAndGet(), entryBCI, allowAssumptions);
    }

    private StructuredGraph(String name, ResolvedJavaMethod method, long graphId, int entryBCI, AllowAssumptions allowAssumptions) {
        super(name);
        this.setStart(add(new StartNode()));
        this.method = method;
        this.graphId = graphId;
        this.entryBCI = entryBCI;
        this.assumptions = allowAssumptions == AllowAssumptions.YES ? new Assumptions() : null;
    }

    public Stamp getReturnStamp() {
        Stamp returnStamp = null;
        for (ReturnNode returnNode : getNodes(ReturnNode.TYPE)) {
            ValueNode result = returnNode.result();
            if (result != null) {
                if (returnStamp == null) {
                    returnStamp = result.stamp();
                } else {
                    returnStamp = returnStamp.meet(result.stamp());
                }
            }
        }
        return returnStamp;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(getClass().getSimpleName() + ":" + graphId);
        String sep = "{";
        if (name != null) {
            buf.append(sep);
            buf.append(name);
            sep = ", ";
        }
        if (method != null) {
            buf.append(sep);
            buf.append(method);
            sep = ", ";
        }

        if (!sep.equals("{")) {
            buf.append("}");
        }
        return buf.toString();
    }

    public StartNode start() {
        return start;
    }

    /**
     * Gets the method from which this graph was built.
     *
     * @return null if this method was not built from a method or the method is not available
     */
    public ResolvedJavaMethod method() {
        return method;
    }

    public int getEntryBCI() {
        return entryBCI;
    }

    public boolean isOSR() {
        return entryBCI != INVOCATION_ENTRY_BCI;
    }

    public long graphId() {
        return graphId;
    }

    public void setStart(StartNode start) {
        this.start = start;
    }

    @Override
    public StructuredGraph copy() {
        return copy(name);
    }

    public StructuredGraph copy(String newName, ResolvedJavaMethod newMethod) {
        return copy(newName, newMethod, AllowAssumptions.from(assumptions != null), isInlinedMethodRecordingEnabled());
    }

    public StructuredGraph copy(String newName, ResolvedJavaMethod newMethod, AllowAssumptions allowAssumptions, boolean enableInlinedMethodRecording) {
        StructuredGraph copy = new StructuredGraph(newName, newMethod, graphId, entryBCI, allowAssumptions);
        if (allowAssumptions == AllowAssumptions.YES && assumptions != null) {
            copy.assumptions.record(assumptions);
        }
        if (!enableInlinedMethodRecording) {
            copy.disableInlinedMethodRecording();
        }
        copy.setGuardsStage(getGuardsStage());
        copy.isAfterFloatingReadPhase = isAfterFloatingReadPhase;
        copy.hasValueProxies = hasValueProxies;
        Map<Node, Node> replacements = Node.newMap();
        replacements.put(start, copy.start);
        copy.addDuplicates(getNodes(), this, this.getNodeCount(), replacements);
        return copy;
    }

    @Override
    public StructuredGraph copy(String newName) {
        return copy(newName, method);
    }

    public ParameterNode getParameter(int index) {
        for (ParameterNode param : getNodes(ParameterNode.TYPE)) {
            if (param.index() == index) {
                return param;
            }
        }
        return null;
    }

    public Iterable<Invoke> getInvokes() {
        final Iterator<MethodCallTargetNode> callTargets = getNodes(MethodCallTargetNode.TYPE).iterator();
        return new Iterable<Invoke>() {

            private Invoke next;

            @Override
            public Iterator<Invoke> iterator() {
                return new Iterator<Invoke>() {

                    @Override
                    public boolean hasNext() {
                        if (next == null) {
                            while (callTargets.hasNext()) {
                                Invoke i = callTargets.next().invoke();
                                if (i != null) {
                                    next = i;
                                    return true;
                                }
                            }
                            return false;
                        } else {
                            return true;
                        }
                    }

                    @Override
                    public Invoke next() {
                        try {
                            return next;
                        } finally {
                            next = null;
                        }
                    }

                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }

    public boolean hasLoops() {
        return hasNode(LoopBeginNode.TYPE);
    }

    public void removeFloating(FloatingNode node) {
        assert node != null && node.isAlive() : "cannot remove " + node;
        node.safeDelete();
    }

    public void replaceFloating(FloatingNode node, Node replacement) {
        assert node != null && node.isAlive() && (replacement == null || replacement.isAlive()) : "cannot replace " + node + " with " + replacement;
        node.replaceAtUsages(replacement);
        node.safeDelete();
    }

    /**
     * Unlinks a node from all its control flow neighbors and then removes it from its graph. The
     * node must have no {@linkplain Node#usages() usages}.
     *
     * @param node the node to be unlinked and removed
     */
    public void removeFixed(FixedWithNextNode node) {
        assert node != null;
        if (node instanceof AbstractBeginNode) {
            ((AbstractBeginNode) node).prepareDelete();
        }
        assert node.hasNoUsages() : node + " " + node.usages().count() + ", " + node.usages().first();
        GraphUtil.unlinkFixedNode(node);
        node.safeDelete();
    }

    public void replaceFixed(FixedWithNextNode node, Node replacement) {
        if (replacement instanceof FixedWithNextNode) {
            replaceFixedWithFixed(node, (FixedWithNextNode) replacement);
        } else {
            assert replacement != null : "cannot replace " + node + " with null";
            assert replacement instanceof FloatingNode : "cannot replace " + node + " with " + replacement;
            replaceFixedWithFloating(node, (FloatingNode) replacement);
        }
    }

    public void replaceFixedWithFixed(FixedWithNextNode node, FixedWithNextNode replacement) {
        assert node != null && replacement != null && node.isAlive() && replacement.isAlive() : "cannot replace " + node + " with " + replacement;
        FixedNode next = node.next();
        node.setNext(null);
        replacement.setNext(next);
        node.replaceAndDelete(replacement);
        if (node == start) {
            setStart((StartNode) replacement);
        }
    }

    public void replaceFixedWithFloating(FixedWithNextNode node, FloatingNode replacement) {
        assert node != null && replacement != null && node.isAlive() && replacement.isAlive() : "cannot replace " + node + " with " + replacement;
        GraphUtil.unlinkFixedNode(node);
        node.replaceAtUsages(replacement);
        node.safeDelete();
    }

    public void removeSplit(ControlSplitNode node, AbstractBeginNode survivingSuccessor) {
        assert node != null;
        assert node.hasNoUsages();
        assert survivingSuccessor != null;
        node.clearSuccessors();
        node.replaceAtPredecessor(survivingSuccessor);
        node.safeDelete();
    }

    public void removeSplitPropagate(ControlSplitNode node, AbstractBeginNode survivingSuccessor) {
        removeSplitPropagate(node, survivingSuccessor, null);
    }

    public void removeSplitPropagate(ControlSplitNode node, AbstractBeginNode survivingSuccessor, SimplifierTool tool) {
        assert node != null;
        assert node.hasNoUsages();
        assert survivingSuccessor != null;
        List<Node> snapshot = node.successors().snapshot();
        node.clearSuccessors();
        node.replaceAtPredecessor(survivingSuccessor);
        node.safeDelete();
        for (Node successor : snapshot) {
            if (successor != null && successor.isAlive()) {
                if (successor != survivingSuccessor) {
                    GraphUtil.killCFG(successor, tool);
                }
            }
        }
    }

    public void replaceSplit(ControlSplitNode node, Node replacement, AbstractBeginNode survivingSuccessor) {
        if (replacement instanceof FixedWithNextNode) {
            replaceSplitWithFixed(node, (FixedWithNextNode) replacement, survivingSuccessor);
        } else {
            assert replacement != null : "cannot replace " + node + " with null";
            assert replacement instanceof FloatingNode : "cannot replace " + node + " with " + replacement;
            replaceSplitWithFloating(node, (FloatingNode) replacement, survivingSuccessor);
        }
    }

    public void replaceSplitWithFixed(ControlSplitNode node, FixedWithNextNode replacement, AbstractBeginNode survivingSuccessor) {
        assert node != null && replacement != null && node.isAlive() && replacement.isAlive() : "cannot replace " + node + " with " + replacement;
        assert survivingSuccessor != null;
        node.clearSuccessors();
        replacement.setNext(survivingSuccessor);
        node.replaceAndDelete(replacement);
    }

    public void replaceSplitWithFloating(ControlSplitNode node, FloatingNode replacement, AbstractBeginNode survivingSuccessor) {
        assert node != null && replacement != null && node.isAlive() && replacement.isAlive() : "cannot replace " + node + " with " + replacement;
        assert survivingSuccessor != null;
        node.clearSuccessors();
        node.replaceAtPredecessor(survivingSuccessor);
        node.replaceAtUsages(replacement);
        node.safeDelete();
    }

    public void addAfterFixed(FixedWithNextNode node, FixedNode newNode) {
        assert node != null && newNode != null && node.isAlive() && newNode.isAlive() : "cannot add " + newNode + " after " + node;
        FixedNode next = node.next();
        node.setNext(newNode);
        if (next != null) {
            assert newNode instanceof FixedWithNextNode;
            FixedWithNextNode newFixedWithNext = (FixedWithNextNode) newNode;
            assert newFixedWithNext.next() == null;
            newFixedWithNext.setNext(next);
        }
    }

    public void addBeforeFixed(FixedNode node, FixedWithNextNode newNode) {
        assert node != null && newNode != null && node.isAlive() && newNode.isAlive() : "cannot add " + newNode + " before " + node;
        assert node.predecessor() != null && node.predecessor() instanceof FixedWithNextNode : "cannot add " + newNode + " before " + node;
        assert newNode.next() == null : newNode;
        FixedWithNextNode pred = (FixedWithNextNode) node.predecessor();
        pred.setNext(newNode);
        newNode.setNext(node);
    }

    public void reduceDegenerateLoopBegin(LoopBeginNode begin) {
        assert begin.loopEnds().isEmpty() : "Loop begin still has backedges";
        if (begin.forwardEndCount() == 1) { // bypass merge and remove
            reduceTrivialMerge(begin);
        } else { // convert to merge
            AbstractMergeNode merge = this.add(new MergeNode());
            this.replaceFixedWithFixed(begin, merge);
        }
    }

    public void reduceTrivialMerge(AbstractMergeNode merge) {
        assert merge.forwardEndCount() == 1;
        assert !(merge instanceof LoopBeginNode) || ((LoopBeginNode) merge).loopEnds().isEmpty();
        for (PhiNode phi : merge.phis().snapshot()) {
            assert phi.valueCount() == 1;
            ValueNode singleValue = phi.valueAt(0);
            phi.replaceAtUsages(singleValue);
            phi.safeDelete();
        }
        // remove loop exits
        if (merge instanceof LoopBeginNode) {
            ((LoopBeginNode) merge).removeExits();
        }
        AbstractEndNode singleEnd = merge.forwardEndAt(0);
        FixedNode sux = merge.next();
        FrameState stateAfter = merge.stateAfter();
        // evacuateGuards
        merge.prepareDelete((FixedNode) singleEnd.predecessor());
        merge.safeDelete();
        if (stateAfter != null && stateAfter.isAlive() && stateAfter.hasNoUsages()) {
            GraphUtil.killWithUnusedFloatingInputs(stateAfter);
        }
        if (sux == null) {
            singleEnd.replaceAtPredecessor(null);
            singleEnd.safeDelete();
        } else {
            singleEnd.replaceAndDelete(sux);
        }
    }

    public GuardsStage getGuardsStage() {
        return guardsStage;
    }

    public void setGuardsStage(GuardsStage guardsStage) {
        assert guardsStage.ordinal() >= this.guardsStage.ordinal();
        this.guardsStage = guardsStage;
    }

    public boolean isAfterFloatingReadPhase() {
        return isAfterFloatingReadPhase;
    }

    public void setAfterFloatingReadPhase(boolean state) {
        assert state : "cannot 'unapply' floating read phase on graph";
        isAfterFloatingReadPhase = state;
    }

    public boolean hasValueProxies() {
        return hasValueProxies;
    }

    public void setHasValueProxies(boolean state) {
        assert !state : "cannot 'unapply' value proxy removal on graph";
        hasValueProxies = state;
    }

    /**
     * Gets the object for recording assumptions while constructing of this graph.
     *
     * @return {@code null} if assumptions cannot be made for this graph
     */
    public Assumptions getAssumptions() {
        return assumptions;
    }

    /**
     * Disables recording of methods inlined while constructing this graph. This can be done at most
     * once and must be done before any inlined methods are recorded.
     */
    public void disableInlinedMethodRecording() {
        assert inlinedMethods != null : "cannot disable inlined method recording more than once";
        assert inlinedMethods.isEmpty() : "cannot disable inlined method recording once methods have been recorded";
        inlinedMethods = null;
    }

    public boolean isInlinedMethodRecordingEnabled() {
        return inlinedMethods != null;
    }

    /**
     * Gets the methods that were inlined while constructing this graph.
     *
     * @return {@code null} if inlined method recording has been
     *         {@linkplain #disableInlinedMethodRecording() disabled}
     */
    public Set<ResolvedJavaMethod> getInlinedMethods() {
        return inlinedMethods;
    }
}
