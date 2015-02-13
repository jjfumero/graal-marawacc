/*
 * Copyright (c) 2012, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.phases.common.inlining.info.elem;

import static com.oracle.graal.compiler.common.GraalOptions.*;
import static com.oracle.graal.phases.common.DeadCodeEliminationPhase.Optionality.*;

import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.StructuredGraph.AllowAssumptions;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.phases.common.inlining.*;
import com.oracle.graal.phases.graph.*;
import com.oracle.graal.phases.tiers.*;

/**
 * <p>
 * Represents a feasible concrete target for inlining, whose graph has been copied already and thus
 * can be modified without affecting the original (usually cached) version.
 * </p>
 *
 * <p>
 * Instances of this class don't make sense in isolation but as part of an
 * {@link com.oracle.graal.phases.common.inlining.info.InlineInfo InlineInfo}.
 * </p>
 *
 * @see com.oracle.graal.phases.common.inlining.walker.InliningData#moveForward()
 * @see com.oracle.graal.phases.common.inlining.walker.CallsiteHolderExplorable
 */
public class InlineableGraph implements Inlineable {

    private final StructuredGraph graph;

    private FixedNodeProbabilityCache probabilites = new FixedNodeProbabilityCache();

    public InlineableGraph(final ResolvedJavaMethod method, final Invoke invoke, final HighTierContext context, CanonicalizerPhase canonicalizer) {
        StructuredGraph original = getOriginalGraph(method, context, canonicalizer, invoke.asNode().graph());
        // TODO copying the graph is only necessary if it is modified or if it contains any invokes
        this.graph = original.copy();
        specializeGraphToArguments(invoke, context, canonicalizer);
    }

    /**
     * This method looks up in a cache the graph for the argument, if not found bytecode is parsed.
     * The graph thus obtained is returned, ie the caller is responsible for cloning before
     * modification.
     */
    private static StructuredGraph getOriginalGraph(final ResolvedJavaMethod method, final HighTierContext context, CanonicalizerPhase canonicalizer, StructuredGraph caller) {
        StructuredGraph result = InliningUtil.getIntrinsicGraph(context.getReplacements(), method);
        if (result != null) {
            return result;
        }
        result = getCachedGraph(method, context);
        if (result != null) {
            return result;
        }
        return parseBytecodes(method, context, canonicalizer, caller);
    }

    /**
     * @return true iff one or more parameters <code>newGraph</code> were specialized to account for
     *         a constant argument, or an argument with a more specific stamp.
     */
    private boolean specializeGraphToArguments(final Invoke invoke, final HighTierContext context, CanonicalizerPhase canonicalizer) {
        try (Debug.Scope s = Debug.scope("InlineGraph", graph)) {

            ArrayList<Node> parameterUsages = replaceParamsWithMoreInformativeArguments(invoke, context);
            if (parameterUsages != null && OptCanonicalizer.getValue()) {
                assert !parameterUsages.isEmpty() : "The caller didn't have more information about arguments after all";
                canonicalizer.applyIncremental(graph, context, parameterUsages);
                return true;
            } else {
                // TODO (chaeubl): if args are not more concrete, inlining should be avoided
                // in most cases or we could at least use the previous graph size + invoke
                // probability to check the inlining
                return false;
            }

        } catch (Throwable e) {
            throw Debug.handle(e);
        }
    }

    private static boolean isArgMoreInformativeThanParam(ValueNode arg, ParameterNode param) {
        return arg.isConstant() || canStampBeImproved(arg, param);
    }

    private static boolean canStampBeImproved(ValueNode arg, ParameterNode param) {
        return improvedStamp(arg, param) != null;
    }

    private static Stamp improvedStamp(ValueNode arg, ParameterNode param) {
        Stamp joinedStamp = param.stamp().join(arg.stamp());
        if (joinedStamp == null || joinedStamp.equals(param.stamp())) {
            return null;
        }
        return joinedStamp;
    }

