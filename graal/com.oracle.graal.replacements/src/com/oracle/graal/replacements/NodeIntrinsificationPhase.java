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
package com.oracle.graal.replacements;

import static com.oracle.graal.api.meta.MetaUtil.*;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.Node.ConstantNodeParameter;
import com.oracle.graal.graph.Node.NodeIntrinsic;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.replacements.Snippet.*;

/**
 * Replaces calls to {@link NodeIntrinsic}s with nodes and calls to methods annotated with
 * {@link Fold} with the result of invoking the annotated method via reflection.
 */
public class NodeIntrinsificationPhase extends Phase {

    private final MetaAccessProvider runtime;
    private final BoxingMethodPool pool;

    public NodeIntrinsificationPhase(MetaAccessProvider runtime, BoxingMethodPool pool) {
        this.runtime = runtime;
        this.pool = pool;
    }

    @Override
    protected void run(StructuredGraph graph) {
        for (Invoke i : graph.getInvokes()) {
            if (i.callTarget() instanceof MethodCallTargetNode) {
                tryIntrinsify(i);
            }
        }
    }

    public static Class<?>[] signatureToTypes(Signature signature, ResolvedJavaType accessingClass) {
        int count = signature.getParameterCount(false);
        Class<?>[] result = new Class<?>[count];
        for (int i = 0; i < result.length; ++i) {
            result[i] = getMirrorOrFail(signature.getParameterType(i, accessingClass).resolve(accessingClass), Thread.currentThread().getContextClassLoader());
        }
        return result;
    }

    private boolean tryIntrinsify(Invoke invoke) {
        ResolvedJavaMethod target = invoke.methodCallTarget().targetMethod();
        NodeIntrinsic intrinsic = target.getAnnotation(Node.NodeIntrinsic.class);
        ResolvedJavaType declaringClass = target.getDeclaringClass();
        if (intrinsic != null) {
            assert target.getAnnotation(Fold.class) == null;

            Class<?>[] parameterTypes = signatureToTypes(target.getSignature(), declaringClass);
            ResolvedJavaType returnType = target.getSignature().getReturnType(declaringClass).resolve(declaringClass);

            // Prepare the arguments for the reflective constructor call on the node class.
            Object[] nodeConstructorArguments = prepareArguments(invoke, parameterTypes, target, false);
            if (nodeConstructorArguments == null) {
                return false;
            }

            // Create the new node instance.
            Class<?> c = getNodeClass(target, intrinsic);
            Node newInstance = createNodeInstance(c, parameterTypes, returnType, intrinsic.setStampFromReturnType(), nodeConstructorArguments);

            // Replace the invoke with the new node.
            invoke.node().graph().add(newInstance);
            invoke.intrinsify(newInstance);

            // Clean up checkcast instructions inserted by javac if the return type is generic.
            cleanUpReturnCheckCast(newInstance);
        } else if (target.getAnnotation(Fold.class) != null) {
            Class<?>[] parameterTypes = signatureToTypes(target.getSignature(), declaringClass);

            // Prepare the arguments for the reflective method call
            Object[] arguments = prepareArguments(invoke, parameterTypes, target, true);
            if (arguments == null) {
                return false;
            }
            Object receiver = null;
            if (!invoke.methodCallTarget().isStatic()) {
                receiver = arguments[0];
                arguments = Arrays.asList(arguments).subList(1, arguments.length).toArray();
            }

            // Call the method
            Constant constant = callMethod(target.getSignature().getReturnKind(), getMirrorOrFail(declaringClass, Thread.currentThread().getContextClassLoader()), target.getName(), parameterTypes,
                            receiver, arguments);

            if (constant != null) {
                // Replace the invoke with the result of the call
                ConstantNode node = ConstantNode.forConstant(constant, runtime, invoke.node().graph());
                invoke.intrinsify(node);

                // Clean up checkcast instructions inserted by javac if the return type is generic.
                cleanUpReturnCheckCast(node);
            } else {
                // Remove the invoke
                invoke.intrinsify(null);
            }
        }
        return true;
    }

