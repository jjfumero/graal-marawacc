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
package com.oracle.graal.truffle;

import static com.oracle.graal.compiler.common.GraalOptions.*;
import static com.oracle.graal.truffle.TruffleCompilerOptions.*;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.replacements.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.debug.internal.*;
import com.oracle.graal.graph.Graph.Mark;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.Node;
import com.oracle.graal.java.*;
import com.oracle.graal.loop.*;
import com.oracle.graal.nodes.CallTargetNode.InvokeKind;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.nodes.virtual.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.phases.common.inlining.*;
import com.oracle.graal.phases.common.inlining.info.*;
import com.oracle.graal.phases.tiers.*;
import com.oracle.graal.phases.util.*;
import com.oracle.graal.truffle.debug.*;
import com.oracle.graal.truffle.nodes.*;
import com.oracle.graal.truffle.nodes.asserts.*;
import com.oracle.graal.truffle.nodes.frame.*;
import com.oracle.graal.truffle.nodes.frame.NewFrameNode.VirtualOnlyInstanceNode;
import com.oracle.graal.truffle.phases.*;
import com.oracle.graal.truffle.substitutions.*;
import com.oracle.graal.virtual.phases.ea.*;
import com.oracle.truffle.api.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.*;

/**
 * Class performing the partial evaluation starting from the root node of an AST.
 */
public class PartialEvaluator {

    private final Providers providers;
    private final CanonicalizerPhase canonicalizer;
    private Set<JavaConstant> constantReceivers;
    private final TruffleCache truffleCache;
    private final SnippetReflectionProvider snippetReflection;
    private final ResolvedJavaMethod callDirectMethod;
    private final ResolvedJavaMethod callInlinedMethod;
    private final ResolvedJavaMethod callSiteProxyMethod;
    protected final ResolvedJavaMethod callRootMethod;
    private final GraphBuilderConfiguration configForRoot;

    public PartialEvaluator(Providers providers, GraphBuilderConfiguration configForRoot, TruffleCache truffleCache, SnippetReflectionProvider snippetReflection) {
        this.providers = providers;
        this.canonicalizer = new CanonicalizerPhase(!ImmutableCode.getValue());
        this.snippetReflection = snippetReflection;
        this.truffleCache = truffleCache;
        this.callDirectMethod = providers.getMetaAccess().lookupJavaMethod(OptimizedCallTarget.getCallDirectMethod());
        this.callInlinedMethod = providers.getMetaAccess().lookupJavaMethod(OptimizedCallTarget.getCallInlinedMethod());
        this.callSiteProxyMethod = providers.getMetaAccess().lookupJavaMethod(GraalFrameInstance.CallNodeFrame.METHOD);
        this.configForRoot = configForRoot;

        try {
            callRootMethod = providers.getMetaAccess().lookupJavaMethod(OptimizedCallTarget.class.getDeclaredMethod("callRoot", Object[].class));
        } catch (NoSuchMethodException ex) {
            throw new RuntimeException(ex);
        }
    }

    public StructuredGraph createGraph(final OptimizedCallTarget callTarget, final Assumptions assumptions, GraphBuilderPlugins graalPlugins) {
        if (TraceTruffleCompilationHistogram.getValue() || TraceTruffleCompilationDetails.getValue()) {
            constantReceivers = new HashSet<>();
        }

        try (Scope c = Debug.scope("TruffleTree")) {
            Debug.dump(callTarget, "truffle tree");
        } catch (Throwable e) {
            throw Debug.handle(e);
        }

        final StructuredGraph graph = new StructuredGraph(callTarget.toString(), callRootMethod);
        assert graph != null : "no graph for root method";

        try (Scope s = Debug.scope("CreateGraph", graph); Indent indent = Debug.logAndIndent("createGraph %s", graph)) {

            Map<ResolvedJavaMethod, StructuredGraph> graphCache = null;
            if (CacheGraphs.getValue()) {
                graphCache = new HashMap<>();
            }
            PhaseContext baseContext = new PhaseContext(providers, assumptions);
            HighTierContext tierContext = new HighTierContext(providers, assumptions, graphCache, new PhaseSuite<HighTierContext>(), OptimisticOptimizations.NONE);

            if (TruffleCompilerOptions.FastPE.getValue()) {
                fastPartialEvaluation(callTarget, assumptions, graph, baseContext, tierContext, graalPlugins);
            } else {
                createRootGraph(graph);
                partialEvaluation(callTarget, assumptions, graph, baseContext, tierContext);
            }

            if (Thread.currentThread().isInterrupted()) {
                return null;
            }

            new VerifyFrameDoesNotEscapePhase().apply(graph, false);
            if (TraceTruffleCompilationHistogram.getValue() && constantReceivers != null) {
                createHistogram();
            }
            postPartialEvaluation(graph);

        } catch (Throwable e) {
            throw Debug.handle(e);
        }

        return graph;
    }

