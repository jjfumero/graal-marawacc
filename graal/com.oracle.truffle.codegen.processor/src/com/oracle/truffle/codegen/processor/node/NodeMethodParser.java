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

import java.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.*;

import com.oracle.truffle.codegen.processor.*;
import com.oracle.truffle.codegen.processor.node.NodeFieldData.*;
import com.oracle.truffle.codegen.processor.template.*;
import com.oracle.truffle.codegen.processor.template.ParameterSpec.Cardinality;

public abstract class NodeMethodParser<E extends TemplateMethod> extends TemplateMethodParser<NodeData, E> {

    public NodeMethodParser(ProcessorContext context, NodeData node) {
        super(context, node);
    }

    public NodeData getNode() {
        return template;
    }

    protected ParameterSpec createValueParameterSpec(String valueName, NodeData nodeData) {
        ParameterSpec spec = new ParameterSpec(valueName, nodeTypeMirrors(nodeData));
        spec.setSignature(true);
        return spec;
    }

    protected List<TypeMirror> nodeTypeMirrors(NodeData nodeData) {
        Set<TypeMirror> typeMirrors = new LinkedHashSet<>();

        for (ExecutableTypeData typeData : nodeData.getExecutableTypes()) {
            typeMirrors.add(typeData.getType().getPrimitiveType());
        }

        typeMirrors.add(nodeData.getTypeSystem().getGenericType());

        return new ArrayList<>(typeMirrors);
    }

    protected ParameterSpec createReturnParameterSpec() {
        return createValueParameterSpec("returnValue", getNode());
    }

    @Override
    public boolean isParsable(ExecutableElement method) {
        if (getAnnotationType() != null) {
            return Utils.findAnnotationMirror(getContext().getEnvironment(), method, getAnnotationType()) != null;
        }

        return true;
    }

    @SuppressWarnings("unused")
    protected final MethodSpec createDefaultMethodSpec(ExecutableElement method, AnnotationMirror mirror, boolean shortCircuitsEnabled, String shortCircuitName) {
        MethodSpec methodSpec = new MethodSpec(createReturnParameterSpec());

        if (getNode().supportsFrame()) {
            methodSpec.addOptional(new ParameterSpec("frame", getContext().getTruffleTypes().getFrame()));
        }

        resolveAndAddImplicitThis(methodSpec, method);

        for (NodeFieldData field : getNode().getFields()) {
            if (field.getKind() == FieldKind.FINAL_FIELD) {
                ParameterSpec spec = new ParameterSpec(field.getName(), field.getType());
                spec.setLocal(true);
                methodSpec.addOptional(spec);
            }
        }

        for (NodeFieldData field : getNode().getFields()) {
            if (field.getExecutionKind() == ExecutionKind.IGNORE) {
                continue;
            }

            if (field.getExecutionKind() == ExecutionKind.DEFAULT) {
                ParameterSpec spec = createValueParameterSpec(field.getName(), field.getNodeData());
                if (field.getKind() == FieldKind.CHILDREN) {
                    spec.setCardinality(Cardinality.MULTIPLE);
                    spec.setIndexed(true);
                }
                methodSpec.addRequired(spec);
            } else if (field.getExecutionKind() == ExecutionKind.SHORT_CIRCUIT) {
                String valueName = field.getName();
                if (shortCircuitName != null && valueName.equals(shortCircuitName)) {
                    break;
                }

                if (shortCircuitsEnabled) {
                    methodSpec.addRequired(new ParameterSpec(shortCircuitValueName(valueName), getContext().getType(boolean.class)));
                }
                methodSpec.addRequired(createValueParameterSpec(valueName, field.getNodeData()));
            } else {
                assert false;
            }
        }

        return methodSpec;
    }

    protected void resolveAndAddImplicitThis(MethodSpec methodSpec, ExecutableElement method) {
        TypeMirror declaredType = Utils.findNearestEnclosingType(method).asType();

        if (!method.getModifiers().contains(Modifier.STATIC) && !Utils.isAssignable(declaredType, getContext().getTruffleTypes().getNode())) {
            methodSpec.addImplicitRequiredType(getNode().getTemplateType().asType());
        }
    }

    private static String shortCircuitValueName(String valueName) {
        return "has" + Utils.firstLetterUpperCase(valueName);
    }

}
