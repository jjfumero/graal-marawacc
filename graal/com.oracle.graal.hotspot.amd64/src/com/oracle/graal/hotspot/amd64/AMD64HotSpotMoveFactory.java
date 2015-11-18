/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.amd64;

import jdk.vm.ci.hotspot.HotSpotCompressedNullConstant;
import jdk.vm.ci.hotspot.HotSpotConstant;
import jdk.vm.ci.hotspot.HotSpotMetaspaceConstant;
import jdk.vm.ci.hotspot.HotSpotObjectConstant;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaConstant;

import com.oracle.graal.compiler.amd64.AMD64MoveFactory;
import com.oracle.graal.lir.amd64.AMD64LIRInstruction;
import com.oracle.graal.lir.framemap.FrameMapBuilder;

public class AMD64HotSpotMoveFactory extends AMD64MoveFactory {

    public AMD64HotSpotMoveFactory(BackupSlotProvider backupSlotProvider, FrameMapBuilder frameMapBuilder) {
        super(backupSlotProvider, frameMapBuilder);
    }

    @Override
    public boolean canInlineConstant(JavaConstant c) {
        if (HotSpotCompressedNullConstant.COMPRESSED_NULL.equals(c)) {
            return true;
        } else if (c instanceof HotSpotObjectConstant) {
            return ((HotSpotObjectConstant) c).isCompressed();
        } else {
            return super.canInlineConstant(c);
        }
    }

    @Override
    public boolean allowConstantToStackMove(Constant value) {
        if (value instanceof HotSpotConstant) {
            return ((HotSpotConstant) value).isCompressed();
        }
        return true;
    }

    @Override
    public AMD64LIRInstruction createLoad(AllocatableValue dst, Constant src) {
        if (HotSpotCompressedNullConstant.COMPRESSED_NULL.equals(src)) {
            return super.createLoad(dst, JavaConstant.INT_0);
        } else if (src instanceof HotSpotObjectConstant) {
            return new AMD64HotSpotMove.HotSpotLoadObjectConstantOp(dst, (HotSpotObjectConstant) src);
        } else if (src instanceof HotSpotMetaspaceConstant) {
            return new AMD64HotSpotMove.HotSpotLoadMetaspaceConstantOp(dst, (HotSpotMetaspaceConstant) src);
        } else {
            return super.createLoad(dst, src);
        }
    }
}