    private class InterceptLoadFieldPlugin implements GraphBuilderPlugins.LoadFieldPlugin {

        public boolean apply(GraphBuilderContext builder, ValueNode receiver, ResolvedJavaField field) {
            if (receiver.isConstant()) {
                JavaConstant asJavaConstant = receiver.asJavaConstant();
                return tryConstantFold(builder, field, asJavaConstant);
            }
            return false;
        }

        private boolean tryConstantFold(GraphBuilderContext builder, ResolvedJavaField field, JavaConstant asJavaConstant) {
            JavaConstant result = providers.getConstantReflection().readConstantFieldValue(field, asJavaConstant);
            if (result != null) {
                ConstantNode constantNode = builder.append(ConstantNode.forConstant(result, providers.getMetaAccess()));
                builder.push(constantNode.getKind().getStackKind(), constantNode);
                return true;
            }
            return false;
        }

        public boolean apply(GraphBuilderContext builder, ResolvedJavaField staticField) {
            if (TruffleCompilerOptions.TruffleExcludeAssertions.getValue() && staticField.getName().equals("$assertionsDisabled")) {
                ConstantNode trueNode = builder.append(ConstantNode.forBoolean(true));
                builder.push(trueNode.getKind().getStackKind(), trueNode);
                return true;
            }
            return tryConstantFold(builder, staticField, null);
        }
    }

    private class InterceptReceiverPlugin implements GraphBuilderPlugins.ParameterPlugin {

        private final Object receiver;

        public InterceptReceiverPlugin(Object receiver) {
            this.receiver = receiver;
        }

        public FloatingNode interceptParameter(int index) {
            if (index == 0) {
                return ConstantNode.forConstant(snippetReflection.forObject(receiver), providers.getMetaAccess());
            }
            return null;
        }
    }

    private class InlineInvokePlugin implements GraphBuilderPlugins.InlineInvokePlugin {

        public boolean shouldInlineInvoke(ResolvedJavaMethod method, int depth) {
            return method.getAnnotation(TruffleBoundary.class) == null;
        }

    }

    private class LoopExplosionPlugin implements GraphBuilderPlugins.LoopExplosionPlugin {

        public boolean shouldExplodeLoops(ResolvedJavaMethod method) {
            return method.getAnnotation(ExplodeLoop.class) != null;
        }

    }

