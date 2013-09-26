/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.truffle;

import static com.oracle.graal.phases.GraalOptions.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.Node;
import com.oracle.graal.java.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.java.MethodCallTargetNode.InvokeKind;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.phases.tiers.*;
import com.oracle.graal.truffle.phases.*;
import com.oracle.graal.virtual.phases.ea.*;
import com.oracle.truffle.api.*;
import com.oracle.truffle.api.nodes.*;

/**
 * Implementation of a cache for Truffle graphs for improving partial evaluation time.
 */
public final class TruffleCache {

    private final MetaAccessProvider metaAccessProvider;
    private final GraphBuilderConfiguration config;
    private final OptimisticOptimizations optimisticOptimizations;
    private final Replacements replacements;

    private final HashMap<List<Object>, StructuredGraph> cache = new HashMap<>();
    private final StructuredGraph markerGraph = new StructuredGraph();

    public TruffleCache(MetaAccessProvider metaAccessProvider, GraphBuilderConfiguration config, OptimisticOptimizations optimisticOptimizations, Replacements replacements) {
        this.metaAccessProvider = metaAccessProvider;
        this.config = config;
        this.optimisticOptimizations = optimisticOptimizations;
        this.replacements = replacements;
    }

    @SuppressWarnings("unused")
    public StructuredGraph lookup(final ResolvedJavaMethod method, final NodeInputList<ValueNode> arguments, final Assumptions assumptions, final CanonicalizerPhase finalCanonicalizer) {

        List<Object> key = new ArrayList<>(arguments.size() + 1);
        key.add(method);
        for (ValueNode v : arguments) {
            if (v.kind() == Kind.Object) {
                key.add(v.stamp());
            }
        }
        StructuredGraph resultGraph = cache.get(key);
        if (resultGraph != null) {
            return resultGraph;
        }

        if (resultGraph == markerGraph) {
            // Avoid recursive inline.
            return null;
        }

        cache.put(key, markerGraph);
        resultGraph = Debug.scope("TruffleCache", new Object[]{metaAccessProvider, method}, new Callable<StructuredGraph>() {

            public StructuredGraph call() {

                final StructuredGraph graph = new StructuredGraph(method);
                PhaseContext context = new PhaseContext(metaAccessProvider, new Assumptions(false), replacements);
                new GraphBuilderPhase(metaAccessProvider, config, optimisticOptimizations).apply(graph);

                for (LocalNode l : graph.getNodes(LocalNode.class)) {
                    if (l.kind() == Kind.Object) {
                        ValueNode actualArgument = arguments.get(l.index());
                        l.setStamp(l.stamp().join(actualArgument.stamp()));
                    }
                }

                // Intrinsify methods.
                new ReplaceIntrinsicsPhase(replacements).apply(graph);

                // Convert deopt to guards.
                new ConvertDeoptimizeToGuardPhase().apply(graph);

                CanonicalizerPhase canonicalizerPhase = new CanonicalizerPhase(!AOTCompilation.getValue());
                PartialEscapePhase partialEscapePhase = new PartialEscapePhase(false, canonicalizerPhase);

                int mark = 0;
                while (true) {

                    partialEscapePhase.apply(graph, context);

                    // Conditional elimination.
                    ConditionalEliminationPhase conditionalEliminationPhase = new ConditionalEliminationPhase(metaAccessProvider);
                    conditionalEliminationPhase.apply(graph);

                    // Canonicalize / constant propagate.
                    canonicalizerPhase.apply(graph, context);

                    boolean inliningProgress = false;
                    for (MethodCallTargetNode methodCallTarget : graph.getNodes(MethodCallTargetNode.class)) {
                        if (graph.getMark() != mark) {
                            // Make sure macro substitutions such as
                            // CompilerDirectives.transferToInterpreter get processed first.
                            for (Node newNode : graph.getNewNodes(mark)) {
                                if (newNode instanceof MethodCallTargetNode) {
                                    MethodCallTargetNode methodCallTargetNode = (MethodCallTargetNode) newNode;
                                    Class<? extends FixedWithNextNode> macroSubstitution = replacements.getMacroSubstitution(methodCallTargetNode.targetMethod());
                                    if (macroSubstitution != null) {
                                        InliningUtil.inlineMacroNode(methodCallTargetNode.invoke(), methodCallTargetNode.targetMethod(), methodCallTargetNode.graph(), macroSubstitution);
                                    } else {
                                        tryCutOffRuntimeExceptions(methodCallTargetNode);
                                    }
                                }
                            }
                            mark = graph.getMark();
                        }
                        if (methodCallTarget.isAlive() && methodCallTarget.invoke() != null && shouldInline(methodCallTarget)) {
                            inliningProgress = true;
                            List<Node> canonicalizerUsages = new ArrayList<Node>();
                            for (Node n : methodCallTarget.invoke().asNode().usages()) {
                                if (n instanceof Canonicalizable) {
                                    canonicalizerUsages.add(n);
                                }
                            }
                            List<ValueNode> argumentSnapshot = methodCallTarget.arguments().snapshot();
                            int beforeInvokeMark = graph.getMark();
                            expandInvoke(methodCallTarget);
                            for (Node arg : argumentSnapshot) {
                                if (arg != null) {
                                    for (Node argUsage : arg.usages()) {
                                        if (graph.isNew(beforeInvokeMark, argUsage) && argUsage instanceof Canonicalizable) {
                                            canonicalizerUsages.add(argUsage);
                                        }
                                    }
                                }
                            }
                            canonicalizerPhase.applyIncremental(graph, context, canonicalizerUsages);
                        }
                    }

                    // Convert deopt to guards.
                    new ConvertDeoptimizeToGuardPhase().apply(graph);

                    if (!inliningProgress) {
                        break;
                    }
                }

                if (TruffleCompilerOptions.TraceTruffleCacheDetails.getValue()) {
                    TTY.println(String.format("[truffle] added to graph cache method %s with %d nodes.", method, graph.getNodeCount()));
                }
                return graph;
            }
        });
        cache.put(key, resultGraph);
        return resultGraph;
    }

