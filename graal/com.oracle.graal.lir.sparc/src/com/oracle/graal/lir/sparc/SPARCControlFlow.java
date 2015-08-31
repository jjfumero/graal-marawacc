/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.lir.sparc;

import static com.oracle.graal.asm.sparc.SPARCAssembler.*;
import static com.oracle.graal.asm.sparc.SPARCAssembler.Annul.*;
import static com.oracle.graal.asm.sparc.SPARCAssembler.BranchPredict.*;
import static com.oracle.graal.asm.sparc.SPARCAssembler.CC.*;
import static com.oracle.graal.asm.sparc.SPARCAssembler.ConditionFlag.*;
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.*;
import static com.oracle.graal.lir.LIRValueUtil.*;
import static com.oracle.graal.lir.sparc.SPARCMove.*;
import static jdk.internal.jvmci.code.ValueUtil.*;
import static jdk.internal.jvmci.sparc.SPARC.*;

import java.util.*;

import jdk.internal.jvmci.code.*;
import jdk.internal.jvmci.common.*;
import jdk.internal.jvmci.meta.*;
import jdk.internal.jvmci.sparc.*;
import jdk.internal.jvmci.sparc.SPARC.CPUFeature;

import com.oracle.graal.asm.*;
import com.oracle.graal.asm.Assembler.LabelHint;
import com.oracle.graal.asm.sparc.*;
import com.oracle.graal.asm.sparc.SPARCAssembler.BranchPredict;
import com.oracle.graal.asm.sparc.SPARCAssembler.CC;
import com.oracle.graal.asm.sparc.SPARCAssembler.ConditionFlag;
import com.oracle.graal.asm.sparc.SPARCMacroAssembler.ScratchRegister;
import com.oracle.graal.asm.sparc.SPARCMacroAssembler.Setx;
import com.oracle.graal.compiler.common.calc.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.SwitchStrategy.BaseSwitchClosure;
import com.oracle.graal.lir.asm.*;

public class SPARCControlFlow {
    // This describes the maximum offset between the first emitted (load constant in to scratch,
    // if does not fit into simm5 of cbcond) instruction and the final branch instruction
    private static final int maximumSelfOffsetInstructions = 2;

    public static final class ReturnOp extends SPARCBlockEndOp {
        public static final LIRInstructionClass<ReturnOp> TYPE = LIRInstructionClass.create(ReturnOp.class);
        public static final SizeEstimate SIZE = SizeEstimate.create(2);

        @Use({REG, ILLEGAL}) protected Value x;

        public ReturnOp(Value x) {
            super(TYPE, SIZE);
            this.x = x;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            emitCodeHelper(crb, masm);
        }

        public static void emitCodeHelper(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            masm.ret();
            // On SPARC we always leave the frame (in the delay slot).
            crb.frameContext.leave(crb);
        }
    }

    public static final class CompareBranchOp extends SPARCBlockEndOp implements SPARCDelayedControlTransfer {
        public static final LIRInstructionClass<CompareBranchOp> TYPE = LIRInstructionClass.create(CompareBranchOp.class);
        public static final SizeEstimate SIZE = SizeEstimate.create(3);
        static final EnumSet<Kind> SUPPORTED_KINDS = EnumSet.of(Kind.Long, Kind.Int, Kind.Object, Kind.Float, Kind.Double);

        private final SPARCCompare opcode;
        @Use({REG}) protected Value x;
        @Use({REG, CONST}) protected Value y;
        private ConditionFlag conditionFlag;
        protected final LabelRef trueDestination;
        protected LabelHint trueDestinationHint;
        protected final LabelRef falseDestination;
        protected LabelHint falseDestinationHint;
        protected final Kind kind;
        protected final boolean unorderedIsTrue;
        private boolean emitted = false;
        private int delaySlotPosition = -1;
        private double trueDestinationProbability;