    @SuppressWarnings("unused")
    private void fastPartialEvaluation(OptimizedCallTarget callTarget, Assumptions assumptions, StructuredGraph graph, PhaseContext baseContext, HighTierContext tierContext,
                    GraphBuilderPlugins graalPlugins) {
        GraphBuilderConfiguration newConfig = configForRoot.copy();
        newConfig.setLoadFieldPlugin(new InterceptLoadFieldPlugin());
        newConfig.setParameterPlugin(new InterceptReceiverPlugin(callTarget));
        newConfig.setInlineInvokePlugin(new InlineInvokePlugin());
        newConfig.setLoopExplosionPlugin(new LoopExplosionPlugin());
        DefaultGraphBuilderPlugins plugins = graalPlugins == null ? new DefaultGraphBuilderPlugins() : graalPlugins.copy();
        TruffleGraphBuilderPlugins.registerPlugins(providers.getMetaAccess(), plugins);
        long ms = System.currentTimeMillis();
        new GraphBuilderPhase.Instance(providers.getMetaAccess(), providers.getStampProvider(), new Assumptions(true), this.snippetReflection, providers.getConstantReflection(), newConfig, plugins,
                        TruffleCompilerImpl.Optimizations).apply(graph);
        System.out.println("# ms: " + (System.currentTimeMillis() - ms));
        Debug.dump(graph, "After FastPE");

        // Do single partial escape and canonicalization pass.
        try (Scope pe = Debug.scope("TrufflePartialEscape", graph)) {
            new PartialEscapePhase(true, canonicalizer).apply(graph, tierContext);
            new IncrementalCanonicalizerPhase<>(canonicalizer, new ConditionalEliminationPhase()).apply(graph, tierContext);
        } catch (Throwable t) {
            Debug.handle(t);
        }
    }

    private void partialEvaluation(final OptimizedCallTarget callTarget, final Assumptions assumptions, final StructuredGraph graph, PhaseContext baseContext, HighTierContext tierContext) {
        injectConstantCallTarget(graph, callTarget, baseContext);

        Debug.dump(graph, "Before expansion");

        TruffleExpansionLogger expansionLogger = null;
        if (TraceTruffleExpansion.getValue()) {
            expansionLogger = new TruffleExpansionLogger(providers, graph);
        }

        expandTree(graph, assumptions, expansionLogger);

        TruffleInliningCache inliningCache = null;
        if (TruffleFunctionInlining.getValue()) {
            callTarget.setInlining(new TruffleInlining(callTarget, new DefaultInliningPolicy()));
            if (TruffleFunctionInliningCache.getValue()) {
                inliningCache = new TruffleInliningCache();
            }
        }

        expandDirectCalls(graph, assumptions, expansionLogger, callTarget.getInlining(), inliningCache);

        if (Thread.currentThread().isInterrupted()) {
            return;
        }

        canonicalizer.apply(graph, baseContext);
        // EA frame and clean up.
        do {
            try (Scope pe = Debug.scope("TrufflePartialEscape", graph)) {
                new PartialEscapePhase(true, canonicalizer).apply(graph, tierContext);
                new IncrementalCanonicalizerPhase<>(canonicalizer, new ConditionalEliminationPhase()).apply(graph, tierContext);
            } catch (Throwable t) {
                Debug.handle(t);
            }
        } while (expandTree(graph, assumptions, expansionLogger));

        if (expansionLogger != null) {
            expansionLogger.print(callTarget);
        }
    }

    public StructuredGraph createRootGraph(StructuredGraph graph) {
        new GraphBuilderPhase.Instance(providers.getMetaAccess(), providers.getStampProvider(), new Assumptions(false), providers.getConstantReflection(), configForRoot,
                        TruffleCompilerImpl.Optimizations).apply(graph);
        return graph;
    }

    public StructuredGraph createInlineGraph(String name) {
        StructuredGraph graph = new StructuredGraph(name, callInlinedMethod);
        new GraphBuilderPhase.Instance(providers.getMetaAccess(), providers.getStampProvider(), new Assumptions(false), providers.getConstantReflection(), configForRoot,
                        TruffleCompilerImpl.Optimizations).apply(graph);
        return graph;
    }

    private static void postPartialEvaluation(final StructuredGraph graph) {
        NeverPartOfCompilationNode.verifyNotFoundIn(graph);
        for (MaterializeFrameNode materializeNode : graph.getNodes(MaterializeFrameNode.class).snapshot()) {
            materializeNode.replaceAtUsages(materializeNode.getFrame());
            graph.removeFixed(materializeNode);
        }
        for (VirtualObjectNode virtualObjectNode : graph.getNodes(VirtualObjectNode.class)) {
            if (virtualObjectNode instanceof VirtualOnlyInstanceNode) {
                VirtualOnlyInstanceNode virtualOnlyInstanceNode = (VirtualOnlyInstanceNode) virtualObjectNode;
                virtualOnlyInstanceNode.setAllowMaterialization(true);
            } else if (virtualObjectNode instanceof VirtualInstanceNode) {
                VirtualInstanceNode virtualInstanceNode = (VirtualInstanceNode) virtualObjectNode;
                ResolvedJavaType type = virtualInstanceNode.type();
                if (type.getAnnotation(CompilerDirectives.ValueType.class) != null) {
                    virtualInstanceNode.setIdentity(false);
                }
            }
        }
    }

