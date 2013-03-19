/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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

import com.oracle.graal.api.meta.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.PhiNode.PhiType;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.graph.*;

public class ConditionalEliminationPhase extends Phase {

    private static final DebugMetric metricConditionRegistered = Debug.metric("ConditionRegistered");
    private static final DebugMetric metricTypeRegistered = Debug.metric("TypeRegistered");
    private static final DebugMetric metricNullnessRegistered = Debug.metric("NullnessRegistered");
    private static final DebugMetric metricObjectEqualsRegistered = Debug.metric("ObjectEqualsRegistered");
    private static final DebugMetric metricCheckCastRemoved = Debug.metric("CheckCastRemoved");
    private static final DebugMetric metricInstanceOfRemoved = Debug.metric("InstanceOfRemoved");
    private static final DebugMetric metricNullCheckRemoved = Debug.metric("NullCheckRemoved");
    private static final DebugMetric metricObjectEqualsRemoved = Debug.metric("ObjectEqualsRemoved");
    private static final DebugMetric metricGuardsRemoved = Debug.metric("GuardsRemoved");

    private final MetaAccessProvider metaAccessProvider;

    private StructuredGraph graph;

    public ConditionalEliminationPhase(MetaAccessProvider metaAccessProvider) {
        this.metaAccessProvider = metaAccessProvider;
    }

    @Override
    protected void run(StructuredGraph inputGraph) {
        graph = inputGraph;
        new ConditionalElimination(graph.start(), new State()).apply();
    }

    public static class State implements MergeableState<State> {

        private IdentityHashMap<ValueNode, ResolvedJavaType> knownTypes;
        private HashSet<ValueNode> knownNonNull;
        private HashSet<ValueNode> knownNull;
        private IdentityHashMap<LogicNode, ValueNode> trueConditions;
        private IdentityHashMap<LogicNode, ValueNode> falseConditions;

        public State() {
            this.knownTypes = new IdentityHashMap<>();
            this.knownNonNull = new HashSet<>();
            this.knownNull = new HashSet<>();
            this.trueConditions = new IdentityHashMap<>();
            this.falseConditions = new IdentityHashMap<>();
        }

        public State(State other) {
            this.knownTypes = new IdentityHashMap<>(other.knownTypes);
            this.knownNonNull = new HashSet<>(other.knownNonNull);
            this.knownNull = new HashSet<>(other.knownNull);
            this.trueConditions = new IdentityHashMap<>(other.trueConditions);
            this.falseConditions = new IdentityHashMap<>(other.falseConditions);
        }

        @Override
        public boolean merge(MergeNode merge, List<State> withStates) {
            IdentityHashMap<ValueNode, ResolvedJavaType> newKnownTypes = new IdentityHashMap<>();
            IdentityHashMap<LogicNode, ValueNode> newTrueConditions = new IdentityHashMap<>();
            IdentityHashMap<LogicNode, ValueNode> newFalseConditions = new IdentityHashMap<>();

            HashSet<ValueNode> newKnownNull = new HashSet<>(knownNull);
            HashSet<ValueNode> newKnownNonNull = new HashSet<>(knownNonNull);
            for (State state : withStates) {
                newKnownNull.retainAll(state.knownNull);
                newKnownNonNull.retainAll(state.knownNonNull);
            }

            for (Map.Entry<ValueNode, ResolvedJavaType> entry : knownTypes.entrySet()) {
                ValueNode node = entry.getKey();
                ResolvedJavaType type = entry.getValue();

                for (State other : withStates) {
                    ResolvedJavaType otherType = other.getNodeType(node);
                    type = widen(type, otherType);
                    if (type == null) {
                        break;
                    }
                }
                if (type == null && type != node.objectStamp().type()) {
                    newKnownTypes.put(node, type);
                }
            }

            for (Map.Entry<LogicNode, ValueNode> entry : trueConditions.entrySet()) {
                LogicNode check = entry.getKey();
                ValueNode guard = entry.getValue();

                for (State other : withStates) {
                    ValueNode otherGuard = other.trueConditions.get(check);
                    if (otherGuard == null) {
                        guard = null;
                        break;
                    }
                    if (otherGuard != guard) {
                        guard = merge;
                    }
                }
                if (guard != null) {
                    newTrueConditions.put(check, guard);
                }
            }
            for (Map.Entry<LogicNode, ValueNode> entry : falseConditions.entrySet()) {
                LogicNode check = entry.getKey();
                ValueNode guard = entry.getValue();

                for (State other : withStates) {
                    ValueNode otherGuard = other.falseConditions.get(check);
                    if (otherGuard == null) {
                        guard = null;
                        break;
                    }
                    if (otherGuard != guard) {
                        guard = merge;
                    }
                }
                if (guard != null) {
                    newFalseConditions.put(check, guard);
                }
            }

            // this piece of code handles phis
            if (!(merge instanceof LoopBeginNode)) {
                for (PhiNode phi : merge.phis()) {
                    if (phi.type() == PhiType.Value && phi.kind() == Kind.Object) {
                        ValueNode firstValue = phi.valueAt(0);
                        ResolvedJavaType type = getNodeType(firstValue);
                        boolean nonNull = knownNonNull.contains(firstValue);
                        boolean isNull = knownNull.contains(firstValue);

                        for (int i = 0; i < withStates.size(); i++) {
                            State otherState = withStates.get(i);
                            ValueNode value = phi.valueAt(i + 1);
                            ResolvedJavaType otherType = otherState.getNodeType(value);
                            type = widen(type, otherType);
                            nonNull &= otherState.knownNonNull.contains(value);
                            isNull &= otherState.knownNull.contains(value);
                        }
                        if (type != null) {
                            newKnownTypes.put(phi, type);
                        }
                        if (nonNull) {
                            newKnownNonNull.add(phi);
                        }
                        if (isNull) {
                            newKnownNull.add(phi);
                        }
                    }
                }
            }

            this.knownTypes = newKnownTypes;
            this.knownNonNull = newKnownNonNull;
            this.knownNull = newKnownNull;
            this.trueConditions = newTrueConditions;
            this.falseConditions = newFalseConditions;
            return true;
        }

