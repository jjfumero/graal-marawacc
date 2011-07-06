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
package com.oracle.max.graal.runtime.nodes;

import com.oracle.max.graal.compiler.gen.*;
import com.oracle.max.graal.compiler.ir.*;
import com.oracle.max.graal.compiler.lir.*;
import com.oracle.max.graal.graph.*;
import com.oracle.max.graal.runtime.*;
import com.sun.cri.ci.*;


public abstract class WriteBarrier extends FixedNodeWithNext {
    private static final int INPUT_COUNT = 0;
    private static final int SUCCESSOR_COUNT = 0;

    public WriteBarrier(int inputCount, int successorCount, Graph graph) {
        super(CiKind.Illegal, inputCount + INPUT_COUNT, successorCount + SUCCESSOR_COUNT, graph);
    }


    protected void generateBarrier(CiValue temp, LIRGenerator generator) {
        HotSpotVMConfig config = CompilerImpl.getInstance().getConfig();
        generator.lir().unsignedShiftRight(temp, CiConstant.forInt(config.cardtableShift), temp, CiValue.IllegalValue);

        long startAddress = config.cardtableStartAddress;
        int displacement = 0;
        if (((int) startAddress) == startAddress) {
            displacement = (int) startAddress;
        } else {
            generator.lir().add(temp, CiConstant.forLong(config.cardtableStartAddress), temp);
        }
        generator.lir().move(CiConstant.FALSE, new CiAddress(CiKind.Boolean, temp, displacement), (LIRDebugInfo) null);
    }
}