    private void injectConstantCallTarget(final StructuredGraph graph, final OptimizedCallTarget constantCallTarget, PhaseContext baseContext) {
        ParameterNode thisNode = graph.getParameter(0);

        /*
         * Converting the call target to a Constant using the SnippetReflectionProvider is a
         * workaround, we should think about a better solution. Since object constants are
         * VM-specific, only the hosting VM knows how to do the conversion.
         */
        thisNode.replaceAndDelete(ConstantNode.forConstant(snippetReflection.forObject(constantCallTarget), providers.getMetaAccess(), graph));

        canonicalizer.apply(graph, baseContext);

        new IncrementalCanonicalizerPhase<>(canonicalizer, new ReplaceIntrinsicsPhase(providers.getReplacements())).apply(graph, baseContext);
    }

    private void createHistogram() {
        DebugHistogram histogram = Debug.createHistogram("Expanded Truffle Nodes");
        for (JavaConstant c : constantReceivers) {
            String javaName = providers.getMetaAccess().lookupJavaType(c).toJavaName(false);

            // The DSL uses nested classes with redundant names - only show the inner class
            int index = javaName.indexOf('$');
            if (index != -1) {
                javaName = javaName.substring(index + 1);
            }

            histogram.add(javaName);

        }
        new DebugHistogramAsciiPrinter(TTY.out().out()).print(histogram);
    }

    private boolean expandTree(StructuredGraph graph, Assumptions assumptions, TruffleExpansionLogger expansionLogger) {
        PhaseContext phaseContext = new PhaseContext(providers, assumptions);
        boolean changed = false;
        boolean changedInIteration;
        ArrayDeque<MethodCallTargetNode> queue = new ArrayDeque<>();
        MetaAccessProvider metaAccess = providers.getMetaAccess();
        ResolvedJavaType profileClass = metaAccess.lookupJavaType(NodeCloneable.class);
        do {
            changedInIteration = false;

            Mark mark = null;
            while (true) {

                for (MethodCallTargetNode methodCallTargetNode : graph.getNewNodes(mark).filter(MethodCallTargetNode.class)) {
                    if (methodCallTargetNode.invokeKind().isDirect()) {
                        ValueNode receiver = methodCallTargetNode.receiver();
                        if (receiver != null && receiver.isConstant() && profileClass.isAssignableFrom(receiver.stamp().javaType(metaAccess))) {
                            queue.addFirst(methodCallTargetNode);
                        } else {
                            queue.addLast(methodCallTargetNode);
                        }
                    }
                }
                mark = graph.getMark();

                if (queue.isEmpty()) {
                    break;
                }
                MethodCallTargetNode methodCallTargetNode = queue.removeFirst();
                if (!methodCallTargetNode.isAlive()) {
                    continue;
                }
                InvokeKind kind = methodCallTargetNode.invokeKind();
                try (Indent id1 = Debug.logAndIndent("try inlining %s, kind = %s", methodCallTargetNode.targetMethod(), kind)) {
                    if (kind.isDirect()) {
                        if ((TraceTruffleCompilationHistogram.getValue() || TraceTruffleCompilationDetails.getValue()) && kind == InvokeKind.Special && methodCallTargetNode.receiver().isConstant()) {
                            constantReceivers.add(methodCallTargetNode.receiver().asJavaConstant());
                        }

                        Replacements replacements = providers.getReplacements();
                        Class<? extends FixedWithNextNode> macroSubstitution = replacements.getMacroSubstitution(methodCallTargetNode.targetMethod());
                        if (macroSubstitution != null) {
                            InliningUtil.inlineMacroNode(methodCallTargetNode.invoke(), methodCallTargetNode.targetMethod(), macroSubstitution);
                            changed = changedInIteration = true;
                            continue;
                        }
                        StructuredGraph inlineGraph = replacements.getMethodSubstitution(methodCallTargetNode.targetMethod());

                        ResolvedJavaMethod targetMethod = methodCallTargetNode.targetMethod();
                        if (inlineGraph == null && targetMethod.hasBytecodes() && targetMethod.canBeInlined()) {
                            inlineGraph = parseGraph(methodCallTargetNode.targetMethod(), methodCallTargetNode.arguments(), phaseContext);
                        }

                        if (inlineGraph != null) {
                            expandTreeInline(graph, phaseContext, expansionLogger, methodCallTargetNode, inlineGraph);
                            changed = changedInIteration = true;
                        }
                    }
                }

                if (graph.getNodeCount() > TruffleCompilerOptions.TruffleGraphMaxNodes.getValue()) {
                    throw new BailoutException("Truffle compilation is exceeding maximum node count: %d", graph.getNodeCount());
                }
            }

        } while (changedInIteration);

        return changed;
    }

