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
import static com.oracle.graal.compiler.GraalCompiler.*;
import static com.oracle.graal.compiler.common.GraalOptions.*;
import static com.oracle.graal.phases.common.DeadCodeEliminationPhase.Optionality.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import sun.misc.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.replacements.*;
import com.oracle.graal.compiler.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.graph.Graph.Mark;
import com.oracle.graal.graph.*;
import com.oracle.graal.java.*;
import com.oracle.graal.java.GraphBuilderConfiguration.Plugins;
import com.oracle.graal.java.GraphBuilderPhase.Instance;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.StructuredGraph.AllowAssumptions;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.phases.common.inlining.*;
import com.oracle.graal.phases.tiers.*;
import com.oracle.graal.phases.util.*;
import com.oracle.graal.replacements.Snippet.DefaultSnippetInliningPolicy;
import com.oracle.graal.replacements.Snippet.SnippetInliningPolicy;

public class ReplacementsImpl implements Replacements {

    public final Providers providers;
    public final SnippetReflectionProvider snippetReflection;
    public final TargetDescription target;
    public final NodeIntrinsificationPhase nodeIntrinsificationPhase;
    private GraphBuilderConfiguration.Plugins graphBuilderPlugins;

    /**
     * The preprocessed replacement graphs.
     */
    protected final ConcurrentMap<ResolvedJavaMethod, StructuredGraph> graphs;

    public void completeInitialization(GraphBuilderConfiguration.Plugins plugins) {
        this.graphBuilderPlugins = plugins;
    }

    /**
     * Encapsulates method and macro substitutions for a single class.
     */
    protected class ClassReplacements {
        public final Map<ResolvedJavaMethod, ResolvedJavaMethod> methodSubstitutions = CollectionsFactory.newMap();
        public final Map<ResolvedJavaMethod, Class<? extends FixedWithNextNode>> macroSubstitutions = CollectionsFactory.newMap();
        public final Set<ResolvedJavaMethod> forcedSubstitutions = new HashSet<>();

        public ClassReplacements(Class<?>[] substitutionClasses, AtomicReference<ClassReplacements> ref) {
            for (Class<?> substitutionClass : substitutionClasses) {
                ClassSubstitution classSubstitution = substitutionClass.getAnnotation(ClassSubstitution.class);
                assert !Snippets.class.isAssignableFrom(substitutionClass);
                SubstitutionGuard defaultGuard = getGuard(classSubstitution.defaultGuard());
                for (Method substituteMethod : substitutionClass.getDeclaredMethods()) {
                    if (ref.get() != null) {
                        // Bail if another thread beat us creating the substitutions
                        return;
                    }
                    MethodSubstitution methodSubstitution = substituteMethod.getAnnotation(MethodSubstitution.class);
                    MacroSubstitution macroSubstitution = substituteMethod.getAnnotation(MacroSubstitution.class);
                    if (methodSubstitution == null && macroSubstitution == null) {
                        continue;
                    }

                    int modifiers = substituteMethod.getModifiers();
                    if (!Modifier.isStatic(modifiers)) {
                        throw new GraalInternalError("Substitution methods must be static: " + substituteMethod);
                    }

                    if (methodSubstitution != null) {
                        SubstitutionGuard guard = getGuard(methodSubstitution.guard());
                        if (guard == null) {
                            guard = defaultGuard;
                        }

                        if (macroSubstitution != null && macroSubstitution.isStatic() != methodSubstitution.isStatic()) {
                            throw new GraalInternalError("Macro and method substitution must agree on isStatic attribute: " + substituteMethod);
                        }
                        if (Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)) {
                            throw new GraalInternalError("Substitution method must not be abstract or native: " + substituteMethod);
                        }
                        String originalName = originalName(substituteMethod, methodSubstitution.value());
                        JavaSignature originalSignature = originalSignature(substituteMethod, methodSubstitution.signature(), methodSubstitution.isStatic());
                        Executable[] originalMethods = originalMethods(classSubstitution, classSubstitution.optional(), originalName, originalSignature);
                        if (originalMethods != null) {
                            for (Executable originalMethod : originalMethods) {
                                if (originalMethod != null && (guard == null || guard.execute())) {
                                    ResolvedJavaMethod original = registerMethodSubstitution(this, originalMethod, substituteMethod);
                                    if (original != null && methodSubstitution.forced() && shouldIntrinsify(original)) {
                                        forcedSubstitutions.add(original);
                                    }
                                }
                            }
                        }
                    }
                    // We don't have per method guards for macro substitutions but at
                    // least respect the defaultGuard if there is one.
                    if (macroSubstitution != null && (defaultGuard == null || defaultGuard.execute())) {
                        String originalName = originalName(substituteMethod, macroSubstitution.value());
                        JavaSignature originalSignature = originalSignature(substituteMethod, macroSubstitution.signature(), macroSubstitution.isStatic());
                        Executable[] originalMethods = originalMethods(classSubstitution, macroSubstitution.optional(), originalName, originalSignature);
                        for (Executable originalMethod : originalMethods) {
                            if (originalMethod != null) {
                                ResolvedJavaMethod original = registerMacroSubstitution(this, originalMethod, macroSubstitution.macro());
                                if (original != null && macroSubstitution.forced() && shouldIntrinsify(original)) {
                                    forcedSubstitutions.add(original);
                                }
                            }
                        }
                    }
                }
            }
        }

