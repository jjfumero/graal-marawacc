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
package com.oracle.graal.hotspot.replacements;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.replacements.nodes.*;

@NodeInfo
public class CallSiteTargetNode extends MacroStateSplitNode implements Canonicalizable, Lowerable {

    public static CallSiteTargetNode create(Invoke invoke) {
        return new CallSiteTargetNode(invoke);
    }

    protected CallSiteTargetNode(Invoke invoke) {
        super(invoke);
    }

    private ValueNode getCallSite() {
        return arguments.get(0);
    }

    private ConstantNode getConstantCallTarget(MetaAccessProvider metaAccess, Assumptions assumptions) {
        if (getCallSite().isConstant() && !getCallSite().isNullConstant()) {
            HotSpotObjectConstant c = (HotSpotObjectConstant) getCallSite().asConstant();
            JavaConstant target = c.getCallSiteTarget(assumptions);
            if (target != null) {
                return ConstantNode.forConstant(target, metaAccess);
            }
        }
        return null;
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        ConstantNode target = getConstantCallTarget(tool.getMetaAccess(), tool.assumptions());
        if (target != null) {
            return target;
        }

        return this;
    }

    @Override
    public void lower(LoweringTool tool) {
        ConstantNode target = getConstantCallTarget(tool.getMetaAccess(), tool.assumptions());

        if (target != null) {
            graph().replaceFixedWithFloating(this, target);
        } else {
            InvokeNode invoke = createInvoke();
            graph().replaceFixedWithFixed(this, invoke);
            invoke.lower(tool);
        }
    }
}
