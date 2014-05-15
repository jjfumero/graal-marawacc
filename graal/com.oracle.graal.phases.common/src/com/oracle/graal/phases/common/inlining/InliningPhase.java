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
package com.oracle.graal.phases.common.inlining;

import static com.oracle.graal.compiler.common.GraalOptions.*;

import java.util.*;
import java.util.function.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.graph.Graph.Mark;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.options.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.phases.common.inlining.info.InlineInfo;
import com.oracle.graal.phases.common.inlining.InliningUtil.Inlineable;
import com.oracle.graal.phases.common.inlining.InliningUtil.InlineableGraph;
import com.oracle.graal.phases.common.inlining.InliningUtil.InlineableMacroNode;
import com.oracle.graal.phases.common.inlining.policy.GreedyInliningPolicy;
import com.oracle.graal.phases.common.inlining.policy.InliningPolicy;
import com.oracle.graal.phases.graph.*;
import com.oracle.graal.phases.tiers.*;
import com.oracle.graal.phases.util.*;

public class InliningPhase extends AbstractInliningPhase {

    public static class Options {

        // @formatter:off
        @Option(help = "Unconditionally inline intrinsics")
        public static final OptionValue<Boolean> AlwaysInlineIntrinsics = new OptionValue<>(false);
        // @formatter:on
    }

    private final InliningPolicy inliningPolicy;
    private final CanonicalizerPhase canonicalizer;

    private int inliningCount;
    private int maxMethodPerInlining = Integer.MAX_VALUE;

    public InliningPhase(CanonicalizerPhase canonicalizer) {
        this(new GreedyInliningPolicy(null), canonicalizer);
    }

    public InliningPhase(Map<Invoke, Double> hints, CanonicalizerPhase canonicalizer) {
        this(new GreedyInliningPolicy(hints), canonicalizer);
    }

    public InliningPhase(InliningPolicy policy, CanonicalizerPhase canonicalizer) {
        this.inliningPolicy = policy;
        this.canonicalizer = canonicalizer;
    }

    public void setMaxMethodsPerInlining(int max) {
        maxMethodPerInlining = max;
    }

    public int getInliningCount() {
        return inliningCount;
    }

    /**
     * <p>
     * The space of inlining decisions is explored depth-first with the help of a stack realized by
     * {@link InliningData}. At any point in time, its topmost element consist of:
     * <ul>
     * <li>
     * one or more {@link CallsiteHolder}s of inlining candidates, all of them corresponding to a
     * single callsite (details below). For example, "exact inline" leads to a single candidate.</li>
     * <li>
     * the callsite (for the targets above) is tracked as a {@link MethodInvocation}. The difference
     * between {@link MethodInvocation#totalGraphs()} and {@link MethodInvocation#processedGraphs()}
     * indicates the topmost {@link CallsiteHolder}s that might be delved-into to explore inlining
     * opportunities.</li>
     * </ul>
     * </p>
     *
     * <p>
     * The bottom-most element in the stack consists of:
     * <ul>
     * <li>
     * a single {@link CallsiteHolder} (the root one, for the method on which inlining was called)</li>
     * <li>
     * a single {@link MethodInvocation} (the {@link MethodInvocation#isRoot} one, ie the unknown
     * caller of the root graph)</li>
     * </ul>
     *
     * </p>
     *
     * <p>
     * The stack grows and shrinks as choices are made among the alternatives below:
     * <ol>
     * <li>
     * not worth inlining: pop any remaining graphs not yet delved into, pop the current invocation.
     * </li>
     * <li>
     * process next invoke: delve into one of the callsites hosted in the current candidate graph,
     * determine whether any inlining should be performed in it</li>
     * <li>
     * try to inline: move past the current inlining candidate (remove it from the topmost element).
     * If that was the last one then try to inline the callsite that is (still) in the topmost
     * element of {@link InliningData}, and then remove such callsite.</li>
     * </ol>
     * </p>
     *
     * <p>
     * Some facts about the alternatives above:
     * <ul>
     * <li>
     * the first step amounts to backtracking, the 2nd one to delving, and the 3rd one also involves
     * bakctraking (however after may-be inlining).</li>
     * <li>
     * the choice of abandon-and-backtrack or delve-into is depends on
     * {@link InliningPolicy#isWorthInlining} and {@link InliningPolicy#continueInlining}.</li>
     * <li>
     * the 3rd choice is picked when both of the previous one aren't picked</li>
     * <li>
     * as part of trying-to-inline, {@link InliningPolicy#isWorthInlining} again sees use, but
     * that's another story.</li>
     * </ul>
     * </p>
     *
     */
    @Override
    protected void run(final StructuredGraph graph, final HighTierContext context) {
        final InliningData data = new InliningData(graph, context.getAssumptions(), maxMethodPerInlining, canonicalizer);
        ToDoubleFunction<FixedNode> probabilities = new FixedNodeProbabilityCache();

        while (data.hasUnprocessedGraphs()) {
            moveForward(context, data, probabilities);
        }

        assert data.inliningDepth() == 0;
        assert data.graphCount() == 0;
    }

