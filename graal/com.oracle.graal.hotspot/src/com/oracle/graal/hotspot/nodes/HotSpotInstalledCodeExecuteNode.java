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
package com.oracle.graal.hotspot.nodes;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.replacements.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.phases.common.*;

public class HotSpotInstalledCodeExecuteNode extends AbstractCallNode implements Lowerable {

    @Input private final ValueNode code;
    private final Class[] signature;

    public HotSpotInstalledCodeExecuteNode(Kind kind, Class[] signature, ValueNode code, ValueNode arg1, ValueNode arg2, ValueNode arg3) {
        super(StampFactory.forKind(kind), new ValueNode[]{arg1, arg2, arg3});
        this.code = code;
        this.signature = signature;
    }

    @Override
    public Object[] getLocationIdentities() {
        return new Object[]{LocationNode.ANY_LOCATION};
    }

    @Override
    public void lower(LoweringTool tool) {
        if (code.isConstant() && code.asConstant().asObject() instanceof HotSpotInstalledCode) {
            HotSpotInstalledCode hsCode = (HotSpotInstalledCode) code.asConstant().asObject();
            InvokeNode invoke = replaceWithInvoke(tool.getRuntime());
            InliningUtil.inline(invoke, (StructuredGraph) hsCode.getGraph(), false);
        } else {
            replaceWithInvoke(tool.getRuntime());
        }
    }

    protected InvokeNode replaceWithInvoke(MetaAccessProvider tool) {
        ResolvedJavaMethod method = null;
        ResolvedJavaField methodField = null;
        ResolvedJavaField metaspaceMethodField = null;
        ResolvedJavaField nmethodField = null;
        try {
            method = tool.lookupJavaMethod(HotSpotInstalledCodeExecuteNode.class.getMethod("placeholder", Object.class, Object.class, Object.class));
            methodField = tool.lookupJavaField(HotSpotInstalledCode.class.getDeclaredField("method"));
            nmethodField = tool.lookupJavaField(HotSpotInstalledCode.class.getDeclaredField("nmethod"));
            metaspaceMethodField = tool.lookupJavaField(HotSpotResolvedJavaMethod.class.getDeclaredField("metaspaceMethod"));
        } catch (NoSuchMethodException | SecurityException | NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
        ResolvedJavaType[] signatureTypes = new ResolvedJavaType[signature.length];
        for (int i = 0; i < signature.length; i++) {
            signatureTypes[i] = tool.lookupJavaType(signature[i]);
        }
        final int verifiedEntryPointOffset = HotSpotSnippetUtils.verifiedEntryPointOffset();

        StructuredGraph g = (StructuredGraph) graph();

        LoadFieldNode loadnmethod = g.add(new LoadFieldNode(code, nmethodField));
        UnsafeLoadNode load = g.add(new UnsafeLoadNode(loadnmethod, verifiedEntryPointOffset, ConstantNode.forLong(0, graph()), HotSpotGraalRuntime.getInstance().getTarget().wordKind));

        LoadFieldNode loadMethod = g.add(new LoadFieldNode(code, methodField));
        LoadFieldNode loadmetaspaceMethod = g.add(new LoadFieldNode(loadMethod, metaspaceMethodField));

        HotSpotIndirectCallTargetNode callTarget = g.add(new HotSpotIndirectCallTargetNode(loadmetaspaceMethod, load, arguments, stamp(), signatureTypes, method, CallingConvention.Type.JavaCall));

        InvokeNode invoke = g.add(new InvokeNode(callTarget, 0));

        invoke.setStateAfter(stateAfter());
        g.replaceFixedWithFixed(this, invoke);

        g.addBeforeFixed(invoke, loadmetaspaceMethod);
        g.addBeforeFixed(loadmetaspaceMethod, loadMethod);
        g.addBeforeFixed(invoke, load);
        g.addBeforeFixed(load, loadnmethod);

        return invoke;
    }

    public static Object placeholder(@SuppressWarnings("unused") Object a1, @SuppressWarnings("unused") Object a2, @SuppressWarnings("unused") Object a3) {
        return 1;
    }

    @NodeIntrinsic
    public static native <T> T call(@ConstantNodeParameter Kind kind, @ConstantNodeParameter Class[] signature, Object code, Object arg1, Object arg2, Object arg3);

}
