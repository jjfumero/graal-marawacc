/*
 * Copyright (c) 2009, 2015, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.asm.sparc.SPARCAssembler.CC.Fcc0;
import static com.oracle.graal.asm.sparc.SPARCAssembler.Op3s.Subcc;
import static com.oracle.graal.asm.sparc.SPARCAssembler.Opfs.Fcmpd;
import static com.oracle.graal.asm.sparc.SPARCAssembler.Opfs.Fcmps;
import static com.oracle.graal.lir.LIRValueUtil.asConstantValue;
import static com.oracle.graal.lir.LIRValueUtil.asJavaConstant;
import static com.oracle.graal.lir.LIRValueUtil.isJavaConstant;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.B2I;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.B2L;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.D2F;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.D2I;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.D2L;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.DADD;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.DDIV;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.DMUL;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.DNEG;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.DSUB;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.F2D;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.F2I;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.F2L;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.FADD;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.FDIV;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.FMUL;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.FNEG;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.FSUB;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.I2D;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.I2F;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.I2L;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.IADD;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.IADDCC;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.IAND;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.IDIV;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.IMUL;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.IMULCC;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.INEG;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.INOT;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.IOR;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.IREM;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.ISHL;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.ISHR;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.ISUB;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.ISUBCC;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.IUMUL;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.IUREM;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.IUSHR;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.IXOR;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.L2D;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.L2F;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.L2I;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.LADD;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.LADDCC;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.LAND;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.LDIV;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.LMUL;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.LNEG;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.LNOT;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.LOR;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.LREM;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.LSHL;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.LSHR;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.LSUB;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.LSUBCC;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.LUDIV;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.LUMUL;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.LUREM;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.LUSHR;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.LXOR;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.S2I;
import static com.oracle.graal.lir.sparc.SPARCArithmetic.S2L;
import static com.oracle.graal.lir.sparc.SPARCBitManipulationOp.IntrinsicOpcode.BSF;
import static com.oracle.graal.lir.sparc.SPARCBitManipulationOp.IntrinsicOpcode.IBSR;
import static com.oracle.graal.lir.sparc.SPARCBitManipulationOp.IntrinsicOpcode.IPOPCNT;
import static com.oracle.graal.lir.sparc.SPARCBitManipulationOp.IntrinsicOpcode.LBSR;
import static com.oracle.graal.lir.sparc.SPARCBitManipulationOp.IntrinsicOpcode.LPOPCNT;
import static com.oracle.graal.lir.sparc.SPARCMathIntrinsicOp.IntrinsicOpcode.ABS;
import static com.oracle.graal.lir.sparc.SPARCMathIntrinsicOp.IntrinsicOpcode.SQRT;
import static jdk.internal.jvmci.code.ValueUtil.isStackSlotValue;
import static jdk.internal.jvmci.meta.JavaKind.Char;
import jdk.internal.jvmci.code.CallingConvention;
import jdk.internal.jvmci.code.CodeUtil;
import jdk.internal.jvmci.code.StackSlotValue;
import jdk.internal.jvmci.common.JVMCIError;
import jdk.internal.jvmci.meta.AllocatableValue;
import jdk.internal.jvmci.meta.Constant;
import jdk.internal.jvmci.meta.JavaConstant;
import jdk.internal.jvmci.meta.JavaKind;
import jdk.internal.jvmci.meta.LIRKind;
import jdk.internal.jvmci.meta.PlatformKind;
import jdk.internal.jvmci.meta.Value;
import jdk.internal.jvmci.sparc.SPARC;
import jdk.internal.jvmci.sparc.SPARC.CPUFeature;

import com.oracle.graal.asm.sparc.SPARCAssembler;
import com.oracle.graal.asm.sparc.SPARCAssembler.CC;
import com.oracle.graal.asm.sparc.SPARCAssembler.ConditionFlag;
import com.oracle.graal.asm.sparc.SPARCAssembler.Op3s;
import com.oracle.graal.asm.sparc.SPARCAssembler.Opfs;
import com.oracle.graal.compiler.common.calc.Condition;
import com.oracle.graal.compiler.common.calc.FloatConvert;
import com.oracle.graal.compiler.common.spi.ForeignCallLinkage;
import com.oracle.graal.compiler.common.spi.LIRKindTool;
import com.oracle.graal.lir.ConstantValue;
import com.oracle.graal.lir.LIR;
import com.oracle.graal.lir.LIRFrameState;
import com.oracle.graal.lir.LIRInstruction;
import com.oracle.graal.lir.LIRValueUtil;
import com.oracle.graal.lir.LabelRef;
import com.oracle.graal.lir.StandardOp.NoOp;
import com.oracle.graal.lir.SwitchStrategy;
import com.oracle.graal.lir.Variable;
import com.oracle.graal.lir.gen.LIRGenerationResult;
import com.oracle.graal.lir.gen.LIRGenerator;
import com.oracle.graal.lir.gen.SpillMoveFactoryBase;
import com.oracle.graal.lir.sparc.SPARCAddressValue;
import com.oracle.graal.lir.sparc.SPARCArithmetic;
import com.oracle.graal.lir.sparc.SPARCArithmetic.BinaryRegConst;
import com.oracle.graal.lir.sparc.SPARCArithmetic.BinaryRegReg;
import com.oracle.graal.lir.sparc.SPARCArithmetic.MulHighOp;
import com.oracle.graal.lir.sparc.SPARCArithmetic.RemOp;
import com.oracle.graal.lir.sparc.SPARCArithmetic.SPARCLMulccOp;
import com.oracle.graal.lir.sparc.SPARCArithmetic.Unary2Op;
import com.oracle.graal.lir.sparc.SPARCArrayEqualsOp;
import com.oracle.graal.lir.sparc.SPARCBitManipulationOp;
import com.oracle.graal.lir.sparc.SPARCByteSwapOp;
import com.oracle.graal.lir.sparc.SPARCCall;
import com.oracle.graal.lir.sparc.SPARCControlFlow;
import com.oracle.graal.lir.sparc.SPARCControlFlow.BranchOp;
import com.oracle.graal.lir.sparc.SPARCControlFlow.CondMoveOp;
import com.oracle.graal.lir.sparc.SPARCControlFlow.ReturnOp;
import com.oracle.graal.lir.sparc.SPARCControlFlow.StrategySwitchOp;
import com.oracle.graal.lir.sparc.SPARCControlFlow.TableSwitchOp;
import com.oracle.graal.lir.sparc.SPARCFloatCompareOp;
import com.oracle.graal.lir.sparc.SPARCImmediateAddressValue;
import com.oracle.graal.lir.sparc.SPARCJumpOp;
import com.oracle.graal.lir.sparc.SPARCLoadConstantTableBaseOp;
import com.oracle.graal.lir.sparc.SPARCMathIntrinsicOp;
import com.oracle.graal.lir.sparc.SPARCMove;
import com.oracle.graal.lir.sparc.SPARCMove.LoadAddressOp;
import com.oracle.graal.lir.sparc.SPARCMove.LoadDataAddressOp;
import com.oracle.graal.lir.sparc.SPARCMove.LoadOp;
import com.oracle.graal.lir.sparc.SPARCMove.MembarOp;
import com.oracle.graal.lir.sparc.SPARCMove.Move;
import com.oracle.graal.lir.sparc.SPARCMove.MoveFpGp;
import com.oracle.graal.lir.sparc.SPARCMove.NullCheckOp;
import com.oracle.graal.lir.sparc.SPARCMove.StackLoadAddressOp;
import com.oracle.graal.lir.sparc.SPARCOP3Op;
import com.oracle.graal.phases.util.Providers;

/**
 * This class implements the SPARC specific portion of the LIR generator.
 */
