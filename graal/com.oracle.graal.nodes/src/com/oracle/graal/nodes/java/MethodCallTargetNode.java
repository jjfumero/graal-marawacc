/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.java;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

public class MethodCallTargetNode extends CallTargetNode implements Node.IterableNodeType, Canonicalizable {

    public enum InvokeKind {
        Interface, Special, Static, Virtual
    }

    private final JavaType returnType;
    private ResolvedJavaMethod targetMethod;
    private InvokeKind invokeKind;

    /**
     * @param arguments
     */
    public MethodCallTargetNode(InvokeKind invokeKind, ResolvedJavaMethod targetMethod, ValueNode[] arguments, JavaType returnType) {
        super(arguments);
        this.invokeKind = invokeKind;
        this.returnType = returnType;
        this.targetMethod = targetMethod;
    }

    /**
     * Gets the target method for this invocation instruction.
     * 
     * @return the target method
     */
    public ResolvedJavaMethod targetMethod() {
        return targetMethod;
    }

    public InvokeKind invokeKind() {
        return invokeKind;
    }

    public void setInvokeKind(InvokeKind kind) {
        this.invokeKind = kind;
    }

    public void setTargetMethod(ResolvedJavaMethod method) {
        targetMethod = method;
    }

    /**
     * Gets the instruction that produces the receiver object for this invocation, if any.
     * 
     * @return the instruction that produces the receiver object for this invocation if any,
     *         {@code null} if this invocation does not take a receiver object
     */
    public ValueNode receiver() {
        return isStatic() ? null : arguments().get(0);
    }

    /**
     * Checks whether this is an invocation of a static method.
     * 
     * @return {@code true} if the invocation is a static invocation
     */
    public boolean isStatic() {
        return invokeKind() == InvokeKind.Static;
    }

    public Kind returnKind() {
        return targetMethod().getSignature().getReturnKind();
    }

    public Invoke invoke() {
        return (Invoke) this.usages().first();
    }

    @Override
    public boolean verify() {
        assert usages().count() <= 1 : "call target may only be used by a single invoke";
        for (Node n : usages()) {
            assertTrue(n instanceof Invoke, "call target can only be used from an invoke (%s)", n);
        }
        return super.verify();
    }

    @Override
    public String toString(Verbosity verbosity) {
        if (verbosity == Verbosity.Long) {
            return super.toString(Verbosity.Short) + "(" + targetMethod() + ")";
        } else {
            return super.toString(verbosity);
        }
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        if (!isStatic()) {
            ValueNode receiver = receiver();
            if (receiver != null && receiver.objectStamp().isExactType()) {
                if (invokeKind == InvokeKind.Interface || invokeKind == InvokeKind.Virtual) {
                    ResolvedJavaMethod method = receiver.objectStamp().type().resolveMethod(targetMethod);
                    if (method != null) {
                        invokeKind = InvokeKind.Special;
                        targetMethod = method;
                    }
                }
            }
        }
        return this;
    }

    public JavaType returnType() {
        return returnType;
    }

    @Override
    public Stamp returnStamp() {
        Kind returnKind = targetMethod.getSignature().getReturnKind();
        if (returnKind == Kind.Object && returnType instanceof ResolvedJavaType) {
            return StampFactory.declared((ResolvedJavaType) returnType);
        } else {
            return StampFactory.forKind(returnKind);
        }
    }

    public JavaType returnType() {
        return returnType;
    }

    @Override
    public String targetName() {
        if (targetMethod() == null) {
            return "??Invalid!";
        }
        return targetMethod().getName();
    }

    public static MethodCallTargetNode find(StructuredGraph graph, ResolvedJavaMethod method) {
        for (MethodCallTargetNode target : graph.getNodes(MethodCallTargetNode.class)) {
            if (target.targetMethod == method) {
                return target;
            }
        }
        return null;
    }
}
