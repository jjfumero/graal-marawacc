/*
 * Copyright (c) 2011, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.jvmci;

import static java.util.Objects.*;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.net.*;

import com.oracle.graal.api.meta.Assumptions.AssumptionResult;
import com.oracle.graal.api.meta.*;
import com.oracle.jvmci.common.*;

/**
 * Implementation of {@link JavaType} for primitive HotSpot types.
 */
public final class HotSpotResolvedPrimitiveType extends HotSpotResolvedJavaType implements HotSpotProxified {

    private final Kind kind;

    /**
     * Creates the Graal mirror for a primitive {@link Kind}.
     *
     * <p>
     * <b>NOTE</b>: Creating an instance of this class does not install the mirror for the
     * {@link Class} type. Use {@link #fromClass(Class)} instead.
     * </p>
     *
     * @param kind the Kind to create the mirror for
     */
    public HotSpotResolvedPrimitiveType(Kind kind) {
        super(String.valueOf(Character.toUpperCase(kind.getTypeChar())));
        this.kind = kind;
        assert mirror().isPrimitive() : mirror() + " not a primitive type";
    }

    @Override
    public int getModifiers() {
        return Modifier.ABSTRACT | Modifier.FINAL | Modifier.PUBLIC;
    }

    @Override
    public HotSpotResolvedObjectTypeImpl getArrayClass() {
        if (kind == Kind.Void) {
            return null;
        }
        Class<?> javaArrayMirror = Array.newInstance(mirror(), 0).getClass();
        return HotSpotResolvedObjectTypeImpl.fromObjectClass(javaArrayMirror);
    }

    public ResolvedJavaType getElementalType() {
        return this;
    }

    @Override
    public ResolvedJavaType getComponentType() {
        return null;
    }

    @Override
    public ResolvedJavaType asExactType() {
        return this;
    }

    @Override
    public ResolvedJavaType getSuperclass() {
        return null;
    }

    @Override
    public ResolvedJavaType[] getInterfaces() {
        return new ResolvedJavaType[0];
    }

    @Override
    public ResolvedJavaType getSingleImplementor() {
        throw new JVMCIError("Cannot call getSingleImplementor() on a non-interface type: %s", this);
    }

    @Override
    public ResolvedJavaType findLeastCommonAncestor(ResolvedJavaType otherType) {
        return null;
    }

    @Override
    public JavaConstant getObjectHub() {
        throw JVMCIError.unimplemented();
    }

    @Override
    public JavaConstant getJavaClass() {
        throw JVMCIError.unimplemented();
    }

    @Override
    public AssumptionResult<Boolean> hasFinalizableSubclass() {
        return new AssumptionResult<>(false);
    }

    @Override
    public boolean hasFinalizer() {
        return false;
    }

    @Override
    public boolean isArray() {
        return false;
    }

    @Override
    public boolean isPrimitive() {
        return true;
    }

    @Override
    public boolean isInitialized() {
        return true;
    }

    public boolean isLinked() {
        return true;
    }

    @Override
    public boolean isInstance(JavaConstant obj) {
        return false;
    }

    @Override
    public boolean isInstanceClass() {
        return false;
    }

    @Override
    public boolean isInterface() {
        return false;
    }

    @Override
    public boolean isAssignableFrom(ResolvedJavaType other) {
        assert other != null;
        return other.equals(this);
    }

    @Override
    public Kind getKind() {
        return kind;
    }

    @Override
    public boolean isJavaLangObject() {
        return false;
    }

    @Override
    public ResolvedJavaMethod resolveConcreteMethod(ResolvedJavaMethod method, ResolvedJavaType callerType) {
        return null;
    }

    @Override
    public ResolvedJavaMethod resolveMethod(ResolvedJavaMethod method, ResolvedJavaType callerType, boolean includeAbstract) {
        return null;
    }

    @Override
    public String toString() {
        return "HotSpotResolvedPrimitiveType<" + kind + ">";
    }

    @Override
    public AssumptionResult<ResolvedJavaType> findLeafConcreteSubtype() {
        return new AssumptionResult<>(this);
    }

    @Override
    public AssumptionResult<ResolvedJavaMethod> findUniqueConcreteMethod(ResolvedJavaMethod method) {
        return null;
    }

    @Override
    public ResolvedJavaField[] getInstanceFields(boolean includeSuperclasses) {
        return new ResolvedJavaField[0];
    }

    @Override
    public ResolvedJavaField[] getStaticFields() {
        return new ResolvedJavaField[0];
    }

    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        return null;
    }

    @Override
    public ResolvedJavaType resolve(ResolvedJavaType accessingClass) {
        requireNonNull(accessingClass);
        return this;
    }

    @Override
    public void initialize() {
    }

    @Override
    public ResolvedJavaField findInstanceFieldWithOffset(long offset, Kind expectedType) {
        return null;
    }

    @Override
    public String getSourceFileName() {
        throw JVMCIError.unimplemented();
    }

    @Override
    public Class<?> mirror() {
        return kind.toJavaClass();
    }

    @Override
    public URL getClassFilePath() {
        return null;
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    @Override
    public boolean isMember() {
        return false;
    }

    @Override
    public ResolvedJavaType getEnclosingType() {
        return null;
    }

    @Override
    public ResolvedJavaMethod[] getDeclaredConstructors() {
        return new ResolvedJavaMethod[0];
    }

    @Override
    public ResolvedJavaMethod[] getDeclaredMethods() {
        return new ResolvedJavaMethod[0];
    }

    @Override
    public ResolvedJavaMethod getClassInitializer() {
        return null;
    }

    @Override
    public boolean isTrustedInterfaceType() {
        return false;
    }
}
