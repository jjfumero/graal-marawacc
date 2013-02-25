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
package com.oracle.graal.hotspot.snippets;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.Node.IterableNodeType;
import com.oracle.graal.loop.phases.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.virtual.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.snippets.nodes.*;

public class ArrayCopyNode extends MacroNode implements Virtualizable, IterableNodeType, Lowerable {

    public ArrayCopyNode(Invoke invoke) {
        super(invoke);
    }

    private ValueNode getSource() {
        return arguments.get(0);
    }

    private ValueNode getSourcePosition() {
        return arguments.get(1);
    }

    private ValueNode getDestination() {
        return arguments.get(2);
    }

    private ValueNode getDestinationPosition() {
        return arguments.get(3);
    }

    private ValueNode getLength() {
        return arguments.get(4);
    }

    private ResolvedJavaMethod selectSnippet(LoweringTool tool) {
        ResolvedJavaType srcType = getSource().objectStamp().type();
        ResolvedJavaType destType = getDestination().objectStamp().type();

        if (srcType != null && srcType.isArray() && destType != null && destType.isArray()) {
            Kind componentKind = srcType.getComponentType().getKind();
            if (componentKind != Kind.Object) {
                if (srcType.getComponentType() == destType.getComponentType()) {
                    return tool.getRuntime().lookupJavaMethod(ArrayCopySnippets.getSnippetForKind(componentKind));
                }
            } else if (destType.getComponentType().isAssignableFrom(srcType.getComponentType()) && getDestination().objectStamp().isExactType()) {
                return tool.getRuntime().lookupJavaMethod(ArrayCopySnippets.getSnippetForKind(Kind.Object));
            }
        }
        return null;
    }

    private static void unrollFixedLengthLoop(StructuredGraph snippetGraph, int length, LoweringTool tool) {
        LocalNode lengthLocal = snippetGraph.getLocal(4);
        if (lengthLocal != null) {
            snippetGraph.replaceFloating(lengthLocal, ConstantNode.forInt(length, snippetGraph));
        }
        // the canonicalization before loop unrolling is needed to propagate the length into
        // additions, etc.
        new CanonicalizerPhase(tool.getRuntime(), tool.assumptions()).apply(snippetGraph);
        new LoopFullUnrollPhase(tool.getRuntime(), tool.assumptions()).apply(snippetGraph);
        new CanonicalizerPhase(tool.getRuntime(), tool.assumptions()).apply(snippetGraph);
    }

    @Override
    public void lower(LoweringTool tool) {
        ResolvedJavaMethod snippetMethod = selectSnippet(tool);
        if (snippetMethod == null) {
            snippetMethod = tool.getRuntime().lookupJavaMethod(ArrayCopySnippets.increaseGenericCallCounterMethod);
            // we will call the generic method. the generic snippet will only increase the counter,
            // not call the actual method. therefore we create a second invoke here.
            ((StructuredGraph) graph()).addAfterFixed(this, createInvoke());
        }
        if (Debug.isLogEnabled()) {
            Debug.log("%s > Intrinsify (%s)", Debug.currentScope(), snippetMethod.getSignature().getParameterType(0, snippetMethod.getDeclaringClass()).getComponentType());
        }

        StructuredGraph snippetGraph = (StructuredGraph) snippetMethod.getCompilerStorage().get(Graph.class);
        assert snippetGraph != null : "ArrayCopySnippets should be installed";
        if (getLength().isConstant()) {
            snippetGraph = snippetGraph.copy();
            unrollFixedLengthLoop(snippetGraph, getLength().asConstant().asInt(), tool);
        }
        InvokeNode invoke = replaceWithInvoke();
        InliningUtil.inline(invoke, snippetGraph, false);
    }

    private static boolean checkBounds(int position, int length, VirtualObjectNode virtualObject) {
        return position >= 0 && position + length <= virtualObject.entryCount();
    }

    private static boolean checkEntryTypes(int srcPos, int length, State srcState, ResolvedJavaType destComponentType) {
        if (destComponentType.getKind() == Kind.Object) {
            for (int i = 0; i < length; i++) {
                if (!destComponentType.isAssignableFrom(srcState.getEntry(srcPos + i).objectStamp().type())) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        if (getSourcePosition().isConstant() && getDestinationPosition().isConstant() && getLength().isConstant()) {
            int srcPos = getSourcePosition().asConstant().asInt();
            int destPos = getDestinationPosition().asConstant().asInt();
            int length = getLength().asConstant().asInt();
            State srcState = tool.getObjectState(getSource());
            State destState = tool.getObjectState(getDestination());

            if (srcState != null && srcState.getState() == EscapeState.Virtual && destState != null && destState.getState() == EscapeState.Virtual) {
                VirtualObjectNode srcVirtual = srcState.getVirtualObject();
                VirtualObjectNode destVirtual = destState.getVirtualObject();
                if (length < 0 || !checkBounds(srcPos, length, srcVirtual) || !checkBounds(destPos, length, destVirtual)) {
                    return;
                }
                if (!checkEntryTypes(srcPos, length, srcState, destVirtual.type().getComponentType())) {
                    return;
                }
                for (int i = 0; i < length; i++) {
                    tool.setVirtualEntry(destState, destPos + i, srcState.getEntry(srcPos + i));
                }
                tool.delete();
                if (Debug.isLogEnabled()) {
                    Debug.log("virtualized arraycopyf(%s, %d, %s, %d, %d)", getSource(), srcPos, getDestination(), destPos, length);
                }
            }
        }
    }
}
