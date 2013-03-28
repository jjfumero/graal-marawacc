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
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.spi.Virtualizable.EscapeState;
import com.oracle.graal.nodes.virtual.*;
import com.oracle.graal.virtual.nodes.*;

class BlockState {

    private final IdentityHashMap<VirtualObjectNode, ObjectState> objectStates = new IdentityHashMap<>();
    private final IdentityHashMap<ValueNode, VirtualObjectNode> objectAliases;
    private final IdentityHashMap<ValueNode, ValueNode> scalarAliases;
    private final HashMap<ReadCacheEntry, ValueNode> readCache;

    static class ReadCacheEntry {

        public final Object identity;
        public final ValueNode object;

        public ReadCacheEntry(Object identity, ValueNode object) {
            this.identity = identity;
            this.object = object;
        }

        @Override
        public int hashCode() {
            int result = 31 + ((identity == null) ? 0 : identity.hashCode());
            return 31 * result + ((object == null) ? 0 : object.hashCode());
        }

        @Override
        public boolean equals(Object obj) {
            ReadCacheEntry other = (ReadCacheEntry) obj;
            return identity == other.identity && object == other.object;
        }

        @Override
        public String toString() {
            return object + ":" + identity;
        }
    }

    public BlockState() {
        objectAliases = new IdentityHashMap<>();
        scalarAliases = new IdentityHashMap<>();
        readCache = new HashMap<>();
    }

    public BlockState(BlockState other) {
        for (Map.Entry<VirtualObjectNode, ObjectState> entry : other.objectStates.entrySet()) {
            objectStates.put(entry.getKey(), entry.getValue().cloneState());
        }
        objectAliases = new IdentityHashMap<>(other.objectAliases);
        scalarAliases = new IdentityHashMap<>(other.scalarAliases);
        readCache = new HashMap<>(other.readCache);
    }

    public void addReadCache(ValueNode object, Object identity, ValueNode value) {
        ValueNode cacheObject;
        ObjectState obj = getObjectState(object);
        if (obj != null) {
            assert !obj.isVirtual();
            cacheObject = obj.getMaterializedValue();
        } else {
            cacheObject = object;
        }
        readCache.put(new ReadCacheEntry(identity, cacheObject), value);
    }

    public ValueNode getReadCache(ValueNode object, Object identity) {
        ValueNode cacheObject;
        ObjectState obj = getObjectState(object);
        if (obj != null) {
            assert !obj.isVirtual();
            cacheObject = obj.getMaterializedValue();
        } else {
            cacheObject = object;
        }
        ValueNode cacheValue = readCache.get(new ReadCacheEntry(identity, cacheObject));
        obj = getObjectState(cacheValue);
        if (obj != null) {
            assert !obj.isVirtual();
            cacheValue = obj.getMaterializedValue();
        } else {
            cacheValue = getScalarAlias(cacheValue);
        }
        return cacheValue;
    }

