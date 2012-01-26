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

package com.oracle.max.graal.compiler.target.amd64;

import com.oracle.max.graal.compiler.target.amd64.AMD64Call.*;
import static com.oracle.max.cri.ci.CiValueUtil.*;
import static com.oracle.max.graal.compiler.target.amd64.AMD64Arithmetic.*;
import static com.oracle.max.graal.compiler.target.amd64.AMD64Compare.*;
import static com.oracle.max.graal.compiler.target.amd64.AMD64CompareToIntOpcode.*;
import static com.oracle.max.graal.compiler.target.amd64.AMD64ControlFlow.*;

import java.util.*;

import com.oracle.max.asm.*;
import com.oracle.max.asm.target.amd64.*;
import com.oracle.max.cri.ci.*;
import com.oracle.max.cri.ci.CiTargetMethod.*;
import com.oracle.max.cri.ri.*;
import com.oracle.max.cri.xir.*;
import com.oracle.max.cri.xir.CiXirAssembler.*;
import com.oracle.max.graal.compiler.*;
import com.oracle.max.graal.compiler.gen.*;
import com.oracle.max.graal.compiler.lir.*;
import com.oracle.max.graal.compiler.target.amd64.AMD64Arithmetic.DivOp;
import com.oracle.max.graal.compiler.target.amd64.AMD64Arithmetic.Op1Reg;
import com.oracle.max.graal.compiler.target.amd64.AMD64Arithmetic.Op1Stack;
import com.oracle.max.graal.compiler.target.amd64.AMD64Arithmetic.Op2RegCommutative;
import com.oracle.max.graal.compiler.target.amd64.AMD64Arithmetic.Op2Stack;
import com.oracle.max.graal.compiler.target.amd64.AMD64Arithmetic.ShiftOp;
import com.oracle.max.graal.compiler.target.amd64.AMD64Compare.CompareOp;
import com.oracle.max.graal.compiler.target.amd64.AMD64Move.*;
import com.oracle.max.graal.compiler.util.*;
import com.oracle.max.graal.graph.*;
import com.oracle.max.graal.nodes.DeoptimizeNode.DeoptAction;
import com.oracle.max.graal.nodes.*;
import com.oracle.max.graal.nodes.calc.*;
import com.oracle.max.graal.nodes.extended.*;
import com.oracle.max.graal.nodes.java.*;

/**
 * This class implements the X86-specific portion of the LIR generator.
 */
public class AMD64LIRGenerator extends LIRGenerator {

    private static final CiRegisterValue RAX_I = AMD64.rax.asValue(CiKind.Int);
    private static final CiRegisterValue RAX_L = AMD64.rax.asValue(CiKind.Long);
    private static final CiRegisterValue RDX_I = AMD64.rdx.asValue(CiKind.Int);
    private static final CiRegisterValue RDX_L = AMD64.rdx.asValue(CiKind.Long);
    private static final CiRegisterValue RCX_I = AMD64.rcx.asValue(CiKind.Int);

    public static class AMD64SpillMoveFactory implements LIR.SpillMoveFactory {
        @Override
        public LIRInstruction createMove(CiValue result, CiValue input) {
            return new SpillMoveOp(result, input);
        }

        @Override
        public LIRInstruction createExchange(CiValue input1, CiValue input2) {
            // TODO implement XCHG operation for LIR
            return null;
        }
    }

    public AMD64LIRGenerator(Graph graph, RiRuntime runtime, CiTarget target, FrameMap frameMap, RiResolvedMethod method, LIR lir, RiXirGenerator xir) {
        super(graph, runtime, target, frameMap, method, lir, xir);
        lir.methodEndMarker = new AMD64MethodEndStub();
        lir.spillMoveFactory = new AMD64SpillMoveFactory();
    }

    @Override
    protected void emitNode(ValueNode node) {
        if (node instanceof AMD64LIRLowerable) {
            ((AMD64LIRLowerable) node).generateAmd64(this);
        } else {
            super.emitNode(node);
        }
    }

    @Override
    public boolean canStoreConstant(CiConstant c) {
        // there is no immediate move of 64-bit constants on Intel
        switch (c.kind) {
            case Long:   return Util.isInt(c.asLong());
            case Double: return false;
            case Object: return c.isNull();
            default:     return true;
        }
    }

    @Override
    public boolean canInlineConstant(CiConstant c) {
        switch (c.kind) {
            case Long:   return NumUtil.isInt(c.asLong());
            case Object: return c.isNull();
            default:     return true;
        }
    }

