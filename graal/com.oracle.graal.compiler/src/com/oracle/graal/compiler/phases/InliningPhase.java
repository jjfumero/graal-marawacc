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
package com.oracle.graal.compiler.phases;

import java.util.*;

import com.oracle.max.cri.ci.*;
import com.oracle.max.cri.ri.*;
import com.oracle.graal.compiler.*;
import com.oracle.graal.compiler.phases.PhasePlan.PhasePosition;
import com.oracle.graal.compiler.util.*;
import com.oracle.graal.compiler.util.InliningUtil.InlineInfo;
import com.oracle.graal.compiler.util.InliningUtil.InliningCallback;
import com.oracle.graal.cri.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.internal.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;


public class InliningPhase extends Phase implements InliningCallback {
    /*
     * - Detect method which only call another method with some parameters set to constants: void foo(a) -> void foo(a, b) -> void foo(a, b, c) ...
     *   These should not be taken into account when determining inlining depth.
     * - honor the result of overrideInliningDecision(0, caller, invoke.bci, method, true);
     */

    private final CiTarget target;
    private final GraalRuntime runtime;

    private final Collection<? extends Invoke> hints;

    private final PriorityQueue<InlineInfo> inlineCandidates = new PriorityQueue<>();
    private CiAssumptions assumptions;

    private final PhasePlan plan;
    private final boolean allowTypeCheckedInlining;
    private final WeightComputationPolicy weightComputationPolicy;
    private final InliningPolicy inliningPolicy;

    // Metrics
    private static final DebugMetric metricInliningPerformed = Debug.metric("InliningPerformed");
    private static final DebugMetric metricInliningConsidered = Debug.metric("InliningConsidered");
    private static final DebugMetric metricInliningStoppedByMaxDesiredSize = Debug.metric("InliningStoppedByMaxDesiredSize");

