/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.replacements;

import java.lang.invoke.*;
import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.meta.Assumptions.AssumptionResult;
import com.oracle.graal.api.meta.MethodHandleAccessProvider.IntrinsicMethod;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.CallTargetNode.InvokeKind;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.replacements.nodes.*;

/**
 * Node for invocation methods defined on the class {@link MethodHandle}.
 */
@NodeInfo
public final class MethodHandleNode extends MacroStateSplitNode implements Simplifiable {
    public static final NodeClass<MethodHandleNode> TYPE = NodeClass.create(MethodHandleNode.class);

    protected final IntrinsicMethod intrinsicMethod;

    public MethodHandleNode(IntrinsicMethod intrinsicMethod, InvokeKind invokeKind, ResolvedJavaMethod targetMethod, int bci, JavaType returnType, ValueNode... arguments) {
        super(TYPE, invokeKind, targetMethod, bci, returnType, arguments);
        this.intrinsicMethod = intrinsicMethod;
    }

    /**
     * Attempts to transform application of an intrinsifiable {@link MethodHandle} method into an
     * invocation on another method with possibly transformed arguments.
     *
     * @param assumptions object for recording any speculations made during the transformation
     * @param methodHandleAccess objects for accessing the implementation internals of a
     *            {@link MethodHandle}
     * @param intrinsicMethod denotes the intrinsifiable {@link MethodHandle} method being processed
     * @param bci the BCI of the original {@link MethodHandle} call
     * @param returnType return type of the original {@link MethodHandle} call
     * @param arguments arguments to the original {@link MethodHandle} call
     * @return a more direct invocation derived from the {@link MethodHandle} call or null
     */
    public static InvokeNode tryResolveTargetInvoke(Assumptions assumptions, MethodHandleAccessProvider methodHandleAccess, IntrinsicMethod intrinsicMethod, ResolvedJavaMethod original, int bci,
                    JavaType returnType, ValueNode... arguments) {
        switch (intrinsicMethod) {
            case INVOKE_BASIC:
                return getInvokeBasicTarget(assumptions, intrinsicMethod, methodHandleAccess, original, bci, returnType, arguments);
            case LINK_TO_STATIC:
            case LINK_TO_SPECIAL:
            case LINK_TO_VIRTUAL:
            case LINK_TO_INTERFACE:
                return getLinkToTarget(assumptions, intrinsicMethod, methodHandleAccess, original, bci, returnType, arguments);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    @Override
    public void simplify(SimplifierTool tool) {
        MethodHandleAccessProvider methodHandleAccess = tool.getConstantReflection().getMethodHandleAccess();
        ValueNode[] argumentsArray = arguments.toArray(new ValueNode[arguments.size()]);
        InvokeNode invoke = tryResolveTargetInvoke(graph().getAssumptions(), methodHandleAccess, intrinsicMethod, targetMethod, bci, returnType, argumentsArray);
        if (invoke != null) {
            assert invoke.graph() == null;
            invoke = graph().addOrUniqueWithInputs(invoke);
            invoke.setStateAfter(stateAfter());
            FixedNode currentNext = next();
            replaceAtUsages(invoke);
            GraphUtil.removeFixedWithUnusedInputs(this);
            graph().addBeforeFixed(currentNext, invoke);
        }
    }

    /**
     * Get the receiver of a MethodHandle.invokeBasic call.
     *
     * @return the receiver argument node
     */
    private static ValueNode getReceiver(ValueNode[] arguments) {
        return arguments[0];
    }

    /**
     * Get the MemberName argument of a MethodHandle.linkTo* call.
     *
     * @return the MemberName argument node (which is the last argument)
     */
    private static ValueNode getMemberName(ValueNode[] arguments) {
        return arguments[arguments.length - 1];
    }

    /**
     * Used for the MethodHandle.invokeBasic method (the {@link IntrinsicMethod#INVOKE_BASIC }
     * method) to get the target {@link InvokeNode} if the method handle receiver is constant.
     *
     * @return invoke node for the {@link java.lang.invoke.MethodHandle} target
     */
    private static InvokeNode getInvokeBasicTarget(Assumptions assumptions, IntrinsicMethod intrinsicMethod, MethodHandleAccessProvider methodHandleAccess, ResolvedJavaMethod original, int bci,
                    JavaType returnType, ValueNode[] arguments) {
        ValueNode methodHandleNode = getReceiver(arguments);
        if (methodHandleNode.isConstant()) {
            return getTargetInvokeNode(assumptions, intrinsicMethod, bci, returnType, arguments, methodHandleAccess.resolveInvokeBasicTarget(methodHandleNode.asJavaConstant(), true), original);
        }
        return null;
    }

    /**
     * Used for the MethodHandle.linkTo* methods (the {@link IntrinsicMethod#LINK_TO_STATIC},
     * {@link IntrinsicMethod#LINK_TO_SPECIAL}, {@link IntrinsicMethod#LINK_TO_VIRTUAL}, and
     * {@link IntrinsicMethod#LINK_TO_INTERFACE} methods) to get the target {@link InvokeNode} if
     * the member name argument is constant.
     *
     * @return invoke node for the member name target
     */
    private static InvokeNode getLinkToTarget(Assumptions assumptions, IntrinsicMethod intrinsicMethod, MethodHandleAccessProvider methodHandleAccess, ResolvedJavaMethod original, int bci,
                    JavaType returnType, ValueNode[] arguments) {
        ValueNode memberNameNode = getMemberName(arguments);
        if (memberNameNode.isConstant()) {
            return getTargetInvokeNode(assumptions, intrinsicMethod, bci, returnType, arguments, methodHandleAccess.resolveLinkToTarget(memberNameNode.asJavaConstant()), original);
        }
        return null;
    }

    /**
     * Helper function to get the {@link InvokeNode} for the targetMethod of a
     * java.lang.invoke.MemberName.
     *
     * @param target the target, already loaded from the member name node
     * @return invoke node for the member name target
     */
    private static InvokeNode getTargetInvokeNode(Assumptions assumptions, IntrinsicMethod intrinsicMethod, int bci, JavaType returnType, ValueNode[] arguments, ResolvedJavaMethod target,
                    ResolvedJavaMethod original) {
        if (target == null) {
            return null;
        }

        // In lambda forms we erase signature types to avoid resolving issues
        // involving class loaders. When we optimize a method handle invoke
        // to a direct call we must cast the receiver and arguments to its
        // actual types.
        Signature signature = target.getSignature();
        final boolean isStatic = target.isStatic();
        final int receiverSkip = isStatic ? 0 : 1;

        // Cast receiver to its type.
        if (!isStatic) {
            JavaType receiverType = target.getDeclaringClass();
            maybeCastArgument(arguments, 0, receiverType);
        }

        // Cast reference arguments to its type.
        for (int index = 0; index < signature.getParameterCount(false); index++) {
            JavaType parameterType = signature.getParameterType(index, target.getDeclaringClass());
            maybeCastArgument(arguments, receiverSkip + index, parameterType);
        }

        if (target.canBeStaticallyBound()) {
            return createTargetInvokeNode(intrinsicMethod, target, original, bci, returnType, arguments);
        }

        // Try to get the most accurate receiver type
        if (intrinsicMethod == IntrinsicMethod.LINK_TO_VIRTUAL || intrinsicMethod == IntrinsicMethod.LINK_TO_INTERFACE) {
            ValueNode receiver = getReceiver(arguments);
            ResolvedJavaType receiverType = StampTool.typeOrNull(receiver.stamp());
            if (receiverType != null) {
                AssumptionResult<ResolvedJavaMethod> concreteMethod = receiverType.findUniqueConcreteMethod(target);
                if (concreteMethod != null) {
                    assumptions.record(concreteMethod);
                    return createTargetInvokeNode(intrinsicMethod, concreteMethod.getResult(), original, bci, returnType, arguments);
                }
            }
        } else {
            AssumptionResult<ResolvedJavaMethod> concreteMethod = target.getDeclaringClass().findUniqueConcreteMethod(target);
            if (concreteMethod != null) {
                assumptions.record(concreteMethod);
                return createTargetInvokeNode(intrinsicMethod, concreteMethod.getResult(), original, bci, returnType, arguments);
            }
        }

        return null;
    }

    /**
     * Inserts a node to cast the argument at index to the given type if the given type is more
     * concrete than the argument type.
     *
     * @param index of the argument to be cast
     * @param type the type the argument should be cast to
     */
    private static void maybeCastArgument(ValueNode[] arguments, int index, JavaType type) {
        if (type instanceof ResolvedJavaType) {
            ResolvedJavaType targetType = (ResolvedJavaType) type;
            if (!targetType.isPrimitive()) {
                ValueNode argument = arguments[index];
                ResolvedJavaType argumentType = StampTool.typeOrNull(argument.stamp());
                if (argumentType == null || (argumentType.isAssignableFrom(targetType) && !argumentType.equals(targetType))) {
                    PiNode piNode = new PiNode(argument, StampFactory.declared(targetType));
                    arguments[index] = piNode;
                }
            }
        }
    }

    /**
     * Creates an {@link InvokeNode} for the given target method. The {@link CallTargetNode} passed
     * to the InvokeNode is in fact a {@link ResolvedMethodHandleCallTargetNode}.
     *
     * @return invoke node for the member name target
     */
    private static InvokeNode createTargetInvokeNode(IntrinsicMethod intrinsicMethod, ResolvedJavaMethod target, ResolvedJavaMethod original, int bci, JavaType returnType, ValueNode[] arguments) {
        InvokeKind targetInvokeKind = target.isStatic() ? InvokeKind.Static : InvokeKind.Special;
        JavaType targetReturnType = target.getSignature().getReturnType(null);

        // MethodHandleLinkTo* nodes have a trailing MemberName argument which
        // needs to be popped.
        ValueNode[] targetArguments;
        switch (intrinsicMethod) {
            case INVOKE_BASIC:
                targetArguments = arguments;
                break;
            case LINK_TO_STATIC:
            case LINK_TO_SPECIAL:
            case LINK_TO_VIRTUAL:
            case LINK_TO_INTERFACE:
                targetArguments = Arrays.copyOfRange(arguments, 0, arguments.length - 1);
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }

        MethodCallTargetNode callTarget = ResolvedMethodHandleCallTargetNode.create(targetInvokeKind, target, targetArguments, targetReturnType, original, arguments, returnType);

        // The call target can have a different return type than the invoker,
        // e.g. the target returns an Object but the invoker void. In this case
        // we need to use the stamp of the invoker. Note: always using the
        // invoker's stamp would be wrong because it's a less concrete type
        // (usually java.lang.Object).
        if (returnType.getKind() == Kind.Void) {
            return new InvokeNode(callTarget, bci, StampFactory.forVoid());
        } else {
            return new InvokeNode(callTarget, bci);
        }
    }
}