        public ResolvedJavaType getNodeType(ValueNode node) {
            ResolvedJavaType result = knownTypes.get(node);
            return result == null ? node.objectStamp().type() : result;
        }

        public boolean isNull(ValueNode value) {
            return value.objectStamp().alwaysNull() || knownNull.contains(value);
        }

        public boolean isNonNull(ValueNode value) {
            return value.objectStamp().nonNull() || knownNonNull.contains(value);
        }

        @Override
        public void loopBegin(LoopBeginNode loopBegin) {
        }

        @Override
        public void loopEnds(LoopBeginNode loopBegin, List<State> loopEndStates) {
        }

        @Override
        public void afterSplit(BeginNode node) {
        }

        @Override
        public State clone() {
            return new State(this);
        }

        /**
         * Adds information about a condition. If isTrue is true then the condition is known to
         * hold, otherwise the condition is known not to hold.
         */
        public void addCondition(boolean isTrue, LogicNode condition, ValueNode anchor) {
            if (isTrue) {
                if (!trueConditions.containsKey(condition)) {
                    trueConditions.put(condition, anchor);
                    metricConditionRegistered.increment();
                }
            } else {
                if (!falseConditions.containsKey(condition)) {
                    falseConditions.put(condition, anchor);
                    metricConditionRegistered.increment();
                }
            }
        }

        /**
         * Adds information about the nullness of a value. If isNull is true then the value is known
         * to be null, otherwise the value is known to be non-null.
         */
        public void addNullness(boolean isNull, ValueNode value) {
            if (isNull) {
                if (!isNull(value)) {
                    metricNullnessRegistered.increment();
                    knownNull.add(value);
                }
            } else {
                if (!isNonNull(value)) {
                    metricNullnessRegistered.increment();
                    knownNonNull.add(value);
                }
            }
        }

        public void addType(ResolvedJavaType type, ValueNode value) {
            ResolvedJavaType knownType = getNodeType(value);
            ResolvedJavaType newType = tighten(type, knownType);

            if (newType != knownType) {
                knownTypes.put(value, newType);
                metricTypeRegistered.increment();
            }
        }
    }

    public static ResolvedJavaType widen(ResolvedJavaType a, ResolvedJavaType b) {
        if (a == null || b == null) {
            return null;
        } else if (a == b) {
            return a;
        } else {
            return a.findLeastCommonAncestor(b);
        }
    }

    public static ResolvedJavaType tighten(ResolvedJavaType a, ResolvedJavaType b) {
        if (a == null) {
            return b;
        } else if (b == null) {
            return a;
        } else if (a == b) {
            return a;
        } else if (a.isAssignableFrom(b)) {
            return b;
        } else {
            return a;
        }
    }

    public class ConditionalElimination extends PostOrderNodeIterator<State> {

