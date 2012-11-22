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
package com.oracle.graal.api.code;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.meta.JavaTypeProfile.*;

/**
 * Utility for deriving hint types for a type check instruction (e.g. checkcast or instanceof)
 * based on the target type of the check and any profiling information available for the instruction.
 */
public class TypeCheckHints {

    private static final ResolvedJavaType[] NO_TYPES = {};

    /**
     * If true, then {@link #types} contains the only possible type that could pass the type check
     * because the target of the type check is a final class or has been speculated to be a final class.
     */
    public final boolean exact;

    /**
     * The most likely types that the type check instruction will see.
     */
    public final ResolvedJavaType[] types;

    /**
     * Derives hint information for use when generating the code for a type check instruction.
     *
     * @param type the target type of the type check
     * @param profile the profiling information available for the instruction (if any)
     * @param assumptions the object in which speculations are recorded. This is null if speculations are not supported.
     * @param minHintHitProbability if the probability that the type check will hit one the profiled types (up to
     *            {@code maxHints}) is below this value, then {@link #types} will be null
     * @param maxHints the maximum length of {@link #types}
     */
    public TypeCheckHints(ResolvedJavaType type, JavaTypeProfile profile, Assumptions assumptions, double minHintHitProbability, int maxHints) {
        if (type != null && isFinalClass(type)) {
            types = new ResolvedJavaType[] {type};
            exact = true;
        } else {
            ResolvedJavaType uniqueSubtype = type == null ? null : type.findUniqueConcreteSubtype();
            if (uniqueSubtype != null) {
                types = new ResolvedJavaType[] {uniqueSubtype};
                if (assumptions.useOptimisticAssumptions()) {
                    assumptions.recordConcreteSubtype(type, uniqueSubtype);
                    exact = true;
                } else {
                    exact = false;
                }
            } else {
                exact = false;
                ResolvedJavaType[] hintTypes = NO_TYPES;
                JavaTypeProfile typeProfile = profile;
                if (typeProfile != null) {
                    double notRecordedTypes = typeProfile.getNotRecordedProbability();
                    ProfiledType[] ptypes = typeProfile.getTypes();
                    if (notRecordedTypes < (1D - minHintHitProbability) && ptypes != null && ptypes.length > 0) {
                        hintTypes = new ResolvedJavaType[ptypes.length];
                        int hintCount = 0;
                        double totalHintProbability = 0.0d;
                        for (ProfiledType ptype : ptypes) {
                            ResolvedJavaType hint = ptype.getType();
                            if (type != null && hint.isSubtypeOf(type)) {
                                hintTypes[hintCount++] = hint;
                                totalHintProbability += ptype.getProbability();
                            }
                        }
                        if (totalHintProbability >= minHintHitProbability) {
                            if (hintTypes.length != hintCount || hintCount > maxHints) {
                                hintTypes = Arrays.copyOf(hintTypes, Math.min(maxHints, hintCount));
                            }
                        } else {
                            hintTypes = NO_TYPES;
                        }

                    }
                }
                this.types = hintTypes;
            }
        }
    }

    /**
     * Determines if a given type can have subtypes. This analysis is purely static; no
     * assumptions are made.
     *
     * @return true if {@code type} has no subtype(s)
     */
    public static boolean isFinalClass(ResolvedJavaType type) {
        if (type.isArrayClass()) {
            return isFinalClass(type.getComponentType());
        }
        return Modifier.isFinal(type.getModifiers());
    }
}
