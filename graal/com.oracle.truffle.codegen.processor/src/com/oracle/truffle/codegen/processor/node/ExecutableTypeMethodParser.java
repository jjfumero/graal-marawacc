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

import java.lang.annotation.*;
import java.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.*;

import com.oracle.truffle.codegen.processor.*;
import com.oracle.truffle.codegen.processor.template.*;
import com.oracle.truffle.codegen.processor.typesystem.*;

public class ExecutableTypeMethodParser extends NodeMethodParser<ExecutableTypeData> {

    public ExecutableTypeMethodParser(ProcessorContext context, NodeData node) {
        super(context, node);
        setEmitErrors(false);
        setParseNullOnError(false);
    }

    @Override
    public MethodSpec createSpecification(ExecutableElement method, AnnotationMirror mirror) {
        List<TypeMirror> types = new ArrayList<>();
        types.addAll(getNode().getTypeSystem().getPrimitiveTypeMirrors());
        types.add(getContext().getType(void.class));

        ParameterSpec returnTypeSpec = new ParameterSpec("executedValue", types);
        returnTypeSpec.setSignature(true);

        List<ParameterSpec> parameters = new ArrayList<>();
        ParameterSpec frameSpec = new ParameterSpec("frame", getContext().getTruffleTypes().getFrame());
        frameSpec.setOptional(true);
        parameters.add(frameSpec);
        return new MethodSpec(new ArrayList<TypeMirror>(), returnTypeSpec, parameters);
    }

    @Override
    public final boolean isParsable(ExecutableElement method) {
        boolean parsable = method.getSimpleName().toString().startsWith("execute");
        return parsable;
    }

    @Override
    public ExecutableTypeData create(TemplateMethod method) {
        TypeData resolvedType = method.getReturnType().getActualTypeData(getNode().getTypeSystem());
        return new ExecutableTypeData(method, getNode().getTypeSystem(), resolvedType);
    }

    @Override
    public Class<? extends Annotation> getAnnotationType() {
        return null;
    }

}
