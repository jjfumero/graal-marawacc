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

import static com.oracle.graal.debug.DebugCloseable.VOID_CLOSEABLE;

import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.DebugCloseable;
import com.oracle.graal.debug.DebugMemUseTracker;
import com.oracle.graal.debug.Management;

public final class MemUseTrackerImpl extends AccumulatedDebugValue implements DebugMemUseTracker {

    public static long getCurrentThreadAllocatedBytes() {
        return Management.getCurrentThreadAllocatedBytes();
    }

    /**
     * Records the most recent active tracker.
     */
    private static final ThreadLocal<CloseableCounterImpl> currentTracker = new ThreadLocal<>();

    public MemUseTrackerImpl(String name, boolean conditional) {
        super(name, conditional, new DebugValue(name + "_Flat", conditional) {

            @Override
            public String toString(long value) {
                return valueToString(value);
            }
        });
    }

    @Override
    public DebugCloseable start() {
        if (!isConditional() || Debug.isMemUseTrackingEnabled()) {
            MemUseCloseableCounterImpl result = new MemUseCloseableCounterImpl(this);
            currentTracker.set(result);
            return result;
        } else {
            return VOID_CLOSEABLE;
        }
    }

    public static String valueToString(long value) {
        return String.format("%d bytes", value);
    }

    @Override
    public String toString(long value) {
        return valueToString(value);
    }

    private static final class MemUseCloseableCounterImpl extends CloseableCounterImpl implements DebugCloseable {

        private MemUseCloseableCounterImpl(AccumulatedDebugValue counter) {
            super(currentTracker.get(), counter);
        }

        @Override
        long getCounterValue() {
            return getCurrentThreadAllocatedBytes();
        }

        @Override
        public void close() {
            super.close();
            currentTracker.set(parent);
        }
    }
}
