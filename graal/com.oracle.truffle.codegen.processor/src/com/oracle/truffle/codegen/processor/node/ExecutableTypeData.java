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
package com.oracle.truffle.codegen.processor.node;

import javax.lang.model.element.*;

import com.oracle.truffle.codegen.processor.*;
import com.oracle.truffle.codegen.processor.template.*;
import com.oracle.truffle.codegen.processor.typesystem.*;

public class ExecutableTypeData extends TemplateMethod {

    private final TypeSystemData typeSystem;
    private final TypeData type;

    public ExecutableTypeData(TemplateMethod method, TypeSystemData typeSystem, TypeData type) {
        super(method);
        this.typeSystem = typeSystem;
        this.type = type;
    }

    public TypeData getType() {
        return type;
    }

    public TypeSystemData getTypeSystem() {
        return typeSystem;
    }

    public boolean hasFrame() {
        return getMethod().getParameters().size() > 0;
    }

    public boolean hasUnexpectedValue(ProcessorContext context) {
        return Utils.canThrowType(getMethod().getThrownTypes(), context.getTruffleTypes().getUnexpectedValueException());
    }

    public boolean isFinal() {
        return getMethod().getModifiers().contains(Modifier.FINAL);
    }

    @Override
    public int hashCode() {
        return type.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ExecutableTypeData) {
            return type.equals(((ExecutableTypeData) obj).type);
        }
        return super.equals(obj);
    }

}
