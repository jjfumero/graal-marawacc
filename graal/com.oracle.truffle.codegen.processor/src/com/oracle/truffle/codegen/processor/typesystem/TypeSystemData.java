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
package com.oracle.truffle.codegen.processor.typesystem;

import java.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.*;

import com.oracle.truffle.codegen.processor.*;
import com.oracle.truffle.codegen.processor.template.*;

public class TypeSystemData extends Template {

    private final TypeData[] types;
    private final TypeMirror[] primitiveTypeMirrors;
    private final TypeMirror[] boxedTypeMirrors;

    private final TypeMirror genericType;

    private final TypeData voidType;

    public TypeSystemData(TypeElement templateType, AnnotationMirror annotation, TypeData[] types, TypeMirror genericType, TypeData voidType) {
        super(templateType, null, annotation);
        this.types = types;
        this.genericType = genericType;
        this.voidType = voidType;
        this.primitiveTypeMirrors = new TypeMirror[types.length];
        for (int i = 0; i < types.length; i++) {
            primitiveTypeMirrors[i] = types[i].getPrimitiveType();
        }

        this.boxedTypeMirrors = new TypeMirror[types.length];
        for (int i = 0; i < types.length; i++) {
            boxedTypeMirrors[i] = types[i].getBoxedType();
        }

        for (TypeData type : types) {
            type.typeSystem = this;
        }
        if (voidType != null) {
            voidType.typeSystem = this;
        }
    }

    public boolean isGeneric(TypeMirror type) {
        return Utils.typeEquals(getGenericType(), type);
    }

    public TypeData getVoidType() {
        return voidType;
    }

    public TypeData[] getTypes() {
        return types;
    }

    public TypeMirror[] getPrimitiveTypeMirrors() {
        return primitiveTypeMirrors;
    }

    public TypeMirror[] getBoxedTypeMirrors() {
        return boxedTypeMirrors;
    }

    public TypeMirror getGenericType() {
        return genericType;
    }

    public TypeData getGenericTypeData() {
        TypeData result = types[types.length - 1];
        assert result.getBoxedType() == genericType;
        return result;
    }

    public TypeData findType(String simpleName) {
        for (TypeData type : types) {
            if (Utils.getSimpleName(type.getBoxedType()).equals(simpleName)) {
                return type;
            }
        }
        return null;
    }

    public TypeData findTypeData(TypeMirror type) {
        if (Utils.typeEquals(voidType.getPrimitiveType(), type)) {
            return voidType;
        }

        int index = findType(type);
        if (index == -1) {
            return null;
        }
        return types[index];
    }

    public int findType(TypeData typeData) {
        return findType(typeData.getPrimitiveType());
    }

    public int findType(TypeMirror type) {
        for (int i = 0; i < types.length; i++) {
            if (Utils.typeEquals(types[i].getPrimitiveType(), type)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[template = " + Utils.getSimpleName(getTemplateType()) + ", types = " + Arrays.toString(types) + "]";
    }

}