    @Override
    public CiAddress makeAddress(LocationNode location, ValueNode object) {
        CiValue base = operand(object);
        CiValue index = CiValue.IllegalValue;
        int scale = 1;
        long displacement = location.displacement();

        if (isConstant(base)) {
            if (!asConstant(base).isNull()) {
                displacement += asConstant(base).asLong();
            }
            base = CiValue.IllegalValue;
        }

        if (location instanceof IndexedLocationNode) {
            IndexedLocationNode indexedLoc = (IndexedLocationNode) location;

            index = operand(indexedLoc.index());
            if (indexedLoc.indexScalingEnabled()) {
                scale = target().sizeInBytes(location.getValueKind());
            }
            if (isConstant(index)) {
                displacement += asConstant(index).asLong() * scale;
                index = CiValue.IllegalValue;
            }
        }

        if (!NumUtil.isInt(displacement)) {
            // Currently it's not worth handling this case.
            throw new CiBailout("integer overflow when computing constant displacement");
        }
        return new CiAddress(location.getValueKind(), base, index, CiAddress.Scale.fromInt(scale), (int) displacement);
    }

    @Override
    public Variable emitMove(CiValue input) {
        Variable result = newVariable(input.kind);
        emitMove(input, result);
        return result;
    }

    @Override
    public void emitMove(CiValue src, CiValue dst) {
        if (isRegister(src) || isStackSlot(dst)) {
            append(new MoveFromRegOp(dst, src));
        } else {
            append(new MoveToRegOp(dst, src));
        }
    }

    @Override
    public Variable emitLoad(CiValue loadAddress, boolean canTrap) {
        Variable result = newVariable(loadAddress.kind);
        append(new LoadOp(result, loadAddress, canTrap ? state() : null));
        return result;
    }

    @Override
    public void emitStore(CiValue storeAddress, CiValue inputVal, boolean canTrap) {
        CiValue input = loadForStore(inputVal, storeAddress.kind);
        append(new StoreOp(storeAddress, input, canTrap ? state() : null));
    }

    @Override
    public Variable emitLea(CiValue address) {
        Variable result = newVariable(target().wordKind);
        append(new LeaOp(result, address));
        return result;
    }

    @Override
    public void emitLabel(Label label, boolean align) {
        append(new LabelOp(label, align));
    }

    @Override
    public void emitJump(LabelRef label, LIRDebugInfo info) {
        append(new JumpOp(label, info));
    }

    @Override
    public void emitBranch(CiValue left, CiValue right, Condition cond, boolean unorderedIsTrue, LabelRef label, LIRDebugInfo info) {
        emitCompare(left, right);
        switch (left.kind) {
            case Boolean:
            case Int:
            case Long:
            case Object: append(new BranchOp(cond, label, info)); break;
            case Float:
            case Double: append(new FloatBranchOp(cond, unorderedIsTrue, label, info)); break;
            default: throw Util.shouldNotReachHere("" + left.kind);
        }
    }

    @Override
    public Variable emitCMove(CiValue left, CiValue right, Condition cond, boolean unorderedIsTrue, CiValue trueValue, CiValue falseValue) {
        emitCompare(left, right);

        Variable result = newVariable(trueValue.kind);
        switch (left.kind) {
            case Boolean:
            case Int:
            case Long:
            case Object: append(new CondMoveOp(result, cond, load(trueValue), loadNonConst(falseValue))); break;
            case Float:
            case Double: append(new FloatCondMoveOp(result, cond, unorderedIsTrue, load(trueValue), load(falseValue))); break;

        }
        return result;
    }

    private void emitCompare(CiValue a, CiValue b) {
        Variable left = load(a);
        CiValue right = loadNonConst(b);
        switch (left.kind) {
            case Jsr:
            case Int: append(new CompareOp(ICMP, left, right)); break;
            case Long: append(new CompareOp(LCMP, left, right)); break;
            case Object: append(new CompareOp(ACMP, left, right)); break;
            case Float: append(new CompareOp(FCMP, left, right)); break;
            case Double: append(new CompareOp(DCMP, left, right)); break;
            default: throw Util.shouldNotReachHere();
        }
    }

