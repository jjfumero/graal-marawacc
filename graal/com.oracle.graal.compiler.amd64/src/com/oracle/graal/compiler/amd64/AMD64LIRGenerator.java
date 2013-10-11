/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.graal.compiler.amd64;

import static com.oracle.graal.api.code.ValueUtil.*;
import static com.oracle.graal.lir.amd64.AMD64Arithmetic.*;
import static com.oracle.graal.lir.amd64.AMD64BitManipulationOp.IntrinsicOpcode.*;
import static com.oracle.graal.lir.amd64.AMD64Compare.*;
import static com.oracle.graal.lir.amd64.AMD64MathIntrinsicOp.IntrinsicOpcode.*;

import com.oracle.graal.amd64.*;
import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.asm.amd64.AMD64Address.Scale;
import com.oracle.graal.asm.amd64.AMD64Assembler.ConditionFlag;
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.StandardOp.JumpOp;
import com.oracle.graal.lir.amd64.*;
import com.oracle.graal.lir.amd64.AMD64Arithmetic.BinaryCommutative;
import com.oracle.graal.lir.amd64.AMD64Arithmetic.BinaryRegConst;
import com.oracle.graal.lir.amd64.AMD64Arithmetic.BinaryRegReg;
import com.oracle.graal.lir.amd64.AMD64Arithmetic.BinaryRegStack;
import com.oracle.graal.lir.amd64.AMD64Arithmetic.BinaryRegStackConst;
import com.oracle.graal.lir.amd64.AMD64Arithmetic.DivRemOp;
import com.oracle.graal.lir.amd64.AMD64Arithmetic.FPDivRemOp;
import com.oracle.graal.lir.amd64.AMD64Arithmetic.Unary1Op;
import com.oracle.graal.lir.amd64.AMD64Arithmetic.Unary2Op;
import com.oracle.graal.lir.amd64.AMD64Compare.CompareOp;
import com.oracle.graal.lir.amd64.AMD64ControlFlow.BranchOp;
import com.oracle.graal.lir.amd64.AMD64ControlFlow.CondMoveOp;
import com.oracle.graal.lir.amd64.AMD64ControlFlow.FloatBranchOp;
import com.oracle.graal.lir.amd64.AMD64ControlFlow.FloatCondMoveOp;
import com.oracle.graal.lir.amd64.AMD64ControlFlow.ReturnOp;
import com.oracle.graal.lir.amd64.AMD64ControlFlow.SequentialSwitchOp;
import com.oracle.graal.lir.amd64.AMD64ControlFlow.SwitchRangesOp;
import com.oracle.graal.lir.amd64.AMD64ControlFlow.TableSwitchOp;
import com.oracle.graal.lir.amd64.AMD64Move.LeaOp;
import com.oracle.graal.lir.amd64.AMD64Move.MembarOp;
import com.oracle.graal.lir.amd64.AMD64Move.MoveFromRegOp;
import com.oracle.graal.lir.amd64.AMD64Move.MoveToRegOp;
import com.oracle.graal.lir.amd64.AMD64Move.StackLeaOp;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.phases.util.*;

/**
 * This class implements the AMD64 specific portion of the LIR generator.
 */
public abstract class AMD64LIRGenerator extends LIRGenerator {

    private static final RegisterValue RAX_I = AMD64.rax.asValue(Kind.Int);
    private static final RegisterValue RAX_L = AMD64.rax.asValue(Kind.Long);
    private static final RegisterValue RDX_I = AMD64.rdx.asValue(Kind.Int);
    private static final RegisterValue RDX_L = AMD64.rdx.asValue(Kind.Long);
    private static final RegisterValue RCX_I = AMD64.rcx.asValue(Kind.Int);

    private class AMD64SpillMoveFactory implements LIR.SpillMoveFactory {

        @Override
        public LIRInstruction createMove(AllocatableValue result, Value input) {
            return AMD64LIRGenerator.this.createMove(result, input);
        }
    }