    private void expandTreeInline(StructuredGraph graph, PhaseContext phaseContext, TruffleExpansionLogger expansionLogger, MethodCallTargetNode methodCallTargetNode, StructuredGraph inlineGraph) {
        try (Indent indent = Debug.logAndIndent("expand graph %s", methodCallTargetNode.targetMethod())) {
            int nodeCountBefore = graph.getNodeCount();
            if (expansionLogger != null) {
                expansionLogger.preExpand(methodCallTargetNode, inlineGraph);
            }
            List<Node> canonicalizedNodes = methodCallTargetNode.invoke().asNode().usages().snapshot();

            Map<Node, Node> inlined = InliningUtil.inline(methodCallTargetNode.invoke(), inlineGraph, false, canonicalizedNodes);
            if (expansionLogger != null) {
                expansionLogger.postExpand(inlined);
            }
            if (Debug.isDumpEnabled()) {
                int nodeCountAfter = graph.getNodeCount();
                Debug.dump(graph, "After expand %s %+d (%d)", methodCallTargetNode.targetMethod().toString(), nodeCountAfter - nodeCountBefore, nodeCountAfter);
            }
            AbstractInlineInfo.getInlinedParameterUsages(canonicalizedNodes, inlineGraph, inlined);
            canonicalizer.applyIncremental(graph, phaseContext, canonicalizedNodes);
        }
    }

    private StructuredGraph parseGraph(final ResolvedJavaMethod targetMethod, final NodeInputList<ValueNode> arguments, final PhaseContext phaseContext) {
        StructuredGraph graph = truffleCache.lookup(targetMethod, arguments, canonicalizer);

        if (graph != null && targetMethod.getAnnotation(ExplodeLoop.class) != null) {
            assert graph.hasLoops() : graph + " does not contain a loop";
            final StructuredGraph graphCopy = graph.copy();
            final List<Node> modifiedNodes = new ArrayList<>();
            for (ParameterNode param : graphCopy.getNodes(ParameterNode.class).snapshot()) {
                ValueNode arg = arguments.get(param.index());
                if (arg.isConstant()) {
                    Constant constant = arg.asConstant();
                    param.usages().snapshotTo(modifiedNodes);
                    param.replaceAndDelete(ConstantNode.forConstant(arg.stamp(), constant, phaseContext.getMetaAccess(), graphCopy));
                } else {
                    ValueNode length = GraphUtil.arrayLength(arg);
                    if (length != null && length.isConstant()) {
                        param.usages().snapshotTo(modifiedNodes);
                        ParameterNode newParam = graphCopy.addWithoutUnique(new ParameterNode(param.index(), param.stamp()));
                        param.replaceAndDelete(graphCopy.addWithoutUnique(new PiArrayNode(newParam, ConstantNode.forInt(length.asJavaConstant().asInt(), graphCopy), param.stamp())));
                    }
                }
            }
            try (Scope s = Debug.scope("TruffleUnrollLoop", targetMethod)) {

                canonicalizer.applyIncremental(graphCopy, phaseContext, modifiedNodes);
                boolean unrolled;
                do {
                    unrolled = false;
                    LoopsData loopsData = new LoopsData(graphCopy);
                    loopsData.detectedCountedLoops();
                    for (LoopEx ex : innerLoopsFirst(loopsData.countedLoops())) {
                        if (ex.counted().isConstantMaxTripCount()) {
                            long constant = ex.counted().constantMaxTripCount();
                            LoopTransformations.fullUnroll(ex, phaseContext, canonicalizer);
                            Debug.dump(graphCopy, "After loop unrolling %d times", constant);
                            unrolled = true;
                            break;
                        }
                    }
                    loopsData.deleteUnusedNodes();
                } while (unrolled);
            } catch (Throwable e) {
                throw Debug.handle(e);
            }

            return graphCopy;
        } else {
            return graph;
        }
    }

