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
package com.oracle.graal.lir.ptx;

import static com.oracle.graal.api.code.ValueUtil.*;
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.*;
import static java.lang.Double.*;
import static java.lang.Float.*;

import com.oracle.graal.ptx.*;
import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.asm.ptx.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.LIRInstruction.Opcode;
import com.oracle.graal.lir.StandardOp.MoveOp;
import com.oracle.graal.lir.asm.*;

// @formatter:off
public class PTXMove {

    @Opcode("MOVE")
    public static class SpillMoveOp extends PTXLIRInstruction implements MoveOp {
        @Def({REG, STACK}) protected Value result;
        @Use({REG, STACK, CONST}) protected Value input;

        public SpillMoveOp(Value result, Value input) {
            this.result = result;
            this.input = input;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, PTXMacroAssembler masm) {
            move(tasm, masm, getResult(), getInput());
        }

        @Override
        public Value getInput() {
            return input;
        }
        @Override
        public Value getResult() {
            return result;
        }
    }


    @Opcode("MOVE")
    public static class MoveToRegOp extends PTXLIRInstruction implements MoveOp {
        @Def({REG, HINT}) protected Value result;
        @Use({REG, STACK, CONST}) protected Value input;

        public MoveToRegOp(Value result, Value input) {
            this.result = result;
            this.input = input;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, PTXMacroAssembler masm) {
            move(tasm, masm, getResult(), getInput());
        }

        @Override
        public Value getInput() {
            return input;
        }
        @Override
        public Value getResult() {
            return result;
        }
    }


    @Opcode("MOVE")
    public static class MoveFromRegOp extends PTXLIRInstruction implements MoveOp {
        @Def({REG, STACK}) protected Value result;
        @Use({REG, CONST, HINT}) protected Value input;

        public MoveFromRegOp(Value result, Value input) {
            this.result = result;
            this.input = input;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, PTXMacroAssembler masm) {
            move(tasm, masm, getResult(), getInput());
        }

        @Override
        public Value getInput() {
            return input;
        }
        @Override
        public Value getResult() {
            return result;
        }
    }


    public static class LoadOp extends PTXLIRInstruction {
        @Def({REG}) protected Value result;
        @Use({ADDR}) protected Value address;
        @State protected LIRFrameState state;

        public LoadOp(Value result, Value address, LIRFrameState state) {
            this.result = result;
            this.address = address;
            this.state = state;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, PTXMacroAssembler masm) {
            load(tasm, masm, result, (Address) address, state);
        }
    }


    public static class StoreOp extends PTXLIRInstruction {
        @Use({ADDR}) protected Value address;
        @Use({REG, CONST}) protected Value input;
        @State protected LIRFrameState state;

        public StoreOp(Value address, Value input, LIRFrameState state) {
            this.address = address;
            this.input = input;
            this.state = state;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, PTXMacroAssembler masm) {
            store(tasm, masm, (Address) address, input, state);
        }
    }


    public static class LeaOp extends PTXLIRInstruction {
        @Def({REG}) protected Value result;
        @Use({ADDR, STACK, UNINITIALIZED}) protected Value address;

        public LeaOp(Value result, Value address) {
            this.result = result;
            this.address = address;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, PTXMacroAssembler masm) {
//            masm.leaq(asLongReg(result), tasm.asAddress(address));
            throw new InternalError("NYI");
        }
    }


    public static class MembarOp extends PTXLIRInstruction {
        private final int barriers;

        public MembarOp(final int barriers) {
            this.barriers = barriers;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, PTXMacroAssembler masm) {
//            masm.membar(barriers);
            throw new InternalError("NYI");
        }
    }


    public static class NullCheckOp extends PTXLIRInstruction {
        @Use protected Value input;
        @State protected LIRFrameState state;

        public NullCheckOp(Variable input, LIRFrameState state) {
            this.input = input;
            this.state = state;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, PTXMacroAssembler masm) {
//            tasm.recordImplicitException(masm.codeBuffer.position(), state);
//            masm.nullCheck(asRegister(input));
            throw new InternalError("NYI");
        }
    }


