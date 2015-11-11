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

import static com.oracle.graal.compiler.common.GraalOptions.DetailedAsserts;
import static com.oracle.graal.lir.LIRValueUtil.isConstantValue;
import static com.oracle.graal.lir.LIRValueUtil.isStackSlotValue;
import static com.oracle.graal.lir.LIRValueUtil.isVariable;
import static com.oracle.graal.lir.LIRValueUtil.isVirtualStackSlot;
import static com.oracle.graal.lir.alloc.trace.TraceRegisterAllocationPhase.Options.TraceRAshareSpillInformation;
import static jdk.vm.ci.code.ValueUtil.isIllegal;
import static jdk.vm.ci.code.ValueUtil.isRegister;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Value;

import com.oracle.graal.compiler.common.alloc.RegisterAllocationConfig;
import com.oracle.graal.compiler.common.alloc.TraceBuilder.TraceBuilderResult;
import com.oracle.graal.compiler.common.cfg.AbstractBlockBase;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.Indent;
import com.oracle.graal.lir.ConstantValue;
import com.oracle.graal.lir.InstructionValueProcedure;
import com.oracle.graal.lir.LIRFrameState;
import com.oracle.graal.lir.LIRInstruction;
import com.oracle.graal.lir.LIRInstruction.OperandFlag;
import com.oracle.graal.lir.LIRInstruction.OperandMode;
import com.oracle.graal.lir.StandardOp;
import com.oracle.graal.lir.StandardOp.BlockEndOp;
import com.oracle.graal.lir.StandardOp.LabelOp;
import com.oracle.graal.lir.StandardOp.MoveOp;
import com.oracle.graal.lir.StandardOp.ValueMoveOp;
import com.oracle.graal.lir.Variable;
import com.oracle.graal.lir.gen.LIRGenerationResult;
import com.oracle.graal.lir.gen.LIRGeneratorTool.MoveFactory;

/**
 * Specialization of {@link com.oracle.graal.lir.alloc.lsra.LinearScanAssignLocationsPhase} that
 * inserts {@link ShadowedRegisterValue}s to describe {@link RegisterValue}s that are also available
 * on the {@link StackSlot stack}.
 */
final class TraceLinearScanAssignLocationsPhase extends TraceLinearScanAllocationPhase {

    @Override
    protected <B extends AbstractBlockBase<B>> void run(TargetDescription target, LIRGenerationResult lirGenRes, List<B> codeEmittingOrder, List<B> linearScanOrder, MoveFactory spillMoveFactory,
                    RegisterAllocationConfig registerAllocationConfig, TraceBuilderResult<?> traceBuilderResult, TraceLinearScan allocator) {
        new Assigner(allocator).assignLocations();
    }

    private static final class Assigner {
        private final TraceLinearScan allocator;

        private Assigner(TraceLinearScan allocator) {
            this.allocator = allocator;
        }

        /**
         * Assigns the allocated location for an LIR instruction operand back into the instruction.
         *
         * @param op current {@link LIRInstruction}
         * @param operand an LIR instruction operand
         * @param mode the usage mode for {@code operand} by the instruction
         * @return the location assigned for the operand
         */
        private Value colorLirOperand(LIRInstruction op, Variable operand, OperandMode mode) {
            int opId = op.id();
            TraceInterval interval = allocator.intervalFor(operand);
            assert interval != null : "interval must exist";

            if (opId != -1) {
                if (DetailedAsserts.getValue()) {
                    AbstractBlockBase<?> block = allocator.blockForId(opId);
                    if (block.getSuccessorCount() <= 1 && opId == allocator.getLastLirInstructionId(block)) {
                        /*
                         * Check if spill moves could have been appended at the end of this block,
                         * but before the branch instruction. So the split child information for
                         * this branch would be incorrect.
                         */
                        LIRInstruction instr = allocator.getLIR().getLIRforBlock(block).get(allocator.getLIR().getLIRforBlock(block).size() - 1);
                        if (instr instanceof StandardOp.JumpOp) {
                            if (allocator.getBlockData(block).liveOut.get(allocator.operandNumber(operand))) {
                                assert false : String.format(
                                                "can't get split child for the last branch of a block because the information would be incorrect (moves are inserted before the branch in resolveDataFlow) block=%s, instruction=%s, operand=%s",
                                                block, instr, operand);
                            }
                        }
                    }
                }

                /*
                 * Operands are not changed when an interval is split during allocation, so search
                 * the right interval here.
                 */
                interval = allocator.splitChildAtOpId(interval, opId, mode);
            }

            if (isIllegal(interval.location()) && interval.canMaterialize()) {
                assert mode != OperandMode.DEF;
                return new ConstantValue(interval.kind(), interval.getMaterializedValue());
            }
            return interval.location();
        }

