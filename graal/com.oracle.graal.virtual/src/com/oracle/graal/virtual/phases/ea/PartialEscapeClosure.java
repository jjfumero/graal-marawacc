/*
 * Copyright (c) 2011, 2012, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.virtual.phases.ea.PartialEscapeAnalysisPhase.*;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.PhiNode.PhiType;
import com.oracle.graal.nodes.VirtualState.NodeClosure;
import com.oracle.graal.nodes.cfg.*;
import com.oracle.graal.nodes.extended.LocationNode.LocationIdentity;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.spi.Virtualizable.EscapeState;
import com.oracle.graal.nodes.virtual.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.graph.*;
import com.oracle.graal.phases.graph.ReentrantBlockIterator.BlockIteratorClosure;
import com.oracle.graal.phases.graph.ReentrantBlockIterator.LoopInfo;
import com.oracle.graal.phases.schedule.*;
import com.oracle.graal.virtual.nodes.*;
import com.oracle.graal.virtual.phases.ea.BlockState.ReadCacheEntry;
import com.oracle.graal.virtual.phases.ea.EffectList.Effect;

class PartialEscapeClosure extends BlockIteratorClosure<BlockState> {

    public static final DebugMetric METRIC_MATERIALIZATIONS = Debug.metric("Materializations");
    public static final DebugMetric METRIC_MATERIALIZATIONS_PHI = Debug.metric("MaterializationsPhi");
    public static final DebugMetric METRIC_MATERIALIZATIONS_MERGE = Debug.metric("MaterializationsMerge");
    public static final DebugMetric METRIC_MATERIALIZATIONS_UNHANDLED = Debug.metric("MaterializationsUnhandled");
    public static final DebugMetric METRIC_MATERIALIZATIONS_LOOP_REITERATION = Debug.metric("MaterializationsLoopReiteration");
    public static final DebugMetric METRIC_MATERIALIZATIONS_LOOP_END = Debug.metric("MaterializationsLoopEnd");
    public static final DebugMetric METRIC_ALLOCATION_REMOVED = Debug.metric("AllocationsRemoved");

    public static final DebugMetric METRIC_STOREFIELD_RECORDED = Debug.metric("StoreFieldRecorded");
    public static final DebugMetric METRIC_LOADFIELD_ELIMINATED = Debug.metric("LoadFieldEliminated");
    public static final DebugMetric METRIC_LOADFIELD_NOT_ELIMINATED = Debug.metric("LoadFieldNotEliminated");
    public static final DebugMetric METRIC_MEMORYCHECKOINT = Debug.metric("MemoryCheckpoint");

    private final NodeBitMap usages;
    private final SchedulePhase schedule;

    private final BlockMap<GraphEffectList> blockEffects;
    private final IdentityHashMap<Loop, GraphEffectList> loopMergeEffects = new IdentityHashMap<>();

    private final VirtualizerToolImpl tool;

    private final Map<Invoke, Double> hints = new IdentityHashMap<>();

    private boolean changed;

    public PartialEscapeClosure(NodeBitMap usages, SchedulePhase schedule, MetaAccessProvider metaAccess, Assumptions assumptions) {
        this.usages = usages;
        this.schedule = schedule;
        this.tool = new VirtualizerToolImpl(usages, metaAccess, assumptions);
        this.blockEffects = new BlockMap<>(schedule.getCFG());
        for (Block block : schedule.getCFG().getBlocks()) {
            blockEffects.put(block, new GraphEffectList());
        }
    }

    public boolean hasChanged() {
        return changed;
    }

    public List<Node> applyEffects(final StructuredGraph graph) {
        final ArrayList<Node> obsoleteNodes = new ArrayList<>();
        BlockIteratorClosure<Void> closure = new BlockIteratorClosure<Void>() {

            private void apply(GraphEffectList effects, Object context) {
                if (!effects.isEmpty()) {
                    Debug.log(" ==== effects for %s", context);
                    for (Effect effect : effects) {
                        effect.apply(graph, obsoleteNodes);
                        if (effect.isVisible()) {
                            Debug.log("    %s", effect);
                        }
                    }
                }
            }

            @Override
            protected Void processBlock(Block block, Void currentState) {
                apply(blockEffects.get(block), block);
                return currentState;
            }

            @Override
            protected Void merge(Block merge, List<Void> states) {
                return null;
            }

            @Override
            protected Void cloneState(Void oldState) {
                return oldState;
            }

            @Override
            protected List<Void> processLoop(Loop loop, Void initialState) {
                LoopInfo<Void> info = ReentrantBlockIterator.processLoop(this, loop, initialState);
                apply(loopMergeEffects.get(loop), loop);
                return info.exitStates;
            }
        };
        ReentrantBlockIterator.apply(closure, schedule.getCFG().getStartBlock(), null, null);
        return obsoleteNodes;
    }

    public Map<Invoke, Double> getHints() {
        return hints;
    }

    @Override
    protected BlockState processBlock(Block block, BlockState state) {
        GraphEffectList effects = blockEffects.get(block);
        tool.setEffects(effects);

        trace("\nBlock: %s (", block);
        List<ScheduledNode> nodeList = schedule.getBlockToNodesMap().get(block);

        FixedWithNextNode lastFixedNode = null;
        for (Node node : nodeList) {
            boolean deleted;
            if (usages.isMarked(node) || node instanceof VirtualizableAllocation) {
                trace("[[%s]] ", node);
                deleted = processNode((ValueNode) node, lastFixedNode == null ? null : lastFixedNode.next(), state, effects);
            } else {
                trace("%s ", node);
                deleted = false;
            }
            if (GraalOptions.OptEarlyReadElimination) {
                if (!deleted) {
                    if (node instanceof StoreFieldNode) {
                        METRIC_STOREFIELD_RECORDED.increment();
                        StoreFieldNode store = (StoreFieldNode) node;
                        ValueNode cachedValue = state.getReadCache(store.object(), (LocationIdentity) store.field());
                        state.killReadCache((LocationIdentity) store.field());

                        if (cachedValue == store.value()) {
                            effects.deleteFixedNode(store);
                            changed = true;
                        } else {
                            state.addReadCache(store.object(), (LocationIdentity) store.field(), store.value());
                        }
                    } else if (node instanceof LoadFieldNode) {
                        LoadFieldNode load = (LoadFieldNode) node;
                        ValueNode cachedValue = state.getReadCache(load.object(), (LocationIdentity) load.field());
                        if (cachedValue != null) {
                            METRIC_LOADFIELD_ELIMINATED.increment();
                            effects.replaceAtUsages(load, cachedValue);
                            state.addScalarAlias(load, cachedValue);
                            changed = true;
                        } else {
                            METRIC_LOADFIELD_NOT_ELIMINATED.increment();
                            state.addReadCache(load.object(), (LocationIdentity) load.field(), load);
                        }
                    } else if (node instanceof MemoryCheckpoint) {
                        METRIC_MEMORYCHECKOINT.increment();
                        MemoryCheckpoint checkpoint = (MemoryCheckpoint) node;
                        for (LocationIdentity identity : checkpoint.getLocationIdentities()) {
                            state.killReadCache(identity);
                        }
                    }
                }
            }
            if (node instanceof FixedWithNextNode) {
                lastFixedNode = (FixedWithNextNode) node;
            }
        }
        trace(")\n    end state: %s\n", state);
        return state;
    }

    private boolean processNode(final ValueNode node, FixedNode insertBefore, final BlockState state, final GraphEffectList effects) {
        tool.reset(state, node);
        if (node instanceof Virtualizable) {
            ((Virtualizable) node).virtualize(tool);
        }
        if (tool.isDeleted()) {
            if (tool.isCustomAction() || !(node instanceof VirtualizableAllocation || node instanceof CyclicMaterializeStoreNode)) {
                changed = true;
            }
            return true;
        }
        if (node instanceof StateSplit) {
            StateSplit split = (StateSplit) node;
            FrameState stateAfter = split.stateAfter();
            if (stateAfter != null) {
                if (stateAfter.usages().count() > 1) {
                    stateAfter = (FrameState) stateAfter.copyWithInputs();
                    split.setStateAfter(stateAfter);
                }
                final HashSet<ObjectState> virtual = new HashSet<>();
                stateAfter.applyToNonVirtual(new NodeClosure<ValueNode>() {

                    @Override
                    public void apply(Node usage, ValueNode value) {
                        ObjectState valueObj = state.getObjectState(value);
                        if (valueObj != null) {
                            virtual.add(valueObj);
                            effects.replaceFirstInput(usage, value, valueObj.virtual);
                        } else if (value instanceof VirtualObjectNode) {
                            ObjectState virtualObj = null;
                            for (ObjectState obj : state.getStates()) {
                                if (value == obj.virtual) {
                                    virtualObj = obj;
                                    break;
                                }
                            }
                            if (virtualObj != null) {
                                virtual.add(virtualObj);
                            }
                        }
                    }
                });
                for (ObjectState obj : state.getStates()) {
                    if (obj.isVirtual() && obj.hasLocks()) {
                        virtual.add(obj);
                    }
                }

                ArrayDeque<ObjectState> queue = new ArrayDeque<>(virtual);
                while (!queue.isEmpty()) {
                    ObjectState obj = queue.removeLast();
                    if (obj.isVirtual()) {
                        for (ValueNode field : obj.getEntries()) {
                            ObjectState fieldObj = state.getObjectState(field);
                            if (fieldObj != null) {
                                if (fieldObj.isVirtual() && !virtual.contains(fieldObj)) {
                                    virtual.add(fieldObj);
                                    queue.addLast(fieldObj);
                                }
                            }
                        }
                    }
                }
                for (ObjectState obj : virtual) {
                    EscapeObjectState v;
                    if (obj.isVirtual()) {
                        ValueNode[] fieldState = obj.getEntries().clone();
                        for (int i = 0; i < fieldState.length; i++) {
                            ObjectState valueObj = state.getObjectState(fieldState[i]);
                            if (valueObj != null) {
                                if (valueObj.isVirtual()) {
                                    fieldState[i] = valueObj.virtual;
                                } else {
                                    fieldState[i] = valueObj.getMaterializedValue();
                                }
                            }
                        }
                        v = new VirtualObjectState(obj.virtual, fieldState);
                    } else {
                        v = new MaterializedObjectState(obj.virtual, obj.getMaterializedValue());
                    }
                    effects.addVirtualMapping(stateAfter, v);
                }
            }
        }
        if (tool.isCustomAction()) {
            return false;
        }
        for (ValueNode input : node.inputs().filter(ValueNode.class)) {
            ObjectState obj = state.getObjectState(input);
            if (obj != null) {
                if (obj.isVirtual() && node instanceof MethodCallTargetNode) {
                    Invoke invoke = ((MethodCallTargetNode) node).invoke();
                    hints.put(invoke, 5d);
                }
                trace("replacing input %s at %s: %s", input, node, obj);
                replaceWithMaterialized(input, node, insertBefore, state, obj, effects, METRIC_MATERIALIZATIONS_UNHANDLED);
            }
        }
        return false;
    }

    private static void ensureMaterialized(BlockState state, ObjectState obj, FixedNode materializeBefore, GraphEffectList effects, DebugMetric metric) {
        assert obj != null;
        if (obj.getState() == EscapeState.Virtual) {
            metric.increment();
            state.materializeBefore(materializeBefore, obj.virtual, EscapeState.Global, effects);
        } else {
            assert obj.getState() == EscapeState.Global;
        }
        assert !obj.isVirtual();
    }

    private static void replaceWithMaterialized(ValueNode value, Node usage, FixedNode materializeBefore, BlockState state, ObjectState obj, GraphEffectList effects, DebugMetric metric) {
        ensureMaterialized(state, obj, materializeBefore, effects, metric);
        effects.replaceFirstInput(usage, value, obj.getMaterializedValue());
    }

    @Override
    protected BlockState merge(Block merge, List<BlockState> states) {
        assert blockEffects.get(merge).isEmpty();
        MergeProcessor processor = new MergeProcessor(merge, usages, blockEffects);
        processor.merge(states);
        blockEffects.get(merge).addAll(processor.mergeEffects);
        blockEffects.get(merge).addAll(processor.afterMergeEffects);
        return processor.newState;

    }

    @Override
    protected BlockState cloneState(BlockState oldState) {
        return oldState.cloneState();
    }

    @Override
    protected List<BlockState> processLoop(Loop loop, BlockState initialState) {
        BlockState loopEntryState = initialState;
        BlockState lastMergedState = initialState;
        MergeProcessor mergeProcessor = new MergeProcessor(loop.header, usages, blockEffects);
        for (int iteration = 0; iteration < 10; iteration++) {
            LoopInfo<BlockState> info = ReentrantBlockIterator.processLoop(this, loop, lastMergedState.cloneState());

            List<BlockState> states = new ArrayList<>();
            states.add(initialState);
            states.addAll(info.endStates);
            mergeProcessor.merge(states);

            Debug.log("================== %s", loop.header);
            Debug.log("%s", mergeProcessor.newState);
            Debug.log("===== vs.");
            Debug.log("%s", lastMergedState);

            if (mergeProcessor.newState.equivalentTo(lastMergedState)) {
                blockEffects.get(loop.header).insertAll(mergeProcessor.mergeEffects, 0);
                loopMergeEffects.put(loop, mergeProcessor.afterMergeEffects);

                assert info.exitStates.size() == loop.exits.size();
                for (int i = 0; i < loop.exits.size(); i++) {
                    BlockState exitState = info.exitStates.get(i);
                    assert exitState != null : "no loop exit state at " + loop.exits.get(i) + " / " + loop.header;
                    processLoopExit((LoopExitNode) loop.exits.get(i).getBeginNode(), loopEntryState, exitState, blockEffects.get(loop.exits.get(i)));
                }

                return info.exitStates;
            } else {
                lastMergedState = mergeProcessor.newState;
                for (Block block : loop.blocks) {
                    blockEffects.get(block).clear();
                }
            }
        }
        throw new GraalInternalError("too many iterations at %s", loop);
    }

    private static void processLoopExit(LoopExitNode exitNode, BlockState initialState, BlockState exitState, GraphEffectList effects) {
        HashMap<VirtualObjectNode, ProxyNode> proxies = new HashMap<>();

        for (ProxyNode proxy : exitNode.proxies()) {
            ObjectState obj = exitState.getObjectState(proxy.value());
            if (obj != null) {
                proxies.put(obj.virtual, proxy);
            }
        }
        for (ObjectState obj : exitState.getStates()) {
            ObjectState initialObj = initialState.getObjectStateOptional(obj.virtual);
            if (obj.isVirtual()) {
                for (int i = 0; i < obj.getEntries().length; i++) {
                    ValueNode value = obj.getEntry(i);
                    ObjectState valueObj = exitState.getObjectState(value);
                    if (valueObj == null) {
                        if (exitNode.loopBegin().isPhiAtMerge(value) || initialObj == null || !initialObj.isVirtual() || initialObj.getEntry(i) != value) {
                            ProxyNode proxy = new ProxyNode(value, exitNode, PhiType.Value, null);
                            obj.setEntry(i, proxy);
                            effects.addFloatingNode(proxy, "virtualProxy");
                        }
                    }
                }
            } else {
                if (initialObj == null || initialObj.isVirtual()) {
                    ProxyNode proxy = proxies.get(obj.virtual);
                    if (proxy == null) {
                        proxy = new ProxyNode(obj.getMaterializedValue(), exitNode, PhiType.Value, null);
                        effects.addFloatingNode(proxy, "proxy");
                    } else {
                        effects.replaceFirstInput(proxy, proxy.value(), obj.getMaterializedValue());
                        // nothing to do - will be handled in processNode
                    }
                    obj.updateMaterializedValue(proxy);
                } else {
                    if (initialObj.getMaterializedValue() == obj.getMaterializedValue()) {
                        Debug.log("materialized value changes within loop: %s vs. %s at %s", initialObj.getMaterializedValue(), obj.getMaterializedValue(), exitNode);
                    }
                }
            }
        }

        for (Map.Entry<ReadCacheEntry, ValueNode> entry : exitState.getReadCache().entrySet()) {
            if (initialState.getReadCache().get(entry.getKey()) != entry.getValue()) {
                ProxyNode proxy = new ProxyNode(exitState.getReadCache(entry.getKey().object, entry.getKey().identity), exitNode, PhiType.Value, null);
                effects.addFloatingNode(proxy, "readCacheProxy");
                entry.setValue(proxy);
            }
        }
    }

    private static class MergeProcessor {

        private final Block mergeBlock;
        private final MergeNode merge;
        private final NodeBitMap usages;
        private final BlockMap<GraphEffectList> blockEffects;
        private final GraphEffectList mergeEffects;
        private final GraphEffectList afterMergeEffects;

        private final HashMap<Object, PhiNode> materializedPhis = new HashMap<>();
        private final IdentityHashMap<VirtualObjectNode, PhiNode[]> valuePhis = new IdentityHashMap<>();
        private final IdentityHashMap<PhiNode, PhiNode[]> valueObjectMergePhis = new IdentityHashMap<>();
        private final IdentityHashMap<PhiNode, VirtualObjectNode> valueObjectVirtuals = new IdentityHashMap<>();
        private BlockState newState;

        public MergeProcessor(Block mergeBlock, NodeBitMap usages, BlockMap<GraphEffectList> blockEffects) {
            this.usages = usages;
            this.mergeBlock = mergeBlock;
            this.blockEffects = blockEffects;
            this.merge = (MergeNode) mergeBlock.getBeginNode();
            this.mergeEffects = new GraphEffectList();
            this.afterMergeEffects = new GraphEffectList();
        }

        private <T> PhiNode getCachedPhi(T virtual, Kind kind) {
            PhiNode result = materializedPhis.get(virtual);
            if (result == null) {
                result = new PhiNode(kind, merge);
                materializedPhis.put(virtual, result);
            }
            return result;
        }

        private PhiNode[] getValuePhis(VirtualObjectNode virtual) {
            PhiNode[] result = valuePhis.get(virtual);
            if (result == null) {
                result = new PhiNode[virtual.entryCount()];
                valuePhis.put(virtual, result);
            }
            return result;
        }

        private PhiNode[] getValueObjectMergePhis(PhiNode phi, int entryCount) {
            PhiNode[] result = valueObjectMergePhis.get(phi);
            if (result == null) {
                result = new PhiNode[entryCount];
                valueObjectMergePhis.put(phi, result);
            }
            return result;
        }

        private VirtualObjectNode getValueObjectVirtual(PhiNode phi, VirtualObjectNode virtual) {
            VirtualObjectNode result = valueObjectVirtuals.get(phi);
            if (result == null) {
                result = virtual.duplicate();
                valueObjectVirtuals.put(phi, result);
            }
            return result;
        }

        private void merge(List<BlockState> states) {
            newState = BlockState.meetAliases(states);

            /*
             * Iterative processing: Merging the materialized/virtual state of virtual objects can
             * lead to new materializations, which can lead to new materializations because of phis,
             * and so on.
             */

            HashSet<VirtualObjectNode> virtualObjects = new HashSet<>(newState.getVirtualObjects());
            boolean materialized;
            do {
                mergeEffects.clear();
                afterMergeEffects.clear();
                materialized = false;
                for (VirtualObjectNode object : virtualObjects) {
                    ObjectState[] objStates = new ObjectState[states.size()];
                    for (int i = 0; i < states.size(); i++) {
                        objStates[i] = states.get(i).getObjectState(object);
                    }
                    int virtual = 0;
                    ObjectState startObj = objStates[0];
                    boolean locksMatch = true;
                    ValueNode singleValue = startObj.isVirtual() ? null : startObj.getMaterializedValue();
                    for (ObjectState obj : objStates) {
                        if (obj.isVirtual()) {
                            virtual++;
                            singleValue = null;
                        } else {
                            if (obj.getMaterializedValue() != singleValue) {
                                singleValue = null;
                            }
                        }
                        locksMatch &= obj.locksEqual(startObj);
                    }

                    assert virtual < states.size() || locksMatch : "mismatching lock counts at " + merge;

                    if (virtual < states.size()) {
                        if (singleValue == null) {
                            PhiNode materializedValuePhi = getCachedPhi(object, Kind.Object);
                            mergeEffects.addFloatingNode(materializedValuePhi, "materializedPhi");
                            for (int i = 0; i < states.size(); i++) {
                                BlockState state = states.get(i);
                                ObjectState obj = objStates[i];
                                materialized |= obj.isVirtual();
                                Block predecessor = mergeBlock.getPredecessors().get(i);
                                ensureMaterialized(state, obj, predecessor.getEndNode(), blockEffects.get(predecessor), METRIC_MATERIALIZATIONS_MERGE);
                                afterMergeEffects.addPhiInput(materializedValuePhi, obj.getMaterializedValue());
                            }
                            newState.addObject(object, new ObjectState(object, materializedValuePhi, EscapeState.Global, null));
                        } else {
                            newState.addObject(object, new ObjectState(object, singleValue, EscapeState.Global, null));
                        }
                    } else {
                        assert virtual == states.size();
                        ValueNode[] values = startObj.getEntries().clone();
                        PhiNode[] phis = getValuePhis(object);
                        for (int index = 0; index < values.length; index++) {
                            for (int i = 1; i < states.size(); i++) {
                                ValueNode[] fields = objStates[i].getEntries();
                                if (phis[index] == null && values[index] != fields[index]) {
                                    phis[index] = new PhiNode(values[index].kind(), merge);
                                }
                            }
                        }
                        outer: for (int index = 0; index < values.length; index++) {
                            if (phis[index] != null) {
                                mergeEffects.addFloatingNode(phis[index], "virtualMergePhi");
                                for (int i = 0; i < states.size(); i++) {
                                    if (!objStates[i].isVirtual()) {
                                        break outer;
                                    }
                                    ValueNode[] fields = objStates[i].getEntries();
                                    ObjectState obj = states.get(i).getObjectState(fields[index]);
                                    if (obj != null) {
                                        materialized |= obj.isVirtual();
                                        Block predecessor = mergeBlock.getPredecessors().get(i);
                                        ensureMaterialized(states.get(i), obj, predecessor.getEndNode(), blockEffects.get(predecessor), METRIC_MATERIALIZATIONS_MERGE);
                                        fields[index] = obj.getMaterializedValue();
                                    }
                                    afterMergeEffects.addPhiInput(phis[index], fields[index]);
                                }
                                values[index] = phis[index];
                            }
                        }
                        newState.addObject(object, new ObjectState(object, values, EscapeState.Virtual, startObj.getLocks()));
                    }
                }

                for (PhiNode phi : merge.phis()) {
                    if (usages.isMarked(phi) && phi.type() == PhiType.Value) {
                        materialized |= processPhi(phi, states);
                    }
                }
            } while (materialized);

            mergeReadCache(states);
        }

        private boolean processPhi(PhiNode phi, List<BlockState> states) {
            assert states.size() == phi.valueCount();
            int virtualInputs = 0;
            boolean materialized = false;
            VirtualObjectNode sameObject = null;
            ResolvedJavaType sameType = null;
            int sameEntryCount = -1;
            boolean hasIdentity = false;
            for (int i = 0; i < phi.valueCount(); i++) {
                ValueNode value = phi.valueAt(i);
                ObjectState obj = states.get(i).getObjectState(value);
                if (obj != null) {
                    if (obj.isVirtual()) {
                        virtualInputs++;
                        if (i == 0) {
                            sameObject = obj.virtual;
                            sameType = obj.virtual.type();
                            sameEntryCount = obj.virtual.entryCount();
                        } else {
                            if (sameObject != obj.virtual) {
                                sameObject = null;
                            }
                            if (sameType != obj.virtual.type()) {
                                sameType = null;
                            }
                            if (sameEntryCount != obj.virtual.entryCount()) {
                                sameEntryCount = -1;
                            }
                            hasIdentity |= obj.virtual.hasIdentity();
                        }
                    } else {
                        afterMergeEffects.setPhiInput(phi, i, obj.getMaterializedValue());
                    }
                }
            }
            boolean materialize = false;
            if (virtualInputs == 0) {
                // nothing to do...
            } else if (virtualInputs == phi.valueCount()) {
                if (sameObject != null) {
                    newState.addAndMarkAlias(sameObject, phi, usages);
                } else if (sameType != null && sameEntryCount != -1) {
                    if (!hasIdentity) {
                        VirtualObjectNode virtual = getValueObjectVirtual(phi, states.get(0).getObjectState(phi.valueAt(0)).virtual);

                        PhiNode[] phis = getValueObjectMergePhis(phi, virtual.entryCount());
                        for (int i = 0; i < virtual.entryCount(); i++) {
                            assert virtual.entryKind(i) != Kind.Object;
                            if (phis[i] == null) {
                                phis[i] = new PhiNode(virtual.entryKind(i), merge);
                            }
                            mergeEffects.addFloatingNode(phis[i], "valueObjectPhi");
                            for (int i2 = 0; i2 < phi.valueCount(); i2++) {
                                afterMergeEffects.addPhiInput(phis[i], states.get(i2).getObjectState(phi.valueAt(i2)).getEntry(i));
                            }
                        }
                        mergeEffects.addFloatingNode(virtual, "valueObjectNode");
                        newState.addObject(virtual, new ObjectState(virtual, Arrays.copyOf(phis, phis.length, ValueNode[].class), EscapeState.Virtual, null));
                        newState.addAndMarkAlias(virtual, virtual, usages);
                        newState.addAndMarkAlias(virtual, phi, usages);
                    } else {
                        materialize = true;
                    }
                } else {
                    materialize = true;
                }
            } else {
                materialize = true;
            }

            if (materialize) {
                for (int i = 0; i < phi.valueCount(); i++) {
                    ValueNode value = phi.valueAt(i);
                    ObjectState obj = states.get(i).getObjectState(value);
                    if (obj != null) {
                        materialized |= obj.isVirtual();
                        Block predecessor = mergeBlock.getPredecessors().get(i);
                        replaceWithMaterialized(value, phi, predecessor.getEndNode(), states.get(i), obj, blockEffects.get(predecessor), METRIC_MATERIALIZATIONS_PHI);
                    }
                }
            }
            return materialized;
        }

        private void mergeReadCache(List<BlockState> states) {
            for (Map.Entry<ReadCacheEntry, ValueNode> entry : states.get(0).readCache.entrySet()) {
                ReadCacheEntry key = entry.getKey();
                ValueNode value = entry.getValue();
                boolean phi = false;
                for (int i = 1; i < states.size(); i++) {
                    ValueNode otherValue = states.get(i).readCache.get(key);
                    if (otherValue == null) {
                        value = null;
                        phi = false;
                        break;
                    }
                    if (!phi && otherValue != value) {
                        phi = true;
                    }
                }
                if (phi) {
                    PhiNode phiNode = getCachedPhi(entry, value.kind());
                    mergeEffects.addFloatingNode(phiNode, "mergeReadCache");
                    for (int i = 0; i < states.size(); i++) {
                        afterMergeEffects.addPhiInput(phiNode, states.get(i).getReadCache(key.object, key.identity));
                    }
                    newState.readCache.put(key, phiNode);
                } else if (value != null) {
                    newState.readCache.put(key, value);
                }
            }
            for (PhiNode phi : merge.phis()) {
                if (phi.kind() == Kind.Object) {
                    for (Map.Entry<ReadCacheEntry, ValueNode> entry : states.get(0).readCache.entrySet()) {
                        if (entry.getKey().object == phi.valueAt(0)) {
                            mergeReadCachePhi(phi, entry.getKey().identity, states);
                        }
                    }

                }
            }
        }

        private void mergeReadCachePhi(PhiNode phi, LocationIdentity identity, List<BlockState> states) {
            ValueNode[] values = new ValueNode[phi.valueCount()];
            for (int i = 0; i < phi.valueCount(); i++) {
                ValueNode value = states.get(i).getReadCache(phi.valueAt(i), identity);
                if (value == null) {
                    return;
                }
                values[i] = value;
            }

            PhiNode phiNode = getCachedPhi(new ReadCacheEntry(identity, phi), values[0].kind());
            mergeEffects.addFloatingNode(phiNode, "mergeReadCachePhi");
            for (int i = 0; i < values.length; i++) {
                afterMergeEffects.addPhiInput(phiNode, values[i]);
            }
            newState.readCache.put(new ReadCacheEntry(identity, phi), phiNode);
        }
    }
}