        public CompareBranchOp(SPARCCompare opcode, Value x, Value y, Condition condition, LabelRef trueDestination, LabelRef falseDestination, Kind kind, boolean unorderedIsTrue,
                        double trueDestinationProbability) {
            super(TYPE, SIZE);
            this.opcode = opcode;
            this.x = x;
            this.y = y;
            this.trueDestination = trueDestination;
            this.falseDestination = falseDestination;
            this.kind = kind;
            this.unorderedIsTrue = unorderedIsTrue;
            this.trueDestinationProbability = trueDestinationProbability;
            CC conditionCodeReg = CC.forKind(kind);
            conditionFlag = fromCondition(conditionCodeReg, condition, unorderedIsTrue);
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            if (emitted) { // Only if delayed control transfer is used we must check this
                assert masm.position() - delaySlotPosition == 4 : "Only one instruction can be stuffed into the delay slot";
            }
            if (!emitted) {
                requestHints(masm);
                int targetPosition = getTargetPosition(masm);
                if (canUseShortBranch(crb, masm, targetPosition)) {
                    emitted = emitShortCompareBranch(crb, masm);
                }
                if (!emitted) { // No short compare/branch was used, so we go into fallback
                    SPARCCompare.emit(crb, masm, opcode, x, y);
                    emitted = emitBranch(crb, masm, kind, conditionFlag, trueDestination, falseDestination, true, trueDestinationProbability);
                }
            }
            assert emitted;
        }

        private static int getTargetPosition(Assembler asm) {
            return asm.position() + maximumSelfOffsetInstructions * asm.target.wordSize;
        }

        public void emitControlTransfer(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            requestHints(masm);
            // When we use short branches, no delay slot is available
            int targetPosition = getTargetPosition(masm);
            if (!canUseShortBranch(crb, masm, targetPosition)) {
                SPARCCompare.emit(crb, masm, opcode, x, y);
                emitted = emitBranch(crb, masm, kind, conditionFlag, trueDestination, falseDestination, false, trueDestinationProbability);
                if (emitted) {
                    delaySlotPosition = masm.position();
                }
            }
        }

        private void requestHints(SPARCMacroAssembler masm) {
            if (trueDestinationHint == null) {
                this.trueDestinationHint = masm.requestLabelHint(trueDestination.label());
            }
            if (falseDestinationHint == null) {
                this.falseDestinationHint = masm.requestLabelHint(falseDestination.label());
            }
        }

        /**
         * Tries to use the emit the compare/branch instruction.
         * <p>
         * CBcond has follwing limitations
         * <ul>
         * <li>Immediate field is only 5 bit and is on the right
         * <li>Jump offset is maximum of -+512 instruction
         *
         * <p>
         * We get from outside
         * <ul>
         * <li>at least one of trueDestination falseDestination is within reach of +-512
         * instructions
         * <li>two registers OR one register and a constant which fits simm13
         *
         * <p>
         * We do:
         * <ul>
         * <li>find out which target needs to be branched conditionally
         * <li>find out if fall-through is possible, if not, a unconditional branch is needed after
         * cbcond (needJump=true)
         * <li>if no fall through: we need to put the closer jump into the cbcond branch and the
         * farther into the jmp (unconditional branch)
         * <li>if constant on the left side, mirror to be on the right
         * <li>if constant on right does not fit into simm5, put it into a scratch register
         *
         * @param crb
         * @param masm
         * @return true if the branch could be emitted
         */
        private boolean emitShortCompareBranch(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            Value tmpValue;
            Value actualX = x;
            Value actualY = y;
            ConditionFlag actualConditionFlag = conditionFlag;
            Label actualTrueTarget = trueDestination.label();
            Label actualFalseTarget = falseDestination.label();
            Label tmpTarget;
            boolean needJump;
            if (crb.isSuccessorEdge(trueDestination)) {
                actualConditionFlag = conditionFlag.negate();
                tmpTarget = actualTrueTarget;
                actualTrueTarget = actualFalseTarget;
                actualFalseTarget = tmpTarget;
                needJump = false;
            } else {
                needJump = !crb.isSuccessorEdge(falseDestination);
                int targetPosition = getTargetPosition(masm);
                if (needJump && !isShortBranch(masm, targetPosition, trueDestinationHint, actualTrueTarget)) {
                    // we have to jump in either way, so we must put the shorter
                    // branch into the actualTarget as only one of the two jump targets
                    // is guaranteed to be simm10
                    actualConditionFlag = actualConditionFlag.negate();
                    tmpTarget = actualTrueTarget;
                    actualTrueTarget = actualFalseTarget;
                    actualFalseTarget = tmpTarget;
                }
            }
            // Keep the constant on the right
            if (isJavaConstant(actualX)) {
                tmpValue = actualX;
                actualX = actualY;
                actualY = tmpValue;
                actualConditionFlag = actualConditionFlag.mirror();
            }
            try (ScratchRegister scratch = masm.getScratchRegister()) {
                emitCBCond(masm, actualX, actualY, actualTrueTarget, actualConditionFlag);
            }
            if (needJump) {
                masm.jmp(actualFalseTarget);
                masm.nop();
            }
            return true;
        }