    @Opcode("CAS")
    public static class CompareAndSwapOp extends PTXLIRInstruction {
        @Def protected Value result;
        @Use({ADDR}) protected Value address;
        @Use protected Value cmpValue;
        @Use protected Value newValue;

        public CompareAndSwapOp(Value result, Address address, Value cmpValue, Value newValue) {
            this.result = result;
            this.address = address;
            this.cmpValue = cmpValue;
            this.newValue = newValue;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, PTXMacroAssembler masm) {
            compareAndSwap(tasm, masm, result, (Address) address, cmpValue, newValue);
        }
    }


    public static void move(TargetMethodAssembler tasm, PTXMacroAssembler masm, Value result, Value input) {
        if (isRegister(input)) {
            if (isRegister(result)) {
                reg2reg(masm, result, input);
            } else if (isStackSlot(result)) {
                reg2stack(tasm, masm, result, input);
            } else {
                throw GraalInternalError.shouldNotReachHere();
            }
        } else if (isStackSlot(input)) {
            if (isRegister(result)) {
                stack2reg(tasm, masm, result, input);
            } else {
                throw GraalInternalError.shouldNotReachHere();
            }
        } else if (isConstant(input)) {
            if (isRegister(result)) {
                const2reg(tasm, masm, result, (Constant) input);
            } else if (isStackSlot(result)) {
                const2stack(tasm, masm, result, (Constant) input);
            } else {
                throw GraalInternalError.shouldNotReachHere();
            }
        } else {
            throw GraalInternalError.shouldNotReachHere();
        }
    }

    private static void reg2reg(PTXMacroAssembler masm, Value result, Value input) {
        if (asRegister(input).equals(asRegister(result))) {
            return;
        }
        switch (input.getKind()) {
            case Int:    masm.mov_s32(asRegister(result),    asRegister(input)); break;
//            case Long:   masm.movq(asRegister(result),    asRegister(input)); break;
//            case Float:  masm.movflt(asFloatReg(result),  asFloatReg(input)); break;
//            case Double: masm.movdbl(asDoubleReg(result), asDoubleReg(input)); break;
            case Object: masm.mov_u64(asRegister(result),    asRegister(input)); break;
            default:     throw GraalInternalError.shouldNotReachHere("kind=" + result.getKind());
        }
    }

    private static void reg2stack(TargetMethodAssembler tasm, PTXMacroAssembler masm, Value result, Value input) {
//        switch (input.getKind()) {
//            case Jsr:
//            case Int:    masm.movl(tasm.asAddress(result),   asRegister(input)); break;
//            case Long:   masm.movq(tasm.asAddress(result),   asRegister(input)); break;
//            case Float:  masm.movflt(tasm.asAddress(result), asFloatReg(input)); break;
//            case Double: masm.movsd(tasm.asAddress(result),  asDoubleReg(input)); break;
//            case Object: masm.movq(tasm.asAddress(result),   asRegister(input)); break;
//            default:     throw GraalInternalError.shouldNotReachHere();
//        }
        throw new InternalError("NYI");
    }

    private static void stack2reg(TargetMethodAssembler tasm, PTXMacroAssembler masm, Value result, Value input) {
//        switch (input.getKind()) {
//            case Jsr:
//            case Int:    masm.movl(asRegister(result),    tasm.asAddress(input)); break;
//            case Long:   masm.movq(asRegister(result),    tasm.asAddress(input)); break;
//            case Float:  masm.movflt(asFloatReg(result),  tasm.asAddress(input)); break;
//            case Double: masm.movdbl(asDoubleReg(result), tasm.asAddress(input)); break;
//            case Object: masm.movq(asRegister(result),    tasm.asAddress(input)); break;
//            default:     throw GraalInternalError.shouldNotReachHere();
//        }
        throw new InternalError("NYI");
    }

