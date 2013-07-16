/*
 * Copyright (c) 2011, 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.lir.amd64;

import static com.oracle.graal.api.code.ValueUtil.*;
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.*;

import static java.lang.Double.*;
import static java.lang.Float.*;

import com.oracle.graal.amd64.*;
import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.asm.amd64.*;
import com.oracle.graal.asm.amd64.AMD64Assembler.ConditionFlag;
import com.oracle.graal.graph.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.StandardOp.MoveOp;
import com.oracle.graal.lir.asm.*;

public class AMD64Move {

    @Opcode("MOVE")
    public static class MoveToRegOp extends AMD64LIRInstruction implements MoveOp {

        @Def({REG, HINT}) protected AllocatableValue result;
        @Use({REG, STACK, CONST}) protected Value input;

        public MoveToRegOp(AllocatableValue result, Value input) {
            this.result = result;
            this.input = input;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            move(tasm, masm, getResult(), getInput());
        }

        @Override
        public Value getInput() {
            return input;
        }

        @Override
        public AllocatableValue getResult() {
            return result;
        }
    }

    @Opcode("MOVE")
    public static class MoveFromRegOp extends AMD64LIRInstruction implements MoveOp {

        @Def({REG, STACK}) protected AllocatableValue result;
        @Use({REG, CONST, HINT}) protected Value input;

        public MoveFromRegOp(AllocatableValue result, Value input) {
            this.result = result;
            this.input = input;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            move(tasm, masm, getResult(), getInput());
        }

        @Override
        public Value getInput() {
            return input;
        }

        @Override
        public AllocatableValue getResult() {
            return result;
        }
    }

    public abstract static class MemOp extends AMD64LIRInstruction {

        protected final Kind kind;
        @Use({COMPOSITE}) protected AMD64AddressValue address;
        @State protected LIRFrameState state;

        public MemOp(Kind kind, AMD64AddressValue address, LIRFrameState state) {
            this.kind = kind;
            this.address = address;
            this.state = state;
        }

        protected abstract void emitMemAccess(AMD64MacroAssembler masm);

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            if (state != null) {
                tasm.recordImplicitException(masm.codeBuffer.position(), state);
            }
            emitMemAccess(masm);
        }
    }

    public static class LoadCompressedPointer extends LoadOp {

        private long base;
        private int shift;
        private int alignment;

        public LoadCompressedPointer(Kind kind, AllocatableValue result, AMD64AddressValue address, LIRFrameState state, long base, int shift, int alignment) {
            super(kind, result, address, state);
            this.base = base;
            this.shift = shift;
            this.alignment = alignment;
            assert kind == Kind.Object || kind == Kind.Long;
        }

        @Override
        public void emitMemAccess(AMD64MacroAssembler masm) {
            Register resRegister = asRegister(result);
            masm.movl(resRegister, address.toAddress());
            if (kind == Kind.Object) {
                decodePointer(masm, resRegister, base, shift, alignment);
            } else {
                decodeKlassPointer(masm, resRegister, base, shift, alignment);
            }
        }
    }

    public static class LoadOp extends MemOp {

        @Def({REG}) protected AllocatableValue result;

        public LoadOp(Kind kind, AllocatableValue result, AMD64AddressValue address, LIRFrameState state) {
            super(kind, address, state);
            this.result = result;
        }

        @Override
        public void emitMemAccess(AMD64MacroAssembler masm) {
            switch (kind) {
                case Boolean:
                case Byte:
                    masm.movsxb(asRegister(result), address.toAddress());
                    break;
                case Char:
                    masm.movzxl(asRegister(result), address.toAddress());
                    break;
                case Short:
                    masm.movswl(asRegister(result), address.toAddress());
                    break;
                case Int:
                    masm.movslq(asRegister(result), address.toAddress());
                    break;
                case Long:
                    masm.movq(asRegister(result), address.toAddress());
                    break;
                case Float:
                    masm.movflt(asFloatReg(result), address.toAddress());
                    break;
                case Double:
                    masm.movdbl(asDoubleReg(result), address.toAddress());
                    break;
                case Object:
                    masm.movq(asRegister(result), address.toAddress());
                    break;
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        }
    }

    public static class StoreCompressedPointer extends AMD64LIRInstruction {

        protected final Kind kind;
        private long base;
        private int shift;
        private int alignment;
        @Temp({REG}) private AllocatableValue scratch;
        @Alive({REG}) protected AllocatableValue input;
        @Alive({COMPOSITE}) protected AMD64AddressValue address;
        @State protected LIRFrameState state;

        public StoreCompressedPointer(Kind kind, AMD64AddressValue address, AllocatableValue input, AllocatableValue scratch, LIRFrameState state, long base, int shift, int alignment) {
            this.base = base;
            this.shift = shift;
            this.alignment = alignment;
            this.scratch = scratch;
            this.kind = kind;
            this.address = address;
            this.state = state;
            this.input = input;
            assert kind == Kind.Object || kind == Kind.Long;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            masm.movq(asRegister(scratch), asRegister(input));
            if (kind == Kind.Object) {
                encodePointer(masm, asRegister(scratch), base, shift, alignment);
            } else {
                encodeKlassPointer(masm, asRegister(scratch), base, shift, alignment);
            }
            if (state != null) {
                tasm.recordImplicitException(masm.codeBuffer.position(), state);
            }
            masm.movl(address.toAddress(), asRegister(scratch));
        }
    }

    public static class StoreOp extends MemOp {

        @Use({REG}) protected AllocatableValue input;

        public StoreOp(Kind kind, AMD64AddressValue address, AllocatableValue input, LIRFrameState state) {
            super(kind, address, state);
            this.input = input;
        }

        @Override
        public void emitMemAccess(AMD64MacroAssembler masm) {
            assert isRegister(input);
            switch (kind) {
                case Boolean:
                case Byte:
                    masm.movb(address.toAddress(), asRegister(input));
                    break;
                case Char:
                case Short:
                    masm.movw(address.toAddress(), asRegister(input));
                    break;
                case Int:
                    masm.movl(address.toAddress(), asRegister(input));
                    break;
                case Long:
                    masm.movq(address.toAddress(), asRegister(input));
                    break;
                case Float:
                    masm.movflt(address.toAddress(), asFloatReg(input));
                    break;
                case Double:
                    masm.movsd(address.toAddress(), asDoubleReg(input));
                    break;
                case Object:
                    masm.movq(address.toAddress(), asRegister(input));
                    break;
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        }
    }

    public static class StoreConstantOp extends MemOp {

        protected final Constant input;
        private final boolean compress;

        public StoreConstantOp(Kind kind, AMD64AddressValue address, Constant input, LIRFrameState state, boolean compress) {
            super(kind, address, state);
            this.input = input;
            this.compress = compress;
        }

        @Override
        public void emitMemAccess(AMD64MacroAssembler masm) {
            switch (kind) {
                case Boolean:
                case Byte:
                    masm.movb(address.toAddress(), input.asInt() & 0xFF);
                    break;
                case Char:
                case Short:
                    masm.movw(address.toAddress(), input.asInt() & 0xFFFF);
                    break;
                case Int:
                    masm.movl(address.toAddress(), input.asInt());
                    break;
                case Long:
                    if (NumUtil.isInt(input.asLong())) {
                        if (compress) {
                            masm.movl(address.toAddress(), (int) input.asLong());
                        } else {
                            masm.movslq(address.toAddress(), (int) input.asLong());
                        }
                    } else {
                        throw GraalInternalError.shouldNotReachHere("Cannot store 64-bit constants to memory");
                    }
                    break;
                case Float:
                    masm.movl(address.toAddress(), floatToRawIntBits(input.asFloat()));
                    break;
                case Double:
                    throw GraalInternalError.shouldNotReachHere("Cannot store 64-bit constants to memory");
                case Object:
                    if (input.isNull()) {
                        if (compress) {
                            masm.movl(address.toAddress(), 0);
                        } else {
                            masm.movptr(address.toAddress(), 0);
                        }
                    } else {
                        throw GraalInternalError.shouldNotReachHere("Cannot store 64-bit constants to memory");
                    }
                    break;
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }
        }
    }

    public static class LeaOp extends AMD64LIRInstruction {

        @Def({REG}) protected AllocatableValue result;
        @Use({COMPOSITE, UNINITIALIZED}) protected AMD64AddressValue address;

        public LeaOp(AllocatableValue result, AMD64AddressValue address) {
            this.result = result;
            this.address = address;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            masm.leaq(asLongReg(result), address.toAddress());
        }
    }

    public static class StackLeaOp extends AMD64LIRInstruction {

        @Def({REG}) protected AllocatableValue result;
        @Use({STACK, UNINITIALIZED}) protected StackSlot slot;

        public StackLeaOp(AllocatableValue result, StackSlot slot) {
            this.result = result;
            this.slot = slot;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            masm.leaq(asLongReg(result), (AMD64Address) tasm.asAddress(slot));
        }
    }

    public static class MembarOp extends AMD64LIRInstruction {

        private final int barriers;

        public MembarOp(final int barriers) {
            this.barriers = barriers;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            masm.membar(barriers);
        }
    }

    public static class NullCheckOp extends AMD64LIRInstruction {

        @Use({REG}) protected AllocatableValue input;
        @State protected LIRFrameState state;

        public NullCheckOp(Variable input, LIRFrameState state) {
            this.input = input;
            this.state = state;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            tasm.recordImplicitException(masm.codeBuffer.position(), state);
            masm.nullCheck(asRegister(input));
        }
    }

    @Opcode("CAS")
    public static class CompareAndSwapOp extends AMD64LIRInstruction {

        @Def protected AllocatableValue result;
        @Use({COMPOSITE}) protected AMD64AddressValue address;
        @Use protected AllocatableValue cmpValue;
        @Use protected AllocatableValue newValue;

        public CompareAndSwapOp(AllocatableValue result, AMD64AddressValue address, AllocatableValue cmpValue, AllocatableValue newValue) {
            this.result = result;
            this.address = address;
            this.cmpValue = cmpValue;
            this.newValue = newValue;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            compareAndSwap(tasm, masm, result, address, cmpValue, newValue);
        }
    }

    @Opcode("CAS")
    public static class CompareAndSwapCompressedOp extends AMD64LIRInstruction {

        @Def protected AllocatableValue result;
        @Alive({COMPOSITE}) protected AMD64AddressValue address;
        @Alive protected AllocatableValue cmpValue;
        @Alive protected AllocatableValue newValue;
        @Temp({REG}) protected AllocatableValue scratch;

        private long base;
        private int shift;
        private int alignment;

        public CompareAndSwapCompressedOp(AllocatableValue result, AMD64AddressValue address, AllocatableValue cmpValue, AllocatableValue newValue, AllocatableValue scratch, long base, int shift,
                        int alignment) {
            this.base = base;
            this.shift = shift;
            this.alignment = alignment;
            this.scratch = scratch;
            this.result = result;
            this.address = address;
            this.cmpValue = cmpValue;
            this.newValue = newValue;
            assert cmpValue.getKind() == Kind.Object;
        }

        @Override
        public void emitCode(TargetMethodAssembler tasm, AMD64MacroAssembler masm) {
            compareAndSwapCompressed(tasm, masm, result, address, cmpValue, newValue, scratch, base, shift, alignment);
        }
    }

    public static void move(TargetMethodAssembler tasm, AMD64MacroAssembler masm, Value result, Value input) {
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

    private static void reg2reg(AMD64MacroAssembler masm, Value result, Value input) {
        if (asRegister(input).equals(asRegister(result))) {
            return;
        }
        switch (input.getKind()) {
            case Int:
                masm.movl(asRegister(result), asRegister(input));
                break;
            case Long:
                masm.movq(asRegister(result), asRegister(input));
                break;
            case Float:
                masm.movflt(asFloatReg(result), asFloatReg(input));
                break;
            case Double:
                masm.movdbl(asDoubleReg(result), asDoubleReg(input));
                break;
            case Object:
                masm.movq(asRegister(result), asRegister(input));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere("kind=" + result.getKind());
        }
    }

    private static void reg2stack(TargetMethodAssembler tasm, AMD64MacroAssembler masm, Value result, Value input) {
        AMD64Address dest = (AMD64Address) tasm.asAddress(result);
        switch (input.getKind()) {
            case Int:
                masm.movl(dest, asRegister(input));
                break;
            case Long:
                masm.movq(dest, asRegister(input));
                break;
            case Float:
                masm.movflt(dest, asFloatReg(input));
                break;
            case Double:
                masm.movsd(dest, asDoubleReg(input));
                break;
            case Object:
                masm.movq(dest, asRegister(input));
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    private static void stack2reg(TargetMethodAssembler tasm, AMD64MacroAssembler masm, Value result, Value input) {
        AMD64Address src = (AMD64Address) tasm.asAddress(input);
        switch (input.getKind()) {
            case Int:
                masm.movl(asRegister(result), src);
                break;
            case Long:
                masm.movq(asRegister(result), src);
                break;
            case Float:
                masm.movflt(asFloatReg(result), src);
                break;
            case Double:
                masm.movdbl(asDoubleReg(result), src);
                break;
            case Object:
                masm.movq(asRegister(result), src);
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    private static void const2reg(TargetMethodAssembler tasm, AMD64MacroAssembler masm, Value result, Constant input) {
        /*
         * Note: we use the kind of the input operand (and not the kind of the result operand)
         * because they don't match in all cases. For example, an object constant can be loaded to a
         * long register when unsafe casts occurred (e.g., for a write barrier where arithmetic
         * operations are then performed on the pointer).
         */
        switch (input.getKind().getStackKind()) {
            case Int:
                if (tasm.runtime.needsDataPatch(input)) {
                    tasm.recordDataReferenceInCode(input, 0, true);
                }
                // Do not optimize with an XOR as this instruction may be between
                // a CMP and a Jcc in which case the XOR will modify the condition
                // flags and interfere with the Jcc.
                masm.movl(asRegister(result), input.asInt());

                break;
            case Long:
                if (tasm.runtime.needsDataPatch(input)) {
                    tasm.recordDataReferenceInCode(input, 0, true);
                }
                // Do not optimize with an XOR as this instruction may be between
                // a CMP and a Jcc in which case the XOR will modify the condition
                // flags and interfere with the Jcc.
                masm.movq(asRegister(result), input.asLong());
                break;
            case Float:
                // This is *not* the same as 'constant == 0.0f' in the case where constant is -0.0f
                if (Float.floatToRawIntBits(input.asFloat()) == Float.floatToRawIntBits(0.0f)) {
                    assert !tasm.runtime.needsDataPatch(input);
                    masm.xorps(asFloatReg(result), asFloatReg(result));
                } else {
                    masm.movflt(asFloatReg(result), (AMD64Address) tasm.asFloatConstRef(input));
                }
                break;
            case Double:
                // This is *not* the same as 'constant == 0.0d' in the case where constant is -0.0d
                if (Double.doubleToRawLongBits(input.asDouble()) == Double.doubleToRawLongBits(0.0d)) {
                    assert !tasm.runtime.needsDataPatch(input);
                    masm.xorpd(asDoubleReg(result), asDoubleReg(result));
                } else {
                    masm.movdbl(asDoubleReg(result), (AMD64Address) tasm.asDoubleConstRef(input));
                }
                break;
            case Object:
                // Do not optimize with an XOR as this instruction may be between
                // a CMP and a Jcc in which case the XOR will modify the condition
                // flags and interfere with the Jcc.
                if (input.isNull()) {
                    masm.movq(asRegister(result), 0x0L);
                } else if (tasm.target.inlineObjects) {
                    tasm.recordDataReferenceInCode(input, 0, true);
                    masm.movq(asRegister(result), 0xDEADDEADDEADDEADL);
                } else {
                    masm.movq(asRegister(result), (AMD64Address) tasm.recordDataReferenceInCode(input, 0, false));
                }
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    private static void const2stack(TargetMethodAssembler tasm, AMD64MacroAssembler masm, Value result, Constant input) {
        assert !tasm.runtime.needsDataPatch(input);
        AMD64Address dest = (AMD64Address) tasm.asAddress(result);
        switch (input.getKind().getStackKind()) {
            case Int:
                masm.movl(dest, input.asInt());
                break;
            case Long:
                masm.movlong(dest, input.asLong());
                break;
            case Float:
                masm.movl(dest, floatToRawIntBits(input.asFloat()));
                break;
            case Double:
                masm.movlong(dest, doubleToRawLongBits(input.asDouble()));
                break;
            case Object:
                if (input.isNull()) {
                    masm.movlong(dest, 0L);
                } else {
                    throw GraalInternalError.shouldNotReachHere("Non-null object constants must be in register");
                }
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    protected static void compareAndSwap(TargetMethodAssembler tasm, AMD64MacroAssembler masm, AllocatableValue result, AMD64AddressValue address, AllocatableValue cmpValue, AllocatableValue newValue) {
        assert asRegister(cmpValue).equals(AMD64.rax) && asRegister(result).equals(AMD64.rax);

        if (tasm.target.isMP) {
            masm.lock();
        }
        switch (cmpValue.getKind()) {
            case Int:
                masm.cmpxchgl(asRegister(newValue), address.toAddress());
                break;
            case Long:
            case Object:
                masm.cmpxchgq(asRegister(newValue), address.toAddress());
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    protected static void compareAndSwapCompressed(TargetMethodAssembler tasm, AMD64MacroAssembler masm, AllocatableValue result, AMD64AddressValue address, AllocatableValue cmpValue,
                    AllocatableValue newValue, AllocatableValue scratch, long base, int shift, int alignment) {
        assert AMD64.rax.equals(asRegister(cmpValue)) && AMD64.rax.equals(asRegister(result));
        final Register scratchRegister = asRegister(scratch);
        final Register cmpRegister = asRegister(cmpValue);
        final Register newRegister = asRegister(newValue);
        encodePointer(masm, cmpRegister, base, shift, alignment);
        masm.movq(scratchRegister, newRegister);
        encodePointer(masm, scratchRegister, base, shift, alignment);
        if (tasm.target.isMP) {
            masm.lock();
        }
        masm.cmpxchgl(scratchRegister, address.toAddress());
    }

    private static void encodePointer(AMD64MacroAssembler masm, Register scratchRegister, long base, int shift, int alignment) {
        // If the base is zero, the uncompressed address has to be shifted right
        // in order to be compressed.
        if (base == 0) {
            if (shift != 0) {
                assert alignment == shift : "Encode algorithm is wrong";
                masm.shrq(scratchRegister, alignment);
            }
        } else {
            // Otherwise the heap base, which resides always in register 12, is subtracted
            // followed by right shift.
            masm.testq(scratchRegister, scratchRegister);
            // If the stored reference is null, move the heap to scratch
            // register and then calculate the compressed oop value.
            masm.cmovq(ConditionFlag.Equal, scratchRegister, AMD64.r12);
            masm.subq(scratchRegister, AMD64.r12);
            masm.shrq(scratchRegister, alignment);
        }
    }

    private static void decodePointer(AMD64MacroAssembler masm, Register resRegister, long base, int shift, int alignment) {
        // If the base is zero, the compressed address has to be shifted left
        // in order to be uncompressed.
        if (base == 0) {
            if (shift != 0) {
                assert alignment == shift : "Decode algorithm is wrong";
                masm.shlq(resRegister, alignment);
            }
        } else {
            Label done = new Label();
            masm.shlq(resRegister, alignment);
            masm.jccb(ConditionFlag.Equal, done);
            // Otherwise the heap base is added to the shifted address.
            masm.addq(resRegister, AMD64.r12);
            masm.bind(done);
        }
    }

    private static void encodeKlassPointer(AMD64MacroAssembler masm, Register scratchRegister, long base, int shift, int alignment) {
        if (base != 0) {
            masm.subq(scratchRegister, AMD64.r12);
        }
        if (shift != 0) {
            assert alignment == shift : "Encode algorithm is wrong";
            masm.shrq(scratchRegister, alignment);
        }
    }

    private static void decodeKlassPointer(AMD64MacroAssembler masm, Register resRegister, long base, int shift, int alignment) {
        if (shift != 0) {
            assert alignment == shift : "Decode algorighm is wrong";
            masm.shlq(resRegister, alignment);
            if (base != 0) {
                masm.addq(resRegister, AMD64.r12);
            }
        } else {
            assert base == 0 : "Sanity";
        }
    }

    public static void decodeKlassPointer(AMD64MacroAssembler masm, Register register, AMD64Address address, long narrowKlassBase, int narrowKlassShift, int logKlassAlignment) {
        masm.movl(register, address);
        decodeKlassPointer(masm, register, narrowKlassBase, narrowKlassShift, logKlassAlignment);
    }
}
