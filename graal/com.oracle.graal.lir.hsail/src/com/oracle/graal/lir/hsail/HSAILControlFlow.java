/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.lir.hsail;

import static com.oracle.graal.lir.LIRInstruction.OperandFlag.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.hsail.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.StandardOp.FallThroughOp;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.nodes.calc.*;

/**
 * Implementation of control flow instructions.
 */
public class HSAILControlFlow {

    /**
     * This class represents the LIR instruction that the HSAIL backend generates for a switch
     * construct in Java.
     * 
     * The HSAIL backend compiles switch statements into a series of cascading compare and branch
     * instructions because this is the currently the recommended way to generate optimally
     * performing HSAIL code. Thus the execution path for both the TABLESWITCH and LOOKUPSWITCH
     * bytecodes go through this op.
     */
    public static class SwitchOp extends HSAILLIRInstruction implements FallThroughOp {
        /**
         * The array of key constants used for the cases of this switch statement.
         */
        @Use({CONST}) protected Constant[] keyConstants;
        /**
         * The branch target labels that correspond to each case.
         */
        private final LabelRef[] keyTargets;
        private LabelRef defaultTarget;
        /**
         * The key of the switch. This will be compared with each of the keyConstants.
         */
        @Alive({REG}) protected Value key;

        /**
         * Constructor. Called from the HSAILLIRGenerator.emitSequentialSwitch routine.
         * 
         * @param keyConstants
         * @param keyTargets
         * @param defaultTarget
         * @param key
         */
        public SwitchOp(Constant[] keyConstants, LabelRef[] keyTargets, LabelRef defaultTarget, Value key) {
            assert keyConstants.length == keyTargets.length;
            this.keyConstants = keyConstants;
            this.keyTargets = keyTargets;
            this.defaultTarget = defaultTarget;
            this.key = key;
        }

        /**
         * Get the default target for this switch op.
         */
        @Override
        public LabelRef fallThroughTarget() {
            return defaultTarget;
        }

        /**
         * Set the default target.
         * 
         * @param target the default target
         */
        @Override
        public void setFallThroughTarget(LabelRef target) {
            defaultTarget = target;

        }

        /**
         * Generates the code for this switch op.
         * 
         * The keys for switch statements in Java bytecode for of type int. However, Graal also
         * generates a TypeSwitchNode (for method dispatch) which triggers the invocation of these
         * routines with keys of type Long or Object. Currently we only support the
         * IntegerSwitchNode so we throw an exception if the key isn't of type int.
         * 
         * @param tasm the TargetMethodAssembler
         * @param masm the HSAIL assembler
         */
        @Override
        public void emitCode(TargetMethodAssembler tasm, HSAILAssembler masm) {
            if (key.getKind() == Kind.Int) {
                for (int i = 0; i < keyConstants.length; i++) {
                    // Generate cascading compare and branches for each case.
                    masm.emitCompare(key, keyConstants[i], "eq", false, false);
                    masm.cbr(masm.nameOf(keyTargets[i].label()));
                }
                // Generate a jump for the default target if there is one.
                if (defaultTarget != null) {
                    masm.jmp(defaultTarget.label());
                }

            } else {
                // Throw an exception if the key isn't of type int.
                throw new GraalInternalError("Switch statments are only supported for int keys");
            }
        }
    }

    public static class ReturnOp extends HSAILLIRInstruction {

        @Use({REG, ILLEGAL}) protected Value x;

        public ReturnOp(Value x) {
            this.x = x;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, HSAILAssembler masm) {
            if (tasm.frameContext != null) {
                tasm.frameContext.leave(tasm);
            }
            masm.exit();
        }
    }

    public static class ForeignCallNoArgOp extends HSAILLIRInstruction {

        @Def({REG}) protected Value out;
        String callName;

        public ForeignCallNoArgOp(String callName, Value out) {
            this.out = out;
            this.callName = callName;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, HSAILAssembler masm) {
            masm.emitComment("//ForeignCall to " + callName + " would have gone here");
        }
    }

    public static class ForeignCall1ArgOp extends ForeignCallNoArgOp {

        @Use({REG, ILLEGAL}) protected Value arg1;

        public ForeignCall1ArgOp(String callName, Value out, Value arg1) {
            super(callName, out);
            this.arg1 = arg1;
        }
    }

    public static class ForeignCall2ArgOp extends ForeignCall1ArgOp {

        @Use({REG, ILLEGAL}) protected Value arg2;

        public ForeignCall2ArgOp(String callName, Value out, Value arg1, Value arg2) {
            super(callName, out, arg1);
            this.arg2 = arg2;
        }
    }

    public static class CompareBranchOp extends HSAILLIRInstruction implements StandardOp.BranchOp {

        @Opcode protected final HSAILCompare opcode;
        @Use({REG, CONST}) protected Value x;
        @Use({REG, CONST}) protected Value y;
        @Def({REG}) protected Value z;
        protected Condition condition;
        protected LabelRef destination;
        protected boolean unordered = false;
        @Def({REG}) protected Value result;

