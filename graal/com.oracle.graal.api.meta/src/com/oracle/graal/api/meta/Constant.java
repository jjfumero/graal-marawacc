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
 * Represents a constant (boxed) value, such as an integer, floating point number, or object
 * reference, within the compiler and across the compiler/runtime interface. Exports a set of
 * {@code Constant} instances that represent frequently used constant values, such as
 * {@link #NULL_OBJECT}.
 */
public abstract class Constant extends Value {

    private static final long serialVersionUID = -6355452536852663986L;

    private static final Constant[] INT_CONSTANT_CACHE = new Constant[100];
    static {
        for (int i = 0; i < INT_CONSTANT_CACHE.length; ++i) {
            INT_CONSTANT_CACHE[i] = new PrimitiveConstant(Kind.Int, i);
        }
    }

    public static final Constant NULL_OBJECT = new NullConstant();
    public static final Constant INT_MINUS_1 = new PrimitiveConstant(Kind.Int, -1);
    public static final Constant INT_0 = forInt(0);
    public static final Constant INT_1 = forInt(1);
    public static final Constant INT_2 = forInt(2);
    public static final Constant INT_3 = forInt(3);
    public static final Constant INT_4 = forInt(4);
    public static final Constant INT_5 = forInt(5);
    public static final Constant LONG_0 = new PrimitiveConstant(Kind.Long, 0L);
    public static final Constant LONG_1 = new PrimitiveConstant(Kind.Long, 1L);
    public static final Constant FLOAT_0 = new PrimitiveConstant(Kind.Float, Float.floatToRawIntBits(0.0F));
    public static final Constant FLOAT_1 = new PrimitiveConstant(Kind.Float, Float.floatToRawIntBits(1.0F));
    public static final Constant FLOAT_2 = new PrimitiveConstant(Kind.Float, Float.floatToRawIntBits(2.0F));
    public static final Constant DOUBLE_0 = new PrimitiveConstant(Kind.Double, Double.doubleToRawLongBits(0.0D));
    public static final Constant DOUBLE_1 = new PrimitiveConstant(Kind.Double, Double.doubleToRawLongBits(1.0D));
    public static final Constant TRUE = new PrimitiveConstant(Kind.Boolean, 1L);
    public static final Constant FALSE = new PrimitiveConstant(Kind.Boolean, 0L);

    static {
        assert FLOAT_0 != forFloat(-0.0F) : "Constant for 0.0f must be different from -0.0f";
        assert DOUBLE_0 != forDouble(-0.0d) : "Constant for 0.0d must be different from -0.0d";
        assert NULL_OBJECT.isNull();
    }

    protected Constant(Kind kind) {
        super(kind);
    }

    /**
     * Checks whether this constant is null.
     *
     * @return {@code true} if this constant is the null constant
     */
    public abstract boolean isNull();

    /**
     * Checks whether this constant is non-null.
     *
     * @return {@code true} if this constant is a primitive, or an object constant that is not null
     */
    public final boolean isNonNull() {
        return !isNull();
    }

    /**
     * Checks whether this constant is the default value for its kind (null, 0, 0.0, false).
     *
     * @return {@code true} if this constant is the default value for its kind
     */
    public abstract boolean isDefaultForKind();

    /**
     * Returns the value of this constant as a boxed Java value.
     *
     * @return the value of this constant
     */
    public abstract Object asBoxedPrimitive();

    /**
     * Returns the primitive int value this constant represents. The constant must have a
     * {@link Kind#getStackKind()} of {@link Kind#Int}.
     *
     * @return the constant value
     */
    public abstract int asInt();

    /**
     * Returns the primitive boolean value this constant represents. The constant must have kind
     * {@link Kind#Boolean}.
     *
     * @return the constant value
     */
    public abstract boolean asBoolean();

    /**
     * Returns the primitive long value this constant represents. The constant must have kind
     * {@link Kind#Long}, a {@link Kind#getStackKind()} of {@link Kind#Int}.
     *
     * @return the constant value
     */
    public abstract long asLong();

    /**
     * Returns the primitive float value this constant represents. The constant must have kind
     * {@link Kind#Float}.
     *
     * @return the constant value
     */
    public abstract float asFloat();

    /**
     * Returns the primitive double value this constant represents. The constant must have kind
     * {@link Kind#Double}.
     *
     * @return the constant value
     */
    public abstract double asDouble();

    public String toValueString() {
        if (getKind() == Kind.Illegal) {
            return "illegal";
        } else {
            return getKind().format(asBoxedPrimitive());
        }
    }

    @Override
    public String toString() {
        if (getKind() == Kind.Illegal) {
            return "illegal";
        } else {
            return getKind().getJavaName() + "[" + toValueString() + "]";
        }
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
        return new PrimitiveConstant(Kind.Double, Double.doubleToRawLongBits(d));
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
        return new PrimitiveConstant(Kind.Float, Float.floatToRawIntBits(f));
    }

    /**
     * Creates a boxed long constant.
     *
     * @param i the long value to box
     * @return a boxed copy of {@code value}
     */
    public static Constant forLong(long i) {
        return i == 0 ? LONG_0 : i == 1 ? LONG_1 : new PrimitiveConstant(Kind.Long, i);
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
        return new PrimitiveConstant(Kind.Int, i);
    }

    /**
     * Creates a boxed byte constant.
     *
     * @param i the byte value to box
     * @return a boxed copy of {@code value}
     */
    public static Constant forByte(byte i) {
        return new PrimitiveConstant(Kind.Byte, i);
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
        return new PrimitiveConstant(Kind.Char, i);
    }

    /**
     * Creates a boxed short constant.
     *
     * @param i the short value to box
     * @return a boxed copy of {@code value}
     */
    public static Constant forShort(short i) {
        return new PrimitiveConstant(Kind.Short, i);
    }

    /**
     * Creates a {@link Constant} from a primitive integer of a certain kind.
     */
    public static Constant forIntegerKind(Kind kind, long i) {
        switch (kind) {
            case Int:
                return new PrimitiveConstant(kind, (int) i);
            case Long:
                return new PrimitiveConstant(kind, i);
            default:
                throw new IllegalArgumentException("not an integer kind: " + kind);
        }
    }

    /**
     * Creates a {@link Constant} from a primitive integer of a certain width.
     */
    public static Constant forPrimitiveInt(int bits, long i) {
        assert bits <= 64;
        if (bits > 32) {
            return new PrimitiveConstant(Kind.Long, i);
        } else {
            return new PrimitiveConstant(Kind.Int, (int) i);
        }
    }

    /**
     * Creates a boxed constant for the given boxed primitive value.
     *
     * @param value the Java boxed value
     * @return the primitive constant holding the {@code value}
     */
    public static Constant forBoxedPrimitive(Object value) {
        if (value instanceof Boolean) {
            return forBoolean((Boolean) value);
        } else if (value instanceof Byte) {
            return forByte((Byte) value);
        } else if (value instanceof Character) {
            return forChar((Character) value);
        } else if (value instanceof Short) {
            return forShort((Short) value);
        } else if (value instanceof Integer) {
            return forInt((Integer) value);
        } else if (value instanceof Long) {
            return forLong((Long) value);
        } else if (value instanceof Float) {
            return forFloat((Float) value);
        } else if (value instanceof Double) {
            return forDouble((Double) value);
        } else {
            return null;
        }
    }

    public static Constant forIllegal() {
        return new PrimitiveConstant(Kind.Illegal, 0);
    }

    /**
     * Returns a constant with the default value for the given kind.
     */
    public static Constant defaultForKind(Kind kind) {
        switch (kind) {
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

    /**
     * Returns the zero value for a given numeric kind.
     */
    public static Constant zero(Kind kind) {
        switch (kind) {
            case Byte:
                return forByte((byte) 0);
            case Char:
                return forChar((char) 0);
            case Double:
                return DOUBLE_0;
            case Float:
                return FLOAT_0;
            case Int:
                return INT_0;
            case Long:
                return LONG_0;
            case Short:
                return forShort((short) 0);
            default:
                throw new IllegalArgumentException(kind.toString());
        }
    }

    /**
     * Returns the one value for a given numeric kind.
     */
    public static Constant one(Kind kind) {
        switch (kind) {
            case Byte:
                return forByte((byte) 1);
            case Char:
                return forChar((char) 1);
            case Double:
                return DOUBLE_1;
            case Float:
                return FLOAT_1;
            case Int:
                return INT_1;
            case Long:
                return LONG_1;
            case Short:
                return forShort((short) 1);
            default:
                throw new IllegalArgumentException(kind.toString());
        }
    }

    /**
     * Adds two numeric constants.
     */
    public static Constant add(Constant x, Constant y) {
        assert x.getKind() == y.getKind();
        switch (x.getKind()) {
            case Byte:
                return forByte((byte) (x.asInt() + y.asInt()));
            case Char:
                return forChar((char) (x.asInt() + y.asInt()));
            case Double:
                return forDouble(x.asDouble() + y.asDouble());
            case Float:
                return forFloat(x.asFloat() + y.asFloat());
            case Int:
                return forInt(x.asInt() + y.asInt());
            case Long:
                return forLong(x.asLong() + y.asLong());
            case Short:
                return forShort((short) (x.asInt() + y.asInt()));
            default:
                throw new IllegalArgumentException(x.getKind().toString());
        }
    }

    /**
     * Multiplies two numeric constants.
     */
    public static Constant mul(Constant x, Constant y) {
        assert x.getKind() == y.getKind();
        switch (x.getKind()) {
            case Byte:
                return forByte((byte) (x.asInt() * y.asInt()));
            case Char:
                return forChar((char) (x.asInt() * y.asInt()));
            case Double:
                return forDouble(x.asDouble() * y.asDouble());
            case Float:
                return forFloat(x.asFloat() * y.asFloat());
            case Int:
                return forInt(x.asInt() * y.asInt());
            case Long:
                return forLong(x.asLong() * y.asLong());
            case Short:
                return forShort((short) (x.asInt() * y.asInt()));
            default:
                throw new IllegalArgumentException(x.getKind().toString());
        }
    }
}