    private void expandInvoke(MethodCallTargetNode methodCallTargetNode) {
        StructuredGraph inlineGraph = replacements.getMethodSubstitution(methodCallTargetNode.targetMethod());
        if (inlineGraph == null) {
            inlineGraph = TruffleCache.this.lookup(methodCallTargetNode.targetMethod(), methodCallTargetNode.arguments(), null, null);
        }
        if (inlineGraph == this.markerGraph) {
            // Can happen for recursive calls.
            throw GraphUtil.approxSourceException(methodCallTargetNode, new IllegalStateException("Found illegal recursive call to " + methodCallTargetNode.targetMethod() +
                            ", must annotate such calls with @CompilerDirectives.SlowPath!"));
        }
        Invoke invoke = methodCallTargetNode.invoke();
        InliningUtil.inline(invoke, inlineGraph, true);
    }

    private boolean tryCutOffRuntimeExceptions(MethodCallTargetNode methodCallTargetNode) {
        if (methodCallTargetNode.targetMethod().isConstructor()) {
            ResolvedJavaType runtimeException = metaAccessProvider.lookupJavaType(RuntimeException.class);
            ResolvedJavaType controlFlowException = metaAccessProvider.lookupJavaType(ControlFlowException.class);
            ResolvedJavaType exceptionType = Objects.requireNonNull(ObjectStamp.typeOrNull(methodCallTargetNode.receiver().stamp()));
            if (runtimeException.isAssignableFrom(methodCallTargetNode.targetMethod().getDeclaringClass()) && !controlFlowException.isAssignableFrom(exceptionType)) {
                DeoptimizeNode deoptNode = methodCallTargetNode.graph().add(new DeoptimizeNode(DeoptimizationAction.InvalidateRecompile, DeoptimizationReason.UnreachedCode));
                FixedNode invokeNode = methodCallTargetNode.invoke().asNode();
                invokeNode.replaceAtPredecessor(deoptNode);
                GraphUtil.killCFG(invokeNode);
                return true;
            }
        }
        return false;
    }

    private static boolean shouldInline(final MethodCallTargetNode methodCallTargetNode) {
        return (methodCallTargetNode.invokeKind() == InvokeKind.Special || methodCallTargetNode.invokeKind() == InvokeKind.Static) &&
                        !Modifier.isNative(methodCallTargetNode.targetMethod().getModifiers()) && methodCallTargetNode.targetMethod().getAnnotation(ExplodeLoop.class) == null &&
                        methodCallTargetNode.targetMethod().getAnnotation(CompilerDirectives.SlowPath.class) == null;
    }
}
