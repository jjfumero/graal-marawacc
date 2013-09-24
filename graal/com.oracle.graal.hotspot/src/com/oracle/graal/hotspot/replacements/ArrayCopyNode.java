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

import static com.oracle.graal.compiler.GraalCompiler.*;

import java.util.concurrent.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.loop.phases.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.virtual.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.phases.tiers.*;
import com.oracle.graal.replacements.nodes.*;

public class ArrayCopyNode extends MacroNode implements Virtualizable, Lowerable {

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

    private StructuredGraph selectSnippet(LoweringTool tool, final Replacements replacements) {
        ResolvedJavaType srcType = ObjectStamp.typeOrNull(getSource().stamp());
        ResolvedJavaType destType = ObjectStamp.typeOrNull(getDestination().stamp());

        if (srcType == null || !srcType.isArray() || destType == null || !destType.isArray()) {
            return null;
        }
        if (!destType.getComponentType().isAssignableFrom(srcType.getComponentType()) || !ObjectStamp.isExactType(getDestination().stamp())) {
            return null;
        }
        Kind componentKind = srcType.getComponentType().getKind();
        final ResolvedJavaMethod snippetMethod = tool.getRuntime().lookupJavaMethod(ArrayCopySnippets.getSnippetForKind(componentKind));
        return Debug.scope("ArrayCopySnippet", snippetMethod, new Callable<StructuredGraph>() {

            @Override
            public StructuredGraph call() throws Exception {
                return replacements.getSnippet(snippetMethod);
            }
        });

    }

    private static void unrollFixedLengthLoop(StructuredGraph snippetGraph, int length, LoweringTool tool) {
        LocalNode lengthLocal = snippetGraph.getLocal(4);
        if (lengthLocal != null) {
            snippetGraph.replaceFloating(lengthLocal, ConstantNode.forInt(length, snippetGraph));
        }
        // the canonicalization before loop unrolling is needed to propagate the length into
        // additions, etc.
        PhaseContext context = new PhaseContext(tool.getRuntime(), tool.assumptions(), tool.getReplacements());
        new CanonicalizerPhase(true).apply(snippetGraph, context);
        new LoopFullUnrollPhase(new CanonicalizerPhase(true)).apply(snippetGraph, context);
        new CanonicalizerPhase(true).apply(snippetGraph, context);
    }

    @Override
    protected StructuredGraph getLoweredSnippetGraph(final LoweringTool tool) {
        if (!shouldIntrinsify(getTargetMethod())) {
            return null;
        }

        final Replacements replacements = tool.getReplacements();
        StructuredGraph snippetGraph = selectSnippet(tool, replacements);
        if (snippetGraph == null) {
            final ResolvedJavaMethod snippetMethod = tool.getRuntime().lookupJavaMethod(ArrayCopySnippets.genericArraycopySnippet);
            snippetGraph = Debug.scope("ArrayCopySnippet", snippetMethod, new Callable<StructuredGraph>() {

                @Override
                public StructuredGraph call() throws Exception {
                    return replacements.getSnippet(snippetMethod).copy();
                }
            });
            replaceSnippetInvokes(snippetGraph);
        } else {
            assert snippetGraph != null : "ArrayCopySnippets should be installed";
            snippetGraph = snippetGraph.copy();
            if (getLength().isConstant() && getLength().asConstant().asInt() <= GraalOptions.MaximumEscapeAnalysisArrayLength.getValue()) {
                final StructuredGraph copy = snippetGraph;
                Debug.scope("ArrayCopySnippetSpecialization", snippetGraph.method(), new Runnable() {

                    @Override
                    public void run() {
                        unrollFixedLengthLoop(copy, getLength().asConstant().asInt(), tool);
                    }
                });
            }
        }
        return lowerReplacement(snippetGraph, tool);
    }

    private static boolean checkBounds(int position, int length, VirtualObjectNode virtualObject) {
        return position >= 0 && position + length <= virtualObject.entryCount();
    }

    private static boolean checkEntryTypes(int srcPos, int length, State srcState, ResolvedJavaType destComponentType, VirtualizerTool tool) {
        if (destComponentType.getKind() == Kind.Object) {
            for (int i = 0; i < length; i++) {
                ValueNode entry = srcState.getEntry(srcPos + i);
                State state = tool.getObjectState(entry);
                ResolvedJavaType type;
                if (state != null) {
                    if (state.getState() == EscapeState.Virtual) {
                        type = state.getVirtualObject().type();
                    } else {
                        type = ObjectStamp.typeOrNull(state.getMaterializedValue());
                    }
                } else {
                    type = ObjectStamp.typeOrNull(entry);
                }
                if (type == null || !destComponentType.isAssignableFrom(type)) {
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
                if (!(srcVirtual instanceof VirtualArrayNode) || !(destVirtual instanceof VirtualArrayNode)) {
                    return;
                }
                if (((VirtualArrayNode) srcVirtual).componentType().getKind() != Kind.Object || ((VirtualArrayNode) destVirtual).componentType().getKind() != Kind.Object) {
                    return;
                }
                if (length < 0 || !checkBounds(srcPos, length, srcVirtual) || !checkBounds(destPos, length, destVirtual)) {
                    return;
                }
                if (!checkEntryTypes(srcPos, length, srcState, destVirtual.type().getComponentType(), tool)) {
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
