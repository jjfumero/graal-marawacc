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
package com.oracle.graal.compiler.phases;

import com.oracle.graal.lir.phases.AllocationPhase.AllocationContext;
import com.oracle.graal.lir.phases.EconomyAllocationStage;
import com.oracle.graal.lir.phases.EconomyPostAllocationOptimizationStage;
import com.oracle.graal.lir.phases.EconomyPreAllocationOptimizationStage;
import com.oracle.graal.lir.phases.LIRPhaseSuite;
import com.oracle.graal.lir.phases.PostAllocationOptimizationPhase.PostAllocationOptimizationContext;
import com.oracle.graal.lir.phases.PreAllocationOptimizationPhase.PreAllocationOptimizationContext;
import com.oracle.graal.phases.PhaseSuite;
import com.oracle.graal.phases.tiers.CompilerConfiguration;
import com.oracle.graal.phases.tiers.HighTierContext;
import com.oracle.graal.phases.tiers.LowTierContext;
import com.oracle.graal.phases.tiers.MidTierContext;

public class EconomyCompilerConfiguration implements CompilerConfiguration {

    public PhaseSuite<HighTierContext> createHighTier() {
        return new EconomyHighTier();
    }

    public PhaseSuite<MidTierContext> createMidTier() {
        return new EconomyMidTier();
    }

    public PhaseSuite<LowTierContext> createLowTier() {
        return new EconomyLowTier();
    }

    public LIRPhaseSuite<PreAllocationOptimizationContext> createPreAllocationOptimizationStage() {
        return new EconomyPreAllocationOptimizationStage();
    }

    public LIRPhaseSuite<AllocationContext> createAllocationStage() {
        return new EconomyAllocationStage();
    }

    public LIRPhaseSuite<PostAllocationOptimizationContext> createPostAllocationOptimizationStage() {
        return new EconomyPostAllocationOptimizationStage();
    }

}
