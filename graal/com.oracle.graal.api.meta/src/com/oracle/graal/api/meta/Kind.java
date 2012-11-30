/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.reflect.*;

/**
 * Denotes the basic kinds of types in CRI, including the all the Java primitive types, for example, {@link Kind#Int}
 * for {@code int} and {@link Kind#Object} for all object types. A kind has a single character short name, a Java name,
 * and a set of flags further describing its behavior.
 */
public enum Kind {
    /** The primitive boolean kind, represented as an int on the stack. */
    Boolean('z', "boolean", true, java.lang.Boolean.TYPE, java.lang.Boolean.class),

    /** The primitive byte kind, represented as an int on the stack. */
    Byte('b', "byte", true, java.lang.Byte.TYPE, java.lang.Byte.class),

    /** The primitive short kind, represented as an int on the stack. */
    Short('s', "short", true, java.lang.Short.TYPE, java.lang.Short.class),

    /** The primitive char kind, represented as an int on the stack. */
    Char('c', "char", true, java.lang.Character.TYPE, java.lang.Character.class),

    /** The primitive int kind, represented as an int on the stack. */
    Int('i', "int", true, java.lang.Integer.TYPE, java.lang.Integer.class),

    /** The primitive float kind. */
    Float('f', "float", false, java.lang.Float.TYPE, java.lang.Float.class),

    /** The primitive long kind. */
    Long('j', "long", false, java.lang.Long.TYPE, java.lang.Long.class),

    /** The primitive double kind. */
    Double('d', "double", false, java.lang.Double.TYPE, java.lang.Double.class),

    /** The Object kind, also used for arrays. */
    Object('a', "Object", false, null, null),

    /** The void float kind. */
    Void('v', "void", false, java.lang.Void.TYPE, java.lang.Void.class),

    /** Denote a bytecode address in a {@code JSR} bytecode. */
    Jsr('r', "jsr", false, null, null),

    /** The non-type. */
    Illegal('-', "illegal", false, null, null);

    private final char typeChar;
    private final String javaName;
    private final boolean isStackInt;
    private final Class primitiveJavaClass;
    private final Class boxedJavaClass;

    private Kind(char typeChar, String javaName, boolean isStackInt, Class primitiveJavaClass, Class boxedJavasClass) {
        this.typeChar = typeChar;
        this.javaName = javaName;
        this.isStackInt = isStackInt;
        this.primitiveJavaClass = primitiveJavaClass;
        this.boxedJavaClass = boxedJavasClass;
        assert primitiveJavaClass == null || javaName.equals(primitiveJavaClass.getName());
    }

    /**
     * Returns the name of the kind as a single character.
     */
    public char getTypeChar() {
        return typeChar;
    }

    /**
     * Returns the name of this kind which will also be it Java programming language name if it is
     * {@linkplain #isPrimitive() primitive} or {@code void}.
     */
    public String getJavaName() {
        return javaName;
    }

    /**
     * Checks whether this type is a Java primitive type.
     *
     * @return {@code true} if this is {@link #Boolean}, {@link #Byte}, {@link #Char}, {@link #Short}, {@link #Int},
     *         {@link #Long}, {@link #Float}, {@link #Double}, or {@link #Void}.
     */
    public boolean isPrimitive() {
        return primitiveJavaClass != null;
    }

    /**
     * Returns the kind that represents this kind when on the Java operand stack.
     *
     * @return the kind used on the operand stack
     */
    public Kind getStackKind() {
        if (isStackInt) {
            return Int;
        }
        return this;
    }

    /**
     * Returns the kind corresponding to the Java type string.
     *
     * @param typeString the Java type string
     * @return the kind
     */
    public static Kind fromTypeString(String typeString) {
        assert typeString.length() > 0;
        final char first = typeString.charAt(0);
        if (first == '[' || first == 'L') {
            return Kind.Object;
        }
        return Kind.fromPrimitiveOrVoidTypeChar(first);
    }

    /**
     * Returns the kind from the character describing a primitive or void.
     *
     * @param ch the character
     * @return the kind
     */
    public static Kind fromPrimitiveOrVoidTypeChar(char ch) {
        switch (ch) {
            case 'Z':
                return Boolean;
            case 'C':
                return Char;
            case 'F':
                return Float;
            case 'D':
                return Double;
            case 'B':
                return Byte;
            case 'S':
                return Short;
            case 'I':
                return Int;
            case 'J':
                return Long;
            case 'V':
                return Void;
        }
        throw new IllegalArgumentException("unknown primitive or void type character: " + ch);
    }

