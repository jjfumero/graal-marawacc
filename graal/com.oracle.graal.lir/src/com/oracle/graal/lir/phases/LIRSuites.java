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
package com.oracle.graal.lir.phases;

import com.oracle.graal.api.code.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.gen.*;
import com.oracle.graal.lir.phases.PreAllocationOptimizationPhase.PreAllocationOptimizationContext;
import com.oracle.graal.lir.phases.PostAllocationOptimizationPhase.PostAllocationOptimizationContext;
import com.oracle.graal.lir.phases.AllocationPhase.AllocationContext;

public class LIRSuites {

    private final LIRPhaseSuite<PreAllocationOptimizationContext> preAllocOptStage;
    private final LIRPhaseSuite<AllocationContext> allocStage;
    private final LIRPhaseSuite<PostAllocationOptimizationContext> postAllocStage;

    public LIRSuites(LIRPhaseSuite<PreAllocationOptimizationContext> preAllocOptStage, LIRPhaseSuite<AllocationContext> allocStage, LIRPhaseSuite<PostAllocationOptimizationContext> postAllocStage) {
        this.preAllocOptStage = preAllocOptStage;
        this.allocStage = allocStage;
        this.postAllocStage = postAllocStage;
    }

    /**
     * {@link PreAllocationOptimizationPhase}s are executed between {@link LIR} generation and
     * register allocation.
     * <p>
     * {@link PreAllocationOptimizationPhase Implementers} can create new
     * {@link LIRGeneratorTool#newVariable variables}, {@link LIRGenerationResult#getFrameMap stack
     * slots} and {@link LIRGenerationResult#getFrameMapBuilder virtual stack slots}.
     */
    public LIRPhaseSuite<PreAllocationOptimizationContext> getPreAllocationOptimizationStage() {
        return preAllocOptStage;
    }

    /**
     * {@link AllocationPhase}s are responsible for register allocation and translating
     * {@link VirtualStackSlot}s into {@link StackSlot}s.
     * <p>
     * After the {@link AllocationStage} there should be no more {@link Variable}s and
     * {@link VirtualStackSlot}s.
     */
    public LIRPhaseSuite<AllocationContext> getAllocationStage() {
        return allocStage;
    }

    /**
     * {@link PostAllocationOptimizationPhase}s are executed after register allocation and before
     * machine code generation.
     * <p>
     * A {@link PostAllocationOptimizationPhase} must not introduce new {@link Variable}s,
     * {@link VirtualStackSlot}s or {@link StackSlot}s.
     */
    public LIRPhaseSuite<PostAllocationOptimizationContext> getPostAllocationOptimizationStage() {
        return postAllocStage;
    }

}
