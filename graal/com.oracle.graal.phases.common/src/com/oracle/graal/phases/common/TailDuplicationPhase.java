/*
 * Copyright (c) 2012, 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.phases.common;

import static com.oracle.graal.phases.GraalOptions.*;

import java.util.*;

import com.oracle.graal.debug.*;
import com.oracle.graal.graph.Graph.DuplicationReplacement;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.Graph.Mark;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.VirtualState.NodeClosure;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.nodes.virtual.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.graph.*;
import com.oracle.graal.phases.tiers.*;

/**
 * This class is a phase that looks for opportunities for tail duplication. The static method
 * {@link #tailDuplicate(MergeNode, TailDuplicationDecision, List, PhaseContext, CanonicalizerPhase)}
 * can also be used to drive tail duplication from other places, e.g., inlining.
 */
public class TailDuplicationPhase extends BasePhase<PhaseContext> {

    /*
     * Various metrics on the circumstances in which tail duplication was/wasn't performed.
     */
    private static final DebugMetric metricDuplicationConsidered = Debug.metric("DuplicationConsidered");
    private static final DebugMetric metricDuplicationPerformed = Debug.metric("DuplicationPerformed");

    private final CanonicalizerPhase canonicalizer;

    /**
     * This interface is used by tail duplication to let clients decide if tail duplication should
     * be performed.
     */
    public interface TailDuplicationDecision {

        /**
         * Queries if tail duplication should be performed at the given merge. If this method
         * returns true then the tail duplication will be performed, because all other checks have
         * happened before.
         * 
         * @param merge The merge at which tail duplication can be performed.
         * @param fixedNodeCount The size of the set of fixed nodes that forms the base for the
         *            duplicated set of nodes.
         * @return true if the tail duplication should be performed, false otherwise.
         */
        boolean doTransform(MergeNode merge, int fixedNodeCount);
    }

    /**
     * A tail duplication decision closure that employs the default algorithm: Check if there are
     * any phis on the merge whose stamps improve and that have usages within the duplicated set of
     * fixed nodes.
     */
    public static final TailDuplicationDecision DEFAULT_DECISION = new TailDuplicationDecision() {

        public boolean doTransform(MergeNode merge, int fixedNodeCount) {
            if (fixedNodeCount < TailDuplicationTrivialSize.getValue()) {
                return true;
            }
            HashSet<PhiNode> improvements = new HashSet<>();
            for (PhiNode phi : merge.phis()) {
                Stamp phiStamp = phi.stamp();
                for (ValueNode input : phi.values()) {
                    if (!input.stamp().equals(phiStamp)) {
                        improvements.add(phi);
                        break;
                    }
                }
            }
            if (improvements.isEmpty()) {
                return false;
            }
            FixedNode current = merge;
            int opportunities = 0;
            while (current instanceof FixedWithNextNode) {
                current = ((FixedWithNextNode) current).next();
                if (current instanceof VirtualizableAllocation) {
                    return false;
                }
                for (PhiNode phi : improvements) {
                    for (Node input : current.inputs()) {
                        if (input == phi) {
                            opportunities++;
                        }
                        if (input.inputs().contains(phi)) {
                            opportunities++;
                        }
                    }
                }
            }
            return opportunities > 0;
        }
    };

    /**
     * A tail duplication decision closure that always returns true.
     */
    public static final TailDuplicationDecision TRUE_DECISION = new TailDuplicationDecision() {

        @Override
        public boolean doTransform(MergeNode merge, int fixedNodeCount) {
            return true;
        }
    };

    public TailDuplicationPhase(CanonicalizerPhase canonicalizer) {
        this.canonicalizer = canonicalizer;
    }

    @Override
    protected void run(StructuredGraph graph, PhaseContext phaseContext) {
        NodesToDoubles nodeProbabilities = new ComputeProbabilityClosure(graph).apply();

        // A snapshot is taken here, so that new MergeNode instances aren't considered for tail
        // duplication.
        for (MergeNode merge : graph.getNodes(MergeNode.class).snapshot()) {
            if (!(merge instanceof LoopBeginNode) && nodeProbabilities.get(merge) >= TailDuplicationProbability.getValue()) {
                tailDuplicate(merge, DEFAULT_DECISION, null, phaseContext, canonicalizer);
            }
        }
    }

