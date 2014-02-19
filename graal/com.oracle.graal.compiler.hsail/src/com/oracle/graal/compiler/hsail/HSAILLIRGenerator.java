/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.graal.compiler.hsail;

import static com.oracle.graal.api.code.ValueUtil.*;
import static com.oracle.graal.lir.hsail.HSAILArithmetic.*;
import static com.oracle.graal.lir.hsail.HSAILBitManipulationOp.IntrinsicOpcode.*;
import static com.oracle.graal.lir.hsail.HSAILCompare.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.StandardOp.JumpOp;
import com.oracle.graal.lir.hsail.*;
import com.oracle.graal.lir.hsail.HSAILArithmetic.ConvertOp;
import com.oracle.graal.lir.hsail.HSAILArithmetic.Op1Stack;
import com.oracle.graal.lir.hsail.HSAILArithmetic.Op2Reg;
import com.oracle.graal.lir.hsail.HSAILArithmetic.Op2Stack;
import com.oracle.graal.lir.hsail.HSAILArithmetic.ShiftOp;
import com.oracle.graal.lir.hsail.HSAILControlFlow.CompareBranchOp;
import com.oracle.graal.lir.hsail.HSAILControlFlow.CondMoveOp;
import com.oracle.graal.lir.hsail.HSAILControlFlow.FloatCondMoveOp;
import com.oracle.graal.lir.hsail.HSAILControlFlow.ReturnOp;
import com.oracle.graal.lir.hsail.HSAILControlFlow.StrategySwitchOp;
import com.oracle.graal.lir.hsail.HSAILMove.LeaOp;
import com.oracle.graal.lir.hsail.HSAILMove.MembarOp;
import com.oracle.graal.lir.hsail.HSAILMove.MoveFromRegOp;
import com.oracle.graal.lir.hsail.HSAILMove.MoveToRegOp;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.phases.util.*;

/**
 * This class implements the HSAIL specific portion of the LIR generator.
 */
public abstract class HSAILLIRGenerator extends LIRGenerator {

    public static class HSAILSpillMoveFactory implements LIR.SpillMoveFactory {

        @Override
        public LIRInstruction createMove(AllocatableValue dst, Value src) {
            if (src instanceof HSAILAddressValue) {
                return new LeaOp(dst, (HSAILAddressValue) src);
            } else if (isRegister(src) || isStackSlot(dst)) {
                return new MoveFromRegOp(dst, src);
            } else {
                return new MoveToRegOp(dst, src);
            }
        }
    }

    public HSAILLIRGenerator(StructuredGraph graph, Providers providers, FrameMap frameMap, CallingConvention cc, LIR lir) {
        super(graph, providers, frameMap, cc, lir);
        lir.spillMoveFactory = new HSAILSpillMoveFactory();
    }

    @Override
    public boolean canStoreConstant(Constant c, boolean isCompressed) {
        // Operand b must be in the .reg state space.
        return false;
    }

    @Override
    public boolean canInlineConstant(Constant c) {
        switch (c.getKind()) {
            case Long:
                return NumUtil.isInt(c.asLong()) && !getCodeCache().needsDataPatch(c);
            case Object:
                return c.isNull();
            default:
                return true;
        }
    }

    @Override
    public Variable emitMove(Value input) {
        Variable result = newVariable(input.getKind());
        emitMove(result, input);
        return result;
    }

    @Override
    public void emitMove(AllocatableValue dst, Value src) {
        if (isRegister(src) || isStackSlot(dst)) {
            append(new MoveFromRegOp(dst, src));
        } else {
            append(new MoveToRegOp(dst, src));
        }
    }

    protected HSAILAddressValue asAddressValue(Value address) {
        if (address instanceof HSAILAddressValue) {
            return (HSAILAddressValue) address;
        } else {
            return emitAddress(address, 0, Value.ILLEGAL, 0);
        }
    }

