/*
 * Copyright (c) 2009, 2014, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.graal.compiler.sparc;

import static com.oracle.graal.api.code.ValueUtil.*;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.*;
import static com.oracle.graal.lir.sparc.SPARCBitManipulationOp.IntrinsicOpcode.*;
import static com.oracle.graal.lir.sparc.SPARCCompare.*;
import static com.oracle.graal.lir.sparc.SPARCMathIntrinsicOp.IntrinsicOpcode.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.sparc.*;
import com.oracle.graal.asm.sparc.SPARCAssembler.CC;
import com.oracle.graal.asm.sparc.SPARCAssembler.ConditionFlag;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.compiler.common.calc.*;
import com.oracle.graal.compiler.common.spi.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.gen.*;
import com.oracle.graal.lir.sparc.*;
import com.oracle.graal.lir.sparc.SPARCArithmetic.BinaryRegConst;
import com.oracle.graal.lir.sparc.SPARCArithmetic.BinaryRegReg;
import com.oracle.graal.lir.sparc.SPARCArithmetic.MulHighOp;
import com.oracle.graal.lir.sparc.SPARCArithmetic.RemOp;
import com.oracle.graal.lir.sparc.SPARCArithmetic.SPARCLMulccOp;
import com.oracle.graal.lir.sparc.SPARCArithmetic.Unary2Op;
import com.oracle.graal.lir.sparc.SPARCCompare.CompareOp;
import com.oracle.graal.lir.sparc.SPARCControlFlow.BranchOp;
import com.oracle.graal.lir.sparc.SPARCControlFlow.CondMoveOp;
import com.oracle.graal.lir.sparc.SPARCControlFlow.ReturnOp;
import com.oracle.graal.lir.sparc.SPARCControlFlow.StrategySwitchOp;
import com.oracle.graal.lir.sparc.SPARCControlFlow.TableSwitchOp;
import com.oracle.graal.lir.sparc.SPARCMove.LoadAddressOp;
import com.oracle.graal.lir.sparc.SPARCMove.LoadDataAddressOp;
import com.oracle.graal.lir.sparc.SPARCMove.MembarOp;
import com.oracle.graal.lir.sparc.SPARCMove.MoveFpGp;
import com.oracle.graal.lir.sparc.SPARCMove.MoveFpGpVIS3;
import com.oracle.graal.lir.sparc.SPARCMove.MoveFromRegOp;
import com.oracle.graal.lir.sparc.SPARCMove.MoveToRegOp;
import com.oracle.graal.lir.sparc.SPARCMove.StackLoadAddressOp;
import com.oracle.graal.phases.util.*;
import com.oracle.graal.sparc.*;
import com.oracle.graal.sparc.SPARC.CPUFeature;

/**
 * This class implements the SPARC specific portion of the LIR generator.
 */
public abstract class SPARCLIRGenerator extends LIRGenerator {

    private StackSlotValue tmpStackSlot;

    private class SPARCSpillMoveFactory implements LIRGeneratorTool.SpillMoveFactory {

        @Override
        public LIRInstruction createMove(AllocatableValue result, Value input) {
            return SPARCLIRGenerator.this.createMove(result, input);
        }
    }

    public SPARCLIRGenerator(LIRKindTool lirKindTool, Providers providers, CallingConvention cc, LIRGenerationResult lirGenRes) {
        super(lirKindTool, providers, cc, lirGenRes);
    }

    public SpillMoveFactory getSpillMoveFactory() {
        return new SPARCSpillMoveFactory();
    }

    @Override
    public boolean canInlineConstant(JavaConstant c) {
        switch (c.getKind()) {
            case Int:
                return SPARCAssembler.isSimm13(c.asInt()) && !getCodeCache().needsDataPatch(c);
            case Long:
                return SPARCAssembler.isSimm13(c.asLong()) && !getCodeCache().needsDataPatch(c);
            case Object:
                return c.isNull();
            default:
                return false;
        }
    }

    protected SPARCLIRInstruction createMove(AllocatableValue dst, Value src) {
        if (src instanceof SPARCAddressValue) {
            return new LoadAddressOp(dst, (SPARCAddressValue) src);
        } else if (isRegister(src) || isStackSlotValue(dst)) {
            return new MoveFromRegOp(dst, src);
        } else {
            return new MoveToRegOp(dst, src);
        }
    }

    @Override
    public void emitMove(AllocatableValue dst, Value src) {
        append(createMove(dst, src));
    }

    @Override
    public void emitData(AllocatableValue dst, byte[] data) {
        append(new LoadDataAddressOp(dst, data));
    }

