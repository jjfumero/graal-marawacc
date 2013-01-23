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
package com.oracle.graal.nodes;

import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.util.*;

@NodeInfo(nameTemplate = "Invoke!#{p#targetMethod/s}")
public class InvokeWithExceptionNode extends ControlSplitNode implements Node.IterableNodeType, Invoke, MemoryCheckpoint, LIRLowerable {

    @Successor private BeginNode next;
    @Successor private DispatchBeginNode exceptionEdge;
    @Input private final CallTargetNode callTarget;
    @Input private FrameState stateAfter;
    private final int bci;
    private boolean polymorphic;
    private boolean useForInlining;
    private final long leafGraphId;
    private double inliningRelevance;

    public InvokeWithExceptionNode(CallTargetNode callTarget, DispatchBeginNode exceptionEdge, int bci, long leafGraphId) {
        super(callTarget.returnStamp());
        this.exceptionEdge = exceptionEdge;
        this.bci = bci;
        this.callTarget = callTarget;
        this.leafGraphId = leafGraphId;
        this.polymorphic = false;
        this.useForInlining = true;
        this.inliningRelevance = Double.NaN;
    }

    public DispatchBeginNode exceptionEdge() {
        return exceptionEdge;
    }

    public void setExceptionEdge(DispatchBeginNode x) {
        updatePredecessor(exceptionEdge, x);
        exceptionEdge = x;
    }

    public BeginNode next() {
        return next;
    }

    public void setNext(BeginNode x) {
        updatePredecessor(next, x);
        next = x;
    }

    public CallTargetNode callTarget() {
        return callTarget;
    }

    public MethodCallTargetNode methodCallTarget() {
        return (MethodCallTargetNode) callTarget;
    }

    @Override
    public boolean isPolymorphic() {
        return polymorphic;
    }

    @Override
    public void setPolymorphic(boolean value) {
        this.polymorphic = value;
    }

    @Override
    public boolean useForInlining() {
        return useForInlining;
    }

    @Override
    public void setUseForInlining(boolean value) {
        this.useForInlining = value;
    }

    @Override
    public double inliningRelevance() {
        return inliningRelevance;
    }

    @Override
    public void setInliningRelevance(double value) {
        inliningRelevance = value;
    }

    @Override
    public long leafGraphId() {
        return leafGraphId;
    }

    @Override
    public String toString(Verbosity verbosity) {
        if (verbosity == Verbosity.Long) {
            return super.toString(Verbosity.Short) + "(bci=" + bci() + ")";
        } else if (verbosity == Verbosity.Name) {
            return "Invoke#" + (callTarget == null ? "null" : callTarget().targetName());
        } else {
            return super.toString(verbosity);
        }
    }

    public int bci() {
        return bci;
    }

    @Override
    public FixedNode node() {
        return this;
    }

    @Override
    public void setNext(FixedNode x) {
        if (x != null) {
            this.setNext(BeginNode.begin(x));
        } else {
            this.setNext(null);
        }
    }

    @Override
    public void lower(LoweringTool tool) {
        tool.getRuntime().lower(this, tool);
    }

    @Override
    public void generate(LIRGeneratorTool gen) {
        gen.emitInvoke(this);
    }

    public FrameState stateAfter() {
        return stateAfter;
    }

    public void setStateAfter(FrameState stateAfter) {
        updateUsages(this.stateAfter, stateAfter);
        this.stateAfter = stateAfter;
    }

    public boolean hasSideEffect() {
        return true;
    }

    public FrameState stateDuring() {
        FrameState tempStateAfter = stateAfter();
        FrameState stateDuring = tempStateAfter.duplicateModified(bci(), tempStateAfter.rethrowException(), kind());
        stateDuring.setDuringCall(true);
        return stateDuring;
    }

    @Override
    public Map<Object, Object> getDebugProperties(Map<Object, Object> map) {
        Map<Object, Object> debugProperties = super.getDebugProperties(map);
        if (callTarget instanceof MethodCallTargetNode && methodCallTarget().targetMethod() != null) {
            debugProperties.put("targetMethod", methodCallTarget().targetMethod());
        }
        return debugProperties;
    }

    public void killExceptionEdge() {
        BeginNode edge = exceptionEdge();
        setExceptionEdge(null);
        GraphUtil.killCFG(edge);
    }

    @Override
    public void intrinsify(Node node) {
        assert !(node instanceof ValueNode) || (((ValueNode) node).kind() == Kind.Void) == (kind() == Kind.Void);
        CallTargetNode call = callTarget;
        FrameState state = stateAfter();
        killExceptionEdge();
        if (node instanceof StateSplit) {
            StateSplit stateSplit = (StateSplit) node;
            stateSplit.setStateAfter(state);
        }
        if (node == null) {
            assert kind() == Kind.Void && usages().isEmpty();
            ((StructuredGraph) graph()).removeSplit(this, next());
        } else if (node instanceof DeoptimizeNode) {
            this.replaceAtPredecessor(node);
            this.replaceAtUsages(null);
            GraphUtil.killCFG(this);
            return;
        } else {
            ((StructuredGraph) graph()).replaceSplit(this, node, next());
        }
        call.safeDelete();
        if (state.usages().isEmpty()) {
            state.safeDelete();
        }
    }

    private static final double EXCEPTION_PROBA = 1e-5;

    @Override
    public double probability(BeginNode successor) {
        return successor == next ? 1 - EXCEPTION_PROBA : EXCEPTION_PROBA;
    }
}