        private static void emitCBCond(SPARCMacroAssembler masm, Value actualX, Value actualY, Label actualTrueTarget, ConditionFlag conditionFlag) {
            switch ((Kind) actualX.getLIRKind().getPlatformKind()) {
                case Int:
                    if (isJavaConstant(actualY)) {
                        int constantY = asJavaConstant(actualY).asInt();
                        CBCOND.emit(masm, conditionFlag, false, asIntReg(actualX), constantY, actualTrueTarget);
                    } else {
                        CBCOND.emit(masm, conditionFlag, false, asIntReg(actualX), asIntReg(actualY), actualTrueTarget);
                    }
                    break;
                case Long:
                    if (isJavaConstant(actualY)) {
                        int constantY = (int) asJavaConstant(actualY).asLong();
                        CBCOND.emit(masm, conditionFlag, true, asLongReg(actualX), constantY, actualTrueTarget);
                    } else {
                        CBCOND.emit(masm, conditionFlag, true, asLongReg(actualX), asLongReg(actualY), actualTrueTarget);
                    }
                    break;
                case Object:
                    if (isJavaConstant(actualY)) {
                        // Object constant valid can only be null
                        assert asJavaConstant(actualY).isNull();
                        CBCOND.emit(masm, conditionFlag, true, asObjectReg(actualX), 0, actualTrueTarget);
                    } else { // this is already loaded
                        CBCOND.emit(masm, conditionFlag, true, asObjectReg(actualX), asObjectReg(actualY), actualTrueTarget);
                    }
                    break;
                default:
                    JVMCIError.shouldNotReachHere();
            }
        }

        private boolean canUseShortBranch(CompilationResultBuilder crb, SPARCAssembler asm, int position) {
            if (!asm.hasFeature(CPUFeature.CBCOND)) {
                return false;
            }
            switch ((Kind) x.getPlatformKind()) {
                case Int:
                case Long:
                case Object:
                    break;
                default:
                    return false;
            }
            // Do not use short branch, if the y value is a constant and does not fit into simm5 but
            // fits into simm13; this means the code with CBcond would be longer as the code without
            // CBcond.
            if (isJavaConstant(y) && !isSimm5(asJavaConstant(y)) && isSimm13(asJavaConstant(y))) {
                return false;
            }
            boolean hasShortJumpTarget = false;
            if (!crb.isSuccessorEdge(trueDestination)) {
                hasShortJumpTarget |= isShortBranch(asm, position, trueDestinationHint, trueDestination.label());
            }
            if (!crb.isSuccessorEdge(falseDestination)) {
                hasShortJumpTarget |= isShortBranch(asm, position, falseDestinationHint, falseDestination.label());
            }
            return hasShortJumpTarget;
        }

        public void resetState() {
            emitted = false;
            delaySlotPosition = -1;
        }

        @Override
        public void verify() {
            super.verify();
            assert SUPPORTED_KINDS.contains(kind) : kind;
            assert x.getKind().equals(kind) && y.getKind().equals(kind) : x + " " + y;
        }
    }

    private static boolean isShortBranch(SPARCAssembler asm, int position, LabelHint hint, Label label) {
        int disp = 0;
        boolean dispValid = true;
        if (label.isBound()) {
            disp = label.position() - position;
        } else if (hint != null && hint.isValid()) {
            disp = hint.getTarget() - hint.getPosition();
        } else {
            dispValid = false;
        }
        if (dispValid) {
            if (disp < 0) {
                disp -= maximumSelfOffsetInstructions * asm.target.wordSize;
            } else {
                disp += maximumSelfOffsetInstructions * asm.target.wordSize;
            }
            return isSimm10(disp >> 2);
        } else if (hint == null) {
            asm.requestLabelHint(label);
        }
        return false;
    }

