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
package com.oracle.truffle.codegen.processor.ast;

import java.util.*;

import javax.annotation.processing.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;

import com.oracle.truffle.codegen.processor.*;
import com.oracle.truffle.codegen.processor.api.element.*;

public class CodeExecutableElement extends CodeElement<Element> implements WritableExecutableElement {

    private final List<TypeMirror> throwables = new ArrayList<>();
    private final List<VariableElement> parameters = parentableList(this, new ArrayList<VariableElement>());

    private TypeMirror returnType;
    private Name name;

    private CodeTree bodyTree;
    private String body;
    private AnnotationValue defaultValue;
    private boolean varArgs;

    public CodeExecutableElement(TypeMirror returnType, String name) {
        super(Utils.modifiers());
        this.returnType = returnType;
        this.name = CodeNames.of(name);
    }

    public CodeExecutableElement(Set<Modifier> modifiers, TypeMirror returnType, String name, CodeVariableElement... parameters) {
        super(modifiers);
        this.returnType = returnType;
        this.name = CodeNames.of(name);
        for (CodeVariableElement codeParameter : parameters) {
            addParameter(codeParameter);
        }
    }

    @Override
    public List<TypeMirror> getThrownTypes() {
        return throwables;
    }

    @Override
    public TypeMirror asType() {
        return returnType;
    }

    @Override
    public ElementKind getKind() {
        return ElementKind.METHOD;
    }

    @Override
    public List< ? extends TypeParameterElement> getTypeParameters() {
        return Collections.emptyList();
    }

    @Override
    public void setVarArgs(boolean varargs) {
        this.varArgs = varargs;
    }

    @Override
    public boolean isVarArgs() {
        return varArgs;
    }

    @Override
    public void setDefaultValue(AnnotationValue defaultValue) {
        this.defaultValue = defaultValue;
    }

    @Override
    public AnnotationValue getDefaultValue() {
        return defaultValue;
    }

    @Override
    public Name getSimpleName() {
        return name;
    }

    public CodeTreeBuilder createBuilder() {
        CodeTreeBuilder builder = new CodeTreeBuilder();
        this.bodyTree = builder.getTree();
        this.bodyTree.setEnclosingElement(this);
        return builder;
    }

    public void setBodyTree(CodeTree body) {
        this.bodyTree = body;
    }

    public CodeTree getBodyTree() {
        return bodyTree;
    }

    public TypeMirror getReturnType() {
        return returnType;
    }

    @Override
    public List<VariableElement> getParameters() {
        return parameters;
    }

    public TypeMirror[] getParameterTypes() {
        TypeMirror[] types = new TypeMirror[getParameters().size()];
        for (int i = 0; i < types.length; i++) {
            types[i] = parameters.get(i).asType();
        }
        return types;
    }

    @Override
    public void setReturnType(TypeMirror type) {
        returnType = type;
    }

    @Override
    public void addParameter(VariableElement parameter) {
        parameters.add(parameter);
    }

    @Override
    public void removeParamter(VariableElement parameter) {
        parameters.remove(parameter);
    }

    @Override
    public void addThrownType(TypeMirror thrownType) {
        throwables.add(thrownType);
    }

    @Override
    public void removeThrownType(TypeMirror thrownType) {
        throwables.remove(thrownType);
    }

    @Override
    public void setSimpleName(Name name) {
        this.name = name;
    }

    @Override
    public void setBody(String body) {
        this.body = body;
    }

    @Override
    public String getBody() {
        return body;
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
        return v.visitExecutable(this, p);
    }


    public static CodeExecutableElement clone(ProcessingEnvironment env, ExecutableElement method) {
        CodeExecutableElement copy = new CodeExecutableElement(method.getReturnType(), method.getSimpleName().toString());
        for (TypeMirror thrownType : method.getThrownTypes()) {
            copy.addThrownType(thrownType);
        }
        copy.setDefaultValue(method.getDefaultValue());

        for (AnnotationMirror mirror : method.getAnnotationMirrors()) {
            copy.addAnnotationMirror(mirror);
        }
        for (VariableElement var : method.getParameters()) {
            copy.addParameter(var);
        }
        for (Element element : method.getEnclosedElements()) {
            copy.add(element);
        }
        copy.setBody(Utils.getMethodBody(env, method));
        copy.getModifiers().addAll(method.getModifiers());
        return copy;
    }

}
