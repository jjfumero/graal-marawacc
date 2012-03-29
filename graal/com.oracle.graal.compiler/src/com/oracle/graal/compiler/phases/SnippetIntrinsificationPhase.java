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
package com.oracle.graal.compiler.phases;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.graph.*;
import com.oracle.graal.graph.Node.ConstantNodeParameter;
import com.oracle.graal.graph.Node.Fold;
import com.oracle.graal.graph.Node.NodeIntrinsic;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.max.cri.ci.*;
import com.oracle.max.cri.ri.*;

public class SnippetIntrinsificationPhase extends Phase {

    private final RiRuntime runtime;
    private final BoxingMethodPool pool;

    public SnippetIntrinsificationPhase(RiRuntime runtime, BoxingMethodPool pool) {
        this.runtime = runtime;
        this.pool = pool;
    }

    @Override
    protected void run(StructuredGraph graph) {
        for (Invoke i : graph.getInvokes()) {
            tryIntrinsify(i);
        }
    }

    private void tryIntrinsify(Invoke invoke) {
        RiResolvedMethod target = invoke.callTarget().targetMethod();
        NodeIntrinsic intrinsic = target.getAnnotation(Node.NodeIntrinsic.class);
        if (intrinsic != null) {
            assert target.getAnnotation(Node.Fold.class) == null;

            Class< ? >[] parameterTypes = CiUtil.signatureToTypes(target.signature(), target.holder());

            // Prepare the arguments for the reflective constructor call on the node class.
            Object[] nodeConstructorArguments = prepareArguments(invoke, parameterTypes, target, false);

            // Create the new node instance.
            Class< ? > c = getNodeClass(target, intrinsic);
            Node newInstance = createNodeInstance(c, parameterTypes, nodeConstructorArguments);

            // Replace the invoke with the new node.
            invoke.node().graph().add(newInstance);
            invoke.intrinsify(newInstance);

            // Clean up checkcast instructions inserted by javac if the return type is generic.
            cleanUpReturnCheckCast(newInstance);
        } else if (target.getAnnotation(Node.Fold.class) != null) {
            Class< ? >[] parameterTypes = CiUtil.signatureToTypes(target.signature(), target.holder());

            // Prepare the arguments for the reflective method call
            Object[] arguments = prepareArguments(invoke, parameterTypes, target, true);
            Object receiver = null;
            if (!invoke.callTarget().isStatic()) {
                receiver = arguments[0];
                arguments = Arrays.asList(arguments).subList(1, arguments.length).toArray();
            }

            // Call the method
            CiConstant constant = callMethod(target.signature().returnKind(false), target.holder().toJava(), target.name(), parameterTypes, receiver, arguments);

            if (constant != null) {
                // Replace the invoke with the result of the call
                ConstantNode node = ConstantNode.forCiConstant(constant, runtime, invoke.node().graph());
                invoke.intrinsify(node);

                // Clean up checkcast instructions inserted by javac if the return type is generic.
                cleanUpReturnCheckCast(node);
            } else {
                // Remove the invoke
                invoke.intrinsify(null);
            }
        }
    }

    /**
     * Converts the arguments of an invoke node to object values suitable for use as the arguments
     * to a reflective invocation of a Java constructor or method.
     *
     * @param folding specifies if the invocation is for handling a {@link Fold} annotation
     */
    private Object[] prepareArguments(Invoke invoke, Class< ? >[] parameterTypes, RiResolvedMethod target, boolean folding) {
        NodeInputList<ValueNode> arguments = invoke.callTarget().arguments();
        Object[] reflectionCallArguments = new Object[arguments.size()];
        for (int i = 0; i < reflectionCallArguments.length; ++i) {
            int parameterIndex = i;
            if (!invoke.callTarget().isStatic()) {
                parameterIndex--;
            }
            ValueNode argument = tryBoxingElimination(parameterIndex, target, arguments.get(i));
            if (folding || CiUtil.getParameterAnnotation(ConstantNodeParameter.class, parameterIndex, target) != null) {
                assert argument instanceof ConstantNode : "parameter " + parameterIndex + " must be a compile time constant for calling " + invoke.callTarget().targetMethod() + ": " + argument;
                ConstantNode constantNode = (ConstantNode) argument;
                Object o = constantNode.asConstant().boxedValue();
                if (o instanceof Class< ? >) {
                    reflectionCallArguments[i] = runtime.getType((Class< ? >) o);
                    parameterTypes[i] = RiResolvedType.class;
                } else {
                    reflectionCallArguments[i] = o;
                }
            } else {
                reflectionCallArguments[i] = argument;
                parameterTypes[i] = ValueNode.class;
            }
        }
        return reflectionCallArguments;
    }

