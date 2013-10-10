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
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.StandardOp.JumpOp;
import com.oracle.graal.lir.hsail.*;
import com.oracle.graal.lir.hsail.HSAILArithmetic.Op1Stack;
import com.oracle.graal.lir.hsail.HSAILArithmetic.Op2Reg;
import com.oracle.graal.lir.hsail.HSAILArithmetic.Op2Stack;
import com.oracle.graal.lir.hsail.HSAILArithmetic.ShiftOp;
import com.oracle.graal.lir.hsail.HSAILControlFlow.CompareBranchOp;
import com.oracle.graal.lir.hsail.HSAILControlFlow.CondMoveOp;
import com.oracle.graal.lir.hsail.HSAILControlFlow.FloatCompareBranchOp;
import com.oracle.graal.lir.hsail.HSAILControlFlow.FloatCondMoveOp;
import com.oracle.graal.lir.hsail.HSAILControlFlow.ForeignCall1ArgOp;
import com.oracle.graal.lir.hsail.HSAILControlFlow.ForeignCallNoArgOp;
import com.oracle.graal.lir.hsail.HSAILControlFlow.ReturnOp;
import com.oracle.graal.lir.hsail.HSAILMove.LeaOp;
import com.oracle.graal.lir.hsail.HSAILMove.LoadCompressedPointer;
import com.oracle.graal.lir.hsail.HSAILMove.LoadOp;
import com.oracle.graal.lir.hsail.HSAILMove.MoveFromRegOp;
import com.oracle.graal.lir.hsail.HSAILMove.MoveToRegOp;
import com.oracle.graal.lir.hsail.HSAILMove.StoreCompressedPointer;
import com.oracle.graal.lir.hsail.HSAILMove.StoreOp;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.java.*;

/**
 * This class implements the HSAIL specific portion of the LIR generator.
 */
public class HSAILLIRGenerator extends LIRGenerator {

    private HotSpotRuntime runtime() {
        return (HotSpotRuntime) codeCache;
    }

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

    public HSAILLIRGenerator(StructuredGraph graph, MetaAccessProvider metaAccess, CodeCacheProvider codeCache, TargetDescription target, FrameMap frameMap, CallingConvention cc, LIR lir) {
        super(graph, metaAccess, codeCache, target, frameMap, cc, lir);
        lir.spillMoveFactory = new HSAILSpillMoveFactory();
    }

