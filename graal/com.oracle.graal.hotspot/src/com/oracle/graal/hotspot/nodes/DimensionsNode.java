/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.compiler.target.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.snippets.*;


/**
 * Intrinsic for allocating an on-stack array of integers to hold the dimensions
 * of a multianewarray instruction.
 */
public final class DimensionsNode extends FixedWithNextNode implements LIRGenLowerable, MonitorEnter {

    private final int rank;

    public DimensionsNode(int rank) {
        super(HotSpotGraalRuntime.wordStamp());
        this.rank = rank;
    }

    @Override
    public void generate(LIRGenerator gen) {
        int size = rank * 4;
        StackSlot array = gen.frameMap().allocateStackBlock(size, false);
        Value result = gen.emitLea(array);
        gen.setResult(this, result);
    }

    @NodeIntrinsic
    public static native Word allocaDimsArray(@ConstantNodeParameter int rank);
}
