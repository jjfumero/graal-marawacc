/*
 * Copyright (c) 2012, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.replacements;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.replacements.*;
import com.oracle.graal.nodes.extended.*;

public class BoxingSubstitutions {

    @ClassSubstitution(Boolean.class)
    private static class BooleanSubstitutions {

        @MethodSubstitution(isForcedInlining = true)
        public static Boolean valueOf(boolean value) {
            return BoxNode.box(value, Boolean.class, Kind.Boolean);
        }

        @MethodSubstitution(isStatic = false, isForcedInlining = true)
        public static boolean booleanValue(Boolean value) {
            return UnboxNode.unbox(value, Kind.Boolean);
        }
    }

    @ClassSubstitution(Byte.class)
    private static class ByteSubstitutions {

        @MethodSubstitution(isForcedInlining = true)
        public static Byte valueOf(byte value) {
            return BoxNode.box(value, Byte.class, Kind.Byte);
        }

        @MethodSubstitution(isStatic = false, isForcedInlining = true)
        public static byte byteValue(Byte value) {
            return UnboxNode.unbox(value, Kind.Byte);
        }
    }

    @ClassSubstitution(Character.class)
    private static class CharacterSubstitutions {

        @MethodSubstitution(isForcedInlining = true)
        public static Character valueOf(char value) {
            return BoxNode.box(value, Character.class, Kind.Char);
        }

        @MethodSubstitution(isStatic = false, isForcedInlining = true)
        public static char charValue(Character value) {
            return UnboxNode.unbox(value, Kind.Char);
        }
    }

    @ClassSubstitution(Double.class)
    private static class DoubleSubstitutions {

        @MethodSubstitution(isForcedInlining = true)
        public static Double valueOf(double value) {
            return BoxNode.box(value, Double.class, Kind.Double);
        }

        @MethodSubstitution(isStatic = false, isForcedInlining = true)
        public static double doubleValue(Double value) {
            return UnboxNode.unbox(value, Kind.Double);
        }
    }

    @ClassSubstitution(Float.class)
    private static class FloatSubstitutions {

        @MethodSubstitution(isForcedInlining = true)
        public static Float valueOf(float value) {
            return BoxNode.box(value, Float.class, Kind.Float);
        }

        @MethodSubstitution(isStatic = false, isForcedInlining = true)
        public static float floatValue(Float value) {
            return UnboxNode.unbox(value, Kind.Float);
        }
    }

    @ClassSubstitution(Integer.class)
    private static class IntegerSubstitutions {

        @MethodSubstitution(isForcedInlining = true)
        public static Integer valueOf(int value) {
            return BoxNode.box(value, Integer.class, Kind.Int);
        }

        @MethodSubstitution(isStatic = false, isForcedInlining = true)
        public static int intValue(Integer value) {
            return UnboxNode.unbox(value, Kind.Int);
        }
    }

    @ClassSubstitution(Long.class)
    private static class LongSubstitutions {

        @MethodSubstitution(isForcedInlining = true)
        public static Long valueOf(long value) {
            return BoxNode.box(value, Long.class, Kind.Long);
        }

        @MethodSubstitution(isStatic = false, isForcedInlining = true)
        public static long longValue(Long value) {
            return UnboxNode.unbox(value, Kind.Long);
        }
    }

    @ClassSubstitution(Short.class)
    private static class ShortSubstitutions {

        @MethodSubstitution(isForcedInlining = true)
        public static Short valueOf(short value) {
            return BoxNode.box(value, Short.class, Kind.Short);
        }

        @MethodSubstitution(isStatic = false, isForcedInlining = true)
        public static short shortValue(Short value) {
            return UnboxNode.unbox(value, Kind.Short);
        }
    }

    public static Class<?>[] getClasses() {
        return new Class<?>[]{BooleanSubstitutions.class, ByteSubstitutions.class, CharacterSubstitutions.class, DoubleSubstitutions.class, FloatSubstitutions.class, IntegerSubstitutions.class,
                        LongSubstitutions.class, ShortSubstitutions.class};
    }
}
