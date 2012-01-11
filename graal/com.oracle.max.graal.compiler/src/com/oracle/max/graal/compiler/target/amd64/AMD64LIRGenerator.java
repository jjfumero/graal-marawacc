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

import static com.oracle.max.cri.ci.CiValueUtil.*;
import static com.oracle.max.graal.compiler.target.amd64.AMD64ArithmeticOpcode.*;
import static com.oracle.max.graal.compiler.target.amd64.AMD64CompareOpcode.*;
import static com.oracle.max.graal.compiler.target.amd64.AMD64CompareToIntOpcode.*;
import static com.oracle.max.graal.compiler.target.amd64.AMD64ConvertFIOpcode.*;
import static com.oracle.max.graal.compiler.target.amd64.AMD64ConvertFLOpcode.*;
import static com.oracle.max.graal.compiler.target.amd64.AMD64ConvertOpcode.*;
import static com.oracle.max.graal.compiler.target.amd64.AMD64DivOpcode.*;
import static com.oracle.max.graal.compiler.target.amd64.AMD64LogicFloatOpcode.*;
import static com.oracle.max.graal.compiler.target.amd64.AMD64MulOpcode.*;
import static com.oracle.max.graal.compiler.target.amd64.AMD64Op1Opcode.*;
import static com.oracle.max.graal.compiler.target.amd64.AMD64ShiftOpcode.*;
import static com.oracle.max.graal.compiler.target.amd64.AMD64StandardOpcode.*;