    @Override
    public boolean canStoreConstant(Constant c) {
        // Operand b must be in the .reg state space.
        return false;
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
                convertedIndex = this.emitConvert(ConvertNode.Op.I2L, index);
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

    private static boolean isCompressCandidate(DeoptimizingNode access) {
        return access != null && ((HeapAccess) access).isCompressible();
    }

    @Override
    public Variable emitLoad(Kind kind, Value address, DeoptimizingNode access) {
        HSAILAddressValue loadAddress = asAddressValue(address);
        Variable result = newVariable(kind);
        LIRFrameState state = access != null ? state(access) : null;
        assert access == null || access instanceof HeapAccess;
        if (runtime().config.useCompressedOops && isCompressCandidate(access)) {
            Variable scratch = newVariable(Kind.Long);
            append(new LoadCompressedPointer(kind, result, scratch, loadAddress, state, runtime().config.narrowOopBase, runtime().config.narrowOopShift, runtime().config.logMinObjAlignment));
        } else {
            append(new LoadOp(kind, result, loadAddress, state));
        }
        return result;
    }

    @Override
    public void emitStore(Kind kind, Value address, Value inputVal, DeoptimizingNode access) {
        HSAILAddressValue storeAddress = asAddressValue(address);
        LIRFrameState state = access != null ? state(access) : null;
        Variable input = load(inputVal);
        if (runtime().config.useCompressedOops && isCompressCandidate(access)) {
            Variable scratch = newVariable(Kind.Long);
            append(new StoreCompressedPointer(kind, storeAddress, input, scratch, state, runtime().config.narrowOopBase, runtime().config.narrowOopShift, runtime().config.logMinObjAlignment));
        } else {
            append(new StoreOp(kind, storeAddress, input, state));
        }
    }

    @Override
    public Variable emitAddress(StackSlot address) {
        throw new InternalError("NYI");
    }

    @Override
    public void emitJump(LabelRef label) {
        append(new JumpOp(label));
    }

    private static HSAILCompare mapKindToCompareOp(Kind kind) {
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
    public void emitCompareBranch(Value left, Value right, Condition cond, boolean unorderedIsTrue, LabelRef label) {
        // We don't have top worry about mirroring the condition on HSAIL.
        Condition finalCondition = cond;
        Variable result = newVariable(left.getKind());
        Kind kind = left.getKind().getStackKind();
        switch (kind) {
            case Int:
            case Long:
            case Object:
                append(new CompareBranchOp(mapKindToCompareOp(kind), finalCondition, left, right, result, result, label));
                break;
            case Float:
            case Double:
                append(new FloatCompareBranchOp(mapKindToCompareOp(kind), finalCondition, left, right, result, result, label, unorderedIsTrue));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere("" + left.getKind());
        }
    }

    @Override
    public void emitOverflowCheckBranch(LabelRef label, boolean negated) {
        throw new InternalError("NYI");
    }

    @Override
    public void emitIntegerTestBranch(Value left, Value right, boolean negated, LabelRef label) {
        throw new InternalError("NYI");
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
        throw new InternalError("NYI");
    }

    @Override
    public Variable emitNegate(Value input) {
        Variable result = newVariable(input.getKind());
        switch (input.getKind()) {
            case Int:
                append(new Op1Stack(INEG, result, input));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        return result;

    }

    @Override
    public Variable emitNot(Value input) {
        Variable result = newVariable(input.getKind());
        switch (input.getKind()) {
            case Int:
                append(new Op1Stack(INOT, result, input));
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
        throw new InternalError("NYI");
    }

    @Override
    public Variable emitURem(Value a, Value b, DeoptimizingNode deopting) {
        throw new InternalError("NYI");
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
        throw new InternalError("NYI");
    }

    @Override
    public Variable emitXor(Value a, Value b) {
        throw new InternalError("NYI");
    }

    @Override
    public Variable emitShl(Value a, Value b) {
        Variable result = newVariable(a.getKind());
        switch (a.getKind()) {
            case Int:
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

    @Override
    public Variable emitShr(Value a, Value b) {
        throw new InternalError("NYI");
    }

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
    public Variable emitConvert(ConvertNode.Op opcode, Value inputVal) {
        Variable input = load(inputVal);
        Variable result = newVariable(opcode.to);
        switch (opcode) {
            case I2F:
                append(new Op1Stack(I2F, result, input));
                break;
            case I2L:
                append(new Op1Stack(I2L, result, input));
                break;
            case I2D:
                append(new Op1Stack(I2D, result, input));
                break;
            case D2I:
                append(new Op1Stack(D2I, result, input));
                break;
            case L2I:
                append(new Op1Stack(L2I, result, input));
                break;
            case F2D:
                append(new Op1Stack(F2D, result, input));
                break;
            case D2F:
                append(new Op1Stack(D2F, result, input));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public void emitDeoptimize(Value actionAndReason, DeoptimizingNode deopting) {
        append(new ReturnOp(Value.ILLEGAL));
    }

    @Override
    public void emitMembar(int barriers) {
        throw new InternalError("NYI");
    }

    @Override
    protected void emitDirectCall(DirectCallTargetNode callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState callState) {
        throw new InternalError("NYI");
    }

    @Override
    protected void emitIndirectCall(IndirectCallTargetNode callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState callState) {
        throw new InternalError("NYI");
    }

    @Override
    protected void emitForeignCall(ForeignCallLinkage linkage, Value result, Value[] arguments, Value[] temps, LIRFrameState info) {
        String callName = linkage.getDescriptor().getName();
        if (callName.equals("createOutOfBoundsException") || callName.equals("createNullPointerException")) {
            // hack Alert !!
            switch (arguments.length) {
                case 0:
                    append(new ForeignCallNoArgOp(callName, result));
                    break;
                case 1:
                    append(new ForeignCall1ArgOp(callName, result, arguments[0]));
                    break;
                default:
                    throw new InternalError("NYI emitForeignCall");
            }

        } else {
            throw new InternalError("NYI emitForeignCall");
        }
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
        throw new InternalError("NYI");
    }

    @Override
    public void emitBitScanReverse(Variable result, Value value) {
        throw new InternalError("NYI");
    }

    @Override
    public Value emitMathAbs(Value input) {
        throw new InternalError("NYI");
    }

    @Override
    public Value emitMathSqrt(Value input) {
        Variable result = newVariable(input.getPlatformKind());
        append(new Op1Stack(SQRT, result, input));
        return result;
    }

    @Override
    public Value emitMathLog(Value input, boolean base10) {
        throw new InternalError("NYI");
    }

    @Override
    public Value emitMathCos(Value input) {
        throw new InternalError("NYI");
    }

    @Override
    public Value emitMathSin(Value input) {
        throw new InternalError("NYI");
    }

    @Override
    public Value emitMathTan(Value input) {
        throw new InternalError("NYI");
    }

    @Override
    public void emitByteSwap(Variable result, Value input) {
        throw new InternalError("NYI");
    }

    @Override
    protected void emitReturn(Value input) {
        append(new ReturnOp(input));
    }

    @Override
    protected void emitSequentialSwitch(Constant[] keyConstants, LabelRef[] keyTargets, LabelRef defaultTarget, Value key) {
        throw new InternalError("NYI");
    }

    @Override
    protected void emitSwitchRanges(int[] lowKeys, int[] highKeys, LabelRef[] targets, LabelRef defaultTarget, Value key) {
        throw new InternalError("NYI");
    }

    @Override
    protected void emitTableSwitch(int lowKey, LabelRef defaultTarget, LabelRef[] targets, Value key) {
        throw new InternalError("NYI");
    }

    @Override
    public void visitCompareAndSwap(LoweredCompareAndSwapNode node, Value address) {
        throw new InternalError("NYI");
    }

    @Override
    public void visitBreakpointNode(BreakpointNode node) {
        throw new InternalError("NYI");
    }

    @Override
    public void visitSafepointNode(SafepointNode i) {
        Debug.log("visitSafePointNode unimplemented");
    }

    @Override
    public void emitUnwind(Value operand) {
        throw new InternalError("NYI");
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
        throw new InternalError("NYI");
    }
}