    /**
     * This method attempts to duplicate the tail of the given merge. The merge must not be a
     * {@link LoopBeginNode}. If the merge is eligible for duplication (at least one fixed node in
     * its tail, no {@link MonitorEnterNode}/ {@link MonitorExitNode}, non-null
     * {@link MergeNode#stateAfter()}) then the decision callback is used to determine whether the
     * tail duplication should actually be performed. If replacements is non-null, then this list of
     * {@link PiNode}s is used to replace one value per merge end.
     * 
     * @param merge The merge whose tail should be duplicated.
     * @param decision A callback that can make the final decision if tail duplication should occur
     *            or not.
     * @param replacements A list of {@link PiNode}s, or null. If this list is non-null then its
     *            size needs to match the merge's end count. Each entry can either be null or a
     *            {@link PiNode}, and is used to replace {@link PiNode#object()} with the
     *            {@link PiNode} in the duplicated branch that corresponds to the entry.
     * @param phaseContext
     */
    public static boolean tailDuplicate(MergeNode merge, TailDuplicationDecision decision, List<GuardedValueNode> replacements, PhaseContext phaseContext, CanonicalizerPhase canonicalizer) {
        assert !(merge instanceof LoopBeginNode);
        assert replacements == null || replacements.size() == merge.forwardEndCount();
        FixedNode fixed = merge;
        int fixedCount = 0;
        while (fixed instanceof FixedWithNextNode) {
            fixed = ((FixedWithNextNode) fixed).next();
            if (fixed instanceof CommitAllocationNode) {
                return false;
            }
            fixedCount++;
        }
        if (fixedCount > 1) {
            metricDuplicationConsidered.increment();
            if (decision.doTransform(merge, fixedCount)) {
                metricDuplicationPerformed.increment();
                new DuplicationOperation(merge, replacements, canonicalizer).duplicate(phaseContext);
                return true;
            }
        }
        return false;
    }

    /**
     * This class encapsulates one tail duplication operation on a specific {@link MergeNode}.
     */
    private static class DuplicationOperation {

        private final MergeNode merge;
        private final StructuredGraph graph;

        private final HashMap<ValueNode, PhiNode> bottomPhis = new HashMap<>();
        private final List<GuardedValueNode> replacements;

        private final CanonicalizerPhase canonicalizer;

        /**
         * Initializes the tail duplication operation without actually performing any work.
         * 
         * @param merge The merge whose tail should be duplicated.
         * @param replacements A list of replacement {@link PiNode}s, or null. If this is non-null,
         *            then the size of the list needs to match the number of end nodes at the merge.
         */
        public DuplicationOperation(MergeNode merge, List<GuardedValueNode> replacements, CanonicalizerPhase canonicalizer) {
            this.merge = merge;
            this.replacements = replacements;
            this.graph = merge.graph();
            this.canonicalizer = canonicalizer;
        }

