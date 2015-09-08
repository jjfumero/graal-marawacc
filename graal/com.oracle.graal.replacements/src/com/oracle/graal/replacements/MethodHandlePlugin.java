/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.replacements;

import jdk.internal.jvmci.meta.*;
import jdk.internal.jvmci.meta.MethodHandleAccessProvider.*;

import com.oracle.graal.graph.*;
import com.oracle.graal.graphbuilderconf.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.CallTargetNode.InvokeKind;
import com.oracle.graal.replacements.nodes.*;

public class MethodHandlePlugin implements NodePlugin {
    private final MethodHandleAccessProvider methodHandleAccess;
    private final boolean safeForDeoptimization;

    public MethodHandlePlugin(MethodHandleAccessProvider methodHandleAccess, boolean safeForDeoptimization) {
        this.methodHandleAccess = methodHandleAccess;
        this.safeForDeoptimization = safeForDeoptimization;
    }

    @Override
    public boolean handleInvoke(GraphBuilderContext b, ResolvedJavaMethod method, ValueNode[] args) {
        IntrinsicMethod intrinsicMethod = methodHandleAccess.lookupMethodHandleIntrinsic(method);
        if (intrinsicMethod != null) {
            InvokeKind invokeKind = b.getInvokeKind();
            if (invokeKind != InvokeKind.Static) {
                args[0] = b.nullCheckedValue(args[0]);
            }
            JavaType invokeReturnType = b.getInvokeReturnType();
            InvokeNode invoke = MethodHandleNode.tryResolveTargetInvoke(b.getAssumptions(), b.getConstantReflection().getMethodHandleAccess(), intrinsicMethod, method, b.bci(), invokeReturnType, args);
            if (invoke == null) {
                MethodHandleNode methodHandleNode = new MethodHandleNode(intrinsicMethod, invokeKind, method, b.bci(), invokeReturnType, args);
                if (invokeReturnType.getJavaKind() == JavaKind.Void) {
                    b.add(methodHandleNode);
                } else {
                    b.addPush(invokeReturnType.getJavaKind(), methodHandleNode);
                }
            } else {
                CallTargetNode callTarget = invoke.callTarget();
                NodeInputList<ValueNode> argumentsList = callTarget.arguments();
                for (int i = 0; i < argumentsList.size(); ++i) {
                    argumentsList.initialize(i, b.recursiveAppend(argumentsList.get(i)));
                }

                boolean inlineEverything = false;
                if (safeForDeoptimization) {
                    // If a MemberName suffix argument is dropped, the replaced call cannot
                    // deoptimized since the necessary frame state cannot be reconstructed.
                    // As such, it needs to recursively inline everything.
                    inlineEverything = args.length != argumentsList.size();
                }
                b.handleReplacedInvoke(invoke.getInvokeKind(), callTarget.targetMethod(), argumentsList.toArray(new ValueNode[argumentsList.size()]), inlineEverything);
            }
            return true;
        }
        return false;
    }
}
