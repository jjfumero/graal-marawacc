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

import static com.oracle.graal.compiler.common.BackendOptions.UserOptions.TraceRA;

import com.oracle.graal.compiler.common.GraalOptions;
import com.oracle.graal.lir.alloc.AllocationStageVerifier;
import com.oracle.graal.lir.alloc.lsra.LinearScanPhase;
import com.oracle.graal.lir.alloc.trace.TraceRegisterAllocationPhase;
import com.oracle.graal.lir.dfa.LocationMarkerPhase;
import com.oracle.graal.lir.dfa.MarkBasePointersPhase;
import com.oracle.graal.lir.phases.AllocationPhase.AllocationContext;
import com.oracle.graal.lir.stackslotalloc.LSStackSlotAllocator;
import com.oracle.graal.lir.stackslotalloc.SimpleStackSlotAllocator;

public class AllocationStage extends LIRPhaseSuite<AllocationContext> {
    public AllocationStage() {
        appendPhase(new MarkBasePointersPhase());
        if (TraceRA.getValue()) {
            appendPhase(new TraceRegisterAllocationPhase());
        } else {
            appendPhase(new LinearScanPhase());
        }

        // build frame map
        if (LSStackSlotAllocator.Options.LIROptLSStackSlotAllocator.getValue()) {
            appendPhase(new LSStackSlotAllocator());
        } else {
            appendPhase(new SimpleStackSlotAllocator());
        }
        // currently we mark locations only if we do register allocation
        appendPhase(new LocationMarkerPhase());

        if (GraalOptions.DetailedAsserts.getValue()) {
            appendPhase(new AllocationStageVerifier());
        }
    }
}
