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

/**
 * Represents a constant (boxed) value, such as an integer, floating point number, or object reference, within the
 * compiler and across the compiler/runtime interface. Exports a set of {@code Constant} instances that represent
 * frequently used constant values, such as {@link #NULL_OBJECT}.
 */
public final class Constant extends Value {

    private static final long serialVersionUID = -6355452536852663986L;

    private static final Constant[] INT_CONSTANT_CACHE = new Constant[100];
    static {
        for (int i = 0; i < INT_CONSTANT_CACHE.length; ++i) {
            INT_CONSTANT_CACHE[i] = new Constant(Kind.Int, null, i);
        }
    }

    public static final Constant NULL_OBJECT = new Constant(Kind.Object, null, 0);
    public static final Constant INT_MINUS_1 = new Constant(Kind.Int, null, -1);
    public static final Constant INT_0 = forInt(0);
    public static final Constant INT_1 = forInt(1);
    public static final Constant INT_2 = forInt(2);
    public static final Constant INT_3 = forInt(3);
    public static final Constant INT_4 = forInt(4);
    public static final Constant INT_5 = forInt(5);
    public static final Constant LONG_0 = new Constant(Kind.Long, null, 0L);
    public static final Constant LONG_1 = new Constant(Kind.Long, null, 1L);
    public static final Constant FLOAT_0 = new Constant(Kind.Float, null, Float.floatToRawIntBits(0.0F));
    public static final Constant FLOAT_1 = new Constant(Kind.Float, null, Float.floatToRawIntBits(1.0F));
    public static final Constant FLOAT_2 = new Constant(Kind.Float, null, Float.floatToRawIntBits(2.0F));
    public static final Constant DOUBLE_0 = new Constant(Kind.Double, null, Double.doubleToRawLongBits(0.0D));
    public static final Constant DOUBLE_1 = new Constant(Kind.Double, null, Double.doubleToRawLongBits(1.0D));
    public static final Constant TRUE = new Constant(Kind.Boolean, null, 1L);
    public static final Constant FALSE = new Constant(Kind.Boolean, null, 0L);

    static {
        assert FLOAT_0 != forFloat(-0.0F) : "Constant for 0.0f must be different from -0.0f";
        assert DOUBLE_0 != forDouble(-0.0d) : "Constant for 0.0d must be different from -0.0d";
        assert NULL_OBJECT.isNull();
    }

    /**
     * The boxed object value if {@code !kind.isObject()} otherwise the (possibly null)
     * {@link #getPrimitiveAnnotation() annotation} for a primitive value.
     */
    private final Object object;

    /**
     * The boxed primitive value as a {@code long}. This is ignored iff {@code kind.isObject()}. For {@code float} and
     * {@code double} values, this value is the result of {@link Float#floatToRawIntBits(float)} and
     * {@link Double#doubleToRawLongBits(double)} respectively.
     */
    private final long primitive;

    private Constant(Kind kind, Object object, long primitive) {
        super(kind);
        this.object = object;
        this.primitive = primitive;
    }

    /**
     * Checks whether this constant is non-null.
     *
     * @return {@code true} if this constant is a primitive, or an object constant that is not null
     */
    public boolean isNonNull() {
        return getKind() != Kind.Object || object != null;
    }

    /**
     * Checks whether this constant is null.
     *
     * @return {@code true} if this constant is the null constant
     */
    public boolean isNull() {
        return getKind() == Kind.Object && object == null;
    }

    /**
     * Checks whether this constant is the default value for its kind (null, 0, 0.0, false).
     *
     * @return {@code true} if this constant is the default value for its kind
     */
    public boolean isDefaultForKind() {
        return object == null && primitive == 0;
    }

    @Override
    public String toString() {
        String annotationSuffix = "";
        if (getKind() != Kind.Object && getPrimitiveAnnotation() != null) {
            annotationSuffix = "{" + getPrimitiveAnnotation() + "}";
        }
        return getKind().getJavaName() + "[" + getKind().format(asBoxedValue()) + (getKind() != Kind.Object ? "|0x" + Long.toHexString(primitive) : "") + "]" + annotationSuffix;
    }

