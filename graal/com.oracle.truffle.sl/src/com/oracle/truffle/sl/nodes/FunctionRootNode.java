/*
 * Copyright (c) 2012, 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.sl.nodes;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.dsl.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;
import com.oracle.truffle.sl.builtins.*;
import com.oracle.truffle.sl.runtime.*;

public final class FunctionRootNode extends RootNode {

    @Child private TypedNode body;

    private final TypedNode uninitializedBody;
    private final String name;
    private final boolean inlineImmediatly;

    private FunctionRootNode(FrameDescriptor frameDescriptor, TypedNode body, String name, boolean inlineImmediatly) {
        super(null, frameDescriptor);
        this.uninitializedBody = NodeUtil.cloneNode(body);
        this.body = adoptChild(body);
        this.name = name;
        this.inlineImmediatly = inlineImmediatly;
    }

    public static CallTarget createBuiltin(SLContext context, NodeFactory<? extends BuiltinNode> factory, String name) {
        int argumentCount = factory.getExecutionSignature().size();
        TypedNode[] arguments = new TypedNode[argumentCount];
        for (int i = 0; i < arguments.length; i++) {
            arguments[i] = new ReadArgumentNode(i);
        }
        BuiltinNode buitinBody = factory.createNode(arguments, context);
        FunctionRootNode root = new FunctionRootNode(new FrameDescriptor(), buitinBody, name, true);
        return Truffle.getRuntime().createCallTarget(root);
    }

    public static CallTarget createFunction(StatementNode body, FrameDescriptor frameDescriptor, String name, TypedNode returnValue, String[] parameterNames) {
        FunctionBodyNode bodyContainer = new FunctionBodyNode(frameDescriptor, body, returnValue, parameterNames);
        FunctionRootNode root = new FunctionRootNode(frameDescriptor, bodyContainer, name, false);
        return Truffle.getRuntime().createCallTarget(root);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return body.executeGeneric(frame);
    }

    public boolean isInlineImmediatly() {
        return inlineImmediatly;
    }

    public TypedNode inline() {
        return NodeUtil.cloneNode(uninitializedBody);
    }

    public Node getUninitializedBody() {
        return uninitializedBody;
    }

    @Override
    public String toString() {
        return "function " + name;
    }

    public String getName() {
        return name;
    }

}