        public CompareBranchOp(HSAILCompare opcode, Condition condition, Value x, Value y, Value z, Value result, LabelRef destination) {
            this.condition = condition;
            this.opcode = opcode;
            this.x = x;
            this.y = y;
            this.z = z;
            this.result = result;
            this.destination = destination;
        }

        @Override
        public LabelRef destination() {
            return destination;
        }

        @Override
        public void negate(LabelRef newDestination) {
            destination = newDestination;
            condition = condition.negate();
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, HSAILAssembler masm) {
            HSAILCompare.emit(tasm, masm, condition, x, y, z, unordered);
            masm.cbr(masm.nameOf(destination.label()));
        }
    }

    public static class FloatCompareBranchOp extends CompareBranchOp {

        public FloatCompareBranchOp(HSAILCompare opcode, Condition condition, Value x, Value y, Value z, Value result, LabelRef destination, boolean unordered) {
            super(opcode, condition, x, y, z, result, destination);
            this.unordered = unordered;
        }

        @Override
        public void negate(LabelRef newDestination) {
            destination = newDestination;
            condition = condition.negate();
            unordered = !unordered;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, HSAILAssembler masm) {
            HSAILCompare.emit(tasm, masm, condition, x, y, z, unordered);
            masm.cbr(masm.nameOf(destination.label()));
        }
    }

    public static class DoubleCompareBranchOp extends CompareBranchOp {

        public DoubleCompareBranchOp(HSAILCompare opcode, Condition condition, Value x, Value y, Value z, Value result, LabelRef destination, boolean unordered) {
            super(opcode, condition, x, y, z, result, destination);
            this.unordered = unordered;
        }

        @Override
        public void negate(LabelRef newDestination) {
            destination = newDestination;
            condition = condition.negate();
            unordered = !unordered;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, HSAILAssembler masm) {
            HSAILCompare.emit(tasm, masm, condition, x, y, z, unordered);
            masm.cbr(masm.nameOf(destination.label()));
        }
    }

    public static class BranchOp extends HSAILLIRInstruction implements StandardOp.BranchOp {

        protected Condition condition;
        protected LabelRef destination;
        @Def({REG}) protected Value result;

        public BranchOp(Condition condition, Value result, LabelRef destination) {
            this.condition = condition;
            this.destination = destination;
            this.result = result;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, HSAILAssembler masm) {
            masm.cbr(masm.nameOf(destination.label()));
        }

        @Override
        public LabelRef destination() {
            return destination;
        }

        @Override
        public void negate(LabelRef newDestination) {
            destination = newDestination;
            condition = condition.negate();
        }
    }

    public static class FloatBranchOp extends BranchOp {

        public FloatBranchOp(Condition condition, Value result, LabelRef destination) {
            super(condition, result, destination);
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, HSAILAssembler masm) {
            masm.cbr(masm.nameOf(destination.label()));
        }
    }

    public static class CondMoveOp extends HSAILLIRInstruction {

        @Opcode protected final HSAILCompare opcode;
        @Def({REG, HINT}) protected Value result;
        @Use({REG, STACK, CONST}) protected Value trueValue;
        @Use({REG, STACK, CONST}) protected Value falseValue;
        @Use({REG, STACK, CONST}) protected Value left;
        @Use({REG, STACK, CONST}) protected Value right;
        protected final Condition condition;

        public CondMoveOp(HSAILCompare opcode, Variable left, Variable right, Variable result, Condition condition, Value trueValue, Value falseValue) {
            this.opcode = opcode;
            this.result = result;
            this.left = left;
            this.right = right;
            this.condition = condition;
            this.trueValue = trueValue;
            this.falseValue = falseValue;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, HSAILAssembler masm) {
            HSAILCompare.emit(tasm, masm, condition, left, right, right, false);
            cmove(tasm, masm, result, false, trueValue, falseValue);
        }
    }

    public static class FloatCondMoveOp extends CondMoveOp {

        private final boolean unorderedIsTrue;

        public FloatCondMoveOp(HSAILCompare opcode, Variable left, Variable right, Variable result, Condition condition, boolean unorderedIsTrue, Variable trueValue, Value falseValue) {
            super(opcode, left, right, result, condition, trueValue, falseValue);
            this.unorderedIsTrue = unorderedIsTrue;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, HSAILAssembler masm) {
            HSAILCompare.emit(tasm, masm, condition, left, right, right, unorderedIsTrue);
            cmove(tasm, masm, result, false, trueValue, falseValue);
        }
    }

    @SuppressWarnings("unused")
    private static void cmove(TargetMethodAssembler tasm, HSAILAssembler masm, Value result, boolean unorderedIsTrue, Value trueValue, Value falseValue) {
        // Check that we don't overwrite an input operand before it is used.
        assert (result.getKind() == trueValue.getKind() && result.getKind() == falseValue.getKind());
        int width;
        switch (result.getKind()) {
        /**
         * We don't need to pass the cond to the assembler. We will always use $c0 as the control
         * register.
         */
            case Float:
            case Int:
                width = 32;
                break;
            case Double:
            case Long:
                width = 64;
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        masm.emitConditionalMove(result, trueValue, falseValue, width);
    }
}
