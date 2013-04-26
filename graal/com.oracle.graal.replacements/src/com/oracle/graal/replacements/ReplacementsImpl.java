/*
 * Copyright (c) 2011, 2013, Oracle and/or its affiliates. All rights reserved.
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
import java.util.concurrent.*;

import sun.misc.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.replacements.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.java.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.java.MethodCallTargetNode.InvokeKind;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.replacements.Snippet.DefaultSnippetInliningPolicy;
import com.oracle.graal.replacements.Snippet.SnippetInliningPolicy;
import com.oracle.graal.word.phases.*;

public class ReplacementsImpl implements Replacements {

    protected final MetaAccessProvider runtime;
    protected final TargetDescription target;
    protected final Assumptions assumptions;

    /**
     * The preprocessed replacement graphs.
     */
    private final ConcurrentMap<ResolvedJavaMethod, StructuredGraph> graphs;

    // These data structures are all fully initialized during single-threaded
    // compiler startup and so do not need to be concurrent.
    private final Map<ResolvedJavaMethod, ResolvedJavaMethod> registeredMethodSubstitutions;
    private final Map<ResolvedJavaMethod, Class<? extends FixedWithNextNode>> registerMacroSubstitutions;
    private final Set<ResolvedJavaMethod> forcedSubstitutions;
    private final Map<Class<? extends SnippetTemplateCache>, SnippetTemplateCache> snippetTemplateCache;

    public ReplacementsImpl(MetaAccessProvider runtime, Assumptions assumptions, TargetDescription target) {
        this.runtime = runtime;
        this.target = target;
        this.assumptions = assumptions;
        this.graphs = new ConcurrentHashMap<>();
        this.registeredMethodSubstitutions = new HashMap<>();
        this.registerMacroSubstitutions = new HashMap<>();
        this.forcedSubstitutions = new HashSet<>();
        this.snippetTemplateCache = new HashMap<>();
    }

    public StructuredGraph getSnippet(ResolvedJavaMethod method) {
        assert method.getAnnotation(Snippet.class) != null : "Snippet must be annotated with @" + Snippet.class.getSimpleName();
        assert !Modifier.isAbstract(method.getModifiers()) && !Modifier.isNative(method.getModifiers()) : "Snippet must not be abstract or native";

        StructuredGraph graph = graphs.get(method);
        if (graph == null) {
            graphs.putIfAbsent(method, makeGraph(method, null, inliningPolicy(method)));
            graph = graphs.get(method);
        }
        return graph;
    }

    public StructuredGraph getMethodSubstitution(ResolvedJavaMethod original) {
        ResolvedJavaMethod substitute = registeredMethodSubstitutions.get(original);
        if (substitute == null) {
            return null;
        }
        StructuredGraph graph = graphs.get(substitute);
        if (graph == null) {
            graphs.putIfAbsent(substitute, makeGraph(substitute, original, inliningPolicy(substitute)));
            graph = graphs.get(substitute);
        }
        return graph;

    }

    public Class<? extends FixedWithNextNode> getMacroSubstitution(ResolvedJavaMethod method) {
        return registerMacroSubstitutions.get(method);
    }

    public Assumptions getAssumptions() {
        return assumptions;
    }

    public void registerSubstitutions(Class<?> substitutions) {
        ClassSubstitution classSubstitution = substitutions.getAnnotation(ClassSubstitution.class);
        assert classSubstitution != null;
        assert !Snippets.class.isAssignableFrom(substitutions);
        for (Method substituteMethod : substitutions.getDeclaredMethods()) {
            MethodSubstitution methodSubstitution = substituteMethod.getAnnotation(MethodSubstitution.class);
            MacroSubstitution macroSubstitution = substituteMethod.getAnnotation(MacroSubstitution.class);
            if (methodSubstitution == null && macroSubstitution == null) {
                continue;
            }

            int modifiers = substituteMethod.getModifiers();
            if (!Modifier.isStatic(modifiers)) {
                throw new RuntimeException("Substitution methods must be static: " + substituteMethod);
            }

            if (methodSubstitution != null) {
                if (Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)) {
                    throw new RuntimeException("Substitution method must not be abstract or native: " + substituteMethod);
                }
                String originalName = originalName(substituteMethod, methodSubstitution.value());
                Class[] originalParameters = originalParameters(substituteMethod, methodSubstitution.signature(), methodSubstitution.isStatic());
                Member originalMethod = originalMethod(classSubstitution, methodSubstitution.optional(), originalName, originalParameters);
                if (originalMethod != null) {
                    ResolvedJavaMethod original = registerMethodSubstitution(originalMethod, substituteMethod);
                    if (original != null && methodSubstitution.forced()) {
                        forcedSubstitutions.add(original);
                    }
                }
            }
            if (macroSubstitution != null) {
                String originalName = originalName(substituteMethod, macroSubstitution.value());
                Class[] originalParameters = originalParameters(substituteMethod, macroSubstitution.signature(), macroSubstitution.isStatic());
                Member originalMethod = originalMethod(classSubstitution, macroSubstitution.optional(), originalName, originalParameters);
                if (originalMethod != null) {
                    ResolvedJavaMethod original = registerMacroSubstitution(originalMethod, macroSubstitution.macro());
                    if (original != null && macroSubstitution.forced()) {
                        forcedSubstitutions.add(original);
                    }
                }
            }
        }
    }

    /**
     * Registers a method substitution.
     * 
     * @param originalMember a method or constructor being substituted
     * @param substituteMethod the substitute method
     * @return the original method
     */
    protected ResolvedJavaMethod registerMethodSubstitution(Member originalMember, Method substituteMethod) {
        ResolvedJavaMethod substitute = runtime.lookupJavaMethod(substituteMethod);
        ResolvedJavaMethod original;
        if (originalMember instanceof Method) {
            original = runtime.lookupJavaMethod((Method) originalMember);
        } else {
            original = runtime.lookupJavaConstructor((Constructor) originalMember);
        }
        Debug.log("substitution: " + MetaUtil.format("%H.%n(%p)", original) + " --> " + MetaUtil.format("%H.%n(%p)", substitute));

        registeredMethodSubstitutions.put(original, substitute);
        return original;
    }

    /**
     * Registers a macro substitution.
     * 
     * @param originalMethod a method or constructor being substituted
     * @param macro the substitute macro node class
     * @return the original method
     */
    protected ResolvedJavaMethod registerMacroSubstitution(Member originalMethod, Class<? extends FixedWithNextNode> macro) {
        ResolvedJavaMethod originalJavaMethod;
        if (originalMethod instanceof Method) {
            originalJavaMethod = runtime.lookupJavaMethod((Method) originalMethod);
        } else {
            originalJavaMethod = runtime.lookupJavaConstructor((Constructor) originalMethod);
        }
        registerMacroSubstitutions.put(originalJavaMethod, macro);
        return originalJavaMethod;
    }

    private SnippetInliningPolicy inliningPolicy(ResolvedJavaMethod method) {
        Class<? extends SnippetInliningPolicy> policyClass = SnippetInliningPolicy.class;
        Snippet snippet = method.getAnnotation(Snippet.class);
        if (snippet != null) {
            policyClass = snippet.inlining();
        }
        if (policyClass == SnippetInliningPolicy.class) {
            return new DefaultSnippetInliningPolicy(runtime);
        }
        try {
            return policyClass.getConstructor().newInstance();
        } catch (Exception e) {
            throw new GraalInternalError(e);
        }
    }

    /**
     * Creates a preprocessed graph for a snippet or method substitution.
     * 
     * @param method the snippet or method substitution for which a graph will be created
     * @param original the original method if {@code method} is a {@linkplain MethodSubstitution
     *            substitution} otherwise null
     * @param policy the inlining policy to use during preprocessing
     */
    public StructuredGraph makeGraph(ResolvedJavaMethod method, ResolvedJavaMethod original, SnippetInliningPolicy policy) {
        return createGraphMaker(method, original).makeGraph(policy);
    }

    /**
     * Can be overridden to return an object that specializes various parts of graph preprocessing.
     */
    protected GraphMaker createGraphMaker(ResolvedJavaMethod substitute, ResolvedJavaMethod original) {
        return new GraphMaker(substitute, original);
    }

    /**
     * Cache to speed up preprocessing of replacement graphs.
     */
    final ConcurrentMap<ResolvedJavaMethod, StructuredGraph> graphCache = new ConcurrentHashMap<>();

    /**
     * Creates and preprocesses a graph for a replacement.
     */
    protected class GraphMaker {

        /**
         * The method for which a graph is being created.
         */
        protected final ResolvedJavaMethod method;

        /**
         * The original method if {@link #method} is a {@linkplain MethodSubstitution substitution}
         * otherwise null.
         */
        protected final ResolvedJavaMethod original;

        boolean substituteCallsOriginal;

        protected GraphMaker(ResolvedJavaMethod substitute, ResolvedJavaMethod original) {
            this.method = substitute;
            this.original = original;
        }

        public StructuredGraph makeGraph(final SnippetInliningPolicy policy) {
            return Debug.scope("BuildSnippetGraph", new Object[]{method}, new Callable<StructuredGraph>() {

                @Override
                public StructuredGraph call() throws Exception {
                    StructuredGraph graph = parseGraph(method, policy);

                    // Cannot have a finalized version of a graph in the cache
                    graph = graph.copy();

                    finalizeGraph(graph);

                    Debug.dump(graph, "%s: Final", method.getName());

                    return graph;
                }
            });
        }

        /**
         * Does final processing of a snippet graph.
         */
        protected void finalizeGraph(StructuredGraph graph) {
            new NodeIntrinsificationPhase(runtime).apply(graph);
            if (!SnippetTemplate.hasConstantParameter(method)) {
                NodeIntrinsificationVerificationPhase.verify(graph);
            }
            new ConvertDeoptimizeToGuardPhase().apply(graph);

            if (original == null) {
                new SnippetFrameStateCleanupPhase().apply(graph);
                new DeadCodeEliminationPhase().apply(graph);
                new InsertStateAfterPlaceholderPhase().apply(graph);
            } else {
                new DeadCodeEliminationPhase().apply(graph);
            }
        }

        private StructuredGraph parseGraph(final ResolvedJavaMethod methodToParse, final SnippetInliningPolicy policy) {
            StructuredGraph graph = graphCache.get(methodToParse);
            if (graph == null) {
                graphCache.putIfAbsent(methodToParse, buildGraph(methodToParse, policy == null ? inliningPolicy(methodToParse) : policy));
                graph = graphCache.get(methodToParse);
                assert graph != null;
            }
            return graph;
        }

        /**
         * Builds the initial graph for a snippet.
         */
        protected StructuredGraph buildInitialGraph(final ResolvedJavaMethod methodToParse) {
            final StructuredGraph graph = new StructuredGraph(methodToParse);
            GraphBuilderConfiguration config = GraphBuilderConfiguration.getSnippetDefault();
            GraphBuilderPhase graphBuilder = new GraphBuilderPhase(runtime, config, OptimisticOptimizations.NONE);
            graphBuilder.apply(graph);

            Debug.dump(graph, "%s: %s", methodToParse.getName(), GraphBuilderPhase.class.getSimpleName());

            new WordTypeVerificationPhase(runtime, target.wordKind).apply(graph);

            return graph;
        }

        /**
         * Called after a graph is inlined.
         * 
         * @param caller the graph into which {@code callee} was inlined
         * @param callee the graph that was inlined into {@code caller}
         */
        protected void afterInline(StructuredGraph caller, StructuredGraph callee) {
            if (GraalOptions.OptCanonicalizer) {
                new WordTypeRewriterPhase(runtime, target.wordKind).apply(caller);
                new CanonicalizerPhase.Instance(runtime, assumptions).apply(caller);
            }
        }

        /**
         * Called after all inlining for a given graph is complete.
         */
        protected void afterInlining(StructuredGraph graph) {
            new NodeIntrinsificationPhase(runtime).apply(graph);

            new WordTypeRewriterPhase(runtime, target.wordKind).apply(graph);

            new DeadCodeEliminationPhase().apply(graph);
            if (GraalOptions.OptCanonicalizer) {
                new CanonicalizerPhase.Instance(runtime, assumptions).apply(graph);
            }
        }

        private StructuredGraph buildGraph(final ResolvedJavaMethod methodToParse, final SnippetInliningPolicy policy) {
            assert !Modifier.isAbstract(methodToParse.getModifiers()) && !Modifier.isNative(methodToParse.getModifiers()) : methodToParse;
            final StructuredGraph graph = buildInitialGraph(methodToParse);

            for (MethodCallTargetNode callTarget : graph.getNodes(MethodCallTargetNode.class)) {
                ResolvedJavaMethod callee = callTarget.targetMethod();
                if (callee == method) {
                    final StructuredGraph originalGraph = new StructuredGraph(original);
                    new GraphBuilderPhase(runtime, GraphBuilderConfiguration.getSnippetDefault(), OptimisticOptimizations.NONE).apply(originalGraph);
                    InliningUtil.inline(callTarget.invoke(), originalGraph, true);

                    Debug.dump(graph, "after inlining %s", callee);
                    afterInline(graph, originalGraph);
                    substituteCallsOriginal = true;
                } else {
                    if ((callTarget.invokeKind() == InvokeKind.Static || callTarget.invokeKind() == InvokeKind.Special) && policy.shouldInline(callee, methodToParse)) {
                        StructuredGraph targetGraph;
                        StructuredGraph intrinsicGraph = InliningUtil.getIntrinsicGraph(ReplacementsImpl.this, callee);
                        if (intrinsicGraph != null && policy.shouldUseReplacement(callee, methodToParse)) {
                            targetGraph = intrinsicGraph;
                        } else {
                            targetGraph = parseGraph(callee, policy);
                        }
                        InliningUtil.inline(callTarget.invoke(), targetGraph, true);
                        Debug.dump(graph, "after inlining %s", callee);
                        afterInline(graph, targetGraph);
                    }
                }
            }

            afterInlining(graph);

            for (LoopEndNode end : graph.getNodes(LoopEndNode.class)) {
                end.disableSafepoint();
            }

            new DeadCodeEliminationPhase().apply(graph);
            return graph;
        }
    }

    private static String originalName(Method substituteMethod, String methodSubstitution) {
        if (methodSubstitution.isEmpty()) {
            return substituteMethod.getName();
        } else {
            return methodSubstitution;
        }
    }

    /**
     * Resolves a name to a class.
     * 
     * @param className the name of the class to resolve
     * @param optional if true, resolution failure returns null
     * @return the resolved class or null if resolution fails and {@code optional} is true
     */
    static Class resolveType(String className, boolean optional) {
        try {
            // Need to use launcher class path to handle classes
            // that are not on the boot class path
            ClassLoader cl = Launcher.getLauncher().getClassLoader();
            return Class.forName(className, false, cl);
        } catch (ClassNotFoundException e) {
            if (optional) {
                return null;
            }
            throw new GraalInternalError("Could not resolve type " + className);
        }
    }

    private static Class resolveType(JavaType type) {
        JavaType base = type;
        int dimensions = 0;
        while (base.getComponentType() != null) {
            base = base.getComponentType();
            dimensions++;
        }

        Class baseClass = base.getKind() != Kind.Object ? base.getKind().toJavaClass() : resolveType(toJavaName(base), false);
        return dimensions == 0 ? baseClass : Array.newInstance(baseClass, new int[dimensions]).getClass();
    }

    private Class[] originalParameters(Method substituteMethod, String methodSubstitution, boolean isStatic) {
        Class[] parameters;
        if (methodSubstitution.isEmpty()) {
            parameters = substituteMethod.getParameterTypes();
            if (!isStatic) {
                assert parameters.length > 0 : "must be a static method with the 'this' object as its first parameter";
                parameters = Arrays.copyOfRange(parameters, 1, parameters.length);
            }
        } else {
            Signature signature = runtime.parseMethodDescriptor(methodSubstitution);
            parameters = new Class[signature.getParameterCount(false)];
            for (int i = 0; i < parameters.length; i++) {
                parameters[i] = resolveType(signature.getParameterType(i, null));
            }
        }
        return parameters;
    }

    private static Member originalMethod(ClassSubstitution classSubstitution, boolean optional, String name, Class[] parameters) {
        Class<?> originalClass = classSubstitution.value();
        if (originalClass == ClassSubstitution.class) {
            originalClass = resolveType(classSubstitution.className(), classSubstitution.optional());
            if (originalClass == null) {
                // optional class was not found
                return null;
            }
        }
        try {
            if (name.equals("<init>")) {
                return originalClass.getDeclaredConstructor(parameters);
            } else {
                return originalClass.getDeclaredMethod(name, parameters);
            }
        } catch (NoSuchMethodException | SecurityException e) {
            if (optional) {
                return null;
            }
            throw new GraalInternalError(e);
        }
    }

    @Override
    public Collection<ResolvedJavaMethod> getAllReplacements() {
        HashSet<ResolvedJavaMethod> result = new HashSet<>();
        result.addAll(registeredMethodSubstitutions.keySet());
        result.addAll(registerMacroSubstitutions.keySet());
        return result;
    }

    @Override
    public boolean isForcedSubstitution(ResolvedJavaMethod method) {
        return forcedSubstitutions.contains(method);
    }

    @Override
    public void registerSnippetTemplateCache(SnippetTemplateCache templates) {
        assert snippetTemplateCache.get(templates.getClass()) == null;
        snippetTemplateCache.put(templates.getClass(), templates);
    }

    @Override
    public <T extends SnippetTemplateCache> T getSnippetTemplateCache(Class<T> templatesClass) {
        SnippetTemplateCache ret = snippetTemplateCache.get(templatesClass);
        return templatesClass.cast(ret);
    }
}
