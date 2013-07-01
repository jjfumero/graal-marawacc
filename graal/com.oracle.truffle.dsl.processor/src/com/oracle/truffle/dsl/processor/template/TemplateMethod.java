/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.dsl.processor.template;

import java.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.*;

import com.oracle.truffle.dsl.processor.*;
import com.oracle.truffle.dsl.processor.typesystem.*;

/**
 * Note: this class has a natural ordering that is inconsistent with equals.
 */
public class TemplateMethod extends MessageContainer implements Comparable<TemplateMethod> {

    private String id;
    private final Template template;
    private final MethodSpec specification;
    private final ExecutableElement method;
    private final AnnotationMirror markerAnnotation;
    private ActualParameter returnType;
    private List<ActualParameter> parameters;

    public TemplateMethod(String id, Template template, MethodSpec specification, ExecutableElement method, AnnotationMirror markerAnnotation, ActualParameter returnType,
                    List<ActualParameter> parameters) {
        this.template = template;
        this.specification = specification;
        this.method = method;
        this.markerAnnotation = markerAnnotation;
        this.returnType = returnType;
        this.parameters = new ArrayList<>();
        for (ActualParameter param : parameters) {
            ActualParameter newParam = new ActualParameter(param);
            this.parameters.add(newParam);
            newParam.setMethod(this);
        }
        this.id = id;
    }

    public TemplateMethod(TemplateMethod method) {
        this(method.id, method.template, method.specification, method.method, method.markerAnnotation, method.returnType, method.parameters);
        getMessages().addAll(method.getMessages());
    }

    public TemplateMethod(TemplateMethod method, ExecutableElement executable) {
        this(method.id, method.template, method.specification, executable, method.markerAnnotation, method.returnType, method.parameters);
        getMessages().addAll(method.getMessages());
    }

    public void setParameters(List<ActualParameter> parameters) {
        this.parameters = parameters;
    }

    @Override
    public Element getMessageElement() {
        return method;
    }

    @Override
    public AnnotationMirror getMessageAnnotation() {
        return markerAnnotation;
    }

