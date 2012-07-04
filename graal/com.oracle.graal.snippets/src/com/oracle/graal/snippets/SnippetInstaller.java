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
package com.oracle.graal.snippets;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.*;
import com.oracle.graal.compiler.phases.*;
import com.oracle.graal.compiler.util.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.java.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.snippets.Snippet.InliningPolicy;

/**
 * Utility for snippet {@linkplain #install(Class) installation}.
 */
public class SnippetInstaller {

    private final MetaAccessProvider runtime;
    private final TargetDescription target;
    private final BoxingMethodPool pool;

    /**
     * A graph cache used by this installer to avoid using the compiler
     * storage for each method processed during snippet installation.
     * Without this, all processed methods are to be determined as
     * {@linkplain IntrinsificationPhase#canIntrinsify intrinsifiable}.
     */
    private final Map<ResolvedJavaMethod, StructuredGraph> graphCache;

    public SnippetInstaller(MetaAccessProvider runtime, TargetDescription target) {
        this.runtime = runtime;
        this.target = target;
        this.pool = new BoxingMethodPool(runtime);
        this.graphCache = new HashMap<>();
    }

    /**
     * Finds all the snippet methods in a given class, builds a graph for them and
     * installs the graph with the key value of {@code Graph.class} in the
     * {@linkplain ResolvedJavaMethod#compilerStorage() compiler storage} of each method.
     * <p>
     * If {@code snippetsHolder} is annotated with {@link ClassSubstitution}, then all
     * methods in the class are snippets. Otherwise, the snippets are those methods
     * annotated with {@link Snippet}.
     */
    public void install(Class<? extends SnippetsInterface> snippetsHolder) {
        if (snippetsHolder.isAnnotationPresent(ClassSubstitution.class)) {
            installSubstitutions(snippetsHolder, snippetsHolder.getAnnotation(ClassSubstitution.class).value());
        } else {
            installSnippets(snippetsHolder);
        }
    }

    private void installSnippets(Class< ? extends SnippetsInterface> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getAnnotation(Snippet.class) != null) {
                int modifiers = method.getModifiers();
                if (Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)) {
                    throw new RuntimeException("Snippet must not be abstract or native");
                }
                ResolvedJavaMethod snippet = runtime.getResolvedJavaMethod(method);
                assert snippet.compilerStorage().get(Graph.class) == null;
                StructuredGraph graph = makeGraph(snippet, inliningPolicy(snippet));
                //System.out.println("snippet: " + graph);
                snippet.compilerStorage().put(Graph.class, graph);
            }
        }
    }

    private void installSubstitutions(Class< ? extends SnippetsInterface> clazz, Class<?> originalClazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            try {
                Method originalMethod = originalClazz.getDeclaredMethod(method.getName(), method.getParameterTypes());
                if (!originalMethod.getReturnType().isAssignableFrom(method.getReturnType())) {
                    throw new RuntimeException("Snippet has incompatible return type");
                }
                int modifiers = method.getModifiers();
                if (Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)) {
                    throw new RuntimeException("Snippet must not be abstract or native");
                }
                ResolvedJavaMethod snippet = runtime.getResolvedJavaMethod(method);
                StructuredGraph graph = makeGraph(snippet, inliningPolicy(snippet));
                //System.out.println("snippet: " + graph);
                runtime.getResolvedJavaMethod(originalMethod).compilerStorage().put(Graph.class, graph);
            } catch (NoSuchMethodException e) {
                throw new GraalInternalError("Could not resolve method in " + originalClazz + " to substitute with " + method, e);
            }
        }
    }

    private static InliningPolicy inliningPolicy(ResolvedJavaMethod method) {
        Class<? extends InliningPolicy> policyClass = InliningPolicy.class;
        Snippet snippet = method.getAnnotation(Snippet.class);
        if (snippet != null) {
            policyClass = snippet.inlining();
        }
        if (policyClass == InliningPolicy.class) {
            return InliningPolicy.Default;
        }
        try {
            return policyClass.getConstructor().newInstance();
        } catch (Exception e) {
            throw new GraalInternalError(e);
        }
    }

    public StructuredGraph makeGraph(final ResolvedJavaMethod method, final InliningPolicy policy) {
        StructuredGraph graph = parseGraph(method, policy);

        Debug.dump(graph, "%s: Final", method.name());

        return graph;
    }

    private StructuredGraph parseGraph(final ResolvedJavaMethod method, final InliningPolicy policy) {
        StructuredGraph graph = graphCache.get(method);
        if (graph == null) {
            graph = buildGraph(method, policy == null ? inliningPolicy(method) : policy);
            //System.out.println("built " + graph);
            graphCache.put(method, graph);
        }
        return graph;
    }

    private StructuredGraph buildGraph(final ResolvedJavaMethod method, final InliningPolicy policy) {
        final StructuredGraph graph = new StructuredGraph(method);
        return Debug.scope("BuildSnippetGraph", new Object[] {method, graph}, new Callable<StructuredGraph>() {
            @Override
            public StructuredGraph call() throws Exception {
                GraphBuilderConfiguration config = GraphBuilderConfiguration.getSnippetDefault();
                GraphBuilderPhase graphBuilder = new GraphBuilderPhase(runtime, config, OptimisticOptimizations.NONE);
                graphBuilder.apply(graph);

                Debug.dump(graph, "%s: %s", method.name(), GraphBuilderPhase.class.getSimpleName());

                new SnippetVerificationPhase().apply(graph);

                new SnippetIntrinsificationPhase(runtime, pool).apply(graph);

                for (Invoke invoke : graph.getInvokes()) {
                    MethodCallTargetNode callTarget = invoke.callTarget();
                    ResolvedJavaMethod callee = callTarget.targetMethod();
                    if (policy.shouldInline(callee, method)) {
                        StructuredGraph targetGraph = parseGraph(callee, policy);
                        InliningUtil.inline(invoke, targetGraph, true);
                        Debug.dump(graph, "after inlining %s", callee);
                        if (GraalOptions.OptCanonicalizer) {
                            new WordTypeRewriterPhase(target.wordKind, runtime.getResolvedJavaType(target.wordKind)).apply(graph);
                            new CanonicalizerPhase(target, runtime, null).apply(graph);
                        }
                    }
                }

                new SnippetIntrinsificationPhase(runtime, pool).apply(graph);

                new WordTypeRewriterPhase(target.wordKind, runtime.getResolvedJavaType(target.wordKind)).apply(graph);

                new DeadCodeEliminationPhase().apply(graph);
                if (GraalOptions.OptCanonicalizer) {
                    new CanonicalizerPhase(target, runtime, null).apply(graph);
                }

                for (LoopEndNode end : graph.getNodes(LoopEndNode.class)) {
                    end.disableSafepoint();
                }

                new InsertStateAfterPlaceholderPhase().apply(graph);

                if (GraalOptions.ProbabilityAnalysis) {
                    new DeadCodeEliminationPhase().apply(graph);
                    new ComputeProbabilityPhase().apply(graph);
                }
                return graph;
            }
        });
    }
}