    /**
     * Returns the value of this constant as a boxed Java value.
     *
     * @return the value of this constant
     */
    public Object asBoxedValue() {
        switch (getKind()) {
            case Byte:
                return (byte) asInt();
            case Boolean:
                return asInt() == 0 ? Boolean.FALSE : Boolean.TRUE;
            case Short:
                return (short) primitive;
            case Char:
                return (char) primitive;
            case Jsr:
                return (int) primitive;
            case Int:
                return (int) primitive;
            case Long:
                return primitive;
            case Float:
                return asFloat();
            case Double:
                return asDouble();
            case Object:
                return object;
        }
        throw new IllegalArgumentException();
    }

    private boolean valueEqual(Constant other, boolean ignoreKind) {
        // must have equivalent kinds to be equal
        if (!ignoreKind && getKind() != other.getKind()) {
            return false;
        }
        if (getKind() == Kind.Object) {
            return object == other.object;
        }
        return primitive == other.primitive && getPrimitiveAnnotation() == other.getPrimitiveAnnotation();
    }

    /**
     * Returns the primitive int value this constant represents. The constant must have a {@link Kind#getStackKind()} of
     * {@link Kind#Int}, or kind {@link Kind#Jsr}.
     *
     * @return the constant value
     */
    public int asInt() {
        assert getKind().getStackKind() == Kind.Int || getKind() == Kind.Jsr;
        return (int) primitive;
    }

    /**
     * Returns the primitive boolean value this constant represents. The constant must have kind {@link Kind#Boolean}.
     *
     * @return the constant value
     */
    public boolean asBoolean() {
        assert getKind() == Kind.Boolean;
        return primitive != 0L;
    }

    /**
     * Returns the primitive long value this constant represents. The constant must have kind {@link Kind#Long}, a
     * {@link Kind#getStackKind()} of {@link Kind#Int}, or kind {@link Kind#Jsr}.
     *
     * @return the constant value
     */
    public long asLong() {
        assert getKind() == Kind.Long || getKind().getStackKind() == Kind.Int || getKind() == Kind.Jsr;
        return primitive;
    }

    /**
     * Returns the primitive float value this constant represents. The constant must have kind {@link Kind#Float}.
     *
     * @return the constant value
     */
    public float asFloat() {
        assert getKind() == Kind.Float;
        return Float.intBitsToFloat((int) primitive);
    }

    /**
     * Returns the primitive double value this constant represents. The constant must have kind {@link Kind#Double}.
     *
     * @return the constant value
     */
    public double asDouble() {
        assert getKind() == Kind.Double;
        return Double.longBitsToDouble(primitive);
    }

    /**
     * Returns the object reference this constant represents. The constant must have kind {@link Kind#Object}.
     *
     * @return the constant value
     */
    public Object asObject() {
        assert getKind() == Kind.Object;
        return object;
    }

    /**
     * Gets the annotation (if any) associated with this constant.
     *
     * @return null if this constant is not primitive or has no annotation
     */
    public Object getPrimitiveAnnotation() {
        return getKind() == Kind.Object ? null : object;
    }

    /**
     * Computes the hashcode of this constant.
     *
     * @return a suitable hashcode for this constant
     */
    @Override
    public int hashCode() {
        if (getKind() == Kind.Object) {
            return System.identityHashCode(object);
        }
        return (int) primitive;
    }

    /**
     * Checks whether this constant equals another object. This is only true if the other object is a constant that has
     * the same {@linkplain #getKind() kind}, value and {@link #getPrimitiveAnnotation() annotation}.
     *
     * @param o the object to compare equality
     * @return {@code true} if this constant is equivalent to the specified object
     */
    @Override
    public boolean equals(Object o) {
        return o == this || o instanceof Constant && valueEqual((Constant) o, false);
    }

    /**
     * Creates a boxed double constant.
     *
     * @param d the double value to box
     * @return a boxed copy of {@code value}
     */
    public static Constant forDouble(double d) {
        if (Double.compare(0.0D, d) == 0) {
            return DOUBLE_0;
        }
        if (Double.compare(d, 1.0D) == 0) {
            return DOUBLE_1;
        }
        return new Constant(Kind.Double, null, Double.doubleToRawLongBits(d));
    }

    /**
     * Creates a boxed float constant.
     *
     * @param f the float value to box
     * @return a boxed copy of {@code value}
     */
    public static Constant forFloat(float f) {
        if (Float.compare(f, 0.0F) == 0) {
            return FLOAT_0;
        }
        if (Float.compare(f, 1.0F) == 0) {
            return FLOAT_1;
        }
        if (Float.compare(f, 2.0F) == 0) {
            return FLOAT_2;
        }
        return new Constant(Kind.Float, null, Float.floatToRawIntBits(f));
    }