    public HSAILAddressValue emitAddress(Value base, long displacement, Value index, int scale) {
        AllocatableValue baseRegister;
        long finalDisp = displacement;

        if (isConstant(base)) {
            if (asConstant(base).isNull()) {
                baseRegister = Value.ILLEGAL;
            } else if (asConstant(base).getKind() != Kind.Object) {
                finalDisp += asConstant(base).asLong();
                baseRegister = Value.ILLEGAL;
            } else {
                baseRegister = load(base);
            }
        } else if (base.equals(Value.ILLEGAL)) {
            baseRegister = Value.ILLEGAL;
        } else {
            baseRegister = asAllocatable(base);
        }
        if (!index.equals(Value.ILLEGAL)) {
            if (isConstant(index)) {
                finalDisp += asConstant(index).asLong() * scale;
            } else {
                Value indexRegister;
                Value convertedIndex;
                convertedIndex = this.emitConvert(Kind.Int, Kind.Long, index);
                if (scale != 1) {
                    indexRegister = emitUMul(convertedIndex, Constant.forInt(scale));
                } else {
                    indexRegister = convertedIndex;
                }
                if (baseRegister.equals(Value.ILLEGAL)) {
                    baseRegister = asAllocatable(indexRegister);
                } else {
                    baseRegister = emitAdd(baseRegister, indexRegister);
                }
            }
        }
        return new HSAILAddressValue(target().wordKind, baseRegister, finalDisp);
    }

    @Override
    public Variable emitAddress(StackSlot address) {
        throw GraalInternalError.unimplemented();
    }

    @Override
    public void emitJump(LabelRef label) {
        append(new JumpOp(label));
    }

    protected static HSAILCompare mapKindToCompareOp(Kind kind) {
        switch (kind) {
            case Int:
                return ICMP;
            case Long:
                return LCMP;
            case Float:
                return FCMP;
            case Double:
                return DCMP;
            case Object:
                return ACMP;
            default:
                throw GraalInternalError.shouldNotReachHere("" + kind);
        }
    }

    @Override
    public void emitCompareBranch(Value left, Value right, Condition cond, boolean unorderedIsTrue, LabelRef trueDestination, LabelRef falseDestination, double trueDestinationProbability) {
        // We don't have to worry about mirroring the condition on HSAIL.
        Condition finalCondition = cond;
        Variable result = newVariable(left.getKind());
        Kind kind = left.getKind().getStackKind();
        switch (kind) {
            case Int:
            case Long:
            case Object:
                append(new CompareBranchOp(mapKindToCompareOp(kind), finalCondition, left, right, result, result, trueDestination, falseDestination, false));
                break;
            case Float:
            case Double:
                append(new CompareBranchOp(mapKindToCompareOp(kind), finalCondition, left, right, result, result, trueDestination, falseDestination, unorderedIsTrue));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere("" + left.getKind());
        }
    }

    @Override
    public void emitOverflowCheckBranch(LabelRef overflow, LabelRef noOverflow, double overflowProbability) {
        throw GraalInternalError.unimplemented();
    }

    @Override
    public void emitIntegerTestBranch(Value left, Value right, LabelRef trueDestination, LabelRef falseDestination, double trueDestinationProbability) {
        throw GraalInternalError.unimplemented();
    }

    @Override
    public Variable emitConditionalMove(Value left, Value right, Condition cond, boolean unorderedIsTrue, Value trueValue, Value falseValue) {
        Condition finalCondition = cond;
        Variable result = newVariable(trueValue.getKind());
        Kind kind = left.getKind().getStackKind();
        switch (kind) {
            case Int:
            case Long:
            case Object:
                append(new CondMoveOp(mapKindToCompareOp(kind), load(left), load(right), result, finalCondition, load(trueValue), load(falseValue)));
                break;
            case Float:
            case Double:
                append(new FloatCondMoveOp(mapKindToCompareOp(kind), load(left), load(right), result, finalCondition, unorderedIsTrue, load(trueValue), load(falseValue)));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere("missing: " + left.getKind());
        }
        return result;
    }

    @Override
    public Variable emitIntegerTestMove(Value left, Value right, Value trueValue, Value falseValue) {
        throw GraalInternalError.unimplemented();
    }