    public void killReadCache(Object identity) {
        if (identity == LocationNode.ANY_LOCATION) {
            readCache.clear();
        } else {
            Iterator<Map.Entry<ReadCacheEntry, ValueNode>> iter = readCache.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<ReadCacheEntry, ValueNode> entry = iter.next();
                if (entry.getKey().identity == identity) {
                    iter.remove();
                }
            }
        }
    }

    public ObjectState getObjectState(VirtualObjectNode object) {
        assert objectStates.containsKey(object);
        return objectStates.get(object);
    }

    public ObjectState getObjectStateOptional(VirtualObjectNode object) {
        return objectStates.get(object);
    }

    public ObjectState getObjectState(ValueNode value) {
        VirtualObjectNode object = objectAliases.get(value);
        return object == null ? null : getObjectState(object);
    }

    public BlockState cloneState() {
        return new BlockState(this);
    }

    public void materializeBefore(FixedNode fixed, VirtualObjectNode virtual, EscapeState state, GraphEffectList materializeEffects) {
        PartialEscapeClosure.METRIC_MATERIALIZATIONS.increment();
        HashSet<VirtualObjectNode> deferred = new HashSet<>();
        GraphEffectList deferredStores = new GraphEffectList();
        materializeChangedBefore(fixed, virtual, state, deferred, deferredStores, materializeEffects);
        materializeEffects.addAll(deferredStores);
    }

    private void materializeChangedBefore(FixedNode fixed, VirtualObjectNode virtual, EscapeState state, HashSet<VirtualObjectNode> deferred, GraphEffectList deferredStores,
                    GraphEffectList materializeEffects) {
        trace("materializing %s at %s", virtual, fixed);
        ObjectState obj = getObjectState(virtual);
        if (obj.getLockCount() > 0 && obj.virtual.type().isArray()) {
            throw new BailoutException("array materialized with lock");
        }

        ValueNode[] fieldState = obj.getEntries();

        // some entries are not default constants - do the materialization
        virtual.materializeAt(fixed);
        MaterializeObjectNode materialize = new MaterializeObjectNode(virtual, obj.getLockCount());
        ValueNode[] values = new ValueNode[obj.getEntries().length];
        materialize.setProbability(fixed.probability());
        obj.escape(materialize, state);
        deferred.add(virtual);
        for (int i = 0; i < fieldState.length; i++) {
            ObjectState valueObj = getObjectState(fieldState[i]);
            if (valueObj != null) {
                if (valueObj.isVirtual()) {
                    materializeChangedBefore(fixed, valueObj.virtual, state, deferred, deferredStores, materializeEffects);
                }
                if (deferred.contains(valueObj.virtual)) {
                    Kind fieldKind;
                    CyclicMaterializeStoreNode store;
                    if (virtual instanceof VirtualArrayNode) {
                        store = new CyclicMaterializeStoreNode(materialize, valueObj.getMaterializedValue(), i);
                        fieldKind = ((VirtualArrayNode) virtual).componentType().getKind();
                    } else {
                        VirtualInstanceNode instanceObject = (VirtualInstanceNode) virtual;
                        store = new CyclicMaterializeStoreNode(materialize, valueObj.getMaterializedValue(), instanceObject.field(i));
                        fieldKind = instanceObject.field(i).getType().getKind();
                    }
                    deferredStores.addFixedNodeBefore(store, fixed);
                    values[i] = ConstantNode.defaultForKind(fieldKind, fixed.graph());
                } else {
                    values[i] = valueObj.getMaterializedValue();
                }
            } else {
                values[i] = fieldState[i];
            }
        }
        deferred.remove(virtual);

        if (virtual instanceof VirtualInstanceNode) {
            VirtualInstanceNode instance = (VirtualInstanceNode) virtual;
            for (int i = 0; i < fieldState.length; i++) {
                readCache.put(new ReadCacheEntry(instance.field(i), materialize), fieldState[i]);
            }
        }

        materializeEffects.addMaterialization(materialize, fixed, values);
    }

    void addAndMarkAlias(VirtualObjectNode virtual, ValueNode node, NodeBitMap usages) {
        objectAliases.put(node, virtual);
        if (node.isAlive()) {
            for (Node usage : node.usages()) {
                markVirtualUsages(usage, usages);
            }
        }
    }

    private void markVirtualUsages(Node node, NodeBitMap usages) {
        if (!usages.isNew(node)) {
            usages.mark(node);
        }
        if (node instanceof VirtualState) {
            for (Node usage : node.usages()) {
                markVirtualUsages(usage, usages);
            }
        }
    }

    public void addObject(VirtualObjectNode virtual, ObjectState state) {
        objectStates.put(virtual, state);
    }

    public void addScalarAlias(ValueNode alias, ValueNode value) {
        scalarAliases.put(alias, value);
    }

    public ValueNode getScalarAlias(ValueNode alias) {
        ValueNode result = scalarAliases.get(alias);
        return result == null ? alias : result;
    }

    public Iterable<ObjectState> getStates() {
        return objectStates.values();
    }

    public Iterable<VirtualObjectNode> getVirtualObjects() {
        return objectAliases.values();
    }

    @Override
    public String toString() {
        return objectStates.toString();
    }

    public void mergeReadCache(List<BlockState> states, MergeNode merge, GraphEffectList effects) {
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
                PhiNode phiNode = new PhiNode(value.kind(), merge);
                effects.addFloatingNode(phiNode);
                for (int i = 0; i < states.size(); i++) {
                    effects.addPhiInput(phiNode, states.get(i).getReadCache(key.object, key.identity));
                }
                readCache.put(key, phiNode);
            } else if (value != null) {
                readCache.put(key, value);
            }
        }
        for (PhiNode phi : merge.phis()) {
            if (phi.kind() == Kind.Object) {
                for (Map.Entry<ReadCacheEntry, ValueNode> entry : states.get(0).readCache.entrySet()) {
                    if (entry.getKey().object == phi.valueAt(0)) {
                        mergeReadCachePhi(phi, entry.getKey().identity, states, merge, effects);
                    }
                }

            }
        }
    }

    private void mergeReadCachePhi(PhiNode phi, Object identity, List<BlockState> states, MergeNode merge, GraphEffectList effects) {
        ValueNode[] values = new ValueNode[phi.valueCount()];
        for (int i = 0; i < phi.valueCount(); i++) {
            ValueNode value = states.get(i).getReadCache(phi.valueAt(i), identity);
            if (value == null) {
                return;
            }
            values[i] = value;
        }

        PhiNode phiNode = new PhiNode(values[0].kind(), merge);
        effects.addFloatingNode(phiNode);
        for (int i = 0; i < values.length; i++) {
            effects.addPhiInput(phiNode, values[i]);
        }
        readCache.put(new ReadCacheEntry(identity, phi), phiNode);
    }

    public static BlockState meetAliases(List<BlockState> states) {
        BlockState newState = new BlockState();

        newState.objectAliases.putAll(states.get(0).objectAliases);
        for (int i = 1; i < states.size(); i++) {
            BlockState state = states.get(i);
            for (Map.Entry<ValueNode, VirtualObjectNode> entry : states.get(0).objectAliases.entrySet()) {
                if (state.objectAliases.containsKey(entry.getKey())) {
                    assert state.objectAliases.get(entry.getKey()) == entry.getValue();
                } else {
                    newState.objectAliases.remove(entry.getKey());
                }
            }
        }

        newState.scalarAliases.putAll(states.get(0).scalarAliases);
        for (int i = 1; i < states.size(); i++) {
            BlockState state = states.get(i);
            for (Map.Entry<ValueNode, ValueNode> entry : states.get(0).scalarAliases.entrySet()) {
                if (state.scalarAliases.containsKey(entry.getKey())) {
                    assert state.scalarAliases.get(entry.getKey()) == entry.getValue();
                } else {
                    newState.scalarAliases.remove(entry.getKey());
                }
            }
        }

        return newState;
    }

    public Map<ReadCacheEntry, ValueNode> getReadCache() {
        return readCache;
    }

}
