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
package com.oracle.graal.graph.iterators;

import java.util.*;

import com.oracle.graal.graph.*;

public abstract class AbstractNodeIterable<T extends Node> implements NodeIterable<T> {
    @Override
    public NodeIterable<T> until(final T u) {
        return new FilteredNodeIterable<>(this).until(u);
    }
    @Override
    public NodeIterable<T> until(final Class<? extends T> clazz) {
        return new FilteredNodeIterable<>(this).until(clazz);
    }
    @Override
    @SuppressWarnings("unchecked")
    public <F extends T> NodeIterable<F> filter(Class<F> clazz) {
        return (NodeIterable<F>) new FilteredNodeIterable<>(this).and(NodePredicates.isA(clazz));
    }
    @Override
    public NodeIterable<T> filterInterface(Class<?> iface) {
        return new FilteredNodeIterable<>(this).and(NodePredicates.isAInterface(iface));
    }
    @Override
    public FilteredNodeIterable<T> filter(NodePredicate predicate) {
        return new FilteredNodeIterable<>(this).and(predicate);
    }
    @Override
    public FilteredNodeIterable<T> nonNull() {
        return new FilteredNodeIterable<>(this).and(NodePredicates.isNotNull());
    }
    @Override
    public NodeIterable<T> distinct() {
        return new FilteredNodeIterable<>(this).distinct();
    }
    @Override
    public List<T> snapshot() {
        ArrayList<T> list = new ArrayList<>();
        for (T n : this) {
            list.add(n);
        }
        return list;
    }
    @Override
    public void snapshotTo(List<T> to) {
        for (T n : this) {
            to.add(n);
        }
    }
    @Override
    public T first() {
        Iterator<T> iterator = iterator();
        if (iterator.hasNext()) {
            return iterator.next();
        }
        return null;
    }
    @Override
    public int count() {
        int count = 0;
        Iterator<T> iterator = iterator();
        while (iterator.hasNext()) {
            iterator.next();
            count++;
        }
        return count;
    }
    @Override
    public boolean isEmpty() {
        return !iterator().hasNext();
    }
    @Override
    public boolean isNotEmpty() {
        return iterator().hasNext();
    }
    @Override
    public boolean contains(T node) {
        return this.filter(NodePredicates.equals(node)).isNotEmpty();
    }
}
