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

import com.oracle.truffle.codegen.processor.*;
import com.oracle.truffle.codegen.processor.node.*;
import com.oracle.truffle.codegen.processor.template.*;

public class GuardParser extends NodeMethodParser<GuardData> {

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
        MethodSpec spec = createDefaultMethodSpec(method, mirror, true, null);
        spec.setVariableRequiredArguments(true);
        spec.getRequired().clear();

        for (ActualParameter parameter : specialization.getRequiredParameters()) {
            ParameterSpec paramSpec = new ParameterSpec(parameter.getLocalName(), parameter.getType(), getNode().getTypeSystem().getGenericType());
            paramSpec.setSignature(true);
            spec.addRequired(paramSpec);
        }

        return spec;
    }

    @Override
    protected ParameterSpec createReturnParameterSpec() {
        return new ParameterSpec("returnType", getContext().getType(boolean.class));
    }

    @Override
    public boolean isParsable(ExecutableElement method) {
        return method.getSimpleName().toString().equals(guardName);
    }

    @Override
    public GuardData create(TemplateMethod method) {
        GuardData guard = new GuardData(method, specialization);
        /*
         * Update parameters in way that parameter specifications match again the node field names
         * etc.
         */
        List<ActualParameter> newParameters = new ArrayList<>();
        for (ActualParameter parameter : guard.getParameters()) {
            ActualParameter specializationParameter = specialization.findParameter(parameter.getSpecification().getName());
            if (specializationParameter == null) {
                newParameters.add(parameter);
            } else {
                newParameters.add(new ActualParameter(specializationParameter.getSpecification(), parameter.getTypeSystemType(), specializationParameter.getIndex(), parameter.isImplicit()));
            }
        }
        guard.setParameters(newParameters);

        return guard;
    }

    @Override
    public Class<? extends Annotation> getAnnotationType() {
        return null;
    }

}