    @Override
    public Variable emitNegate(CiValue input) {
        Variable result = newVariable(input.kind);
        switch (input.kind) {
            case Int:    append(new Op1Stack(INEG, result, input)); break;
            case Long:   append(new Op1Stack(LNEG, result, input)); break;
            case Float:  append(new Op2RegCommutative(FXOR, result, input, CiConstant.forFloat(Float.intBitsToFloat(0x80000000)))); break;
            case Double: append(new Op2RegCommutative(DXOR, result, input, CiConstant.forDouble(Double.longBitsToDouble(0x8000000000000000L)))); break;
            default: throw Util.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitAdd(CiValue a, CiValue b) {
        Variable result = newVariable(a.kind);
        switch(a.kind) {
            case Int:    append(new Op2Stack(IADD, result, a, loadNonConst(b))); break;
            case Long:   append(new Op2Stack(LADD, result, a, loadNonConst(b))); break;
            case Float:  append(new Op2Stack(FADD, result, a, loadNonConst(b))); break;
            case Double: append(new Op2Stack(DADD, result, a, loadNonConst(b))); break;
            default:     throw Util.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitSub(CiValue a, CiValue b) {
        Variable result = newVariable(a.kind);
        switch(a.kind) {
            case Int:    append(new Op2Stack(ISUB, result, a, loadNonConst(b))); break;
            case Long:   append(new Op2Stack(LSUB, result, a, loadNonConst(b))); break;
            case Float:  append(new Op2Stack(FSUB, result, a, loadNonConst(b))); break;
            case Double: append(new Op2Stack(DSUB, result, a, loadNonConst(b))); break;
            default:     throw Util.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitMul(CiValue a, CiValue b) {
        Variable result = newVariable(a.kind);
        switch(a.kind) {
            case Int:    append(new Op2RegCommutative(IMUL, result, a, loadNonConst(b))); break;
            case Long:   append(new Op2RegCommutative(LMUL, result, a, loadNonConst(b))); break;
            case Float:  append(new Op2Stack(FMUL, result, a, loadNonConst(b))); break;
            case Double: append(new Op2Stack(DMUL, result, a, loadNonConst(b))); break;
            default:     throw Util.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitDiv(CiValue a, CiValue b) {
        switch(a.kind) {
            case Int:
                emitMove(a, RAX_I);
                append(new DivOp(IDIV, RAX_I, RAX_I, load(b), state()));
                return emitMove(RAX_I);
            case Long:
                emitMove(a, RAX_L);
                append(new DivOp(LDIV, RAX_L, RAX_L, load(b), state()));
                return emitMove(RAX_L);
            case Float: {
                Variable result = newVariable(a.kind);
                append(new Op2Stack(FDIV, result, a, loadNonConst(b)));
                return result;
            }
            case Double: {
                Variable result = newVariable(a.kind);
                append(new Op2Stack(DDIV, result, a, loadNonConst(b)));
                return result;
            }
            default:
                throw Util.shouldNotReachHere();
        }
    }

    @Override
    public Variable emitRem(CiValue a, CiValue b) {
        switch(a.kind) {
            case Int:
                emitMove(a, RAX_I);
                append(new DivOp(IREM, RDX_I, RAX_I, load(b), state()));
                return emitMove(RDX_I);
            case Long:
                emitMove(a, RAX_L);
                append(new DivOp(LREM, RDX_L, RAX_L, load(b), state()));
                return emitMove(RDX_L);
            case Float:
                return emitCallToRuntime(CiRuntimeCall.ArithmeticFrem, false, a, b);
            case Double:
                return emitCallToRuntime(CiRuntimeCall.ArithmeticDrem, false, a, b);
            default:
                throw Util.shouldNotReachHere();
        }
    }

    @Override
    public Variable emitUDiv(CiValue a, CiValue b) {
        switch(a.kind) {
            case Int:
                emitMove(a, RAX_I);
                append(new DivOp(IUDIV, RAX_I, RAX_I, load(b), state()));
                return emitMove(RAX_I);
            case Long:
                emitMove(a, RAX_L);
                append(new DivOp(LUDIV, RAX_L, RAX_L, load(b), state()));
                return emitMove(RAX_L);
            default:
                throw Util.shouldNotReachHere();
        }
    }

    @Override
    public Variable emitURem(CiValue a, CiValue b) {
        switch(a.kind) {
            case Int:
                emitMove(a, RAX_I);
                append(new DivOp(IUREM, RDX_I, RAX_I, load(b), state()));
                return emitMove(RDX_I);
            case Long:
                emitMove(a, RAX_L);
                append(new DivOp(LUREM, RDX_L, RAX_L, load(b), state()));
                return emitMove(RDX_L);
            default:
                throw Util.shouldNotReachHere();
        }
    }


    @Override
    public Variable emitAnd(CiValue a, CiValue b) {
        Variable result = newVariable(a.kind);
        switch(a.kind) {
            case Int:    append(new Op2Stack(IAND, result, a, loadNonConst(b))); break;
            case Long:   append(new Op2Stack(LAND, result, a, loadNonConst(b))); break;
            default:     throw Util.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitOr(CiValue a, CiValue b) {
        Variable result = newVariable(a.kind);
        switch(a.kind) {
            case Int:    append(new Op2Stack(IOR, result, a, loadNonConst(b))); break;
            case Long:   append(new Op2Stack(LOR, result, a, loadNonConst(b))); break;
            default:     throw Util.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitXor(CiValue a, CiValue b) {
        Variable result = newVariable(a.kind);
        switch(a.kind) {
            case Int:    append(new Op2Stack(IXOR, result, a, loadNonConst(b))); break;
            case Long:   append(new Op2Stack(LXOR, result, a, loadNonConst(b))); break;
            default:     throw Util.shouldNotReachHere();
        }
        return result;
    }


    @Override
    public Variable emitShl(CiValue a, CiValue b) {
        Variable result = newVariable(a.kind);
        switch (a.kind) {
            case Int:    append(new ShiftOp(ISHL, result, a, loadShiftCount(b))); break;
            case Long:   append(new ShiftOp(LSHL, result, a, loadShiftCount(b))); break;
            default: Util.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitShr(CiValue a, CiValue b) {
        Variable result = newVariable(a.kind);
        switch (a.kind) {
            case Int:    append(new ShiftOp(ISHR, result, a, loadShiftCount(b))); break;
            case Long:   append(new ShiftOp(LSHR, result, a, loadShiftCount(b))); break;
            default: Util.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitUShr(CiValue a, CiValue b) {
        Variable result = newVariable(a.kind);
        switch (a.kind) {
            case Int:    append(new ShiftOp(IUSHR, result, a, loadShiftCount(b))); break;
            case Long:   append(new ShiftOp(LUSHR, result, a, loadShiftCount(b))); break;
            default: Util.shouldNotReachHere();
        }
        return result;
    }

    private CiValue loadShiftCount(CiValue value) {
        if (isConstant(value)) {
            return value;
        }
        // Non-constant shift count must be in RCX
        emitMove(value, RCX_I);
        return RCX_I;
    }


    @Override
    public Variable emitConvert(ConvertNode.Op opcode, CiValue inputVal) {
        Variable input = load(inputVal);
        Variable result = newVariable(opcode.to);
        switch (opcode) {
            case I2L: append(new Op1Reg(I2L, result, input)); break;
            case L2I: append(new Op1Stack(L2I, result, input)); break;
            case I2B: append(new Op1Stack(I2B, result, input)); break;
            case I2C: append(new Op1Stack(I2C, result, input)); break;
            case I2S: append(new Op1Stack(I2S, result, input)); break;
            case F2D: append(new Op1Reg(F2D, result, input)); break;
            case D2F: append(new Op1Reg(D2F, result, input)); break;
            case I2F: append(new Op1Reg(I2F, result, input)); break;
            case I2D: append(new Op1Reg(I2D, result, input)); break;
            case F2I: append(new Op1Reg(F2I, result, input)); break;
            case D2I: append(new Op1Reg(D2I, result, input)); break;
            case L2F: append(new Op1Reg(L2F, result, input)); break;
            case L2D: append(new Op1Reg(L2D, result, input)); break;
            case F2L: append(new Op1Reg(F2L, result, input)); break;
            case D2L: append(new Op1Reg(D2L, result, input)); break;
            case MOV_I2F: append(new Op1Reg(MOV_I2F, result, input)); break;
            case MOV_L2D: append(new Op1Reg(MOV_L2D, result, input)); break;
            case MOV_F2I: append(new Op1Reg(MOV_F2I, result, input)); break;
            case MOV_D2L: append(new Op1Reg(MOV_D2L, result, input)); break;
            default: throw Util.shouldNotReachHere();
        }
        return result;
    }


    @Override
    public void emitDeoptimizeOn(Condition cond, DeoptAction action, Object deoptInfo) {
        LIRDebugInfo info = state();
        LabelRef stubEntry = createDeoptStub(action, info, deoptInfo);
        if (cond != null) {
            append(new BranchOp(cond, stubEntry, info));
        } else {
            append(new JumpOp(stubEntry, info));
        }
    }

    @Override
    public void emitMembar(int barriers) {
        int necessaryBarriers = target.arch.requiredBarriers(barriers);
        if (target.isMP && necessaryBarriers != 0) {
            append(new MembarOp(necessaryBarriers));
        }
    }

    @Override
    protected void emitCall(Object targetMethod, CiValue result, List<CiValue> arguments, CiValue targetAddress, LIRDebugInfo info, Map<XirMark, Mark> marks) {
        if (isConstant(targetAddress)) {
            assert asConstant(targetAddress).isDefaultValue() : "destination address should be zero";
            append(new DirectCallOp(targetMethod, result, arguments.toArray(new CiValue[arguments.size()]), info, marks));
        } else {
            append(new IndirectCallOp(targetMethod, result, arguments.toArray(new CiValue[arguments.size()]), targetAddress, info, marks));
        }
    }

    @Override
    protected void emitReturn(CiValue input) {
        append(new ReturnOp(input));
    }

    @Override
    protected void emitXir(XirSnippet snippet, CiValue[] operands, CiValue outputOperand, CiValue[] inputs, CiValue[] temps, int[] inputOperandIndices, int[] tempOperandIndices, int outputOperandIndex,
                    LIRDebugInfo info, LIRDebugInfo infoAfter, LabelRef trueSuccessor, LabelRef falseSuccessor) {
        append(new AMD64XirOp(snippet, operands, outputOperand, inputs, temps, inputOperandIndices, tempOperandIndices, outputOperandIndex, info, infoAfter, trueSuccessor, falseSuccessor));
    }

    @Override
    protected void emitTableSwitch(int lowKey, LabelRef defaultTarget, LabelRef[] targets, CiValue index) {
        // Making a copy of the switch value is necessary because jump table destroys the input value
        Variable tmp = emitMove(index);
        append(new TableSwitchOp(lowKey, defaultTarget, targets, tmp, newVariable(target.wordKind)));
    }

    @Override
    protected LabelRef createDeoptStub(DeoptAction action, LIRDebugInfo info, Object deoptInfo) {
        assert info.topFrame.bci >= 0 : "invalid bci for deopt framestate";
        AMD64DeoptimizationStub stub = new AMD64DeoptimizationStub(action, info, deoptInfo);
        lir.deoptimizationStubs.add(stub);
        return LabelRef.forLabel(stub.label);
    }

    @Override
    protected void emitNullCheckGuard(NullCheckNode node) {
        assert !node.expectedNull;
        Variable value = load(operand(node.object()));
        LIRDebugInfo info = state();
        append(new NullCheckOp(value, info));
    }

    // TODO The CompareAndSwapNode in its current form needs to be lowered to several Nodes before code generation to separate three parts:
    // * The write barriers (and possibly read barriers) when accessing an object field
    // * The distinction of returning a boolean value (semantic similar to a BooleanNode to be used as a condition?) or the old value being read
    // * The actual compare-and-swap
    @Override
    public void visitCompareAndSwap(CompareAndSwapNode node) {
        CiKind kind = node.newValue().kind();
        assert kind == node.expected().kind();

        CiValue expected = loadNonConst(operand(node.expected()));
        Variable newValue = load(operand(node.newValue()));

        CiAddress address;
        CiValue index = operand(node.offset());
        if (isConstant(index) && NumUtil.isInt(asConstant(index).asLong())) {
            address = new CiAddress(kind, load(operand(node.object())), (int) asConstant(index).asLong());
        } else {
            address = new CiAddress(kind, load(operand(node.object())), load(index), CiAddress.Scale.Times1, 0);
        }

        if (kind == CiKind.Object) {
            address = new CiAddress(kind, emitLea(address));
            preGCWriteBarrier(address.base, false, null);
        }

        CiRegisterValue rax = AMD64.rax.asValue(kind);
        emitMove(expected, rax);
        append(new CompareAndSwapOp(rax, address, rax, newValue));

        Variable result = newVariable(node.kind());
        if (node.directResult()) {
            emitMove(rax, result);
        } else {
            append(new CondMoveOp(result, Condition.EQ, load(CiConstant.TRUE), CiConstant.FALSE));
        }
        setResult(node, result);

        if (kind == CiKind.Object) {
            postGCWriteBarrier(address.base, newValue);
        }
    }

    // TODO The class NormalizeCompareNode should be lowered away in the front end, since the code generated is long and uses branches anyway.
    @Override
    public void visitNormalizeCompare(NormalizeCompareNode x) {
        emitCompare(operand(x.x()), operand(x.y()));
        Variable result = newVariable(x.kind());
        switch (x.x().kind()){
            case Float:
            case Double:
                if (x.isUnorderedLess) {
                    append(CMP2INT_UL.create(result));
                } else {
                    append(CMP2INT_UG.create(result));
                }
                break;
            case Long:
                append(CMP2INT.create(result));
                break;
            default:
                throw Util.shouldNotReachHere();
        }
        setResult(x, result);
    }
}
