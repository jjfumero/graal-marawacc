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
package com.oracle.graal.phases.common.inlining.walker;

import com.oracle.graal.api.code.Assumptions;
import com.oracle.graal.api.code.BailoutException;
import com.oracle.graal.api.meta.JavaTypeProfile;
import com.oracle.graal.api.meta.ResolvedJavaMethod;
import com.oracle.graal.api.meta.ResolvedJavaType;
import com.oracle.graal.compiler.common.GraalInternalError;
import com.oracle.graal.compiler.common.type.ObjectStamp;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.DebugMetric;
import com.oracle.graal.graph.Graph;
import com.oracle.graal.graph.Node;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.MethodCallTargetNode;
import com.oracle.graal.phases.OptimisticOptimizations;
import com.oracle.graal.phases.common.CanonicalizerPhase;
import com.oracle.graal.phases.common.inlining.InliningUtil;
import com.oracle.graal.phases.common.inlining.info.*;
import com.oracle.graal.phases.common.inlining.policy.InliningPolicy;
import com.oracle.graal.phases.graph.FixedNodeProbabilityCache;
import com.oracle.graal.phases.tiers.HighTierContext;
import com.oracle.graal.phases.util.Providers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

import static com.oracle.graal.compiler.common.GraalOptions.MegamorphicInliningMinMethodProbability;
import static com.oracle.graal.compiler.common.GraalOptions.OptCanonicalizer;

/**
 * Holds the data for building the callee graphs recursively: graphs and invocations (each
 * invocation can have multiple graphs).
 */
public class InliningData {

    private static final CallsiteHolder DUMMY_CALLSITE_HOLDER = new CallsiteHolder(null, 1.0, 1.0);
    // Metrics
    private static final DebugMetric metricInliningPerformed = Debug.metric("InliningPerformed");
    private static final DebugMetric metricInliningRuns = Debug.metric("InliningRuns");
    private static final DebugMetric metricInliningConsidered = Debug.metric("InliningConsidered");

    /**
     * Call hierarchy from outer most call (i.e., compilation unit) to inner most callee.
     */
    private final ArrayDeque<CallsiteHolder> graphQueue = new ArrayDeque<>();
    private final ArrayDeque<MethodInvocation> invocationQueue = new ArrayDeque<>();
    private final ToDoubleFunction<FixedNode> probabilities = new FixedNodeProbabilityCache();

    private final HighTierContext context;
    private final int maxMethodPerInlining;
    private final CanonicalizerPhase canonicalizer;
    private final InliningPolicy inliningPolicy;

    private int maxGraphs;

    public InliningData(StructuredGraph rootGraph, HighTierContext context, int maxMethodPerInlining, CanonicalizerPhase canonicalizer, InliningPolicy inliningPolicy) {
        assert rootGraph != null;
        this.context = context;
        this.maxMethodPerInlining = maxMethodPerInlining;
        this.canonicalizer = canonicalizer;
        this.inliningPolicy = inliningPolicy;
        this.maxGraphs = 1;

        Assumptions rootAssumptions = context.getAssumptions();
        invocationQueue.push(new MethodInvocation(null, rootAssumptions, 1.0, 1.0));
        pushGraph(rootGraph, 1.0, 1.0);
    }

