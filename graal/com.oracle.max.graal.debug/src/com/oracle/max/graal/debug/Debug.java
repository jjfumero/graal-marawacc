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
package com.oracle.max.graal.debug;

import com.oracle.max.graal.debug.internal.DebugScope;
import com.oracle.max.graal.debug.internal.MetricImpl;
import com.oracle.max.graal.debug.internal.TimerImpl;
import java.util.Collections;
import java.util.concurrent.*;


public class Debug {
    public static boolean SCOPE = false;
    public static boolean LOG = false;
    public static boolean METER = false;
    public static boolean TIME = false;

    public static void sandbox(String name, Runnable runnable) {
        if (SCOPE) {
            DebugScope.getInstance().scope(name, runnable, null, true, new Object[0]);
        } else {
            runnable.run();
        }
    }

    public static void scope(String name, Runnable runnable) {
        scope(name, null, runnable);
    }

    public static <T> T scope(String name, Callable<T> callable) {
        return scope(name, null, callable);
    }

    public static void scope(String name, Object context, Runnable runnable) {
        if (SCOPE) {
            DebugScope.getInstance().scope(name, runnable, null, false, new Object[]{context});
        } else {
            runnable.run();
        }
    }

    public static <T> T scope(String name, Object context, Callable<T> callable) {
        if (SCOPE) {
            return DebugScope.getInstance().scope(name, null, callable, false, new Object[]{context});
        } else {
            return DebugScope.call(callable);
        }
    }

    public static void log(String msg, Object... args) {
        if (LOG) {
            DebugScope.getInstance().log(msg, args);
        }
    }

    public static void dump(Object object, String msg, Object... args) {
        if (LOG) {
            DebugScope.getInstance().log(msg, args);
        }
    }

    public static Iterable<Object> context() {
        if (SCOPE) {
            return DebugScope.getInstance().getCurrentContext();
        } else {
            return Collections.emptyList();
        }
    }

    public static DebugMetric metric(String name) {
        if (METER) {
            return new MetricImpl(name);
        } else {
            return VOID_METRIC;
        }
    }

    private static final DebugMetric VOID_METRIC = new DebugMetric() {
        @Override
        public void increment() { }
        @Override
        public void add(int value) { }
    };

    public static DebugTimer timer(String name) {
        if (TIME) {
            return new TimerImpl(name);
        } else {
            return VOID_TIMER;
        }
    }

    private static final DebugTimer VOID_TIMER = new DebugTimer() {
        @Override
        public void start() { }
        @Override
        public void stop() { }
    };
}
