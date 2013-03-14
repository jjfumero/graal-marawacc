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
package com.oracle.truffle.codegen.processor;

import java.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.tools.Diagnostic.*;

import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.intrinsics.*;
import com.oracle.truffle.api.nodes.*;
import com.oracle.truffle.api.nodes.Node.Child;
import com.oracle.truffle.api.nodes.Node.Children;

/**
 * THIS IS NOT PUBLIC API.
 */
public final class TruffleTypes {

    private final TypeMirror node;
    private final TypeMirror nodeArray;
    private final TypeMirror unexpectedValueException;
    private final TypeMirror frame;
    private final TypeMirror stableAnnotation;
    private final TypeMirror contentStableAnnotation;
    private final TypeMirror typeConversion;
    private final TypeMirror truffleIntrinsics;

    private final List<String> errors = new ArrayList<>();

    public TruffleTypes(ProcessorContext context) {
        node = getRequired(context, Node.class);
        nodeArray = context.getEnvironment().getTypeUtils().getArrayType(node);
        unexpectedValueException = getRequired(context, UnexpectedResultException.class);
        frame = getRequired(context, VirtualFrame.class);
        stableAnnotation = getRequired(context, Child.class);
        contentStableAnnotation = getRequired(context, Children.class);
        typeConversion = getRequired(context, TypeConversion.class);
        truffleIntrinsics = getRequired(context, TruffleIntrinsics.class);
    }

    public boolean verify(ProcessorContext context, Element element, AnnotationMirror mirror) {
        if (errors.isEmpty()) {
            return true;
        }

        for (String error : errors) {
            context.getLog().message(Kind.ERROR, element, mirror, null, error);
        }

        return false;
    }

    private TypeMirror getRequired(ProcessorContext context, Class clazz) {
        TypeMirror type = context.getType(clazz);
        if (type == null) {
            errors.add(String.format("Could not find required type: %s", clazz.getSimpleName()));
        }
        return type;
    }

    public TypeMirror getTruffleIntrinsics() {
        return truffleIntrinsics;
    }

    public TypeMirror getTypeConversion() {
        return typeConversion;
    }

    public TypeMirror getNode() {
        return node;
    }

    public TypeMirror getNodeArray() {
        return nodeArray;
    }

    public TypeMirror getFrame() {
        return frame;
    }

    public TypeMirror getUnexpectedValueException() {
        return unexpectedValueException;
    }

    public TypeMirror getStableAnnotation() {
        return stableAnnotation;
    }

    public TypeMirror getContentStableAnnotation() {
        return contentStableAnnotation;
    }
}