        /**
         * Performs the actual tail duplication:
         * <ul>
         * <li>Creates a new {@link ValueAnchorNode} at the beginning of the duplicated area, an
         * transfers all dependencies from the merge to this anchor.</li>
         * <li>Determines the set of fixed nodes to be duplicated.</li>
         * <li>Creates the new merge at the bottom of the duplicated area.</li>
         * <li>Determines the complete set of duplicated nodes.</li>
         * <li>Performs the actual duplication.</li>
         * </ul>
         * 
         * @param phaseContext
         */
        private void duplicate(PhaseContext phaseContext) {
            Debug.log("tail duplication at merge %s in %s", merge, graph.method());

            Mark startMark = graph.getMark();

            ValueAnchorNode anchor = addValueAnchor();

            // determine the fixed nodes that should be duplicated (currently: all nodes up until
            // the first control
            // split, end node, deopt or return.
            ArrayList<FixedNode> fixedNodes = new ArrayList<>();
            FixedNode fixed = merge.next();
            FrameState stateAfter = merge.stateAfter();
            while (fixed instanceof FixedWithNextNode) {
                fixedNodes.add(fixed);
                if (fixed instanceof StateSplit && ((StateSplit) fixed).stateAfter() != null) {
                    stateAfter = ((StateSplit) fixed).stateAfter();
                }
                fixed = ((FixedWithNextNode) fixed).next();
            }

            AbstractEndNode endAfter = createNewMerge(fixed, stateAfter);
            MergeNode mergeAfter = endAfter.merge();
            fixedNodes.add(endAfter);
            final HashSet<Node> duplicatedNodes = buildDuplicatedNodeSet(fixedNodes, stateAfter);
            mergeAfter.clearEnds();
            expandDuplicated(duplicatedNodes, mergeAfter);

            List<AbstractEndNode> endSnapshot = merge.forwardEnds().snapshot();
            List<PhiNode> phiSnapshot = merge.phis().snapshot();

            int endIndex = 0;
            for (final AbstractEndNode forwardEnd : merge.forwardEnds()) {
                Map<Node, Node> duplicates;
                if (replacements == null || replacements.get(endIndex) == null) {
                    duplicates = graph.addDuplicates(duplicatedNodes, graph, duplicatedNodes.size(), (DuplicationReplacement) null);
                } else {
                    HashMap<Node, Node> replace = new HashMap<>();
                    replace.put(replacements.get(endIndex).object(), replacements.get(endIndex));
                    duplicates = graph.addDuplicates(duplicatedNodes, graph, duplicatedNodes.size(), replace);
                }
                for (Map.Entry<ValueNode, PhiNode> phi : bottomPhis.entrySet()) {
                    phi.getValue().initializeValueAt(merge.forwardEndIndex(forwardEnd), (ValueNode) duplicates.get(phi.getKey()));
                }
                mergeAfter.addForwardEnd((AbstractEndNode) duplicates.get(endAfter));

                // re-wire the duplicated ValueAnchorNode to the predecessor of the corresponding
                // EndNode
                FixedNode anchorDuplicate = (FixedNode) duplicates.get(anchor);
                ((FixedWithNextNode) forwardEnd.predecessor()).setNext(anchorDuplicate);
                // move dependencies on the ValueAnchorNode to the previous BeginNode
                AbstractBeginNode prevBegin = AbstractBeginNode.prevBegin(anchorDuplicate);
                anchorDuplicate.replaceAtUsages(prevBegin);

                // re-wire the phi duplicates to the correct input
                for (PhiNode phi : phiSnapshot) {
                    PhiNode phiDuplicate = (PhiNode) duplicates.get(phi);
                    phiDuplicate.replaceAtUsages(phi.valueAt(forwardEnd));
                    phiDuplicate.safeDelete();
                }
                endIndex++;
            }
            GraphUtil.killCFG(merge);
            for (AbstractEndNode forwardEnd : endSnapshot) {
                forwardEnd.safeDelete();
            }
            for (PhiNode phi : phiSnapshot) {
                // these phis should go away, but they still need to be anchored to a merge to be
                // valid...
                if (phi.isAlive()) {
                    phi.setMerge(mergeAfter);
                }
            }
            canonicalizer.applyIncremental(graph, phaseContext, startMark);
            Debug.dump(graph, "After tail duplication at %s", merge);
        }

        /**
         * Inserts a new ValueAnchorNode after the merge and transfers all dependency-usages (not
         * phis) to this ValueAnchorNode.
         * 
         * @return The new {@link ValueAnchorNode} that was created.
         */
        private ValueAnchorNode addValueAnchor() {
            ValueAnchorNode anchor = graph.add(new ValueAnchorNode(null));
            graph.addAfterFixed(merge, anchor);
            for (Node usage : merge.usages().snapshot()) {
                if (usage instanceof PhiNode && ((PhiNode) usage).merge() == merge) {
                    // nothing to do
                } else {
                    usage.replaceFirstInput(merge, anchor);
                }
            }
            return anchor;
        }