    private static void const2reg(TargetMethodAssembler tasm, PTXMacroAssembler masm, Value result, Constant input) {
        // Note: we use the kind of the input operand (and not the kind of the result operand) because they don't match
        // in all cases. For example, an object constant can be loaded to a long register when unsafe casts occurred (e.g.,
        // for a write barrier where arithmetic operations are then performed on the pointer).
        switch (input.getKind().getStackKind()) {
            case Jsr:
            case Int:
                if (tasm.runtime.needsDataPatch(input)) {
                    tasm.recordDataReferenceInCode(input, 0, true);
                }
                masm.mov_s32(asRegister(result), input.asInt());
                break;
//            case Long:
//                if (tasm.runtime.needsDataPatch(input)) {
//                    tasm.recordDataReferenceInCode(input, 0, true);
//                }
//                // Do not optimize with an XOR as this instruction may be between
//                // a CMP and a Jcc in which case the XOR will modify the condition
//                // flags and interfere with the Jcc.
//                masm.movq(asRegister(result), input.asLong());
//                break;
//            case Float:
//                // This is *not* the same as 'constant == 0.0f' in the case where constant is -0.0f
//                if (Float.floatToRawIntBits(input.asFloat()) == Float.floatToRawIntBits(0.0f)) {
//                    assert !tasm.runtime.needsDataPatch(input);
//                    masm.xorps(asFloatReg(result), asFloatReg(result));
//                } else {
//                    masm.movflt(asFloatReg(result), tasm.asFloatConstRef(input));
//                }
//                break;
//            case Double:
//                // This is *not* the same as 'constant == 0.0d' in the case where constant is -0.0d
//                if (Double.doubleToRawLongBits(input.asDouble()) == Double.doubleToRawLongBits(0.0d)) {
//                    assert !tasm.runtime.needsDataPatch(input);
//                    masm.xorpd(asDoubleReg(result), asDoubleReg(result));
//                } else {
//                    masm.movdbl(asDoubleReg(result), tasm.asDoubleConstRef(input));
//                }
//                break;
//            case Object:
//                // Do not optimize with an XOR as this instruction may be between
//                // a CMP and a Jcc in which case the XOR will modify the condition
//                // flags and interfere with the Jcc.
//                if (input.isNull()) {
//                    masm.movq(asRegister(result), 0x0L);
//                } else if (tasm.target.inlineObjects) {
//                    tasm.recordDataReferenceInCode(input, 0, true);
//                    masm.movq(asRegister(result), 0xDEADDEADDEADDEADL);
//                } else {
//                    masm.movq(asRegister(result), tasm.recordDataReferenceInCode(input, 0, false));
//                }
//                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    private static void const2stack(TargetMethodAssembler tasm, PTXMacroAssembler masm, Value result, Constant input) {
//        assert !tasm.runtime.needsDataPatch(input);
//        switch (input.getKind().getStackKind()) {
//            case Jsr:
//            case Int:    masm.movl(tasm.asAddress(result), input.asInt()); break;
//            case Long:   masm.movlong(tasm.asAddress(result), input.asLong()); break;
//            case Float:  masm.movl(tasm.asAddress(result), floatToRawIntBits(input.asFloat())); break;
//            case Double: masm.movlong(tasm.asAddress(result), doubleToRawLongBits(input.asDouble())); break;
//            case Object:
//                if (input.isNull()) {
//                    masm.movlong(tasm.asAddress(result), 0L);
//                } else {
//                    throw GraalInternalError.shouldNotReachHere("Non-null object constants must be in register");
//                }
//                break;
//            default:
//                throw GraalInternalError.shouldNotReachHere();
//        }
        throw new InternalError("NYI");
    }


    public static void load(TargetMethodAssembler tasm, PTXMacroAssembler masm, Value result, Address loadAddr, LIRFrameState info) {
//        if (info != null) {
//            tasm.recordImplicitException(masm.codeBuffer.position(), info);
//        }
        Register a = asRegister(loadAddr.getBase());
        int immOff = loadAddr.getDisplacement();
        switch (loadAddr.getKind()) {
//            case Boolean:
//            case Byte:   masm.movsxb(asRegister(result),  loadAddr); break;
//            case Char:   masm.movzxl(asRegister(result),  loadAddr); break;
//            case Short:  masm.movswl(asRegister(result),  loadAddr); break;
            case Int:    masm.ld_global_s32(asRegister(result),   a, immOff); break;
//            case Long:   masm.movq(asRegister(result),    loadAddr); break;
//            case Float:  masm.movflt(asFloatReg(result),  loadAddr); break;
//            case Double: masm.movdbl(asDoubleReg(result), loadAddr); break;
            case Object: masm.ld_global_u32(asRegister(result),   a, immOff); break;
            default:     throw GraalInternalError.shouldNotReachHere();
        }
    }

    public static void store(TargetMethodAssembler tasm, PTXMacroAssembler masm, Address storeAddr, Value input, LIRFrameState info) {
//        if (info != null) {
//            tasm.recordImplicitException(masm.codeBuffer.position(), info);
//        }
//
        Register a = asRegister(storeAddr.getBase());
        int immOff = storeAddr.getDisplacement();
        System.out.println("store: "+storeAddr);
        if (isRegister(input)) {
            switch (storeAddr.getKind()) {
//                case Boolean:
//                case Byte:   masm.movb(storeAddr,   asRegister(input)); break;
//                case Char:
//                case Short:  masm.movw(storeAddr,   asRegister(input)); break;
                case Int:    masm.st_global_s32(a, immOff,   asRegister(input)); break;
//                case Long:   masm.movq(storeAddr,   asRegister(input)); break;
//                case Float:  masm.movflt(storeAddr, asFloatReg(input)); break;
//                case Double: masm.movsd(storeAddr,  asDoubleReg(input)); break;
//                case Object: masm.movq(storeAddr,   asRegister(input)); break;
                default:     throw GraalInternalError.shouldNotReachHere();
            }
        } else if (isConstant(input)) {
            Constant c = (Constant) input;
            switch (storeAddr.getKind()) {
//                case Boolean:
//                case Byte:   masm.movb(storeAddr, c.asInt() & 0xFF); break;
//                case Char:
//                case Short:  masm.movw(storeAddr, c.asInt() & 0xFFFF); break;
//                case Jsr:
//                case Int:    masm.st_st32(a, immOff, c.asInt()); break;
//                case Long:
//                    if (NumUtil.isInt(c.asLong())) {
//                        masm.movslq(storeAddr, (int) c.asLong());
//                    } else {
//                        throw GraalInternalError.shouldNotReachHere("Cannot store 64-bit constants to memory");
//                    }
//                    break;
//                case Float:  masm.movl(storeAddr, floatToRawIntBits(c.asFloat())); break;
//                case Double: throw GraalInternalError.shouldNotReachHere("Cannot store 64-bit constants to memory");
//                case Object:
//                    if (c.isNull()) {
//                        masm.movptr(storeAddr, 0);
//                    } else {
//                        throw GraalInternalError.shouldNotReachHere("Cannot store 64-bit constants to memory");
//                    }
//                    break;
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }

        } else {
            throw GraalInternalError.shouldNotReachHere();
        }
    }

    protected static void compareAndSwap(TargetMethodAssembler tasm, PTXMacroAssembler masm, Value result, Address address, Value cmpValue, Value newValue) {
//        assert asRegister(cmpValue) == PTX.rax && asRegister(result) == PTX.rax;
//
//        if (tasm.target.isMP) {
//            masm.lock();
//        }
//        switch (cmpValue.getKind()) {
//            case Int:    masm.cmpxchgl(asRegister(newValue), address); break;
//            case Long:
//            case Object: masm.cmpxchgq(asRegister(newValue), address); break;
//            default:     throw GraalInternalError.shouldNotReachHere();
//        }
        throw new InternalError("NYI");
    }
}
