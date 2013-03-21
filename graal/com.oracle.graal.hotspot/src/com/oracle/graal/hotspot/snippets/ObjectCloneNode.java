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

import java.lang.reflect.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.virtual.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.snippets.nodes.*;

public class ObjectCloneNode extends MacroNode implements VirtualizableAllocation, ArrayLengthProvider {

    public ObjectCloneNode(Invoke invoke) {
        super(invoke);
    }

    @Override
    public boolean inferStamp() {
        return updateStamp(getObject().stamp());
    }

    private ValueNode getObject() {
        return arguments.get(0);
    }

    @Override
    protected StructuredGraph getSnippetGraph(LoweringTool tool) {
        if (!GraalOptions.IntrinsifyObjectClone) {
            return null;
        }

        ResolvedJavaType type = getObject().objectStamp().type();
        Method method;
        /*
         * The first condition tests if the parameter is an array, the second condition tests if the
         * parameter can be an array. Otherwise, the parameter is known to be a non-array object.
         */
        if (type.isArray()) {
            method = ObjectCloneSnippets.arrayCloneMethod;
        } else if (type == null || type.isAssignableFrom(tool.getRuntime().lookupJavaType(Object[].class))) {
            method = ObjectCloneSnippets.genericCloneMethod;
        } else {
            method = ObjectCloneSnippets.instanceCloneMethod;
        }
        ResolvedJavaMethod snippetMethod = tool.getRuntime().lookupJavaMethod(method);
        StructuredGraph snippetGraph = (StructuredGraph) snippetMethod.getCompilerStorage().get(Graph.class);

        assert snippetGraph != null : "ObjectCloneSnippets should be installed";
        return snippetGraph;
    }

    private static boolean isCloneableType(ResolvedJavaType type, MetaAccessProvider metaAccess) {
        return metaAccess.lookupJavaType(Cloneable.class).isAssignableFrom(type);
    }

    private static ResolvedJavaType getConcreteType(ObjectStamp stamp, Assumptions assumptions) {
        if (stamp.isExactType() || stamp.type() == null) {
            return stamp.type();
        } else {
            ResolvedJavaType type = stamp.type().findUniqueConcreteSubtype();
            if (type != null) {
                assumptions.recordConcreteSubtype(stamp.type(), type);
            }
            return type;
        }
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        State originalState = tool.getObjectState(getObject());
        if (originalState != null && originalState.getState() == EscapeState.Virtual) {
            VirtualObjectNode originalVirtual = originalState.getVirtualObject();
            if (isCloneableType(originalVirtual.type(), tool.getMetaAccessProvider())) {
                ValueNode[] newEntryState = new ValueNode[originalVirtual.entryCount()];
                for (int i = 0; i < newEntryState.length; i++) {
                    newEntryState[i] = originalState.getEntry(i);
                }
                VirtualObjectNode newVirtual = originalVirtual.duplicate();
                tool.createVirtualObject(newVirtual, newEntryState, 0);
                tool.replaceWithVirtual(newVirtual);
            }
        } else {
            ValueNode obj;
            if (originalState != null) {
                obj = originalState.getMaterializedValue();
            } else {
                obj = tool.getReplacedValue(getObject());
            }
            ResolvedJavaType type = getConcreteType(obj.objectStamp(), tool.getAssumptions());
            if (isCloneableType(type, tool.getMetaAccessProvider())) {
                if (!type.isArray()) {
                    VirtualInstanceNode newVirtual = new VirtualInstanceNode(type);
                    ResolvedJavaField[] fields = newVirtual.getFields();

                    ValueNode[] state = new ValueNode[fields.length];
                    final LoadFieldNode[] loads = new LoadFieldNode[fields.length];
                    for (int i = 0; i < fields.length; i++) {
                        state[i] = loads[i] = graph().add(new LoadFieldNode(obj, fields[i]));
                    }

                    final StructuredGraph structuredGraph = (StructuredGraph) graph();
                    tool.customAction(new Runnable() {

                        public void run() {
                            for (LoadFieldNode load : loads) {
                                structuredGraph.addBeforeFixed(ObjectCloneNode.this, load);
                            }
                        }
                    });
                    tool.createVirtualObject(newVirtual, state, 0);
                    tool.replaceWithVirtual(newVirtual);
                }
            }
        }
    }

    @Override
    public ValueNode length() {
        if (getObject() instanceof ArrayLengthProvider) {
            return ((ArrayLengthProvider) getObject()).length();
        } else {
            return null;
        }
    }
}