import com.oracle.max.asm.*;
import com.oracle.max.asm.target.amd64.*;
import com.oracle.max.cri.ci.*;
import com.oracle.max.cri.ri.*;
import com.oracle.max.cri.xir.*;
import com.oracle.max.graal.compiler.*;
import com.oracle.max.graal.compiler.gen.*;
import com.oracle.max.graal.compiler.lir.*;
import com.oracle.max.graal.compiler.util.*;
import com.oracle.max.graal.graph.*;
import com.oracle.max.graal.nodes.DeoptimizeNode.DeoptAction;
import com.oracle.max.graal.nodes.*;
import com.oracle.max.graal.nodes.calc.*;
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

    static {
        StandardOpcode.SPILL_MOVE = AMD64MoveOpcode.SpillMoveOpcode.SPILL_MOVE;
        StandardOpcode.NULL_CHECK = AMD64StandardOpcode.NULL_CHECK;
        StandardOpcode.DIRECT_CALL = AMD64CallOpcode.DIRECT_CALL;
        StandardOpcode.INDIRECT_CALL = AMD64CallOpcode.INDIRECT_CALL;
        StandardOpcode.LABEL = AMD64StandardOpcode.LABEL;
        StandardOpcode.JUMP = AMD64StandardOpcode.JUMP;
        StandardOpcode.RETURN = AMD64StandardOpcode.RETURN;
        StandardOpcode.XIR = AMD64XirOpcode.XIR;
    }

    public AMD64LIRGenerator(GraalContext context, Graph graph, RiRuntime runtime, CiTarget target, FrameMap frameMap, RiResolvedMethod method, LIR lir, RiXirGenerator xir) {
        super(context, graph, runtime, target, frameMap, method, lir, xir);
        lir.methodEndMarker = new AMD64MethodEndStub();
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
    public Variable emitMove(CiValue input) {
        Variable result = newVariable(input.kind);
        append(MOVE.create(result, input));
        return result;
    }

    @Override
    public void emitMove(CiValue src, CiValue dst) {
        append(MOVE.create(dst, src));
    }

    @Override
    public Variable emitLoad(CiAddress loadAddress, CiKind kind, boolean canTrap) {
        Variable result = newVariable(kind);
        append(LOAD.create(result, loadAddress.base, loadAddress.index, loadAddress.scale, loadAddress.displacement, kind, canTrap ? state() : null));
        return result;
    }

    @Override
    public void emitStore(CiAddress storeAddress, CiValue inputVal, CiKind kind, boolean canTrap) {
        CiValue input = loadForStore(inputVal, kind);
        append(STORE.create(storeAddress.base, storeAddress.index, storeAddress.scale, storeAddress.displacement, input, kind, canTrap ? state() : null));
    }

    @Override
    public Variable emitLea(CiAddress address) {
        Variable result = newVariable(target().wordKind);
        append(LEA_MEMORY.create(result, address.base, address.index, address.scale, address.displacement));
        return result;
    }

    @Override
    public Variable emitLea(CiStackSlot address) {
        Variable result = newVariable(target().wordKind);
        append(LEA_STACK.create(result, address));
        return result;
    }

    @Override
    public void emitLabel(Label label, boolean align) {
        append(LABEL.create(label, align));
    }

    @Override
    public void emitJump(LabelRef label, LIRDebugInfo info) {
        append(JUMP.create(label, info));
    }

    @Override
    public void emitBranch(CiValue left, CiValue right, Condition cond, boolean unorderedIsTrue, LabelRef label, LIRDebugInfo info) {
        emitCompare(left, right);
        switch (left.kind) {
            case Boolean:
            case Int:
            case Long:
            case Object: append(BRANCH.create(cond, label, info)); break;
            case Float:
            case Double: append(FLOAT_BRANCH.create(cond, unorderedIsTrue, label, info)); break;
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
            case Object: append(CMOVE.create(result, cond, load(trueValue), loadNonConst(falseValue))); break;
            case Float:
            case Double: append(FLOAT_CMOVE.create(result, cond, unorderedIsTrue, load(trueValue), load(falseValue))); break;

        }
        return result;
    }

    private void emitCompare(CiValue a, CiValue b) {
        Variable left = load(a);
        CiValue right = loadNonConst(b);
        switch (left.kind) {
            case Jsr:
            case Int: append(ICMP.create(left, right)); break;
            case Long: append(LCMP.create(left, right)); break;
            case Object: append(ACMP.create(left, right)); break;
            case Float: append(FCMP.create(left, right)); break;
            case Double: append(DCMP.create(left, right)); break;
            default: throw Util.shouldNotReachHere();
        }
    }

    @Override
    public Variable emitNegate(CiValue input) {
        Variable result = newVariable(input.kind);
        switch (input.kind) {
            case Int:    append(INEG.create(result, input)); break;
            case Long:   append(LNEG.create(result, input)); break;
            case Float:  append(FXOR.create(result, input, CiConstant.forFloat(Float.intBitsToFloat(0x80000000)))); break;
            case Double: append(DXOR.create(result, input, CiConstant.forDouble(Double.longBitsToDouble(0x8000000000000000L)))); break;
            default: throw Util.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitAdd(CiValue a, CiValue b) {
        Variable result = newVariable(a.kind);
        switch(a.kind) {
            case Int:    append(IADD.create(result, a, loadNonConst(b))); break;
            case Long:   append(LADD.create(result, a, loadNonConst(b))); break;
            case Float:  append(FADD.create(result, a, loadNonConst(b))); break;
            case Double: append(DADD.create(result, a, loadNonConst(b))); break;
            default:     throw Util.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitSub(CiValue a, CiValue b) {
        Variable result = newVariable(a.kind);
        switch(a.kind) {
            case Int:    append(ISUB.create(result, a, loadNonConst(b))); break;
            case Long:   append(LSUB.create(result, a, loadNonConst(b))); break;
            case Float:  append(FSUB.create(result, a, loadNonConst(b))); break;
            case Double: append(DSUB.create(result, a, loadNonConst(b))); break;
            default:     throw Util.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitMul(CiValue a, CiValue b) {
        Variable result = newVariable(a.kind);
        switch(a.kind) {
            case Int:    append(IMUL.create(result, a, loadNonConst(b))); break;
            case Long:   append(LMUL.create(result, a, loadNonConst(b))); break;
            case Float:  append(FMUL.create(result, a, loadNonConst(b))); break;
            case Double: append(DMUL.create(result, a, loadNonConst(b))); break;
            default:     throw Util.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitDiv(CiValue a, CiValue b) {
        switch(a.kind) {
            case Int:
                append(MOVE.create(RAX_I, a));
                append(IDIV.create(RAX_I, state(), RAX_I, load(b)));
                return emitMove(RAX_I);
            case Long:
                append(MOVE.create(RAX_L, a));
                append(LDIV.create(RAX_L, state(), RAX_L, load(b)));
                return emitMove(RAX_L);
            case Float: {
                Variable result = newVariable(a.kind);
                append(FDIV.create(result, a, loadNonConst(b)));
                return result;
            }
            case Double: {
                Variable result = newVariable(a.kind);
                append(DDIV.create(result, a, loadNonConst(b)));
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
                append(MOVE.create(RAX_I, a));
                append(IREM.create(RDX_I, state(), RAX_I, load(b)));
                return emitMove(RDX_I);
            case Long:
                append(MOVE.create(RAX_L, a));
                append(LREM.create(RDX_L, state(), RAX_L, load(b)));
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
                append(MOVE.create(RAX_I, load(a)));
                append(IUDIV.create(RAX_I, state(), RAX_I, load(b)));
                return emitMove(RAX_I);
            case Long:
                append(MOVE.create(RAX_L, load(a)));
                append(LUDIV.create(RAX_L, state(), RAX_L, load(b)));
                return emitMove(RAX_L);
            default:
                throw Util.shouldNotReachHere();
        }
    }

    @Override
    public Variable emitURem(CiValue a, CiValue b) {
        switch(a.kind) {
            case Int:
                append(MOVE.create(RAX_I, load(a)));
                append(IUREM.create(RDX_I, state(), RAX_I, load(b)));
                return emitMove(RDX_I);
            case Long:
                append(MOVE.create(RAX_L, load(a)));
                append(LUREM.create(RDX_L, state(), RAX_L, load(b)));
                return emitMove(RDX_L);
            default:
                throw Util.shouldNotReachHere();
        }
    }


    @Override
    public Variable emitAnd(CiValue a, CiValue b) {
        Variable result = newVariable(a.kind);
        switch(a.kind) {
            case Int:    append(IAND.create(result, a, loadNonConst(b))); break;
            case Long:   append(LAND.create(result, a, loadNonConst(b))); break;
            default:     throw Util.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitOr(CiValue a, CiValue b) {
        Variable result = newVariable(a.kind);
        switch(a.kind) {
            case Int:    append(IOR.create(result, a, loadNonConst(b))); break;
            case Long:   append(LOR.create(result, a, loadNonConst(b))); break;
            default:     throw Util.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitXor(CiValue a, CiValue b) {
        Variable result = newVariable(a.kind);
        switch(a.kind) {
            case Int:    append(IXOR.create(result, a, loadNonConst(b))); break;
            case Long:   append(LXOR.create(result, a, loadNonConst(b))); break;
            default:     throw Util.shouldNotReachHere();
        }
        return result;
    }


    @Override
    public Variable emitShl(CiValue a, CiValue b) {
        Variable result = newVariable(a.kind);
        switch (a.kind) {
            case Int:    append(ISHL.create(result, a, loadShiftCount(b))); break;
            case Long:   append(LSHL.create(result, a, loadShiftCount(b))); break;
            default: Util.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitShr(CiValue a, CiValue b) {
        Variable result = newVariable(a.kind);
        switch (a.kind) {
            case Int:    append(ISHR.create(result, a, loadShiftCount(b))); break;
            case Long:   append(LSHR.create(result, a, loadShiftCount(b))); break;
            default: Util.shouldNotReachHere();
        }
        return result;
    }

    @Override
    public Variable emitUShr(CiValue a, CiValue b) {
        Variable result = newVariable(a.kind);
        switch (a.kind) {
            case Int:    append(IUSHR.create(result, a, loadShiftCount(b))); break;
            case Long:   append(LUSHR.create(result, a, loadShiftCount(b))); break;
            default: Util.shouldNotReachHere();
        }
        return result;
    }

    private CiValue loadShiftCount(CiValue value) {
        if (isConstant(value)) {
            return value;
        }
        // Non-constant shift count must be in RCX
        append(MOVE.create(RCX_I, value));
        return RCX_I;
    }


    @Override
    public Variable emitConvert(ConvertNode.Op opcode, CiValue inputVal) {
        Variable input = load(inputVal);
        Variable result = newVariable(opcode.to);
        switch (opcode) {
            case I2L: append(I2L.create(result, input)); break;
            case L2I: append(L2I.create(result, input)); break;
            case I2B: append(I2B.create(result, input)); break;
            case I2C: append(I2C.create(result, input)); break;
            case I2S: append(I2S.create(result, input)); break;
            case F2D: append(F2D.create(result, input)); break;
            case D2F: append(D2F.create(result, input)); break;
            case I2F: append(I2F.create(result, input)); break;
            case I2D: append(I2D.create(result, input)); break;
            case F2I: append(F2I.create(result, input)); break;
            case D2I: append(D2I.create(result, input)); break;
            case L2F: append(L2F.create(result, input)); break;
            case L2D: append(L2D.create(result, input)); break;
            case F2L: append(F2L.create(result, input, newVariable(CiKind.Long))); break;
            case D2L: append(D2L.create(result, input, newVariable(CiKind.Long))); break;
            case MOV_I2F: append(MOV_I2F.create(result, input)); break;
            case MOV_L2D: append(MOV_L2D.create(result, input)); break;
            case MOV_F2I: append(MOV_F2I.create(result, input)); break;
            case MOV_D2L: append(MOV_D2L.create(result, input)); break;
            default: throw Util.shouldNotReachHere();
        }
        return result;
    }


    @Override
    public void emitDeoptimizeOn(Condition cond, DeoptAction action, Object deoptInfo) {
        LIRDebugInfo info = state();
        LabelRef stubEntry = createDeoptStub(action, info, deoptInfo);
        if (cond != null) {
            append(BRANCH.create(cond, stubEntry, info));
        } else {
            append(JUMP.create(stubEntry, info));
        }
    }

    @Override
    public void emitMembar(int barriers) {
        int necessaryBarriers = target.arch.requiredBarriers(barriers);
        if (target.isMP && necessaryBarriers != 0) {
            append(MEMBAR.create(necessaryBarriers));
        }
    }

    @Override
    protected void emitTableSwitch(int lowKey, LabelRef defaultTarget, LabelRef[] targets, CiValue index) {
        // Making a copy of the switch value is necessary because jump table destroys the input value
        Variable tmp = emitMove(index);
        append(TABLE_SWITCH.create(lowKey, defaultTarget, targets, tmp, newVariable(target.wordKind)));
    }

    @Override
    protected LabelRef createDeoptStub(DeoptAction action, LIRDebugInfo info, Object deoptInfo) {
        assert info.topFrame.bci >= 0 : "invalid bci for deopt framestate";
        AMD64DeoptimizationStub stub = new AMD64DeoptimizationStub(action, info, deoptInfo);
        lir.deoptimizationStubs.add(stub);
        return LabelRef.forLabel(stub.label);
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
        Variable addrBase = load(operand(node.object()));
        CiValue addrIndex = operand(node.offset());
        int addrDisplacement = 0;
        if (isConstant(addrIndex) && NumUtil.isInt(asConstant(addrIndex).asLong())) {
            addrDisplacement = (int) asConstant(addrIndex).asLong();
            addrIndex = CiValue.IllegalValue;
        } else {
            addrIndex = load(addrIndex);
        }

        if (kind == CiKind.Object) {
            Variable loadedAddress = newVariable(target.wordKind);
            append(LEA_MEMORY.create(loadedAddress, addrBase, addrIndex, CiAddress.Scale.Times1, addrDisplacement));
            preGCWriteBarrier(loadedAddress, false, null);

            addrBase = loadedAddress;
            addrIndex = Variable.IllegalValue;
            addrDisplacement = 0;
        }

        CiRegisterValue rax = AMD64.rax.asValue(kind);
        append(MOVE.create(rax, expected));
        append(CAS.create(rax, addrBase, addrIndex, CiAddress.Scale.Times1, addrDisplacement, rax, newValue));

        Variable result = newVariable(node.kind());
        if (node.directResult()) {
            append(MOVE.create(result, rax));
        } else {
            append(CMOVE.create(result, Condition.EQ, load(CiConstant.TRUE), CiConstant.FALSE));
        }
        setResult(node, result);

        if (kind == CiKind.Object) {
            postGCWriteBarrier(addrBase, newValue);
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
