/*
 * Copyright (c) 2011, 2015, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.lir.LIRInstruction.OperandFlag.COMPOSITE;
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.HINT;
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.REG;
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.STACK;
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.UNINITIALIZED;
import static com.oracle.graal.lir.LIRValueUtil.asJavaConstant;
import static com.oracle.graal.lir.LIRValueUtil.isJavaConstant;
import static java.lang.Double.doubleToRawLongBits;
import static java.lang.Float.floatToRawIntBits;
import static jdk.internal.jvmci.code.ValueUtil.asRegister;
import static jdk.internal.jvmci.code.ValueUtil.isRegister;
import static jdk.internal.jvmci.code.ValueUtil.isStackSlot;
import static jdk.internal.jvmci.code.ValueUtil.isStackSlotValue;
import jdk.internal.jvmci.amd64.AMD64;
import jdk.internal.jvmci.code.Register;
import jdk.internal.jvmci.code.StackSlotValue;
import jdk.internal.jvmci.common.JVMCIError;
import jdk.internal.jvmci.meta.AllocatableValue;
import jdk.internal.jvmci.meta.Constant;
import jdk.internal.jvmci.meta.JavaConstant;
import jdk.internal.jvmci.meta.JavaKind;
import jdk.internal.jvmci.meta.Value;

import com.oracle.graal.asm.NumUtil;
import com.oracle.graal.asm.amd64.AMD64Address;
import com.oracle.graal.asm.amd64.AMD64Assembler.AMD64MIOp;
import com.oracle.graal.asm.amd64.AMD64Assembler.AMD64MOp;
import com.oracle.graal.asm.amd64.AMD64Assembler.OperandSize;
import com.oracle.graal.asm.amd64.AMD64MacroAssembler;
import com.oracle.graal.lir.LIRFrameState;
import com.oracle.graal.lir.LIRInstructionClass;
import com.oracle.graal.lir.Opcode;
import com.oracle.graal.lir.StandardOp.LoadConstantOp;
import com.oracle.graal.lir.StandardOp.NullCheck;
import com.oracle.graal.lir.StandardOp.ValueMoveOp;
import com.oracle.graal.lir.asm.CompilationResultBuilder;

public class AMD64Move {

    private abstract static class AbstractMoveOp extends AMD64LIRInstruction implements ValueMoveOp {
        public static final LIRInstructionClass<AbstractMoveOp> TYPE = LIRInstructionClass.create(AbstractMoveOp.class);

        private JavaKind moveKind;

        protected AbstractMoveOp(LIRInstructionClass<? extends AbstractMoveOp> c, JavaKind moveKind) {
            super(c);
            if (moveKind == JavaKind.Illegal) {
                // unknown operand size, conservatively move the whole register
                this.moveKind = JavaKind.Long;
            } else {
                this.moveKind = moveKind;
            }
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            move(moveKind, crb, masm, getResult(), getInput());
        }
    }

    @Opcode("MOVE")
    public static final class MoveToRegOp extends AbstractMoveOp {
        public static final LIRInstructionClass<MoveToRegOp> TYPE = LIRInstructionClass.create(MoveToRegOp.class);

        @Def({REG, HINT}) protected AllocatableValue result;
        @Use({REG, STACK}) protected AllocatableValue input;

        public MoveToRegOp(JavaKind moveKind, AllocatableValue result, AllocatableValue input) {
            super(TYPE, moveKind);
            this.result = result;
            this.input = input;
        }

        @Override
        public AllocatableValue getInput() {
            return input;
        }

        @Override
        public AllocatableValue getResult() {
            return result;
        }
    }

    @Opcode("MOVE")
    public static final class MoveFromRegOp extends AbstractMoveOp {
        public static final LIRInstructionClass<MoveFromRegOp> TYPE = LIRInstructionClass.create(MoveFromRegOp.class);

        @Def({REG, STACK}) protected AllocatableValue result;
        @Use({REG, HINT}) protected AllocatableValue input;

        public MoveFromRegOp(JavaKind moveKind, AllocatableValue result, AllocatableValue input) {
            super(TYPE, moveKind);
            this.result = result;
            this.input = input;
        }

        @Override
        public AllocatableValue getInput() {
            return input;
        }

        @Override
        public AllocatableValue getResult() {
            return result;
        }
    }

    @Opcode("MOVE")
    public static class MoveFromConstOp extends AMD64LIRInstruction implements LoadConstantOp {
        public static final LIRInstructionClass<MoveFromConstOp> TYPE = LIRInstructionClass.create(MoveFromConstOp.class);

        @Def({REG, STACK}) protected AllocatableValue result;
        private final JavaConstant input;

        public MoveFromConstOp(AllocatableValue result, JavaConstant input) {
            super(TYPE);
            this.result = result;
            this.input = input;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            if (isRegister(result)) {
                const2reg(crb, masm, result, input);
            } else {
                assert isStackSlot(result);
                const2stack(crb, masm, result, input);
            }
        }

        public Constant getConstant() {
            return input;
        }

        public AllocatableValue getResult() {
            return result;
        }
    }

    @Opcode("STACKMOVE")
    public static final class AMD64StackMove extends AMD64LIRInstruction implements ValueMoveOp {
        public static final LIRInstructionClass<AMD64StackMove> TYPE = LIRInstructionClass.create(AMD64StackMove.class);

        @Def({STACK}) protected AllocatableValue result;
        @Use({STACK, HINT}) protected AllocatableValue input;
        @Alive({OperandFlag.STACK, OperandFlag.UNINITIALIZED}) private StackSlotValue backupSlot;

        private Register scratch;

        public AMD64StackMove(AllocatableValue result, AllocatableValue input, Register scratch, StackSlotValue backupSlot) {
            super(TYPE);
            this.result = result;
            this.input = input;
            this.backupSlot = backupSlot;
            this.scratch = scratch;
        }

        @Override
        public AllocatableValue getInput() {
            return input;
        }

        @Override
        public AllocatableValue getResult() {
            return result;
        }

        public Register getScratchRegister() {
            return scratch;
        }

        public StackSlotValue getBackupSlot() {
            return backupSlot;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            // backup scratch register
            move((JavaKind) backupSlot.getPlatformKind(), crb, masm, backupSlot, scratch.asValue(backupSlot.getLIRKind()));
            // move stack slot
            move((JavaKind) getInput().getPlatformKind(), crb, masm, scratch.asValue(getInput().getLIRKind()), getInput());
            move((JavaKind) getResult().getPlatformKind(), crb, masm, getResult(), scratch.asValue(getResult().getLIRKind()));
            // restore scratch register
            move((JavaKind) backupSlot.getPlatformKind(), crb, masm, scratch.asValue(backupSlot.getLIRKind()), backupSlot);

        }
    }

    @Opcode("MULTISTACKMOVE")
    public static final class AMD64MultiStackMove extends AMD64LIRInstruction {
        public static final LIRInstructionClass<AMD64MultiStackMove> TYPE = LIRInstructionClass.create(AMD64MultiStackMove.class);

        @Def({STACK}) protected AllocatableValue[] results;
        @Use({STACK}) protected Value[] inputs;
        @Alive({OperandFlag.STACK, OperandFlag.UNINITIALIZED}) private StackSlotValue backupSlot;

        private Register scratch;

        public AMD64MultiStackMove(AllocatableValue[] results, Value[] inputs, Register scratch, StackSlotValue backupSlot) {
            super(TYPE);
            this.results = results;
            this.inputs = inputs;
            this.backupSlot = backupSlot;
            this.scratch = scratch;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            // backup scratch register
            move((JavaKind) backupSlot.getPlatformKind(), crb, masm, backupSlot, scratch.asValue(backupSlot.getLIRKind()));
            for (int i = 0; i < results.length; i++) {
                Value input = inputs[i];
                AllocatableValue result = results[i];
                // move stack slot
                move((JavaKind) input.getPlatformKind(), crb, masm, scratch.asValue(input.getLIRKind()), input);
                move((JavaKind) result.getPlatformKind(), crb, masm, result, scratch.asValue(result.getLIRKind()));
            }
            // restore scratch register
            move((JavaKind) backupSlot.getPlatformKind(), crb, masm, scratch.asValue(backupSlot.getLIRKind()), backupSlot);

        }
    }

    @Opcode("STACKMOVE")
    public static final class AMD64PushPopStackMove extends AMD64LIRInstruction implements ValueMoveOp {
        public static final LIRInstructionClass<AMD64PushPopStackMove> TYPE = LIRInstructionClass.create(AMD64PushPopStackMove.class);

        @Def({STACK}) protected AllocatableValue result;
        @Use({STACK, HINT}) protected AllocatableValue input;
        private final OperandSize size;

        public AMD64PushPopStackMove(OperandSize size, AllocatableValue result, AllocatableValue input) {
            super(TYPE);
            this.result = result;
            this.input = input;
            this.size = size;
        }

        @Override
        public AllocatableValue getInput() {
            return input;
        }

        @Override
        public AllocatableValue getResult() {
            return result;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            AMD64MOp.PUSH.emit(masm, size, (AMD64Address) crb.asAddress(input));
            AMD64MOp.POP.emit(masm, size, (AMD64Address) crb.asAddress(result));
        }
    }

    public static final class LeaOp extends AMD64LIRInstruction {
        public static final LIRInstructionClass<LeaOp> TYPE = LIRInstructionClass.create(LeaOp.class);

        @Def({REG}) protected AllocatableValue result;
        @Use({COMPOSITE, UNINITIALIZED}) protected AMD64AddressValue address;

        public LeaOp(AllocatableValue result, AMD64AddressValue address) {
            super(TYPE);
            this.result = result;
            this.address = address;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            masm.leaq(asRegister(result, JavaKind.Long), address.toAddress());
        }
    }

    public static final class LeaDataOp extends AMD64LIRInstruction {
        public static final LIRInstructionClass<LeaDataOp> TYPE = LIRInstructionClass.create(LeaDataOp.class);

        @Def({REG}) protected AllocatableValue result;
        private final byte[] data;

        public LeaDataOp(AllocatableValue result, byte[] data) {
            super(TYPE);
            this.result = result;
            this.data = data;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            masm.leaq(asRegister(result), (AMD64Address) crb.recordDataReferenceInCode(data, 16));
        }
    }

    public static final class StackLeaOp extends AMD64LIRInstruction {
        public static final LIRInstructionClass<StackLeaOp> TYPE = LIRInstructionClass.create(StackLeaOp.class);

        @Def({REG}) protected AllocatableValue result;
        @Use({STACK, UNINITIALIZED}) protected StackSlotValue slot;

        public StackLeaOp(AllocatableValue result, StackSlotValue slot) {
            super(TYPE);
            assert isStackSlotValue(slot) : "Not a stack slot: " + slot;
            this.result = result;
            this.slot = slot;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            masm.leaq(asRegister(result, JavaKind.Long), (AMD64Address) crb.asAddress(slot));
        }
    }

    public static final class MembarOp extends AMD64LIRInstruction {
        public static final LIRInstructionClass<MembarOp> TYPE = LIRInstructionClass.create(MembarOp.class);

        private final int barriers;

        public MembarOp(final int barriers) {
            super(TYPE);
            this.barriers = barriers;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            masm.membar(barriers);
        }
    }

    public static final class NullCheckOp extends AMD64LIRInstruction implements NullCheck {
        public static final LIRInstructionClass<NullCheckOp> TYPE = LIRInstructionClass.create(NullCheckOp.class);

        @Use({COMPOSITE}) protected AMD64AddressValue address;
        @State protected LIRFrameState state;

        public NullCheckOp(AMD64AddressValue address, LIRFrameState state) {
            super(TYPE);
            this.address = address;
            this.state = state;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            crb.recordImplicitException(masm.position(), state);
            masm.nullCheck(address.toAddress());
        }

        public Value getCheckedValue() {
            return address.base;
        }

        public LIRFrameState getState() {
            return state;
        }
    }

    @Opcode("CAS")
    public static final class CompareAndSwapOp extends AMD64LIRInstruction {
        public static final LIRInstructionClass<CompareAndSwapOp> TYPE = LIRInstructionClass.create(CompareAndSwapOp.class);

        private final JavaKind accessKind;

        @Def protected AllocatableValue result;
        @Use({COMPOSITE}) protected AMD64AddressValue address;
        @Use protected AllocatableValue cmpValue;
        @Use protected AllocatableValue newValue;

        public CompareAndSwapOp(JavaKind accessKind, AllocatableValue result, AMD64AddressValue address, AllocatableValue cmpValue, AllocatableValue newValue) {
            super(TYPE);
            this.accessKind = accessKind;
            this.result = result;
            this.address = address;
            this.cmpValue = cmpValue;
            this.newValue = newValue;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            assert asRegister(cmpValue).equals(AMD64.rax) && asRegister(result).equals(AMD64.rax);

            if (crb.target.isMP) {
                masm.lock();
            }
            switch (accessKind) {
                case Int:
                    masm.cmpxchgl(asRegister(newValue), address.toAddress());
                    break;
                case Long:
                case Object:
                    masm.cmpxchgq(asRegister(newValue), address.toAddress());
                    break;
                default:
                    throw JVMCIError.shouldNotReachHere();
            }
        }
    }

    @Opcode("ATOMIC_READ_AND_ADD")
    public static final class AtomicReadAndAddOp extends AMD64LIRInstruction {
        public static final LIRInstructionClass<AtomicReadAndAddOp> TYPE = LIRInstructionClass.create(AtomicReadAndAddOp.class);

        private final JavaKind accessKind;

        @Def protected AllocatableValue result;
        @Alive({COMPOSITE}) protected AMD64AddressValue address;
        @Use protected AllocatableValue delta;

        public AtomicReadAndAddOp(JavaKind accessKind, AllocatableValue result, AMD64AddressValue address, AllocatableValue delta) {
            super(TYPE);
            this.accessKind = accessKind;
            this.result = result;
            this.address = address;
            this.delta = delta;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            move(accessKind, crb, masm, result, delta);
            if (crb.target.isMP) {
                masm.lock();
            }
            switch (accessKind) {
                case Int:
                    masm.xaddl(address.toAddress(), asRegister(result));
                    break;
                case Long:
                    masm.xaddq(address.toAddress(), asRegister(result));
                    break;
                default:
                    throw JVMCIError.shouldNotReachHere();
            }
        }
    }

    @Opcode("ATOMIC_READ_AND_WRITE")
    public static final class AtomicReadAndWriteOp extends AMD64LIRInstruction {
        public static final LIRInstructionClass<AtomicReadAndWriteOp> TYPE = LIRInstructionClass.create(AtomicReadAndWriteOp.class);

        private final JavaKind accessKind;

        @Def protected AllocatableValue result;
        @Alive({COMPOSITE}) protected AMD64AddressValue address;
        @Use protected AllocatableValue newValue;

        public AtomicReadAndWriteOp(JavaKind accessKind, AllocatableValue result, AMD64AddressValue address, AllocatableValue newValue) {
            super(TYPE);
            this.accessKind = accessKind;
            this.result = result;
            this.address = address;
            this.newValue = newValue;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            move(accessKind, crb, masm, result, newValue);
            switch (accessKind) {
                case Int:
                    masm.xchgl(asRegister(result), address.toAddress());
                    break;
                case Long:
                case Object:
                    masm.xchgq(asRegister(result), address.toAddress());
                    break;
                default:
                    throw JVMCIError.shouldNotReachHere();
            }
        }
    }

    public static void move(CompilationResultBuilder crb, AMD64MacroAssembler masm, Value result, Value input) {
        move((JavaKind) result.getPlatformKind(), crb, masm, result, input);
    }

    public static void move(JavaKind moveKind, CompilationResultBuilder crb, AMD64MacroAssembler masm, Value result, Value input) {
        if (isRegister(input)) {
            if (isRegister(result)) {
                reg2reg(moveKind, masm, result, input);
            } else if (isStackSlot(result)) {
                reg2stack(moveKind, crb, masm, result, input);
            } else {
                throw JVMCIError.shouldNotReachHere();
            }
        } else if (isStackSlot(input)) {
            if (isRegister(result)) {
                stack2reg(moveKind, crb, masm, result, input);
            } else {
                throw JVMCIError.shouldNotReachHere();
            }
        } else if (isJavaConstant(input)) {
            if (isRegister(result)) {
                const2reg(crb, masm, result, asJavaConstant(input));
            } else if (isStackSlot(result)) {
                const2stack(crb, masm, result, asJavaConstant(input));
            } else {
                throw JVMCIError.shouldNotReachHere();
            }
        } else {
            throw JVMCIError.shouldNotReachHere();
        }
    }

    private static void reg2reg(JavaKind kind, AMD64MacroAssembler masm, Value result, Value input) {
        if (asRegister(input).equals(asRegister(result))) {
            return;
        }
        switch (kind.getStackKind()) {
            case Int:
                masm.movl(asRegister(result), asRegister(input));
                break;
            case Long:
                masm.movq(asRegister(result), asRegister(input));
                break;
            case Float:
                masm.movflt(asRegister(result, JavaKind.Float), asRegister(input, JavaKind.Float));
                break;
            case Double:
                masm.movdbl(asRegister(result, JavaKind.Double), asRegister(input, JavaKind.Double));
                break;
            case Object:
                masm.movq(asRegister(result), asRegister(input));
                break;
            default:
                throw JVMCIError.shouldNotReachHere("kind=" + result.getPlatformKind());
        }
    }

    private static void reg2stack(JavaKind kind, CompilationResultBuilder crb, AMD64MacroAssembler masm, Value result, Value input) {
        AMD64Address dest = (AMD64Address) crb.asAddress(result);
        switch (kind) {
            case Boolean:
            case Byte:
                masm.movb(dest, asRegister(input));
                break;
            case Short:
            case Char:
                masm.movw(dest, asRegister(input));
                break;
            case Int:
                masm.movl(dest, asRegister(input));
                break;
            case Long:
                masm.movq(dest, asRegister(input));
                break;
            case Float:
                masm.movflt(dest, asRegister(input, JavaKind.Float));
                break;
            case Double:
                masm.movsd(dest, asRegister(input, JavaKind.Double));
                break;
            case Object:
                masm.movq(dest, asRegister(input));
                break;
            default:
                throw JVMCIError.shouldNotReachHere();
        }
    }

    private static void stack2reg(JavaKind kind, CompilationResultBuilder crb, AMD64MacroAssembler masm, Value result, Value input) {
        AMD64Address src = (AMD64Address) crb.asAddress(input);
        switch (kind) {
            case Boolean:
                masm.movzbl(asRegister(result), src);
                break;
            case Byte:
                masm.movsbl(asRegister(result), src);
                break;
            case Short:
                masm.movswl(asRegister(result), src);
                break;
            case Char:
                masm.movzwl(asRegister(result), src);
                break;
            case Int:
                masm.movl(asRegister(result), src);
                break;
            case Long:
                masm.movq(asRegister(result), src);
                break;
            case Float:
                masm.movflt(asRegister(result, JavaKind.Float), src);
                break;
            case Double:
                masm.movdbl(asRegister(result, JavaKind.Double), src);
                break;
            case Object:
                masm.movq(asRegister(result), src);
                break;
            default:
                throw JVMCIError.shouldNotReachHere();
        }
    }

    public static void const2reg(CompilationResultBuilder crb, AMD64MacroAssembler masm, Value result, JavaConstant input) {
        /*
         * Note: we use the kind of the input operand (and not the kind of the result operand)
         * because they don't match in all cases. For example, an object constant can be loaded to a
         * long register when unsafe casts occurred (e.g., for a write barrier where arithmetic
         * operations are then performed on the pointer).
         */
        switch (input.getJavaKind().getStackKind()) {
            case Int:
                if (crb.codeCache.needsDataPatch(input)) {
                    crb.recordInlineDataInCode(input);
                }
                // Do not optimize with an XOR as this instruction may be between
                // a CMP and a Jcc in which case the XOR will modify the condition
                // flags and interfere with the Jcc.
                masm.movl(asRegister(result), input.asInt());

                break;
            case Long:
                boolean patch = false;
                if (crb.codeCache.needsDataPatch(input)) {
                    patch = true;
                    crb.recordInlineDataInCode(input);
                }
                // Do not optimize with an XOR as this instruction may be between
                // a CMP and a Jcc in which case the XOR will modify the condition
                // flags and interfere with the Jcc.
                if (patch) {
                    masm.movq(asRegister(result), input.asLong());
                } else {
                    if (input.asLong() == (int) input.asLong()) {
                        // Sign extended to long
                        masm.movslq(asRegister(result), (int) input.asLong());
                    } else if ((input.asLong() & 0xFFFFFFFFL) == input.asLong()) {
                        // Zero extended to long
                        masm.movl(asRegister(result), (int) input.asLong());
                    } else {
                        masm.movq(asRegister(result), input.asLong());
                    }
                }
                break;
            case Float:
                // This is *not* the same as 'constant == 0.0f' in the case where constant is -0.0f
                if (Float.floatToRawIntBits(input.asFloat()) == Float.floatToRawIntBits(0.0f)) {
                    assert !crb.codeCache.needsDataPatch(input);
                    masm.xorps(asRegister(result, JavaKind.Float), asRegister(result, JavaKind.Float));
                } else {
                    masm.movflt(asRegister(result, JavaKind.Float), (AMD64Address) crb.asFloatConstRef(input));
                }
                break;
            case Double:
                // This is *not* the same as 'constant == 0.0d' in the case where constant is -0.0d
                if (Double.doubleToRawLongBits(input.asDouble()) == Double.doubleToRawLongBits(0.0d)) {
                    assert !crb.codeCache.needsDataPatch(input);
                    masm.xorpd(asRegister(result, JavaKind.Double), asRegister(result, JavaKind.Double));
                } else {
                    masm.movdbl(asRegister(result, JavaKind.Double), (AMD64Address) crb.asDoubleConstRef(input));
                }
                break;
            case Object:
                // Do not optimize with an XOR as this instruction may be between
                // a CMP and a Jcc in which case the XOR will modify the condition
                // flags and interfere with the Jcc.
                if (input.isNull()) {
                    masm.movq(asRegister(result), 0x0L);
                } else if (crb.target.inlineObjects) {
                    crb.recordInlineDataInCode(input);
                    masm.movq(asRegister(result), 0xDEADDEADDEADDEADL);
                } else {
                    masm.movq(asRegister(result), (AMD64Address) crb.recordDataReferenceInCode(input, 0));
                }
                break;
            default:
                throw JVMCIError.shouldNotReachHere();
        }
    }

    public static void const2stack(CompilationResultBuilder crb, AMD64MacroAssembler masm, Value result, JavaConstant input) {
        assert !crb.codeCache.needsDataPatch(input);
        AMD64Address dest = (AMD64Address) crb.asAddress(result);
        final long imm;
        switch (input.getJavaKind().getStackKind()) {
            case Int:
                imm = input.asInt();
                break;
            case Long:
                imm = input.asLong();
                break;
            case Float:
                imm = floatToRawIntBits(input.asFloat());
                break;
            case Double:
                imm = doubleToRawLongBits(input.asDouble());
                break;
            case Object:
                if (input.isNull()) {
                    imm = 0;
                } else {
                    throw JVMCIError.shouldNotReachHere("Non-null object constants must be in register");
                }
                break;
            default:
                throw JVMCIError.shouldNotReachHere();
        }
        switch ((JavaKind) result.getPlatformKind()) {
            case Byte:
                assert NumUtil.isByte(imm) : "Is not in byte range: " + imm;
                AMD64MIOp.MOVB.emit(masm, OperandSize.BYTE, dest, (int) imm);
                break;
            case Short:
                assert NumUtil.isShort(imm) : "Is not in short range: " + imm;
                AMD64MIOp.MOV.emit(masm, OperandSize.WORD, dest, (int) imm);
                break;
            case Char:
                assert NumUtil.isUShort(imm) : "Is not in char range: " + imm;
                AMD64MIOp.MOV.emit(masm, OperandSize.WORD, dest, (int) imm);
                break;
            case Int:
            case Float:
                assert NumUtil.isInt(imm) : "Is not in int range: " + imm;
                masm.movl(dest, (int) imm);
                break;
            case Long:
            case Double:
            case Object:
                masm.movlong(dest, imm);
                break;
            default:
                throw JVMCIError.shouldNotReachHere("Unknown result Kind: " + result.getPlatformKind());
        }
    }
}
