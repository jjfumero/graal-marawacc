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
package com.oracle.graal.nodes;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.nodes.extended.*;

public class G1ReferentFieldReadBarrier extends WriteBarrier implements DeoptimizingNode {

    @Input private ValueNode expectedObject;
    private final boolean doLoad;

    @Input private FrameState deoptimizationState;
    private final boolean nullCheck;

    public ValueNode getExpectedObject() {
        return expectedObject;
    }

    public boolean doLoad() {
        return doLoad;
    }

    public boolean getNullCheck() {
        return nullCheck;
    }

    public G1ReferentFieldReadBarrier(ValueNode object, ValueNode expectedObject, LocationNode location, boolean doLoad, boolean nullCheck) {
        super(object, location, true);
        this.doLoad = doLoad;
        this.nullCheck = nullCheck;
        this.expectedObject = expectedObject;
    }

    @Override
    public boolean canDeoptimize() {
        return nullCheck;
    }

    @Override
    public FrameState getDeoptimizationState() {
        return deoptimizationState;
    }

    @Override
    public void setDeoptimizationState(FrameState state) {
        updateUsages(deoptimizationState, state);
        deoptimizationState = state;
    }

    @Override
    public DeoptimizationReason getDeoptimizationReason() {
        return DeoptimizationReason.NullCheckException;
    }
}