        /**
         * Given a set of fixed nodes, this method determines the set of fixed and floating nodes
         * that needs to be duplicated, i.e., all nodes that due to data flow and other dependencies
         * needs to be duplicated.
         * 
         * @param fixedNodes The set of fixed nodes that should be duplicated.
         * @param stateAfter The frame state of the merge that follows the set of fixed nodes. All
         *            {@link ValueNode}s reachable from this state are considered to be reachable
         *            from within the duplicated set of nodes.
         * @return The set of nodes that need to be duplicated.
         */
        private HashSet<Node> buildDuplicatedNodeSet(final ArrayList<FixedNode> fixedNodes, FrameState stateAfter) {
            final NodeBitMap aboveBound = graph.createNodeBitMap();
            final NodeBitMap belowBound = graph.createNodeBitMap();

            final Deque<Node> worklist = new ArrayDeque<>();

            // Build the set of nodes that have (transitive) usages within the duplicatedNodes.
            // This is achieved by iterating all nodes that are reachable via inputs from the the
            // fixed nodes.
            aboveBound.markAll(fixedNodes);
            worklist.addAll(fixedNodes);

            // the phis at the original merge should always be duplicated
            for (PhiNode phi : merge.phis()) {
                aboveBound.mark(phi);
                worklist.add(phi);
            }

            NodeClosure<Node> aboveClosure = new NodeClosure<Node>() {

                @Override
                public void apply(Node usage, Node node) {
                    if (node instanceof PhiNode && !fixedNodes.contains(((PhiNode) node).merge())) {
                        // stop iterating: phis belonging to outside merges are known to be outside.
                    } else if (node instanceof FixedNode) {
                        // stop iterating: fixed nodes within the given set are traversal roots
                        // anyway, and all other
                        // fixed nodes are known to be outside.
                    } else if (!node.isExternal() && !aboveBound.isMarked(node)) {
                        worklist.add(node);
                        aboveBound.mark(node);
                    }
                }
            };

            if (stateAfter != null) {
                stateAfter.applyToNonVirtual(aboveClosure);
            }
            while (!worklist.isEmpty()) {
                Node current = worklist.remove();
                for (Node input : current.inputs()) {
                    if (!input.isExternal()) {
                        aboveClosure.apply(current, input);
                    }
                }
            }

            // Build the set of nodes that have (transitive) inputs within the duplicatedNodes.
            // This is achieved by iterating all nodes that are reachable via usages from the fixed
            // nodes.
            belowBound.markAll(fixedNodes);
            worklist.addAll(fixedNodes);

            // the phis at the original merge should always be duplicated
            for (PhiNode phi : merge.phis()) {
                belowBound.mark(phi);
                worklist.add(phi);
            }

            while (!worklist.isEmpty()) {
                Node current = worklist.remove();
                for (Node usage : current.usages()) {
                    if (usage instanceof PhiNode && !fixedNodes.contains(((PhiNode) usage).merge())) {
                        // stop iterating: phis belonging to outside merges are known to be outside.
                    } else if (usage instanceof FixedNode) {
                        // stop iterating: fixed nodes within the given set are traversal roots
                        // anyway, and all other
                        // fixed nodes are known to be outside.
                    } else if (!belowBound.isMarked(usage)) {
                        worklist.add(usage);
                        belowBound.mark(usage);
                    }
                }
            }

            // build the intersection
            belowBound.intersect(aboveBound);
            HashSet<Node> result = new HashSet<>();
            for (Node node : belowBound) {
                result.add(node);
            }
            return result;
        }

        /**
         * Creates a new merge and end node construct at the end of the duplicated area. While it is
         * useless in itself (merge with only one end) it simplifies the later duplication step.
         * 
         * @param successor The successor of the duplicated set of nodes, i.e., the first node that
         *            should not be duplicated.
         * @param stateAfterMerge The frame state that should be used for the merge.
         * @return The newly created end node.
         */
        private AbstractEndNode createNewMerge(FixedNode successor, FrameState stateAfterMerge) {
            MergeNode newBottomMerge = graph.add(new MergeNode());
            AbstractEndNode newBottomEnd = graph.add(new EndNode());
            newBottomMerge.addForwardEnd(newBottomEnd);
            newBottomMerge.setStateAfter(stateAfterMerge);
            ((FixedWithNextNode) successor.predecessor()).setNext(newBottomEnd);
            newBottomMerge.setNext(successor);
            return newBottomEnd;
        }

