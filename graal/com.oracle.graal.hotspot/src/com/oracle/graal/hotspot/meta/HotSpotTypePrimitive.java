/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.annotation.*;
import java.lang.reflect.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;

/**
 * Implementation of {@link JavaType} for primitive HotSpot types.
 */
public final class HotSpotTypePrimitive extends HotSpotJavaType implements ResolvedJavaType {

    private static final long serialVersionUID = -6208552348908071473L;
    private final Kind kind;
    private final Class<?> javaMirror;
    private final Class javaArrayMirror;

    public HotSpotTypePrimitive(Kind kind) {
        super(String.valueOf(Character.toUpperCase(kind.getTypeChar())));
        this.kind = kind;
        this.javaMirror = kind.toJavaClass();
        this.javaArrayMirror = kind.isVoid() ? null : Array.newInstance(javaMirror, 0).getClass();
    }

    @Override
    public int getModifiers() {
        return Modifier.ABSTRACT | Modifier.FINAL | Modifier.PUBLIC;
    }

    @Override
    public ResolvedJavaType getArrayClass() {
        return HotSpotResolvedJavaType.fromClass(javaArrayMirror);
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
        assert javaMirror.getSuperclass() == null;
        return null;
    }

    @Override
    public ResolvedJavaType[] getInterfaces() {
        return new ResolvedJavaType[0];
    }

    @Override
    public ResolvedJavaType findLeastCommonAncestor(ResolvedJavaType otherType) {
        return null;
    }

    @Override
    public Constant getEncoding(Representation r) {
        throw GraalInternalError.unimplemented("HotSpotTypePrimitive.getEncoding");
    }

    @Override
    public boolean hasFinalizableSubclass() {
        return false;
    }

    @Override
    public boolean hasFinalizer() {
        return false;
    }

    @Override
    public boolean isArrayClass() {
        return false;
    }

    @Override
    public boolean isInitialized() {
        return true;
    }

    @Override
    public boolean isInstance(Constant obj) {
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
    public boolean isAssignableTo(ResolvedJavaType other) {
        if (other instanceof HotSpotTypePrimitive) {
            return other == this;
        }
        return false;
    }

    @Override
    public Kind getKind() {
        return kind;
    }

    @Override
    public ResolvedJavaMethod resolveMethod(ResolvedJavaMethod method) {
        return null;
    }

    @Override
    public String toString() {
        return "HotSpotTypePrimitive<" + kind + ">";
    }

    @Override
    public ResolvedJavaType findUniqueConcreteSubtype() {
        return this;
    }

    @Override
    public ResolvedJavaMethod findUniqueConcreteMethod(ResolvedJavaMethod method) {
        return null;
    }

    @Override
    public ResolvedJavaField[] getInstanceFields(boolean includeSuperclasses) {
        return new ResolvedJavaField[0];
    }

    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        return javaMirror.getAnnotation(annotationClass);
    }

    @Override
    public Class< ? > toJava() {
        return javaMirror;
    }

    @Override
    public boolean isClass(Class c) {
        return c == javaMirror;
    }

    @Override
    public ResolvedJavaType resolve(ResolvedJavaType accessingClass) {
        return this;
    }

    @Override
    public void initialize() {
    }
}