    @Override
    protected List<MessageContainer> findChildContainers() {
        return Collections.emptyList();
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public Template getTemplate() {
        return template;
    }

    public MethodSpec getSpecification() {
        return specification;
    }

    public ActualParameter getReturnType() {
        return returnType;
    }

    public void replaceParameter(String localName, ActualParameter newParameter) {
        if (returnType.getLocalName().equals(localName)) {
            returnType = newParameter;
            returnType.setMethod(this);
        }

        for (ListIterator<ActualParameter> iterator = parameters.listIterator(); iterator.hasNext();) {
            ActualParameter parameter = iterator.next();
            if (parameter.getLocalName().equals(localName)) {
                iterator.set(newParameter);
                newParameter.setMethod(this);
            }
        }
    }

    public List<ActualParameter> getRequiredParameters() {
        List<ActualParameter> requiredParameters = new ArrayList<>();
        for (ActualParameter parameter : getParameters()) {
            if (getSpecification().getRequired().contains(parameter.getSpecification())) {
                requiredParameters.add(parameter);
            }
        }
        return requiredParameters;
    }

    public List<ActualParameter> getParameters() {
        return parameters;
    }

    public List<ActualParameter> findParameters(ParameterSpec spec) {
        List<ActualParameter> foundParameters = new ArrayList<>();
        for (ActualParameter param : getReturnTypeAndParameters()) {
            if (param.getSpecification().equals(spec)) {
                foundParameters.add(param);
            }
        }
        return foundParameters;
    }

    public ActualParameter findParameter(String valueName) {
        for (ActualParameter param : getReturnTypeAndParameters()) {
            if (param.getLocalName().equals(valueName)) {
                return param;
            }
        }
        return null;
    }

    public List<ActualParameter> getReturnTypeAndParameters() {
        List<ActualParameter> allParameters = new ArrayList<>(getParameters().size() + 1);
        if (getReturnType() != null) {
            allParameters.add(getReturnType());
        }
        allParameters.addAll(getParameters());
        return Collections.unmodifiableList(allParameters);
    }

    public boolean canBeAccessedByInstanceOf(ProcessorContext context, TypeMirror type) {
        TypeMirror methodType = Utils.findNearestEnclosingType(getMethod()).asType();
        return Utils.isAssignable(context, type, methodType) || Utils.isAssignable(context, methodType, type);
    }

    public ExecutableElement getMethod() {
        return method;
    }

    public String getMethodName() {
        if (getMethod() != null) {
            return getMethod().getSimpleName().toString();
        } else {
            return "$synthetic";
        }
    }

    public AnnotationMirror getMarkerAnnotation() {
        return markerAnnotation;
    }

    @Override
    public String toString() {
        return String.format("%s [id = %s, method = %s]", getClass().getSimpleName(), getId(), getMethod());
    }

    public ActualParameter getPreviousParam(ActualParameter searchParam) {
        ActualParameter prev = null;
        for (ActualParameter param : getParameters()) {
            if (param == searchParam) {
                return prev;
            }
            prev = param;
        }
        return prev;
    }

    public Signature getSignature() {
        Signature signature = new Signature();
        for (ActualParameter parameter : getReturnTypeAndParameters()) {
            if (!parameter.getSpecification().isSignature()) {
                continue;
            }
            TypeData typeData = parameter.getTypeSystemType();
            if (typeData != null) {
                signature.types.add(typeData);
            }
        }
        return signature;
    }

    public void updateSignature(Signature signature) {
        assert signature.size() >= 1;
        int signatureIndex = 0;
        for (ActualParameter parameter : getReturnTypeAndParameters()) {
            if (!parameter.getSpecification().isSignature()) {
                continue;
            }
            TypeData newType = signature.get(signatureIndex++);
            if (!parameter.getTypeSystemType().equals(newType)) {
                replaceParameter(parameter.getLocalName(), new ActualParameter(parameter, newType));
            }
        }
    }

    @Override
    public int compareTo(TemplateMethod o) {
        if (this == o) {
            return 0;
        }

        int compare = compareBySignature(o);
        if (compare == 0) {
            // if signature sorting failed sort by id
            compare = getId().compareTo(o.getId());
        }
        if (compare == 0) {
            // if still no difference sort by enclosing type name
            TypeElement enclosingType1 = Utils.findNearestEnclosingType(getMethod());
            TypeElement enclosingType2 = Utils.findNearestEnclosingType(o.getMethod());
            compare = enclosingType1.getQualifiedName().toString().compareTo(enclosingType2.getQualifiedName().toString());
        }
        return compare;
    }

    public List<ActualParameter> getParametersAfter(ActualParameter genericParameter) {
        boolean found = false;
        List<ActualParameter> foundParameters = new ArrayList<>();
        for (ActualParameter param : getParameters()) {
            if (param.getLocalName().equals(genericParameter.getLocalName())) {
                found = true;
            } else if (found) {
                foundParameters.add(param);
            }
        }
        return foundParameters;
    }

    public int compareBySignature(TemplateMethod compareMethod) {
        TypeSystemData typeSystem = getTemplate().getTypeSystem();
        if (typeSystem != compareMethod.getTemplate().getTypeSystem()) {
            throw new IllegalStateException("Cannot compare two methods with different type systems.");
        }

        Signature signature1 = getSignature();
        Signature signature2 = compareMethod.getSignature();
        if (signature1.size() != signature2.size()) {
            return signature2.size() - signature1.size();
        }

        int result = 0;
        for (int i = 1; i < signature1.size(); i++) {
            int typeResult = compareActualParameter(typeSystem, signature1.get(i), signature2.get(i));
            if (result == 0) {
                result = typeResult;
            } else if (typeResult != 0 && Math.signum(result) != Math.signum(typeResult)) {
                // We cannot define an order.
                return 0;
            }
        }
        if (result == 0 && signature1.size() > 0) {
            result = compareActualParameter(typeSystem, signature1.get(0), signature2.get(0));
        }

        return result;
    }

    private static int compareActualParameter(TypeSystemData typeSystem, TypeData t1, TypeData t2) {
        int index1 = typeSystem.findType(t1);
        int index2 = typeSystem.findType(t2);
        return index1 - index2;
    }

    public static class Signature implements Iterable<TypeData>, Comparable<Signature> {

        final List<TypeData> types;

        public Signature() {
            this.types = new ArrayList<>();
        }

        public Signature(List<TypeData> signature) {
            this.types = signature;
        }

        @Override
        public int hashCode() {
            return types.hashCode();
        }

        public int compareTo(Signature o) {
            if (o.size() != size()) {
                return size() - o.size();
            }

            int typeSum = 0;
            int otherTypeSum = 0;
            for (int i = 0; i < types.size(); i++) {
                TypeData type = types.get(i);
                TypeData otherType = o.get(i);
                typeSum += type.isGeneric() ? 1 : 0;
                otherTypeSum += otherType.isGeneric() ? 1 : 0;
            }

            return typeSum - otherTypeSum;
        }

        public int size() {
            return types.size();
        }

        public TypeData get(int index) {
            return types.get(index);
        }

        public Signature combine(Signature genericSignature, Signature other) {
            assert types.size() == other.types.size();
            assert genericSignature.types.size() == other.types.size();

            if (this.equals(other)) {
                return this;
            }

            Signature signature = new Signature();
            for (int i = 0; i < types.size(); i++) {
                TypeData type1 = types.get(i);
                TypeData type2 = other.types.get(i);
                if (type1.equals(type2)) {
                    signature.types.add(type1);
                } else {
                    signature.types.add(genericSignature.types.get(i));
                }
            }
            return signature;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Signature) {
                return ((Signature) obj).types.equals(types);
            }
            return super.equals(obj);
        }

        public Iterator<TypeData> iterator() {
            return types.iterator();
        }

        @Override
        public String toString() {
            return types.toString();
        }

        public boolean hasAnyParameterMatch(Signature other) {
            for (int i = 1; i < types.size(); i++) {
                TypeData type1 = types.get(i);
                TypeData type2 = other.types.get(i);
                if (type1.equals(type2)) {
                    return true;
                }
            }
            return false;
        }
    }

}
