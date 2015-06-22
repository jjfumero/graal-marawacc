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

import static jdk.internal.jvmci.meta.LocationIdentity.*;

import java.util.*;

import jdk.internal.jvmci.meta.*;

import com.oracle.graal.compiler.common.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.cfg.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.memory.*;
import com.oracle.graal.nodes.memory.address.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.virtual.phases.ea.ReadEliminationBlockState.CacheEntry;
import com.oracle.graal.virtual.phases.ea.ReadEliminationBlockState.LoadCacheEntry;
import com.oracle.graal.virtual.phases.ea.ReadEliminationBlockState.ReadCacheEntry;
import com.oracle.graal.virtual.phases.ea.ReadEliminationBlockState.UnsafeLoadCacheEntry;

public class ReadEliminationClosure extends EffectsClosure<ReadEliminationBlockState> {

    public ReadEliminationClosure(ControlFlowGraph cfg) {
        super(null, cfg);
    }

    @Override
    protected ReadEliminationBlockState getInitialState() {
        return new ReadEliminationBlockState();
    }

    @Override
    protected boolean processNode(Node node, ReadEliminationBlockState state, GraphEffectList effects, FixedWithNextNode lastFixedNode) {
        boolean deleted = false;
        if (node instanceof AccessFieldNode) {
            AccessFieldNode access = (AccessFieldNode) node;
            if (access.isVolatile()) {
                processIdentity(state, any());
            } else {
                ValueNode object = GraphUtil.unproxify(access.object());
                LoadCacheEntry identifier = new LoadCacheEntry(object, access.field().getLocationIdentity());
                ValueNode cachedValue = state.getCacheEntry(identifier);
                if (node instanceof LoadFieldNode) {
                    if (cachedValue != null && access.stamp().isCompatible(cachedValue.stamp())) {
                        effects.replaceAtUsages(access, cachedValue);
                        addScalarAlias(access, cachedValue);
                        deleted = true;
                    } else {
                        state.addCacheEntry(identifier, access);
                    }
                } else {
                    assert node instanceof StoreFieldNode;
                    StoreFieldNode store = (StoreFieldNode) node;
                    ValueNode value = getScalarAlias(store.value());
                    if (GraphUtil.unproxify(value) == GraphUtil.unproxify(cachedValue)) {
                        effects.deleteNode(store);
                        deleted = true;
                    }
                    state.killReadCache(store.field().getLocationIdentity());
                    state.addCacheEntry(identifier, value);
                }
            }
        } else if (node instanceof ReadNode) {
            ReadNode read = (ReadNode) node;
            if (read.getAddress() instanceof OffsetAddressNode) {
                OffsetAddressNode address = (OffsetAddressNode) read.getAddress();
                if (address.getOffset().isConstant()) {
                    ValueNode object = GraphUtil.unproxify(address.getBase());
                    ReadCacheEntry identifier = new ReadCacheEntry(object, address.getOffset(), read.getLocationIdentity());
                    ValueNode cachedValue = state.getCacheEntry(identifier);
                    if (cachedValue != null && read.stamp().isCompatible(cachedValue.stamp())) {
                        // Anchor guard if it is not fixed and different from cachedValue's guard
                        if (read.getGuard() != null && !(read.getGuard() instanceof FixedNode)) {
                            if (!(cachedValue instanceof GuardedNode) || ((GuardedNode) cachedValue).getGuard() != read.getGuard()) {
                                effects.addFixedNodeBefore(new ValueAnchorNode((ValueNode) read.getGuard()), read);
                            }
                        }
                    }
                }
            }
        } else if (node instanceof WriteNode) {
            WriteNode write = (WriteNode) node;
            if (write.getAddress() instanceof OffsetAddressNode) {
                OffsetAddressNode address = (OffsetAddressNode) write.getAddress();
                if (address.getOffset().isConstant()) {
                    ValueNode object = GraphUtil.unproxify(address.getBase());
                    ReadCacheEntry identifier = new ReadCacheEntry(object, address.getOffset(), write.getLocationIdentity());
                    ValueNode cachedValue = state.getCacheEntry(identifier);

                    ValueNode value = getScalarAlias(write.value());
                    if (GraphUtil.unproxify(value) == GraphUtil.unproxify(cachedValue)) {
                        effects.deleteNode(write);
                        deleted = true;
                    }
                    processIdentity(state, write.getLocationIdentity());
                    state.addCacheEntry(identifier, value);
                } else {
                    processIdentity(state, write.getLocationIdentity());
                }
            }
        } else if (node instanceof UnsafeAccessNode) {
            if (node instanceof UnsafeLoadNode) {
                UnsafeLoadNode load = (UnsafeLoadNode) node;
                if (load.offset().isConstant() && !load.getLocationIdentity().equals(LocationIdentity.any())) {
                    ValueNode object = GraphUtil.unproxify(load.object());
                    UnsafeLoadCacheEntry identifier = new UnsafeLoadCacheEntry(object, load.offset(), load.getLocationIdentity());
                    ValueNode cachedValue = state.getCacheEntry(identifier);
                    if (cachedValue != null && load.stamp().isCompatible(cachedValue.stamp())) {
                        effects.replaceAtUsages(load, cachedValue);
                        addScalarAlias(load, cachedValue);
                        deleted = true;
                    } else {
                        state.addCacheEntry(identifier, load);
                    }
                }
            } else {
                assert node instanceof UnsafeStoreNode;
                UnsafeStoreNode write = (UnsafeStoreNode) node;
                if (write.offset().isConstant() && !write.getLocationIdentity().equals(LocationIdentity.any())) {
                    ValueNode object = GraphUtil.unproxify(write.object());
                    UnsafeLoadCacheEntry identifier = new UnsafeLoadCacheEntry(object, write.offset(), write.getLocationIdentity());
                    ValueNode cachedValue = state.getCacheEntry(identifier);

                    ValueNode value = getScalarAlias(write.value());
                    if (GraphUtil.unproxify(value) == GraphUtil.unproxify(cachedValue)) {
                        effects.deleteNode(write);
                        deleted = true;
                    }
                    processIdentity(state, write.getLocationIdentity());
                    state.addCacheEntry(identifier, value);
                } else {
                    processIdentity(state, write.getLocationIdentity());
                }
            }
        } else if (node instanceof MemoryCheckpoint.Single) {
            LocationIdentity identity = ((MemoryCheckpoint.Single) node).getLocationIdentity();
            processIdentity(state, identity);
        } else if (node instanceof MemoryCheckpoint.Multi) {
            for (LocationIdentity identity : ((MemoryCheckpoint.Multi) node).getLocationIdentities()) {
                processIdentity(state, identity);
            }
        }
        return deleted;
    }

