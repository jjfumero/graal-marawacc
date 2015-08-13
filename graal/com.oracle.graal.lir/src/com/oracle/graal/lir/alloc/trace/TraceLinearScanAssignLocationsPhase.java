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

import static jdk.internal.jvmci.code.ValueUtil.*;
import jdk.internal.jvmci.code.*;
import jdk.internal.jvmci.meta.*;

import com.oracle.graal.compiler.common.alloc.TraceBuilder.TraceBuilderResult;
import com.oracle.graal.compiler.common.cfg.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.LIRInstruction.OperandMode;
import com.oracle.graal.lir.StandardOp.BlockEndOp;
import com.oracle.graal.lir.alloc.lsra.*;

/**
 * Specialization of {@link LinearScanAssignLocationsPhase} that inserts
 * {@link ShadowedRegisterValue}s to describe {@link RegisterValue}s that are also available on the
 * {@link StackSlotValue stack}.
 */
class TraceLinearScanAssignLocationsPhase extends LinearScanAssignLocationsPhase {

    private final TraceBuilderResult<?> traceBuilderResult;

    TraceLinearScanAssignLocationsPhase(LinearScan allocator, TraceBuilderResult<?> traceBuilderResult) {
        super(allocator);
        this.traceBuilderResult = traceBuilderResult;
    }

    @Override
    protected Value colorLirOperand(LIRInstruction op, Variable operand, OperandMode mode) {
        if (!isBlockEndWithEdgeToUnallocatedTrace(op, mode)) {
            return super.colorLirOperand(op, operand, mode);
        }

        int opId = op.id();
        Interval interval = allocator.intervalFor(operand);
        assert interval != null : "interval must exist";

        /*
         * Operands are not changed when an interval is split during allocation, so search the right
         * interval here.
         */
        interval = allocator.splitChildAtOpId(interval, opId, mode);

        if (isIllegal(interval.location()) && interval.canMaterialize()) {
            assert mode != OperandMode.DEF;
            return interval.getMaterializedValue();
        }
        if (interval.alwaysInMemory() && isRegister(interval.location())) {
            return new ShadowedRegisterValue((RegisterValue) interval.location(), interval.spillSlot());
        }
        return interval.location();
    }

    private boolean isBlockEndWithEdgeToUnallocatedTrace(LIRInstruction op, OperandMode mode) {
        if (!(op instanceof BlockEndOp) || !OperandMode.ALIVE.equals(mode)) {
            return false;
        }
        AbstractBlockBase<?> block = allocator.blockForId(op.id());
        int currentTrace = traceBuilderResult.getTraceForBlock(block);

        for (AbstractBlockBase<?> succ : block.getSuccessors()) {
            if (currentTrace < traceBuilderResult.getTraceForBlock(succ)) {
                // succ is not yet allocated
                return true;
            }
        }
        return false;
    }

}
