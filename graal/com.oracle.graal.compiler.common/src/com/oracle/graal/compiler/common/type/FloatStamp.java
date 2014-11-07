/*
 * Copyright (c) 2012, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.compiler.common.type;

import static com.oracle.graal.compiler.common.calc.FloatConvert.*;

import java.util.function.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.compiler.common.spi.*;
import com.oracle.graal.compiler.common.type.ArithmeticOpTable.BinaryOp;
import com.oracle.graal.compiler.common.type.ArithmeticOpTable.FloatConvertOp;
import com.oracle.graal.compiler.common.type.ArithmeticOpTable.UnaryOp;

public class FloatStamp extends PrimitiveStamp {

    private final double lowerBound;
    private final double upperBound;
    private final boolean nonNaN;

    protected FloatStamp(int bits) {
        this(bits, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false);
    }

    public FloatStamp(int bits, double lowerBound, double upperBound, boolean nonNaN) {
        super(bits, OPS);
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
        this.nonNaN = nonNaN;
    }

    @Override
    public Stamp unrestricted() {
        return new FloatStamp(getBits());
    }

    @Override
    public Stamp illegal() {
        return new FloatStamp(getBits(), Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, true);
    }

    @Override
    public Stamp constant(Constant c, MetaAccessProvider meta) {
        JavaConstant jc = (JavaConstant) c;
        assert jc.getKind().isNumericFloat() && jc.getKind().getBitCount() == getBits();
        return StampFactory.forConstant(jc);
    }

    @Override
    public boolean isLegal() {
        return lowerBound <= upperBound || !nonNaN;
    }

    @Override
    public Kind getStackKind() {
        if (getBits() > 32) {
            return Kind.Double;
        } else {
            return Kind.Float;
        }
    }

    @Override
    public LIRKind getLIRKind(LIRKindTool tool) {
        return tool.getFloatingKind(getBits());
    }

    @Override
    public ResolvedJavaType javaType(MetaAccessProvider metaAccess) {
        switch (getBits()) {
            case 32:
                return metaAccess.lookupJavaType(Float.TYPE);
            case 64:
                return metaAccess.lookupJavaType(Double.TYPE);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    /**
     * The (inclusive) lower bound on the value described by this stamp.
     */
    public double lowerBound() {
        return lowerBound;
    }

    /**
     * The (inclusive) upper bound on the value described by this stamp.
     */
    public double upperBound() {
        return upperBound;
    }

    public boolean isNonNaN() {
        return nonNaN;
    }

    public boolean isUnrestricted() {
        return lowerBound == Double.NEGATIVE_INFINITY && upperBound == Double.POSITIVE_INFINITY && !nonNaN;
    }

    public boolean contains(double value) {
        if (Double.isNaN(value)) {
            return !nonNaN;
        } else {
            return value >= lowerBound && value <= upperBound;
        }
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        str.append('f');
        str.append(getBits());
        str.append(nonNaN ? "!" : "");
        if (lowerBound == upperBound) {
            str.append(" [").append(lowerBound).append(']');
        } else if (lowerBound != Double.NEGATIVE_INFINITY || upperBound != Double.POSITIVE_INFINITY) {
            str.append(" [").append(lowerBound).append(" - ").append(upperBound).append(']');
        }
        return str.toString();
    }

    private static double meetBounds(double a, double b, DoubleBinaryOperator op) {
        if (Double.isNaN(a)) {
            return b;
        } else if (Double.isNaN(b)) {
            return a;
        } else {
            return op.applyAsDouble(a, b);
        }
    }

    @Override
    public Stamp meet(Stamp otherStamp) {
        if (otherStamp == this) {
            return this;
        }
        if (!(otherStamp instanceof FloatStamp)) {
            return StampFactory.illegal(Kind.Illegal);
        }
        FloatStamp other = (FloatStamp) otherStamp;
        assert getBits() == other.getBits();
        double meetUpperBound = meetBounds(upperBound, other.upperBound, Math::max);
        double meetLowerBound = meetBounds(lowerBound, other.lowerBound, Math::min);
        boolean meetNonNaN = nonNaN && other.nonNaN;
        if (Double.compare(meetLowerBound, lowerBound) == 0 && Double.compare(meetUpperBound, upperBound) == 0 && meetNonNaN == nonNaN) {
            return this;
        } else if (Double.compare(meetLowerBound, other.lowerBound) == 0 && Double.compare(meetUpperBound, other.upperBound) == 0 && meetNonNaN == other.nonNaN) {
            return other;
        } else {
            return new FloatStamp(getBits(), meetLowerBound, meetUpperBound, meetNonNaN);
        }
    }

    @Override
    public Stamp join(Stamp otherStamp) {
        if (otherStamp == this) {
            return this;
        }
        if (!(otherStamp instanceof FloatStamp)) {
            return StampFactory.illegal(Kind.Illegal);
        }
        FloatStamp other = (FloatStamp) otherStamp;
        assert getBits() == other.getBits();
        double joinUpperBound = Math.min(upperBound, other.upperBound);
        double joinLowerBound = Math.max(lowerBound, other.lowerBound);
        boolean joinNonNaN = nonNaN || other.nonNaN;
        if (Double.compare(joinLowerBound, lowerBound) == 0 && Double.compare(joinUpperBound, upperBound) == 0 && joinNonNaN == nonNaN) {
            return this;
        } else if (Double.compare(joinLowerBound, other.lowerBound) == 0 && Double.compare(joinUpperBound, other.upperBound) == 0 && joinNonNaN == other.nonNaN) {
            return other;
        } else {
            return new FloatStamp(getBits(), joinLowerBound, joinUpperBound, joinNonNaN);
        }
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        long temp;
        result = prime * result + super.hashCode();
        temp = Double.doubleToLongBits(lowerBound);
        result = prime * result + (int) (temp ^ (temp >>> 32));
        result = prime * result + (nonNaN ? 1231 : 1237);
        temp = Double.doubleToLongBits(upperBound);
        result = prime * result + (int) (temp ^ (temp >>> 32));
        return result;
    }

    @Override
    public boolean isCompatible(Stamp stamp) {
        if (this == stamp) {
            return true;
        }
        if (stamp instanceof FloatStamp) {
            FloatStamp other = (FloatStamp) stamp;
            return getBits() == other.getBits();
        }
        return false;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass() || !super.equals(obj)) {
            return false;
        }
        FloatStamp other = (FloatStamp) obj;
        if (Double.doubleToLongBits(lowerBound) != Double.doubleToLongBits(other.lowerBound)) {
            return false;
        }
        if (Double.doubleToLongBits(upperBound) != Double.doubleToLongBits(other.upperBound)) {
            return false;
        }
        if (nonNaN != other.nonNaN) {
            return false;
        }
        return super.equals(other);
    }

    @Override
    public JavaConstant asConstant() {
        if (nonNaN && Double.compare(lowerBound, upperBound) == 0) {
            switch (getBits()) {
                case 32:
                    return JavaConstant.forFloat((float) lowerBound);
                case 64:
                    return JavaConstant.forDouble(lowerBound);
            }
        }
        return null;
    }

    private static final ArithmeticOpTable OPS = new ArithmeticOpTable(

    new UnaryOp.Neg() {

        @Override
        public Constant foldConstant(Constant c) {
            PrimitiveConstant value = (PrimitiveConstant) c;
            switch (value.getKind()) {
                case Float:
                    return JavaConstant.forFloat(-value.asFloat());
                case Double:
                    return JavaConstant.forDouble(-value.asDouble());
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        }

        @Override
        public Stamp foldStamp(Stamp s) {
            FloatStamp stamp = (FloatStamp) s;
            return new FloatStamp(stamp.getBits(), -stamp.upperBound(), -stamp.lowerBound(), stamp.isNonNaN());
        }
    },

    new BinaryOp.Add(false, true) {

        @Override
        public Constant foldConstant(Constant const1, Constant const2) {
            PrimitiveConstant a = (PrimitiveConstant) const1;
            PrimitiveConstant b = (PrimitiveConstant) const2;
            assert a.getKind() == b.getKind();
            switch (a.getKind()) {
                case Float:
                    return JavaConstant.forFloat(a.asFloat() + b.asFloat());
                case Double:
                    return JavaConstant.forDouble(a.asDouble() + b.asDouble());
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        }

        @Override
        public Stamp foldStamp(Stamp stamp1, Stamp stamp2) {
            // TODO
            return stamp1.unrestricted();
        }

        @Override
        public boolean isNeutral(Constant value) {
            PrimitiveConstant n = (PrimitiveConstant) value;
            switch (n.getKind()) {
                case Float:
                    return Float.compare(n.asFloat(), -0.0f) == 0;
                case Double:
                    return Double.compare(n.asDouble(), -0.0) == 0;
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        }
    },

    new BinaryOp.Sub(false, false) {

        @Override
        public Constant foldConstant(Constant const1, Constant const2) {
            PrimitiveConstant a = (PrimitiveConstant) const1;
            PrimitiveConstant b = (PrimitiveConstant) const2;
            assert a.getKind() == b.getKind();
            switch (a.getKind()) {
                case Float:
                    return JavaConstant.forFloat(a.asFloat() - b.asFloat());
                case Double:
                    return JavaConstant.forDouble(a.asDouble() - b.asDouble());
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        }

        @Override
        public Stamp foldStamp(Stamp stamp1, Stamp stamp2) {
            // TODO
            return stamp1.unrestricted();
        }

        @Override
        public boolean isNeutral(Constant value) {
            PrimitiveConstant n = (PrimitiveConstant) value;
            switch (n.getKind()) {
                case Float:
                    return Float.compare(n.asFloat(), 0.0f) == 0;
                case Double:
                    return Double.compare(n.asDouble(), 0.0) == 0;
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        }
    },

    new BinaryOp.Mul(false, true) {

        @Override
        public Constant foldConstant(Constant const1, Constant const2) {
            PrimitiveConstant a = (PrimitiveConstant) const1;
            PrimitiveConstant b = (PrimitiveConstant) const2;
            assert a.getKind() == b.getKind();
            switch (a.getKind()) {
                case Float:
                    return JavaConstant.forFloat(a.asFloat() * b.asFloat());
                case Double:
                    return JavaConstant.forDouble(a.asDouble() * b.asDouble());
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        }

        @Override
        public Stamp foldStamp(Stamp a, Stamp b) {
            // TODO
            return a.unrestricted();
        }

        @Override
        public boolean isNeutral(Constant value) {
            PrimitiveConstant n = (PrimitiveConstant) value;
            switch (n.getKind()) {
                case Float:
                    return Float.compare(n.asFloat(), 1.0f) == 0;
                case Double:
                    return Double.compare(n.asDouble(), 1.0) == 0;
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        }
    },

    new BinaryOp.Div(false, false) {

        @Override
        public Constant foldConstant(Constant const1, Constant const2) {
            PrimitiveConstant a = (PrimitiveConstant) const1;
            PrimitiveConstant b = (PrimitiveConstant) const2;
            assert a.getKind() == b.getKind();
            switch (a.getKind()) {
                case Float:
                    return JavaConstant.forFloat(a.asFloat() / b.asFloat());
                case Double:
                    return JavaConstant.forDouble(a.asDouble() / b.asDouble());
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        }

        @Override
        public Stamp foldStamp(Stamp stamp1, Stamp stamp2) {
            // TODO
            return stamp1.unrestricted();
        }

        @Override
        public boolean isNeutral(Constant value) {
            PrimitiveConstant n = (PrimitiveConstant) value;
            switch (n.getKind()) {
                case Float:
                    return Float.compare(n.asFloat(), 1.0f) == 0;
                case Double:
                    return Double.compare(n.asDouble(), 1.0) == 0;
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        }
    },

    new BinaryOp.Rem(false, false) {

        @Override
        public Constant foldConstant(Constant const1, Constant const2) {
            PrimitiveConstant a = (PrimitiveConstant) const1;
            PrimitiveConstant b = (PrimitiveConstant) const2;
            assert a.getKind() == b.getKind();
            switch (a.getKind()) {
                case Float:
                    return JavaConstant.forFloat(a.asFloat() % b.asFloat());
                case Double:
                    return JavaConstant.forDouble(a.asDouble() % b.asDouble());
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        }

        @Override
        public Stamp foldStamp(Stamp stamp1, Stamp stamp2) {
            // TODO
            return stamp1.unrestricted();
        }
    },

    null, null, null, null,

    new UnaryOp.Abs() {

        @Override
        public Constant foldConstant(Constant c) {
            PrimitiveConstant value = (PrimitiveConstant) c;
            switch (value.getKind()) {
                case Float:
                    return JavaConstant.forFloat(Math.abs(value.asFloat()));
                case Double:
                    return JavaConstant.forDouble(Math.abs(value.asDouble()));
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        }

        @Override
        public Stamp foldStamp(Stamp s) {
            FloatStamp stamp = (FloatStamp) s;
            return new FloatStamp(stamp.getBits(), 0, Math.max(-stamp.lowerBound(), stamp.upperBound()), stamp.isNonNaN());
        }
    },

    new UnaryOp.Sqrt() {

        @Override
        public Constant foldConstant(Constant c) {
            PrimitiveConstant value = (PrimitiveConstant) c;
            switch (value.getKind()) {
                case Float:
                    return JavaConstant.forFloat((float) Math.sqrt(value.asFloat()));
                case Double:
                    return JavaConstant.forDouble(Math.sqrt(value.asDouble()));
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        }

        @Override
        public Stamp foldStamp(Stamp s) {
            return s.unrestricted();
        }
    },

    null, null, null,

    new FloatConvertOp(F2I) {

        @Override
        public Constant foldConstant(Constant c) {
            PrimitiveConstant value = (PrimitiveConstant) c;
            return JavaConstant.forInt((int) value.asFloat());
        }

        @Override
        public Stamp foldStamp(Stamp stamp) {
            assert stamp instanceof FloatStamp && ((FloatStamp) stamp).getBits() == 32;
            return StampFactory.forKind(Kind.Int);
        }
    },

    new FloatConvertOp(F2L) {

        @Override
        public Constant foldConstant(Constant c) {
            PrimitiveConstant value = (PrimitiveConstant) c;
            return JavaConstant.forLong((long) value.asFloat());
        }

        @Override
        public Stamp foldStamp(Stamp stamp) {
            assert stamp instanceof FloatStamp && ((FloatStamp) stamp).getBits() == 32;
            return StampFactory.forKind(Kind.Long);
        }
    },

    new FloatConvertOp(D2I) {

        @Override
        public Constant foldConstant(Constant c) {
            PrimitiveConstant value = (PrimitiveConstant) c;
            return JavaConstant.forInt((int) value.asDouble());
        }

        @Override
        public Stamp foldStamp(Stamp stamp) {
            assert stamp instanceof FloatStamp && ((FloatStamp) stamp).getBits() == 64;
            return StampFactory.forKind(Kind.Int);
        }
    },

    new FloatConvertOp(D2L) {

        @Override
        public Constant foldConstant(Constant c) {
            PrimitiveConstant value = (PrimitiveConstant) c;
            return JavaConstant.forLong((long) value.asDouble());
        }

        @Override
        public Stamp foldStamp(Stamp stamp) {
            assert stamp instanceof FloatStamp && ((FloatStamp) stamp).getBits() == 64;
            return StampFactory.forKind(Kind.Long);
        }
    },

    new FloatConvertOp(F2D) {

        @Override
        public Constant foldConstant(Constant c) {
            PrimitiveConstant value = (PrimitiveConstant) c;
            return JavaConstant.forDouble(value.asFloat());
        }

        @Override
        public Stamp foldStamp(Stamp stamp) {
            assert stamp instanceof FloatStamp && ((FloatStamp) stamp).getBits() == 32;
            return StampFactory.forKind(Kind.Double);
        }
    },

    new FloatConvertOp(D2F) {

        @Override
        public Constant foldConstant(Constant c) {
            PrimitiveConstant value = (PrimitiveConstant) c;
            return JavaConstant.forFloat((float) value.asDouble());
        }

        @Override
        public Stamp foldStamp(Stamp stamp) {
            assert stamp instanceof FloatStamp && ((FloatStamp) stamp).getBits() == 64;
            return StampFactory.forKind(Kind.Float);
        }
    });
}