public abstract class SPARCLIRGenerator extends LIRGenerator {

    private StackSlotValue tmpStackSlot;
    private SPARCSpillMoveFactory moveFactory;
    private Variable constantTableBase;
    private SPARCLoadConstantTableBaseOp loadConstantTableBaseOp;

    private class SPARCSpillMoveFactory extends SpillMoveFactoryBase {

        @Override
        protected LIRInstruction createMoveIntern(AllocatableValue result, Value input) {
            return SPARCLIRGenerator.this.createMove(result, input);
        }

        @Override
        protected LIRInstruction createStackMoveIntern(AllocatableValue result, AllocatableValue input) {
            return SPARCLIRGenerator.this.createStackMove(result, input);
        }

        @Override
        protected LIRInstruction createLoadIntern(AllocatableValue result, Constant input) {
            return SPARCLIRGenerator.this.createMoveConstant(result, input);
        }
    }

    public SPARCLIRGenerator(LIRKindTool lirKindTool, Providers providers, CallingConvention cc, LIRGenerationResult lirGenRes) {
        super(lirKindTool, providers, cc, lirGenRes);
    }

    public SpillMoveFactory getSpillMoveFactory() {
        if (moveFactory == null) {
            moveFactory = new SPARCSpillMoveFactory();
        }
        return moveFactory;
    }