    private static void processIdentity(ReadEliminationBlockState state, LocationIdentity identity) {
        if (identity.isAny()) {
            state.killReadCache();
            return;
        }
        state.killReadCache(identity);
    }

    @Override
    protected void processLoopExit(LoopExitNode exitNode, ReadEliminationBlockState initialState, ReadEliminationBlockState exitState, GraphEffectList effects) {
        if (exitNode.graph().hasValueProxies()) {
            for (Map.Entry<CacheEntry<?>, ValueNode> entry : exitState.getReadCache().entrySet()) {
                if (initialState.getReadCache().get(entry.getKey()) != entry.getValue()) {
                    ProxyNode proxy = new ValueProxyNode(exitState.getCacheEntry(entry.getKey()), exitNode);
                    effects.addFloatingNode(proxy, "readCacheProxy");
                    entry.setValue(proxy);
                }
            }
        }
    }

    @Override
    protected ReadEliminationBlockState cloneState(ReadEliminationBlockState other) {
        return new ReadEliminationBlockState(other);
    }

    @Override
    protected MergeProcessor createMergeProcessor(Block merge) {
        return new ReadEliminationMergeProcessor(merge);
    }

    private class ReadEliminationMergeProcessor extends EffectsClosure<ReadEliminationBlockState>.MergeProcessor {

        private final HashMap<Object, ValuePhiNode> materializedPhis = CollectionsFactory.newMap();

        public ReadEliminationMergeProcessor(Block mergeBlock) {
            super(mergeBlock);
        }

        protected <T> PhiNode getCachedPhi(T virtual, Stamp stamp) {
            ValuePhiNode result = materializedPhis.get(virtual);
            if (result == null) {
                result = createValuePhi(stamp);
                materializedPhis.put(virtual, result);
            }
            return result;
        }

        @Override
        protected void merge(List<ReadEliminationBlockState> states) {
            super.merge(states);

            mergeReadCache(states);
        }

        private void mergeReadCache(List<ReadEliminationBlockState> states) {
            for (Map.Entry<CacheEntry<?>, ValueNode> entry : states.get(0).readCache.entrySet()) {
                CacheEntry<?> key = entry.getKey();
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
                    PhiNode phiNode = getCachedPhi(entry, value.stamp().unrestricted());
                    mergeEffects.addFloatingNode(phiNode, "mergeReadCache");
                    for (int i = 0; i < states.size(); i++) {
                        setPhiInput(phiNode, i, states.get(i).getCacheEntry(key));
                    }
                    newState.addCacheEntry(key, phiNode);
                } else if (value != null) {
                    newState.addCacheEntry(key, value);
                }
            }
            for (PhiNode phi : getPhis()) {
                if (phi.getKind() == Kind.Object) {
                    for (Map.Entry<CacheEntry<?>, ValueNode> entry : states.get(0).readCache.entrySet()) {
                        if (entry.getKey().object == getPhiValueAt(phi, 0)) {
                            mergeReadCachePhi(phi, entry.getKey(), states);
                        }
                    }

                }
            }
        }

        private void mergeReadCachePhi(PhiNode phi, CacheEntry<?> identifier, List<ReadEliminationBlockState> states) {
            ValueNode[] values = new ValueNode[states.size()];
            for (int i = 0; i < states.size(); i++) {
                ValueNode value = states.get(i).getCacheEntry(identifier.duplicateWithObject(getPhiValueAt(phi, i)));
                if (value == null) {
                    return;
                }
                values[i] = value;
            }

            CacheEntry<?> newIdentifier = identifier.duplicateWithObject(phi);
            PhiNode phiNode = getCachedPhi(newIdentifier, values[0].stamp().unrestricted());
            mergeEffects.addFloatingNode(phiNode, "mergeReadCachePhi");
            for (int i = 0; i < values.length; i++) {
                setPhiInput(phiNode, i, values[i]);
            }
            newState.addCacheEntry(newIdentifier, phiNode);
        }
    }
}