    /**
     * Determines if inlining is possible at the given invoke node.
     *
     * @param invoke the invoke that should be inlined
     * @return an instance of InlineInfo, or null if no inlining is possible at the given invoke
     */
    public InlineInfo getInlineInfo(Invoke invoke, Assumptions assumptions) {
        final String failureMessage = InliningUtil.checkInvokeConditions(invoke);
        if (failureMessage != null) {
            InliningUtil.logNotInlinedMethod(invoke, failureMessage);
            return null;
        }
        MethodCallTargetNode callTarget = (MethodCallTargetNode) invoke.callTarget();
        ResolvedJavaMethod targetMethod = callTarget.targetMethod();
        final OptimisticOptimizations optimisticOpts = context.getOptimisticOptimizations();

        if (callTarget.invokeKind() == MethodCallTargetNode.InvokeKind.Special || targetMethod.canBeStaticallyBound()) {
            return getExactInlineInfo(invoke, optimisticOpts, targetMethod);
        }

        assert callTarget.invokeKind() == MethodCallTargetNode.InvokeKind.Virtual || callTarget.invokeKind() == MethodCallTargetNode.InvokeKind.Interface;

        ResolvedJavaType holder = targetMethod.getDeclaringClass();
        if (!(callTarget.receiver().stamp() instanceof ObjectStamp)) {
            return null;
        }
        ObjectStamp receiverStamp = (ObjectStamp) callTarget.receiver().stamp();
        if (receiverStamp.alwaysNull()) {
            // Don't inline if receiver is known to be null
            return null;
        }
        ResolvedJavaType contextType = invoke.getContextType();
        if (receiverStamp.type() != null) {
            // the invoke target might be more specific than the holder (happens after inlining:
            // parameters lose their declared type...)
            ResolvedJavaType receiverType = receiverStamp.type();
            if (receiverType != null && holder.isAssignableFrom(receiverType)) {
                holder = receiverType;
                if (receiverStamp.isExactType()) {
                    assert targetMethod.getDeclaringClass().isAssignableFrom(holder) : holder + " subtype of " + targetMethod.getDeclaringClass() + " for " + targetMethod;
                    ResolvedJavaMethod resolvedMethod = holder.resolveMethod(targetMethod, contextType);
                    if (resolvedMethod != null) {
                        return getExactInlineInfo(invoke, optimisticOpts, resolvedMethod);
                    }
                }
            }
        }

        if (holder.isArray()) {
            // arrays can be treated as Objects
            ResolvedJavaMethod resolvedMethod = holder.resolveMethod(targetMethod, contextType);
            if (resolvedMethod != null) {
                return getExactInlineInfo(invoke, optimisticOpts, resolvedMethod);
            }
        }

        if (assumptions.useOptimisticAssumptions()) {
            ResolvedJavaType uniqueSubtype = holder.findUniqueConcreteSubtype();
            if (uniqueSubtype != null) {
                ResolvedJavaMethod resolvedMethod = uniqueSubtype.resolveMethod(targetMethod, contextType);
                if (resolvedMethod != null) {
                    return getAssumptionInlineInfo(invoke, optimisticOpts, resolvedMethod, new Assumptions.ConcreteSubtype(holder, uniqueSubtype));
                }
            }

            ResolvedJavaMethod concrete = holder.findUniqueConcreteMethod(targetMethod);
            if (concrete != null) {
                return getAssumptionInlineInfo(invoke, optimisticOpts, concrete, new Assumptions.ConcreteMethod(targetMethod, holder, concrete));
            }
        }

        // type check based inlining
        return getTypeCheckedInlineInfo(invoke, targetMethod, optimisticOpts);
    }