    @Override
    public SPARCAddressValue emitAddress(Value base, long displacement, Value index, int scale) {
        AllocatableValue baseRegister;
        long finalDisp = displacement;
        if (isConstant(base)) {
            if (asConstant(base).isNull()) {
                baseRegister = SPARC.g0.asValue(base.getLIRKind());
            } else if (asConstant(base).getKind() != Kind.Object) {
                finalDisp += asConstant(base).asLong();
                baseRegister = Value.ILLEGAL;
            } else {
                baseRegister = load(base);
            }
        } else {
            baseRegister = asAllocatable(base);
        }

        AllocatableValue indexRegister;
        if (!index.equals(Value.ILLEGAL) && scale != 0) {
            if (isConstant(index)) {
                finalDisp += asConstant(index).asLong() * scale;
                indexRegister = Value.ILLEGAL;
            } else {
                if (scale != 1) {
                    Value longIndex = index.getKind() == Kind.Long ? index : emitSignExtend(index, 32, 64);
                    if (CodeUtil.isPowerOf2(scale)) {
                        indexRegister = emitShl(longIndex, JavaConstant.forLong(CodeUtil.log2(scale)));
                    } else {
                        indexRegister = emitMul(longIndex, JavaConstant.forLong(scale), false);
                    }
                } else {
                    indexRegister = asAllocatable(index);
                }
            }
        } else {
            indexRegister = Value.ILLEGAL;
        }

        int displacementInt;

        // If we don't have an index register we can use a displacement, otherwise load the
        // displacement into a register and add it to the base.
        if (indexRegister.equals(Value.ILLEGAL) && SPARCAssembler.isSimm13(finalDisp)) {
            displacementInt = (int) finalDisp;
        } else {
            displacementInt = 0;
            if (baseRegister.equals(Value.ILLEGAL)) {
                baseRegister = load(JavaConstant.forLong(finalDisp));
            } else {
                if (finalDisp == 0) {
                    // Nothing to do. Just use the base register.
                } else {
                    Variable longBaseRegister = newVariable(LIRKind.derivedReference(Kind.Long));
                    emitMove(longBaseRegister, baseRegister);
                    baseRegister = emitAdd(longBaseRegister, JavaConstant.forLong(finalDisp), false);
                }
            }
        }

        LIRKind resultKind = getAddressKind(base, displacement, index);
        return new SPARCAddressValue(resultKind, baseRegister, indexRegister, displacementInt);
    }

    protected SPARCAddressValue asAddressValue(Value address) {
        if (address instanceof SPARCAddressValue) {
            return (SPARCAddressValue) address;
        } else {
            return emitAddress(address, 0, Value.ILLEGAL, 0);
        }
    }

    @Override
    public Variable emitAddress(StackSlotValue address) {
        Variable result = newVariable(LIRKind.value(target().wordKind));
        append(new StackLoadAddressOp(result, address));
        return result;
    }

    @Override
    public void emitReturn(Value input) {
        AllocatableValue operand = Value.ILLEGAL;
        if (input != null) {
            operand = resultOperandFor(input.getLIRKind());
            emitMove(operand, input);
        }
        append(new ReturnOp(operand));
    }

    @Override
    public void emitJump(LabelRef label) {
        append(new SPARCJumpOp(label));
    }

    @Override
    public void emitCompareBranch(PlatformKind cmpKind, Value x, Value y, Condition cond, boolean unorderedIsTrue, LabelRef trueDestination, LabelRef falseDestination,
                    double trueDestinationProbability) {
        Variable left;
        Value right;
        Condition actualCondition;
        if (isConstant(x)) {
            left = load(y);
            right = loadNonConst(x);
            actualCondition = cond.mirror();
        } else {
            left = load(x);
            right = loadNonConst(y);
            actualCondition = cond;
        }
        SPARCCompare opcode;
        Kind kind = left.getKind().getStackKind();
        switch (kind) {
            case Object:
                opcode = ACMP;
                break;
            case Long:
                opcode = LCMP;
                break;
            case Int:
            case Short:
            case Char:
            case Byte:
                opcode = ICMP;
                break;
            case Float:
                opcode = FCMP;
                break;
            case Double:
                opcode = DCMP;
                break;
            default:
                throw GraalInternalError.shouldNotReachHere(kind.toString());
        }
        append(new SPARCControlFlow.CompareBranchOp(opcode, left, right, actualCondition, trueDestination, falseDestination, kind, unorderedIsTrue, trueDestinationProbability));
    }

    @Override
    public void emitOverflowCheckBranch(LabelRef overflow, LabelRef noOverflow, LIRKind cmpLIRKind, double overflowProbability) {
        Kind cmpKind = (Kind) cmpLIRKind.getPlatformKind();
        append(new BranchOp(ConditionFlag.OverflowSet, overflow, noOverflow, cmpKind, overflowProbability));
    }

