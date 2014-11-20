/*
 * Copyright (c) 2009, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.meta;

import java.lang.invoke.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.lir.*;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Represents a constant non-{@code null} object reference, within the compiler and across the
 * compiler/runtime interface.
 */
public final class HotSpotObjectConstantImpl extends AbstractValue implements HotSpotObjectConstant {

    private static final long serialVersionUID = 3592151693708093496L;

    public static JavaConstant forObject(Object object) {
        return forObject(object, false);
    }

    public static JavaConstant forObject(Object object, boolean compressed) {
        if (object == null) {
            return compressed ? HotSpotCompressedNullConstant.COMPRESSED_NULL : JavaConstant.NULL_OBJECT;
        } else {
            return new HotSpotObjectConstantImpl(object, compressed);
        }
    }

    public static JavaConstant forStableArray(Object object, int stableDimension, boolean isDefaultStable) {
        if (object == null) {
            return JavaConstant.NULL_OBJECT;
        } else {
            assert object.getClass().isArray();
            return new HotSpotObjectConstantImpl(object, false, stableDimension, isDefaultStable);
        }
    }

    public static JavaConstant forBoxedValue(Kind kind, Object value) {
        if (kind == Kind.Object) {
            return HotSpotObjectConstantImpl.forObject(value);
        } else {
            return JavaConstant.forBoxedPrimitive(value);
        }
    }

    public static Object asBoxedValue(Constant constant) {
        if (JavaConstant.isNull(constant)) {
            return null;
        } else if (constant instanceof HotSpotObjectConstantImpl) {
            return ((HotSpotObjectConstantImpl) constant).object;
        } else {
            return ((JavaConstant) constant).asBoxedPrimitive();
        }
    }

    private final Object object;
    private final boolean compressed;
    private final byte stableDimension;
    private final boolean isDefaultStable;

    private HotSpotObjectConstantImpl(Object object, boolean compressed, int stableDimension, boolean isDefaultStable) {
        super(LIRKind.reference(compressed ? Kind.Int : Kind.Object));
        this.object = object;
        this.compressed = compressed;
        this.stableDimension = (byte) stableDimension;
        this.isDefaultStable = isDefaultStable;
        assert object != null;
        assert stableDimension == 0 || (object != null && object.getClass().isArray());
        assert stableDimension >= 0 && stableDimension <= 255;
        assert !isDefaultStable || stableDimension > 0;
    }

    private HotSpotObjectConstantImpl(Object object, boolean compressed) {
        this(object, compressed, 0, false);
    }

    /**
     * Package-private accessor for the object represented by this constant.
     */
    Object object() {
        return object;
    }

    /**
     * Determines if the object represented by this constant is {@link Object#equals(Object) equal}
     * to a given object.
     */
    public boolean isEqualTo(Object obj) {
        return object.equals(obj);
    }

    /**
     * Gets the class of the object represented by this constant.
     */
    public Class<?> getObjectClass() {
        return object.getClass();
    }

    public boolean isCompressed() {
        return compressed;
    }

    public JavaConstant compress() {
        assert !compressed;
        return new HotSpotObjectConstantImpl(object, true, stableDimension, isDefaultStable);
    }

    public JavaConstant uncompress() {
        assert compressed;
        return new HotSpotObjectConstantImpl(object, false, stableDimension, isDefaultStable);
    }

    public JavaConstant getClassLoader() {
        if (object instanceof Class) {
            /*
             * This is an intrinsic for getClassLoader0, which occurs after any security checks. We
             * can't call that directly so just call getClassLoader.
             */
            return HotSpotObjectConstantImpl.forObject(((Class<?>) object).getClassLoader());
        }
        return null;
    }

    public int getIdentityHashCode() {
        return System.identityHashCode(object);
    }

    public JavaConstant getNodeClass() {
        if (object instanceof Class) {
            return HotSpotObjectConstantImpl.forObject(NodeClass.get((Class<?>) object));
        }
        return null;
    }

    public JavaConstant getComponentType() {
        if (object instanceof Class) {
            return HotSpotObjectConstantImpl.forObject(((Class<?>) object).getComponentType());
        }
        return null;
    }

    public JavaConstant getSuperclass() {
        if (object instanceof Class) {
            return HotSpotObjectConstantImpl.forObject(((Class<?>) object).getSuperclass());
        }
        return null;
    }

    public JavaConstant getCallSiteTarget(Assumptions assumptions) {
        if (object instanceof CallSite) {
            CallSite callSite = (CallSite) object;
            MethodHandle target = callSite.getTarget();
            if (!(callSite instanceof ConstantCallSite)) {
                if (assumptions == null || !assumptions.useOptimisticAssumptions()) {
                    return null;
                }
                assumptions.record(new Assumptions.CallSiteTargetValue(callSite, target));
            }
            return HotSpotObjectConstantImpl.forObject(target);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public JavaConstant getCompositeValueClass() {
        if (object instanceof Class) {
            Class<? extends CompositeValue> c = (Class<? extends CompositeValue>) object;
            assert CompositeValue.class.isAssignableFrom(c) : c;
            return HotSpotObjectConstantImpl.forObject(CompositeValueClass.get(c));
        }
        return null;
    }

    @SuppressFBWarnings(value = "ES_COMPARING_STRINGS_WITH_EQ", justification = "reference equality is what we want")
    public boolean isInternedString() {
        if (object instanceof String) {
            String s = (String) object;
            return s.intern() == s;
        }
        return false;
    }

    @Override
    public boolean isNull() {
        return false;
    }

    @Override
    public boolean isDefaultForKind() {
        return false;
    }

    @Override
    public Object asBoxedPrimitive() {
        throw new IllegalArgumentException();
    }

    @Override
    public int asInt() {
        throw new IllegalArgumentException();
    }

    @Override
    public boolean asBoolean() {
        throw new IllegalArgumentException();
    }

    @Override
    public long asLong() {
        throw new IllegalArgumentException();
    }

    @Override
    public float asFloat() {
        throw new IllegalArgumentException();
    }

    @Override
    public double asDouble() {
        throw new IllegalArgumentException();
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(object);
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        } else if (o instanceof HotSpotObjectConstantImpl) {
            HotSpotObjectConstantImpl other = (HotSpotObjectConstantImpl) o;
            return super.equals(o) && object == other.object && compressed == other.compressed && stableDimension == other.stableDimension && isDefaultStable == other.isDefaultStable;
        }
        return false;
    }

    @Override
    public String toValueString() {
        if (object instanceof String) {
            return (String) object;
        } else {
            return Kind.Object.format(object);
        }
    }

    @Override
    public String toString() {
        return (compressed ? "NarrowOop" : getKind().getJavaName()) + "[" + Kind.Object.format(object) + "]";
    }

    /**
     * Number of stable dimensions if this constant is a stable array.
     */
    public int getStableDimension() {
        return stableDimension & 0xff;
    }

    /**
     * Returns {@code true} if this is a stable array constant and its elements should be considered
     * as stable regardless of whether they are default values.
     */
    public boolean isDefaultStable() {
        return isDefaultStable;
    }
}
