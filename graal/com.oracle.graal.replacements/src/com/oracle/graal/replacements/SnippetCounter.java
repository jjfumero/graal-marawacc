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
package com.oracle.graal.replacements;

//JaCoCo Exclude

import static com.oracle.graal.graph.FieldIntrospection.*;

import java.io.*;
import java.util.*;

import com.oracle.graal.graph.*;
import com.oracle.graal.replacements.Snippet.*;
import com.oracle.graal.replacements.nodes.*;

/**
 * A counter that can be safely {@linkplain #inc() incremented} from within a snippet for gathering
 * snippet specific metrics.
 */
public class SnippetCounter implements Comparable<SnippetCounter> {

    /**
     * A group of related counters.
     */
    public static class Group {

        final String name;
        final List<SnippetCounter> counters;

        public Group(String name) {
            this.name = name;
            this.counters = new ArrayList<>();
        }

        @Override
        public synchronized String toString() {
            Collections.sort(counters);

            long total = 0;
            int maxNameLen = 0;
            for (SnippetCounter c : counters) {
                total += c.value;
                maxNameLen = Math.max(c.name.length(), maxNameLen);
            }

            StringBuilder buf = new StringBuilder(String.format("Counters: %s%n", name));

            for (SnippetCounter c : counters) {
                double percent = total == 0D ? 0D : ((double) (c.value * 100)) / total;
                buf.append(String.format("  %" + maxNameLen + "s: %5.2f%%%10d  // %s%n", c.name, percent, c.value, c.description));
            }
            return buf.toString();
        }
    }

    /**
     * Sorts counters in descending order of their {@linkplain #value() values}.
     */
    @Override
    public int compareTo(SnippetCounter o) {
        if (value > o.value) {
            return -1;
        } else if (o.value < value) {
            return 1;
        }
        return 0;
    }

    private static final List<Group> groups = new ArrayList<>();

    private final Group group;
    private final int index;
    private final String name;
    private final String description;
    private long value;

    @Fold
    private static int countOffset() {
        try {
            return (int) unsafe.objectFieldOffset(SnippetCounter.class.getDeclaredField("value"));
        } catch (Exception e) {
            throw new GraalInternalError(e);
        }
    }

    /**
     * Creates a counter.
     * 
     * @param group the group to which the counter belongs. If this is null, the newly created
     *            counter is disabled and {@linkplain #inc() incrementing} is a no-op.
     * @param name the name of the counter
     * @param description a brief comment describing the metric represented by the counter
     */
    public SnippetCounter(Group group, String name, String description) {
        this.group = group;
        this.name = name;
        this.description = description;
        if (group != null) {
            List<SnippetCounter> counters = group.counters;
            this.index = counters.size();
            counters.add(this);
            if (index == 0) {
                groups.add(group);
            }
        } else {
            this.index = -1;
        }
    }

    /**
     * Increments the value of this counter. This method can be safely used in a snippet if it is
     * invoked on a compile-time constant {@link SnippetCounter} object.
     */
    public void inc() {
        if (group != null) {
            DirectObjectStoreNode.storeLong(this, countOffset(), 0, value + 1);
        }
    }

    /**
     * Gets the value of this counter.
     */
    public long value() {
        return value;
    }

    /**
     * Prints all the counter groups to a given stream.
     */
    public static void printGroups(PrintStream out) {
        for (Group group : groups) {
            out.println(group);
        }
    }
}
