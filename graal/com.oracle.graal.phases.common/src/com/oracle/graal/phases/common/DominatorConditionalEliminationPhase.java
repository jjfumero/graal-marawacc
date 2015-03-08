/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.util.*;
import java.util.function.*;

import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.cfg.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.schedule.*;

public class DominatorConditionalEliminationPhase extends Phase {

    private static final DebugMetric metricStampsRegistered = Debug.metric("StampsRegistered");
    private static final DebugMetric metricStampsFound = Debug.metric("StampsFound");

    public DominatorConditionalEliminationPhase() {
    }

    private static final class InfoElement {
        private Stamp stamp;
        private ValueNode guard;

        public InfoElement(Stamp stamp, ValueNode guard) {
            this.stamp = stamp;
            this.guard = guard;
        }

        public Stamp getStamp() {
            return stamp;
        }

        public ValueNode getGuard() {
            return guard;
        }
    }

    private static final class Info {
        private ArrayList<InfoElement> infos;

        public Info() {
            infos = new ArrayList<>();
        }

        public Iterable<InfoElement> getElements() {
            return infos;
        }

        public void pushElement(InfoElement element) {
            infos.add(element);
        }

        public void popElement() {
            infos.remove(infos.size() - 1);
        }
    }

    @Override
    protected void run(StructuredGraph graph) {
        SchedulePhase schedule = new SchedulePhase(SchedulePhase.SchedulingStrategy.EARLIEST);
        schedule.apply(graph);
        ControlFlowGraph cfg = schedule.getCFG();
        cfg.computePostdominators();
        Instance instance = new Instance(graph, b -> schedule.getBlockToNodesMap().get(b), n -> schedule.getNodeToBlockMap().get(n));
        instance.processBlock(cfg.getStartBlock());
    }

    private static class Instance {

        private final NodeMap<Info> map;
        private final Stack<LoopExitNode> loopExits;
        private final Function<Block, Iterable<? extends Node>> blockToNodes;
        private final Function<Node, Block> nodeToBlock;

        public Instance(StructuredGraph graph, Function<Block, Iterable<? extends Node>> blockToNodes, Function<Node, Block> nodeToBlock) {
            map = graph.createNodeMap();
            loopExits = new Stack<>();
            this.blockToNodes = blockToNodes;
            this.nodeToBlock = nodeToBlock;
        }

        private void processBlock(Block block) {

            List<Runnable> undoOperations = new ArrayList<>();

            if (preprocess(block, undoOperations)) {

                // Process always reached block first.
                Block postdominator = block.getPostdominator();
                if (postdominator != null && postdominator.getDominator() == block) {
                    processBlock(postdominator);
                }

                // Now go for the other dominators.
                for (Block dominated : block.getDominated()) {
                    if (dominated != postdominator) {
                        assert dominated.getDominator() == block;
                        processBlock(dominated);
                    }
                }

                postprocess(undoOperations);
            }
        }

        private static void postprocess(List<Runnable> undoOperations) {
            for (Runnable r : undoOperations) {
                r.run();
            }
        }

        private boolean preprocess(Block block, List<Runnable> undoOperations) {
            AbstractBeginNode beginNode = block.getBeginNode();
            if (beginNode.isAlive() || (beginNode instanceof MergeNode && beginNode.next().isAlive())) {
                if (beginNode instanceof LoopExitNode) {
                    LoopExitNode loopExitNode = (LoopExitNode) beginNode;
                    this.loopExits.push(loopExitNode);
                    undoOperations.add(() -> loopExits.pop());
                }
                for (Node n : blockToNodes.apply(block)) {
                    if (n.isAlive()) {
                        processNode(n, block, undoOperations);
                    }
                }
                return true;
            } else {
                // Control flow has been deleted by previous eliminations.
                return false;
            }
        }

        private void processNode(Node node, Block block, List<Runnable> undoOperations) {
            if (node instanceof AbstractBeginNode) {
                processAbstractBegin((AbstractBeginNode) node, undoOperations);
            } else if (node instanceof FixedGuardNode) {
                processFixedGuard((FixedGuardNode) node, block, undoOperations);
            } else if (node instanceof GuardNode) {
                processGuard((GuardNode) node, block, undoOperations);
            } else if (node instanceof CheckCastNode) {
                processCheckCast((CheckCastNode) node, block);
            } else if (node instanceof ConditionAnchorNode) {
                processConditionAnchor((ConditionAnchorNode) node, block);
            } else if (node instanceof IfNode) {
                processIf((IfNode) node, block);
            } else {
                return;
            }
        }

