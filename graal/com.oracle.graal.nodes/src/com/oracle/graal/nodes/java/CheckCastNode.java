/*
 * Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.java;

import static com.oracle.graal.api.code.DeoptimizationAction.*;
import static com.oracle.graal.api.meta.DeoptimizationReason.*;
import static com.oracle.graal.nodes.extended.BranchProbabilityNode.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.meta.ProfilingInfo.TriState;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

/**
 * Implements a type check against a compile-time known type.
 */
public final class CheckCastNode extends FixedWithNextNode implements Canonicalizable, Lowerable, Node.IterableNodeType, Virtualizable, ValueProxy {

    @Input private ValueNode object;
    private final ResolvedJavaType type;
    private final JavaTypeProfile profile;

    /**
     * Determines the exception thrown by this node if the check fails: {@link ClassCastException}
     * if false; {@link ArrayStoreException} if true.
     */
    private final boolean forStoreCheck;

    /**
     * Creates a new CheckCast instruction.
     * 
     * @param type the type being cast to
     * @param object the instruction producing the object
     */
    public CheckCastNode(ResolvedJavaType type, ValueNode object, JavaTypeProfile profile, boolean forStoreCheck) {
        super(StampFactory.declared(type));
        assert type != null;
        this.type = type;
        this.object = object;
        this.profile = profile;
        this.forStoreCheck = forStoreCheck;
    }

    public boolean isForStoreCheck() {
        return forStoreCheck;
    }

    // TODO (ds) remove once performance regression in compiler.sunflow (and other benchmarks)
    // caused by new lowering is fixed
    private static final boolean useNewLowering = true; // Boolean.getBoolean("graal.checkcast.useNewLowering");

    /**
     * Lowers a {@link CheckCastNode} to a {@link GuardingPiNode}. That is:
     * 
     * <pre>
     * 1: A a = ...
     * 2: B b = (B) a;
     * </pre>
     * 
     * is lowered to:
     * 
     * <pre>
     * 1: A a = ...
     * 2: B b = guardingPi(a == null || a instanceof B, a, stamp(B))
     * </pre>
     * 
     * or if a is known to be non-null:
     * 
     * <pre>
     * 1: A a = ...
     * 2: B b = guardingPi(a instanceof B, a, stamp(B, non-null))
     * </pre>
     * 
     * Note: we use {@link Graph#add} as opposed to {@link Graph#unique} for the new
     * {@link InstanceOfNode} to maintain the invariant checked by
     * {@code LoweringPhase.checkUsagesAreScheduled()}.
     */
    @Override
    public void lower(LoweringTool tool, LoweringType loweringType) {
        if (useNewLowering) {
            InstanceOfNode typeTest = graph().add(new InstanceOfNode(type, object, profile));
            Stamp stamp = StampFactory.declared(type).join(object.stamp());
            ValueNode condition;
            if (stamp == null) {
                // This is a check cast that will always fail
                condition = LogicConstantNode.contradiction(graph());
                stamp = StampFactory.declared(type);
            } else if (object.stamp().nonNull()) {
                condition = typeTest;
            } else {
                if (profile != null && profile.getNullSeen() == TriState.FALSE) {
                    FixedGuardNode nullGuard = graph().add(new FixedGuardNode(graph().unique(new IsNullNode(object)), UnreachedCode, DeoptimizationAction.InvalidateReprofile, true));
                    graph().addBeforeFixed(this, nullGuard);
                    condition = typeTest;
                    stamp = stamp.join(StampFactory.objectNonNull());
                } else {
                    double shortCircuitProbability;
                    if (profile == null) {
                        shortCircuitProbability = NOT_FREQUENT_PROBABILITY;
                    } else {
                        // Tell the instanceof it does not need to do a null check
                        typeTest.setProfile(new JavaTypeProfile(TriState.FALSE, profile.getNotRecordedProbability(), profile.getTypes()));

                        // TODO (ds) replace with probability of null-seen when available
                        shortCircuitProbability = NOT_FREQUENT_PROBABILITY;
                    }
                    condition = graph().unique(new ShortCircuitOrNode(graph().unique(new IsNullNode(object)), false, typeTest, false, shortCircuitProbability));
                }
            }
            GuardingPiNode checkedObject = graph().add(new GuardingPiNode(object, condition, false, forStoreCheck ? ArrayStoreException : ClassCastException, InvalidateReprofile, stamp));
            graph().replaceFixedWithFixed(this, checkedObject);
        } else {
            tool.getRuntime().lower(this, tool);
        }
    }

    @Override
    public boolean inferStamp() {
        return updateStamp(stamp().join(object().stamp()));
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        assert object() != null : this;

        ResolvedJavaType objectType = object().objectStamp().type();
        if (objectType != null && type.isAssignableFrom(objectType)) {
            // we don't have to check for null types here because they will also pass the
            // checkcast.
            return object();
        }

        // remove checkcast if next node is a more specific checkcast
        if (predecessor() instanceof CheckCastNode) {
            CheckCastNode ccn = (CheckCastNode) predecessor();
            if (ccn != null && ccn.type != null && ccn == object && ccn.forStoreCheck == forStoreCheck && ccn.type.isAssignableFrom(type)) {
                StructuredGraph graph = ccn.graph();
                CheckCastNode newccn = graph.add(new CheckCastNode(type, ccn.object, ccn.profile, ccn.forStoreCheck));
                graph.replaceFixedWithFixed(ccn, newccn);
                return newccn;
            }
        }

        if (object().objectStamp().alwaysNull()) {
            return object();
        }
        if (tool.assumptions().useOptimisticAssumptions()) {
            ResolvedJavaType exactType = type.findUniqueConcreteSubtype();
            if (exactType != null && exactType != type) {
                // Propagate more precise type information to usages of the checkcast.
                tool.assumptions().recordConcreteSubtype(type, exactType);
                return graph().add(new CheckCastNode(exactType, object, profile, forStoreCheck));
            }
        }

        return this;
    }

    public ValueNode object() {
        return object;
    }

    /**
     * Gets the type being cast to.
     */
    public ResolvedJavaType type() {
        return type;
    }

    public JavaTypeProfile profile() {
        return profile;
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        State state = tool.getObjectState(object);
        if (state != null && state.getState() == EscapeState.Virtual) {
            if (type.isAssignableFrom(state.getVirtualObject().type())) {
                tool.replaceWithVirtual(state.getVirtualObject());
            }
        }
    }

    @Override
    public ValueNode getOriginalValue() {
        return object;
    }
}
