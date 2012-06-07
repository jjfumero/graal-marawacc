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
package com.oracle.graal.hotspot.ri;

import java.lang.annotation.*;
import java.lang.reflect.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.Compiler;

/**
 * Implementation of RiType for primitive HotSpot types.
 */
public final class HotSpotTypePrimitive extends HotSpotType implements RiResolvedType {

    private static final long serialVersionUID = -6208552348908071473L;
    private RiKind kind;
    private final HotSpotKlassOop klassOop;

    public HotSpotTypePrimitive(Compiler compiler, RiKind kind) {
        super(compiler);
        this.kind = kind;
        this.name = String.valueOf(Character.toUpperCase(kind.typeChar));
        this.klassOop = new HotSpotKlassOop(compiler, kind.toJavaClass());
    }

    @Override
    public int accessFlags() {
        assert kind != null && kind.toJavaClass() != null;
        return Modifier.ABSTRACT | Modifier.FINAL | Modifier.PUBLIC;
    }

    @Override
    public RiResolvedType arrayOf() {
        return (RiResolvedType) compiler.getCompilerToVM().getPrimitiveArrayType(kind);
    }

    @Override
    public RiResolvedType componentType() {
        return null;
    }

    @Override
    public RiResolvedType exactType() {
        return this;
    }

    @Override
    public RiResolvedType superType() {
        return null;
    }

    @Override
    public RiResolvedType leastCommonAncestor(RiResolvedType otherType) {
        return null;
    }

    @Override
    public RiConstant getEncoding(Representation r) {
        throw GraalInternalError.unimplemented("HotSpotTypePrimitive.getEncoding");
    }

    @Override
    public RiKind getRepresentationKind(Representation r) {
        return kind;
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
    public boolean hasSubclass() {
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
    public boolean isInstance(RiConstant obj) {
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
    public boolean isSubtypeOf(RiResolvedType other) {
        return false;
    }

    @Override
    public RiKind kind(boolean architecture) {
        return kind;
    }

    @Override
    public RiResolvedMethod resolveMethodImpl(RiResolvedMethod method) {
        return null;
    }

    @Override
    public String toString() {
        return "HotSpotTypePrimitive<" + kind + ">";
    }

    @Override
    public RiResolvedType uniqueConcreteSubtype() {
        return this;
    }

    @Override
    public RiResolvedMethod uniqueConcreteMethod(RiResolvedMethod method) {
        return null;
    }

    @Override
    public RiResolvedField[] declaredFields() {
        return null;
    }

    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        return toJava().getAnnotation(annotationClass);
    }

    @Override
    public Class< ? > toJava() {
        return kind.toJavaClass();
    }

    @Override
    public RiResolvedType resolve(RiResolvedType accessingClass) {
        return this;
    }

    @Override
    public HotSpotKlassOop klassOop() {
        return klassOop;
    }
}