    /**
     * Generates the LIR instruction for a negation operation.
     * 
     * @param input the value that is being negated
     * @return Variable that represents the result of the negation
     */
    @Override
    public Variable emitNegate(Value input) {
        Variable result = newVariable(input.getKind());
        switch (input.getKind()) {
            case Int:
                // Note: The Int case also handles the negation of shorts, bytes, and chars because
                // Java treats these types as ints at the bytecode level.
                append(new Op1Stack(INEG, result, input));
                break;
            case Long:
                append(new Op1Stack(LNEG, result, input));
                break;
            case Double:
                append(new Op1Stack(DNEG, result, input));
                break;
            case Float:
                append(new Op1Stack(FNEG, result, input));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        return result;

    }

    /**
     * Generates the LIR instruction for a bitwise NOT operation.
     * 
     * @param input the source operand
     * @return Variable that represents the result of the operation
     */
    @Override
    public Variable emitNot(Value input) {
        Variable result = newVariable(input.getKind());
        switch (input.getKind()) {
            case Int:
                // Note: The Int case also covers other primitive integral types smaller than an int
                // (char, byte, short) because Java treats these types as ints.
                append(new Op1Stack(INOT, result, input));
                break;
            case Long:
                append(new Op1Stack(LNOT, result, input));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        return result;

    }

    public Variable emitTestAddressAdd(Value a, Value b) {
        Variable result = newVariable(a.getKind());
        switch (a.getKind()) {
            case Int:
                append(new Op2Stack(IADD, result, a, loadNonConst(b)));
                break;
            case Long:
                append(new Op2Stack(LADD, result, a, loadNonConst(b)));
                break;
            case Float:
                append(new Op2Stack(FADD, result, a, loadNonConst(b)));
                break;
            case Double:
                append(new Op2Stack(DADD, result, a, loadNonConst(b)));
                break;
            case Object:
                throw GraalInternalError.shouldNotReachHere();
            default:
                throw GraalInternalError.shouldNotReachHere();
        }

        return result;
    }

    @Override
    public Variable emitAdd(Value a, Value b) {
        Variable result = newVariable(a.getKind());
        switch (a.getKind()) {
            case Int:
                append(new Op2Stack(IADD, result, a, loadNonConst(b)));
                break;
            case Long:
                append(new Op2Stack(LADD, result, a, loadNonConst(b)));
                break;
            case Float:
                append(new Op2Stack(FADD, result, a, loadNonConst(b)));
                break;
            case Double:
                append(new Op2Stack(DADD, result, a, loadNonConst(b)));
                break;
            case Object:
                append(new Op2Stack(OADD, result, a, loadNonConst(b)));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitSub(Value a, Value b) {
        Variable result = newVariable(a.getKind());
        switch (a.getKind()) {
            case Int:
                append(new Op2Stack(ISUB, result, a, loadNonConst(b)));
                break;
            case Float:
                append(new Op2Stack(FSUB, result, a, loadNonConst(b)));
                break;
            case Long:
                append(new Op2Stack(LSUB, result, a, loadNonConst(b)));
                break;
            case Double:
                append(new Op2Stack(DSUB, result, a, loadNonConst(b)));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitMul(Value a, Value b) {
        Variable result = newVariable(a.getKind());
        switch (a.getKind()) {
            case Int:
                append(new Op2Reg(IMUL, result, a, loadNonConst(b)));
                break;
            case Long:
                append(new Op2Reg(LMUL, result, a, loadNonConst(b)));
                break;
            case Float:
                append(new Op2Reg(FMUL, result, a, loadNonConst(b)));
                break;
            case Double:
                append(new Op2Reg(DMUL, result, a, loadNonConst(b)));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        return result;
    }

    public Variable emitUMul(Value a, Value b) {
        Variable result = newVariable(a.getKind());
        switch (a.getKind()) {
            case Int:
                append(new Op2Reg(LUMUL, result, a, loadNonConst(b)));
                break;
            case Long:
                append(new Op2Reg(LUMUL, result, a, loadNonConst(b)));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        return result;
    }

    @Override
    protected boolean peephole(ValueNode valueNode) {
        // No peephole optimizations for now.
        return false;
    }

    @Override
    public Value emitDiv(Value a, Value b, DeoptimizingNode deopting) {
        Variable result = newVariable(a.getKind());
        switch (a.getKind()) {
            case Int:
                append(new Op2Stack(IDIV, result, a, loadNonConst(b)));
                break;
            case Long:
                append(new Op2Stack(LDIV, result, a, loadNonConst(b)));
                break;
            case Float:
                append(new Op2Stack(FDIV, result, a, loadNonConst(b)));
                break;
            case Double:
                append(new Op2Stack(DDIV, result, a, loadNonConst(b)));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        return result;

    }

    @Override
    public Value emitRem(Value a, Value b, DeoptimizingNode deopting) {
        Variable result = newVariable(a.getKind());
        switch (a.getKind()) {
            case Int:
                append(new Op2Stack(IREM, result, a, loadNonConst(b)));
                break;
            case Long:
                append(new Op2Stack(LREM, result, a, loadNonConst(b)));
                break;
            case Float:
                append(new Op2Stack(FREM, result, a, loadNonConst(b)));
                break;
            case Double:
                append(new Op2Stack(DREM, result, a, loadNonConst(b)));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitUDiv(Value a, Value b, DeoptimizingNode deopting) {
        throw GraalInternalError.unimplemented();
    }

    @Override
    public Variable emitURem(Value a, Value b, DeoptimizingNode deopting) {
        throw GraalInternalError.unimplemented();
    }

    @Override
    public Variable emitAnd(Value a, Value b) {
        Variable result = newVariable(a.getKind());
        switch (a.getKind()) {
            case Int:
                append(new Op2Stack(IAND, result, a, loadNonConst(b)));
                break;
            case Long:
                append(new Op2Stack(LAND, result, a, loadNonConst(b)));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitOr(Value a, Value b) {
        Variable result = newVariable(a.getKind());
        switch (a.getKind()) {
            case Int:
                append(new Op2Stack(IOR, result, a, loadNonConst(b)));
                break;
            case Long:
                append(new Op2Stack(LOR, result, a, loadNonConst(b)));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitXor(Value a, Value b) {
        Variable result = newVariable(a.getKind());
        switch (a.getKind()) {
            case Int:
                append(new Op2Stack(IXOR, result, a, loadNonConst(b)));
                break;
            case Long:
                append(new Op2Stack(LXOR, result, a, loadNonConst(b)));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        return result;
    }

    /**
     * Generates the LIR instruction for a shift left operation.
     * 
     * @param a The value that is being shifted
     * @param b The shift amount
     * @return Variable that represents the result of the operation
     */
    @Override
    public Variable emitShl(Value a, Value b) {
        Variable result = newVariable(a.getKind());
        switch (a.getKind()) {
            case Int:
                // Note: The Int case also covers the shifting of bytes, shorts and chars because
                // Java treats these types as ints at the bytecode level.
                append(new ShiftOp(ISHL, result, a, b));
                break;
            case Long:
                append(new ShiftOp(LSHL, result, a, b));
                break;
            default:
                GraalInternalError.shouldNotReachHere();
        }
        return result;
    }

    /**
     * Generates the LIR instruction for a shift right operation.
     * 
     * @param a The value that is being shifted
     * @param b The shift amount
     * @return Variable that represents the result of the operation
     */
    @Override
    public Variable emitShr(Value a, Value b) {
        Variable result = newVariable(a.getKind());
        switch (a.getKind()) {
            case Int:
                // Note: The Int case also covers the shifting of bytes, shorts and chars because
                // Java treats these types as ints at the bytecode level.
                append(new ShiftOp(ISHR, result, a, b));
                break;
            case Long:
                append(new ShiftOp(LSHR, result, a, b));
                break;
            default:
                GraalInternalError.shouldNotReachHere();
        }
        return result;
    }

    /**
     * Generates the LIR instruction for an unsigned shift right operation.
     * 
     * @param a The value that is being shifted
     * @param b The shift amount
     * @return Variable that represents the result of the operation
     */
    @Override
    public Variable emitUShr(Value a, Value b) {
        Variable result = newVariable(a.getKind());
        switch (a.getKind()) {
            case Int:
                append(new ShiftOp(IUSHR, result, a, b));
                break;
            case Long:
                append(new ShiftOp(LUSHR, result, a, b));
                break;
            default:
                GraalInternalError.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitConvert(Kind from, Kind to, Value inputVal) {
        Variable input = load(inputVal);
        Variable result = newVariable(to);
        append(new ConvertOp(result, input, to, from));
        return result;
    }

    @Override
    public Value emitReinterpret(Kind to, Value inputVal) {
        Variable result = newVariable(to);
        emitMove(result, inputVal);
        return result;
    }

    @Override
    public void emitDeoptimize(Value actionAndReason, Value speculation, DeoptimizingNode deopting) {
        append(new ReturnOp(Value.ILLEGAL));
    }

    @Override
    public void emitMembar(int barriers) {
        int necessaryBarriers = target().arch.requiredBarriers(barriers);
        append(new MembarOp(necessaryBarriers));
    }

    @Override
    protected void emitDirectCall(DirectCallTargetNode callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState callState) {
        throw GraalInternalError.unimplemented();
    }

    @Override
    protected void emitIndirectCall(IndirectCallTargetNode callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState callState) {
        throw GraalInternalError.unimplemented();
    }

    @Override
    public void emitBitCount(Variable result, Value value) {
        if (value.getKind().getStackKind() == Kind.Int) {
            append(new HSAILBitManipulationOp(IPOPCNT, result, value));
        } else {
            append(new HSAILBitManipulationOp(LPOPCNT, result, value));
        }
    }

    @Override
    public void emitBitScanForward(Variable result, Value value) {
        throw GraalInternalError.unimplemented();
    }

    @Override
    public void emitBitScanReverse(Variable result, Value value) {
        throw GraalInternalError.unimplemented();
    }

    /**
     * Emits the LIR code for the {@link HSAILArithmetic#ABS} operation.
     * 
     * @param input the source operand
     * @return Value representing the result of the operation
     */
    @Override
    public Value emitMathAbs(Value input) {
        Variable result = newVariable(input.getPlatformKind());
        append(new Op1Stack(ABS, result, input));
        return result;
    }

    /**
     * Emits the LIR code for the {@link HSAILArithmetic#CEIL} operation.
     * 
     * @param input the source operand
     * @return Value representing the result of the operation
     */
    public Value emitMathCeil(Value input) {
        Variable result = newVariable(input.getPlatformKind());
        append(new Op1Stack(CEIL, result, input));
        return result;
    }

    /**
     * Emits the LIR code for the {@link HSAILArithmetic#FLOOR} operation.
     * 
     * @param input the source operand
     * @return Value representing the result of the operation
     */
    public Value emitMathFloor(Value input) {
        Variable result = newVariable(input.getPlatformKind());
        append(new Op1Stack(FLOOR, result, input));
        return result;
    }

    /**
     * Emits the LIR code for the {@link HSAILArithmetic#RINT} operation.
     * 
     * @param input the source operand
     * @return Value representing the result of the operation
     */
    public Value emitMathRint(Value input) {
        Variable result = newVariable(input.getPlatformKind());
        append(new Op1Stack(RINT, result, input));
        return result;
    }

    /**
     * Emits the LIR code for the {@link HSAILArithmetic#SQRT} operation.
     * 
     * @param input the source operand
     * @return value representing the result of the operation
     */
    @Override
    public Value emitMathSqrt(Value input) {
        Variable result = newVariable(input.getPlatformKind());
        append(new Op1Stack(SQRT, result, input));
        return result;
    }

    @Override
    public Value emitMathLog(Value input, boolean base10) {
        throw GraalInternalError.unimplemented();
    }

    @Override
    public Value emitMathCos(Value input) {
        throw GraalInternalError.unimplemented();
    }

    @Override
    public Value emitMathSin(Value input) {
        throw GraalInternalError.unimplemented();
    }

    @Override
    public Value emitMathTan(Value input) {
        throw GraalInternalError.unimplemented();
    }

    @Override
    public void emitByteSwap(Variable result, Value input) {
        throw GraalInternalError.unimplemented();
    }

    @Override
    public void emitArrayEquals(Kind kind, Variable result, Value array1, Value array2, Value length) {
        // TODO Auto-generated method stub
        throw GraalInternalError.unimplemented();
    }

    @Override
    protected void emitReturn(Value input) {
        append(new ReturnOp(input));
    }

    /**
     * This routine handles the LIR code generation for switch nodes by calling
     * emitSequentialSwitch.
     * 
     * This routine overrides LIRGenerator.emitSwitch( ) which calls emitSequentialSwitch or
     * emitTableSwitch based on a heuristic.
     * 
     * The recommended approach in HSAIL for generating performant code for switch statements is to
     * emit a series of cascading compare and branches. Thus this routines always calls
     * emitSequentialSwitch, which implements this approach.
     * 
     * Note: Only IntegerSwitchNodes are currently supported. The IntegerSwitchNode is the node that
     * Graal generates for any switch construct appearing in Java bytecode.
     */
    @Override
    protected void emitStrategySwitch(Constant[] keyConstants, double[] keyProbabilities, LabelRef[] keyTargets, LabelRef defaultTarget, Variable value) {
        emitStrategySwitch(new SwitchStrategy.SequentialStrategy(keyProbabilities, keyConstants), value, keyTargets, defaultTarget);
    }

    /**
     * Generates the LIR instruction for a switch construct that is meant to be assembled into a
     * series of cascading compare and branch instructions. This is currently the recommended way of
     * generating performant HSAIL code for switch constructs.
     * 
     * In Java bytecode the keys for switch statements are always ints.
     * 
     * The x86 backend also adds support for handling keys of type long or Object but these two
     * special cases are for handling the TypeSwitchNode, which is a node that the JVM produces for
     * handling operations related to method dispatch. We haven't yet added support for the
     * TypeSwitchNode, so for the time being we have added a check to ensure that the keys are of
     * type int. This also allows us to flag any test cases/execution paths that may trigger the
     * creation of a TypeSwitchNode which we don't support yet.
     * 
     * 
     * @param strategy the strategy used for this switch.
     * @param keyTargets array of branch targets for each of the cases.
     * @param defaultTarget the branch target for the default case.
     * @param key the key that is compared against the key constants in the case statements.
     */
    @Override
    protected void emitStrategySwitch(SwitchStrategy strategy, Variable key, LabelRef[] keyTargets, LabelRef defaultTarget) {
        if (key.getKind() == Kind.Int) {
            // Append the LIR instruction for generating compare and branch instructions.
            append(new StrategySwitchOp(strategy, keyTargets, defaultTarget, key));
        } else {
            // Throw an exception if the keys aren't ints.
            throw GraalInternalError.unimplemented("Switch statements are only supported for keys of type int");
        }
    }

    @Override
    protected void emitTableSwitch(int lowKey, LabelRef defaultTarget, LabelRef[] targets, Value key) {
        throw GraalInternalError.unimplemented();
    }

    @Override
    public void visitBreakpointNode(BreakpointNode node) {
        throw GraalInternalError.unimplemented();
    }

    @Override
    public void visitSafepointNode(SafepointNode i) {
        Debug.log("visitSafePointNode unimplemented");
    }

    @Override
    public void emitUnwind(Value operand) {
        throw GraalInternalError.unimplemented();
    }

    @Override
    public void emitNullCheck(ValueNode v, DeoptimizingNode deopting) {
        assert v.kind() == Kind.Object;
        Variable obj = newVariable(Kind.Object);
        emitMove(obj, operand(v));
        append(new HSAILMove.NullCheckOp(obj, state(deopting)));
    }

    @Override
    public void visitInfopointNode(InfopointNode i) {
        throw GraalInternalError.unimplemented();
    }
}