    public static final class BranchOp extends SPARCBlockEndOp implements StandardOp.BranchOp {
        public static final LIRInstructionClass<BranchOp> TYPE = LIRInstructionClass.create(BranchOp.class);
        public static final SizeEstimate SIZE = SizeEstimate.create(2);
        protected final ConditionFlag conditionFlag;
        protected final LabelRef trueDestination;
        protected final LabelRef falseDestination;
        protected final Kind kind;
        protected final double trueDestinationProbability;

        public BranchOp(ConditionFlag conditionFlag, LabelRef trueDestination, LabelRef falseDestination, Kind kind, double trueDestinationProbability) {
            super(TYPE, SIZE);
            this.trueDestination = trueDestination;
            this.falseDestination = falseDestination;
            this.kind = kind;
            this.conditionFlag = conditionFlag;
            this.trueDestinationProbability = trueDestinationProbability;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            emitBranch(crb, masm, kind, conditionFlag, trueDestination, falseDestination, true, trueDestinationProbability);
        }

        @Override
        public void verify() {
            assert CompareBranchOp.SUPPORTED_KINDS.contains(kind);
        }
    }

    private static boolean emitBranch(CompilationResultBuilder crb, SPARCMacroAssembler masm, Kind kind, ConditionFlag conditionFlag, LabelRef trueDestination, LabelRef falseDestination,
                    boolean withDelayedNop, double trueDestinationProbability) {
        Label actualTarget;
        ConditionFlag actualConditionFlag;
        boolean needJump;
        BranchPredict predictTaken;
        if (falseDestination != null && crb.isSuccessorEdge(trueDestination)) {
            actualConditionFlag = conditionFlag != null ? conditionFlag.negate() : null;
            actualTarget = falseDestination.label();
            needJump = false;
            predictTaken = trueDestinationProbability < .5d ? PREDICT_TAKEN : PREDICT_NOT_TAKEN;
        } else {
            actualConditionFlag = conditionFlag;
            actualTarget = trueDestination.label();
            needJump = falseDestination != null && !crb.isSuccessorEdge(falseDestination);
            predictTaken = trueDestinationProbability > .5d ? PREDICT_TAKEN : PREDICT_NOT_TAKEN;
        }
        if (!withDelayedNop && needJump) {
            // We cannot make use of the delay slot when we jump in true-case and false-case
            return false;
        }
        if (kind == Kind.Double || kind == Kind.Float) {
            masm.fbpcc(actualConditionFlag, NOT_ANNUL, actualTarget, CC.Fcc0, predictTaken);
        } else {
            CC cc = kind == Kind.Int ? CC.Icc : CC.Xcc;
            masm.bpcc(actualConditionFlag, NOT_ANNUL, actualTarget, cc, predictTaken);
        }
        if (withDelayedNop) {
            masm.nop();  // delay slot
        }
        if (needJump) {
            masm.jmp(falseDestination.label());
        }
        return true;
    }

    public static final class StrategySwitchOp extends SPARCBlockEndOp {
        public static final LIRInstructionClass<StrategySwitchOp> TYPE = LIRInstructionClass.create(StrategySwitchOp.class);
        protected JavaConstant[] keyConstants;
        private final LabelRef[] keyTargets;
        private LabelRef defaultTarget;
        @Alive({REG}) protected Value key;
        @Alive({REG, ILLEGAL}) protected Value constantTableBase;
        @Temp({REG}) protected Value scratch;
        private final SwitchStrategy strategy;
        private final Map<Label, LabelHint> labelHints;
        private final List<Label> conditionalLabels = new ArrayList<>();

        public StrategySwitchOp(Value constantTableBase, SwitchStrategy strategy, LabelRef[] keyTargets, LabelRef defaultTarget, Value key, Value scratch) {
            super(TYPE);
            this.strategy = strategy;
            this.keyConstants = strategy.keyConstants;
            this.keyTargets = keyTargets;
            this.defaultTarget = defaultTarget;
            this.constantTableBase = constantTableBase;
            this.key = key;
            this.scratch = scratch;
            this.labelHints = new HashMap<>();
            assert keyConstants.length == keyTargets.length;
            assert keyConstants.length == strategy.keyProbabilities.length;
        }