        private void processCheckCast(CheckCastNode node, Block block) {
            tryProofCondition(node, block, (guard, result) -> {
                if (result) {
                    PiNode piNode = node.graph().unique(new PiNode(node.object(), node.stamp(), guard));
                    node.replaceAtUsages(piNode);
                    GraphUtil.unlinkFixedNode(node);
                    node.safeDelete();
                }
            });
        }

        private void processIf(IfNode node, Block block) {
            tryProofCondition(node.condition(), block, (guard, result) -> {
                AbstractBeginNode survivingSuccessor = node.getSuccessor(result);
                survivingSuccessor.replaceAtUsages(InputType.Guard, guard);
                survivingSuccessor.replaceAtPredecessor(null);
                node.replaceAtPredecessor(survivingSuccessor);
                GraphUtil.killCFG(node);
            });
        }

        private void registerNewCondition(LogicNode condition, boolean negated, ValueNode guard, List<Runnable> undoOperations) {
            if (condition instanceof UnaryOpLogicNode) {
                UnaryOpLogicNode unaryLogicNode = (UnaryOpLogicNode) condition;
                Stamp newStamp = unaryLogicNode.getSucceedingStampForValue(negated);
                registerNewStamp(unaryLogicNode.getValue(), newStamp, guard, undoOperations);
            } else if (condition instanceof BinaryOpLogicNode) {
                BinaryOpLogicNode binaryOpLogicNode = (BinaryOpLogicNode) condition;
                ValueNode x = binaryOpLogicNode.getX();
                if (!x.isConstant()) {
                    Stamp newStampX = binaryOpLogicNode.getSucceedingStampForX(negated);
                    registerNewStamp(x, newStampX, guard, undoOperations);
                }

                ValueNode y = binaryOpLogicNode.getY();
                if (!y.isConstant()) {
                    Stamp newStampY = binaryOpLogicNode.getSucceedingStampForY(negated);
                    registerNewStamp(y, newStampY, guard, undoOperations);
                }
                registerCondition(condition, negated, guard, undoOperations);
            }
        }

        private void registerCondition(LogicNode condition, boolean negated, ValueNode guard, List<Runnable> undoOperations) {
            this.registerNewStamp(condition, negated ? StampFactory.contradiction() : StampFactory.tautology(), guard, undoOperations);
        }

        private Iterable<InfoElement> getInfoElements(ValueNode proxiedValue) {
            ValueNode value = GraphUtil.unproxify(proxiedValue);
            Info info = map.get(value);
            if (info == null) {
                return Collections.emptyList();
            } else {
                return info.getElements();
            }
        }

        private boolean rewireGuards(ValueNode guard, boolean result, Block block, BiConsumer<ValueNode, Boolean> rewireGuardFunction) {
            assert guard instanceof GuardingNode;
            metricStampsFound.increment();
            ValueNode proxiedGuard = proxyGuard(guard, block);
            rewireGuardFunction.accept(proxiedGuard, result);
            return true;
        }

        private ValueNode proxyGuard(ValueNode guard, Block block) {
            ValueNode proxiedGuard = guard;
            if (!this.loopExits.isEmpty()) {
                while (proxiedGuard instanceof GuardProxyNode) {
                    proxiedGuard = ((GuardProxyNode) proxiedGuard).value();
                }
                ValueNode initialProxiedGuard = proxiedGuard;
                Block guardBlock = nodeToBlock.apply(proxiedGuard);
                assert guardBlock != null;
                Block curBlock = block;
                if (guardBlock != curBlock) {
                    int loopExitIndex = loopExits.size() - 1;
                    GuardProxyNode lastProxy = null;
                    do {
                        LoopExitNode loopExitNode = loopExits.get(loopExitIndex);
                        Block loopExitBlock = nodeToBlock.apply(loopExitNode);
                        assert loopExitBlock != null;
                        if (loopExitBlock == curBlock) {
                            assert curBlock != guardBlock;
                            GuardProxyNode guardProxy = proxiedGuard.graph().unique(new GuardProxyNode((GuardingNode) initialProxiedGuard, loopExitNode));
                            if (lastProxy == null) {
                                proxiedGuard = guardProxy;
                            } else {
                                lastProxy.setValue(guardProxy);
                            }
                            lastProxy = guardProxy;
                            loopExitIndex--;
                            if (loopExitIndex < 0) {
                                break;
                            }
                        }
                        curBlock = curBlock.getDominator();
                    } while (guardBlock != curBlock);
                }
            }
            return proxiedGuard;
        }