    @Override
    public boolean canInlineConstant(JavaConstant c) {
        switch (c.getJavaKind()) {
            case Boolean:
            case Byte:
            case Char:
            case Short:
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

    protected LIRInstruction createMove(AllocatableValue dst, Value src) {
        boolean srcIsSlot = isStackSlotValue(src);
        boolean dstIsSlot = isStackSlotValue(dst);
        if (src instanceof ConstantValue) {
            return createMoveConstant(dst, ((ConstantValue) src).getConstant());
        } else if (src instanceof SPARCAddressValue) {
            return new LoadAddressOp(dst, (SPARCAddressValue) src);
        } else {
            assert src instanceof AllocatableValue;
            if (srcIsSlot && dstIsSlot) {
                throw JVMCIError.shouldNotReachHere(src.getClass() + " " + dst.getClass());
            } else {
                return new Move(dst, (AllocatableValue) src);
            }
        }
    }

    protected LIRInstruction createMoveConstant(AllocatableValue dst, Constant src) {
        if (src instanceof JavaConstant) {
            JavaConstant javaConstant = (JavaConstant) src;
            if (canInlineConstant(javaConstant)) {
                return new SPARCMove.LoadInlineConstant(javaConstant, dst);
            } else {
                return new SPARCMove.LoadConstantFromTable(javaConstant, getConstantTableBase(), dst);
            }
        } else {
            throw JVMCIError.shouldNotReachHere(src.getClass().toString());
        }
    }

    protected LIRInstruction createStackMove(AllocatableValue result, AllocatableValue input) {
        return new SPARCMove.Move(result, input);
    }

    @Override
    public void emitMove(AllocatableValue dst, Value src) {
        append(createMove(dst, src));
    }

    @Override
    public void emitMoveConstant(AllocatableValue dst, Constant src) {
        append(createMoveConstant(dst, src));
    }

    @Override
    public void emitData(AllocatableValue dst, byte[] data) {
        append(new LoadDataAddressOp(dst, data));
    }

    protected SPARCAddressValue asAddressValue(Value address) {
        if (address instanceof SPARCAddressValue) {
            return (SPARCAddressValue) address;
        } else {
            LIRKind kind = address.getLIRKind();
            if (address instanceof JavaConstant) {
                long displacement = ((JavaConstant) address).asLong();
                if (SPARCAssembler.isSimm13(displacement)) {
                    return new SPARCImmediateAddressValue(kind, SPARC.g0.asValue(kind), (int) displacement);
                }
            }
            return new SPARCImmediateAddressValue(kind, asAllocatable(address), 0);
        }
    }

    @Override
    public Variable emitAddress(StackSlotValue address) {
        Variable result = newVariable(LIRKind.value(target().arch.getWordKind()));
        append(new StackLoadAddressOp(result, address));
        return result;
    }

    @Override
    public void emitReturn(JavaKind javaKind, Value input) {
        AllocatableValue operand = Value.ILLEGAL;
        if (input != null) {
            operand = resultOperandFor(javaKind, input.getLIRKind());
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
        Value left;
        Value right;
        Condition actualCondition;
        if (isJavaConstant(x)) {
            left = load(y);
            right = loadNonConst(x);
            actualCondition = cond.mirror();
        } else {
            left = load(x);
            right = loadNonConst(y);
            actualCondition = cond;
        }
        JavaKind actualCmpKind = (JavaKind) cmpKind;
        if (actualCmpKind.isNumericInteger()) {
            actualCmpKind = toSPARCCmpKind(actualCmpKind);
            append(new SPARCControlFlow.CompareBranchOp(canonicalizeForCompare(left, cmpKind, actualCmpKind), canonicalizeForCompare(right, cmpKind, actualCmpKind), actualCondition, trueDestination,
                            falseDestination, actualCmpKind, unorderedIsTrue, trueDestinationProbability));
        } else if (actualCmpKind.isNumericFloat()) {
            emitFloatCompare(cmpKind, x, y, Fcc0);
            ConditionFlag cf = SPARCControlFlow.fromCondition(Fcc0, cond, unorderedIsTrue);
            append(new SPARCControlFlow.BranchOp(cf, trueDestination, falseDestination, actualCmpKind, trueDestinationProbability));
        }
    }

    private static JavaKind toSPARCCmpKind(JavaKind actualCmpKind) {
        // TODO: Change to PlatformKind
        assert actualCmpKind.isNumericInteger();
        if (actualCmpKind.getByteCount() <= 4) {
            return JavaKind.Int;
        } else {
            return JavaKind.Long;
        }
    }

    private Value canonicalizeForCompare(Value v, PlatformKind from, PlatformKind to) {
        if (LIRValueUtil.isJavaConstant(v)) {
            JavaConstant c = asJavaConstant(v);
            return new ConstantValue(v.getLIRKind().changeType(to), c);
        } else {
            int fromBytes = from.getSizeInBytes() * 8;
            int toBytes = to.getSizeInBytes() * 8;
            assert from.getSizeInBytes() <= v.getPlatformKind().getSizeInBytes();
            if (from == to && !v.getPlatformKind().equals(JavaKind.Char)) {
                return v;
            } else {
                return emitSignExtend(v, fromBytes, toBytes);
            }
        }
    }

    @Override
    public void emitOverflowCheckBranch(LabelRef overflow, LabelRef noOverflow, LIRKind cmpLIRKind, double overflowProbability) {
        JavaKind cmpKind = (JavaKind) cmpLIRKind.getPlatformKind();
        append(new BranchOp(ConditionFlag.OverflowSet, overflow, noOverflow, cmpKind, overflowProbability));
    }

    @Override
    public void emitIntegerTestBranch(Value left, Value right, LabelRef trueDestination, LabelRef falseDestination, double trueDestinationProbability) {
        emitIntegerTest(left, right);
        append(new BranchOp(ConditionFlag.Equal, trueDestination, falseDestination, ((JavaKind) left.getPlatformKind()).getStackKind(), trueDestinationProbability));
    }

    private void emitIntegerTest(Value a, Value b) {
        assert ((JavaKind) a.getPlatformKind()).isNumericInteger();
        if (LIRValueUtil.isVariable(b)) {
            append(new SPARCOP3Op(Op3s.Andcc, load(b), loadNonConst(a)));
        } else {
            append(new SPARCOP3Op(Op3s.Andcc, load(a), loadNonConst(b)));
        }
    }

    private Value loadSimm11(Value value) {
        if (isJavaConstant(value)) {
            JavaConstant c = asJavaConstant(value);
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
        // TODO: (sa) Review this loadSimm11 if it is really necessary
        switch ((JavaKind) left.getLIRKind().getPlatformKind()) {
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
                throw JVMCIError.shouldNotReachHere();
        }
        Variable result = newVariable(trueValue.getLIRKind());
        ConditionFlag finalCondition = SPARCControlFlow.fromCondition(conditionFlags, mirrored ? cond.mirror() : cond, unorderedIsTrue);
        append(new CondMoveOp(result, conditionFlags, finalCondition, actualTrueValue, actualFalseValue));
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
        boolean mirrored;
        JavaKind cmpJavaKind = (JavaKind) cmpKind;
        if (cmpJavaKind.isNumericInteger()) { // Integer case
            mirrored = emitIntegerCompare(cmpJavaKind, a, b);
        } else if (cmpJavaKind.isNumericFloat()) { // Float case
            mirrored = false; // No mirroring done on floats
            emitFloatCompare(cmpJavaKind, a, b, Fcc0);
        } else {
            throw JVMCIError.shouldNotReachHere();
        }
        return mirrored;
    }

    private boolean emitIntegerCompare(JavaKind cmpJavaKind, Value a, Value b) {
        boolean mirrored;
        Value left;
        Value right;
        if (LIRValueUtil.isVariable(b)) {
            left = load(b);
            right = loadNonConst(a);
            mirrored = true;
        } else {
            left = load(a);
            right = loadNonConst(b);
            mirrored = false;
        }
        int compareBits = cmpJavaKind.getBitCount();
        // SPARC compares 32 or 64 bits
        if (compareBits < JavaKind.Int.getBitCount()) {
            if (cmpJavaKind.equals(Char)) { // Char needs zero extend
                left = emitZeroExtend(left, compareBits, 32);
                right = emitZeroExtend(right, compareBits, 32);
            } else {
                left = emitSignExtend(left, compareBits, 32);
                right = emitSignExtend(right, compareBits, 32);
            }
        }
        append(new SPARCOP3Op(Subcc, left, right));
        return mirrored;
    }

    private void emitFloatCompare(PlatformKind cmpJavaKind, Value a, Value b, CC cc) {
        Opfs floatCompareOpcode;
        switch ((JavaKind) cmpJavaKind) {
            case Double:
                floatCompareOpcode = Fcmpd;
                break;
            case Float:
                floatCompareOpcode = Fcmps;
                break;
            default:
                throw JVMCIError.shouldNotReachHere();
        }
        append(new SPARCFloatCompareOp(floatCompareOpcode, cc, load(a), load(b)));
    }

    @Override
    public Variable emitIntegerTestMove(Value left, Value right, Value trueValue, Value falseValue) {
        emitIntegerTest(left, right);
        Variable result = newVariable(trueValue.getLIRKind());
        JavaKind kind = ((JavaKind) left.getPlatformKind()).getStackKind();
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
                throw JVMCIError.shouldNotReachHere();
        }
        ConditionFlag flag = SPARCControlFlow.fromCondition(conditionCode, Condition.EQ, false);
        // TODO: (sa) Review this loadSimm11 if it is really necessary
        append(new CondMoveOp(result, conditionCode, flag, loadSimm11(trueValue), loadSimm11(falseValue)));
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
        AllocatableValue scratchValue = newVariable(key.getLIRKind());
        AllocatableValue base = AllocatableValue.ILLEGAL;
        for (Constant c : strategy.getKeyConstants()) {
            if (!(c instanceof JavaConstant) || !canInlineConstant((JavaConstant) c)) {
                base = getConstantTableBase();
                break;
            }
        }
        append(createStrategySwitchOp(base, strategy, keyTargets, defaultTarget, key, scratchValue));
    }

    protected StrategySwitchOp createStrategySwitchOp(AllocatableValue base, SwitchStrategy strategy, LabelRef[] keyTargets, LabelRef defaultTarget, Variable key, AllocatableValue scratchValue) {
        return new StrategySwitchOp(base, strategy, keyTargets, defaultTarget, key, scratchValue);
    }

    @Override
    protected void emitTableSwitch(int lowKey, LabelRef defaultTarget, LabelRef[] targets, Value key) {
        // Making a copy of the switch value is necessary because jump table destroys the input
        // value
        Variable tmp = newVariable(key.getLIRKind());
        emitMove(tmp, key);
        append(new TableSwitchOp(lowKey, defaultTarget, targets, tmp, newVariable(LIRKind.value(target().arch.getWordKind()))));
    }

    @Override
    public Variable emitBitCount(Value operand) {
        Variable result = newVariable(LIRKind.combine(operand).changeType(JavaKind.Int));
        if (operand.getPlatformKind() == JavaKind.Long) {
            append(new SPARCBitManipulationOp(LPOPCNT, result, asAllocatable(operand), this));
        } else {
            append(new SPARCBitManipulationOp(IPOPCNT, result, asAllocatable(operand), this));
        }
        return result;
    }

    @Override
    public Variable emitBitScanForward(Value operand) {
        Variable result = newVariable(LIRKind.combine(operand).changeType(JavaKind.Int));
        append(new SPARCBitManipulationOp(BSF, result, asAllocatable(operand), this));
        return result;
    }

    @Override
    public Variable emitBitScanReverse(Value operand) {
        Variable result = newVariable(LIRKind.combine(operand).changeType(JavaKind.Int));
        if (operand.getPlatformKind() == JavaKind.Long) {
            append(new SPARCBitManipulationOp(LBSR, result, asAllocatable(operand), this));
        } else {
            append(new SPARCBitManipulationOp(IBSR, result, asAllocatable(operand), this));
        }
        return result;
    }

    @Override
    public Value emitMathAbs(Value input) {
        Variable result = newVariable(LIRKind.combine(input));
        append(new SPARCMathIntrinsicOp(ABS, result, asAllocatable(input)));
        return result;
    }

    @Override
    public Value emitMathSqrt(Value input) {
        Variable result = newVariable(LIRKind.combine(input));
        append(new SPARCMathIntrinsicOp(SQRT, result, asAllocatable(input)));
        return result;
    }

    @Override
    public Variable emitByteSwap(Value input) {
        Variable result = newVariable(LIRKind.combine(input));
        append(new SPARCByteSwapOp(this, result, input));
        return result;
    }

    @Override
    public Variable emitArrayEquals(JavaKind kind, Value array1, Value array2, Value length) {
        Variable result = newVariable(LIRKind.value(JavaKind.Int));
        append(new SPARCArrayEqualsOp(this, kind, result, load(array1), load(array2), asAllocatable(length)));
        return result;
    }

    @Override
    public Value emitNegate(Value input) {
        switch (((JavaKind) input.getPlatformKind()).getStackKind()) {
            case Long:
                return emitUnary(LNEG, input);
            case Int:
                return emitUnary(INEG, input);
            case Float:
                return emitUnary(FNEG, input);
            case Double:
                return emitUnary(DNEG, input);
            default:
                throw JVMCIError.shouldNotReachHere();
        }
    }

    @Override
    public Value emitNot(Value input) {
        switch (((JavaKind) input.getPlatformKind()).getStackKind()) {
            case Int:
                return emitUnary(INOT, input);
            case Long:
                return emitUnary(LNOT, input);
            default:
                throw JVMCIError.shouldNotReachHere();
        }
    }

    private Variable emitUnary(SPARCArithmetic op, Value input) {
        Variable result = newVariable(LIRKind.combine(input));
        append(new Unary2Op(op, result, load(input)));
        return result;
    }

    private Variable emitBinary(LIRKind resultKind, SPARCArithmetic op, boolean commutative, Value a, Value b) {
        return emitBinary(resultKind, op, commutative, a, b, null);
    }

    private Variable emitBinary(LIRKind resultKind, SPARCArithmetic op, boolean commutative, Value a, Value b, LIRFrameState state) {
        if (isJavaConstant(b) && canInlineConstant(asJavaConstant(b))) {
            return emitBinaryConst(resultKind, op, load(a), asConstantValue(b), state);
        } else if (commutative && isJavaConstant(a) && canInlineConstant(asJavaConstant(a))) {
            return emitBinaryConst(resultKind, op, load(b), asConstantValue(a), state);
        } else {
            return emitBinaryVar(resultKind, op, load(a), load(b), state);
        }
    }

    private Variable emitBinaryConst(LIRKind resultKind, SPARCArithmetic op, AllocatableValue a, ConstantValue b, LIRFrameState state) {
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
                if (canInlineConstant(b.getJavaConstant())) {
                    Variable result = newVariable(resultKind);
                    append(new BinaryRegConst(op, result, a, b.getJavaConstant(), state));
                    return result;
                }
                break;
        }
        return emitBinaryVar(resultKind, op, a, asAllocatable(b), state);
    }

    private Variable emitBinaryVar(LIRKind resultKind, SPARCArithmetic op, AllocatableValue a, AllocatableValue b, LIRFrameState state) {
        Variable result = newVariable(resultKind);
        append(new BinaryRegReg(op, result, a, b, state));
        return result;
    }

    @Override
    protected boolean isNumericInteger(PlatformKind kind) {
        return ((JavaKind) kind).isNumericInteger();
    }

    @Override
    public Variable emitAdd(LIRKind resultKind, Value a, Value b, boolean setFlags) {
        switch (((JavaKind) a.getPlatformKind()).getStackKind()) {
            case Int:
                return emitBinary(resultKind, setFlags ? IADDCC : IADD, true, a, b);
            case Long:
                return emitBinary(resultKind, setFlags ? LADDCC : LADD, true, a, b);
            case Float:
                return emitBinary(resultKind, FADD, true, a, b);
            case Double:
                return emitBinary(resultKind, DADD, true, a, b);
            default:
                throw JVMCIError.shouldNotReachHere();
        }
    }

    @Override
    public Variable emitSub(LIRKind resultKind, Value a, Value b, boolean setFlags) {
        switch (((JavaKind) a.getPlatformKind()).getStackKind()) {
            case Int:
                return emitBinary(resultKind, setFlags ? ISUBCC : ISUB, false, a, b);
            case Long:
                return emitBinary(resultKind, setFlags ? LSUBCC : LSUB, false, a, b);
            case Float:
                return emitBinary(resultKind, FSUB, false, a, b);
            case Double:
                return emitBinary(resultKind, DSUB, false, a, b);
            default:
                throw JVMCIError.shouldNotReachHere("missing: " + a.getPlatformKind());
        }
    }

    @Override
    public Variable emitMul(Value a, Value b, boolean setFlags) {
        LIRKind resultKind = LIRKind.combine(a, b);
        switch (((JavaKind) a.getPlatformKind()).getStackKind()) {
            case Int:
                return emitBinary(resultKind, setFlags ? IMULCC : IMUL, true, a, b);
            case Long:
                if (setFlags) {
                    Variable result = newVariable(LIRKind.combine(a, b));
                    append(new SPARCLMulccOp(result, load(a), load(b), this));
                    return result;
                } else {
                    return emitBinary(resultKind, LMUL, true, a, b);
                }
            case Float:
                return emitBinary(resultKind, FMUL, true, a, b);
            case Double:
                return emitBinary(resultKind, DMUL, true, a, b);
            default:
                throw JVMCIError.shouldNotReachHere("missing: " + a.getPlatformKind());
        }
    }

    @Override
    public Value emitMulHigh(Value a, Value b) {
        switch (((JavaKind) a.getPlatformKind()).getStackKind()) {
            case Int:
                return emitMulHigh(IMUL, a, b);
            case Long:
                return emitMulHigh(LMUL, a, b);
            default:
                throw JVMCIError.shouldNotReachHere();
        }
    }

    @Override
    public Value emitUMulHigh(Value a, Value b) {
        switch (((JavaKind) a.getPlatformKind()).getStackKind()) {
            case Int:
                return emitMulHigh(IUMUL, a, b);
            case Long:
                return emitMulHigh(LUMUL, a, b);
            default:
                throw JVMCIError.shouldNotReachHere();
        }
    }

    private Value emitMulHigh(SPARCArithmetic opcode, Value a, Value b) {
        Variable result = newVariable(LIRKind.combine(a, b));
        MulHighOp mulHigh = new MulHighOp(opcode, load(a), load(b), result, newVariable(LIRKind.combine(a, b)));
        append(mulHigh);
        return result;
    }

    @Override
    public Value emitDiv(Value a, Value b, LIRFrameState state) {
        LIRKind resultKind = LIRKind.combine(a, b);
        switch (((JavaKind) a.getPlatformKind()).getStackKind()) {
            case Int:
                return emitBinary(resultKind, IDIV, false, a, b, state);
            case Long:
                return emitBinary(resultKind, LDIV, false, a, b, state);
            case Float:
                return emitBinary(resultKind, FDIV, false, a, b, state);
            case Double:
                return emitBinary(resultKind, DDIV, false, a, b, state);
            default:
                throw JVMCIError.shouldNotReachHere("missing: " + a.getPlatformKind());
        }
    }

    @Override
    public Value emitRem(Value a, Value b, LIRFrameState state) {
        Variable result = newVariable(LIRKind.combine(a, b));
        Variable q1; // Intermediate values
        Variable q2;
        Variable q3;
        Variable q4;
        switch (((JavaKind) a.getPlatformKind()).getStackKind()) {
            case Int:
                append(new RemOp(IREM, result, load(a), loadNonConst(b), state, this));
                break;
            case Long:
                append(new RemOp(LREM, result, load(a), loadNonConst(b), state, this));
                break;
            case Float:
                q1 = newVariable(LIRKind.value(JavaKind.Float));
                append(new BinaryRegReg(FDIV, q1, a, b, state));
                q2 = newVariable(LIRKind.value(JavaKind.Float));
                append(new Unary2Op(F2I, q2, q1));
                q3 = newVariable(LIRKind.value(JavaKind.Float));
                append(new Unary2Op(I2F, q3, q2));
                q4 = newVariable(LIRKind.value(JavaKind.Float));
                append(new BinaryRegReg(FMUL, q4, q3, b));
                append(new BinaryRegReg(FSUB, result, a, q4));
                break;
            case Double:
                q1 = newVariable(LIRKind.value(JavaKind.Double));
                append(new BinaryRegReg(DDIV, q1, a, b, state));
                q2 = newVariable(LIRKind.value(JavaKind.Double));
                append(new Unary2Op(D2L, q2, q1));
                q3 = newVariable(LIRKind.value(JavaKind.Double));
                append(new Unary2Op(L2D, q3, q2));
                q4 = newVariable(LIRKind.value(JavaKind.Double));
                append(new BinaryRegReg(DMUL, q4, q3, b));
                append(new BinaryRegReg(DSUB, result, a, q4));
                break;
            default:
                throw JVMCIError.shouldNotReachHere("missing: " + a.getPlatformKind());
        }
        return result;
    }

    @Override
    public Value emitURem(Value a, Value b, LIRFrameState state) {
        Variable result = newVariable(LIRKind.combine(a, b));
        switch (((JavaKind) a.getPlatformKind()).getStackKind()) {
            case Int:
                append(new RemOp(IUREM, result, load(a), load(b), state, this));
                break;
            case Long:
                append(new RemOp(LUREM, result, load(a), loadNonConst(b), state, this));
                break;
            default:
                throw JVMCIError.shouldNotReachHere();
        }
        return result;

    }

    @Override
    public Value emitUDiv(Value a, Value b, LIRFrameState state) {
        SPARCArithmetic op;
        Value actualA = a;
        Value actualB = b;
        switch (((JavaKind) a.getPlatformKind()).getStackKind()) {
            case Int:
                op = LUDIV;
                actualA = emitZeroExtend(actualA, 32, 64);
                actualB = emitZeroExtend(actualB, 32, 64);
                break;
            case Long:
                op = LUDIV;
                break;
            default:
                throw JVMCIError.shouldNotReachHere();
        }
        return emitBinary(LIRKind.combine(actualA, actualB), op, false, actualA, actualB, state);
    }

    @Override
    public Variable emitAnd(Value a, Value b) {
        LIRKind resultKind = LIRKind.combine(a, b);
        switch (((JavaKind) a.getPlatformKind()).getStackKind()) {
            case Int:
                return emitBinary(resultKind, IAND, true, a, b);
            case Long:
                return emitBinary(resultKind, LAND, true, a, b);

            default:
                throw JVMCIError.shouldNotReachHere("missing: " + a.getPlatformKind());
        }
    }

    @Override
    public Variable emitOr(Value a, Value b) {
        LIRKind resultKind = LIRKind.combine(a, b);
        switch (((JavaKind) a.getPlatformKind()).getStackKind()) {
            case Int:
                return emitBinary(resultKind, IOR, true, a, b);
            case Long:
                return emitBinary(resultKind, LOR, true, a, b);
            default:
                throw JVMCIError.shouldNotReachHere("missing: " + a.getPlatformKind());
        }
    }

    @Override
    public Variable emitXor(Value a, Value b) {
        LIRKind resultKind = LIRKind.combine(a, b);
        switch (((JavaKind) a.getPlatformKind()).getStackKind()) {
            case Int:
                return emitBinary(resultKind, IXOR, true, a, b);
            case Long:
                return emitBinary(resultKind, LXOR, true, a, b);
            default:
                throw JVMCIError.shouldNotReachHere();
        }
    }

    private Variable emitShift(SPARCArithmetic op, Value a, Value b) {
        Variable result = newVariable(LIRKind.combine(a, b).changeType(a.getPlatformKind()));
        if (isJavaConstant(b) && canInlineConstant(asJavaConstant(b))) {
            append(new BinaryRegConst(op, result, load(a), asJavaConstant(b), null));
        } else {
            append(new BinaryRegReg(op, result, load(a), load(b)));
        }
        return result;
    }

    @Override
    public Variable emitShl(Value a, Value b) {
        switch (((JavaKind) a.getPlatformKind()).getStackKind()) {
            case Int:
                return emitShift(ISHL, a, b);
            case Long:
                return emitShift(LSHL, a, b);
            default:
                throw JVMCIError.shouldNotReachHere();
        }
    }

    @Override
    public Variable emitShr(Value a, Value b) {
        switch (((JavaKind) a.getPlatformKind()).getStackKind()) {
            case Int:
                return emitShift(ISHR, a, b);
            case Long:
                return emitShift(LSHR, a, b);
            default:
                throw JVMCIError.shouldNotReachHere();
        }
    }

    @Override
    public Variable emitUShr(Value a, Value b) {
        switch (((JavaKind) a.getPlatformKind()).getStackKind()) {
            case Int:
                return emitShift(IUSHR, a, b);
            case Long:
                return emitShift(LUSHR, a, b);
            default:
                throw JVMCIError.shouldNotReachHere();
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
                return emitConvert2Op(LIRKind.combine(inputVal).changeType(JavaKind.Float), D2F, input);
            case F2D:
                return emitConvert2Op(LIRKind.combine(inputVal).changeType(JavaKind.Double), F2D, input);
            case I2F: {
                AllocatableValue intEncodedFloatReg = newVariable(LIRKind.combine(input).changeType(JavaKind.Float));
                moveBetweenFpGp(intEncodedFloatReg, input);
                AllocatableValue convertedFloatReg = newVariable(intEncodedFloatReg.getLIRKind());
                append(new Unary2Op(I2F, convertedFloatReg, intEncodedFloatReg));
                return convertedFloatReg;
            }
            case I2D: {
                // Unfortunately we must do int -> float -> double because fitod has float
                // and double encoding in one instruction
                AllocatableValue convertedFloatReg = newVariable(LIRKind.combine(input).changeType(JavaKind.Float));
                moveBetweenFpGp(convertedFloatReg, input);
                AllocatableValue convertedDoubleReg = newVariable(LIRKind.combine(input).changeType(JavaKind.Double));
                append(new Unary2Op(I2D, convertedDoubleReg, convertedFloatReg));
                return convertedDoubleReg;
            }
            case L2D: {
                AllocatableValue longEncodedDoubleReg = newVariable(LIRKind.combine(input).changeType(JavaKind.Double));
                moveBetweenFpGp(longEncodedDoubleReg, input);
                AllocatableValue convertedDoubleReg = newVariable(longEncodedDoubleReg.getLIRKind());
                append(new Unary2Op(L2D, convertedDoubleReg, longEncodedDoubleReg));
                return convertedDoubleReg;
            }
            case D2I: {
                AllocatableValue convertedFloatReg = emitConvert2Op(LIRKind.combine(input).changeType(JavaKind.Float), D2I, input);
                AllocatableValue convertedIntReg = newVariable(LIRKind.combine(convertedFloatReg).changeType(JavaKind.Int));
                moveBetweenFpGp(convertedIntReg, convertedFloatReg);
                return convertedIntReg;
            }
            case F2L: {
                AllocatableValue convertedDoubleReg = emitConvert2Op(LIRKind.combine(input).changeType(JavaKind.Double), F2L, input);
                AllocatableValue convertedLongReg = newVariable(LIRKind.combine(convertedDoubleReg).changeType(JavaKind.Long));
                moveBetweenFpGp(convertedLongReg, convertedDoubleReg);
                return convertedLongReg;
            }
            case F2I: {
                AllocatableValue convertedFloatReg = emitConvert2Op(LIRKind.combine(input).changeType(JavaKind.Float), F2I, input);
                AllocatableValue convertedIntReg = newVariable(LIRKind.combine(convertedFloatReg).changeType(JavaKind.Int));
                moveBetweenFpGp(convertedIntReg, convertedFloatReg);
                return convertedIntReg;
            }
            case D2L: {
                AllocatableValue convertedDoubleReg = emitConvert2Op(LIRKind.combine(input).changeType(JavaKind.Double), D2L, input);
                AllocatableValue convertedLongReg = newVariable(LIRKind.combine(convertedDoubleReg).changeType(JavaKind.Long));
                moveBetweenFpGp(convertedLongReg, convertedDoubleReg);
                return convertedLongReg;
            }
            case L2F: {
                // long -> double -> float see above
                AllocatableValue convertedDoubleReg = newVariable(LIRKind.combine(input).changeType(JavaKind.Double));
                moveBetweenFpGp(convertedDoubleReg, input);
                AllocatableValue convertedFloatReg = newVariable(LIRKind.combine(input).changeType(JavaKind.Float));
                append(new Unary2Op(L2F, convertedFloatReg, convertedDoubleReg));
                return convertedFloatReg;
            }
            default:
                throw JVMCIError.shouldNotReachHere();
        }
    }

    private void moveBetweenFpGp(AllocatableValue dst, AllocatableValue src) {
        AllocatableValue tempSlot;
        if (getArchitecture().getFeatures().contains(CPUFeature.VIS3)) {
            tempSlot = AllocatableValue.ILLEGAL;
        } else {
            tempSlot = getTempSlot(LIRKind.value(JavaKind.Long));
        }
        append(new MoveFpGp(dst, src, tempSlot));
    }

    protected StackSlotValue getTempSlot(LIRKind kind) {
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
        if (inputVal.getPlatformKind() == JavaKind.Long && bits <= 32) {
            return emitConvert2Op(LIRKind.combine(inputVal).changeType(JavaKind.Int), L2I, asAllocatable(inputVal));
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
                    return emitConvert2Op(LIRKind.combine(inputVal).changeType(JavaKind.Long), B2L, asAllocatable(inputVal));
                case 16:
                    return emitConvert2Op(LIRKind.combine(inputVal).changeType(JavaKind.Long), S2L, asAllocatable(inputVal));
                case 32:
                    return emitConvert2Op(LIRKind.combine(inputVal).changeType(JavaKind.Long), I2L, asAllocatable(inputVal));
                default:
                    throw JVMCIError.unimplemented("unsupported sign extension (" + fromBits + " bit -> " + toBits + " bit)");
            }
        } else {
            // sign extend to 32 bits (smaller values are internally represented as 32 bit values)
            switch (fromBits) {
                case 8:
                    return emitConvert2Op(LIRKind.combine(inputVal).changeType(JavaKind.Int), B2I, asAllocatable(inputVal));
                case 16:
                    return emitConvert2Op(LIRKind.combine(inputVal).changeType(JavaKind.Int), S2I, asAllocatable(inputVal));
                case 32:
                    return inputVal;
                default:
                    throw JVMCIError.unimplemented("unsupported sign extension (" + fromBits + " bit -> " + toBits + " bit)");
            }
        }
    }

    @Override
    public Value emitZeroExtend(Value inputVal, int fromBits, int toBits) {
        assert fromBits <= toBits && toBits <= 64;
        if (fromBits == toBits) {
            return inputVal;
        } else if (fromBits > 32) {
            assert inputVal.getPlatformKind() == JavaKind.Long;
            Variable result = newVariable(LIRKind.combine(inputVal).changeType(JavaKind.Long));
            long mask = CodeUtil.mask(fromBits);
            append(new BinaryRegConst(SPARCArithmetic.LAND, result, asAllocatable(inputVal), JavaConstant.forLong(mask), null));
            return result;
        } else {
            assert inputVal.getPlatformKind() == JavaKind.Int || inputVal.getPlatformKind() == JavaKind.Short || inputVal.getPlatformKind() == JavaKind.Byte ||
                            inputVal.getPlatformKind() == JavaKind.Char : inputVal.getPlatformKind();
            Variable result = newVariable(LIRKind.combine(inputVal).changeType(JavaKind.Int));
            long mask = CodeUtil.mask(fromBits);
            JavaConstant constant = JavaConstant.forInt((int) mask);
            if (fromBits == 32) {
                append(new BinaryRegConst(IUSHR, result, inputVal, JavaConstant.forInt(0)));
            } else if (canInlineConstant(constant)) {
                append(new BinaryRegConst(SPARCArithmetic.IAND, result, asAllocatable(inputVal), constant, null));
            } else {
                Variable maskVar = newVariable(LIRKind.combine(inputVal).changeType(JavaKind.Int));
                emitMoveConstant(maskVar, constant);
                append(new BinaryRegReg(IAND, result, maskVar, asAllocatable(inputVal)));
            }
            if (toBits > 32) {
                Variable longResult = newVariable(LIRKind.combine(inputVal).changeType(JavaKind.Long));
                emitMove(longResult, result);
                return longResult;
            } else {
                return result;
            }
        }
    }

    @Override
    public AllocatableValue emitReinterpret(LIRKind to, Value inputVal) {
        JavaKind from = (JavaKind) inputVal.getPlatformKind();
        AllocatableValue input = asAllocatable(inputVal);
        Variable result = newVariable(to);
        // These cases require a move between CPU and FPU registers:
        switch ((JavaKind) to.getPlatformKind()) {
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

    public Value emitSignExtendLoad(LIRKind kind, Value address, LIRFrameState state) {
        SPARCAddressValue loadAddress = asAddressValue(address);
        Variable result = newVariable(kind);
        append(new LoadOp(kind.getPlatformKind(), result, loadAddress, state, true));
        return result;
    }

    public void emitNullCheck(Value address, LIRFrameState state) {
        PlatformKind kind = address.getPlatformKind();
        assert kind == JavaKind.Object || kind == JavaKind.Long : address + " - " + kind + " not an object!";
        append(new NullCheckOp(asAddressValue(address), state));
    }

    public void emitLoadConstantTableBase() {
        constantTableBase = newVariable(LIRKind.value(JavaKind.Long));
        int nextPosition = getResult().getLIR().getLIRforBlock(getCurrentBlock()).size();
        NoOp placeHolder = append(new NoOp(getCurrentBlock(), nextPosition));
        loadConstantTableBaseOp = new SPARCLoadConstantTableBaseOp(constantTableBase, placeHolder);
    }

    boolean useConstantTableBase = false;

    protected Variable getConstantTableBase() {
        useConstantTableBase = true;
        return constantTableBase;
    }

    @Override
    public void beforeRegisterAllocation() {
        LIR lir = getResult().getLIR();
        loadConstantTableBaseOp.setAlive(lir, useConstantTableBase);
    }
}