        @Override
        public void emitCode(final CompilationResultBuilder crb, final SPARCMacroAssembler masm) {
            final Register keyRegister = asRegister(key);
            final Register constantBaseRegister = AllocatableValue.ILLEGAL.equals(constantTableBase) ? g0 : asRegister(constantTableBase);
            BaseSwitchClosure closure = new BaseSwitchClosure(crb, masm, keyTargets, defaultTarget) {
                int conditionalLabelPointer = 0;

                /**
                 * This method caches the generated labels over two assembly passes to get
                 * information about branch lengths.
                 */
                @Override
                public Label conditionalJump(int index, Condition condition) {
                    Label label;
                    if (conditionalLabelPointer <= conditionalLabels.size()) {
                        label = new Label();
                        conditionalLabels.add(label);
                        conditionalLabelPointer = conditionalLabels.size();
                    } else {
                        // TODO: (sa) We rely here on the order how the labels are generated during
                        // code generation; if the order is not stable ower two assembly passes, the
                        // result can be wrong
                        label = conditionalLabels.get(conditionalLabelPointer++);
                    }
                    conditionalJump(index, condition, label);
                    return label;
                }

                @Override
                protected void conditionalJump(int index, Condition condition, Label target) {
                    JavaConstant constant = keyConstants[index];
                    CC conditionCode;
                    Long bits;
                    switch (key.getKind()) {
                        case Char:
                        case Byte:
                        case Short:
                        case Int:
                            conditionCode = CC.Icc;
                            bits = constant.asLong();
                            break;
                        case Long: {
                            conditionCode = CC.Xcc;
                            bits = constant.asLong();
                            break;
                        }
                        case Object: {
                            conditionCode = crb.codeCache.getTarget().wordKind == Kind.Long ? CC.Xcc : CC.Icc;
                            bits = constant.isDefaultForKind() ? 0L : null;
                            break;
                        }
                        default:
                            throw new JVMCIError("switch only supported for int, long and object");
                    }
                    ConditionFlag conditionFlag = fromCondition(conditionCode, condition, false);
                    LabelHint hint = requestHint(masm, target);
                    boolean isShortConstant = isSimm5(constant);
                    int cbCondPosition = masm.position();
                    if (!isShortConstant) { // Load constant takes one instruction
                        cbCondPosition += SPARC.INSTRUCTION_SIZE;
                    }
                    boolean canUseShortBranch = masm.hasFeature(CPUFeature.CBCOND) && isShortBranch(masm, cbCondPosition, hint, target);
                    if (bits != null && canUseShortBranch) {
                        if (isShortConstant) {
                            CBCOND.emit(masm, conditionFlag, conditionCode == Xcc, keyRegister, (int) (long) bits, target);
                        } else {
                            Register scratchRegister = asRegister(scratch);
                            const2reg(crb, masm, scratch, constantBaseRegister, keyConstants[index], SPARCDelayedControlTransfer.DUMMY);
                            CBCOND.emit(masm, conditionFlag, conditionCode == Xcc, keyRegister, scratchRegister, target);
                        }
                    } else {
                        if (bits != null && isSimm13(constant)) {
                            masm.cmp(keyRegister, (int) (long) bits); // Cast is safe
                        } else {
                            Register scratchRegister = asRegister(scratch);
                            const2reg(crb, masm, scratch, constantBaseRegister, keyConstants[index], SPARCDelayedControlTransfer.DUMMY);
                            masm.cmp(keyRegister, scratchRegister);
                        }
                        masm.bpcc(conditionFlag, ANNUL, target, conditionCode, PREDICT_TAKEN);
                        masm.nop();  // delay slot
                    }
                }
            };
            strategy.run(closure);
        }

        private LabelHint requestHint(SPARCMacroAssembler masm, Label label) {
            LabelHint hint = labelHints.get(label);
            if (hint == null) {
                hint = masm.requestLabelHint(label);
                labelHints.put(label, hint);
            }
            return hint;
        }

