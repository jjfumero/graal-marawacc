/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.lir.alloc.trace;

import java.util.List;

import jdk.internal.jvmci.code.TargetDescription;

import com.oracle.graal.compiler.common.alloc.RegisterAllocationConfig;
import com.oracle.graal.compiler.common.cfg.AbstractBlockBase;
import com.oracle.graal.lir.gen.LIRGenerationResult;
import com.oracle.graal.lir.gen.LIRGeneratorTool.SpillMoveFactory;
import com.oracle.graal.lir.phases.LIRPhase;

public abstract class TraceAllocationPhase extends LIRPhase<TraceAllocationPhase.TraceAllocationContext> {

    public static final class TraceAllocationContext {
        private final SpillMoveFactory spillMoveFactory;
        private final RegisterAllocationConfig registerAllocationConfig;

        public TraceAllocationContext(SpillMoveFactory spillMoveFactory, RegisterAllocationConfig registerAllocationConfig) {
            this.spillMoveFactory = spillMoveFactory;
            this.registerAllocationConfig = registerAllocationConfig;
        }
    }

    @Override
    protected final <B extends AbstractBlockBase<B>> void run(TargetDescription target, LIRGenerationResult lirGenRes, List<B> codeEmittingOrder, List<B> linearScanOrder,
                    TraceAllocationContext context) {
        run(target, lirGenRes, codeEmittingOrder, linearScanOrder, context.spillMoveFactory, context.registerAllocationConfig);
    }

    protected abstract <B extends AbstractBlockBase<B>> void run(TargetDescription target, LIRGenerationResult lirGenRes, List<B> codeEmittingOrder, List<B> linearScanOrder,
                    SpillMoveFactory spillMoveFactory, RegisterAllocationConfig registerAllocationConfig);

}