    /**
     * This method detects:
     * <ul>
     * <li>
     * constants among the arguments to the <code>invoke</code></li>
     * <li>
     * arguments with more precise type than that declared by the corresponding parameter</li>
     * </ul>
     *
     * <p>
     * The corresponding parameters are updated to reflect the above information. Before doing so,
     * their usages are added to <code>parameterUsages</code> for later incremental
     * canonicalization.
     * </p>
     *
     * @return null if no incremental canonicalization is need, a list of nodes for such
     *         canonicalization otherwise.
     */
    private ArrayList<Node> replaceParamsWithMoreInformativeArguments(final Invoke invoke, final HighTierContext context) {
        NodeInputList<ValueNode> args = invoke.callTarget().arguments();
        ArrayList<Node> parameterUsages = null;
        List<ParameterNode> params = graph.getNodes(ParameterNode.class).snapshot();
        assert params.size() <= args.size();
        /*
         * param-nodes that aren't used (eg, as a result of canonicalization) don't occur in
         * `params`. Thus, in general, the sizes of `params` and `args` don't always match. Still,
         * it's always possible to pair a param-node with its corresponding arg-node using
         * param.index() as index into `args`.
         */
        for (ParameterNode param : params) {
            if (param.usages().isNotEmpty()) {
                ValueNode arg = args.get(param.index());
                if (arg.isConstant()) {
                    Constant constant = arg.asConstant();
                    parameterUsages = trackParameterUsages(param, parameterUsages);
                    // collect param usages before replacing the param
                    graph.replaceFloating(param, ConstantNode.forConstant(arg.stamp(), constant, context.getMetaAccess(), graph));
                    // param-node gone, leaving a gap in the sequence given by param.index()
                } else {
                    Stamp impro = improvedStamp(arg, param);
                    if (impro != null) {
                        param.setStamp(impro);
                        parameterUsages = trackParameterUsages(param, parameterUsages);
                    } else {
                        assert !isArgMoreInformativeThanParam(arg, param);
                    }
                }
            }
        }
        assert (parameterUsages == null) || (!parameterUsages.isEmpty());
        return parameterUsages;
    }

    private static ArrayList<Node> trackParameterUsages(ParameterNode param, ArrayList<Node> parameterUsages) {
        ArrayList<Node> result = (parameterUsages == null) ? new ArrayList<>() : parameterUsages;
        param.usages().snapshotTo(result);
        return result;
    }

    private static StructuredGraph getCachedGraph(ResolvedJavaMethod method, HighTierContext context) {
        if (context.getGraphCache() != null) {
            StructuredGraph cachedGraph = context.getGraphCache().get(method);
            if (cachedGraph != null) {
                // TODO: check that cachedGraph.getAssumptions() are still valid
                // instead of waiting for code installation to do it.
                return cachedGraph;
            }
        }
        return null;
    }

    /**
     * This method builds the IR nodes for the given <code>method</code> and canonicalizes them.
     * Provided profiling info is mature, the resulting graph is cached. The caller is responsible
     * for cloning before modification.</p>
     */
    private static StructuredGraph parseBytecodes(ResolvedJavaMethod method, HighTierContext context, CanonicalizerPhase canonicalizer, StructuredGraph caller) {
        StructuredGraph newGraph = new StructuredGraph(method, AllowAssumptions.from(caller.getAssumptions() != null));
        try (Debug.Scope s = Debug.scope("InlineGraph", newGraph)) {
            if (!caller.isInlinedMethodRecordingEnabled()) {
                // Don't record inlined methods in the callee if
                // the caller doesn't want them. This decision is
                // preserved in the graph cache (if used) which is
                // ok since the graph cache is compilation local.
                newGraph.disableInlinedMethodRecording();
            }
            if (context.getGraphBuilderSuite() != null) {
                context.getGraphBuilderSuite().apply(newGraph, context);
            }
            assert newGraph.start().next() != null : "graph needs to be populated by the GraphBuilderSuite";

            new DeadCodeEliminationPhase(Optional).apply(newGraph);

            if (OptCanonicalizer.getValue()) {
                canonicalizer.apply(newGraph, context);
            }

            if (context.getGraphCache() != null) {
                context.getGraphCache().put(newGraph.method(), newGraph);
            }
            return newGraph;
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
    }

    @Override
    public int getNodeCount() {
        return graph.getNodeCount();
    }

    @Override
    public Iterable<Invoke> getInvokes() {
        return graph.getInvokes();
    }

    @Override
    public double getProbability(Invoke invoke) {
        return probabilites.applyAsDouble(invoke.asNode());
    }

    public StructuredGraph getGraph() {
        return graph;
    }
}