        @Override
        public SizeEstimate estimateSize() {
            int constantBytes = 0;
            for (JavaConstant v : keyConstants) {
                if (!SPARCAssembler.isSimm13(v)) {
                    constantBytes += v.getKind().getByteCount();
                }
            }
            return new SizeEstimate(4 * keyTargets.length, constantBytes);
        }
    }

    public static final class TableSwitchOp extends SPARCBlockEndOp {
        public static final LIRInstructionClass<TableSwitchOp> TYPE = LIRInstructionClass.create(TableSwitchOp.class);

        private final int lowKey;
        private final LabelRef defaultTarget;
        private final LabelRef[] targets;
        @Alive protected Value index;
        @Temp protected Value scratch;

        public TableSwitchOp(final int lowKey, final LabelRef defaultTarget, final LabelRef[] targets, Variable index, Variable scratch) {
            super(TYPE);
            this.lowKey = lowKey;
            this.defaultTarget = defaultTarget;
            this.targets = targets;
            this.index = index;
            this.scratch = scratch;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            Register value = asIntReg(index);
            Register scratchReg = asLongReg(scratch);

            // Compare index against jump table bounds
            int highKey = lowKey + targets.length - 1;

            // subtract the low value from the switch value
            if (isSimm13(lowKey)) {
                masm.sub(value, lowKey, scratchReg);
            } else {
                try (ScratchRegister sc = masm.getScratchRegister()) {
                    Register scratch2 = sc.getRegister();
                    new Setx(lowKey, scratch2).emit(masm);
                    masm.sub(value, scratch2, scratchReg);
                }
            }
            int upperLimit = highKey - lowKey;
            try (ScratchRegister sc = masm.getScratchRegister()) {
                Register scratch2 = sc.getRegister();
                if (isSimm13(upperLimit)) {
                    masm.cmp(scratchReg, upperLimit);
                } else {
                    new Setx(upperLimit, scratch2).emit(masm);
                    masm.cmp(scratchReg, upperLimit);
                }

                // Jump to default target if index is not within the jump table
                if (defaultTarget != null) {
                    masm.bpcc(GreaterUnsigned, NOT_ANNUL, defaultTarget.label(), Icc, PREDICT_TAKEN);
                    masm.nop();  // delay slot
                }

                // Load jump table entry into scratch and jump to it
                masm.sll(scratchReg, 3, scratchReg); // Multiply by 8
                // Zero the left bits sll with shcnt>0 does not mask upper 32 bits
                masm.srl(scratchReg, 0, scratchReg);
                masm.rdpc(scratch2);

                // The jump table follows four instructions after rdpc
                masm.add(scratchReg, 4 * 4, scratchReg);
                masm.jmpl(scratch2, scratchReg, g0);
            }
            masm.nop();

            // Emit jump table entries
            for (LabelRef target : targets) {
                masm.bpcc(Always, NOT_ANNUL, target.label(), Xcc, PREDICT_TAKEN);
                masm.nop(); // delay slot
            }
        }

        @Override
        public SizeEstimate estimateSize() {
            return SizeEstimate.create(17 + targets.length * 2);
        }
    }

    @Opcode("CMOVE")
    public static final class CondMoveOp extends SPARCLIRInstruction {
        public static final LIRInstructionClass<CondMoveOp> TYPE = LIRInstructionClass.create(CondMoveOp.class);

        @Def({REG, HINT}) protected Value result;
        @Use({REG, CONST}) protected Value trueValue;
        @Use({REG, CONST}) protected Value falseValue;

        private final ConditionFlag condition;
        private final CC cc;

