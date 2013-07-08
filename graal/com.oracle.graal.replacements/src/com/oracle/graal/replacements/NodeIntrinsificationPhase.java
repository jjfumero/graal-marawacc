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
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.internal.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.Node.ConstantNodeParameter;
import com.oracle.graal.graph.Node.NodeIntrinsic;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.PhiNode.PhiType;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.replacements.Snippet.Fold;

/**
 * Replaces calls to {@link NodeIntrinsic}s with nodes and calls to methods annotated with
 * {@link Fold} with the result of invoking the annotated method via reflection.
 */
public class NodeIntrinsificationPhase extends Phase {

    private final MetaAccessProvider runtime;

    public NodeIntrinsificationPhase(MetaAccessProvider runtime) {
        this.runtime = runtime;
    }

    @Override
    protected void run(StructuredGraph graph) {
        ArrayList<Node> cleanUpReturnList = new ArrayList<>();
        for (MethodCallTargetNode node : graph.getNodes(MethodCallTargetNode.class)) {
            tryIntrinsify(node, cleanUpReturnList);
        }

        for (Node node : cleanUpReturnList) {
            cleanUpReturnCheckCast(node);
        }
    }

    private boolean tryIntrinsify(MethodCallTargetNode methodCallTargetNode, List<Node> cleanUpReturnList) {
        ResolvedJavaMethod target = methodCallTargetNode.targetMethod();
        NodeIntrinsic intrinsic = target.getAnnotation(Node.NodeIntrinsic.class);
        ResolvedJavaType declaringClass = target.getDeclaringClass();
        if (intrinsic != null) {
            assert target.getAnnotation(Fold.class) == null;
            assert Modifier.isStatic(target.getModifiers()) : "node intrinsic must be static: " + target;

            ResolvedJavaType[] parameterTypes = MetaUtil.resolveJavaTypes(MetaUtil.signatureToTypes(target), declaringClass);
            ResolvedJavaType returnType = target.getSignature().getReturnType(declaringClass).resolve(declaringClass);

            // Prepare the arguments for the reflective constructor call on the node class.
            Constant[] nodeConstructorArguments = prepareArguments(methodCallTargetNode, parameterTypes, target, false);
            if (nodeConstructorArguments == null) {
                return false;
            }

            // Create the new node instance.
            ResolvedJavaType c = getNodeClass(target, intrinsic);
            Node newInstance = createNodeInstance(c, parameterTypes, returnType, intrinsic.setStampFromReturnType(), nodeConstructorArguments);

            // Replace the invoke with the new node.
            methodCallTargetNode.graph().add(newInstance);
            methodCallTargetNode.invoke().intrinsify(newInstance);

            // Clean up checkcast instructions inserted by javac if the return type is generic.
            cleanUpReturnList.add(newInstance);
        } else if (target.getAnnotation(Fold.class) != null) {
            ResolvedJavaType[] parameterTypes = MetaUtil.resolveJavaTypes(MetaUtil.signatureToTypes(target), declaringClass);

            // Prepare the arguments for the reflective method call
            Constant[] arguments = prepareArguments(methodCallTargetNode, parameterTypes, target, true);
            if (arguments == null) {
                return false;
            }
            Constant receiver = null;
            if (!methodCallTargetNode.isStatic()) {
                receiver = arguments[0];
                arguments = Arrays.copyOfRange(arguments, 1, arguments.length);
                parameterTypes = Arrays.copyOfRange(parameterTypes, 1, parameterTypes.length);
            }

            // Call the method
            Constant constant = target.invoke(receiver, arguments);

            if (constant != null) {
                // Replace the invoke with the result of the call
                ConstantNode node = ConstantNode.forConstant(constant, runtime, methodCallTargetNode.graph());
                methodCallTargetNode.invoke().intrinsify(node);

                // Clean up checkcast instructions inserted by javac if the return type is generic.
                cleanUpReturnList.add(node);
            } else {
                // Remove the invoke
                methodCallTargetNode.invoke().intrinsify(null);
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
    private Constant[] prepareArguments(MethodCallTargetNode methodCallTargetNode, ResolvedJavaType[] parameterTypes, ResolvedJavaMethod target, boolean folding) {
        NodeInputList<ValueNode> arguments = methodCallTargetNode.arguments();
        Constant[] reflectionCallArguments = new Constant[arguments.size()];
        for (int i = 0; i < reflectionCallArguments.length; ++i) {
            int parameterIndex = i;
            if (!methodCallTargetNode.isStatic()) {
                parameterIndex--;
            }
            ValueNode argument = arguments.get(i);
            if (folding || MetaUtil.getParameterAnnotation(ConstantNodeParameter.class, parameterIndex, target) != null) {
                if (!(argument instanceof ConstantNode)) {
                    return null;
                }
                ConstantNode constantNode = (ConstantNode) argument;
                Constant constant = constantNode.asConstant();
                Object o = constant.asBoxedValue();
                if (o instanceof Class<?>) {
                    reflectionCallArguments[i] = Constant.forObject(runtime.lookupJavaType((Class<?>) o));
                    parameterTypes[i] = runtime.lookupJavaType(ResolvedJavaType.class);
                } else {
                    if (parameterTypes[i].getKind() == Kind.Boolean) {
                        reflectionCallArguments[i] = Constant.forObject(Boolean.valueOf(constant.asInt() != 0));
                    } else if (parameterTypes[i].getKind() == Kind.Byte) {
                        reflectionCallArguments[i] = Constant.forObject(Byte.valueOf((byte) constant.asInt()));
                    } else if (parameterTypes[i].getKind() == Kind.Short) {
                        reflectionCallArguments[i] = Constant.forObject(Short.valueOf((short) constant.asInt()));
                    } else if (parameterTypes[i].getKind() == Kind.Char) {
                        reflectionCallArguments[i] = Constant.forObject(Character.valueOf((char) constant.asInt()));
                    } else {
                        reflectionCallArguments[i] = constant;
                    }
                }
            } else {
                reflectionCallArguments[i] = Constant.forObject(argument);
                parameterTypes[i] = runtime.lookupJavaType(ValueNode.class);
            }
        }
        return reflectionCallArguments;
    }

    private ResolvedJavaType getNodeClass(ResolvedJavaMethod target, NodeIntrinsic intrinsic) {
        ResolvedJavaType result;
        if (intrinsic.value() == NodeIntrinsic.class) {
            result = target.getDeclaringClass();
        } else {
            result = runtime.lookupJavaType(intrinsic.value());
        }
        assert runtime.lookupJavaType(ValueNode.class).isAssignableFrom(result) : "Node intrinsic class " + toJavaName(result, false) + " derived from @" + NodeIntrinsic.class.getSimpleName() +
                        " annotation on " + format("%H.%n(%p)", target) + " is not a subclass of " + ValueNode.class;
        return result;
    }

    private Node createNodeInstance(ResolvedJavaType nodeClass, ResolvedJavaType[] parameterTypes, ResolvedJavaType returnType, boolean setStampFromReturnType, Constant[] nodeConstructorArguments) {
        ResolvedJavaMethod constructor = null;
        Constant[] arguments = null;

        for (ResolvedJavaMethod c : nodeClass.getDeclaredConstructors()) {
            Constant[] match = match(c, parameterTypes, nodeConstructorArguments);

            if (match != null) {
                if (constructor == null) {
                    constructor = c;
                    arguments = match;
                } else {
                    throw new GraalInternalError("Found multiple constructors in " + nodeClass + " compatible with signature " + Arrays.toString(parameterTypes) + ": " + constructor + ", " + c);
                }
            }
        }
        if (constructor == null) {
            throw new GraalInternalError("Could not find constructor in " + nodeClass + " compatible with signature " + Arrays.toString(parameterTypes));
        }

        try {
            ValueNode intrinsicNode = (ValueNode) constructor.newInstance(arguments).asObject();

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

    private Constant[] match(ResolvedJavaMethod c, ResolvedJavaType[] parameterTypes, Constant[] nodeConstructorArguments) {
        Constant[] arguments = null;
        boolean needsMetaAccessProviderArgument = false;

        ResolvedJavaType[] signature = MetaUtil.resolveJavaTypes(MetaUtil.signatureToTypes(c.getSignature(), null), c.getDeclaringClass());
        if (signature.length != 0 && signature[0].equals(runtime.lookupJavaType(MetaAccessProvider.class))) {
            // Chop off the MetaAccessProvider first parameter
            signature = Arrays.copyOfRange(signature, 1, signature.length);
            needsMetaAccessProviderArgument = true;
        }

        if (Arrays.equals(parameterTypes, signature)) {
            // Exact match
            arguments = nodeConstructorArguments;

        } else if (signature.length > 0 && signature[signature.length - 1].isArray()) {
            // Last constructor parameter is an array, so check if we have a vararg match
            int fixedArgs = signature.length - 1;
            if (parameterTypes.length < fixedArgs) {
                return null;
            }
            for (int i = 0; i < fixedArgs; i++) {
                if (!parameterTypes[i].equals(signature[i])) {
                    return null;
                }
            }

            ResolvedJavaType componentType = signature[fixedArgs].getComponentType();
            assert componentType != null;
            for (int i = fixedArgs; i < nodeConstructorArguments.length; i++) {
                if (!parameterTypes[i].equals(componentType)) {
                    return null;
                }
            }
            arguments = Arrays.copyOf(nodeConstructorArguments, fixedArgs + 1);
            arguments[fixedArgs] = componentType.newArray(nodeConstructorArguments.length - fixedArgs);

            Object varargs = arguments[fixedArgs].asObject();
            for (int i = fixedArgs; i < nodeConstructorArguments.length; i++) {
                Array.set(varargs, i - fixedArgs, nodeConstructorArguments[i].asBoxedValue());
            }
        } else {
            return null;
        }

        if (needsMetaAccessProviderArgument) {
            Constant[] copy = new Constant[arguments.length + 1];
            System.arraycopy(arguments, 0, copy, 1, arguments.length);
            copy[0] = Constant.forObject(runtime);
            arguments = copy;
        }
        return arguments;
    }

    private static String sourceLocation(Node n) {
        String loc = GraphUtil.approxSourceLocation(n);
        return loc == null ? "<unknown>" : loc;
    }

    public void cleanUpReturnCheckCast(Node newInstance) {
        if (newInstance instanceof ValueNode && (((ValueNode) newInstance).kind() != Kind.Object || ((ValueNode) newInstance).stamp() == StampFactory.forNodeIntrinsic())) {
            StructuredGraph graph = (StructuredGraph) newInstance.graph();
            for (CheckCastNode checkCastNode : newInstance.usages().filter(CheckCastNode.class).snapshot()) {
                for (Node checkCastUsage : checkCastNode.usages().snapshot()) {
                    checkCheckCastUsage(graph, newInstance, checkCastNode, checkCastUsage);
                }
                FixedNode next = checkCastNode.next();
                checkCastNode.setNext(null);
                checkCastNode.replaceAtPredecessor(next);
                GraphUtil.killCFG(checkCastNode);
            }
        }
    }

    private static void checkCheckCastUsage(StructuredGraph graph, Node intrinsifiedNode, Node input, Node usage) {
        if (usage instanceof ValueAnchorNode) {
            ValueAnchorNode valueAnchorNode = (ValueAnchorNode) usage;
            valueAnchorNode.removeAnchoredNode((ValueNode) input);
            Debug.log("%s: Removed a ValueAnchor input", Debug.contextSnapshot(JavaMethod.class));
        } else if (usage instanceof UnboxNode) {
            UnboxNode unbox = (UnboxNode) usage;
            unbox.replaceAtUsages(intrinsifiedNode);
            graph.removeFloating(unbox);
            Debug.log("%s: Removed an UnboxNode", Debug.contextSnapshot(JavaMethod.class));
        } else if (usage instanceof MethodCallTargetNode) {
            MethodCallTargetNode checkCastCallTarget = (MethodCallTargetNode) usage;
            assert checkCastCallTarget.targetMethod().getAnnotation(NodeIntrinsic.class) != null : "checkcast at " + sourceLocation(input) +
                            " not used by an unboxing method or node intrinsic, but by a call at " + sourceLocation(checkCastCallTarget.usages().first()) + " to " + checkCastCallTarget.targetMethod();
            usage.replaceFirstInput(input, intrinsifiedNode);
            Debug.log("%s: Checkcast used in an other node intrinsic", Debug.contextSnapshot(JavaMethod.class));
        } else if (usage instanceof FrameState) {
            usage.replaceFirstInput(input, null);
            Debug.log("%s: Checkcast used in a FS", Debug.contextSnapshot(JavaMethod.class));
        } else if (usage instanceof ReturnNode && ((ValueNode) intrinsifiedNode).stamp() == StampFactory.forNodeIntrinsic()) {
            usage.replaceFirstInput(input, intrinsifiedNode);
            Debug.log("%s: Checkcast used in a return with forNodeIntrinsic stamp", Debug.contextSnapshot(JavaMethod.class));
        } else if (usage instanceof IsNullNode) {
            if (!usage.usages().isEmpty()) {
                assert usage.usages().count() == 1 && usage.usages().first().predecessor() == input : usage + " " + input;
                graph.replaceFloating((FloatingNode) usage, LogicConstantNode.contradiction(graph));
                Debug.log("%s: Replaced IsNull with false", Debug.contextSnapshot(JavaMethod.class));
            } else {
                // Removed as usage of a GuardingPiNode
            }
        } else if (usage instanceof ProxyNode) {
            ProxyNode proxy = (ProxyNode) usage;
            assert proxy.type() == PhiType.Value;
            ProxyNode newProxy = graph.unique(new ProxyNode((ValueNode) intrinsifiedNode, proxy.proxyPoint(), PhiType.Value, proxy.getIdentity()));
            for (Node proxyUsage : usage.usages().snapshot()) {
                checkCheckCastUsage(graph, newProxy, proxy, proxyUsage);
            }
        } else if (usage instanceof PiNode) {
            for (Node piUsage : usage.usages().snapshot()) {
                checkCheckCastUsage(graph, intrinsifiedNode, usage, piUsage);
            }
        } else if (usage instanceof GuardingPiNode) {
            GuardingPiNode pi = (GuardingPiNode) usage;
            for (Node piUsage : pi.usages().snapshot()) {
                checkCheckCastUsage(graph, intrinsifiedNode, usage, piUsage);
            }
            graph.removeFixed(pi);
        } else {
            DebugScope.dump(graph, "exception");
            assert false : sourceLocation(usage) + " has unexpected usage " + usage + " of checkcast " + input + " at " + sourceLocation(input);
        }
    }
}
