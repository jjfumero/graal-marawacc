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

import static com.oracle.graal.lir.phases.LIRPhase.Options.LIROptimization;
import jdk.vm.ci.options.NestedBooleanOptionValue;
import jdk.vm.ci.options.Option;
import jdk.vm.ci.options.OptionType;
import jdk.vm.ci.options.OptionValue;

import com.oracle.graal.lir.ControlFlowOptimizer;
import com.oracle.graal.lir.EdgeMoveOptimizer;
import com.oracle.graal.lir.NullCheckOptimizer;
import com.oracle.graal.lir.RedundantMoveElimination;
import com.oracle.graal.lir.phases.PostAllocationOptimizationPhase.PostAllocationOptimizationContext;
import com.oracle.graal.lir.profiling.MoveProfiling;

public class PostAllocationOptimizationStage extends LIRPhaseSuite<PostAllocationOptimizationContext> {
    public static class Options {
        // @formatter:off
        @Option(help = "", type = OptionType.Debug)
        public static final NestedBooleanOptionValue LIROptEdgeMoveOptimizer = new NestedBooleanOptionValue(LIROptimization, true);
        @Option(help = "", type = OptionType.Debug)
        public static final NestedBooleanOptionValue LIROptControlFlowOptimizer = new NestedBooleanOptionValue(LIROptimization, true);
        @Option(help = "", type = OptionType.Debug)
        public static final NestedBooleanOptionValue LIROptRedundantMoveElimination = new NestedBooleanOptionValue(LIROptimization, true);
        @Option(help = "", type = OptionType.Debug)
        public static final NestedBooleanOptionValue LIROptNullCheckOptimizer = new NestedBooleanOptionValue(LIROptimization, true);
        @Option(help = "Enables profiling of move types on LIR level. " +
                       "Move types are for example stores (register to stack), " +
                       "constant loads (constant to register) or copies (register to register).", type = OptionType.Debug)
        public static final OptionValue<Boolean> LIRProfileMoves = new OptionValue<>(false);
        // @formatter:on
    }

    public PostAllocationOptimizationStage() {
        if (Options.LIROptEdgeMoveOptimizer.getValue()) {
            appendPhase(new EdgeMoveOptimizer());
        }
        if (Options.LIROptControlFlowOptimizer.getValue()) {
            appendPhase(new ControlFlowOptimizer());
        }
        if (Options.LIROptRedundantMoveElimination.getValue()) {
            appendPhase(new RedundantMoveElimination());
        }
        if (Options.LIROptNullCheckOptimizer.getValue()) {
            appendPhase(new NullCheckOptimizer());
        }
        if (Options.LIRProfileMoves.getValue()) {
            appendPhase(new MoveProfiling());
        }
    }
}