    public InlineInfo getTypeCheckedInlineInfo(Invoke invoke, ResolvedJavaMethod targetMethod, OptimisticOptimizations optimisticOpts) {
        JavaTypeProfile typeProfile;
        ValueNode receiver = invoke.callTarget().arguments().get(0);
        if (receiver instanceof TypeProfileProxyNode) {
            TypeProfileProxyNode typeProfileProxyNode = (TypeProfileProxyNode) receiver;
            typeProfile = typeProfileProxyNode.getProfile();
        } else {
            InliningUtil.logNotInlined(invoke, inliningDepth(), targetMethod, "no type profile exists");
            return null;
        }

        JavaTypeProfile.ProfiledType[] ptypes = typeProfile.getTypes();
        if (ptypes == null || ptypes.length <= 0) {
            InliningUtil.logNotInlined(invoke, inliningDepth(), targetMethod, "no types in profile");
            return null;
        }
        ResolvedJavaType contextType = invoke.getContextType();
        double notRecordedTypeProbability = typeProfile.getNotRecordedProbability();
        if (ptypes.length == 1 && notRecordedTypeProbability == 0) {
            if (!optimisticOpts.inlineMonomorphicCalls()) {
                InliningUtil.logNotInlined(invoke, inliningDepth(), targetMethod, "inlining monomorphic calls is disabled");
                return null;
            }

            ResolvedJavaType type = ptypes[0].getType();
            assert type.isArray() || !type.isAbstract();
            ResolvedJavaMethod concrete = type.resolveMethod(targetMethod, contextType);
            if (!InliningUtil.checkTargetConditions(this, context.getReplacements(), invoke, concrete, optimisticOpts)) {
                return null;
            }
            return new TypeGuardInlineInfo(invoke, concrete, type);
        } else {
            invoke.setPolymorphic(true);

            if (!optimisticOpts.inlinePolymorphicCalls() && notRecordedTypeProbability == 0) {
                InliningUtil.logNotInlinedInvoke(invoke, inliningDepth(), targetMethod, "inlining polymorphic calls is disabled (%d types)", ptypes.length);
                return null;
            }
            if (!optimisticOpts.inlineMegamorphicCalls() && notRecordedTypeProbability > 0) {
                // due to filtering impossible types, notRecordedTypeProbability can be > 0 although
                // the number of types is lower than what can be recorded in a type profile
                InliningUtil.logNotInlinedInvoke(invoke, inliningDepth(), targetMethod, "inlining megamorphic calls is disabled (%d types, %f %% not recorded types)", ptypes.length,
                                notRecordedTypeProbability * 100);
                return null;
            }

            // Find unique methods and their probabilities.
            ArrayList<ResolvedJavaMethod> concreteMethods = new ArrayList<>();
            ArrayList<Double> concreteMethodsProbabilities = new ArrayList<>();
            for (int i = 0; i < ptypes.length; i++) {
                ResolvedJavaMethod concrete = ptypes[i].getType().resolveMethod(targetMethod, contextType);
                if (concrete == null) {
                    InliningUtil.logNotInlined(invoke, inliningDepth(), targetMethod, "could not resolve method");
                    return null;
                }
                int index = concreteMethods.indexOf(concrete);
                double curProbability = ptypes[i].getProbability();
                if (index < 0) {
                    index = concreteMethods.size();
                    concreteMethods.add(concrete);
                    concreteMethodsProbabilities.add(curProbability);
                } else {
                    concreteMethodsProbabilities.set(index, concreteMethodsProbabilities.get(index) + curProbability);
                }
            }

            if (concreteMethods.size() > maxMethodPerInlining) {
                InliningUtil.logNotInlinedInvoke(invoke, inliningDepth(), targetMethod, "polymorphic call with more than %d target methods", maxMethodPerInlining);
                return null;
            }

            // Clear methods that fall below the threshold.
            if (notRecordedTypeProbability > 0) {
                ArrayList<ResolvedJavaMethod> newConcreteMethods = new ArrayList<>();
                ArrayList<Double> newConcreteMethodsProbabilities = new ArrayList<>();
                for (int i = 0; i < concreteMethods.size(); ++i) {
                    if (concreteMethodsProbabilities.get(i) >= MegamorphicInliningMinMethodProbability.getValue()) {
                        newConcreteMethods.add(concreteMethods.get(i));
                        newConcreteMethodsProbabilities.add(concreteMethodsProbabilities.get(i));
                    }
                }

                if (newConcreteMethods.size() == 0) {
                    // No method left that is worth inlining.
                    InliningUtil.logNotInlinedInvoke(invoke, inliningDepth(), targetMethod, "no methods remaining after filtering less frequent methods (%d methods previously)",
                                    concreteMethods.size());
                    return null;
                }

                concreteMethods = newConcreteMethods;
                concreteMethodsProbabilities = newConcreteMethodsProbabilities;
            }

            // Clean out types whose methods are no longer available.
            ArrayList<JavaTypeProfile.ProfiledType> usedTypes = new ArrayList<>();
            ArrayList<Integer> typesToConcretes = new ArrayList<>();
            for (JavaTypeProfile.ProfiledType type : ptypes) {
                ResolvedJavaMethod concrete = type.getType().resolveMethod(targetMethod, contextType);
                int index = concreteMethods.indexOf(concrete);
                if (index == -1) {
                    notRecordedTypeProbability += type.getProbability();
                } else {
                    assert type.getType().isArray() || !type.getType().isAbstract() : type + " " + concrete;
                    usedTypes.add(type);
                    typesToConcretes.add(index);
                }
            }

            if (usedTypes.size() == 0) {
                // No type left that is worth checking for.
                InliningUtil.logNotInlinedInvoke(invoke, inliningDepth(), targetMethod, "no types remaining after filtering less frequent types (%d types previously)", ptypes.length);
                return null;
            }

            for (ResolvedJavaMethod concrete : concreteMethods) {
                if (!InliningUtil.checkTargetConditions(this, context.getReplacements(), invoke, concrete, optimisticOpts)) {
                    InliningUtil.logNotInlined(invoke, inliningDepth(), targetMethod, "it is a polymorphic method call and at least one invoked method cannot be inlined");
                    return null;
                }
            }
            return new MultiTypeGuardInlineInfo(invoke, concreteMethods, concreteMethodsProbabilities, usedTypes, typesToConcretes, notRecordedTypeProbability);
        }
    }

    public InlineInfo getAssumptionInlineInfo(Invoke invoke, OptimisticOptimizations optimisticOpts, ResolvedJavaMethod concrete, Assumptions.Assumption takenAssumption) {
        assert !concrete.isAbstract();
        if (!InliningUtil.checkTargetConditions(this, context.getReplacements(), invoke, concrete, optimisticOpts)) {
            return null;
        }
        return new AssumptionInlineInfo(invoke, concrete, takenAssumption);
    }

