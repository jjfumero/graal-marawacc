/*
 * Copyright (c) 2011, 2015, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.compiler.common.GraalOptions.UseGraalQueries;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import jdk.internal.jvmci.meta.ConstantReflectionProvider;
import jdk.internal.jvmci.meta.JavaConstant;
import jdk.internal.jvmci.meta.JavaKind;
import jdk.internal.jvmci.meta.MetaAccessProvider;

import com.oracle.graal.compiler.common.CollectionsFactory;
import com.oracle.graal.compiler.common.cfg.Loop;
import com.oracle.graal.compiler.common.type.Stamp;
import com.oracle.graal.compiler.common.type.StampFactory;
import com.oracle.graal.compiler.common.util.ArraySet;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.DebugMetric;
import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.NodeBitMap;
import com.oracle.graal.graph.NodePosIterator;
import com.oracle.graal.graph.Position;
import com.oracle.graal.graph.spi.Canonicalizable;
import com.oracle.graal.nodes.CallTargetNode;
import com.oracle.graal.nodes.ConstantNode;
import com.oracle.graal.nodes.ControlSinkNode;
import com.oracle.graal.nodes.FixedNode;
import com.oracle.graal.nodes.FixedWithNextNode;
import com.oracle.graal.nodes.FrameState;
import com.oracle.graal.nodes.Invoke;
import com.oracle.graal.nodes.LoopBeginNode;
import com.oracle.graal.nodes.LoopExitNode;
import com.oracle.graal.nodes.PhiNode;
import com.oracle.graal.nodes.ProxyNode;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.ValuePhiNode;
import com.oracle.graal.nodes.ValueProxyNode;
import com.oracle.graal.nodes.VirtualState;
import com.oracle.graal.nodes.VirtualState.NodeClosure;
import com.oracle.graal.nodes.cfg.Block;
import com.oracle.graal.nodes.spi.NodeWithState;
import com.oracle.graal.nodes.spi.Virtualizable;
import com.oracle.graal.nodes.spi.VirtualizableAllocation;
import com.oracle.graal.nodes.spi.VirtualizerTool;
import com.oracle.graal.nodes.virtual.VirtualObjectNode;
import com.oracle.graal.phases.common.query.nodes.InstrumentationNode;
import com.oracle.graal.phases.schedule.SchedulePhase;

public abstract class PartialEscapeClosure<BlockT extends PartialEscapeBlockState<BlockT>> extends EffectsClosure<BlockT> {

    public static final DebugMetric METRIC_MATERIALIZATIONS = Debug.metric("Materializations");
    public static final DebugMetric METRIC_MATERIALIZATIONS_PHI = Debug.metric("MaterializationsPhi");
    public static final DebugMetric METRIC_MATERIALIZATIONS_MERGE = Debug.metric("MaterializationsMerge");
    public static final DebugMetric METRIC_MATERIALIZATIONS_UNHANDLED = Debug.metric("MaterializationsUnhandled");
    public static final DebugMetric METRIC_MATERIALIZATIONS_LOOP_REITERATION = Debug.metric("MaterializationsLoopReiteration");
    public static final DebugMetric METRIC_MATERIALIZATIONS_LOOP_END = Debug.metric("MaterializationsLoopEnd");
    public static final DebugMetric METRIC_ALLOCATION_REMOVED = Debug.metric("AllocationsRemoved");

    public static final DebugMetric METRIC_MEMORYCHECKPOINT = Debug.metric("MemoryCheckpoint");

    private final NodeBitMap hasVirtualInputs;
    private final VirtualizerToolImpl tool;

    public final ArrayList<VirtualObjectNode> virtualObjects = new ArrayList<>();

    private final class CollectVirtualObjectsClosure extends NodeClosure<ValueNode> {
        private final Set<VirtualObjectNode> virtual;
        private final GraphEffectList effects;
        private final BlockT state;

        private CollectVirtualObjectsClosure(Set<VirtualObjectNode> virtual, GraphEffectList effects, BlockT state) {
            this.virtual = virtual;
            this.effects = effects;
            this.state = state;
        }

        @Override
        public void apply(Node usage, ValueNode value) {
            if (value instanceof VirtualObjectNode) {
                VirtualObjectNode object = (VirtualObjectNode) value;
                if (object.getObjectId() != -1 && state.getObjectStateOptional(object) != null) {
                    virtual.add(object);
                }
            } else {
                ValueNode alias = getAlias(value);
                if (alias instanceof VirtualObjectNode) {
                    VirtualObjectNode object = (VirtualObjectNode) alias;
                    virtual.add(object);
                    effects.replaceFirstInput(usage, value, object);
                }
            }
        }
    }

    /**
     * Final subclass of PartialEscapeClosure, for performance and to make everything behave nicely
     * with generics.
     */
    public static final class Final extends PartialEscapeClosure<PartialEscapeBlockState.Final> {

        public Final(SchedulePhase schedule, MetaAccessProvider metaAccess, ConstantReflectionProvider constantReflection) {
            super(schedule, metaAccess, constantReflection);
        }

        @Override
        protected PartialEscapeBlockState.Final getInitialState() {
            return new PartialEscapeBlockState.Final();
        }

        @Override
        protected PartialEscapeBlockState.Final cloneState(PartialEscapeBlockState.Final oldState) {
            return new PartialEscapeBlockState.Final(oldState);
        }
    }

    public PartialEscapeClosure(SchedulePhase schedule, MetaAccessProvider metaAccess, ConstantReflectionProvider constantReflection) {
        super(schedule, schedule.getCFG());
        this.hasVirtualInputs = schedule.getCFG().graph.createNodeBitMap();
        this.tool = new VirtualizerToolImpl(metaAccess, constantReflection, this);
    }

    /**
     * @return true if the node was deleted, false otherwise
     */
    @Override
    protected boolean processNode(Node node, BlockT state, GraphEffectList effects, FixedWithNextNode lastFixedNode) {
        /*
         * These checks make up for the fact that an earliest schedule moves CallTargetNodes upwards
         * and thus materializes virtual objects needlessly. Also, FrameStates and ConstantNodes are
         * scheduled, but can safely be ignored.
         */
        if (node instanceof CallTargetNode || node instanceof FrameState || node instanceof ConstantNode) {
            return false;
        } else if (node instanceof Invoke) {
            processNodeInternal(((Invoke) node).callTarget(), state, effects, lastFixedNode);
        }
        return processNodeInternal(node, state, effects, lastFixedNode);
    }

    private boolean processNodeInternal(Node node, BlockT state, GraphEffectList effects, FixedWithNextNode lastFixedNode) {
        FixedNode nextFixedNode = lastFixedNode == null ? null : lastFixedNode.next();
        VirtualUtil.trace("%s", node);

        if (requiresProcessing(node)) {
            if (processVirtualizable((ValueNode) node, nextFixedNode, state, effects) == false) {
                return false;
            }
            if (tool.isDeleted()) {
                VirtualUtil.trace("deleted virtualizable allocation %s", node);
                return true;
            }
        }
        if (hasVirtualInputs.isMarked(node) && node instanceof ValueNode) {
            if (node instanceof Virtualizable) {
                if (processVirtualizable((ValueNode) node, nextFixedNode, state, effects) == false) {
                    return false;
                }
                if (tool.isDeleted()) {
                    VirtualUtil.trace("deleted virtualizable node %s", node);
                    return true;
                }
            }
            if (UseGraalQueries.getValue() && (node instanceof InstrumentationNode)) {
                // ignore inputs for InstrumentationNode
                return false;
            }
            processNodeInputs((ValueNode) node, nextFixedNode, state, effects);
        }

        if (hasScalarReplacedInputs(node) && node instanceof ValueNode) {
            if (processNodeWithScalarReplacedInputs((ValueNode) node, nextFixedNode, state, effects)) {
                return true;
            }
        }
        return false;
    }

    protected boolean requiresProcessing(Node node) {
        return node instanceof VirtualizableAllocation;
    }

    private boolean processNodeWithScalarReplacedInputs(ValueNode node, FixedNode insertBefore, BlockT state, GraphEffectList effects) {
        ValueNode canonicalizedValue = node;
        if (node instanceof Canonicalizable.Unary<?>) {
            @SuppressWarnings("unchecked")
            Canonicalizable.Unary<ValueNode> canonicalizable = (Canonicalizable.Unary<ValueNode>) node;
            ObjectState valueObj = getObjectState(state, canonicalizable.getValue());
            ValueNode valueAlias = valueObj != null ? valueObj.getMaterializedValue() : getScalarAlias(canonicalizable.getValue());
            if (valueAlias != canonicalizable.getValue()) {
                canonicalizedValue = (ValueNode) canonicalizable.canonical(tool, valueAlias);
            }
        } else if (node instanceof Canonicalizable.Binary<?>) {
            @SuppressWarnings("unchecked")
            Canonicalizable.Binary<ValueNode> canonicalizable = (Canonicalizable.Binary<ValueNode>) node;
            ObjectState xObj = getObjectState(state, canonicalizable.getX());
            ValueNode xAlias = xObj != null ? xObj.getMaterializedValue() : getScalarAlias(canonicalizable.getX());
            ObjectState yObj = getObjectState(state, canonicalizable.getY());
            ValueNode yAlias = yObj != null ? yObj.getMaterializedValue() : getScalarAlias(canonicalizable.getY());
            if (xAlias != canonicalizable.getX() || yAlias != canonicalizable.getY()) {
                canonicalizedValue = (ValueNode) canonicalizable.canonical(tool, xAlias, yAlias);
            }
        } else {
            return false;
        }
        if (canonicalizedValue != node && canonicalizedValue != null) {
            if (canonicalizedValue.isAlive()) {
                ValueNode alias = getAliasAndResolve(state, canonicalizedValue);
                if (alias instanceof VirtualObjectNode) {
                    addAndMarkAlias((VirtualObjectNode) alias, node);
                    effects.deleteNode(node);
                } else {
                    effects.replaceAtUsages(node, alias);
                    addScalarAlias(node, alias);
                }
            } else {
                if (!prepareCanonicalNode(canonicalizedValue, state, effects)) {
                    VirtualUtil.trace("replacement via canonicalization too complex: %s -> %s", node, canonicalizedValue);
                    return false;
                }
                effects.ensureAdded(canonicalizedValue, insertBefore);
                if (canonicalizedValue instanceof ControlSinkNode) {
                    effects.replaceWithSink((FixedWithNextNode) node, (ControlSinkNode) canonicalizedValue);
                    state.markAsDead();
                } else {
                    effects.replaceAtUsages(node, canonicalizedValue);
                    addScalarAlias(node, canonicalizedValue);
                }
            }
            VirtualUtil.trace("replaced via canonicalization: %s -> %s", node, canonicalizedValue);
            return true;
        }
        return false;
    }

    private boolean prepareCanonicalNode(ValueNode node, BlockT state, GraphEffectList effects) {
        assert !node.isAlive();
        NodePosIterator iter = node.inputs().iterator();
        while (iter.hasNext()) {
            Position pos = iter.nextPosition();
            Node input = pos.get(node);
            if (input instanceof ValueNode) {
                if (input.isAlive()) {
                    ObjectState obj = getObjectState(state, (ValueNode) input);
                    if (obj != null) {
                        if (obj.isVirtual()) {
                            return false;
                        } else {
                            pos.initialize(node, obj.getMaterializedValue());
                        }
                    } else {
                        pos.initialize(node, getScalarAlias((ValueNode) input));
                    }
                } else {
                    if (!prepareCanonicalNode((ValueNode) input, state, effects)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void processNodeInputs(ValueNode node, FixedNode insertBefore, BlockT state, GraphEffectList effects) {
        VirtualUtil.trace("processing nodewithstate: %s", node);
        for (Node input : node.inputs()) {
            if (input instanceof ValueNode) {
                ValueNode alias = getAlias((ValueNode) input);
                if (alias instanceof VirtualObjectNode) {
                    int id = ((VirtualObjectNode) alias).getObjectId();
                    ensureMaterialized(state, id, insertBefore, effects, METRIC_MATERIALIZATIONS_UNHANDLED);
                    effects.replaceFirstInput(node, input, state.getObjectState(id).getMaterializedValue());
                    VirtualUtil.trace("replacing input %s at %s", input, node);
                }
            }
        }
        if (node instanceof NodeWithState) {
            processNodeWithState((NodeWithState) node, state, effects);
        }
    }

    private boolean processVirtualizable(ValueNode node, FixedNode insertBefore, BlockT state, GraphEffectList effects) {
        tool.reset(state, node, insertBefore, effects);
        return virtualize(node, tool);
    }

    protected boolean virtualize(ValueNode node, VirtualizerTool vt) {
        ((Virtualizable) node).virtualize(vt);
        return true; // request further processing
    }

    private void processNodeWithState(NodeWithState nodeWithState, BlockT state, GraphEffectList effects) {
        for (FrameState fs : nodeWithState.states()) {
            FrameState frameState = getUniqueFramestate(nodeWithState, fs);
            Set<VirtualObjectNode> virtual = new ArraySet<>();
            frameState.applyToNonVirtual(new CollectVirtualObjectsClosure(virtual, effects, state));
            collectLockedVirtualObjects(state, virtual);
            collectReferencedVirtualObjects(state, virtual);
            addVirtualMappings(frameState, virtual, state, effects);
        }
    }

    private static FrameState getUniqueFramestate(NodeWithState nodeWithState, FrameState frameState) {
        if (frameState.getUsageCount() > 1) {
            // Can happen for example from inlined snippets with multiple state split nodes.
            FrameState copy = (FrameState) frameState.copyWithInputs();
            nodeWithState.asNode().replaceFirstInput(frameState, copy);
            return copy;
        }
        return frameState;
    }

    private void addVirtualMappings(FrameState frameState, Set<VirtualObjectNode> virtual, BlockT state, GraphEffectList effects) {
        for (VirtualObjectNode obj : virtual) {
            effects.addVirtualMapping(frameState, state.getObjectState(obj).createEscapeObjectState(obj));
        }
    }

    private void collectReferencedVirtualObjects(BlockT state, Set<VirtualObjectNode> virtual) {
        ArrayDeque<VirtualObjectNode> queue = new ArrayDeque<>(virtual);
        while (!queue.isEmpty()) {
            VirtualObjectNode object = queue.removeLast();
            int id = object.getObjectId();
            if (id != -1) {
                ObjectState objState = state.getObjectStateOptional(id);
                if (objState != null && objState.isVirtual()) {
                    for (ValueNode entry : objState.getEntries()) {
                        if (entry instanceof VirtualObjectNode) {
                            VirtualObjectNode entryVirtual = (VirtualObjectNode) entry;
                            if (!virtual.contains(entryVirtual)) {
                                virtual.add(entryVirtual);
                                queue.addLast(entryVirtual);
                            }
                        }
                    }
                }
            }
        }
    }

    private void collectLockedVirtualObjects(BlockT state, Set<VirtualObjectNode> virtual) {
        for (int i = 0; i < state.getStateCount(); i++) {
            ObjectState objState = state.getObjectStateOptional(i);
            if (objState != null && objState.isVirtual() && objState.hasLocks()) {
                virtual.add(virtualObjects.get(i));
            }
        }
    }

    /**
     * @return true if materialization happened, false if not.
     */
    protected boolean ensureMaterialized(PartialEscapeBlockState<?> state, int object, FixedNode materializeBefore, GraphEffectList effects, DebugMetric metric) {
        if (state.getObjectState(object).isVirtual()) {
            metric.increment();
            VirtualObjectNode virtual = virtualObjects.get(object);
            state.materializeBefore(materializeBefore, virtual, effects);
            updateStatesForMaterialized(state, virtual, state.getObjectState(object).getMaterializedValue());
            return true;
        } else {
            return false;
        }
    }

    public static void updateStatesForMaterialized(PartialEscapeBlockState<?> state, VirtualObjectNode virtual, ValueNode materializedValue) {
        // update all existing states with the newly materialized object
        for (int i = 0; i < state.getStateCount(); i++) {
            ObjectState objState = state.getObjectStateOptional(i);
            if (objState != null && objState.isVirtual()) {
                ValueNode[] entries = objState.getEntries();
                for (int i2 = 0; i2 < entries.length; i2++) {
                    if (entries[i2] == virtual) {
                        state.setEntry(i, i2, materializedValue);
                    }
                }
            }
        }
    }

    @Override
    protected void processInitialLoopState(Loop<Block> loop, BlockT initialState) {
        for (PhiNode phi : ((LoopBeginNode) loop.getHeader().getBeginNode()).phis()) {
            if (phi.valueAt(0) != null) {
                ValueNode alias = getAliasAndResolve(initialState, phi.valueAt(0));
                if (alias instanceof VirtualObjectNode) {
                    VirtualObjectNode virtual = (VirtualObjectNode) alias;
                    addAndMarkAlias(virtual, phi);
                }
            }
        }
    }

    @Override
    protected void processLoopExit(LoopExitNode exitNode, BlockT initialState, BlockT exitState, GraphEffectList effects) {
        if (exitNode.graph().hasValueProxies()) {
            Map<Integer, ProxyNode> proxies = new IdentityHashMap<>();
            for (ProxyNode proxy : exitNode.proxies()) {
                ValueNode alias = getAlias(proxy.value());
                if (alias instanceof VirtualObjectNode) {
                    VirtualObjectNode virtual = (VirtualObjectNode) alias;
                    proxies.put(virtual.getObjectId(), proxy);
                }
            }
            for (int i = 0; i < exitState.getStateCount(); i++) {
                ObjectState exitObjState = exitState.getObjectStateOptional(i);
                if (exitObjState != null) {
                    ObjectState initialObjState = initialState.getObjectStateOptional(i);

                    if (exitObjState.isVirtual()) {
                        processVirtualAtLoopExit(exitNode, effects, i, exitObjState, initialObjState, exitState);
                    } else {
                        processMaterializedAtLoopExit(exitNode, effects, proxies, i, exitObjState, initialObjState, exitState);
                    }
                }
            }
        }
    }

    private static void processMaterializedAtLoopExit(LoopExitNode exitNode, GraphEffectList effects, Map<Integer, ProxyNode> proxies, int object, ObjectState exitObjState,
                    ObjectState initialObjState, PartialEscapeBlockState<?> exitState) {
        if (initialObjState == null || initialObjState.isVirtual()) {
            ProxyNode proxy = proxies.get(object);
            if (proxy == null) {
                proxy = new ValueProxyNode(exitObjState.getMaterializedValue(), exitNode);
                effects.addFloatingNode(proxy, "proxy");
            } else {
                effects.replaceFirstInput(proxy, proxy.value(), exitObjState.getMaterializedValue());
                // nothing to do - will be handled in processNode
            }
            exitState.updateMaterializedValue(object, proxy);
        } else {
            if (initialObjState.getMaterializedValue() != exitObjState.getMaterializedValue()) {
                Debug.log("materialized value changes within loop: %s vs. %s at %s", initialObjState.getMaterializedValue(), exitObjState.getMaterializedValue(), exitNode);
            }
        }
    }

    private static void processVirtualAtLoopExit(LoopExitNode exitNode, GraphEffectList effects, int object, ObjectState exitObjState, ObjectState initialObjState, PartialEscapeBlockState<?> exitState) {
        for (int i = 0; i < exitObjState.getEntries().length; i++) {
            ValueNode value = exitState.getObjectState(object).getEntry(i);
            if (!(value instanceof VirtualObjectNode || value.isConstant())) {
                if (exitNode.loopBegin().isPhiAtMerge(value) || initialObjState == null || !initialObjState.isVirtual() || initialObjState.getEntry(i) != value) {
                    ProxyNode proxy = new ValueProxyNode(value, exitNode);
                    exitState.setEntry(object, i, proxy);
                    effects.addFloatingNode(proxy, "virtualProxy");
                }
            }
        }
    }

    @Override
    protected MergeProcessor createMergeProcessor(Block merge) {
        return new MergeProcessor(merge);
    }

    protected class MergeProcessor extends EffectsClosure<BlockT>.MergeProcessor {

        private HashMap<Object, ValuePhiNode> materializedPhis;
        private Map<ValueNode, ValuePhiNode[]> valuePhis;
        private Map<ValuePhiNode, VirtualObjectNode> valueObjectVirtuals;
        private final boolean needsCaching;

        public MergeProcessor(Block mergeBlock) {
            super(mergeBlock);
            needsCaching = mergeBlock.isLoopHeader();
        }

        protected <T> PhiNode getPhi(T virtual, Stamp stamp) {
            if (needsCaching) {
                return getPhiCached(virtual, stamp);
            } else {
                return createValuePhi(stamp);
            }
        }

        private <T> PhiNode getPhiCached(T virtual, Stamp stamp) {
            if (materializedPhis == null) {
                materializedPhis = CollectionsFactory.newMap();
            }
            ValuePhiNode result = materializedPhis.get(virtual);
            if (result == null) {
                result = createValuePhi(stamp);
                materializedPhis.put(virtual, result);
            }
            return result;
        }

        private PhiNode[] getValuePhis(ValueNode key, int entryCount) {
            if (needsCaching) {
                return getValuePhisCached(key, entryCount);
            } else {
                return new ValuePhiNode[entryCount];
            }
        }

        private PhiNode[] getValuePhisCached(ValueNode key, int entryCount) {
            if (valuePhis == null) {
                valuePhis = Node.newIdentityMap();
            }
            ValuePhiNode[] result = valuePhis.get(key);
            if (result == null) {
                result = new ValuePhiNode[entryCount];
                valuePhis.put(key, result);
            }
            assert result.length == entryCount;
            return result;
        }

        private VirtualObjectNode getValueObjectVirtual(ValuePhiNode phi, VirtualObjectNode virtual) {
            if (needsCaching) {
                return getValueObjectVirtualCached(phi, virtual);
            } else {
                return virtual.duplicate();
            }
        }

        private VirtualObjectNode getValueObjectVirtualCached(ValuePhiNode phi, VirtualObjectNode virtual) {
            if (valueObjectVirtuals == null) {
                valueObjectVirtuals = Node.newIdentityMap();
            }
            VirtualObjectNode result = valueObjectVirtuals.get(phi);
            if (result == null) {
                result = virtual.duplicate();
                valueObjectVirtuals.put(phi, result);
            }
            return result;
        }

        /**
         * Merge all predecessor block states into one block state. This is an iterative process,
         * because merging states can lead to materializations which make previous parts of the
         * merging operation invalid. The merging process is executed until a stable state has been
         * reached. This method needs to be careful to place the effects of the merging operation
         * into the correct blocks.
         *
         * @param statesList the predecessor block states of the merge
         */
        @Override
        protected void merge(List<BlockT> statesList) {
            super.merge(statesList);

            PartialEscapeBlockState<?>[] states = new PartialEscapeBlockState<?>[statesList.size()];
            for (int i = 0; i < statesList.size(); i++) {
                states[i] = statesList.get(i);
            }

            // calculate the set of virtual objects that exist in all predecessors
            int[] virtualObjTemp = intersectVirtualObjects(states);

            boolean materialized;
            do {
                materialized = false;

                if (PartialEscapeBlockState.identicalObjectStates(states)) {
                    newState.adoptAddObjectStates(states[0]);
                } else {

                    for (int object : virtualObjTemp) {

                        if (PartialEscapeBlockState.identicalObjectStates(states, object)) {
                            newState.addObject(object, states[0].getObjectState(object).share());
                            continue;
                        }

                        // determine if all inputs are virtual or the same materialized value
                        int virtualCount = 0;
                        ObjectState startObj = states[0].getObjectState(object);
                        boolean locksMatch = true;
                        boolean ensureVirtual = true;
                        ValueNode uniqueMaterializedValue = startObj.isVirtual() ? null : startObj.getMaterializedValue();
                        for (int i = 0; i < states.length; i++) {
                            ObjectState obj = states[i].getObjectState(object);
                            ensureVirtual &= obj.getEnsureVirtualized();
                            if (obj.isVirtual()) {
                                virtualCount++;
                                uniqueMaterializedValue = null;
                                locksMatch &= obj.locksEqual(startObj);
                            } else if (obj.getMaterializedValue() != uniqueMaterializedValue) {
                                uniqueMaterializedValue = null;
                            }
                        }

                        if (virtualCount == states.length && locksMatch) {
                            materialized |= mergeObjectStates(object, null, states);
                        } else {
                            if (uniqueMaterializedValue != null) {
                                newState.addObject(object, new ObjectState(uniqueMaterializedValue, null, ensureVirtual));
                            } else {
                                PhiNode materializedValuePhi = getPhi(object, StampFactory.forKind(JavaKind.Object));
                                mergeEffects.addFloatingNode(materializedValuePhi, "materializedPhi");
                                for (int i = 0; i < states.length; i++) {
                                    ObjectState obj = states[i].getObjectState(object);
                                    if (obj.isVirtual()) {
                                        Block predecessor = getPredecessor(i);
                                        materialized |= ensureMaterialized(states[i], object, predecessor.getEndNode(), blockEffects.get(predecessor), METRIC_MATERIALIZATIONS_MERGE);
                                        obj = states[i].getObjectState(object);
                                    }
                                    setPhiInput(materializedValuePhi, i, obj.getMaterializedValue());
                                }
                                newState.addObject(object, new ObjectState(materializedValuePhi, null, false));
                            }
                        }
                    }
                }

                for (PhiNode phi : getPhis()) {
                    if (hasVirtualInputs.isMarked(phi) && phi instanceof ValuePhiNode) {
                        materialized |= processPhi((ValuePhiNode) phi, states, virtualObjTemp);
                    }
                }
                if (materialized) {
                    newState.resetObjectStates(virtualObjects.size());
                    mergeEffects.clear();
                    afterMergeEffects.clear();
                }
            } while (materialized);
        }

        private int[] intersectVirtualObjects(PartialEscapeBlockState<?>[] states) {
            int length = states[0].getStateCount();
            for (int i = 1; i < states.length; i++) {
                length = Math.min(length, states[i].getStateCount());
            }
            boolean[] result = new boolean[length];
            Arrays.fill(result, true);
            int count = length;
            for (int i = 0; i < states.length; i++) {
                PartialEscapeBlockState<?> state = states[i];
                for (int i2 = 0; i2 < length; i2++) {
                    if (result[i2]) {
                        if (state.getObjectStateOptional(i2) == null) {
                            result[i2] = false;
                            count--;
                        }
                    }
                }
            }
            int[] resultInts = new int[count];
            int index = 0;
            for (int i = 0; i < length; i++) {
                if (result[i]) {
                    resultInts[index++] = i;
                }
            }
            assert index == count;
            return resultInts;
        }

        /**
         * Try to merge multiple virtual object states into a single object state. If the incoming
         * object states are compatible, then this method will create PhiNodes for the object's
         * entries where needed. If they are incompatible, then all incoming virtual objects will be
         * materialized, and a PhiNode for the materialized values will be created. Object states
         * can be incompatible if they contain {@code long} or {@code double} values occupying two
         * {@code int} slots in such a way that that their values cannot be merged using PhiNodes.
         *
         * @param states the predecessor block states of the merge
         * @return true if materialization happened during the merge, false otherwise
         */
        private boolean mergeObjectStates(int resultObject, int[] sourceObjects, PartialEscapeBlockState<?>[] states) {
            boolean compatible = true;
            boolean ensureVirtual = true;
            IntFunction<Integer> getObject = index -> sourceObjects == null ? resultObject : sourceObjects[index];

            VirtualObjectNode virtual = virtualObjects.get(resultObject);
            int entryCount = virtual.entryCount();

            // determine all entries that have a two-slot value
            JavaKind[] twoSlotKinds = null;
            outer: for (int i = 0; i < states.length; i++) {
                ObjectState objectState = states[i].getObjectState(getObject.apply(i));
                ValueNode[] entries = objectState.getEntries();
                int valueIndex = 0;
                ensureVirtual &= objectState.getEnsureVirtualized();
                while (valueIndex < entryCount) {
                    JavaKind otherKind = entries[valueIndex].getStackKind();
                    JavaKind entryKind = virtual.entryKind(valueIndex);
                    if (entryKind == JavaKind.Int && otherKind.needsTwoSlots()) {
                        if (twoSlotKinds == null) {
                            twoSlotKinds = new JavaKind[entryCount];
                        }
                        if (twoSlotKinds[valueIndex] != null && twoSlotKinds[valueIndex] != otherKind) {
                            compatible = false;
                            break outer;
                        }
                        twoSlotKinds[valueIndex] = otherKind;
                        // skip the next entry
                        valueIndex++;
                    } else {
                        assert entryKind.getStackKind() == otherKind.getStackKind() || (entryKind == JavaKind.Int && otherKind == JavaKind.Illegal) ||
                                        entryKind.getBitCount() >= otherKind.getBitCount() : entryKind + " vs " + otherKind;
                    }
                    valueIndex++;
                }
            }
            if (compatible && twoSlotKinds != null) {
                // if there are two-slot values then make sure the incoming states can be merged
                outer: for (int valueIndex = 0; valueIndex < entryCount; valueIndex++) {
                    if (twoSlotKinds[valueIndex] != null) {
                        assert valueIndex < virtual.entryCount() - 1 && virtual.entryKind(valueIndex) == JavaKind.Int && virtual.entryKind(valueIndex + 1) == JavaKind.Int;
                        for (int i = 0; i < states.length; i++) {
                            int object = getObject.apply(i);
                            ObjectState objectState = states[i].getObjectState(object);
                            ValueNode value = objectState.getEntry(valueIndex);
                            JavaKind valueKind = value.getStackKind();
                            if (valueKind != twoSlotKinds[valueIndex]) {
                                ValueNode nextValue = objectState.getEntry(valueIndex + 1);
                                if (value.isConstant() && value.asConstant().equals(JavaConstant.INT_0) && nextValue.isConstant() && nextValue.asConstant().equals(JavaConstant.INT_0)) {
                                    // rewrite to a zero constant of the larger kind
                                    states[i].setEntry(object, valueIndex, ConstantNode.defaultForKind(twoSlotKinds[valueIndex], graph()));
                                    states[i].setEntry(object, valueIndex + 1, ConstantNode.forConstant(JavaConstant.forIllegal(), tool.getMetaAccessProvider(), graph()));
                                } else {
                                    compatible = false;
                                    break outer;
                                }
                            }
                        }
                    }
                }
            }

            if (compatible) {
                // virtual objects are compatible: create phis for all entries that need them
                ValueNode[] values = states[0].getObjectState(getObject.apply(0)).getEntries().clone();
                PhiNode[] phis = getValuePhis(virtual, virtual.entryCount());
                int valueIndex = 0;
                while (valueIndex < values.length) {
                    for (int i = 1; i < states.length; i++) {
                        if (phis[valueIndex] == null) {
                            ValueNode field = states[i].getObjectState(getObject.apply(i)).getEntry(valueIndex);
                            if (values[valueIndex] != field) {
                                phis[valueIndex] = createValuePhi(values[valueIndex].stamp().unrestricted());
                            }
                        }
                    }
                    if (phis[valueIndex] != null && !phis[valueIndex].stamp().isCompatible(values[valueIndex].stamp())) {
                        phis[valueIndex] = createValuePhi(values[valueIndex].stamp().unrestricted());
                    }
                    if (twoSlotKinds != null && twoSlotKinds[valueIndex] != null) {
                        // skip an entry after a long/double value that occupies two int slots
                        valueIndex++;
                        phis[valueIndex] = null;
                        values[valueIndex] = ConstantNode.forConstant(JavaConstant.forIllegal(), tool.getMetaAccessProvider(), graph());
                    }
                    valueIndex++;
                }

                boolean materialized = false;
                for (int i = 0; i < values.length; i++) {
                    PhiNode phi = phis[i];
                    if (phi != null) {
                        mergeEffects.addFloatingNode(phi, "virtualMergePhi");
                        if (virtual.entryKind(i) == JavaKind.Object) {
                            materialized |= mergeObjectEntry(getObject, states, phi, i);
                        } else {
                            for (int i2 = 0; i2 < states.length; i2++) {
                                ObjectState state = states[i2].getObjectState(getObject.apply(i2));
                                if (!state.isVirtual()) {
                                    break;
                                }
                                setPhiInput(phi, i2, state.getEntry(i));
                            }
                        }
                        values[i] = phi;
                    }
                }
                newState.addObject(resultObject, new ObjectState(values, states[0].getObjectState(getObject.apply(0)).getLocks(), ensureVirtual));
                return materialized;
            } else {
                // not compatible: materialize in all predecessors
                PhiNode materializedValuePhi = getPhi(resultObject, StampFactory.forKind(JavaKind.Object));
                for (int i = 0; i < states.length; i++) {
                    Block predecessor = getPredecessor(i);
                    ensureMaterialized(states[i], getObject.apply(i), predecessor.getEndNode(), blockEffects.get(predecessor), METRIC_MATERIALIZATIONS_MERGE);
                    setPhiInput(materializedValuePhi, i, states[i].getObjectState(getObject.apply(i)).getMaterializedValue());
                }
                newState.addObject(resultObject, new ObjectState(materializedValuePhi, null, ensureVirtual));
                return true;
            }
        }

        /**
         * Fill the inputs of the PhiNode corresponding to one {@link JavaKind#Object} entry in the
         * virtual object.
         *
         * @return true if materialization happened during the merge, false otherwise
         */
        private boolean mergeObjectEntry(IntFunction<Integer> objectIdFunc, PartialEscapeBlockState<?>[] states, PhiNode phi, int entryIndex) {
            boolean materialized = false;
            for (int i = 0; i < states.length; i++) {
                int object = objectIdFunc.apply(i);
                ObjectState objectState = states[i].getObjectState(object);
                if (!objectState.isVirtual()) {
                    break;
                }
                ValueNode entry = objectState.getEntry(entryIndex);
                if (entry instanceof VirtualObjectNode) {
                    VirtualObjectNode entryVirtual = (VirtualObjectNode) entry;
                    Block predecessor = getPredecessor(i);
                    materialized |= ensureMaterialized(states[i], entryVirtual.getObjectId(), predecessor.getEndNode(), blockEffects.get(predecessor), METRIC_MATERIALIZATIONS_MERGE);
                    objectState = states[i].getObjectState(object);
                    if (objectState.isVirtual()) {
                        states[i].setEntry(object, entryIndex, entry = states[i].getObjectState(entryVirtual.getObjectId()).getMaterializedValue());
                    }
                }
                setPhiInput(phi, i, entry);
            }
            return materialized;
        }

        /**
         * Examine a PhiNode and try to replace it with merging of virtual objects if all its inputs
         * refer to virtual object states. In order for the merging to happen, all incoming object
         * states need to be compatible and without object identity (meaning that their object
         * identity if not used later on).
         *
         * @param phi the PhiNode that should be processed
         * @param states the predecessor block states of the merge
         * @param mergedVirtualObjects the set of virtual objects that exist in all incoming states,
         *            and therefore also exist in the merged state
         * @return true if materialization happened during the merge, false otherwise
         */
        private boolean processPhi(ValuePhiNode phi, PartialEscapeBlockState<?>[] states, int[] mergedVirtualObjects) {
            aliases.set(phi, null);

            // determine how many inputs are virtual and if they're all the same virtual object
            int virtualInputs = 0;
            boolean uniqueVirtualObject = true;
            VirtualObjectNode[] virtualObjs = new VirtualObjectNode[states.length];
            for (int i = 0; i < states.length; i++) {
                ValueNode alias = getAlias(getPhiValueAt(phi, i));
                if (alias instanceof VirtualObjectNode) {
                    VirtualObjectNode virtual = (VirtualObjectNode) alias;
                    virtualObjs[i] = virtual;
                    ObjectState objectState = states[i].getObjectStateOptional(virtual);
                    if (objectState == null) {
                        assert getPhiValueAt(phi, i) instanceof PhiNode : "this should only happen for phi nodes";
                        return false;
                    }
                    if (objectState.isVirtual()) {
                        if (virtualObjs[0] != alias) {
                            uniqueVirtualObject = false;
                        }
                        virtualInputs++;
                    }
                }
            }
            if (virtualInputs == states.length) {
                if (uniqueVirtualObject) {
                    // all inputs refer to the same object: just make the phi node an alias
                    addAndMarkAlias(virtualObjs[0], phi);
                    mergeEffects.deleteNode(phi);
                    return false;
                } else {
                    // all inputs are virtual: check if they're compatible and without identity
                    boolean compatible = true;
                    boolean hasIdentity = false;
                    VirtualObjectNode firstVirtual = virtualObjs[0];
                    for (int i = 0; i < states.length; i++) {
                        VirtualObjectNode virtual = virtualObjs[i];
                        hasIdentity |= virtual.hasIdentity();
                        boolean identitySurvives = virtual.hasIdentity() && Arrays.asList(mergedVirtualObjects).contains(virtual.getObjectId());
                        if (identitySurvives || !firstVirtual.type().equals(virtual.type()) || firstVirtual.entryCount() != virtual.entryCount()) {
                            compatible = false;
                            break;
                        }
                        if (!states[0].getObjectState(firstVirtual).locksEqual(states[i].getObjectState(virtual))) {
                            compatible = false;
                            break;
                        }
                    }
                    if (compatible && hasIdentity) {
                        // we still need to check whether this value is referenced by any other phi
                        outer: for (PhiNode otherPhi : getPhis().filter(otherPhi -> otherPhi != phi)) {
                            for (int i = 0; i < states.length; i++) {
                                ValueNode alias = getAliasAndResolve(states[i], getPhiValueAt(otherPhi, i));
                                if (alias instanceof VirtualObjectNode) {
                                    VirtualObjectNode phiValueVirtual = (VirtualObjectNode) alias;
                                    if (Arrays.asList(virtualObjs).contains(phiValueVirtual)) {
                                        compatible = false;
                                        break outer;
                                    }
                                }
                            }
                        }
                    }

                    if (compatible) {
                        VirtualObjectNode virtual = getValueObjectVirtual(phi, virtualObjs[0]);
                        mergeEffects.addFloatingNode(virtual, "valueObjectNode");
                        mergeEffects.deleteNode(phi);
                        if (virtual.getObjectId() == -1) {
                            int id = virtualObjects.size();
                            virtualObjects.add(virtual);
                            virtual.setObjectId(id);
                        }

                        int[] virtualObjectIds = new int[states.length];
                        for (int i = 0; i < states.length; i++) {
                            virtualObjectIds[i] = virtualObjs[i].getObjectId();
                        }
                        boolean materialized = mergeObjectStates(virtual.getObjectId(), virtualObjectIds, states);
                        addAndMarkAlias(virtual, virtual);
                        addAndMarkAlias(virtual, phi);
                        return materialized;
                    }
                }
            }

            // otherwise: materialize all phi inputs
            boolean materialized = false;
            for (int i = 0; i < states.length; i++) {
                VirtualObjectNode virtual = virtualObjs[i];
                if (virtual != null) {
                    Block predecessor = getPredecessor(i);
                    materialized |= ensureMaterialized(states[i], virtual.getObjectId(), predecessor.getEndNode(), blockEffects.get(predecessor), METRIC_MATERIALIZATIONS_PHI);
                    setPhiInput(phi, i, getAliasAndResolve(states[i], virtual));
                }
            }
            return materialized;
        }
    }

    public ObjectState getObjectState(PartialEscapeBlockState<?> state, ValueNode value) {
        if (value == null) {
            return null;
        }
        if (value.isAlive() && !aliases.isNew(value)) {
            ValueNode object = aliases.get(value);
            return object instanceof VirtualObjectNode ? state.getObjectStateOptional((VirtualObjectNode) object) : null;
        } else {
            if (value instanceof VirtualObjectNode) {
                return state.getObjectStateOptional((VirtualObjectNode) value);
            }
            return null;
        }
    }

    public ValueNode getAlias(ValueNode value) {
        if (value != null && !(value instanceof VirtualObjectNode)) {
            if (value.isAlive() && !aliases.isNew(value)) {
                ValueNode result = aliases.get(value);
                if (result != null) {
                    return result;
                }
            }
        }
        return value;
    }

    public ValueNode getAliasAndResolve(PartialEscapeBlockState<?> state, ValueNode value) {
        ValueNode result = getAlias(value);
        if (result instanceof VirtualObjectNode) {
            int id = ((VirtualObjectNode) result).getObjectId();
            if (id != -1 && !state.getObjectState(id).isVirtual()) {
                result = state.getObjectState(id).getMaterializedValue();
            }
        }
        return result;
    }

    void addAndMarkAlias(VirtualObjectNode virtual, ValueNode node) {
        if (node.isAlive()) {
            aliases.set(node, virtual);
            for (Node usage : node.usages()) {
                markVirtualUsages(usage);
            }
        }
    }

    private void markVirtualUsages(Node node) {
        if (!hasVirtualInputs.isNew(node) && !hasVirtualInputs.isMarked(node)) {
            hasVirtualInputs.mark(node);
            if (node instanceof VirtualState) {
                for (Node usage : node.usages()) {
                    markVirtualUsages(usage);
                }
            }
        }
    }
}