    /**
     * Returns the Kind representing the given Java class.
     *
     * @param klass the class
     * @return the kind
     */
    public static Kind fromJavaClass(Class< ? > klass) {
        if (klass == Boolean.primitiveJavaClass) {
            return Boolean;
        } else if (klass == Byte.primitiveJavaClass) {
            return Byte;
        } else if (klass == Short.primitiveJavaClass) {
            return Short;
        } else if (klass == Char.primitiveJavaClass) {
            return Char;
        } else if (klass == Int.primitiveJavaClass) {
            return Int;
        } else if (klass == Long.primitiveJavaClass) {
            return Long;
        } else if (klass == Float.primitiveJavaClass) {
            return Float;
        } else if (klass == Double.primitiveJavaClass) {
            return Double;
        } else if (klass == Void.primitiveJavaClass) {
            return Void;
        } else {
            return Object;
        }
    }

    /**
     * Returns the Java class representing this kind.
     *
     * @return the Java class
     */
    public Class< ? > toJavaClass() {
        return primitiveJavaClass;
    }

    /**
     * Returns the Java class for instances of boxed values of this kind.
     *
     * @return the Java class
     */
    public Class< ? > toBoxedJavaClass() {
        return boxedJavaClass;
    }

    /**
     * Converts this value type to a string.
     */
    @Override
    public String toString() {
        return javaName;
    }

    /**
     * Marker interface for types that should be {@linkplain Kind#format(Object) formatted} with their
     * {@link Object#toString()} value.
     */
    public interface FormatWithToString {
    }

    /**
     * Gets a formatted string for a given value of this kind.
     *
     * @param value a value of this kind
     * @return a formatted string for {@code value} based on this kind
     */
    public String format(Object value) {
        if (this == Kind.Object) {
            if (value == null) {
                return "null";
            } else {
                if (value instanceof String) {
                    String s = (String) value;
                    if (s.length() > 50) {
                        return "String:\"" + s.substring(0, 30) + "...\"";
                    } else {
                        return "String:\"" + s + '"';
                    }
                } else if (value instanceof JavaType) {
                    return "JavaType:" + MetaUtil.toJavaName((JavaType) value);
                } else if (value instanceof Enum || value instanceof FormatWithToString || value instanceof Number) {
                    return MetaUtil.getSimpleName(value.getClass(), true) + ":" + String.valueOf(value);
                } else if (value instanceof Class< ? >) {
                    return "Class:" + ((Class< ? >) value).getName();
                } else if (value.getClass().isArray()) {
                    return formatArray(value);
                } else {
                    return MetaUtil.getSimpleName(value.getClass(), true) + "@" + System.identityHashCode(value);
                }
            }
        } else {
            return value.toString();
        }
    }

    private static final int MAX_FORMAT_ARRAY_LENGTH = 5;

    private static String formatArray(Object array) {
        Class< ? > componentType = array.getClass().getComponentType();
        assert componentType != null;
        int arrayLength = Array.getLength(array);
        StringBuilder buf = new StringBuilder(MetaUtil.getSimpleName(componentType, true)).append('[').append(arrayLength).append("]{");
        int length = Math.min(MAX_FORMAT_ARRAY_LENGTH, arrayLength);
        boolean primitive = componentType.isPrimitive();
        for (int i = 0; i < length; i++) {
            if (primitive) {
                buf.append(Array.get(array, i));
            } else {
                Object o = ((Object[]) array)[i];
                buf.append(Kind.Object.format(o));
            }
            if (i != length - 1) {
                buf.append(", ");
            }
        }
        if (arrayLength != length) {
            buf.append(", ...");
        }
        return buf.append('}').toString();
    }

    /**
     * The minimum value that can be represented as a value of this kind.
     *
     * @return the minimum value
     */
    public long getMinValue() {
        switch (this) {
            case Boolean:
                return 0;
            case Byte:
                return java.lang.Byte.MIN_VALUE;
            case Char:
                return java.lang.Character.MIN_VALUE;
            case Short:
                return java.lang.Short.MIN_VALUE;
            case Jsr:
            case Int:
                return java.lang.Integer.MIN_VALUE;
            case Long:
                return java.lang.Long.MIN_VALUE;
            default:
                throw new IllegalArgumentException("illegal call to minValue on " + this);
        }
    }

    /**
     * The maximum value that can be represented as a value of this kind.
     *
     * @return the maximum value
     */
    public long getMaxValue() {
        switch (this) {
            case Boolean:
                return 1;
            case Byte:
                return java.lang.Byte.MAX_VALUE;
            case Char:
                return java.lang.Character.MAX_VALUE;
            case Short:
                return java.lang.Short.MAX_VALUE;
            case Jsr:
            case Int:
                return java.lang.Integer.MAX_VALUE;
            case Long:
                return java.lang.Long.MAX_VALUE;
            default:
                throw new IllegalArgumentException("illegal call to maxValue on " + this);
        }
    }

    /**
     * Number of bits that are necessary to represent a value of this kind.
     *
     * @return the number of bits
     */
    public int getBitCount() {
        switch (this) {
            case Boolean:
                return 1;
            case Byte:
                return 8;
            case Char:
            case Short:
                return 16;
            case Jsr:
            case Int:
                return 32;
            case Long:
                return 64;
            default:
                throw new IllegalArgumentException("illegal call to bits on " + this);
        }
    }
}