        private JavaSignature originalSignature(Method substituteMethod, String methodSubstitution, boolean isStatic) {
            Class<?>[] parameters;
            Class<?> returnType;
            if (methodSubstitution.isEmpty()) {
                parameters = substituteMethod.getParameterTypes();
                if (!isStatic) {
                    assert parameters.length > 0 : "must be a static method with the 'this' object as its first parameter";
                    parameters = Arrays.copyOfRange(parameters, 1, parameters.length);
                }
                returnType = substituteMethod.getReturnType();
            } else {
                Signature signature = providers.getMetaAccess().parseMethodDescriptor(methodSubstitution);
                parameters = new Class[signature.getParameterCount(false)];
                for (int i = 0; i < parameters.length; i++) {
                    parameters[i] = resolveClass(signature.getParameterType(i, null));
                }
                returnType = resolveClass(signature.getReturnType(null));
            }
            return new JavaSignature(returnType, parameters);
        }

        private Executable[] originalMethods(ClassSubstitution classSubstitution, boolean optional, String name, JavaSignature signature) {
            Class<?> originalClass = classSubstitution.value();
            if (originalClass == ClassSubstitution.class) {
                ArrayList<Executable> result = new ArrayList<>();
                for (String className : classSubstitution.className()) {
                    originalClass = resolveClass(className, classSubstitution.optional());
                    if (originalClass != null) {
                        result.add(lookupOriginalMethod(originalClass, name, signature, optional));
                    }
                }
                if (result.size() == 0) {
                    // optional class was not found
                    return null;
                }
                return result.toArray(new Executable[result.size()]);
            }
            Executable original = lookupOriginalMethod(originalClass, name, signature, optional);
            if (original != null) {
                return new Executable[]{original};
            }
            return null;
        }