    public AMD64LIRGenerator(StructuredGraph graph, MetaAccessProvider metaAccess, CodeCacheProvider codeCache, TargetDescription target, FrameMap frameMap, CallingConvention cc, LIR lir) {
        super(graph, metaAccess, codeCache, target, frameMap, cc, lir);
        lir.spillMoveFactory = new AMD64SpillMoveFactory();
    }

    @Override
    public boolean canStoreConstant(Constant c) {
        // there is no immediate move of 64-bit constants on Intel
        switch (c.getKind()) {
            case Long:
                return Util.isInt(c.asLong()) && !codeCache.needsDataPatch(c);
            case Double:
                return false;
            case Object:
                return c.isNull();
            default:
                return true;
        }
    }

    @Override
    public boolean canInlineConstant(Constant c) {
        switch (c.getKind()) {
            case Long:
                return NumUtil.isInt(c.asLong()) && !codeCache.needsDataPatch(c);
            case Object:
                return c.isNull();
            default:
                return true;
        }
    }

    @Override
    public Variable emitMove(Value input) {
        Variable result = newVariable(input.getPlatformKind());
        emitMove(result, input);
        return result;
    }

    protected AMD64LIRInstruction createMove(AllocatableValue dst, Value src) {
        if (src instanceof AMD64AddressValue) {
            return new LeaOp(dst, (AMD64AddressValue) src);
        } else if (isRegister(src) || isStackSlot(dst)) {
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
    public AMD64AddressValue emitAddress(Value base, long displacement, Value index, int scale) {
        AllocatableValue baseRegister;
        long finalDisp = displacement;
        if (isConstant(base)) {
            if (asConstant(base).isNull()) {
                baseRegister = Value.ILLEGAL;
            } else if (asConstant(base).getKind() != Kind.Object && !codeCache.needsDataPatch(asConstant(base))) {
                finalDisp += asConstant(base).asLong();
                baseRegister = Value.ILLEGAL;
            } else {
                baseRegister = load(base);
            }
        } else {
            baseRegister = asAllocatable(base);
        }

        AllocatableValue indexRegister;
        Scale scaleEnum;
        if (!index.equals(Value.ILLEGAL) && scale != 0) {
            scaleEnum = Scale.fromInt(scale);
            if (isConstant(index)) {
                finalDisp += asConstant(index).asLong() * scale;
                indexRegister = Value.ILLEGAL;
            } else {
                indexRegister = asAllocatable(index);
            }
        } else {
            indexRegister = Value.ILLEGAL;
            scaleEnum = Scale.Times1;
        }

        int displacementInt;
        if (NumUtil.isInt(finalDisp)) {
            displacementInt = (int) finalDisp;
        } else {
            displacementInt = 0;
            AllocatableValue displacementRegister = load(Constant.forLong(finalDisp));
            if (baseRegister.equals(Value.ILLEGAL)) {
                baseRegister = displacementRegister;
            } else if (indexRegister.equals(Value.ILLEGAL)) {
                indexRegister = displacementRegister;
                scaleEnum = Scale.Times1;
            } else {
                baseRegister = emitAdd(baseRegister, displacementRegister);
            }
        }

        return new AMD64AddressValue(target().wordKind, baseRegister, indexRegister, scaleEnum, displacementInt);
    }

    protected AMD64AddressValue asAddressValue(Value address) {
        if (address instanceof AMD64AddressValue) {
            return (AMD64AddressValue) address;
        } else {
            return emitAddress(address, 0, Value.ILLEGAL, 0);
        }
    }

    @Override
    public Variable emitAddress(StackSlot address) {
        Variable result = newVariable(target().wordKind);
        append(new StackLeaOp(result, address));
        return result;
    }

    @Override
    public void emitJump(LabelRef label) {
        append(new JumpOp(label));
    }

    @Override
    public void emitCompareBranch(Value left, Value right, Condition cond, boolean unorderedIsTrue, LabelRef label) {
        boolean mirrored = emitCompare(left, right);
        Condition finalCondition = mirrored ? cond.mirror() : cond;
        switch (left.getKind().getStackKind()) {
            case Int:
            case Long:
            case Object:
                append(new BranchOp(finalCondition, label));
                break;
            case Float:
            case Double:
                append(new FloatBranchOp(finalCondition, unorderedIsTrue, label));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere("" + left.getKind());
        }
    }

    @Override
    protected void emitIndirectCall(IndirectCallTargetNode callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState callState) {
        AllocatableValue targetAddress = AMD64.rax.asValue();
        emitMove(targetAddress, operand(callTarget.computedAddress()));
        append(new AMD64Call.IndirectCallOp(callTarget.target(), result, parameters, temps, targetAddress, callState));
    }

    @Override
    public void emitOverflowCheckBranch(LabelRef destination, boolean negated) {
        append(new BranchOp(negated ? ConditionFlag.NoOverflow : ConditionFlag.Overflow, destination));
    }

    @Override
    public void emitIntegerTestBranch(Value left, Value right, boolean negated, LabelRef label) {
        emitIntegerTest(left, right);
        append(new BranchOp(negated ? Condition.NE : Condition.EQ, label));
    }

    @Override
    public Variable emitConditionalMove(Value left, Value right, Condition cond, boolean unorderedIsTrue, Value trueValue, Value falseValue) {
        boolean mirrored = emitCompare(left, right);
        Condition finalCondition = mirrored ? cond.mirror() : cond;

        Variable result = newVariable(trueValue.getKind());
        switch (left.getKind().getStackKind()) {
            case Int:
            case Long:
            case Object:
                append(new CondMoveOp(result, finalCondition, load(trueValue), loadNonConst(falseValue)));
                break;
            case Float:
            case Double:
                append(new FloatCondMoveOp(result, finalCondition, unorderedIsTrue, load(trueValue), load(falseValue)));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere("" + left.getKind());
        }
        return result;
    }

    @Override
    public Variable emitIntegerTestMove(Value left, Value right, Value trueValue, Value falseValue) {
        emitIntegerTest(left, right);
        Variable result = newVariable(trueValue.getKind());
        append(new CondMoveOp(result, Condition.EQ, load(trueValue), loadNonConst(falseValue)));
        return result;
    }

    private void emitIntegerTest(Value a, Value b) {
        assert a.getKind().getStackKind() == Kind.Int || a.getKind() == Kind.Long;
        if (LIRValueUtil.isVariable(b)) {
            append(new AMD64TestOp(load(b), loadNonConst(a)));
        } else {
            append(new AMD64TestOp(load(a), loadNonConst(b)));
        }
    }

    /**
     * This method emits the compare instruction, and may reorder the operands. It returns true if
     * it did so.
     * 
     * @param a the left operand of the comparison
     * @param b the right operand of the comparison
     * @return true if the left and right operands were switched, false otherwise
     */
    private boolean emitCompare(Value a, Value b) {
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
        switch (left.getKind().getStackKind()) {
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
    public void emitNullCheck(ValueNode v, DeoptimizingNode deoping) {
        assert v.kind() == Kind.Object;
        append(new AMD64Move.NullCheckOp(load(operand(v)), state(deoping)));
    }

    @Override
    public Variable emitNegate(Value inputVal) {
        AllocatableValue input = asAllocatable(inputVal);
        Variable result = newVariable(input.getKind());
        switch (input.getKind()) {
            case Int:
                append(new Unary1Op(INEG, result, input));
                break;
            case Long:
                append(new Unary1Op(LNEG, result, input));
                break;
            case Float:
                append(new BinaryRegConst(FXOR, result, input, Constant.forFloat(Float.intBitsToFloat(0x80000000))));
                break;
            case Double:
                append(new BinaryRegConst(DXOR, result, input, Constant.forDouble(Double.longBitsToDouble(0x8000000000000000L))));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitNot(Value inputVal) {
        AllocatableValue input = asAllocatable(inputVal);
        Variable result = newVariable(input.getKind());
        switch (input.getKind()) {
            case Int:
                append(new Unary1Op(INOT, result, input));
                break;
            case Long:
                append(new Unary1Op(LNOT, result, input));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        return result;
    }

    private Variable emitBinary(AMD64Arithmetic op, boolean commutative, Value a, Value b) {
        if (isConstant(b)) {
            return emitBinaryConst(op, commutative, asAllocatable(a), asConstant(b));
        } else if (commutative && isConstant(a)) {
            return emitBinaryConst(op, commutative, asAllocatable(b), asConstant(a));
        } else {
            return emitBinaryVar(op, commutative, asAllocatable(a), asAllocatable(b));
        }
    }

    private Variable emitBinaryConst(AMD64Arithmetic op, boolean commutative, AllocatableValue a, Constant b) {
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
                if (NumUtil.isInt(b.asLong())) {
                    Variable result = newVariable(a.getKind());
                    append(new BinaryRegConst(op, result, a, b));
                    return result;
                }
                break;

            case IMUL:
            case LMUL:
                if (NumUtil.isInt(b.asLong())) {
                    Variable result = newVariable(a.getKind());
                    append(new BinaryRegStackConst(op, result, a, b));
                    return result;
                }
                break;
        }

        return emitBinaryVar(op, commutative, a, asAllocatable(b));
    }

    private Variable emitBinaryVar(AMD64Arithmetic op, boolean commutative, AllocatableValue a, AllocatableValue b) {
        Variable result = newVariable(a.getKind());
        if (commutative) {
            append(new BinaryCommutative(op, result, a, b));
        } else {
            append(new BinaryRegStack(op, result, a, b));
        }
        return result;
    }

    @Override
    public Variable emitAdd(Value a, Value b) {
        switch (a.getKind().getStackKind()) {
            case Int:
                return emitBinary(IADD, true, a, b);
            case Long:
                return emitBinary(LADD, true, a, b);
            case Float:
                return emitBinary(FADD, true, a, b);
            case Double:
                return emitBinary(DADD, true, a, b);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    @Override
    public Variable emitSub(Value a, Value b) {
        switch (a.getKind().getStackKind()) {
            case Int:
                return emitBinary(ISUB, false, a, b);
            case Long:
                return emitBinary(LSUB, false, a, b);
            case Float:
                return emitBinary(FSUB, false, a, b);
            case Double:
                return emitBinary(DSUB, false, a, b);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    @Override
    public Variable emitMul(Value a, Value b) {
        switch (a.getKind().getStackKind()) {
            case Int:
                return emitBinary(IMUL, true, a, b);
            case Long:
                return emitBinary(LMUL, true, a, b);
            case Float:
                return emitBinary(FMUL, true, a, b);
            case Double:
                return emitBinary(DMUL, true, a, b);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    @Override
    protected boolean peephole(ValueNode valueNode) {
        if ((valueNode instanceof IntegerDivNode) || (valueNode instanceof IntegerRemNode)) {
            FixedBinaryNode divRem = (FixedBinaryNode) valueNode;
            FixedNode node = divRem.next();
            while (node instanceof FixedWithNextNode) {
                FixedWithNextNode fixedWithNextNode = (FixedWithNextNode) node;
                if (((fixedWithNextNode instanceof IntegerDivNode) || (fixedWithNextNode instanceof IntegerRemNode)) && fixedWithNextNode.getClass() != divRem.getClass()) {
                    FixedBinaryNode otherDivRem = (FixedBinaryNode) fixedWithNextNode;
                    if (otherDivRem.x() == divRem.x() && otherDivRem.y() == divRem.y() && operand(otherDivRem) == null) {
                        Value[] results = emitIntegerDivRem(operand(divRem.x()), operand(divRem.y()), (DeoptimizingNode) valueNode);
                        if (divRem instanceof IntegerDivNode) {
                            setResult(divRem, results[0]);
                            setResult(otherDivRem, results[1]);
                        } else {
                            setResult(divRem, results[1]);
                            setResult(otherDivRem, results[0]);
                        }
                        return true;
                    }
                }
                node = fixedWithNextNode.next();
            }
        }
        return false;
    }

    private void emitDivRem(AMD64Arithmetic op, Value a, Value b, LIRFrameState state) {
        AllocatableValue rax = AMD64.rax.asValue(a.getPlatformKind());
        emitMove(rax, a);
        append(new DivRemOp(op, rax, asAllocatable(b), state));
    }

    public Value[] emitIntegerDivRem(Value a, Value b, DeoptimizingNode deopting) {
        LIRFrameState state = state(deopting);
        switch (a.getKind().getStackKind()) {
            case Int:
                emitDivRem(IDIVREM, a, b, state);
                return new Value[]{emitMove(RAX_I), emitMove(RDX_I)};
            case Long:
                emitDivRem(LDIVREM, a, b, state);
                return new Value[]{emitMove(RAX_L), emitMove(RDX_L)};
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    @Override
    public Value emitDiv(Value a, Value b, DeoptimizingNode deopting) {
        switch (a.getKind().getStackKind()) {
            case Int:
                emitDivRem(IDIV, a, b, state(deopting));
                return emitMove(RAX_I);
            case Long:
                emitDivRem(LDIV, a, b, state(deopting));
                return emitMove(RAX_L);
            case Float: {
                Variable result = newVariable(a.getPlatformKind());
                append(new BinaryRegStack(FDIV, result, asAllocatable(a), asAllocatable(b)));
                return result;
            }
            case Double: {
                Variable result = newVariable(a.getPlatformKind());
                append(new BinaryRegStack(DDIV, result, asAllocatable(a), asAllocatable(b)));
                return result;
            }
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    @Override
    public Value emitRem(Value a, Value b, DeoptimizingNode deopting) {
        switch (a.getKind().getStackKind()) {
            case Int:
                emitDivRem(IREM, a, b, state(deopting));
                return emitMove(RDX_I);
            case Long:
                emitDivRem(LREM, a, b, state(deopting));
                return emitMove(RDX_L);
            case Float: {
                Variable result = newVariable(a.getPlatformKind());
                append(new FPDivRemOp(FREM, result, load(a), load(b)));
                return result;
            }
            case Double: {
                Variable result = newVariable(a.getPlatformKind());
                append(new FPDivRemOp(DREM, result, load(a), load(b)));
                return result;
            }
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    @Override
    public Variable emitUDiv(Value a, Value b, DeoptimizingNode deopting) {
        LIRFrameState state = state(deopting);
        switch (a.getKind().getStackKind()) {
            case Int:
                emitDivRem(IUDIV, a, b, state);
                return emitMove(RAX_I);
            case Long:
                emitDivRem(LUDIV, a, b, state);
                return emitMove(RAX_L);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    @Override
    public Variable emitURem(Value a, Value b, DeoptimizingNode deopting) {
        LIRFrameState state = state(deopting);
        switch (a.getKind().getStackKind()) {
            case Int:
                emitDivRem(IUREM, a, b, state);
                return emitMove(RDX_I);
            case Long:
                emitDivRem(LUREM, a, b, state);
                return emitMove(RDX_L);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    @Override
    public Variable emitAnd(Value a, Value b) {
        switch (a.getKind().getStackKind()) {
            case Int:
                return emitBinary(IAND, true, a, b);
            case Long:
                return emitBinary(LAND, true, a, b);
            default:
                throw GraalInternalError.shouldNotReachHere();
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
                throw GraalInternalError.shouldNotReachHere();
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

    private Variable emitShift(AMD64Arithmetic op, Value a, Value b) {
        Variable result = newVariable(a.getPlatformKind());
        AllocatableValue input = asAllocatable(a);
        if (isConstant(b)) {
            append(new BinaryRegConst(op, result, input, asConstant(b)));
        } else {
            emitMove(RCX_I, b);
            append(new BinaryRegReg(op, result, input, RCX_I));
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

    @Override
    public Variable emitConvert(ConvertNode.Op opcode, Value inputVal) {
        Variable input = load(inputVal);
        Variable result = newVariable(opcode.to);
        switch (opcode) {
            case I2L:
                append(new Unary2Op(I2L, result, input));
                break;
            case L2I:
                append(new Unary1Op(L2I, result, input));
                break;
            case I2B:
                append(new Unary2Op(I2B, result, input));
                break;
            case I2C:
                append(new Unary1Op(I2C, result, input));
                break;
            case I2S:
                append(new Unary2Op(I2S, result, input));
                break;
            case F2D:
                append(new Unary2Op(F2D, result, input));
                break;
            case D2F:
                append(new Unary2Op(D2F, result, input));
                break;
            case I2F:
                append(new Unary2Op(I2F, result, input));
                break;
            case I2D:
                append(new Unary2Op(I2D, result, input));
                break;
            case F2I:
                append(new Unary2Op(F2I, result, input));
                break;
            case D2I:
                append(new Unary2Op(D2I, result, input));
                break;
            case L2F:
                append(new Unary2Op(L2F, result, input));
                break;
            case L2D:
                append(new Unary2Op(L2D, result, input));
                break;
            case F2L:
                append(new Unary2Op(F2L, result, input));
                break;
            case D2L:
                append(new Unary2Op(D2L, result, input));
                break;
            case MOV_I2F:
                append(new Unary2Op(MOV_I2F, result, input));
                break;
            case MOV_L2D:
                append(new Unary2Op(MOV_L2D, result, input));
                break;
            case MOV_F2I:
                append(new Unary2Op(MOV_F2I, result, input));
                break;
            case MOV_D2L:
                append(new Unary2Op(MOV_D2L, result, input));
                break;
            case UNSIGNED_I2L:
                // Instructions that move or generate 32-bit register values also set the upper 32
                // bits of the register to zero.
                // Consequently, there is no need for a special zero-extension move.
                emitMove(result, input);
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public void emitMembar(int barriers) {
        int necessaryBarriers = target.arch.requiredBarriers(barriers);
        if (target.isMP && necessaryBarriers != 0) {
            append(new MembarOp(necessaryBarriers));
        }
    }

    @Override
    protected void emitForeignCall(ForeignCallLinkage linkage, Value result, Value[] arguments, Value[] temps, LIRFrameState info) {
        long maxOffset = linkage.getMaxCallTargetOffset();
        if (maxOffset != (int) maxOffset) {
            append(new AMD64Call.DirectFarForeignCallOp(this, linkage, result, arguments, temps, info));
        } else {
            append(new AMD64Call.DirectNearForeignCallOp(linkage, result, arguments, temps, info));
        }
    }

    @Override
    public void emitBitCount(Variable result, Value value) {
        if (value.getKind().getStackKind() == Kind.Int) {
            append(new AMD64BitManipulationOp(IPOPCNT, result, asAllocatable(value)));
        } else {
            append(new AMD64BitManipulationOp(LPOPCNT, result, asAllocatable(value)));
        }
    }

    @Override
    public void emitBitScanForward(Variable result, Value value) {
        append(new AMD64BitManipulationOp(BSF, result, asAllocatable(value)));
    }

    @Override
    public void emitBitScanReverse(Variable result, Value value) {
        if (value.getKind().getStackKind() == Kind.Int) {
            append(new AMD64BitManipulationOp(IBSR, result, asAllocatable(value)));
        } else {
            append(new AMD64BitManipulationOp(LBSR, result, asAllocatable(value)));
        }
    }

    @Override
    public Value emitMathAbs(Value input) {
        Variable result = newVariable(input.getPlatformKind());
        append(new BinaryRegConst(DAND, result, asAllocatable(input), Constant.forDouble(Double.longBitsToDouble(0x7FFFFFFFFFFFFFFFL))));
        return result;
    }

    @Override
    public Value emitMathSqrt(Value input) {
        Variable result = newVariable(input.getPlatformKind());
        append(new Unary2Op(SQRT, result, asAllocatable(input)));
        return result;
    }

    @Override
    public Value emitMathLog(Value input, boolean base10) {
        Variable result = newVariable(input.getPlatformKind());
        append(new AMD64MathIntrinsicOp(base10 ? LOG10 : LOG, result, asAllocatable(input)));
        return result;
    }

    @Override
    public Value emitMathCos(Value input) {
        Variable result = newVariable(input.getPlatformKind());
        append(new AMD64MathIntrinsicOp(COS, result, asAllocatable(input)));
        return result;
    }

    @Override
    public Value emitMathSin(Value input) {
        Variable result = newVariable(input.getPlatformKind());
        append(new AMD64MathIntrinsicOp(SIN, result, asAllocatable(input)));
        return result;
    }

    @Override
    public Value emitMathTan(Value input) {
        Variable result = newVariable(input.getPlatformKind());
        append(new AMD64MathIntrinsicOp(TAN, result, asAllocatable(input)));
        return result;
    }

    @Override
    public void emitByteSwap(Variable result, Value input) {
        append(new AMD64ByteSwapOp(result, input));
    }

    @Override
    protected void emitReturn(Value input) {
        append(new ReturnOp(input));
    }

    @Override
    protected void emitSequentialSwitch(Constant[] keyConstants, LabelRef[] keyTargets, LabelRef defaultTarget, Value key) {
        // Making a copy of the switch value is necessary because jump table destroys the input
        // value
        if (key.getKind() == Kind.Int || key.getKind() == Kind.Long) {
            append(new SequentialSwitchOp(keyConstants, keyTargets, defaultTarget, key, Value.ILLEGAL));
        } else {
            assert key.getKind() == Kind.Object : key.getKind();
            append(new SequentialSwitchOp(keyConstants, keyTargets, defaultTarget, key, newVariable(Kind.Object)));
        }
    }

    @Override
    protected void emitSwitchRanges(int[] lowKeys, int[] highKeys, LabelRef[] targets, LabelRef defaultTarget, Value key) {
        append(new SwitchRangesOp(lowKeys, highKeys, targets, defaultTarget, key));
    }

    @Override
    protected void emitTableSwitch(int lowKey, LabelRef defaultTarget, LabelRef[] targets, Value key) {
        // Making a copy of the switch value is necessary because jump table destroys the input
        // value
        Variable tmp = emitMove(key);
        append(new TableSwitchOp(lowKey, defaultTarget, targets, tmp, newVariable(target.wordKind)));
    }

    @Override
    public void visitBreakpointNode(BreakpointNode node) {
        JavaType[] sig = new JavaType[node.arguments().size()];
        for (int i = 0; i < sig.length; i++) {
            sig[i] = node.arguments().get(i).stamp().javaType(metaAccess);
        }

        Value[] parameters = visitInvokeArguments(frameMap.registerConfig.getCallingConvention(CallingConvention.Type.JavaCall, null, sig, target(), false), node.arguments());
        append(new AMD64BreakpointOp(parameters));
    }

    @Override
    public void visitInfopointNode(InfopointNode i) {
        append(new InfopointOp(stateFor(i.getState()), i.reason));
    }
}
