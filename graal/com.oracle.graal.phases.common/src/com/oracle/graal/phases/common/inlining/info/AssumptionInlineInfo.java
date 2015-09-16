/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.phases.common.inlining.info;

import java.util.Collection;

import jdk.internal.jvmci.meta.Assumptions.AssumptionResult;
import jdk.internal.jvmci.meta.ResolvedJavaMethod;

import com.oracle.graal.graph.Node;
import com.oracle.graal.nodes.CallTargetNode.InvokeKind;
import com.oracle.graal.nodes.Invoke;
import com.oracle.graal.phases.common.inlining.InliningUtil;
import com.oracle.graal.phases.util.Providers;

/**
 * Represents an inlining opportunity where the current class hierarchy leads to a monomorphic
 * target method, but for which an assumption has to be registered because of non-final classes.
 */
public class AssumptionInlineInfo extends ExactInlineInfo {

    private final AssumptionResult<?> takenAssumption;

    public AssumptionInlineInfo(Invoke invoke, ResolvedJavaMethod concrete, AssumptionResult<?> takenAssumption) {
        super(invoke, concrete);
        this.takenAssumption = takenAssumption;
    }

    @Override
    public Collection<Node> inline(Providers providers) {
        takenAssumption.recordTo(invoke.asNode().graph().getAssumptions());
        return super.inline(providers);
    }

    @Override
    public void tryToDevirtualizeInvoke(Providers providers) {
        takenAssumption.recordTo(invoke.asNode().graph().getAssumptions());
        InliningUtil.replaceInvokeCallTarget(invoke, graph(), InvokeKind.Special, concrete);
    }

    @Override
    public String toString() {
        return "assumption " + concrete.format("%H.%n(%p):%r");
    }
}