    public InlineInfo getExactInlineInfo(Invoke invoke, OptimisticOptimizations optimisticOpts, ResolvedJavaMethod targetMethod) {
        assert !targetMethod.isAbstract();
        if (!InliningUtil.checkTargetConditions(this, context.getReplacements(), invoke, targetMethod, optimisticOpts)) {
            return null;
        }
        return new ExactInlineInfo(invoke, targetMethod);
    }

    private void doInline(CallsiteHolder callerCallsiteHolder, MethodInvocation calleeInfo, Assumptions callerAssumptions) {
        StructuredGraph callerGraph = callerCallsiteHolder.graph();
        Graph.Mark markBeforeInlining = callerGraph.getMark();
        InlineInfo callee = calleeInfo.callee();
        try {
            try (Debug.Scope scope = Debug.scope("doInline", callerGraph)) {
                List<Node> invokeUsages = callee.invoke().asNode().usages().snapshot();
                callee.inline(new Providers(context), callerAssumptions);
                callerAssumptions.record(calleeInfo.assumptions());
                metricInliningRuns.increment();
                Debug.dump(callerGraph, "after %s", callee);

                if (OptCanonicalizer.getValue()) {
                    Graph.Mark markBeforeCanonicalization = callerGraph.getMark();
                    canonicalizer.applyIncremental(callerGraph, context, invokeUsages, markBeforeInlining);

                    // process invokes that are possibly created during canonicalization
                    for (Node newNode : callerGraph.getNewNodes(markBeforeCanonicalization)) {
                        if (newNode instanceof Invoke) {
                            callerCallsiteHolder.pushInvoke((Invoke) newNode);
                        }
                    }
                }

                callerCallsiteHolder.computeProbabilities();

                metricInliningPerformed.increment();
            }
        } catch (BailoutException bailout) {
            throw bailout;
        } catch (AssertionError | RuntimeException e) {
            throw new GraalInternalError(e).addContext(callee.toString());
        } catch (GraalInternalError e) {
            throw e.addContext(callee.toString());
        }
    }

    /**
     * @return true iff inlining was actually performed
     */
    private boolean tryToInline(CallsiteHolder callerCallsiteHolder, MethodInvocation calleeInfo, MethodInvocation parentInvocation, int inliningDepth) {
        InlineInfo callee = calleeInfo.callee();
        Assumptions callerAssumptions = parentInvocation.assumptions();
        metricInliningConsidered.increment();

        if (inliningPolicy.isWorthInlining(probabilities, context.getReplacements(), callee, inliningDepth, calleeInfo.probability(), calleeInfo.relevance(), true)) {
            doInline(callerCallsiteHolder, calleeInfo, callerAssumptions);
            return true;
        }

        if (context.getOptimisticOptimizations().devirtualizeInvokes()) {
            callee.tryToDevirtualizeInvoke(context.getMetaAccess(), callerAssumptions);
        }

        return false;
    }

    /**
     * Process the next invoke and enqueue all its graphs for processing.
     */
    void processNextInvoke() {
        CallsiteHolder callsiteHolder = currentGraph();
        Invoke invoke = callsiteHolder.popInvoke();
        MethodInvocation callerInvocation = currentInvocation();
        Assumptions parentAssumptions = callerInvocation.assumptions();
        InlineInfo info = getInlineInfo(invoke, parentAssumptions);

        if (info != null) {
            double invokeProbability = callsiteHolder.invokeProbability(invoke);
            double invokeRelevance = callsiteHolder.invokeRelevance(invoke);
            MethodInvocation calleeInvocation = pushInvocation(info, parentAssumptions, invokeProbability, invokeRelevance);

            for (int i = 0; i < info.numberOfMethods(); i++) {
                InliningUtil.Inlineable elem = DepthSearchUtil.getInlineableElement(info.methodAt(i), info.invoke(), context.replaceAssumptions(calleeInvocation.assumptions()), canonicalizer);
                info.setInlinableElement(i, elem);
                if (elem instanceof InliningUtil.InlineableGraph) {
                    pushGraph(((InliningUtil.InlineableGraph) elem).getGraph(), invokeProbability * info.probabilityAt(i), invokeRelevance * info.relevanceAt(i));
                } else {
                    assert elem instanceof InliningUtil.InlineableMacroNode;
                    pushDummyGraph();
                }
            }
        }
    }

