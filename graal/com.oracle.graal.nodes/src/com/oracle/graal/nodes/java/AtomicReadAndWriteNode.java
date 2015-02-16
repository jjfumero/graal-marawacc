/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.java;

import static com.oracle.graal.compiler.common.UnsafeAccess.*;
import sun.misc.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.spi.*;

/**
 * Represents an atomic read-and-write operation like {@link Unsafe#getAndSetInt(Object, long, int)}
 * .
 */
@NodeInfo
public final class AtomicReadAndWriteNode extends AbstractMemoryCheckpoint implements Lowerable, MemoryCheckpoint.Single {

    public static final NodeClass<AtomicReadAndWriteNode> TYPE = NodeClass.get(AtomicReadAndWriteNode.class);
    @Input ValueNode object;
    @Input ValueNode offset;
    @Input ValueNode newValue;

    protected final Kind valueKind;
    protected final LocationIdentity locationIdentity;

    public AtomicReadAndWriteNode(ValueNode object, ValueNode offset, ValueNode newValue, Kind valueKind, LocationIdentity locationIdentity) {
        super(TYPE, StampFactory.forKind(newValue.getKind()));
        this.object = object;
        this.offset = offset;
        this.newValue = newValue;
        this.valueKind = valueKind;
        this.locationIdentity = locationIdentity;
    }

    public ValueNode object() {
        return object;
    }

    public ValueNode offset() {
        return offset;
    }

    public ValueNode newValue() {
        return newValue;
    }

    public Kind getValueKind() {
        return valueKind;
    }

    public LocationIdentity getLocationIdentity() {
        return locationIdentity;
    }

    public void lower(LoweringTool tool) {
        tool.getLowerer().lower(this, tool);
    }

    @NodeIntrinsic
    public static int getAndSetInt(Object object, long offset, int newValue, @SuppressWarnings("unused") @ConstantNodeParameter Kind valueKind,
                    @ConstantNodeParameter @SuppressWarnings("unused") LocationIdentity locationIdentity) {
        return unsafe.getAndSetInt(object, offset, newValue);
    }

    @NodeIntrinsic
    public static long getAndSetLong(Object object, long offset, long newValue, @SuppressWarnings("unused") @ConstantNodeParameter Kind valueKind,
                    @ConstantNodeParameter @SuppressWarnings("unused") LocationIdentity locationIdentity) {
        return unsafe.getAndSetLong(object, offset, newValue);
    }

    @NodeIntrinsic
    public static Object getAndSetObject(Object object, long offset, Object newValue, @SuppressWarnings("unused") @ConstantNodeParameter Kind valueKind,
                    @ConstantNodeParameter @SuppressWarnings("unused") LocationIdentity locationIdentity) {
        return unsafe.getAndSetObject(object, offset, newValue);
    }

}
