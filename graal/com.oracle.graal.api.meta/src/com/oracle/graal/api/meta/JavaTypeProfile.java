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
package com.oracle.graal.api.meta;

import java.io.*;

import com.oracle.graal.api.meta.ProfilingInfo.*;

/**
 * This profile object represents the type profile at a specific BCI. The precision of the supplied
 * values may vary, but a runtime that provides this information should be aware that it will be
 * used to guide performance-critical decisions like speculative inlining, etc.
 */
public final class JavaTypeProfile implements Serializable {

    private static final long serialVersionUID = -6877016333706838441L;

    /**
     * A profiled type that has a probability. Profiled types are naturally sorted in descending
     * order of their probabilities.
     */
    public static final class ProfiledType implements Comparable<ProfiledType>, Serializable {

        private static final long serialVersionUID = 7838575753661305744L;

        private final ResolvedJavaType type;
        private final double probability;

        public ProfiledType(ResolvedJavaType type, double probability) {
            assert type != null;
            assert probability >= 0.0D && probability <= 1.0D;
            this.type = type;
            this.probability = probability;
        }

        /**
         * Returns the type for this profile entry.
         */
        public ResolvedJavaType getType() {
            return type;
        }

        /**
         * Returns the estimated probability of {@link #getType()}.
         * 
         * @return double value >= 0.0 and <= 1.0
         */
        public double getProbability() {
            return probability;
        }

        @Override
        public int compareTo(ProfiledType o) {
            if (getProbability() > o.getProbability()) {
                return -1;
            } else if (getProbability() < o.getProbability()) {
                return 1;
            }
            return 0;
        }

        @Override
        public String toString() {
            return "{" + type.getName() + ", " + probability + "}";
        }
    }

    private final TriState nullSeen;
    private final double notRecordedProbability;
    private final ProfiledType[] ptypes;

    /**
     * Determines if an array of profiled types are sorted in descending order of their
     * probabilities.
     */
    private static boolean isSorted(ProfiledType[] ptypes) {
        for (int i = 1; i < ptypes.length; i++) {
            if (ptypes[i - 1].getProbability() < ptypes[i].getProbability()) {
                return false;
            }
        }
        return true;
    }

    public JavaTypeProfile(TriState nullSeen, double notRecordedProbability, ProfiledType... ptypes) {
        this.nullSeen = nullSeen;
        this.ptypes = ptypes;
        this.notRecordedProbability = notRecordedProbability;
        assert isSorted(ptypes);
    }

    /**
     * Returns the estimated probability of all types that could not be recorded due to profiling
     * limitations.
     * 
     * @return double value >= 0.0 and <= 1.0
     */
    public double getNotRecordedProbability() {
        return notRecordedProbability;
    }

    /**
     * Returns whether a null value was at the type check.
     */
    public TriState getNullSeen() {
        return nullSeen;
    }

    /**
     * A list of types for which the runtime has recorded probability information. Note that this
     * includes both positive and negative types where a positive type is a subtype of the checked
     * type and a negative type is not.
     */
    public ProfiledType[] getTypes() {
        return ptypes;
    }

    /**
     * Searches for an entry of a given resolved Java type.
     * 
     * @param type the type for which an entry should be searched
     * @return the entry or null if no entry for this type can be found
     */
    public ProfiledType findEntry(ResolvedJavaType type) {
        if (ptypes != null) {
            for (ProfiledType pt : ptypes) {
                if (pt.getType() == type) {
                    return pt;
                }
            }
        }
        return null;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("JavaTypeProfile[");
        builder.append(this.nullSeen);
        builder.append(", ");
        if (ptypes != null) {
            for (ProfiledType pt : ptypes) {
                builder.append(pt.toString());
                builder.append(", ");
            }
        }
        builder.append(this.notRecordedProbability);
        builder.append("]");
        return builder.toString();
    }
}
