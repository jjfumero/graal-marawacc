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
import com.oracle.graal.api.meta.MetaUtil;
import com.oracle.graal.api.meta.ResolvedJavaMethod;
import com.oracle.graal.nodes.CallTargetNode;
import com.oracle.graal.nodes.java.MethodCallTargetNode;
import com.oracle.graal.phases.common.inlining.info.InlineInfo;
import com.oracle.graal.phases.common.inlining.info.elem.Inlineable;
import com.oracle.graal.phases.common.inlining.info.elem.InlineableGraph;
import com.oracle.graal.phases.common.inlining.info.elem.InlineableMacroNode;

/**
 * <p>
 * An instance of this class denotes a callsite being analyzed for inlining.
 * </p>
 * <p>
 * Each element of the {@link InliningData} stack contains one such instance, the accompanying
 * {@link CallsiteHolder}s in that element represent feasible targets for the callsite in question.
 * </p>
 *
 * @see InliningData#moveForward()
 */
public class MethodInvocation {

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

    public CallsiteHolder buildCallsiteHolderForElement(int index) {
        Inlineable elem = callee.inlineableElementAt(index);
        if (elem instanceof InlineableGraph) {
            InlineableGraph ig = (InlineableGraph) elem;
            return new CallsiteHolderExplorable(ig.getGraph(), probability * callee.probabilityAt(index), relevance * callee.relevanceAt(index));
        } else {
            assert elem instanceof InlineableMacroNode;
            return CallsiteHolderDummy.DUMMY_CALLSITE_HOLDER;
        }
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
