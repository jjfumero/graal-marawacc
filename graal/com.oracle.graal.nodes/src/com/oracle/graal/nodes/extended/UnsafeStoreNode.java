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
package com.oracle.graal.nodes.extended;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.cri.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

/**
 * Store of a value at a location specified as an offset relative to an object.
 */
public class UnsafeStoreNode extends FixedWithNextNode implements StateSplit, Lowerable {

    @Input private ValueNode object;
    @Input private ValueNode offset;
    @Input private ValueNode value;
    private final int displacement;
    private final RiKind storeKind;
    @Input(notDataflow = true) private FrameState stateAfter;

    public FrameState stateAfter() {
        return stateAfter;
    }

    public void setStateAfter(FrameState x) {
        assert x == null || x.isAlive() : "frame state must be in a graph";
        updateUsages(stateAfter, x);
        stateAfter = x;
    }

    public boolean hasSideEffect() {
        return true;
    }

    public UnsafeStoreNode(ValueNode object, int displacement, ValueNode offset, ValueNode value, RiKind kind) {
        super(StampFactory.forVoid());
        assert kind != RiKind.Void && kind != RiKind.Illegal;
        this.object = object;
        this.displacement = displacement;
        this.offset = offset;
        this.value = value;
        this.storeKind = kind;
    }

    public ValueNode object() {
        return object;
    }

    public int displacement() {
        return displacement;
    }

    public ValueNode offset() {
        return offset;
    }

    public ValueNode value() {
        return value;
    }

    public RiKind storeKind() {
        return storeKind;
    }

    @Override
    public void lower(CiLoweringTool tool) {
        tool.getRuntime().lower(this, tool);
    }

    // specialized on value type until boxing/unboxing is sorted out in intrinsification
    @SuppressWarnings("unused")
    @NodeIntrinsic
    public static void store(Object object, @ConstantNodeParameter int displacement, long offset, Object value, @ConstantNodeParameter RiKind kind) {
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unused")
    @NodeIntrinsic
    public static void store(Object object, @ConstantNodeParameter int displacement, long offset, boolean value, @ConstantNodeParameter RiKind kind) {
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unused")
    @NodeIntrinsic
    public static void store(Object object, @ConstantNodeParameter int displacement, long offset, byte value, @ConstantNodeParameter RiKind kind) {
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unused")
    @NodeIntrinsic
    public static void store(Object object, @ConstantNodeParameter int displacement, long offset, char value, @ConstantNodeParameter RiKind kind) {
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unused")
    @NodeIntrinsic
    public static void store(Object object, @ConstantNodeParameter int displacement, long offset, double value, @ConstantNodeParameter RiKind kind) {
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unused")
    @NodeIntrinsic
    public static void store(Object object, @ConstantNodeParameter int displacement, long offset, float value, @ConstantNodeParameter RiKind kind) {
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unused")
    @NodeIntrinsic
    public static void store(Object object, @ConstantNodeParameter int displacement, long offset, int value, @ConstantNodeParameter RiKind kind) {
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unused")
    @NodeIntrinsic
    public static void store(Object object, @ConstantNodeParameter int displacement, long offset, long value, @ConstantNodeParameter RiKind kind) {
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unused")
    @NodeIntrinsic
    public static void store(Object object, @ConstantNodeParameter int displacement, long offset, short value, @ConstantNodeParameter RiKind kind) {
        throw new UnsupportedOperationException();
    }

}
