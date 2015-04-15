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
import static com.oracle.graal.java.AbstractBytecodeParser.Options.*;
import static com.oracle.graal.truffle.TruffleCompilerOptions.*;

import java.lang.invoke.*;
import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.replacements.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.graphbuilderconf.*;
import com.oracle.graal.graphbuilderconf.GraphBuilderConfiguration.Plugins;
import com.oracle.graal.java.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.StructuredGraph.AllowAssumptions;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.virtual.*;
import com.oracle.graal.options.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.phases.common.inlining.*;
import com.oracle.graal.phases.tiers.*;
import com.oracle.graal.phases.util.*;
import com.oracle.graal.replacements.*;
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

    @Option(help = "New partial evaluation on Graal graphs", type = OptionType.Expert)//
    public static final StableOptionValue<Boolean> GraphPE = new StableOptionValue<>(false);

    private final Providers providers;
    private final CanonicalizerPhase canonicalizer;
    private final SnippetReflectionProvider snippetReflection;
    private final ResolvedJavaMethod callDirectMethod;
    private final ResolvedJavaMethod callInlinedMethod;
    private final ResolvedJavaMethod callSiteProxyMethod;
    private final ResolvedJavaMethod callRootMethod;
    private final GraphBuilderConfiguration configForRoot;

    public PartialEvaluator(Providers providers, GraphBuilderConfiguration configForRoot, SnippetReflectionProvider snippetReflection) {
        this.providers = providers;
        this.canonicalizer = new CanonicalizerPhase();
        this.snippetReflection = snippetReflection;
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

    public StructuredGraph createGraph(final OptimizedCallTarget callTarget, AllowAssumptions allowAssumptions) {
        try (Scope c = Debug.scope("TruffleTree")) {
            Debug.dump(callTarget, "truffle tree");
        } catch (Throwable e) {
            throw Debug.handle(e);
        }

        final StructuredGraph graph = new StructuredGraph(callTarget.toString(), callRootMethod, allowAssumptions);
        assert graph != null : "no graph for root method";

        try (Scope s = Debug.scope("CreateGraph", graph); Indent indent = Debug.logAndIndent("createGraph %s", graph)) {

            PhaseContext baseContext = new PhaseContext(providers);
            HighTierContext tierContext = new HighTierContext(providers, new PhaseSuite<HighTierContext>(), OptimisticOptimizations.NONE);

            fastPartialEvaluation(callTarget, graph, baseContext, tierContext);

            if (Thread.currentThread().isInterrupted()) {
                return null;
            }

            new VerifyFrameDoesNotEscapePhase().apply(graph, false);
            postPartialEvaluation(graph);

        } catch (Throwable e) {
            throw Debug.handle(e);
        }

        return graph;
    }

    private class InterceptLoadFieldPlugin implements LoadFieldPlugin {

        public boolean apply(GraphBuilderContext builder, ValueNode receiver, ResolvedJavaField field) {
            if (receiver.isConstant()) {
                JavaConstant asJavaConstant = receiver.asJavaConstant();
                return tryConstantFold(builder, providers.getMetaAccess(), providers.getConstantReflection(), field, asJavaConstant);
            }
            return false;
        }

        public boolean apply(GraphBuilderContext builder, ResolvedJavaField staticField) {
            if (TruffleCompilerOptions.TruffleExcludeAssertions.getValue() && staticField.getName().equals("$assertionsDisabled")) {
                ConstantNode trueNode = builder.add(ConstantNode.forBoolean(true));
                builder.addPush(trueNode);
                return true;
            }
            return tryConstantFold(builder, providers.getMetaAccess(), providers.getConstantReflection(), staticField, null);
        }
    }

    private class InterceptReceiverPlugin implements ParameterPlugin {

        private final Object receiver;

        public InterceptReceiverPlugin(Object receiver) {
            this.receiver = receiver;
        }

        public FloatingNode interceptParameter(GraphBuilderContext b, int index, Stamp stamp) {
            if (index == 0) {
                return ConstantNode.forConstant(snippetReflection.forObject(receiver), providers.getMetaAccess());
            }
            return null;
        }
    }

    private class PEInlineInvokePlugin implements InlineInvokePlugin {

        private Deque<TruffleInlining> inlining;
        private OptimizedDirectCallNode lastDirectCallNode;
        private final ReplacementsImpl replacements;

        public PEInlineInvokePlugin(TruffleInlining inlining, ReplacementsImpl replacements) {
            this.inlining = new ArrayDeque<>();
            this.inlining.push(inlining);
            this.replacements = replacements;
        }

        @Override
        public InlineInfo getInlineInfo(GraphBuilderContext builder, ResolvedJavaMethod original, ValueNode[] arguments, JavaType returnType) {
            InlineInfo inlineInfo = replacements.getInlineInfo(builder, original, arguments, returnType);
            if (inlineInfo != null) {
                return inlineInfo;
            }

            if (original.getAnnotation(TruffleBoundary.class) != null) {
                return null;
            }
            assert !replacements.hasSubstitution(original, builder.bci()) : "Replacements must have been processed by now";
            assert !builder.parsingReplacement();

            if (TruffleCompilerOptions.TruffleFunctionInlining.getValue()) {
                if (original.equals(callSiteProxyMethod)) {
                    ValueNode arg1 = arguments[0];
                    if (!arg1.isConstant()) {
                        GraalInternalError.shouldNotReachHere("The direct call node does not resolve to a constant!");
                    }

                    Object callNode = snippetReflection.asObject(Object.class, (JavaConstant) arg1.asConstant());
                    if (callNode instanceof OptimizedDirectCallNode) {
                        OptimizedDirectCallNode directCallNode = (OptimizedDirectCallNode) callNode;
                        lastDirectCallNode = directCallNode;
                    }
                } else if (original.equals(callDirectMethod)) {
                    TruffleInliningDecision decision = getDecision(inlining.peek(), lastDirectCallNode);
                    lastDirectCallNode = null;
                    if (decision != null && decision.isInline()) {
                        inlining.push(decision);
                        builder.getAssumptions().record(new AssumptionValidAssumption((OptimizedAssumption) decision.getTarget().getNodeRewritingAssumption()));
                        return new InlineInfo(callInlinedMethod, false, false);
                    }
                }
            }

            return new InlineInfo(original, false, false);
        }

        @Override
        public void postInline(ResolvedJavaMethod inlinedTargetMethod) {
            if (inlinedTargetMethod.equals(callInlinedMethod)) {
                inlining.pop();
            }
        }
    }

    private class ParsingInlineInvokePlugin implements InlineInvokePlugin {

        private final ReplacementsImpl replacements;
        private final InvocationPlugins invocationPlugins;
        private final LoopExplosionPlugin loopExplosionPlugin;
        private final boolean inlineDuringParsing;

        public ParsingInlineInvokePlugin(ReplacementsImpl replacements, InvocationPlugins invocationPlugins, LoopExplosionPlugin loopExplosionPlugin, boolean inlineDuringParsing) {
            this.replacements = replacements;
            this.invocationPlugins = invocationPlugins;
            this.loopExplosionPlugin = loopExplosionPlugin;
            this.inlineDuringParsing = inlineDuringParsing;
        }

        private boolean hasMethodHandleArgument(ValueNode[] arguments) {
            for (ValueNode argument : arguments) {
                if (argument.isConstant()) {
                    JavaConstant constant = argument.asJavaConstant();
                    if (constant.getKind() == Kind.Object && snippetReflection.asObject(MethodHandle.class, constant) != null) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public InlineInfo getInlineInfo(GraphBuilderContext builder, ResolvedJavaMethod original, ValueNode[] arguments, JavaType returnType) {
            InlineInfo inlineInfo = replacements.getInlineInfo(builder, original, arguments, returnType);
            if (inlineInfo != null) {
                return inlineInfo;
            }

            if (invocationPlugins.lookupInvocation(original) != null || loopExplosionPlugin.shouldExplodeLoops(original)) {
                return null;
            }
            if (original.getAnnotation(TruffleBoundary.class) != null) {
                return null;
            }
            assert !replacements.hasSubstitution(original, builder.bci()) : "Replacements must have been processed by now";

            if (original.equals(callSiteProxyMethod) || original.equals(callDirectMethod)) {
                return null;
            }
            if (hasMethodHandleArgument(arguments)) {
                /*
                 * We want to inline invokes that have a constant MethodHandle parameter to remove
                 * invokedynamic related calls as early as possible.
                 */
                return new InlineInfo(original, false, false);
            }
            if (inlineDuringParsing && original.hasBytecodes() && original.getCode().length < TrivialInliningSize.getValue() && builder.getDepth() < InlineDuringParsingMaxDepth.getValue()) {
                return new InlineInfo(original, false, false);
            }
            return null;
        }
    }

    private class PELoopExplosionPlugin implements LoopExplosionPlugin {

        public boolean shouldExplodeLoops(ResolvedJavaMethod method) {
            return method.getAnnotation(ExplodeLoop.class) != null;
        }

        public boolean shouldMergeExplosions(ResolvedJavaMethod method) {
            ExplodeLoop explodeLoop = method.getAnnotation(ExplodeLoop.class);
            if (explodeLoop != null) {
                return explodeLoop.merge();
            }
            return false;
        }

    }

    protected void doFastPE(OptimizedCallTarget callTarget, StructuredGraph graph) {
        GraphBuilderConfiguration newConfig = configForRoot.copy();
        InvocationPlugins invocationPlugins = newConfig.getPlugins().getInvocationPlugins();
        TruffleGraphBuilderPlugins.registerInvocationPlugins(providers.getMetaAccess(), invocationPlugins, false, snippetReflection);

        newConfig.setUseProfiling(false);
        Plugins plugins = newConfig.getPlugins();
        plugins.setLoadFieldPlugin(new InterceptLoadFieldPlugin());
        plugins.setParameterPlugin(new InterceptReceiverPlugin(callTarget));
        callTarget.setInlining(new TruffleInlining(callTarget, new DefaultInliningPolicy()));

        InlineInvokePlugin inlinePlugin = new PEInlineInvokePlugin(callTarget.getInlining(), (ReplacementsImpl) providers.getReplacements());
        if (PrintTruffleExpansionHistogram.getValue()) {
            inlinePlugin = new HistogramInlineInvokePlugin(graph, inlinePlugin);
        }
        plugins.setInlineInvokePlugin(inlinePlugin);
        plugins.setLoopExplosionPlugin(new PELoopExplosionPlugin());
        new GraphBuilderPhase.Instance(providers.getMetaAccess(), providers.getStampProvider(), providers.getConstantReflection(), newConfig, TruffleCompilerImpl.Optimizations, null).apply(graph);
        if (PrintTruffleExpansionHistogram.getValue()) {
            ((HistogramInlineInvokePlugin) inlinePlugin).print(callTarget, System.out);
        }
    }

    protected void doGraphPE(OptimizedCallTarget callTarget, StructuredGraph graph) {
        GraphBuilderConfiguration newConfig = configForRoot.copy();
        InvocationPlugins parsingInvocationPlugins = newConfig.getPlugins().getInvocationPlugins();
        TruffleGraphBuilderPlugins.registerInvocationPlugins(providers.getMetaAccess(), parsingInvocationPlugins, true, snippetReflection);

        callTarget.setInlining(new TruffleInlining(callTarget, new DefaultInliningPolicy()));

        LoopExplosionPlugin loopExplosionPlugin = new PELoopExplosionPlugin();

        newConfig.setUseProfiling(false);
        Plugins plugins = newConfig.getPlugins();
        plugins.setLoadFieldPlugin(new InterceptLoadFieldPlugin());
        plugins.setInlineInvokePlugin(new ParsingInlineInvokePlugin((ReplacementsImpl) providers.getReplacements(), parsingInvocationPlugins, loopExplosionPlugin,
                        !PrintTruffleExpansionHistogram.getValue()));

        CachingPEGraphDecoder decoder = new CachingPEGraphDecoder(providers, newConfig, AllowAssumptions.from(graph.getAssumptions() != null));

        ParameterPlugin parameterPlugin = new InterceptReceiverPlugin(callTarget);

        InvocationPlugins decodingInvocationPlugins = new InvocationPlugins(parsingInvocationPlugins.getParent());
        TruffleGraphBuilderPlugins.registerInvocationPlugins(providers.getMetaAccess(), decodingInvocationPlugins, false, snippetReflection);
        InlineInvokePlugin decodingInlinePlugin = new PEInlineInvokePlugin(callTarget.getInlining(), (ReplacementsImpl) providers.getReplacements());
        if (PrintTruffleExpansionHistogram.getValue()) {
            decodingInlinePlugin = new HistogramInlineInvokePlugin(graph, decodingInlinePlugin);
        }

        decoder.decode(graph, graph.method(), loopExplosionPlugin, decodingInvocationPlugins, decodingInlinePlugin, parameterPlugin);

        if (PrintTruffleExpansionHistogram.getValue()) {
            ((HistogramInlineInvokePlugin) decodingInlinePlugin).print(callTarget, System.out);
        }
    }

    @SuppressWarnings("unused")
    private void fastPartialEvaluation(OptimizedCallTarget callTarget, StructuredGraph graph, PhaseContext baseContext, HighTierContext tierContext) {
        if (GraphPE.getValue()) {
            doGraphPE(callTarget, graph);
        } else {
            doFastPE(callTarget, graph);
        }
        Debug.dump(graph, "After FastPE");

        graph.maybeCompress();

        // Perform deoptimize to guard conversion.
        new ConvertDeoptimizeToGuardPhase().apply(graph, tierContext);

        for (MethodCallTargetNode methodCallTargetNode : graph.getNodes(MethodCallTargetNode.TYPE)) {
            StructuredGraph inlineGraph = providers.getReplacements().getSubstitution(methodCallTargetNode.targetMethod(), methodCallTargetNode.invoke().bci());
            if (inlineGraph != null) {
                InliningUtil.inline(methodCallTargetNode.invoke(), inlineGraph, true, null);
            }
        }

        // Perform conditional elimination.
        new DominatorConditionalEliminationPhase(false).apply(graph);

        canonicalizer.apply(graph, tierContext);

        // Do single partial escape and canonicalization pass.
        try (Scope pe = Debug.scope("TrufflePartialEscape", graph)) {
            new PartialEscapePhase(false, canonicalizer).apply(graph, tierContext);
        } catch (Throwable t) {
            Debug.handle(t);
        }

        // recompute loop frequencies now that BranchProbabilities have had time to canonicalize
        ComputeLoopFrequenciesClosure.compute(graph);

        graph.maybeCompress();

        if (TruffleCompilerOptions.TraceTrufflePerformanceWarnings.getValue()) {
            reportPerformanceWarnings(graph);
        }
    }

    private static void reportPerformanceWarnings(StructuredGraph graph) {
        ArrayList<ValueNode> warnings = new ArrayList<>();
        for (MethodCallTargetNode call : graph.getNodes(MethodCallTargetNode.TYPE)) {
            if (call.targetMethod().getAnnotation(TruffleBoundary.class) == null && call.targetMethod().getAnnotation(TruffleCallBoundary.class) == null) {
                TracePerformanceWarningsListener.logPerformanceWarning(String.format("not inlined %s call to %s (%s)", call.invokeKind(), call.targetMethod(), call), null);
                warnings.add(call);
            }
        }

        for (CheckCastNode cast : graph.getNodes().filter(CheckCastNode.class)) {
            if (cast.type().findLeafConcreteSubtype() == null) {
                TracePerformanceWarningsListener.logPerformanceWarning(String.format("non-leaf type checkcast: %s (%s)", cast.type().getName(), cast), null);
                warnings.add(cast);
            }
        }

        for (InstanceOfNode instanceOf : graph.getNodes().filter(InstanceOfNode.class)) {
            if (instanceOf.type().findLeafConcreteSubtype() == null) {
                TracePerformanceWarningsListener.logPerformanceWarning(String.format("non-leaf type instanceof: %s (%s)", instanceOf.type().getName(), instanceOf), null);
                warnings.add(instanceOf);
            }
        }

        if (Debug.isEnabled() && !warnings.isEmpty()) {
            try (Scope s = Debug.scope("TrufflePerformanceWarnings", graph)) {
                Debug.dump(graph, "performance warnings %s", warnings);
            } catch (Throwable t) {
                Debug.handle(t);
            }
        }
    }

    public StructuredGraph createRootGraph(StructuredGraph graph) {
        new GraphBuilderPhase.Instance(providers.getMetaAccess(), providers.getStampProvider(), providers.getConstantReflection(), configForRoot, TruffleCompilerImpl.Optimizations, null).apply(graph);
        return graph;
    }

    public StructuredGraph createInlineGraph(String name, StructuredGraph caller) {
        StructuredGraph graph = new StructuredGraph(name, callInlinedMethod, AllowAssumptions.from(caller.getAssumptions() != null));
        new GraphBuilderPhase.Instance(providers.getMetaAccess(), providers.getStampProvider(), providers.getConstantReflection(), configForRoot, TruffleCompilerImpl.Optimizations, null).apply(graph);
        return graph;
    }

    private static void postPartialEvaluation(final StructuredGraph graph) {
        NeverPartOfCompilationNode.verifyNotFoundIn(graph);
        for (MaterializeFrameNode materializeNode : graph.getNodes(MaterializeFrameNode.TYPE).snapshot()) {
            materializeNode.replaceAtUsages(materializeNode.getFrame());
            graph.removeFixed(materializeNode);
        }
        for (VirtualObjectNode virtualObjectNode : graph.getNodes(VirtualObjectNode.TYPE)) {
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

        if (!TruffleCompilerOptions.TruffleInlineAcrossTruffleBoundary.getValue()) {
            // Do not inline across Truffle boundaries.
            for (MethodCallTargetNode mct : graph.getNodes(MethodCallTargetNode.TYPE)) {
                if (mct.targetMethod().getAnnotation(TruffleBoundary.class) != null) {
                    mct.invoke().setUseForInlining(false);
                }
            }
        }
    }

    private static TruffleInliningDecision getDecision(TruffleInlining inlining, OptimizedDirectCallNode callNode) {
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
            return null;
        }
        return decision;
    }
}