        private final LogicNode trueConstant;
        private final LogicNode falseConstant;

        public ConditionalElimination(FixedNode start, State initialState) {
            super(start, initialState);
            trueConstant = LogicConstantNode.tautology(graph);
            falseConstant = LogicConstantNode.contradiction(graph);
        }

        @Override
        public void finished() {
            if (trueConstant.usages().isEmpty()) {
                graph.removeFloating(trueConstant);
            }
            if (falseConstant.usages().isEmpty()) {
                graph.removeFloating(falseConstant);
            }
        }

        private void registerCondition(boolean isTrue, LogicNode condition, ValueNode anchor) {
            state.addCondition(isTrue, condition, anchor);

            if (isTrue && condition instanceof InstanceOfNode) {
                InstanceOfNode instanceOf = (InstanceOfNode) condition;
                ValueNode object = instanceOf.object();
                state.addNullness(false, object);
                state.addType(instanceOf.type(), object);
            } else if (condition instanceof IsNullNode) {
                IsNullNode nullCheck = (IsNullNode) condition;
                state.addNullness(isTrue, nullCheck.object());
            } else if (condition instanceof ObjectEqualsNode) {
                ObjectEqualsNode equals = (ObjectEqualsNode) condition;
                ValueNode x = equals.x();
                ValueNode y = equals.y();
                if (isTrue) {
                    if (state.isNull(x) && !state.isNull(y)) {
                        metricObjectEqualsRegistered.increment();
                        state.addNullness(true, y);
                    } else if (!state.isNull(x) && state.isNull(y)) {
                        metricObjectEqualsRegistered.increment();
                        state.addNullness(true, x);
                    }
                    if (state.isNonNull(x) && !state.isNonNull(y)) {
                        metricObjectEqualsRegistered.increment();
                        state.addNullness(false, y);
                    } else if (!state.isNonNull(x) && state.isNonNull(y)) {
                        metricObjectEqualsRegistered.increment();
                        state.addNullness(false, x);
                    }
                } else {
                    if (state.isNull(x) && !state.isNonNull(y)) {
                        metricObjectEqualsRegistered.increment();
                        state.addNullness(true, y);
                    } else if (!state.isNonNull(x) && state.isNull(y)) {
                        metricObjectEqualsRegistered.increment();
                        state.addNullness(true, x);
                    }
                }
            }
        }

        private void registerControlSplitInfo(Node pred, BeginNode begin) {
            assert pred != null && begin != null;

            if (pred instanceof IfNode) {
                IfNode ifNode = (IfNode) pred;

                if (!(ifNode.condition() instanceof LogicConstantNode)) {
                    registerCondition(begin == ifNode.trueSuccessor(), ifNode.condition(), begin);
                }
            } else if (pred instanceof TypeSwitchNode) {
                TypeSwitchNode typeSwitch = (TypeSwitchNode) pred;

                if (typeSwitch.value() instanceof LoadHubNode) {
                    LoadHubNode loadHub = (LoadHubNode) typeSwitch.value();
                    ResolvedJavaType type = null;
                    for (int i = 0; i < typeSwitch.keyCount(); i++) {
                        if (typeSwitch.keySuccessor(i) == begin) {
                            if (type == null) {
                                type = typeSwitch.typeAt(i);
                            } else {
                                type = widen(type, typeSwitch.typeAt(i));
                            }
                        }
                    }
                    if (type != null) {
                        state.addNullness(false, loadHub.object());
                        state.addType(type, loadHub.object());
                    }
                }
            }
        }

        private void registerGuard(GuardNode guard) {
            LogicNode condition = guard.condition();

            ValueNode existingGuards = guard.negated() ? state.falseConditions.get(condition) : state.trueConditions.get(condition);
            if (existingGuards != null) {
                guard.replaceAtUsages(existingGuards);
                GraphUtil.killWithUnusedFloatingInputs(guard);
                metricGuardsRemoved.increment();
            } else {
                LogicNode replacement = evaluateCondition(condition, trueConstant, falseConstant);
                if (replacement != null) {
                    guard.setCondition(replacement);
                    if (condition.usages().isEmpty()) {
                        GraphUtil.killWithUnusedFloatingInputs(condition);
                    }
                    metricGuardsRemoved.increment();
                } else {
                    registerCondition(!guard.negated(), condition, guard);
                }
            }
        }