        private Executable lookupOriginalMethod(Class<?> originalClass, String name, JavaSignature signature, boolean optional) throws GraalInternalError {
            try {
                if (name.equals("<init>")) {
                    assert signature.returnType.equals(void.class) : signature;
                    Constructor<?> original = originalClass.getDeclaredConstructor(signature.parameters);
                    return original;
                } else {
                    Method original = originalClass.getDeclaredMethod(name, signature.parameters);
                    if (!original.getReturnType().equals(signature.returnType)) {
                        throw new NoSuchMethodException(originalClass.getName() + "." + name + signature);
                    }
                    return original;
                }
            } catch (NoSuchMethodException | SecurityException e) {
                if (optional) {
                    return null;
                }
                throw new GraalInternalError(e);
            }
        }
    }

    /**
     * Per-class replacements. The entries in these maps are all fully initialized during
     * single-threaded compiler startup and so do not need to be concurrent.
     */
    private final Map<String, AtomicReference<ClassReplacements>> classReplacements;
    private final Map<String, Class<?>[]> internalNameToSubstitutionClasses;

    // This map is key'ed by a class name instead of a Class object so that
    // it is stable across VM executions (in support of replay compilation).
    private final Map<String, SnippetTemplateCache> snippetTemplateCache;

    public ReplacementsImpl(Providers providers, SnippetReflectionProvider snippetReflection, TargetDescription target) {
        this.providers = providers.copyWith(this);
        this.classReplacements = CollectionsFactory.newMap();
        this.internalNameToSubstitutionClasses = CollectionsFactory.newMap();
        this.snippetReflection = snippetReflection;
        this.target = target;
        this.graphs = new ConcurrentHashMap<>();
        this.snippetTemplateCache = CollectionsFactory.newMap();
        this.nodeIntrinsificationPhase = createNodeIntrinsificationPhase();
    }

    private static final boolean UseSnippetGraphCache = Boolean.parseBoolean(System.getProperty("graal.useSnippetGraphCache", "true"));
    private static final DebugTimer SnippetPreparationTime = Debug.timer("SnippetPreparationTime");

    /**
     * Gets the method and macro replacements for a given class. This method will parse the
     * replacements in the substitution classes associated with {@code internalName} the first time
     * this method is called for {@code internalName}.
     */
    protected ClassReplacements getClassReplacements(String internalName) {
        Class<?>[] substitutionClasses = internalNameToSubstitutionClasses.get(internalName);
        if (substitutionClasses != null) {
            AtomicReference<ClassReplacements> crRef = classReplacements.get(internalName);
            if (crRef.get() == null) {
                crRef.compareAndSet(null, new ClassReplacements(substitutionClasses, crRef));
            }
            return crRef.get();
        }
        return null;
    }

    public StructuredGraph getSnippet(ResolvedJavaMethod method, Object[] args) {
        return getSnippet(method, null, args);
    }

    @Override
    public StructuredGraph getSnippet(ResolvedJavaMethod method, ResolvedJavaMethod recursiveEntry, Object[] args) {
        assert method.getAnnotation(Snippet.class) != null : "Snippet must be annotated with @" + Snippet.class.getSimpleName();
        assert method.hasBytecodes() : "Snippet must not be abstract or native";

        StructuredGraph graph = UseSnippetGraphCache ? graphs.get(method) : null;
        if (graph == null) {
            try (DebugCloseable a = SnippetPreparationTime.start()) {
                FrameStateProcessing frameStateProcessing = method.getAnnotation(Snippet.class).removeAllFrameStates() ? FrameStateProcessing.Removal
                                : FrameStateProcessing.CollapseFrameForSingleSideEffect;
                StructuredGraph newGraph = makeGraph(method, args, recursiveEntry, inliningPolicy(method), frameStateProcessing);
                Debug.metric("SnippetNodeCount[%#s]", method).add(newGraph.getNodeCount());
                if (!UseSnippetGraphCache || args != null) {
                    return newGraph;
                }
                graphs.putIfAbsent(method, newGraph);
                graph = graphs.get(method);
            }
        }
        return graph;
    }

    @Override
    public void registerSnippet(ResolvedJavaMethod method) {
        // No initialization needed as snippet graphs are created on demand in getSnippet
    }

    @Override
    public void notifyAfterConstantsBound(StructuredGraph specializedSnippet) {

        // Do deferred intrinsification of node intrinsics

        nodeIntrinsificationPhase.apply(specializedSnippet);
        new CanonicalizerPhase().apply(specializedSnippet, new PhaseContext(providers));
        NodeIntrinsificationVerificationPhase.verify(specializedSnippet);
    }

    protected NodeIntrinsificationPhase createNodeIntrinsificationPhase() {
        return new NodeIntrinsificationPhase(providers, snippetReflection);
    }

    @Override
    public StructuredGraph getMethodSubstitution(ResolvedJavaMethod original) {
        ClassReplacements cr = getClassReplacements(original.getDeclaringClass().getName());
        ResolvedJavaMethod substitute = cr == null ? null : cr.methodSubstitutions.get(original);
        if (substitute == null) {
            return null;
        }
        StructuredGraph graph = graphs.get(substitute);
        if (graph == null) {
            graph = makeGraph(substitute, null, original, inliningPolicy(substitute), FrameStateProcessing.None);
            graph.freeze();
            graphs.putIfAbsent(substitute, graph);
            graph = graphs.get(substitute);
        }
        assert graph.isFrozen();
        return graph;

    }

    public Class<? extends FixedWithNextNode> getMacroSubstitution(ResolvedJavaMethod method) {
        ClassReplacements cr = getClassReplacements(method.getDeclaringClass().getName());
        return cr == null ? null : cr.macroSubstitutions.get(method);
    }

    private SubstitutionGuard getGuard(Class<? extends SubstitutionGuard> guardClass) {
        if (guardClass != SubstitutionGuard.class) {
            Constructor<?>[] constructors = guardClass.getConstructors();
            if (constructors.length != 1) {
                throw new GraalInternalError("Substitution guard " + guardClass.getSimpleName() + " must have a single public constructor");
            }
            Constructor<?> constructor = constructors[0];
            Class<?>[] paramTypes = constructor.getParameterTypes();
            // Check for supported constructor signatures
            try {
                Object[] args = new Object[constructor.getParameterCount()];
                for (int i = 0; i < args.length; i++) {
                    Object arg = snippetReflection.getSubstitutionGuardParameter(paramTypes[i]);
                    if (arg != null) {
                        args[i] = arg;
                    } else if (paramTypes[i].isInstance(target.arch)) {
                        args[i] = target.arch;
                    } else {
                        throw new GraalInternalError("Unsupported type %s in substitution guard constructor: %s", paramTypes[i].getName(), constructor);
                    }
                }

                return (SubstitutionGuard) constructor.newInstance(args);
            } catch (Exception e) {
                throw new GraalInternalError(e);
            }
        }
        return null;
    }

    private static boolean checkSubstitutionInternalName(Class<?> substitutions, String internalName) {
        ClassSubstitution cs = substitutions.getAnnotation(ClassSubstitution.class);
        assert cs != null : substitutions + " must be annotated by " + ClassSubstitution.class.getSimpleName();
        if (cs.value() == ClassSubstitution.class) {
            for (String className : cs.className()) {
                if (toInternalName(className).equals(internalName)) {
                    return true;
                }
            }
            assert false : internalName + " not found in " + Arrays.toString(cs.className());
        } else {
            String originalInternalName = toInternalName(cs.value().getName());
            assert originalInternalName.equals(internalName) : originalInternalName + " != " + internalName;
        }
        return true;
    }

    public void registerSubstitutions(Type original, Class<?> substitutionClass) {
        String internalName = toInternalName(original.getTypeName());
        assert checkSubstitutionInternalName(substitutionClass, internalName);
        Class<?>[] classes = internalNameToSubstitutionClasses.get(internalName);
        if (classes == null) {
            classes = new Class<?>[]{substitutionClass};
        } else {
            assert !Arrays.asList(classes).contains(substitutionClass);
            classes = Arrays.copyOf(classes, classes.length + 1);
            classes[classes.length - 1] = substitutionClass;
        }
        internalNameToSubstitutionClasses.put(internalName, classes);
        AtomicReference<ClassReplacements> existing = classReplacements.put(internalName, new AtomicReference<>());
        assert existing == null || existing.get() == null;
    }

    /**
     * Registers a method substitution.
     *
     * @param originalMember a method or constructor being substituted
     * @param substituteMethod the substitute method
     * @return the original method
     */
    protected ResolvedJavaMethod registerMethodSubstitution(ClassReplacements cr, Executable originalMember, Method substituteMethod) {
        MetaAccessProvider metaAccess = providers.getMetaAccess();
        ResolvedJavaMethod substitute = metaAccess.lookupJavaMethod(substituteMethod);
        ResolvedJavaMethod original = metaAccess.lookupJavaMethod(originalMember);
        if (Debug.isLogEnabled()) {
            Debug.log("substitution: %s --> %s", original.format("%H.%n(%p) %r"), substitute.format("%H.%n(%p) %r"));
        }

        cr.methodSubstitutions.put(original, substitute);
        return original;
    }

    /**
     * Registers a macro substitution.
     *
     * @param originalMethod a method or constructor being substituted
     * @param macro the substitute macro node class
     * @return the original method
     */
    protected ResolvedJavaMethod registerMacroSubstitution(ClassReplacements cr, Executable originalMethod, Class<? extends FixedWithNextNode> macro) {
        MetaAccessProvider metaAccess = providers.getMetaAccess();
        ResolvedJavaMethod originalJavaMethod = metaAccess.lookupJavaMethod(originalMethod);
        cr.macroSubstitutions.put(originalJavaMethod, macro);
        return originalJavaMethod;
    }

    private static SnippetInliningPolicy createPolicyClassInstance(Class<? extends SnippetInliningPolicy> policyClass) {
        try {
            return policyClass.getConstructor().newInstance();
        } catch (Exception e) {
            throw new GraalInternalError(e);
        }
    }

    public SnippetInliningPolicy inliningPolicy(ResolvedJavaMethod method) {
        Class<? extends SnippetInliningPolicy> policyClass = SnippetInliningPolicy.class;
        Snippet snippet = method.getAnnotation(Snippet.class);
        if (snippet != null) {
            policyClass = snippet.inlining();
        }
        if (policyClass == SnippetInliningPolicy.class) {
            return new DefaultSnippetInliningPolicy(providers.getMetaAccess());
        }
        return createPolicyClassInstance(policyClass);
    }

    /**
     * Creates a preprocessed graph for a snippet or method substitution.
     *
     * @param method the snippet or method substitution for which a graph will be created
     * @param args
     * @param original the original method if {@code method} is a {@linkplain MethodSubstitution
     *            substitution} otherwise null
     * @param policy the inlining policy to use during preprocessing
     * @param frameStateProcessing controls how {@link FrameState FrameStates} should be handled.
     */
    public StructuredGraph makeGraph(ResolvedJavaMethod method, Object[] args, ResolvedJavaMethod original, SnippetInliningPolicy policy, FrameStateProcessing frameStateProcessing) {
        return createGraphMaker(method, original, frameStateProcessing).makeGraph(args, policy);
    }

    /**
     * Can be overridden to return an object that specializes various parts of graph preprocessing.
     */
    protected GraphMaker createGraphMaker(ResolvedJavaMethod substitute, ResolvedJavaMethod original, FrameStateProcessing frameStateProcessing) {
        return new GraphMaker(this, substitute, original, frameStateProcessing);
    }

    /**
     * Cache to speed up preprocessing of replacement graphs.
     */
    final ConcurrentMap<ResolvedJavaMethod, StructuredGraph> graphCache = new ConcurrentHashMap<>();

    public enum FrameStateProcessing {
        None,
        /**
         * @see CollapseFrameForSingleSideEffectPhase
         */
        CollapseFrameForSingleSideEffect,
        /**
         * Removes frame states from all nodes in the graph.
         */
        Removal
    }

    /**
     * Calls in snippets to methods matching one of these filters are elided. Only void methods are
     * considered for elision.
     */
    private static final MethodFilter[] MethodsElidedInSnippets = getMethodsElidedInSnippets();

    private static MethodFilter[] getMethodsElidedInSnippets() {
        String commaSeparatedPatterns = System.getProperty("graal.MethodsElidedInSnippets");
        if (commaSeparatedPatterns != null) {
            return MethodFilter.parse(commaSeparatedPatterns);
        }
        return null;
    }

    /**
     * Creates and preprocesses a graph for a replacement.
     */
    public static class GraphMaker {
        /** The replacements object that the graphs are created for. */
        protected final ReplacementsImpl replacements;

        /**
         * The method for which a graph is being created.
         */
        protected final ResolvedJavaMethod method;

        /**
         * The original method which {@link #method} is substituting. Calls to {@link #method} or
         * {@link #substitutedMethod} will be replaced with a forced inline of
         * {@link #substitutedMethod}.
         */
        protected final ResolvedJavaMethod substitutedMethod;

        /**
         * Controls how FrameStates are processed.
         */
        private FrameStateProcessing frameStateProcessing;

        protected GraphMaker(ReplacementsImpl replacements, ResolvedJavaMethod substitute, ResolvedJavaMethod substitutedMethod, FrameStateProcessing frameStateProcessing) {
            this.replacements = replacements;
            this.method = substitute;
            this.substitutedMethod = substitutedMethod;
            this.frameStateProcessing = frameStateProcessing;
        }

        public StructuredGraph makeGraph(Object[] args, final SnippetInliningPolicy policy) {
            try (Scope s = Debug.scope("BuildSnippetGraph", method)) {
                StructuredGraph graph = parseGraph(method, args, policy, 0);

                if (args == null) {
                    // Cannot have a finalized version of a graph in the cache
                    graph = graph.copy();
                }

                finalizeGraph(graph);

                Debug.dump(graph, "%s: Final", method.getName());

                return graph;
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
        }

        /**
         * Does final processing of a snippet graph.
         */
        protected void finalizeGraph(StructuredGraph graph) {
            replacements.nodeIntrinsificationPhase.apply(graph);
            if (!SnippetTemplate.hasConstantParameter(method)) {
                NodeIntrinsificationVerificationPhase.verify(graph);
            }
            int sideEffectCount = 0;
            assert (sideEffectCount = graph.getNodes().filter(e -> hasSideEffect(e)).count()) >= 0;
            new ConvertDeoptimizeToGuardPhase().apply(graph, null);
            assert sideEffectCount == graph.getNodes().filter(e -> hasSideEffect(e)).count() : "deleted side effecting node";

            switch (frameStateProcessing) {
                case Removal:
                    for (Node node : graph.getNodes()) {
                        if (node instanceof StateSplit) {
                            ((StateSplit) node).setStateAfter(null);
                        }
                    }
                    break;
                case CollapseFrameForSingleSideEffect:
                    new CollapseFrameForSingleSideEffectPhase().apply(graph);
                    break;
            }
            new DeadCodeEliminationPhase(Required).apply(graph);
        }

        /**
         * Filter nodes which have side effects and shouldn't be deleted from snippets when
         * converting deoptimizations to guards. Currently this only allows exception constructors
         * to be eliminated to cover the case when Java assertions are in the inlined code.
         *
         * @param node
         * @return true for nodes that have side effects and are unsafe to delete
         */
        private boolean hasSideEffect(Node node) {
            if (node instanceof StateSplit) {
                if (((StateSplit) node).hasSideEffect()) {
                    if (node instanceof Invoke) {
                        CallTargetNode callTarget = ((Invoke) node).callTarget();
                        if (callTarget instanceof MethodCallTargetNode) {
                            ResolvedJavaMethod targetMethod = ((MethodCallTargetNode) callTarget).targetMethod();
                            if (targetMethod.isConstructor()) {
                                ResolvedJavaType throwableType = replacements.providers.getMetaAccess().lookupJavaType(Throwable.class);
                                return !throwableType.isAssignableFrom(targetMethod.getDeclaringClass());
                            }
                        }
                    }
                    // Not an exception constructor call
                    return true;
                }
            }
            // Not a StateSplit
            return false;
        }

        private static final int MAX_GRAPH_INLINING_DEPTH = 100; // more than enough

        private StructuredGraph parseGraph(final ResolvedJavaMethod methodToParse, Object[] args, final SnippetInliningPolicy policy, int inliningDepth) {
            StructuredGraph graph = args == null ? replacements.graphCache.get(methodToParse) : null;
            if (graph == null) {
                StructuredGraph newGraph = null;
                try (Scope s = Debug.scope("ParseGraph", methodToParse)) {
                    newGraph = buildGraph(methodToParse, args, policy == null ? replacements.inliningPolicy(methodToParse) : policy, inliningDepth);
                } catch (Throwable e) {
                    throw Debug.handle(e);
                }
                if (args == null) {
                    replacements.graphCache.putIfAbsent(methodToParse, newGraph);
                    graph = replacements.graphCache.get(methodToParse);
                } else {
                    graph = newGraph;
                }
                assert graph != null;
            }
            return graph;
        }

        /**
         * Builds the initial graph for a snippet.
         */
        protected StructuredGraph buildInitialGraph(final ResolvedJavaMethod methodToParse, Object[] args) {
            // Replacements cannot have optimistic assumptions since they have
            // to be valid for the entire run of the VM.
            final StructuredGraph graph = new StructuredGraph(methodToParse, AllowAssumptions.NO);

            // They will also never be never be evolved or have breakpoints set in them
            graph.disableInlinedMethodRecording();

            try (Scope s = Debug.scope("buildInitialGraph", graph)) {
                MetaAccessProvider metaAccess = replacements.providers.getMetaAccess();

                if (MethodsElidedInSnippets != null && methodToParse.getSignature().getReturnKind() == Kind.Void && MethodFilter.matches(MethodsElidedInSnippets, methodToParse)) {
                    graph.addAfterFixed(graph.start(), graph.add(new ReturnNode(null)));
                } else {
                    GraphBuilderConfiguration config = GraphBuilderConfiguration.getSnippetDefault();
                    Plugins plugins = config.getPlugins().updateFrom(replacements.graphBuilderPlugins, false);
                    plugins.getInvocationPlugins().setDefaults(replacements.graphBuilderPlugins.getInvocationPlugins());
                    if (args != null) {
                        plugins.setParameterPlugin(new ConstantBindingParameterPlugin(args, plugins.getParameterPlugin(), metaAccess, replacements.snippetReflection));
                    }
                    createGraphBuilder(metaAccess, replacements.providers.getStampProvider(), replacements.providers.getConstantReflection(), config, OptimisticOptimizations.NONE).apply(graph);
                }
                afterParsing(graph);

                if (OptCanonicalizer.getValue()) {
                    new CanonicalizerPhase().apply(graph, new PhaseContext(replacements.providers));
                }
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
            return graph;
        }

        protected Instance createGraphBuilder(MetaAccessProvider metaAccess, StampProvider stampProvider, ConstantReflectionProvider constantReflection, GraphBuilderConfiguration graphBuilderConfig,
                        OptimisticOptimizations optimisticOpts) {
            ResolvedJavaMethod rootMethodIsReplacement = substitutedMethod == null ? method : substitutedMethod;
            return new GraphBuilderPhase.Instance(metaAccess, stampProvider, constantReflection, graphBuilderConfig, optimisticOpts, rootMethodIsReplacement);
        }

        /**
         * @param graph
         */
        protected void afterParsing(StructuredGraph graph) {
        }

        protected Object beforeInline(@SuppressWarnings("unused") MethodCallTargetNode callTarget, @SuppressWarnings("unused") StructuredGraph callee) {
            return null;
        }

        /**
         * Called after a graph is inlined.
         *
         * @param caller the graph into which {@code callee} was inlined
         * @param callee the graph that was inlined into {@code caller}
         * @param beforeInlineData value returned by {@link #beforeInline}.
         */
        protected void afterInline(StructuredGraph caller, StructuredGraph callee, Object beforeInlineData) {
            if (OptCanonicalizer.getValue()) {
                new CanonicalizerPhase().apply(caller, new PhaseContext(replacements.providers));
            }
        }

        /**
         * Called after all inlining for a given graph is complete.
         */
        protected void afterInlining(StructuredGraph graph) {
            replacements.nodeIntrinsificationPhase.apply(graph);
            new DeadCodeEliminationPhase(Optional).apply(graph);
            if (OptCanonicalizer.getValue()) {
                new CanonicalizerPhase().apply(graph, new PhaseContext(replacements.providers));
            }
        }

        private StructuredGraph buildGraph(final ResolvedJavaMethod methodToParse, Object[] args, final SnippetInliningPolicy policy, int inliningDepth) {
            assert inliningDepth < MAX_GRAPH_INLINING_DEPTH : "inlining limit exceeded";
            assert methodToParse.hasBytecodes() : methodToParse;
            final StructuredGraph graph = buildInitialGraph(methodToParse, args);
            try (Scope s = Debug.scope("buildGraph", graph)) {
                Set<MethodCallTargetNode> doNotInline = null;
                for (MethodCallTargetNode callTarget : graph.getNodes(MethodCallTargetNode.TYPE)) {
                    if (doNotInline != null && doNotInline.contains(callTarget)) {
                        continue;
                    }
                    ResolvedJavaMethod callee = callTarget.targetMethod();
                    if (substitutedMethod != null && (callee.equals(method) || callee.equals(substitutedMethod))) {
                        /*
                         * Ensure that calls to the original method inside of a substitution ends up
                         * calling it instead of the Graal substitution.
                         */
                        if (substitutedMethod.hasBytecodes()) {
                            final StructuredGraph originalGraph = buildInitialGraph(substitutedMethod, null);
                            Mark mark = graph.getMark();
                            InliningUtil.inline(callTarget.invoke(), originalGraph, true, null);
                            for (MethodCallTargetNode inlinedCallTarget : graph.getNewNodes(mark).filter(MethodCallTargetNode.class)) {
                                if (doNotInline == null) {
                                    doNotInline = new HashSet<>();
                                }
                                // We do not want to do further inlining (now) for calls
                                // in the original method as this can cause unlimited
                                // recursive inlining given an eager inlining policy such
                                // as DefaultSnippetInliningPolicy.
                                doNotInline.add(inlinedCallTarget);
                            }
                            Debug.dump(graph, "after inlining %s", callee);
                            afterInline(graph, originalGraph, null);
                        }
                    } else {
                        Class<? extends FixedWithNextNode> macroNodeClass = InliningUtil.getMacroNodeClass(replacements, callee);
                        if (macroNodeClass != null) {
                            InliningUtil.inlineMacroNode(callTarget.invoke(), callee, macroNodeClass);
                        } else {
                            StructuredGraph intrinsicGraph = InliningUtil.getIntrinsicGraph(replacements, callee);
                            if (callTarget.invokeKind().isDirect() && (policy.shouldInline(callee, methodToParse) || (intrinsicGraph != null && policy.shouldUseReplacement(callee, methodToParse)))) {
                                StructuredGraph targetGraph;
                                if (intrinsicGraph != null && policy.shouldUseReplacement(callee, methodToParse)) {
                                    targetGraph = intrinsicGraph;
                                } else {
                                    if (callee.getName().startsWith("$jacoco")) {
                                        throw new GraalInternalError("Parsing call to JaCoCo instrumentation method " + callee.format("%H.%n(%p)") + " from " + methodToParse.format("%H.%n(%p)") +
                                                        " while preparing replacement " + method.format("%H.%n(%p)") + ". Placing \"//JaCoCo Exclude\" anywhere in " +
                                                        methodToParse.getDeclaringClass().getSourceFileName() + " should fix this.");
                                    }
                                    targetGraph = parseGraph(callee, null, policy, inliningDepth + 1);
                                }
                                Object beforeInlineData = beforeInline(callTarget, targetGraph);
                                InliningUtil.inline(callTarget.invoke(), targetGraph, true, null);
                                Debug.dump(graph, "after inlining %s", callee);
                                afterInline(graph, targetGraph, beforeInlineData);
                            }
                        }
                    }
                }

                afterInlining(graph);

                for (LoopEndNode end : graph.getNodes(LoopEndNode.TYPE)) {
                    end.disableSafepoint();
                }

                new DeadCodeEliminationPhase(Required).apply(graph);
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
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
    public static Class<?> resolveClass(String className, boolean optional) {
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

    private static Class<?> resolveClass(JavaType type) {
        JavaType base = type;
        int dimensions = 0;
        while (base.getComponentType() != null) {
            base = base.getComponentType();
            dimensions++;
        }

        Class<?> baseClass = base.getKind() != Kind.Object ? base.getKind().toJavaClass() : resolveClass(base.toJavaName(), false);
        return dimensions == 0 ? baseClass : Array.newInstance(baseClass, new int[dimensions]).getClass();
    }

    static class JavaSignature {
        final Class<?> returnType;
        final Class<?>[] parameters;

        public JavaSignature(Class<?> returnType, Class<?>[] parameters) {
            this.parameters = parameters;
            this.returnType = returnType;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < parameters.length; i++) {
                if (i != 0) {
                    sb.append(", ");
                }
                sb.append(parameters[i].getName());
            }
            return sb.append(") ").append(returnType.getName()).toString();
        }
    }

    @Override
    public Collection<ResolvedJavaMethod> getAllReplacements() {
        HashSet<ResolvedJavaMethod> result = new HashSet<>();
        for (String internalName : classReplacements.keySet()) {
            ClassReplacements cr = getClassReplacements(internalName);
            result.addAll(cr.methodSubstitutions.keySet());
            result.addAll(cr.macroSubstitutions.keySet());
        }
        return result;
    }

    @Override
    public boolean isForcedSubstitution(ResolvedJavaMethod method) {
        ClassReplacements cr = getClassReplacements(method.getDeclaringClass().getName());
        return cr != null && cr.forcedSubstitutions.contains(method);
    }

    @Override
    public ResolvedJavaMethod getMethodSubstitutionMethod(ResolvedJavaMethod original) {
        ClassReplacements cr = getClassReplacements(original.getDeclaringClass().getName());
        return cr == null ? null : cr.methodSubstitutions.get(original);
    }

    @Override
    public void registerSnippetTemplateCache(SnippetTemplateCache templates) {
        assert snippetTemplateCache.get(templates.getClass().getName()) == null;
        snippetTemplateCache.put(templates.getClass().getName(), templates);
    }

    @Override
    public <T extends SnippetTemplateCache> T getSnippetTemplateCache(Class<T> templatesClass) {
        SnippetTemplateCache ret = snippetTemplateCache.get(templatesClass.getName());
        return templatesClass.cast(ret);
    }
}