    private static Class< ? > getNodeClass(RiResolvedMethod target, NodeIntrinsic intrinsic) {
        Class< ? > result = intrinsic.value();
        if (result == NodeIntrinsic.class) {
            result = target.holder().toJava();
        }
        assert Node.class.isAssignableFrom(result);
        return result;
    }

    private ValueNode tryBoxingElimination(int parameterIndex, RiResolvedMethod target, ValueNode node) {
        if (parameterIndex >= 0) {
            Type type = target.getGenericParameterTypes()[parameterIndex];
            if (type instanceof TypeVariable) {
                TypeVariable typeVariable = (TypeVariable) type;
                if (typeVariable.getBounds().length == 1) {
                    Type boundType = typeVariable.getBounds()[0];
                    if (boundType instanceof Class && ((Class) boundType).getSuperclass() == null) {
                        // Unbound generic => try boxing elimination
                        if (node.usages().size() == 2) {
                            if (node instanceof Invoke) {
                                Invoke invokeNode = (Invoke) node;
                                MethodCallTargetNode callTarget = invokeNode.callTarget();
                                if (pool.isBoxingMethod(callTarget.targetMethod())) {
                                    FrameState stateAfter = invokeNode.stateAfter();
                                    assert stateAfter.usages().size() == 1;
                                    invokeNode.node().replaceAtUsages(null);
                                    ValueNode result = callTarget.arguments().get(0);
                                    StructuredGraph graph = (StructuredGraph) node.graph();
                                    if (invokeNode instanceof InvokeWithExceptionNode) {
                                        // Destroy exception edge & clear stateAfter.
                                        InvokeWithExceptionNode invokeWithExceptionNode = (InvokeWithExceptionNode) invokeNode;

                                        invokeWithExceptionNode.killExceptionEdge();
                                        graph.removeSplit(invokeWithExceptionNode, InvokeWithExceptionNode.NORMAL_EDGE);
                                    } else {
                                        graph.removeFixed((InvokeNode) invokeNode);
                                    }
                                    stateAfter.safeDelete();
                                    GraphUtil.propagateKill(callTarget);
                                    return result;
                                }
                            }
                        }
                    }
                }
            }
        }
        return node;
    }

    private static Node createNodeInstance(Class< ? > nodeClass, Class< ? >[] parameterTypes, Object[] nodeConstructorArguments) {

        Constructor< ? > constructor;
        try {
            constructor = nodeClass.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            return (ValueNode) constructor.newInstance(nodeConstructorArguments);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Calls a Java method via reflection.
     */
    private static CiConstant callMethod(CiKind returnKind, Class< ? > holder, String name, Class< ? >[] parameterTypes, Object receiver, Object[] arguments) {
        Method method;
        try {
            method = holder.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            Object result = method.invoke(receiver, arguments);
            if (result == null) {
                return null;
            }
            return CiConstant.forBoxed(returnKind, result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void cleanUpReturnCheckCast(Node newInstance) {
        if (newInstance instanceof ValueNode && ((ValueNode) newInstance).kind() != CiKind.Object) {
            StructuredGraph graph = (StructuredGraph) newInstance.graph();
            for (CheckCastNode checkCastNode : newInstance.usages().filter(CheckCastNode.class).snapshot()) {
                for (Node checkCastUsage : checkCastNode.usages().snapshot()) {
                    if (checkCastUsage instanceof ValueAnchorNode) {
                        ValueAnchorNode valueAnchorNode = (ValueAnchorNode) checkCastUsage;
                        graph.removeFixed(valueAnchorNode);
                    } else if (checkCastUsage instanceof MethodCallTargetNode) {
                        MethodCallTargetNode checkCastCallTarget = (MethodCallTargetNode) checkCastUsage;
                        assert pool.isUnboxingMethod(checkCastCallTarget.targetMethod());
                        Invoke invokeNode = checkCastCallTarget.invoke();
                        invokeNode.node().replaceAtUsages(newInstance);
                        if (invokeNode instanceof InvokeWithExceptionNode) {
                            // Destroy exception edge & clear stateAfter.
                            InvokeWithExceptionNode invokeWithExceptionNode = (InvokeWithExceptionNode) invokeNode;

                            invokeWithExceptionNode.killExceptionEdge();
                            graph.removeSplit(invokeWithExceptionNode, InvokeWithExceptionNode.NORMAL_EDGE);
                        } else {
                            graph.removeFixed((InvokeNode) invokeNode);
                        }
                        checkCastCallTarget.safeDelete();
                    } else if (checkCastUsage instanceof FrameState) {
                        checkCastUsage.replaceFirstInput(checkCastNode, null);
                    } else {
                        assert false : "unexpected checkcast usage: " + checkCastUsage;
                    }
                }
                checkCastNode.safeDelete();
            }
        }
    }
}