    public int graphCount() {
        return graphQueue.size();
    }

    private void pushGraph(StructuredGraph graph, double probability, double relevance) {
        assert graph != null;
        assert !contains(graph);
        graphQueue.push(new CallsiteHolder(graph, probability, relevance));
        assert graphQueue.size() <= maxGraphs;
    }

    private void pushDummyGraph() {
        graphQueue.push(DUMMY_CALLSITE_HOLDER);
    }

    public boolean hasUnprocessedGraphs() {
        return !graphQueue.isEmpty();
    }

    public CallsiteHolder currentGraph() {
        return graphQueue.peek();
    }

    public void popGraph() {
        graphQueue.pop();
        assert graphQueue.size() <= maxGraphs;
    }

    public void popGraphs(int count) {
        assert count >= 0;
        for (int i = 0; i < count; i++) {
            graphQueue.pop();
        }
    }

    private static final Object[] NO_CONTEXT = {};

    /**
     * Gets the call hierarchy of this inlining from outer most call to inner most callee.
     */
    public Object[] inliningContext() {
        if (!Debug.isDumpEnabled()) {
            return NO_CONTEXT;
        }
        Object[] result = new Object[graphQueue.size()];
        int i = 0;
        for (CallsiteHolder g : graphQueue) {
            result[i++] = g.method();
        }
        return result;
    }

    public MethodInvocation currentInvocation() {
        return invocationQueue.peekFirst();
    }

    private MethodInvocation pushInvocation(InlineInfo info, Assumptions assumptions, double probability, double relevance) {
        MethodInvocation methodInvocation = new MethodInvocation(info, new Assumptions(assumptions.useOptimisticAssumptions()), probability, relevance);
        invocationQueue.addFirst(methodInvocation);
        maxGraphs += info.numberOfMethods();
        assert graphQueue.size() <= maxGraphs;
        return methodInvocation;
    }

    public void popInvocation() {
        maxGraphs -= invocationQueue.peekFirst().callee().numberOfMethods();
        assert graphQueue.size() <= maxGraphs;
        invocationQueue.removeFirst();
    }

    public int countRecursiveInlining(ResolvedJavaMethod method) {
        int count = 0;
        for (CallsiteHolder callsiteHolder : graphQueue) {
            if (method.equals(callsiteHolder.method())) {
                count++;
            }
        }
        return count;
    }

    public int inliningDepth() {
        assert invocationQueue.size() > 0;
        return invocationQueue.size() - 1;
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder("Invocations: ");

        for (MethodInvocation invocation : invocationQueue) {
            if (invocation.callee() != null) {
                result.append(invocation.callee().numberOfMethods());
                result.append("x ");
                result.append(invocation.callee().invoke());
                result.append("; ");
            }
        }

        result.append("\nGraphs: ");
        for (CallsiteHolder graph : graphQueue) {
            result.append(graph.graph());
            result.append("; ");
        }

        return result.toString();
    }

    private boolean contains(StructuredGraph graph) {
        for (CallsiteHolder info : graphQueue) {
            if (info.graph() == graph) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true iff inlining was actually performed
     */
    public boolean moveForward() {

        final MethodInvocation currentInvocation = currentInvocation();

        final boolean backtrack = (!currentInvocation.isRoot() && !inliningPolicy.isWorthInlining(probabilities, context.getReplacements(), currentInvocation.callee(), inliningDepth(),
                        currentInvocation.probability(), currentInvocation.relevance(), false));
        if (backtrack) {
            int remainingGraphs = currentInvocation.totalGraphs() - currentInvocation.processedGraphs();
            assert remainingGraphs > 0;
            popGraphs(remainingGraphs);
            popInvocation();
            return false;
        }

        final boolean delve = currentGraph().hasRemainingInvokes() && inliningPolicy.continueInlining(currentGraph().graph());
        if (delve) {
            processNextInvoke();
            return false;
        }

        popGraph();
        if (currentInvocation.isRoot()) {
            return false;
        }

        // try to inline
        assert currentInvocation.callee().invoke().asNode().isAlive();
        currentInvocation.incrementProcessedGraphs();
        if (currentInvocation.processedGraphs() == currentInvocation.totalGraphs()) {
            popInvocation();
            final MethodInvocation parentInvoke = currentInvocation();
            try (Debug.Scope s = Debug.scope("Inlining", inliningContext())) {
                return tryToInline(currentGraph(), currentInvocation, parentInvoke, inliningDepth() + 1);
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
        }

        return false;
    }
}