        /**
         * Expands the set of nodes to be duplicated by looking at a number of conditions:
         * <ul>
         * <li>{@link ValueNode}s that have usages on the outside need to be replaced with phis for
         * the outside usages.</li>
         * <li>Non-{@link ValueNode} nodes that have outside usages (frame states, virtual object
         * states, ...) need to be cloned immediately for the outside usages.</li>
         * <li>Nodes that have a {@link StampFactory#extension()} or
         * {@link StampFactory#condition()} stamp need to be cloned immediately for the outside
         * usages.</li>
         * <li>Dependencies into the duplicated nodes will be replaced with dependencies on the
         * merge.</li>
         * <li>Outside non-{@link ValueNode}s with usages within the duplicated set of nodes need to
         * also be duplicated.</li>
         * <li>Outside {@link ValueNode}s with {@link StampFactory#extension()} or
         * {@link StampFactory#condition()} stamps that have usages within the duplicated set of
         * nodes need to also be duplicated.</li>
         * </ul>
         * 
         * @param duplicatedNodes The set of duplicated nodes that will be modified (expanded).
         * @param newBottomMerge The merge that follows the duplicated set of nodes. It will be used
         *            for newly created phis and to as a target for dependencies that pointed into
         *            the duplicated set of nodes.
         */
        private void expandDuplicated(HashSet<Node> duplicatedNodes, MergeNode newBottomMerge) {
            Deque<Node> worklist = new ArrayDeque<>(duplicatedNodes);

            while (!worklist.isEmpty()) {
                Node duplicated = worklist.remove();
                if (hasUsagesOutside(duplicated, duplicatedNodes)) {
                    boolean cloneForOutsideUsages = false;
                    if (duplicated instanceof ValueNode) {
                        ValueNode node = (ValueNode) duplicated;
                        if (node.stamp() == StampFactory.dependency()) {
                            // re-route dependencies to the merge
                            replaceUsagesOutside(node, newBottomMerge, duplicatedNodes);
                            // TODO(ls) maybe introduce phis for dependencies
                        } else if (node.stamp() == StampFactory.extension() || node.stamp() == StampFactory.condition()) {
                            cloneForOutsideUsages = true;
                        } else {
                            // introduce a new phi
                            PhiNode newPhi = bottomPhis.get(node);
                            if (newPhi == null) {
                                newPhi = graph.addWithoutUnique(new PhiNode(node.kind(), newBottomMerge));
                                bottomPhis.put(node, newPhi);
                                newPhi.addInput(node);
                            }
                            replaceUsagesOutside(node, newPhi, duplicatedNodes);
                        }
                    } else {
                        cloneForOutsideUsages = true;
                    }
                    if (cloneForOutsideUsages) {
                        // clone the offending node to the outside
                        Node newOutsideClone = duplicated.copyWithInputs();
                        replaceUsagesOutside(duplicated, newOutsideClone, duplicatedNodes);
                        // this might cause other nodes to have outside usages, we need to look at
                        // those as well
                        for (Node input : newOutsideClone.inputs()) {
                            if (duplicatedNodes.contains(input)) {
                                worklist.add(input);
                            }
                        }
                    }
                }
                // check if this node has an input that lies outside and cannot be shared
                for (Node input : duplicated.inputs()) {
                    if (!duplicatedNodes.contains(input)) {
                        boolean duplicateInput = false;
                        if (input instanceof VirtualState) {
                            duplicateInput = true;
                        } else if (input instanceof ValueNode) {
                            Stamp inputStamp = ((ValueNode) input).stamp();
                            if (inputStamp == StampFactory.extension() || inputStamp == StampFactory.condition()) {
                                duplicateInput = true;
                            }
                        }
                        if (duplicateInput) {
                            duplicatedNodes.add(input);
                            worklist.add(input);
                        }
                    }
                }
            }
        }

        /**
         * Checks if the given node has usages that are not within the given set of nodes.
         * 
         * @param node The node whose usages are checked.
         * @param nodeSet The set of nodes that are considered to be "within".
         * @return true if the given node has usages on the outside, false otherwise.
         */
        private static boolean hasUsagesOutside(Node node, HashSet<Node> nodeSet) {
            for (Node usage : node.usages()) {
                if (!nodeSet.contains(usage)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Replaces the given node with the given replacement at all usages that are not within the
         * given set of nodes.
         * 
         * @param node The node to be replaced at outside usages.
         * @param replacement The node that replaced the given node at outside usages.
         * @param nodeSet The set of nodes that are considered to be "within".
         */
        private static void replaceUsagesOutside(Node node, Node replacement, HashSet<Node> nodeSet) {
            for (Node usage : node.usages().snapshot()) {
                if (!nodeSet.contains(usage)) {
                    usage.replaceFirstInput(node, replacement);
                }
            }
        }
    }
}