    public InliningPhase(CiTarget target, GraalRuntime runtime, Collection<? extends Invoke> hints, CiAssumptions assumptions, PhasePlan plan, ProfilingInfoConfiguration profilingInfoConfig) {
        this.target = target;
        this.runtime = runtime;
        this.hints = hints;
        this.assumptions = assumptions;
        this.plan = plan;
        this.allowTypeCheckedInlining = profilingInfoConfig.useTypeProfile();
        this.weightComputationPolicy = createWeightComputationPolicy();
        this.inliningPolicy = createInliningPolicy();
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void run(StructuredGraph graph) {
        graph.createNodeMap();

        if (hints != null) {
            scanInvokes((Iterable<? extends Node>) Util.uncheckedCast(this.hints), -1, graph);
        } else {
            scanInvokes(graph.getNodes(InvokeNode.class), 0, graph);
            scanInvokes(graph.getNodes(InvokeWithExceptionNode.class), 0, graph);
        }

        while (!inlineCandidates.isEmpty() && graph.getNodeCount() < GraalOptions.MaximumDesiredSize) {
            InlineInfo info = inlineCandidates.remove();
            if (info.invoke.node().isAlive() && inliningPolicy.isWorthInlining(graph, info)) {
                Iterable<Node> newNodes = null;
                try {
                    info.inline(graph, runtime, this);
                    Debug.dump(graph, "after %s", info);
                    // get the new nodes here, the canonicalizer phase will reset the mark
                    newNodes = graph.getNewNodes();
                    if (GraalOptions.OptCanonicalizer) {
                        new CanonicalizerPhase(target, runtime, true, assumptions).apply(graph);
                    }
                    if (GraalOptions.Intrinsify) {
                        new IntrinsificationPhase(runtime).apply(graph);
                    }
                    metricInliningPerformed.increment();
                } catch (CiBailout bailout) {
                    // TODO determine if we should really bail out of the whole compilation.
                    throw bailout;
                } catch (AssertionError e) {
                    throw new GraalInternalError(e).addContext(info.toString());
                } catch (RuntimeException e) {
                    throw new GraalInternalError(e).addContext(info.toString());
                } catch (GraalInternalError e) {
                    throw e.addContext(info.toString());
                }

                if (newNodes != null && info.level < GraalOptions.MaximumInlineLevel) {
                    scanInvokes(newNodes, info.level + 1, graph);
                }
            }
        }

        if (GraalOptions.Debug && graph.getNodeCount() >= GraalOptions.MaximumDesiredSize) {
            Debug.log("inlining cut off by MaximumDesiredSize");
            metricInliningStoppedByMaxDesiredSize.increment();
        }
    }

    private void scanInvokes(Iterable<? extends Node> newNodes, int level, StructuredGraph graph) {
        graph.mark();
        for (Node node : newNodes) {
            if (node != null) {
                if (node instanceof Invoke) {
                    Invoke invoke = (Invoke) node;
                    scanInvoke(invoke, level);
                }
                for (Node usage : node.usages().filterInterface(Invoke.class).snapshot()) {
                    scanInvoke((Invoke) usage, level);
                }
            }
        }
    }

    private void scanInvoke(Invoke invoke, int level) {
        InlineInfo info = InliningUtil.getInlineInfo(invoke, level >= 0 ? level : computeInliningLevel(invoke), runtime, assumptions, this, allowTypeCheckedInlining);
        if (info != null) {
            assert level == -1 || computeInliningLevel(invoke) == level : "outer FramesStates must match inlining level";
            metricInliningConsidered.increment();
            inlineCandidates.add(info);
        }
    }

    public static final Map<RiMethod, Integer> parsedMethods = new HashMap<>();

    @Override
    public StructuredGraph buildGraph(RiResolvedMethod method) {
        StructuredGraph newGraph = new StructuredGraph(method);

        if (plan != null) {
            plan.runPhases(PhasePosition.AFTER_PARSING, newGraph);
        }
        assert newGraph.start().next() != null : "graph needs to be populated during PhasePosition.AFTER_PARSING";

        if (GraalOptions.ProbabilityAnalysis) {
            new DeadCodeEliminationPhase().apply(newGraph);
            new ComputeProbabilityPhase().apply(newGraph);
        }
        if (GraalOptions.OptCanonicalizer) {
            new CanonicalizerPhase(target, runtime, assumptions).apply(newGraph);
        }
        return newGraph;
    }

    @Override
    public double inliningWeight(RiResolvedMethod caller, RiResolvedMethod method, Invoke invoke) {
        boolean preferred = hints != null && hints.contains(invoke);
        return weightComputationPolicy.computeWeight(caller, method, invoke, preferred);
    }

    public static int graphComplexity(StructuredGraph graph) {
        int result = 0;
        for (Node node : graph.getNodes()) {
            if (node instanceof ConstantNode || node instanceof LocalNode || node instanceof BeginNode || node instanceof ReturnNode || node instanceof UnwindNode) {
                result += 0;
            } else if (node instanceof PhiNode) {
                result += 5;
            } else if (node instanceof MergeNode || node instanceof Invoke || node instanceof LoopEndNode || node instanceof EndNode) {
                result += 0;
            } else if (node instanceof ControlSplitNode) {
                result += ((ControlSplitNode) node).blockSuccessorCount();
            } else {
                result += 1;
            }
        }
        return Math.max(1, result);
    }


    @Override
    public void recordConcreteMethodAssumption(RiResolvedMethod method, RiResolvedType context, RiResolvedMethod impl) {
        assumptions.recordConcreteMethod(method, context, impl);
    }

    @Override
    public void recordMethodContentsAssumption(RiResolvedMethod method) {
        if (assumptions != null) {
            assumptions.recordMethodContents(method);
        }
    }

    private static int computeInliningLevel(Invoke invoke) {
        int count = 0;
        FrameState curState = invoke.stateAfter();
        while (curState != null) {
            count++;
            curState = curState.outerFrameState();
        }
        return count - 1;
    }

    private static InliningPolicy createInliningPolicy() {
        switch(GraalOptions.InliningPolicy) {
            case 0: return new WeightBasedInliningPolicy();
            case 1: return new C1StaticSizeBasedInliningPolicy();
            case 2: return new MinimumCodeSizeBasedInliningPolicy();
            case 3: return new DynamicSizeBasedInliningPolicy();
            case 4: return new GreedySizeBasedInliningPolicy();
            default:
                GraalInternalError.shouldNotReachHere();
                return null;
        }
    }

    private WeightComputationPolicy createWeightComputationPolicy() {
        switch(GraalOptions.WeightComputationPolicy) {
            case 0: return new ExecutionCountBasedWeightComputationPolicy();
            case 1: return new BytecodeSizeBasedWeightComputationPolicy();
            case 2: return new ComplexityBasedWeightComputationPolicy();
            default:
                GraalInternalError.shouldNotReachHere();
                return null;
        }
    }

    private interface InliningPolicy {
        boolean isWorthInlining(StructuredGraph callerGraph, InlineInfo info);
    }

    private static class WeightBasedInliningPolicy implements InliningPolicy {
        @Override
        public boolean isWorthInlining(StructuredGraph callerGraph, InlineInfo info) {
            if (!checkCompiledCodeSize(info)) {
                return false;
            }

            double penalty = Math.pow(GraalOptions.InliningSizePenaltyExp, callerGraph.getNodeCount() / (double) GraalOptions.MaximumDesiredSize) / GraalOptions.InliningSizePenaltyExp;
            if (info.weight > GraalOptions.MaximumInlineWeight / (1 + penalty * GraalOptions.InliningSizePenalty)) {
                Debug.log("not inlining (cut off by weight %e): %s", info.weight, info);
                return false;
            }

            Debug.log("inlining (weight %f): %s", info.weight, info);
            return true;
        }
    }

    private static class C1StaticSizeBasedInliningPolicy implements InliningPolicy {
        @Override
        public boolean isWorthInlining(StructuredGraph callerGraph, InlineInfo info) {
            double maxSize = Math.max(GraalOptions.MaximumTrivialSize, Math.pow(GraalOptions.NestedInliningSizeRatio, info.level) * GraalOptions.MaximumInlineSize);
            return decideSizeBasedInlining(info, maxSize);
        }
    }

    private static class MinimumCodeSizeBasedInliningPolicy implements InliningPolicy {
        @Override
        public boolean isWorthInlining(StructuredGraph callerGraph, InlineInfo info) {
            assert GraalOptions.ProbabilityAnalysis;
            if (!checkCompiledCodeSize(info)) {
                return false;
            }

            double inlineWeight = Math.min(GraalOptions.ProbabilityCapForInlining, info.invoke.probability());
            double maxSize = Math.pow(GraalOptions.NestedInliningSizeRatio, info.level) * GraalOptions.MaximumInlineSize * inlineWeight;
            maxSize = Math.max(GraalOptions.MaximumTrivialSize, maxSize);

            return decideSizeBasedInlining(info, maxSize);
        }
    }

    private static class DynamicSizeBasedInliningPolicy implements InliningPolicy {
        @Override
        public boolean isWorthInlining(StructuredGraph callerGraph, InlineInfo info) {
            assert GraalOptions.ProbabilityAnalysis;
            if (!checkCompiledCodeSize(info)) {
                return false;
            }

            double inlineBoost = Math.min(GraalOptions.ProbabilityCapForInlining, info.invoke.probability()) + Math.log10(Math.max(1, info.invoke.probability() - GraalOptions.ProbabilityCapForInlining + 1));
            double maxSize = Math.pow(GraalOptions.NestedInliningSizeRatio, info.level) * GraalOptions.MaximumInlineSize;
            maxSize = maxSize + maxSize * inlineBoost;
            maxSize = Math.min(GraalOptions.MaximumGreedyInlineSize, Math.max(GraalOptions.MaximumTrivialSize, maxSize));

            return decideSizeBasedInlining(info, maxSize);
        }
    }

    private static class GreedySizeBasedInliningPolicy implements InliningPolicy {
        @Override
        public boolean isWorthInlining(StructuredGraph callerGraph, InlineInfo info) {
            assert GraalOptions.ProbabilityAnalysis;
            if (!checkCompiledCodeSize(info)) {
                return false;
            }

            double maxSize = GraalOptions.MaximumGreedyInlineSize;
            if (GraalOptions.InliningBonusPerTransferredValue != 0) {
                RiSignature signature = info.invoke.callTarget().targetMethod().signature();
                int transferredValues = signature.argumentCount(true);
                if (signature.returnKind(false) != CiKind.Void) {
                    transferredValues++;
                }
                maxSize += transferredValues * GraalOptions.InliningBonusPerTransferredValue;
            }

            double inlineRatio = Math.min(GraalOptions.ProbabilityCapForInlining, info.invoke.probability());
            maxSize = Math.pow(GraalOptions.NestedInliningSizeRatio, info.level) * maxSize * inlineRatio;
            maxSize = Math.max(maxSize, GraalOptions.MaximumTrivialSize);

            return decideSizeBasedInlining(info, maxSize);
        }
    }

    private static boolean decideSizeBasedInlining(InlineInfo info, double maxSize) {
        boolean success = info.weight <= maxSize;
        if (DebugScope.getInstance().isLogEnabled()) {
            String formatterString = success ? "inlining invoke at %s@%d (size %f <= %f): %s" : "not inlining invoke at %s@%d (too large %f > %f): %s";
            Debug.log(formatterString, CiUtil.format("%H.%n(%p):%r", info.invoke.stateAfter().method()), info.invoke.bci(), info.weight, maxSize, info);
        }
        return success;
    }

    private static boolean checkCompiledCodeSize(InlineInfo info) {
        if (GraalOptions.SmallCompiledCodeSize >= 0 && info.compiledCodeSize() > GraalOptions.SmallCompiledCodeSize) {
            Debug.log("not inlining invoke at %s@%d (CompiledCodeSize %d > %d): %s", CiUtil.format("%H.%n(%p):%r", info.invoke.stateAfter().method()), info.invoke.bci(), info.compiledCodeSize(), GraalOptions.SmallCompiledCodeSize, info);
            return false;
        }
        return true;
    }


    private interface WeightComputationPolicy {
        double computeWeight(RiResolvedMethod caller, RiResolvedMethod method, Invoke invoke, boolean preferredInvoke);
    }

    private class ExecutionCountBasedWeightComputationPolicy implements WeightComputationPolicy {
        @Override
        public double computeWeight(RiResolvedMethod caller, RiResolvedMethod method, Invoke invoke, boolean preferredInvoke) {
            double ratio;
            if (preferredInvoke) {
                ratio = 1000000;
            } else {
                if (GraalOptions.ProbabilityAnalysis) {
                    ratio = invoke.node().probability();
                } else {
                    RiProfilingInfo profilingInfo = method.profilingInfo();
                    int executionCount = profilingInfo.getExecutionCount(invoke.bci());
                    if (executionCount > 0) {
                        RiResolvedMethod parent = invoke.stateAfter().method();
                        ratio = executionCount / (float) parent.invocationCount();
                    } else {
                        ratio = 1;
                    }
                }
            }

            final double normalSize;
            // TODO (lstadler) get rid of this magic, it's here to emulate the old behavior for the time being
            if (ratio < 0.01) {
                ratio = 0.01;
            }
            if (ratio < 0.5) {
                normalSize = 10 * ratio / 0.5;
            } else if (ratio < 2) {
                normalSize = 10 + (35 - 10) * (ratio - 0.5) / 1.5;
            } else if (ratio < 20) {
                normalSize = 35;
            } else if (ratio < 40) {
                normalSize = 35 + (350 - 35) * (ratio - 20) / 20;
            } else {
                normalSize = 350;
            }

            int count;
            if (GraalOptions.ParseBeforeInlining) {
                if (!parsedMethods.containsKey(method)) {
                    StructuredGraph newGraph = new StructuredGraph(method);
                    if (plan != null) {
                        plan.runPhases(PhasePosition.AFTER_PARSING, newGraph);
                    }
                    if (GraalOptions.OptCanonicalizer) {
                        new CanonicalizerPhase(target, runtime, assumptions).apply(newGraph);
                    }
                    count = graphComplexity(newGraph);
                    parsedMethods.put(method, count);
                } else {
                    count = parsedMethods.get(method);
                }
            } else {
                count = method.codeSize();
            }

            return count / normalSize;
        }
    }

    private static class BytecodeSizeBasedWeightComputationPolicy implements WeightComputationPolicy {
        @Override
        public double computeWeight(RiResolvedMethod caller, RiResolvedMethod method, Invoke invoke, boolean preferredInvoke) {
            double codeSize = method.codeSize();
            if (preferredInvoke) {
                codeSize = codeSize / GraalOptions.BoostInliningForEscapeAnalysis;
            }
            return codeSize;
        }
    }

    private static class ComplexityBasedWeightComputationPolicy implements WeightComputationPolicy {
        @Override
        public double computeWeight(RiResolvedMethod caller, RiResolvedMethod method, Invoke invoke, boolean preferredInvoke) {
            double complexity = method.compilationComplexity();
            if (preferredInvoke) {
                complexity = complexity / GraalOptions.BoostInliningForEscapeAnalysis;
            }
            return complexity;
        }
    }
}
