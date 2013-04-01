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

import java.lang.annotation.*;
import java.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.*;

import com.oracle.truffle.codegen.processor.*;
import com.oracle.truffle.codegen.processor.node.*;
import com.oracle.truffle.codegen.processor.template.*;

public class GuardParser extends TemplateMethodParser<Template, GuardData> {

    private final SpecializationData specialization;
    private final String guardName;

    public GuardParser(ProcessorContext context, SpecializationData specialization, String guardName) {
        super(context, specialization.getNode());
        this.specialization = specialization;
        this.guardName = guardName;
        setEmitErrors(false);
        setParseNullOnError(false);
    }

    @Override
    public MethodSpec createSpecification(ExecutableElement method, AnnotationMirror mirror) {
        List<ParameterSpec> specs = new ArrayList<>();
        for (ActualParameter parameter : specialization.getParameters()) {
            ParameterSpec spec = new ParameterSpec(parameter.getSpecification().getName(), parameter.getActualType());
            spec.setSignature(true);
            spec.setOptional(true);
            specs.add(spec);
        }
        ParameterSpec returnTypeSpec = new ParameterSpec("returnType", getContext().getType(boolean.class));
        return new MethodSpec(Collections.<TypeMirror> emptyList(), returnTypeSpec, specs);
    }

    @Override
    public boolean isParsable(ExecutableElement method) {
        return method.getSimpleName().toString().equals(guardName);
    }

    @Override
    public GuardData create(TemplateMethod method) {
        return new GuardData(method);
    }

    @Override
    public Class<? extends Annotation> getAnnotationType() {
        return null;
    }

}