        public CondMoveOp(Variable result, CC cc, ConditionFlag condition, Value trueValue, Value falseValue) {
            super(TYPE);
            this.result = result;
            this.condition = condition;
            this.trueValue = trueValue;
            this.falseValue = falseValue;
            this.cc = cc;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            if (result.equals(trueValue)) { // We have the true value in place, do he opposite
                cmove(masm, cc, result, condition.negate(), falseValue);
            } else if (result.equals(falseValue)) {
                cmove(masm, cc, result, condition, trueValue);
            } else { // We have to move one of the input values to the result
                ConditionFlag actualCondition = condition;
                Value actualTrueValue = trueValue;
                Value actualFalseValue = falseValue;
                if (isJavaConstant(falseValue) && isSimm11(asJavaConstant(falseValue))) {
                    actualCondition = condition.negate();
                    actualTrueValue = falseValue;
                    actualFalseValue = trueValue;
                }
                SPARCMove.move(crb, masm, result, actualFalseValue, SPARCDelayedControlTransfer.DUMMY);
                cmove(masm, cc, result, actualCondition, actualTrueValue);
            }
        }

        @Override
        public SizeEstimate estimateSize() {
            int constantSize = 0;
            if (isJavaConstant(trueValue) && !SPARCAssembler.isSimm13(asJavaConstant(trueValue))) {
                constantSize += trueValue.getKind().getByteCount();
            }
            if (isJavaConstant(falseValue) && !SPARCAssembler.isSimm13(asJavaConstant(falseValue))) {
                constantSize += trueValue.getKind().getByteCount();
            }
            return SizeEstimate.create(3, constantSize);
        }
    }

    private static void cmove(SPARCMacroAssembler masm, CC cc, Value result, ConditionFlag cond, Value other) {
        switch (other.getKind()) {
            case Boolean:
            case Byte:
            case Short:
            case Char:
            case Int:
                if (isJavaConstant(other)) {
                    int constant;
                    if (asJavaConstant(other).isNull()) {
                        constant = 0;
                    } else {
                        constant = asJavaConstant(other).asInt();
                    }
                    masm.movcc(cond, cc, constant, asRegister(result));
                } else {
                    masm.movcc(cond, cc, asRegister(other), asRegister(result));
                }
                break;
            case Long:
            case Object:
                if (isJavaConstant(other)) {
                    long constant;
                    if (asJavaConstant(other).isNull()) {
                        constant = 0;
                    } else {
                        constant = asJavaConstant(other).asLong();
                    }
                    masm.movcc(cond, cc, (int) constant, asRegister(result));
                } else {
                    masm.movcc(cond, cc, asRegister(other), asRegister(result));
                }
                break;
            case Float:
                masm.fmovscc(cond, cc, asFloatReg(other), asFloatReg(result));
                break;
            case Double:
                masm.fmovdcc(cond, cc, asDoubleReg(other), asDoubleReg(result));
                break;
            default:
                throw JVMCIError.shouldNotReachHere();
        }
    }

    public static ConditionFlag fromCondition(CC conditionFlagsRegister, Condition cond, boolean unorderedIsTrue) {
        switch (conditionFlagsRegister) {
            case Xcc:
            case Icc:
                switch (cond) {
                    case EQ:
                        return Equal;
                    case NE:
                        return NotEqual;
                    case BT:
                        return LessUnsigned;
                    case LT:
                        return Less;
                    case BE:
                        return LessEqualUnsigned;
                    case LE:
                        return LessEqual;
                    case AE:
                        return GreaterEqualUnsigned;
                    case GE:
                        return GreaterEqual;
                    case AT:
                        return GreaterUnsigned;
                    case GT:
                        return Greater;
                }
                throw JVMCIError.shouldNotReachHere("Unimplemented for: " + cond);
            case Fcc0:
            case Fcc1:
            case Fcc2:
            case Fcc3:
                switch (cond) {
                    case EQ:
                        return unorderedIsTrue ? F_UnorderedOrEqual : F_Equal;
                    case NE:
                        return ConditionFlag.F_NotEqual;
                    case LT:
                        return unorderedIsTrue ? F_UnorderedOrLess : F_Less;
                    case LE:
                        return unorderedIsTrue ? F_UnorderedOrLessOrEqual : F_LessOrEqual;
                    case GE:
                        return unorderedIsTrue ? F_UnorderedGreaterOrEqual : F_GreaterOrEqual;
                    case GT:
                        return unorderedIsTrue ? F_UnorderedOrGreater : F_Greater;
                }
                throw JVMCIError.shouldNotReachHere("Unkown condition: " + cond);
        }
        throw JVMCIError.shouldNotReachHere("Unknown condition flag register " + conditionFlagsRegister);
    }
}
