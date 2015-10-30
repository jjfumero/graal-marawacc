/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.replacements.arraycopy;

import static jdk.vm.ci.meta.LocationIdentity.any;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.LocationIdentity;

import com.oracle.graal.compiler.common.type.StampFactory;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.nodeinfo.InputType;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.NamedLocationIdentity;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.extended.ArrayRangeWriteNode;
import com.oracle.graal.nodes.memory.MemoryAccess;
import com.oracle.graal.nodes.memory.MemoryCheckpoint;
import com.oracle.graal.nodes.memory.MemoryNode;
import com.oracle.graal.nodes.spi.Lowerable;
import com.oracle.graal.nodes.spi.LoweringTool;
import com.oracle.graal.replacements.SnippetTemplate.Arguments;

@NodeInfo(allowedUsageTypes = {InputType.Memory})
public final class UnsafeArrayCopyNode extends ArrayRangeWriteNode implements Lowerable, MemoryCheckpoint.Single, MemoryAccess {

    public static final NodeClass<UnsafeArrayCopyNode> TYPE = NodeClass.create(UnsafeArrayCopyNode.class);
    @Input ValueNode src;
    @Input ValueNode srcPos;
    @Input ValueNode dest;
    @Input ValueNode destPos;
    @Input ValueNode length;
    @OptionalInput ValueNode layoutHelper;

    @OptionalInput(InputType.Memory) MemoryNode lastLocationAccess;

    protected JavaKind elementKind;

    public UnsafeArrayCopyNode(ValueNode src, ValueNode srcPos, ValueNode dest, ValueNode destPos, ValueNode length, ValueNode layoutHelper, JavaKind elementKind) {
        super(TYPE, StampFactory.forVoid());
        assert layoutHelper == null || elementKind == null;
        this.src = src;
        this.srcPos = srcPos;
        this.dest = dest;
        this.destPos = destPos;
        this.length = length;
        this.layoutHelper = layoutHelper;
        this.elementKind = elementKind;
    }

    public UnsafeArrayCopyNode(ValueNode src, ValueNode srcPos, ValueNode dest, ValueNode destPos, ValueNode length, JavaKind elementKind) {
        this(src, srcPos, dest, destPos, length, null, elementKind);
    }

    public UnsafeArrayCopyNode(ValueNode src, ValueNode srcPos, ValueNode dest, ValueNode destPos, ValueNode length, ValueNode layoutHelper) {
        this(src, srcPos, dest, destPos, length, layoutHelper, null);
    }

    @Override
    public ValueNode getArray() {
        return dest;
    }

    @Override
    public ValueNode getIndex() {
        return destPos;
    }

    @Override
    public ValueNode getLength() {
        return length;
    }

    @Override
    public boolean isObjectArray() {
        return elementKind == JavaKind.Object;
    }

    @Override
    public boolean isInitialization() {
        return false;
    }

    public JavaKind getElementKind() {
        return elementKind;
    }

    @Override
    public void lower(LoweringTool tool) {
        if (graph().getGuardsStage().areFrameStatesAtDeopts()) {
            UnsafeArrayCopySnippets.Templates templates = tool.getReplacements().getSnippetTemplateCache(UnsafeArrayCopySnippets.Templates.class);
            templates.lower(this, tool);
        }
    }

    public void addSnippetArguments(Arguments args) {
        args.add("src", src);
        args.add("srcPos", srcPos);
        args.add("dest", dest);
        args.add("destPos", destPos);
        args.add("length", length);
        if (layoutHelper != null) {
            args.add("layoutHelper", layoutHelper);
        }
    }

    @Override
    public LocationIdentity getLocationIdentity() {
        if (elementKind != null) {
            return NamedLocationIdentity.getArrayLocation(elementKind);
        }
        return any();
    }

    public MemoryNode getLastLocationAccess() {
        return lastLocationAccess;
    }

    public void setLastLocationAccess(MemoryNode lla) {
        updateUsagesInterface(lastLocationAccess, lla);
        lastLocationAccess = lla;
    }

    @NodeIntrinsic
    public static native void arraycopy(Object src, int srcPos, Object dest, int destPos, int length, @ConstantNodeParameter JavaKind elementKind);

    @NodeIntrinsic
    public static native void arraycopyPrimitive(Object src, int srcPos, Object dest, int destPos, int length, int layoutHelper);
}