        /**
         * Determines if, at the current point in the control flow, the condition is known to be
         * true, false or unknown. In case of true or false the corresponding value is returned,
         * otherwise null.
         */
        private <T extends ValueNode> T evaluateCondition(LogicNode condition, T trueValue, T falseValue) {
            if (state.trueConditions.containsKey(condition)) {
                return trueValue;
            } else if (state.falseConditions.containsKey(condition)) {
                return falseValue;
            } else {
                if (condition instanceof InstanceOfNode) {
                    InstanceOfNode instanceOf = (InstanceOfNode) condition;
                    ValueNode object = instanceOf.object();
                    if (state.isNull(object)) {
                        metricInstanceOfRemoved.increment();
                        return falseValue;
                    } else if (state.isNonNull(object)) {
                        ResolvedJavaType type = state.getNodeType(object);
                        if (type != null && instanceOf.type().isAssignableFrom(type)) {
                            metricInstanceOfRemoved.increment();
                            return trueValue;
                        }
                    }
                } else if (condition instanceof IsNullNode) {
                    IsNullNode isNull = (IsNullNode) condition;
                    ValueNode object = isNull.object();
                    if (state.isNull(object)) {
                        metricNullCheckRemoved.increment();
                        return trueValue;
                    } else if (state.isNonNull(object)) {
                        metricNullCheckRemoved.increment();
                        return falseValue;
                    }
                } else if (condition instanceof ObjectEqualsNode) {
                    ObjectEqualsNode equals = (ObjectEqualsNode) condition;
                    ValueNode x = equals.x();
                    ValueNode y = equals.y();
                    if (state.isNull(x) && state.isNonNull(y) || state.isNonNull(x) && state.isNull(y)) {
                        metricObjectEqualsRemoved.increment();
                        return falseValue;
                    } else if (state.isNull(x) && state.isNull(y)) {
                        metricObjectEqualsRemoved.increment();
                        return trueValue;
                    }
                }
            }
            return null;
        }

        @Override
        protected void node(FixedNode node) {
            if (node instanceof BeginNode) {
                BeginNode begin = (BeginNode) node;
                Node pred = node.predecessor();

                if (pred != null) {
                    registerControlSplitInfo(pred, begin);
                }
                for (GuardNode guard : begin.guards().snapshot()) {
                    registerGuard(guard);
                }
            } else if (node instanceof CheckCastNode) {
                CheckCastNode checkCast = (CheckCastNode) node;
                ValueNode object = checkCast.object();
                boolean isNull = state.isNull(object);
                ResolvedJavaType type = state.getNodeType(object);
                if (isNull || (type != null && checkCast.type().isAssignableFrom(type))) {
                    boolean nonNull = state.isNonNull(object);
                    ValueAnchorNode anchor = graph.add(new ValueAnchorNode());
                    PiNode piNode;
                    if (isNull) {
                        ConstantNode nullObject = ConstantNode.forObject(null, metaAccessProvider, graph);
                        piNode = graph.unique(new PiNode(nullObject, anchor, StampFactory.forConstant(nullObject.value, metaAccessProvider)));
                    } else {
                        piNode = graph.unique(new PiNode(object, anchor, StampFactory.declared(type, nonNull)));
                    }
                    checkCast.replaceAtUsages(piNode);
                    graph.replaceFixedWithFixed(checkCast, anchor);
                    metricCheckCastRemoved.increment();
                }
            } else if (node instanceof IfNode) {
                IfNode ifNode = (IfNode) node;
                LogicNode compare = ifNode.condition();
                LogicNode replacement = evaluateCondition(compare, trueConstant, falseConstant);

                if (replacement != null) {
                    ifNode.setCondition(replacement);
                    if (compare.usages().isEmpty()) {
                        GraphUtil.killWithUnusedFloatingInputs(compare);
                    }
                }
            } else if (node instanceof EndNode) {
                EndNode endNode = (EndNode) node;
                for (PhiNode phi : endNode.merge().phis()) {
                    int index = endNode.merge().phiPredecessorIndex(endNode);
                    ValueNode value = phi.valueAt(index);
                    if (value instanceof ConditionalNode) {
                        ConditionalNode materialize = (ConditionalNode) value;
                        LogicNode compare = materialize.condition();
                        ValueNode replacement = evaluateCondition(compare, materialize.trueValue(), materialize.falseValue());

                        if (replacement != null) {
                            phi.setValueAt(index, replacement);
                            if (materialize.usages().isEmpty()) {
                                GraphUtil.killWithUnusedFloatingInputs(materialize);
                            }
                        }
                    }
                }
            }
        }
    }

}
