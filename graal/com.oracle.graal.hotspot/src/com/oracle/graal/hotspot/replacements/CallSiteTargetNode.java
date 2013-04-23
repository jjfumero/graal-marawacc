/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.replacements;

import java.lang.invoke.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.replacements.nodes.*;

public class CallSiteTargetNode extends MacroNode implements Canonicalizable, Lowerable {

    public CallSiteTargetNode(Invoke invoke) {
        super(invoke);
    }

    private ValueNode getCallSite() {
        return arguments.get(0);
    }

    private ConstantNode getConstantCallTarget(MetaAccessProvider metaAccessProvider, Assumptions assumptions) {
        if (getCallSite().isConstant() && !getCallSite().isNullConstant()) {
            CallSite callSite = (CallSite) getCallSite().asConstant().asObject();
            if (callSite instanceof ConstantCallSite) {
                return ConstantNode.forObject(callSite.getTarget(), metaAccessProvider, graph());
            } else if (callSite instanceof MutableCallSite || callSite instanceof VolatileCallSite && assumptions != null && assumptions.useOptimisticAssumptions()) {
                MethodHandle target = callSite.getTarget();
                assumptions.record(new Assumptions.CallSiteTargetValue(callSite, target));
                return ConstantNode.forObject(target, metaAccessProvider, graph());
            }
        }
        return null;
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        ConstantNode target = getConstantCallTarget(tool.runtime(), tool.assumptions());
        if (target != null) {
            return target;
        }

        return this;
    }

    @Override
    public void lower(LoweringTool tool, LoweringType loweringType) {
        StructuredGraph graph = (StructuredGraph) graph();
        ConstantNode target = getConstantCallTarget(tool.getRuntime(), tool.assumptions());

        if (target != null) {
            graph.replaceFixedWithFloating(this, target);
        } else {
            graph.replaceFixedWithFixed(this, createInvoke());
        }
    }
}
