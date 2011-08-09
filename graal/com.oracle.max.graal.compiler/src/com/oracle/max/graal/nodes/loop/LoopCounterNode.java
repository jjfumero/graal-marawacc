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
package com.oracle.max.graal.nodes.loop;

import com.oracle.max.graal.graph.*;
import com.oracle.max.graal.nodes.base.*;
import com.oracle.max.graal.nodes.spi.*;
import com.sun.cri.ci.*;

/**
 * Counts loop iterations from 0 to Niter.
 * If used directly (and not just by BasicInductionVariables) computed with Phi(0, this + 1)
 */
public final class LoopCounterNode extends InductionVariableNode {
    @Input private LoopBeginNode loopBegin;

    @Override
    public LoopBeginNode loopBegin() {
        return loopBegin;
    }

    public void setLoopBegin(LoopBeginNode x) {
        updateUsages(loopBegin, x);
        loopBegin = x;
    }

    public LoopCounterNode(CiKind kind, LoopBeginNode loop, Graph graph) {
        super(kind, graph);
        setLoopBegin(loop);
    }

    @Override
    public void peelOneIteration() {
        BasicInductionVariableNode biv = null;
        for (Node usage : usages()) {
            if (!(usage instanceof InductionVariableNode && ((InductionVariableNode) usage).loopBegin() == this.loopBegin())) {
                if (biv == null) {
                    biv = createBasicInductionVariable();
                    biv.peelOneIteration();
                }
                usage.inputs().replace(this, biv);
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends Op> T lookup(Class<T> clazz) {
        if (clazz == LoweringOp.class) {
            return (T) LOWERING;
        }
        return super.lookup(clazz);
    }

    private BasicInductionVariableNode createBasicInductionVariable() {
        Graph graph = graph();
        return new BasicInductionVariableNode(kind, ConstantNode.forInt(0, graph), ConstantNode.forInt(1, graph), this, graph);
    }

    private static final LoweringOp LOWERING = new LoweringOp() {
        @Override
        public void lower(Node n, CiLoweringTool tool) {
            LoopCounterNode loopCounter = (LoopCounterNode) n;
            Graph graph = n.graph();
            PhiNode phi = BasicInductionVariableNode.LOWERING.ivToPhi(loopCounter.loopBegin(), ConstantNode.forInt(0, graph), ConstantNode.forInt(1, graph), loopCounter.kind);
            loopCounter.replaceAtNonIVUsages(phi);
        }
    };
}