    private void expandDirectCalls(StructuredGraph graph, Assumptions assumptions, TruffleExpansionLogger expansionLogger, TruffleInlining inlining, TruffleInliningCache inliningCache) {
        PhaseContext phaseContext = new PhaseContext(providers, assumptions);

        for (MethodCallTargetNode methodCallTargetNode : graph.getNodes(MethodCallTargetNode.class).snapshot()) {
            StructuredGraph inlineGraph = parseDirectCallGraph(phaseContext, assumptions, inlining, inliningCache, methodCallTargetNode);

            if (inlineGraph != null) {
                expandTreeInline(graph, phaseContext, expansionLogger, methodCallTargetNode, inlineGraph);
            }
        }
        // non inlined direct calls need to be expanded until TruffleCallBoundary.
        expandTree(graph, assumptions, expansionLogger);
        assert noDirectCallsLeft(graph);
    }

    private boolean noDirectCallsLeft(StructuredGraph graph) {
        for (MethodCallTargetNode methodCallTargetNode : graph.getNodes(MethodCallTargetNode.class).snapshot()) {
            if (methodCallTargetNode.targetMethod().equals(callDirectMethod)) {
                return false;
            }
        }
        return true;
    }

    private StructuredGraph parseDirectCallGraph(PhaseContext phaseContext, Assumptions assumptions, TruffleInlining inlining, TruffleInliningCache inliningCache,
                    MethodCallTargetNode methodCallTargetNode) {
        OptimizedDirectCallNode callNode = resolveConstantCallNode(methodCallTargetNode);
        if (callNode == null) {
            return null;
        }

        TruffleInliningDecision decision = inlining.findByCall(callNode);
        if (decision == null) {
            if (TruffleCompilerOptions.TraceTrufflePerformanceWarnings.getValue()) {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("callNode", callNode);
                TracePerformanceWarningsListener.logPerformanceWarning("A direct call within the Truffle AST is not reachable anymore. Call node could not be inlined.", properties);
            }
        }

        if (decision != null && decision.getTarget() != decision.getProfile().getCallNode().getCurrentCallTarget()) {
            if (TruffleCompilerOptions.TraceTrufflePerformanceWarnings.getValue()) {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("originalTarget", decision.getTarget());
                properties.put("callNode", callNode);
                TracePerformanceWarningsListener.logPerformanceWarning(String.format("CallTarget changed during compilation. Call node could not be inlined."), properties);
            }
            decision = null;
        }

        StructuredGraph graph;
        if (decision != null && decision.isInline()) {
            if (inliningCache == null) {
                graph = createInlineGraph(phaseContext, assumptions, null, decision);
            } else {
                graph = inliningCache.getCachedGraph(phaseContext, assumptions, decision);
            }
            decision.getProfile().setGraalDeepNodeCount(graph.getNodeCount());

            assumptions.record(new AssumptionValidAssumption((OptimizedAssumption) decision.getTarget().getNodeRewritingAssumption()));
        } else {
            // we continue expansion of callDirect until we reach the callBoundary.
            graph = parseGraph(methodCallTargetNode.targetMethod(), methodCallTargetNode.arguments(), phaseContext);
        }

        return graph;
    }