    @Override
    public void emitIntegerTestBranch(Value left, Value right, LabelRef trueDestination, LabelRef falseDestination, double trueDestinationProbability) {
        emitIntegerTest(left, right);
        append(new BranchOp(ConditionFlag.Equal, trueDestination, falseDestination, left.getKind().getStackKind(), trueDestinationProbability));
    }

    private void emitIntegerTest(Value a, Value b) {
        assert a.getKind().isNumericInteger();
        if (LIRValueUtil.isVariable(b)) {
            append(new SPARCTestOp(load(b), loadNonConst(a)));
        } else {
            append(new SPARCTestOp(load(a), loadNonConst(b)));
        }
    }

    private Value loadSimm11(Value value) {
        if (isConstant(value)) {
            JavaConstant c = asConstant(value);
            if (c.isNull() || SPARCAssembler.isSimm11(c)) {
                return value;
            }
        }
        return load(value);
    }

    @Override
    public Variable emitConditionalMove(PlatformKind cmpKind, Value left, Value right, Condition cond, boolean unorderedIsTrue, Value trueValue, Value falseValue) {
        boolean mirrored = emitCompare(cmpKind, left, right);
        CC conditionFlags;
        Value actualTrueValue = trueValue;
        Value actualFalseValue = falseValue;
        switch ((Kind) left.getLIRKind().getPlatformKind()) {
            case Byte:
            case Short:
            case Char:
            case Int:
                conditionFlags = CC.Icc;
                actualTrueValue = loadSimm11(trueValue);
                actualFalseValue = loadSimm11(falseValue);
                break;
            case Object:
            case Long:
                conditionFlags = CC.Xcc;
                actualTrueValue = loadSimm11(trueValue);
                actualFalseValue = loadSimm11(falseValue);
                break;
            case Float:
            case Double:
                conditionFlags = CC.Fcc0;
                actualTrueValue = load(trueValue); // Floats cannot be immediate at all
                actualFalseValue = load(falseValue);
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        Variable result = newVariable(trueValue.getLIRKind());
        ConditionFlag finalCondition = ConditionFlag.fromCondtition(conditionFlags, mirrored ? cond.mirror() : cond, unorderedIsTrue);
        Kind kind = result.getKind().getStackKind();
        append(new CondMoveOp(kind, result, conditionFlags, finalCondition, actualTrueValue, actualFalseValue));
        return result;
    }

    /**
     * This method emits the compare instruction, and may reorder the operands. It returns true if
     * it did so.
     *
     * @param cmpKind Kind how a and b have to be compared
     * @param a the left operand of the comparison
     * @param b the right operand of the comparison
     * @return true if the left and right operands were switched, false otherwise
     */
    protected boolean emitCompare(PlatformKind cmpKind, Value a, Value b) {
        Variable left;
        Value right;
        boolean mirrored;
        if (LIRValueUtil.isVariable(b)) {
            left = load(b);
            right = loadNonConst(a);
            mirrored = true;
        } else {
            left = load(a);
            right = loadNonConst(b);
            mirrored = false;
        }
        switch ((Kind) cmpKind) {
            case Short:
            case Char:
                append(new CompareOp(ICMP, emitSignExtend(left, 16, 32), emitSignExtend(right, 16, 32)));
                break;
            case Byte:
                append(new CompareOp(ICMP, emitSignExtend(left, 8, 32), emitSignExtend(right, 8, 32)));
                break;
            case Int:
                append(new CompareOp(ICMP, left, right));
                break;
            case Long:
                append(new CompareOp(LCMP, left, right));
                break;
            case Object:
                append(new CompareOp(ACMP, left, right));
                break;
            case Float:
                append(new CompareOp(FCMP, left, right));
                break;
            case Double:
                append(new CompareOp(DCMP, left, right));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        return mirrored;
    }

    @Override
    public Variable emitIntegerTestMove(Value left, Value right, Value trueValue, Value falseValue) {
        emitIntegerTest(left, right);
        Variable result = newVariable(trueValue.getLIRKind());
        Kind kind = left.getKind().getStackKind();
        CC conditionCode;
        switch (kind) {
            case Object:
            case Long:
                conditionCode = CC.Xcc;
                break;
            case Int:
            case Short:
            case Char:
            case Byte:
                conditionCode = CC.Icc;
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        ConditionFlag flag = ConditionFlag.fromCondtition(conditionCode, Condition.EQ, false);
        append(new CondMoveOp(kind, result, conditionCode, flag, loadSimm11(trueValue), loadSimm11(falseValue)));
        return result;
    }

    @Override
    protected void emitForeignCallOp(ForeignCallLinkage linkage, Value result, Value[] arguments, Value[] temps, LIRFrameState info) {
        long maxOffset = linkage.getMaxCallTargetOffset();
        if (SPARCAssembler.isWordDisp30(maxOffset)) {
            append(new SPARCCall.DirectNearForeignCallOp(linkage, result, arguments, temps, info));
        } else {
            append(new SPARCCall.DirectFarForeignCallOp(linkage, result, arguments, temps, info));
        }
    }

    @Override
    public void emitStrategySwitch(SwitchStrategy strategy, Variable key, LabelRef[] keyTargets, LabelRef defaultTarget) {
        append(new StrategySwitchOp(strategy, keyTargets, defaultTarget, key, newVariable(key.getLIRKind())));
    }

    @Override
    protected void emitTableSwitch(int lowKey, LabelRef defaultTarget, LabelRef[] targets, Value key) {
        // Making a copy of the switch value is necessary because jump table destroys the input
        // value
        Variable tmp = newVariable(key.getLIRKind());
        emitMove(tmp, key);
        append(new TableSwitchOp(lowKey, defaultTarget, targets, tmp, newVariable(LIRKind.value(target().wordKind))));
    }

    @Override
    public Variable emitBitCount(Value operand) {
        Variable result = newVariable(LIRKind.derive(operand).changeType(Kind.Int));
        if (operand.getKind().getStackKind() == Kind.Int) {
            append(new SPARCBitManipulationOp(IPOPCNT, result, asAllocatable(operand), this));
        } else {
            append(new SPARCBitManipulationOp(LPOPCNT, result, asAllocatable(operand), this));
        }
        return result;
    }

    @Override
    public Variable emitBitScanForward(Value operand) {
        Variable result = newVariable(LIRKind.derive(operand).changeType(Kind.Int));
        append(new SPARCBitManipulationOp(BSF, result, asAllocatable(operand), this));
        return result;
    }

    @Override
    public Variable emitBitScanReverse(Value operand) {
        Variable result = newVariable(LIRKind.derive(operand).changeType(Kind.Int));
        if (operand.getKind().getStackKind() == Kind.Int) {
            append(new SPARCBitManipulationOp(IBSR, result, asAllocatable(operand), this));
        } else {
            append(new SPARCBitManipulationOp(LBSR, result, asAllocatable(operand), this));
        }
        return result;
    }

    @Override
    public Value emitMathAbs(Value input) {
        Variable result = newVariable(LIRKind.derive(input));
        append(new SPARCMathIntrinsicOp(ABS, result, asAllocatable(input)));
        return result;
    }

    @Override
    public Value emitMathSqrt(Value input) {
        Variable result = newVariable(LIRKind.derive(input));
        append(new SPARCMathIntrinsicOp(SQRT, result, asAllocatable(input)));
        return result;
    }

    @Override
    public Variable emitByteSwap(Value input) {
        Variable result = newVariable(LIRKind.derive(input));
        append(new SPARCByteSwapOp(this, result, input));
        return result;
    }

    @Override
    public Variable emitArrayEquals(Kind kind, Value array1, Value array2, Value length) {
        Variable result = newVariable(LIRKind.value(Kind.Int));
        append(new SPARCArrayEqualsOp(this, kind, result, load(array1), load(array2), asAllocatable(length)));
        return result;
    }

    @Override
    public Value emitNegate(Value input) {
        switch (input.getKind().getStackKind()) {
            case Long:
                return emitUnary(LNEG, input);
            case Int:
                return emitUnary(INEG, input);
            case Float:
                return emitUnary(FNEG, input);
            case Double:
                return emitUnary(DNEG, input);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    @Override
    public Value emitNot(Value input) {
        switch (input.getKind().getStackKind()) {
            case Int:
                return emitUnary(INOT, input);
            case Long:
                return emitUnary(LNOT, input);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    private Variable emitUnary(SPARCArithmetic op, Value input) {
        Variable result = newVariable(LIRKind.derive(input));
        append(new Unary2Op(op, result, load(input)));
        return result;
    }

    private Variable emitBinary(SPARCArithmetic op, boolean commutative, Value a, Value b) {
        return emitBinary(op, commutative, a, b, null);
    }

    private Variable emitBinary(SPARCArithmetic op, boolean commutative, Value a, Value b, LIRFrameState state) {
        if (isConstant(b) && canInlineConstant(asConstant(b))) {
            return emitBinaryConst(op, load(a), asConstant(b), state);
        } else if (commutative && isConstant(a) && canInlineConstant(asConstant(a))) {
            return emitBinaryConst(op, load(b), asConstant(a), state);
        } else {
            return emitBinaryVar(op, load(a), load(b), state);
        }
    }

    private Variable emitBinaryConst(SPARCArithmetic op, AllocatableValue a, JavaConstant b, LIRFrameState state) {
        switch (op) {
            case IADD:
            case LADD:
            case ISUB:
            case LSUB:
            case IAND:
            case LAND:
            case IOR:
            case LOR:
            case IXOR:
            case LXOR:
            case IMUL:
            case LMUL:
                if (canInlineConstant(b)) {
                    Variable result = newVariable(LIRKind.derive(a, b));
                    append(new BinaryRegConst(op, result, a, b, state));
                    return result;
                }
                break;
        }
        return emitBinaryVar(op, a, asAllocatable(b), state);
    }

    private Variable emitBinaryVar(SPARCArithmetic op, AllocatableValue a, AllocatableValue b, LIRFrameState state) {
        Variable result = newVariable(LIRKind.derive(a, b));
        append(new BinaryRegReg(op, result, a, b, state));
        return result;
    }

    @Override
    public Variable emitAdd(Value a, Value b, boolean setFlags) {
        switch (a.getKind().getStackKind()) {
            case Int:
                return emitBinary(setFlags ? IADDCC : IADD, true, a, b);
            case Long:
                return emitBinary(setFlags ? LADDCC : LADD, true, a, b);
            case Float:
                return emitBinary(FADD, true, a, b);
            case Double:
                return emitBinary(DADD, true, a, b);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    @Override
    public Variable emitSub(Value a, Value b, boolean setFlags) {
        switch (a.getKind().getStackKind()) {
            case Int:
                return emitBinary(setFlags ? ISUBCC : ISUB, false, a, b);
            case Long:
                return emitBinary(setFlags ? LSUBCC : LSUB, false, a, b);
            case Float:
                return emitBinary(FSUB, false, a, b);
            case Double:
                return emitBinary(DSUB, false, a, b);
            default:
                throw GraalInternalError.shouldNotReachHere("missing: " + a.getKind());
        }
    }

    @Override
    public Variable emitMul(Value a, Value b, boolean setFlags) {
        switch (a.getKind().getStackKind()) {
            case Int:
                return emitBinary(setFlags ? IMULCC : IMUL, true, a, b);
            case Long:
                if (setFlags) {
                    Variable result = newVariable(LIRKind.derive(a, b));
                    append(new SPARCLMulccOp(result, load(a), load(b), this));
                    return result;
                } else {
                    return emitBinary(LMUL, true, a, b);
                }
            case Float:
                return emitBinary(FMUL, true, a, b);
            case Double:
                return emitBinary(DMUL, true, a, b);
            default:
                throw GraalInternalError.shouldNotReachHere("missing: " + a.getKind());
        }
    }

    @Override
    public Value emitMulHigh(Value a, Value b) {
        switch (a.getKind().getStackKind()) {
            case Int:
                return emitMulHigh(IMUL, a, b);
            case Long:
                return emitMulHigh(LMUL, a, b);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    @Override
    public Value emitUMulHigh(Value a, Value b) {
        switch (a.getKind().getStackKind()) {
            case Int:
                return emitMulHigh(IUMUL, a, b);
            case Long:
                return emitMulHigh(LUMUL, a, b);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    private Value emitMulHigh(SPARCArithmetic opcode, Value a, Value b) {
        Variable result = newVariable(LIRKind.derive(a, b));
        MulHighOp mulHigh = new MulHighOp(opcode, load(a), load(b), result, newVariable(LIRKind.derive(a, b)));
        append(mulHigh);
        return result;
    }

    @Override
    public Value emitDiv(Value a, Value b, LIRFrameState state) {
        switch (a.getKind().getStackKind()) {
            case Int:
                return emitBinary(IDIV, false, a, b, state);
            case Long:
                return emitBinary(LDIV, false, a, b, state);
            case Float:
                return emitBinary(FDIV, false, a, b, state);
            case Double:
                return emitBinary(DDIV, false, a, b, state);
            default:
                throw GraalInternalError.shouldNotReachHere("missing: " + a.getKind());
        }
    }

    @Override
    public Value emitRem(Value a, Value b, LIRFrameState state) {
        Variable result = newVariable(LIRKind.derive(a, b));
        Variable q = null;
        switch (a.getKind().getStackKind()) {
            case Int:
                append(new RemOp(IREM, result, load(a), loadNonConst(b), state, this));
                break;
            case Long:
                append(new RemOp(LREM, result, load(a), loadNonConst(b), state, this));
                break;
            case Float:
                q = newVariable(LIRKind.value(Kind.Float));
                append(new BinaryRegReg(FDIV, q, a, b, state));
                append(new Unary2Op(F2I, q, q));
                append(new Unary2Op(I2F, q, q));
                append(new BinaryRegReg(FMUL, q, q, b));
                append(new BinaryRegReg(FSUB, result, a, q));
                break;
            case Double:
                q = newVariable(LIRKind.value(Kind.Double));
                append(new BinaryRegReg(DDIV, q, a, b, state));
                append(new Unary2Op(D2L, q, q));
                append(new Unary2Op(L2D, q, q));
                append(new BinaryRegReg(DMUL, q, q, b));
                append(new BinaryRegReg(DSUB, result, a, q));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere("missing: " + a.getKind());
        }
        return result;
    }

    @Override
    public Value emitURem(Value a, Value b, LIRFrameState state) {
        Variable result = newVariable(LIRKind.derive(a, b));
        switch (a.getKind().getStackKind()) {
            case Int:
                append(new RemOp(IUREM, result, load(a), load(b), state, this));
                break;
            case Long:
                append(new RemOp(LUREM, result, load(a), loadNonConst(b), state, this));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        return result;

    }

    @Override
    public Value emitUDiv(Value a, Value b, LIRFrameState state) {
        SPARCArithmetic op;
        Value actualA = a;
        Value actualB = b;
        switch (a.getKind().getStackKind()) {
            case Int:
                op = LUDIV;
                actualA = emitZeroExtend(actualA, 32, 64);
                actualB = emitZeroExtend(actualB, 32, 64);
                break;
            case Long:
                op = LUDIV;
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        return emitBinary(op, false, actualA, actualB, state);
    }

    @Override
    public Variable emitAnd(Value a, Value b) {
        switch (a.getKind().getStackKind()) {
            case Int:
                return emitBinary(IAND, true, a, b);
            case Long:
                return emitBinary(LAND, true, a, b);

            default:
                throw GraalInternalError.shouldNotReachHere("missing: " + a.getKind());
        }
    }

    @Override
    public Variable emitOr(Value a, Value b) {
        switch (a.getKind().getStackKind()) {
            case Int:
                return emitBinary(IOR, true, a, b);
            case Long:
                return emitBinary(LOR, true, a, b);
            default:
                throw GraalInternalError.shouldNotReachHere("missing: " + a.getKind());
        }
    }

    @Override
    public Variable emitXor(Value a, Value b) {
        switch (a.getKind().getStackKind()) {
            case Int:
                return emitBinary(IXOR, true, a, b);
            case Long:
                return emitBinary(LXOR, true, a, b);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    private Variable emitShift(SPARCArithmetic op, Value a, Value b) {
        Variable result = newVariable(LIRKind.derive(a, b).changeType(a.getPlatformKind()));
        if (isConstant(b) && canInlineConstant((JavaConstant) b)) {
            append(new BinaryRegConst(op, result, load(a), asConstant(b), null));
        } else {
            append(new BinaryRegReg(op, result, load(a), load(b)));
        }
        return result;
    }

    @Override
    public Variable emitShl(Value a, Value b) {
        switch (a.getKind().getStackKind()) {
            case Int:
                return emitShift(ISHL, a, b);
            case Long:
                return emitShift(LSHL, a, b);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    @Override
    public Variable emitShr(Value a, Value b) {
        switch (a.getKind().getStackKind()) {
            case Int:
                return emitShift(ISHR, a, b);
            case Long:
                return emitShift(LSHR, a, b);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    @Override
    public Variable emitUShr(Value a, Value b) {
        switch (a.getKind().getStackKind()) {
            case Int:
                return emitShift(IUSHR, a, b);
            case Long:
                return emitShift(LUSHR, a, b);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    private AllocatableValue emitConvertMove(LIRKind kind, AllocatableValue input) {
        Variable result = newVariable(kind);
        emitMove(result, input);
        return result;
    }

    private AllocatableValue emitConvert2Op(LIRKind kind, SPARCArithmetic op, AllocatableValue input) {
        Variable result = newVariable(kind);
        append(new Unary2Op(op, result, input));
        return result;
    }

    @Override
    public Value emitFloatConvert(FloatConvert op, Value inputVal) {
        AllocatableValue input = asAllocatable(inputVal);
        switch (op) {
            case D2F:
                return emitConvert2Op(LIRKind.derive(inputVal).changeType(Kind.Float), D2F, input);
            case F2D:
                return emitConvert2Op(LIRKind.derive(inputVal).changeType(Kind.Double), F2D, input);
            case I2F: {
                AllocatableValue convertedFloatReg = newVariable(LIRKind.derive(input).changeType(Kind.Float));
                moveBetweenFpGp(convertedFloatReg, input);
                append(new Unary2Op(I2F, convertedFloatReg, convertedFloatReg));
                return convertedFloatReg;
            }
            case I2D: {
                // Unfortunately we must do int -> float -> double because fitod has float
                // and double encoding in one instruction
                AllocatableValue convertedFloatReg = newVariable(LIRKind.derive(input).changeType(Kind.Float));
                moveBetweenFpGp(convertedFloatReg, input);
                AllocatableValue convertedDoubleReg = newVariable(LIRKind.derive(input).changeType(Kind.Double));
                append(new Unary2Op(I2D, convertedDoubleReg, convertedFloatReg));
                return convertedDoubleReg;
            }
            case L2D: {
                AllocatableValue convertedDoubleReg = newVariable(LIRKind.derive(input).changeType(Kind.Double));
                moveBetweenFpGp(convertedDoubleReg, input);
                append(new Unary2Op(L2D, convertedDoubleReg, convertedDoubleReg));
                return convertedDoubleReg;
            }
            case D2I: {
                AllocatableValue convertedFloatReg = emitConvert2Op(LIRKind.derive(input).changeType(Kind.Float), D2I, input);
                AllocatableValue convertedIntReg = newVariable(LIRKind.derive(convertedFloatReg).changeType(Kind.Int));
                moveBetweenFpGp(convertedIntReg, convertedFloatReg);
                return convertedIntReg;
            }
            case F2L: {
                AllocatableValue convertedDoubleReg = emitConvert2Op(LIRKind.derive(input).changeType(Kind.Double), F2L, input);
                AllocatableValue convertedLongReg = newVariable(LIRKind.derive(convertedDoubleReg).changeType(Kind.Long));
                moveBetweenFpGp(convertedLongReg, convertedDoubleReg);
                return convertedLongReg;
            }
            case F2I: {
                AllocatableValue convertedFloatReg = emitConvert2Op(LIRKind.derive(input).changeType(Kind.Float), F2I, input);
                AllocatableValue convertedIntReg = newVariable(LIRKind.derive(convertedFloatReg).changeType(Kind.Int));
                moveBetweenFpGp(convertedIntReg, convertedFloatReg);
                return convertedIntReg;
            }
            case D2L: {
                AllocatableValue convertedDoubleReg = emitConvert2Op(LIRKind.derive(input).changeType(Kind.Double), D2L, input);
                AllocatableValue convertedLongReg = newVariable(LIRKind.derive(convertedDoubleReg).changeType(Kind.Long));
                moveBetweenFpGp(convertedLongReg, convertedDoubleReg);
                return convertedLongReg;
            }
            case L2F: {
                // long -> double -> float see above
                AllocatableValue convertedDoubleReg = newVariable(LIRKind.derive(input).changeType(Kind.Double));
                moveBetweenFpGp(convertedDoubleReg, input);
                AllocatableValue convertedFloatReg = newVariable(LIRKind.derive(input).changeType(Kind.Float));
                append(new Unary2Op(L2F, convertedFloatReg, convertedDoubleReg));
                return convertedFloatReg;
            }
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    private void moveBetweenFpGp(AllocatableValue dst, AllocatableValue src) {
        if (!getArchitecture().getFeatures().contains(CPUFeature.VIS3)) {
            StackSlotValue tempSlot = getTempSlot(LIRKind.value(Kind.Long));
            append(new MoveFpGp(dst, src, tempSlot));
        } else {
            append(new MoveFpGpVIS3(dst, src));
        }
    }

    private StackSlotValue getTempSlot(LIRKind kind) {
        if (tmpStackSlot == null) {
            tmpStackSlot = getResult().getFrameMapBuilder().allocateSpillSlot(kind);
        }
        return tmpStackSlot;
    }

    protected SPARC getArchitecture() {
        return (SPARC) target().arch;
    }

    @Override
    public Value emitNarrow(Value inputVal, int bits) {
        if (inputVal.getKind() == Kind.Long && bits <= 32) {
            return emitConvert2Op(LIRKind.derive(inputVal).changeType(Kind.Int), L2I, asAllocatable(inputVal));
        } else {
            return inputVal;
        }
    }

    @Override
    public Value emitSignExtend(Value inputVal, int fromBits, int toBits) {
        assert fromBits <= toBits && toBits <= 64;
        if (fromBits == toBits) {
            return inputVal;
        } else if (toBits > 32) {
            // sign extend to 64 bits
            switch (fromBits) {
                case 8:
                    return emitConvert2Op(LIRKind.derive(inputVal).changeType(Kind.Long), B2L, asAllocatable(inputVal));
                case 16:
                    return emitConvert2Op(LIRKind.derive(inputVal).changeType(Kind.Long), S2L, asAllocatable(inputVal));
                case 32:
                    return emitConvert2Op(LIRKind.derive(inputVal).changeType(Kind.Long), I2L, asAllocatable(inputVal));
                default:
                    throw GraalInternalError.unimplemented("unsupported sign extension (" + fromBits + " bit -> " + toBits + " bit)");
            }
        } else {
            // sign extend to 32 bits (smaller values are internally represented as 32 bit values)
            switch (fromBits) {
                case 8:
                    return emitConvert2Op(LIRKind.derive(inputVal).changeType(Kind.Int), B2I, asAllocatable(inputVal));
                case 16:
                    return emitConvert2Op(LIRKind.derive(inputVal).changeType(Kind.Int), S2I, asAllocatable(inputVal));
                case 32:
                    return inputVal;
                default:
                    throw GraalInternalError.unimplemented("unsupported sign extension (" + fromBits + " bit -> " + toBits + " bit)");
            }
        }
    }

    @Override
    public Value emitZeroExtend(Value inputVal, int fromBits, int toBits) {
        assert fromBits <= toBits && toBits <= 64;
        if (fromBits == toBits) {
            return inputVal;
        } else if (fromBits > 32) {
            assert inputVal.getKind() == Kind.Long;
            Variable result = newVariable(LIRKind.derive(inputVal).changeType(Kind.Long));
            long mask = CodeUtil.mask(fromBits);
            append(new BinaryRegConst(SPARCArithmetic.LAND, result, asAllocatable(inputVal), JavaConstant.forLong(mask), null));
            return result;
        } else {
            assert inputVal.getKind() == Kind.Int || inputVal.getKind() == Kind.Short || inputVal.getKind() == Kind.Byte || inputVal.getKind() == Kind.Char : inputVal.getKind();
            Variable result = newVariable(LIRKind.derive(inputVal).changeType(Kind.Int));
            long mask = CodeUtil.mask(fromBits);
            JavaConstant constant = JavaConstant.forInt((int) mask);
            if (fromBits == 32) {
                append(new BinaryRegConst(IUSHR, result, inputVal, JavaConstant.forInt(0)));
            } else if (canInlineConstant(constant)) {
                append(new BinaryRegConst(SPARCArithmetic.IAND, result, asAllocatable(inputVal), constant, null));
            } else {
                Variable maskVar = newVariable(LIRKind.derive(inputVal).changeType(Kind.Int));
                emitMove(maskVar, constant);
                append(new BinaryRegReg(IAND, result, maskVar, asAllocatable(inputVal)));
            }
            if (toBits > 32) {
                Variable longResult = newVariable(LIRKind.derive(inputVal).changeType(Kind.Long));
                emitMove(longResult, result);
                return longResult;
            } else {
                return result;
            }
        }
    }

    @Override
    public AllocatableValue emitReinterpret(LIRKind to, Value inputVal) {
        Kind from = inputVal.getKind();
        AllocatableValue input = asAllocatable(inputVal);
        Variable result = newVariable(to);
        // These cases require a move between CPU and FPU registers:
        switch ((Kind) to.getPlatformKind()) {
            case Int:
                switch (from) {
                    case Float:
                    case Double:
                        moveBetweenFpGp(result, input);
                        return result;
                }
                break;
            case Long:
                switch (from) {
                    case Float:
                    case Double:
                        moveBetweenFpGp(result, input);
                        return result;
                }
                break;
            case Float:
                switch (from) {
                    case Int:
                    case Long:
                        moveBetweenFpGp(result, input);
                        return result;
                }
                break;
            case Double:
                switch (from) {
                    case Int:
                    case Long:
                        moveBetweenFpGp(result, input);
                        return result;
                }
                break;
        }

        // Otherwise, just emit an ordinary move instruction.
        // Instructions that move or generate 32-bit register values also set the upper 32
        // bits of the register to zero.
        // Consequently, there is no need for a special zero-extension move.
        return emitConvertMove(to, input);
    }

    @Override
    public void emitMembar(int barriers) {
        int necessaryBarriers = target().arch.requiredBarriers(barriers);
        if (target().isMP && necessaryBarriers != 0) {
            append(new MembarOp(necessaryBarriers));
        }
    }

    @Override
    public void emitDeoptimize(Value actionAndReason, Value speculation, LIRFrameState state) {
        append(new ReturnOp(Value.ILLEGAL));
    }

}