        private boolean tryProofCondition(Node node, Block block, BiConsumer<ValueNode, Boolean> rewireGuardFunction) {
            if (node instanceof UnaryOpLogicNode) {
                UnaryOpLogicNode unaryLogicNode = (UnaryOpLogicNode) node;
                ValueNode value = unaryLogicNode.getValue();
                for (InfoElement infoElement : getInfoElements(value)) {
                    Stamp stamp = infoElement.getStamp();
                    Boolean result = unaryLogicNode.tryFold(stamp);
                    if (result != null) {
                        return rewireGuards(infoElement.getGuard(), result, block, rewireGuardFunction);
                    }
                }
            } else if (node instanceof BinaryOpLogicNode) {
                BinaryOpLogicNode binaryOpLogicNode = (BinaryOpLogicNode) node;
                for (InfoElement infoElement : getInfoElements(binaryOpLogicNode)) {
                    if (infoElement.getStamp().equals(StampFactory.contradiction())) {
                        return rewireGuards(infoElement.getGuard(), false, block, rewireGuardFunction);
                    } else if (infoElement.getStamp().equals(StampFactory.tautology())) {
                        return rewireGuards(infoElement.getGuard(), true, block, rewireGuardFunction);
                    }
                }

                ValueNode x = binaryOpLogicNode.getX();
                ValueNode y = binaryOpLogicNode.getY();
                for (InfoElement infoElement : getInfoElements(x)) {
                    Boolean result = binaryOpLogicNode.tryFold(infoElement.getStamp(), y.stamp());
                    if (result != null) {
                        return rewireGuards(infoElement.getGuard(), result, block, rewireGuardFunction);
                    }
                }

                for (InfoElement infoElement : getInfoElements(y)) {
                    Boolean result = binaryOpLogicNode.tryFold(x.stamp(), infoElement.getStamp());
                    if (result != null) {
                        return rewireGuards(infoElement.getGuard(), result, block, rewireGuardFunction);
                    }
                }
            } else if (node instanceof CheckCastNode) {
                CheckCastNode checkCastNode = (CheckCastNode) node;
                for (InfoElement infoElement : getInfoElements(checkCastNode.object())) {
                    Boolean result = checkCastNode.tryFold(infoElement.getStamp());
                    if (result != null) {
                        return rewireGuards(infoElement.getGuard(), result, block, rewireGuardFunction);
                    }
                }
            } else if (node instanceof ShortCircuitOrNode) {
                final ShortCircuitOrNode shortCircuitOrNode = (ShortCircuitOrNode) node;
                tryProofCondition(shortCircuitOrNode.getX(), block, (guard, result) -> {
                    if (result == !shortCircuitOrNode.isXNegated()) {
                        rewireGuards(guard, result, block, rewireGuardFunction);
                    } else {
                        tryProofCondition(shortCircuitOrNode.getY(), block, (innerGuard, innerResult) -> {
                            if (innerGuard == guard) {
                                rewireGuards(guard, shortCircuitOrNode.isYNegated() ? !innerResult : innerResult, block, rewireGuardFunction);
                            }
                        });
                    }
                });
            }

            return false;
        }

        private void registerNewStamp(ValueNode proxiedValue, Stamp newStamp, ValueNode guard, List<Runnable> undoOperations) {
            if (newStamp != null) {
                ValueNode value = GraphUtil.unproxify(proxiedValue);
                Info info = map.get(value);
                if (info == null) {
                    info = new Info();
                    map.set(value, info);
                }
                metricStampsRegistered.increment();
                final Info finalInfo = info;
                finalInfo.pushElement(new InfoElement(newStamp, guard));
                undoOperations.add(() -> finalInfo.popElement());
            }
        }

