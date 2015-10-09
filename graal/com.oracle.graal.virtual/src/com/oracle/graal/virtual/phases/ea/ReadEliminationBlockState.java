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

import java.util.HashMap;
import java.util.Map;

import jdk.vm.ci.meta.LocationIdentity;

import com.oracle.graal.compiler.common.CollectionsFactory;
import com.oracle.graal.nodes.ValueNode;

public class ReadEliminationBlockState extends EffectsBlockState<ReadEliminationBlockState> {

    final HashMap<CacheEntry<?>, ValueNode> readCache;

    abstract static class CacheEntry<T> {

        public final ValueNode object;
        public final T identity;

        public CacheEntry(ValueNode object, T identity) {
            this.object = object;
            this.identity = identity;
        }

        public abstract CacheEntry<T> duplicateWithObject(ValueNode newObject);

        @Override
        public int hashCode() {
            int result = 31 + ((identity == null) ? 0 : identity.hashCode());
            return 31 * result + ((object == null) ? 0 : object.hashCode());
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof CacheEntry<?>)) {
                return false;
            }
            CacheEntry<?> other = (CacheEntry<?>) obj;
            return identity == other.identity && object == other.object;
        }

        @Override
        public String toString() {
            return object + ":" + identity;
        }

        public abstract boolean conflicts(LocationIdentity other);
    }

    static class LoadCacheEntry extends CacheEntry<LocationIdentity> {

        public LoadCacheEntry(ValueNode object, LocationIdentity identity) {
            super(object, identity);
        }

        @Override
        public CacheEntry<LocationIdentity> duplicateWithObject(ValueNode newObject) {
            return new LoadCacheEntry(newObject, identity);
        }

        @Override
        public boolean conflicts(LocationIdentity other) {
            return identity.equals(other);
        }
    }

    /**
     * CacheEntry describing an Unsafe memory reference. The memory location and the location
     * identity are separate so both must be considered when looking for optimizable memory
     * accesses.
     *
     */
    static class UnsafeLoadCacheEntry extends CacheEntry<ValueNode> {

        private LocationIdentity locationIdentity;

        public UnsafeLoadCacheEntry(ValueNode object, ValueNode location, LocationIdentity locationIdentity) {
            super(object, location);
            this.locationIdentity = locationIdentity;
        }

        @Override
        public CacheEntry<ValueNode> duplicateWithObject(ValueNode newObject) {
            return new UnsafeLoadCacheEntry(newObject, identity, locationIdentity);
        }

        @Override
        public boolean conflicts(LocationIdentity other) {
            return locationIdentity.equals(other);
        }
    }

    static class ReadCacheEntry extends CacheEntry<ValueNode> {

        private final LocationIdentity location;

        public ReadCacheEntry(ValueNode object, ValueNode offset, LocationIdentity location) {
            super(object, offset);
            this.location = location;
        }

        @Override
        public CacheEntry<ValueNode> duplicateWithObject(ValueNode newObject) {
            return new ReadCacheEntry(newObject, identity, location);
        }

        @Override
        public boolean conflicts(LocationIdentity other) {
            return location.equals(other);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ReadCacheEntry)) {
                return false;
            }

            ReadCacheEntry other = (ReadCacheEntry) obj;
            return this.location.equals(other.location) && super.equals(other);
        }

        @Override
        public int hashCode() {
            return location.hashCode() * 23 + super.hashCode();
        }
    }

    public ReadEliminationBlockState() {
        readCache = CollectionsFactory.newMap();
    }

    public ReadEliminationBlockState(ReadEliminationBlockState other) {
        readCache = CollectionsFactory.newMap(other.readCache);
    }

    @Override
    public String toString() {
        return super.toString() + " " + readCache;
    }

    @Override
    public boolean equivalentTo(ReadEliminationBlockState other) {
        return compareMapsNoSize(readCache, other.readCache);
    }

    public void addCacheEntry(CacheEntry<?> identifier, ValueNode value) {
        readCache.put(identifier, value);
    }

    public ValueNode getCacheEntry(CacheEntry<?> identifier) {
        return readCache.get(identifier);
    }

    public void killReadCache() {
        readCache.clear();
    }

    public void killReadCache(LocationIdentity identity) {
        readCache.entrySet().removeIf(entry -> entry.getKey().conflicts(identity));
    }

    public Map<CacheEntry<?>, ValueNode> getReadCache() {
        return readCache;
    }
}