    private void moveForward(HighTierContext context, InliningData data, ToDoubleFunction<FixedNode> probabilities) {

        final MethodInvocation currentInvocation = data.currentInvocation();

        final boolean backtrack = (!currentInvocation.isRoot() && !inliningPolicy.isWorthInlining(probabilities, context.getReplacements(), currentInvocation.callee(), data.inliningDepth(),
                        currentInvocation.probability(), currentInvocation.relevance(), false));
        if (backtrack) {
            int remainingGraphs = currentInvocation.totalGraphs() - currentInvocation.processedGraphs();
            assert remainingGraphs > 0;
            data.popGraphs(remainingGraphs);
            data.popInvocation();
            return;
        }

        final boolean delve = data.currentGraph().hasRemainingInvokes() && inliningPolicy.continueInlining(data.currentGraph().graph());
        if (delve) {
            data.processNextInvoke(context);
            return;
        }

        data.popGraph();
        if (currentInvocation.isRoot()) {
            return;
        }

        // try to inline
        assert currentInvocation.callee().invoke().asNode().isAlive();
        currentInvocation.incrementProcessedGraphs();
        if (currentInvocation.processedGraphs() == currentInvocation.totalGraphs()) {
            data.popInvocation();
            final MethodInvocation parentInvoke = data.currentInvocation();
            try (Scope s = Debug.scope("Inlining", data.inliningContext())) {
                boolean wasInlined = InliningData.tryToInline(probabilities, data.currentGraph(), currentInvocation, parentInvoke, data.inliningDepth() + 1, context, inliningPolicy, canonicalizer);
                if (wasInlined) {
                    inliningCount++;
                }
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
        }
    }

    /**
     * Holds the data for building the callee graphs recursively: graphs and invocations (each
     * invocation can have multiple graphs).
     */
    static class InliningData {

        private static final CallsiteHolder DUMMY_CALLSITE_HOLDER = new CallsiteHolder(null, 1.0, 1.0);
        // Metrics
        private static final DebugMetric metricInliningPerformed = Debug.metric("InliningPerformed");
        private static final DebugMetric metricInliningRuns = Debug.metric("InliningRuns");
        private static final DebugMetric metricInliningConsidered = Debug.metric("InliningConsidered");

        /**
         * Call hierarchy from outer most call (i.e., compilation unit) to inner most callee.
         */
        private final ArrayDeque<CallsiteHolder> graphQueue;
        private final ArrayDeque<MethodInvocation> invocationQueue;
        private final int maxMethodPerInlining;
        private final CanonicalizerPhase canonicalizer;

        private int maxGraphs;

        public InliningData(StructuredGraph rootGraph, Assumptions rootAssumptions, int maxMethodPerInlining, CanonicalizerPhase canonicalizer) {
            assert rootGraph != null;
            this.graphQueue = new ArrayDeque<>();
            this.invocationQueue = new ArrayDeque<>();
            this.maxMethodPerInlining = maxMethodPerInlining;
            this.canonicalizer = canonicalizer;
            this.maxGraphs = 1;

            invocationQueue.push(new MethodInvocation(null, rootAssumptions, 1.0, 1.0));
            pushGraph(rootGraph, 1.0, 1.0);
        }

        private static void doInline(CallsiteHolder callerCallsiteHolder, MethodInvocation calleeInfo, Assumptions callerAssumptions, HighTierContext context, CanonicalizerPhase canonicalizer) {
            StructuredGraph callerGraph = callerCallsiteHolder.graph();
            Mark markBeforeInlining = callerGraph.getMark();
            InlineInfo callee = calleeInfo.callee();
            try {
                try (Scope scope = Debug.scope("doInline", callerGraph)) {
                    List<Node> invokeUsages = callee.invoke().asNode().usages().snapshot();
                    callee.inline(new Providers(context), callerAssumptions);
                    callerAssumptions.record(calleeInfo.assumptions());
                    metricInliningRuns.increment();
                    Debug.dump(callerGraph, "after %s", callee);

                    if (OptCanonicalizer.getValue()) {
                        Mark markBeforeCanonicalization = callerGraph.getMark();
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
        private static boolean tryToInline(ToDoubleFunction<FixedNode> probabilities, CallsiteHolder callerCallsiteHolder, MethodInvocation calleeInfo, MethodInvocation parentInvocation,
                        int inliningDepth, HighTierContext context, InliningPolicy inliningPolicy, CanonicalizerPhase canonicalizer) {
            InlineInfo callee = calleeInfo.callee();
            Assumptions callerAssumptions = parentInvocation.assumptions();
            metricInliningConsidered.increment();

            if (inliningPolicy.isWorthInlining(probabilities, context.getReplacements(), callee, inliningDepth, calleeInfo.probability(), calleeInfo.relevance(), true)) {
                doInline(callerCallsiteHolder, calleeInfo, callerAssumptions, context, canonicalizer);
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
        void processNextInvoke(HighTierContext context) {
            CallsiteHolder callsiteHolder = currentGraph();
            Invoke invoke = callsiteHolder.popInvoke();
            MethodInvocation callerInvocation = currentInvocation();
            Assumptions parentAssumptions = callerInvocation.assumptions();
            InlineInfo info = InliningUtil.getInlineInfo(this, invoke, maxMethodPerInlining, context.getReplacements(), parentAssumptions, context.getOptimisticOptimizations());

            if (info != null) {
                double invokeProbability = callsiteHolder.invokeProbability(invoke);
                double invokeRelevance = callsiteHolder.invokeRelevance(invoke);
                MethodInvocation calleeInvocation = pushInvocation(info, parentAssumptions, invokeProbability, invokeRelevance);

                for (int i = 0; i < info.numberOfMethods(); i++) {
                    Inlineable elem = DepthSearchUtil.getInlineableElement(info.methodAt(i), info.invoke(), context.replaceAssumptions(calleeInvocation.assumptions()), canonicalizer);
                    info.setInlinableElement(i, elem);
                    if (elem instanceof InlineableGraph) {
                        pushGraph(((InlineableGraph) elem).getGraph(), invokeProbability * info.probabilityAt(i), invokeRelevance * info.relevanceAt(i));
                    } else {
                        assert elem instanceof InlineableMacroNode;
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
            maxGraphs -= invocationQueue.peekFirst().callee.numberOfMethods();
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
    }

    private static class MethodInvocation {

        private final InlineInfo callee;
        private final Assumptions assumptions;
        private final double probability;
        private final double relevance;

        private int processedGraphs;

        public MethodInvocation(InlineInfo info, Assumptions assumptions, double probability, double relevance) {
            this.callee = info;
            this.assumptions = assumptions;
            this.probability = probability;
            this.relevance = relevance;
        }

        public void incrementProcessedGraphs() {
            processedGraphs++;
            assert processedGraphs <= callee.numberOfMethods();
        }

        public int processedGraphs() {
            assert processedGraphs <= callee.numberOfMethods();
            return processedGraphs;
        }

        public int totalGraphs() {
            return callee.numberOfMethods();
        }

        public InlineInfo callee() {
            return callee;
        }

        public Assumptions assumptions() {
            return assumptions;
        }

        public double probability() {
            return probability;
        }

        public double relevance() {
            return relevance;
        }

        public boolean isRoot() {
            return callee == null;
        }

        @Override
        public String toString() {
            if (isRoot()) {
                return "<root>";
            }
            CallTargetNode callTarget = callee.invoke().callTarget();
            if (callTarget instanceof MethodCallTargetNode) {
                ResolvedJavaMethod calleeMethod = ((MethodCallTargetNode) callTarget).targetMethod();
                return MetaUtil.format("Invoke#%H.%n(%p)", calleeMethod);
            } else {
                return "Invoke#" + callTarget.targetName();
            }
        }
    }

}