        private void processConditionAnchor(ConditionAnchorNode node, Block block) {
            tryProofCondition(node.condition(), block, (guard, result) -> {
                if (result == node.isNegated()) {
                    node.replaceAtUsages(guard);
                    GraphUtil.unlinkFixedNode(node);
                    GraphUtil.killWithUnusedFloatingInputs(node);
                }
            });
        }

        private void processGuard(GuardNode node, Block block, List<Runnable> undoOperations) {
            if (!tryProofCondition(node.condition(), block, (guard, result) -> {
                if (result != node.isNegated()) {
                    node.replaceAndDelete(guard);
                }
            })) {
                registerNewCondition(node.condition(), node.isNegated(), node, undoOperations);
            }
        }

        private void processFixedGuard(FixedGuardNode node, Block block, List<Runnable> undoOperations) {
            if (!tryProofCondition(node.condition(), block, (guard, result) -> {
                if (result != node.isNegated()) {
                    node.replaceAtUsages(guard);
                    GraphUtil.unlinkFixedNode(node);
                    GraphUtil.killWithUnusedFloatingInputs(node);
                } else {
                    DeoptimizeNode deopt = node.graph().add(new DeoptimizeNode(node.getAction(), node.getReason()));
                    deopt.setStateBefore(node.stateBefore());
                    node.replaceAtPredecessor(deopt);
                    GraphUtil.killCFG(node);
                }
            })) {
                registerNewCondition(node.condition(), node.isNegated(), node, undoOperations);
            }
        }

        private void processAbstractBegin(AbstractBeginNode beginNode, List<Runnable> undoOperations) {
            Node predecessor = beginNode.predecessor();
            if (predecessor instanceof IfNode) {
                IfNode ifNode = (IfNode) predecessor;
                boolean negated = (ifNode.falseSuccessor() == beginNode);
                LogicNode condition = ifNode.condition();
                registerNewCondition(condition, negated, beginNode, undoOperations);
            } else if (predecessor instanceof TypeSwitchNode) {
                TypeSwitchNode typeSwitch = (TypeSwitchNode) predecessor;
                processTypeSwitch(beginNode, undoOperations, predecessor, typeSwitch);
            } else if (predecessor instanceof IntegerSwitchNode) {
                IntegerSwitchNode integerSwitchNode = (IntegerSwitchNode) predecessor;
                processIntegerSwitch(beginNode, undoOperations, predecessor, integerSwitchNode);
            }
        }

        private void processIntegerSwitch(AbstractBeginNode beginNode, List<Runnable> undoOperations, Node predecessor, IntegerSwitchNode integerSwitchNode) {
            Stamp stamp = null;
            for (int i = 0; i < integerSwitchNode.keyCount(); i++) {
                if (integerSwitchNode.keySuccessor(i) == predecessor) {
                    if (stamp == null) {
                        stamp = StampFactory.forConstant(integerSwitchNode.keyAt(i));
                    } else {
                        stamp = stamp.meet(StampFactory.forConstant(integerSwitchNode.keyAt(i)));
                    }
                }
            }

            if (stamp != null) {
                registerNewStamp(integerSwitchNode.value(), stamp, beginNode, undoOperations);
            }
        }

        private void processTypeSwitch(AbstractBeginNode beginNode, List<Runnable> undoOperations, Node predecessor, TypeSwitchNode typeSwitch) {
            ValueNode hub = typeSwitch.value();
            if (hub instanceof LoadHubNode) {
                LoadHubNode loadHub = (LoadHubNode) hub;
                Stamp stamp = null;
                for (int i = 0; i < typeSwitch.keyCount(); i++) {
                    if (typeSwitch.keySuccessor(i) == predecessor) {
                        if (stamp == null) {
                            stamp = StampFactory.exactNonNull(typeSwitch.typeAt(i));
                        } else {
                            stamp = stamp.meet(StampFactory.exactNonNull(typeSwitch.typeAt(i)));
                        }
                    }
                }
                if (stamp != null) {
                    registerNewStamp(loadHub.getValue(), stamp, beginNode, undoOperations);
                }
            }
        }
    }
}