    private OptimizedDirectCallNode resolveConstantCallNode(MethodCallTargetNode methodCallTargetNode) {
        if (!methodCallTargetNode.targetMethod().equals(callDirectMethod)) {
            return null;
        }

        Invoke invoke = methodCallTargetNode.invoke();
        if (invoke == null) {
            return null;
        }

        FrameState directCallState = invoke.stateAfter();
        while (directCallState != null && !directCallState.method().equals(callSiteProxyMethod)) {
            directCallState = directCallState.outerFrameState();
        }

        if (directCallState == null) {
            // not a direct call. May be indirect call.
            return null;
        }

        if (directCallState.values().isEmpty()) {
            throw new AssertionError(String.format("Frame state of method '%s' is invalid.", callDirectMethod.toString()));
        }

        ValueNode node = directCallState.values().get(0);
        if (!node.isConstant()) {
            throw new AssertionError(String.format("Method argument for method '%s' is not constant.", callDirectMethod.toString()));
        }

        JavaConstant constantCallNode = node.asJavaConstant();
        Object value = snippetReflection.asObject(Object.class, constantCallNode);

        if (!(value instanceof OptimizedDirectCallNode)) {
            // might be an indirect call.
            return null;
        }

        return (OptimizedDirectCallNode) value;
    }

    private StructuredGraph createInlineGraph(PhaseContext phaseContext, Assumptions assumptions, TruffleInliningCache cache, TruffleInliningDecision decision) {
        try (Scope s = Debug.scope("GuestLanguageInlinedGraph", new DebugDumpScope(decision.getTarget().toString()))) {
            OptimizedCallTarget target = decision.getTarget();
            StructuredGraph inlineGraph = createInlineGraph(target.toString());
            injectConstantCallTarget(inlineGraph, decision.getTarget(), phaseContext);
            TruffleExpansionLogger expansionLogger = null;
            if (TraceTruffleExpansion.getValue()) {
                expansionLogger = new TruffleExpansionLogger(providers, inlineGraph);
            }
            expandTree(inlineGraph, assumptions, expansionLogger);
            expandDirectCalls(inlineGraph, assumptions, expansionLogger, decision, cache);

            if (expansionLogger != null) {
                expansionLogger.print(target);
            }
            return inlineGraph;
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
    }

    private static List<LoopEx> innerLoopsFirst(Collection<LoopEx> loops) {
        ArrayList<LoopEx> sortedLoops = new ArrayList<>(loops);
        Collections.sort(sortedLoops, new Comparator<LoopEx>() {

            @Override
            public int compare(LoopEx o1, LoopEx o2) {
                return o2.loop().getDepth() - o1.loop().getDepth();
            }
        });
        return sortedLoops;
    }

    private final class TruffleInliningCache {

        private final Map<CacheKey, StructuredGraph> cache;

        public TruffleInliningCache() {
            this.cache = new HashMap<>();
        }

        public StructuredGraph getCachedGraph(PhaseContext phaseContext, Assumptions assumptions, TruffleInliningDecision decision) {
            CacheKey cacheKey = new CacheKey(decision);
            StructuredGraph inlineGraph = cache.get(cacheKey);
            if (inlineGraph == null) {
                inlineGraph = createInlineGraph(phaseContext, assumptions, this, decision);
                cache.put(cacheKey, inlineGraph);
            }
            return inlineGraph;
        }

        private final class CacheKey {

            public final TruffleInliningDecision decision;

            public CacheKey(TruffleInliningDecision decision) {
                this.decision = decision;
                /*
                 * If decision.isInline() is not true CacheKey#hashCode does not match
                 * CacheKey#equals
                 */
                assert decision.isInline();
            }

            @Override
            public int hashCode() {
                return decision.getTarget().hashCode();
            }

            @Override
            public boolean equals(Object obj) {
                if (!(obj instanceof CacheKey)) {
                    return false;
                }
                CacheKey other = (CacheKey) obj;
                return decision.isSameAs(other.decision);
            }
        }
    }

}