    /**
     * Converts the arguments of an invoke node to object values suitable for use as the arguments
     * to a reflective invocation of a Java constructor or method.
     * 
     * @param folding specifies if the invocation is for handling a {@link Fold} annotation
     * @return the arguments for the reflective invocation or null if an argument of {@code invoke}
     *         that is expected to be constant isn't
     */
    private Object[] prepareArguments(Invoke invoke, Class<?>[] parameterTypes, ResolvedJavaMethod target, boolean folding) {
        NodeInputList<ValueNode> arguments = invoke.callTarget().arguments();
        Object[] reflectionCallArguments = new Object[arguments.size()];
        for (int i = 0; i < reflectionCallArguments.length; ++i) {
            int parameterIndex = i;
            if (!invoke.methodCallTarget().isStatic()) {
                parameterIndex--;
            }
            ValueNode argument = tryBoxingElimination(parameterIndex, target, arguments.get(i));
            if (folding || MetaUtil.getParameterAnnotation(ConstantNodeParameter.class, parameterIndex, target) != null) {
                if (!(argument instanceof ConstantNode)) {
                    return null;
                }
                ConstantNode constantNode = (ConstantNode) argument;
                Constant constant = constantNode.asConstant();
                Object o = constant.asBoxedValue();
                if (o instanceof Class<?>) {
                    reflectionCallArguments[i] = runtime.lookupJavaType((Class<?>) o);
                    parameterTypes[i] = ResolvedJavaType.class;
                } else {
                    if (parameterTypes[i] == boolean.class) {
                        reflectionCallArguments[i] = Boolean.valueOf(constant.asInt() != 0);
                    } else if (parameterTypes[i] == byte.class) {
                        reflectionCallArguments[i] = Byte.valueOf((byte) constant.asInt());
                    } else if (parameterTypes[i] == short.class) {
                        reflectionCallArguments[i] = Short.valueOf((short) constant.asInt());
                    } else if (parameterTypes[i] == char.class) {
                        reflectionCallArguments[i] = Character.valueOf((char) constant.asInt());
                    } else {
                        reflectionCallArguments[i] = o;
                    }
                }
            } else {
                reflectionCallArguments[i] = argument;
                parameterTypes[i] = ValueNode.class;
            }
        }
        return reflectionCallArguments;
    }

    private static Class<?> getNodeClass(ResolvedJavaMethod target, NodeIntrinsic intrinsic) {
        Class<?> result = intrinsic.value();
        if (result == NodeIntrinsic.class) {
            return getMirrorOrFail(target.getDeclaringClass(), Thread.currentThread().getContextClassLoader());
        }
        assert Node.class.isAssignableFrom(result);
        return result;
    }