    /**
     * Creates a boxed long constant.
     *
     * @param i the long value to box
     * @return a boxed copy of {@code value}
     */
    public static Constant forLong(long i) {
        return i == 0 ? LONG_0 : i == 1 ? LONG_1 : new Constant(Kind.Long, null, i);
    }

    /**
     * Creates a boxed integer constant.
     *
     * @param i the integer value to box
     * @return a boxed copy of {@code value}
     */
    public static Constant forInt(int i) {
        if (i == -1) {
            return INT_MINUS_1;
        }
        if (i >= 0 && i < INT_CONSTANT_CACHE.length) {
            return INT_CONSTANT_CACHE[i];
        }
        return new Constant(Kind.Int, null, i);
    }

    /**
     * Creates a boxed byte constant.
     *
     * @param i the byte value to box
     * @return a boxed copy of {@code value}
     */
    public static Constant forByte(byte i) {
        return new Constant(Kind.Byte, null, i);
    }

    /**
     * Creates a boxed boolean constant.
     *
     * @param i the boolean value to box
     * @return a boxed copy of {@code value}
     */
    public static Constant forBoolean(boolean i) {
        return i ? TRUE : FALSE;
    }

    /**
     * Creates a boxed char constant.
     *
     * @param i the char value to box
     * @return a boxed copy of {@code value}
     */
    public static Constant forChar(char i) {
        return new Constant(Kind.Char, null, i);
    }

    /**
     * Creates a boxed short constant.
     *
     * @param i the short value to box
     * @return a boxed copy of {@code value}
     */
    public static Constant forShort(short i) {
        return new Constant(Kind.Short, null, i);
    }

    /**
     * Creates a boxed address (jsr/ret address) constant.
     *
     * @param i the address value to box
     * @return a boxed copy of {@code value}
     */
    public static Constant forJsr(int i) {
        return new Constant(Kind.Jsr, null, i);
    }

    /**
     * Creates a boxed object constant.
     *
     * @param o the object value to box
     * @return a boxed copy of {@code value}
     */
    public static Constant forObject(Object o) {
        if (o == null) {
            return NULL_OBJECT;
        }
        return new Constant(Kind.Object, o, 0L);
    }

    /**
     * Creates an annotated int or long constant. An annotation enables a client to associate some extra semantic or
     * debugging information with a primitive. An annotated primitive constant is never {@linkplain #equals(Object)
     * equal} to a non-annotated constant.
     *
     * @param kind the type of this constant
     * @param i the value of this constant
     * @param annotation an arbitrary non-null object
     */
    public static Constant forIntegerKind(Kind kind, long i, Object annotation) {
        switch (kind) {
            case Int:
                return new Constant(kind, annotation, (int) i);
            case Long:
                return new Constant(kind, annotation, i);
            default:
                throw new IllegalArgumentException("not an integer kind: " + kind);
        }
    }

    /**
     * Creates a boxed constant for the given kind from an Object. The object needs to be of the Java boxed type
     * corresponding to the kind.
     *
     * @param kind the kind of the constant to create
     * @param value the Java boxed value: a {@link Byte} instance for {@link Kind#Byte}, etc.
     * @return the boxed copy of {@code value}
     */
    public static Constant forBoxed(Kind kind, Object value) {
        switch (kind) {
            case Boolean:
                return forBoolean((Boolean) value);
            case Byte:
                return forByte((Byte) value);
            case Char:
                return forChar((Character) value);
            case Short:
                return forShort((Short) value);
            case Int:
                return forInt((Integer) value);
            case Long:
                return forLong((Long) value);
            case Float:
                return forFloat((Float) value);
            case Double:
                return forDouble((Double) value);
            case Object:
                return forObject(value);
            default:
                throw new RuntimeException("cannot create Constant for boxed " + kind + " value");
        }
    }

    /**
     * Returns a constant with the default value for the given kind.
     */
    public static Constant defaultForKind(Kind kind) {
        switch(kind) {
            case Boolean:
                return FALSE;
            case Byte:
                return forByte((byte) 0);
            case Char:
                return forChar((char) 0);
            case Short:
                return forShort((short) 0);
            case Int:
                return INT_0;
            case Double:
                return DOUBLE_0;
            case Float:
                return FLOAT_0;
            case Long:
                return LONG_0;
            case Object:
                return NULL_OBJECT;
            default:
                throw new IllegalArgumentException(kind.toString());
        }
    }
}