        /**
         * @param op
         * @param operand
         * @param valueMode
         * @param flags
         * @see InstructionValueProcedure#doValue(LIRInstruction, Value, OperandMode, EnumSet)
         */
        private Value debugInfoProcedure(LIRInstruction op, Value operand, OperandMode valueMode, EnumSet<OperandFlag> flags) {
            if (isVirtualStackSlot(operand)) {
                return operand;
            }
            int tempOpId = op.id();
            OperandMode mode = OperandMode.USE;
            AbstractBlockBase<?> block = allocator.blockForId(tempOpId);
            if (block.getSuccessorCount() == 1 && tempOpId == allocator.getLastLirInstructionId(block)) {
                /*
                 * Generating debug information for the last instruction of a block. If this
                 * instruction is a branch, spill moves are inserted before this branch and so the
                 * wrong operand would be returned (spill moves at block boundaries are not
                 * considered in the live ranges of intervals).
                 *
                 * Solution: use the first opId of the branch target block instead.
                 */
                final LIRInstruction instr = allocator.getLIR().getLIRforBlock(block).get(allocator.getLIR().getLIRforBlock(block).size() - 1);
                if (instr instanceof StandardOp.JumpOp) {
                    if (allocator.getBlockData(block).liveOut.get(allocator.operandNumber(operand))) {
                        tempOpId = allocator.getFirstLirInstructionId(block.getSuccessors().iterator().next());
                        mode = OperandMode.DEF;
                    }
                }
            }

            /*
             * Get current location of operand. The operand must be live because debug information
             * is considered when building the intervals if the interval is not live,
             * colorLirOperand will cause an assert on failure.
             */
            Value result = colorLirOperand(op, (Variable) operand, mode);
            assert !allocator.hasCall(tempOpId) || isStackSlotValue(result) || isConstantValue(result) || !allocator.isCallerSave(result) : "cannot have caller-save register operands at calls";
            return result;
        }

        private void computeDebugInfo(final LIRInstruction op, LIRFrameState info) {
            info.forEachState(op, this::debugInfoProcedure);
        }

        private void assignLocations(List<LIRInstruction> instructions) {
            int numInst = instructions.size();
            boolean hasDead = false;

            for (int j = 0; j < numInst; j++) {
                final LIRInstruction op = instructions.get(j);
                if (op == null) {
                    /*
                     * this can happen when spill-moves are removed in eliminateSpillMoves
                     */
                    hasDead = true;
                } else if (assignLocations(op)) {
                    instructions.set(j, null);
                    hasDead = true;
                }
            }

            if (hasDead) {
                // Remove null values from the list.
                instructions.removeAll(Collections.singleton(null));
            }
        }

        /**
         * Assigns the operand of an {@link LIRInstruction}.
         *
         * @param op The {@link LIRInstruction} that should be colored.
         * @return {@code true} if the instruction should be deleted.
         */
        private boolean assignLocations(LIRInstruction op) {
            assert op != null;
            if (TraceRAshareSpillInformation.getValue()) {
                if (op instanceof BlockEndOp) {
                    ((BlockEndOp) op).forEachOutgoingValue(colorOutgoingIncomingValues);
                } else if (op instanceof LabelOp) {
                    ((LabelOp) op).forEachIncomingValue(colorOutgoingIncomingValues);
                }
            }

            InstructionValueProcedure assignProc = (inst, operand, mode, flags) -> isVariable(operand) ? colorLirOperand(inst, (Variable) operand, mode) : operand;
            // remove useless moves
            if (op instanceof MoveOp) {
                AllocatableValue result = ((MoveOp) op).getResult();
                if (isVariable(result) && allocator.isMaterialized(result, op.id(), OperandMode.DEF)) {
                    /*
                     * This happens if a materializable interval is originally not spilled but then
                     * kicked out in LinearScanWalker.splitForSpilling(). When kicking out such an
                     * interval this move operation was already generated.
                     */
                    return true;
                }
            }

            op.forEachInput(assignProc);
            op.forEachAlive(assignProc);
            op.forEachTemp(assignProc);
            op.forEachOutput(assignProc);

            // compute reference map and debug information
            op.forEachState((inst, state) -> computeDebugInfo(inst, state));

            // remove useless moves
            if (op instanceof ValueMoveOp) {
                ValueMoveOp move = (ValueMoveOp) op;
                if (move.getInput().equals(move.getResult())) {
                    return true;
                }
            }
            return false;
        }

        @SuppressWarnings("try")
        private void assignLocations() {
            try (Indent indent = Debug.logAndIndent("assign locations")) {
                for (AbstractBlockBase<?> block : allocator.sortedBlocks()) {
                    try (Indent indent2 = Debug.logAndIndent("assign locations in block B%d", block.getId())) {
                        assignLocations(allocator.getLIR().getLIRforBlock(block));
                    }
                }
            }
        }

        private InstructionValueProcedure colorOutgoingIncomingValues = new InstructionValueProcedure() {

            public Value doValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
                if (isVariable(value)) {
                    TraceInterval interval = allocator.intervalFor(value);
                    assert interval != null : "interval must exist";
                    interval = allocator.splitChildAtOpId(interval, instruction.id(), mode);

                    if (interval.alwaysInMemory() && isRegister(interval.location())) {
                        return new ShadowedRegisterValue((RegisterValue) interval.location(), interval.spillSlot());
                    }
                }
                return value;
            }
        };
    }

}