    private ValueNode tryBoxingElimination(int parameterIndex, ResolvedJavaMethod target, ValueNode node) {
        if (parameterIndex >= 0) {
            Type type = target.getGenericParameterTypes()[parameterIndex];
            if (type instanceof TypeVariable) {
                TypeVariable typeVariable = (TypeVariable) type;
                if (typeVariable.getBounds().length == 1) {
                    Type boundType = typeVariable.getBounds()[0];
                    if (boundType instanceof Class && ((Class) boundType).getSuperclass() == null) {
                        // Unbound generic => try boxing elimination
                        if (node.usages().count() == 2) {
                            if (node instanceof Invoke) {
                                Invoke invokeNode = (Invoke) node;
                                MethodCallTargetNode callTarget = invokeNode.methodCallTarget();
                                if (pool.isBoxingMethod(callTarget.targetMethod())) {
                                    FrameState stateAfter = invokeNode.stateAfter();
                                    assert stateAfter.usages().count() == 1;
                                    invokeNode.node().replaceAtUsages(null);
                                    ValueNode result = callTarget.arguments().get(0);
                                    StructuredGraph graph = (StructuredGraph) node.graph();
                                    if (invokeNode instanceof InvokeWithExceptionNode) {
                                        // Destroy exception edge & clear stateAfter.
                                        InvokeWithExceptionNode invokeWithExceptionNode = (InvokeWithExceptionNode) invokeNode;

                                        invokeWithExceptionNode.killExceptionEdge();
                                        graph.removeSplit(invokeWithExceptionNode, invokeWithExceptionNode.next());
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

    private static Class asBoxedType(Class type) {
        if (!type.isPrimitive()) {
            return type;
        }

        if (Boolean.TYPE == type) {
            return Boolean.class;
        }
        if (Character.TYPE == type) {
            return Character.class;
        }
        if (Byte.TYPE == type) {
            return Byte.class;
        }
        if (Short.TYPE == type) {
            return Short.class;
        }
        if (Integer.TYPE == type) {
            return Integer.class;
        }
        if (Long.TYPE == type) {
            return Long.class;
        }
        if (Float.TYPE == type) {
            return Float.class;
        }
        assert Double.TYPE == type;
        return Double.class;
    }

    static final int VARARGS = 0x00000080;

    private static Node createNodeInstance(Class<?> nodeClass, Class<?>[] parameterTypes, ResolvedJavaType returnType, boolean setStampFromReturnType, Object[] nodeConstructorArguments) {
        Object[] arguments = null;
        Constructor<?> constructor = null;
        nextConstructor: for (Constructor c : nodeClass.getDeclaredConstructors()) {
            Class[] signature = c.getParameterTypes();
            if ((c.getModifiers() & VARARGS) != 0) {
                int fixedArgs = signature.length - 1;
                if (parameterTypes.length < fixedArgs) {
                    continue nextConstructor;
                }

                for (int i = 0; i < fixedArgs; i++) {
                    if (!parameterTypes[i].equals(signature[i])) {
                        continue nextConstructor;
                    }
                }

                Class componentType = signature[fixedArgs].getComponentType();
                assert componentType != null : "expected last parameter of varargs constructor " + c + " to be an array type";
                Class boxedType = asBoxedType(componentType);
                for (int i = fixedArgs; i < nodeConstructorArguments.length; i++) {
                    if (!boxedType.isInstance(nodeConstructorArguments[i])) {
                        continue nextConstructor;
                    }
                }

                arguments = Arrays.copyOf(nodeConstructorArguments, fixedArgs + 1);
                int varargsLength = nodeConstructorArguments.length - fixedArgs;
                Object varargs = Array.newInstance(componentType, varargsLength);
                for (int i = fixedArgs; i < nodeConstructorArguments.length; i++) {
                    Array.set(varargs, i - fixedArgs, nodeConstructorArguments[i]);
                }
                arguments[fixedArgs] = varargs;
                constructor = c;
                break;
            } else if (Arrays.equals(parameterTypes, signature)) {
                arguments = nodeConstructorArguments;
                constructor = c;
                break;
            }
        }
        if (constructor == null) {
            throw new GraalInternalError("Could not find constructor in " + nodeClass + " compatible with signature " + Arrays.toString(parameterTypes));
        }
        constructor.setAccessible(true);
        try {
            ValueNode intrinsicNode = (ValueNode) constructor.newInstance(arguments);
            if (setStampFromReturnType) {
                if (returnType.getKind() == Kind.Object) {
                    intrinsicNode.setStamp(StampFactory.declared(returnType));
                } else {
                    intrinsicNode.setStamp(StampFactory.forKind(returnType.getKind()));
                }
            }
            return intrinsicNode;
        } catch (Exception e) {
            throw new RuntimeException(constructor + Arrays.toString(nodeConstructorArguments), e);
        }
    }

    /**
     * Calls a Java method via reflection.
     */
    private static Constant callMethod(Kind returnKind, Class<?> holder, String name, Class<?>[] parameterTypes, Object receiver, Object[] arguments) {
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
            return Constant.forBoxed(returnKind, result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sourceLocation(Node n) {
        String loc = GraphUtil.approxSourceLocation(n);
        return loc == null ? "<unknown>" : loc;
    }

    public void cleanUpReturnCheckCast(Node newInstance) {
        if (newInstance instanceof ValueNode && (((ValueNode) newInstance).kind() != Kind.Object || ((ValueNode) newInstance).stamp() == StampFactory.forNodeIntrinsic())) {
            StructuredGraph graph = (StructuredGraph) newInstance.graph();
            for (CheckCastNode checkCastNode : newInstance.usages().filter(CheckCastNode.class).snapshot()) {
                for (ProxyNode vpn : checkCastNode.usages().filter(ProxyNode.class).snapshot()) {
                    graph.replaceFloating(vpn, checkCastNode);
                }
                for (Node checkCastUsage : checkCastNode.usages().snapshot()) {
                    if (checkCastUsage instanceof ValueAnchorNode) {
                        ValueAnchorNode valueAnchorNode = (ValueAnchorNode) checkCastUsage;
                        graph.removeFixed(valueAnchorNode);
                    } else if (checkCastUsage instanceof MethodCallTargetNode) {
                        MethodCallTargetNode checkCastCallTarget = (MethodCallTargetNode) checkCastUsage;
                        if (pool.isUnboxingMethod(checkCastCallTarget.targetMethod())) {
                            Invoke invokeNode = checkCastCallTarget.invoke();
                            invokeNode.node().replaceAtUsages(newInstance);
                            if (invokeNode instanceof InvokeWithExceptionNode) {
                                // Destroy exception edge & clear stateAfter.
                                InvokeWithExceptionNode invokeWithExceptionNode = (InvokeWithExceptionNode) invokeNode;

                                invokeWithExceptionNode.killExceptionEdge();
                                graph.removeSplit(invokeWithExceptionNode, invokeWithExceptionNode.next());
                            } else {
                                graph.removeFixed((InvokeNode) invokeNode);
                            }
                            checkCastCallTarget.safeDelete();
                        } else {
                            assert checkCastCallTarget.targetMethod().getAnnotation(NodeIntrinsic.class) != null : "checkcast at " + sourceLocation(checkCastNode) +
                                            " not used by an unboxing method or node intrinsic, but by a call at " + sourceLocation(checkCastCallTarget.usages().first()) + " to " +
                                            checkCastCallTarget.targetMethod();
                            checkCastUsage.replaceFirstInput(checkCastNode, checkCastNode.object());
                        }
                    } else if (checkCastUsage instanceof FrameState) {
                        checkCastUsage.replaceFirstInput(checkCastNode, null);
                    } else if (checkCastUsage instanceof ReturnNode && checkCastNode.object().stamp() == StampFactory.forNodeIntrinsic()) {
                        checkCastUsage.replaceFirstInput(checkCastNode, checkCastNode.object());
                    } else {
                        assert false : sourceLocation(checkCastUsage) + " has unexpected usage " + checkCastUsage + " of checkcast at " + sourceLocation(checkCastNode);
                    }
                }
                FixedNode next = checkCastNode.next();
                checkCastNode.setNext(null);
                checkCastNode.replaceAtPredecessor(next);
                GraphUtil.killCFG(checkCastNode);
            }
        }
    }
}
