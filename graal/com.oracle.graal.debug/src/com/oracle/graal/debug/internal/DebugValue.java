/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.debug.internal;

/**
 * A name and index for a value managed in a thread local value map. All access to the value is made
 * via a {@link DebugValue} instance.
 */
public abstract class DebugValue implements Comparable<DebugValue> {

    private final String name;
    private int index;

    protected DebugValue(String name) {
        this.name = name;
        this.index = -1;
    }

    protected long getCurrentValue() {
        ensureInitialized();
        return DebugScope.getInstance().getCurrentValue(index);
    }

    protected void setCurrentValue(long l) {
        ensureInitialized();
        DebugScope.getInstance().setCurrentValue(index, l);
    }

    private void ensureInitialized() {
        if (index == -1) {
            index = KeyRegistry.register(this);
        }
    }

    protected void addToCurrentValue(long value) {
        setCurrentValue(getCurrentValue() + value);
    }

    /**
     * Gets the globally unique index for the value represented by this object.
     */
    public int getIndex() {
        ensureInitialized();
        return index;
    }

    /**
     * Gets the globally unique name for the value represented by this object.
     */
    public String getName() {
        return name;
    }

    public int compareTo(DebugValue o) {
        return name.compareTo(o.name);
    }

    @Override
    public String toString() {
        return name + "@" + index;
    }

    public abstract String toString(long value);
}
