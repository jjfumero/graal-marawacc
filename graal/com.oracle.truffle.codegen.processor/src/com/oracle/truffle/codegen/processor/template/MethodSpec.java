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
package com.oracle.truffle.codegen.processor.template;

import java.util.*;

import javax.lang.model.type.*;

public class MethodSpec {

    private final List<TypeMirror> implicitTypes;

    private final ParameterSpec returnType;
    private final List<ParameterSpec> parameters;

    public MethodSpec(List<TypeMirror> prefixTypes, ParameterSpec returnType, List<ParameterSpec> parameters) {
        this.implicitTypes = prefixTypes;
        this.returnType = returnType;
        this.parameters = parameters;
    }

    public List<TypeMirror> getImplicitTypes() {
        return implicitTypes;
    }

    public ParameterSpec getReturnType() {
        return returnType;
    }

    public List<ParameterSpec> getParameters() {
        return parameters;
    }

    public ParameterSpec findParameterSpec(String name) {
        for (ParameterSpec spec : parameters) {
            if (spec.getName().equals(name)) {
                return spec;
            }
        }
        return null;
    }

}
