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
package com.oracle.graal.lir.sparc;

import static com.oracle.graal.api.code.ValueUtil.*;
import static com.oracle.graal.api.meta.Kind.*;
import static com.oracle.graal.asm.sparc.SPARCAssembler.*;
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.*;
import static com.oracle.graal.sparc.SPARC.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.sparc.*;
import com.oracle.graal.asm.sparc.SPARCAssembler.Add;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fmovd;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fmovs;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fzerod;
import com.oracle.graal.asm.sparc.SPARCAssembler.Fzeros;
import com.oracle.graal.asm.sparc.SPARCAssembler.Lddf;
import com.oracle.graal.asm.sparc.SPARCAssembler.Ldf;
import com.oracle.graal.asm.sparc.SPARCAssembler.Ldsb;
import com.oracle.graal.asm.sparc.SPARCAssembler.Ldsh;
import com.oracle.graal.asm.sparc.SPARCAssembler.Ldsw;
import com.oracle.graal.asm.sparc.SPARCAssembler.Lduh;
import com.oracle.graal.asm.sparc.SPARCAssembler.Ldx;
import com.oracle.graal.asm.sparc.SPARCAssembler.Membar;
import com.oracle.graal.asm.sparc.SPARCAssembler.Movdtox;
import com.oracle.graal.asm.sparc.SPARCAssembler.Movstosw;
import com.oracle.graal.asm.sparc.SPARCAssembler.Movstouw;
import com.oracle.graal.asm.sparc.SPARCAssembler.Movwtos;
import com.oracle.graal.asm.sparc.SPARCAssembler.Movxtod;
import com.oracle.graal.asm.sparc.SPARCAssembler.Or;
import com.oracle.graal.asm.sparc.SPARCAssembler.Stb;
import com.oracle.graal.asm.sparc.SPARCAssembler.Stdf;
import com.oracle.graal.asm.sparc.SPARCAssembler.Stf;
import com.oracle.graal.asm.sparc.SPARCAssembler.Sth;
import com.oracle.graal.asm.sparc.SPARCAssembler.Stw;
import com.oracle.graal.asm.sparc.SPARCAssembler.Stx;
import com.oracle.graal.asm.sparc.SPARCMacroAssembler.Cas;
import com.oracle.graal.asm.sparc.SPARCMacroAssembler.Casx;
import com.oracle.graal.asm.sparc.SPARCMacroAssembler.Clr;
import com.oracle.graal.asm.sparc.SPARCMacroAssembler.Mov;
import com.oracle.graal.asm.sparc.SPARCMacroAssembler.Setx;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.StandardOp.ImplicitNullCheck;
import com.oracle.graal.lir.StandardOp.MoveOp;
import com.oracle.graal.lir.StandardOp.NullCheck;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.sparc.*;
import com.oracle.graal.sparc.SPARC.CPUFeature;

public class SPARCMove {

    @Opcode("MOVE_TOREG")
    public static class MoveToRegOp extends SPARCLIRInstruction implements MoveOp, SPARCTailDelayedLIRInstruction {

        @Def({REG, HINT}) protected AllocatableValue result;
        @Use({REG, STACK, CONST}) protected Value input;

        public MoveToRegOp(AllocatableValue result, Value input) {
            this.result = result;
            this.input = input;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            move(crb, masm, getResult(), getInput(), delayedControlTransfer);
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

    @Opcode("MOVE_FROMREG")
    public static class MoveFromRegOp extends SPARCLIRInstruction implements MoveOp, SPARCTailDelayedLIRInstruction {

        @Def({REG, STACK}) protected AllocatableValue result;
        @Use({REG, CONST, HINT}) protected Value input;

        public MoveFromRegOp(AllocatableValue result, Value input) {
            this.result = result;
            this.input = input;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            move(crb, masm, getResult(), getInput(), delayedControlTransfer);
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

    /**
     * Move between floating-point and general purpose register domain (WITHOUT VIS3).
     */
    @Opcode("MOVE")
    public static class MoveFpGp extends SPARCLIRInstruction implements MoveOp, SPARCTailDelayedLIRInstruction {

        @Def({REG}) protected AllocatableValue result;
        @Use({REG}) protected AllocatableValue input;
        @Use({STACK}) protected StackSlot temp;

        public MoveFpGp(AllocatableValue result, AllocatableValue input, StackSlot temp) {
            super();
            this.result = result;
            this.input = input;
            this.temp = temp;
            assert this.temp.getPlatformKind() == Kind.Long;
        }

        public Value getInput() {
            return input;
        }

        public AllocatableValue getResult() {
            return result;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            Kind inputKind = (Kind) input.getPlatformKind();
            Kind resultKind = (Kind) result.getPlatformKind();
            int resultKindSize = crb.target.getSizeInBytes(resultKind);
            try (SPARCScratchRegister sc = SPARCScratchRegister.get()) {
                Register scratch = sc.getRegister();
                SPARCAddress tempAddress = generateSimm13OffsetLoad((SPARCAddress) crb.asAddress(temp), masm, scratch);
                switch (inputKind) {
                    case Float:
                        assert resultKindSize == 4;
                        new Stf(asFloatReg(input), tempAddress).emit(masm);
                        break;
                    case Double:
                        assert resultKindSize == 8;
                        new Stdf(asDoubleReg(input), tempAddress).emit(masm);
                        break;
                    case Long:
                    case Int:
                    case Short:
                    case Char:
                    case Byte:
                        if (resultKindSize == 8) {
                            new Stx(asLongReg(input), tempAddress).emit(masm);
                        } else if (resultKindSize == 4) {
                            new Stw(asIntReg(input), tempAddress).emit(masm);
                        } else if (resultKindSize == 2) {
                            new Sth(asIntReg(input), tempAddress).emit(masm);
                        } else if (resultKindSize == 1) {
                            new Stb(asIntReg(input), tempAddress).emit(masm);
                        } else {
                            throw GraalInternalError.shouldNotReachHere();
                        }
                        break;
                    default:
                        GraalInternalError.shouldNotReachHere();
                }
                delayedControlTransfer.emitControlTransfer(crb, masm);
                switch (resultKind) {
                    case Long:
                        new Ldx(tempAddress, asLongReg(result)).emit(masm);
                        break;
                    case Int:
                        new Ldsw(tempAddress, asIntReg(result)).emit(masm);
                        break;
                    case Short:
                        new Ldsh(tempAddress, asIntReg(input)).emit(masm);
                        break;
                    case Char:
                        new Lduh(tempAddress, asIntReg(input)).emit(masm);
                        break;
                    case Byte:
                        new Ldsb(tempAddress, asIntReg(input)).emit(masm);
                        break;
                    case Float:
                        new Ldf(tempAddress, asFloatReg(result)).emit(masm);
                        break;
                    case Double:
                        new Lddf(tempAddress, asDoubleReg(result)).emit(masm);
                        break;
                    default:
                        GraalInternalError.shouldNotReachHere();
                        break;
                }
            }
        }
    }

    /**
     * Move between floating-point and general purpose register domain (WITH VIS3).
     */
    @Opcode("MOVE")
    public static class MoveFpGpVIS3 extends SPARCLIRInstruction implements MoveOp, SPARCTailDelayedLIRInstruction {

        @Def({REG}) protected AllocatableValue result;
        @Use({REG}) protected AllocatableValue input;

        public MoveFpGpVIS3(AllocatableValue result, AllocatableValue input) {
            super();
            this.result = result;
            this.input = input;
        }

        public Value getInput() {
            return input;
        }

        public AllocatableValue getResult() {
            return result;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            Kind inputKind = (Kind) input.getPlatformKind();
            Kind resultKind = (Kind) result.getPlatformKind();
            delayedControlTransfer.emitControlTransfer(crb, masm);
            if (resultKind == Float) {
                if (inputKind == Int || inputKind == Short || inputKind == Char || inputKind == Byte) {
                    new Movwtos(asIntReg(input), asFloatReg(result)).emit(masm);
                } else {
                    throw GraalInternalError.shouldNotReachHere();
                }
            } else if (resultKind == Double) {
                if (inputKind == Int || inputKind == Short || inputKind == Char || inputKind == Byte) {
                    new Movxtod(asIntReg(input), asDoubleReg(result)).emit(masm);
                } else {
                    new Movxtod(asLongReg(input), asDoubleReg(result)).emit(masm);
                }
            } else if (inputKind == Float) {
                if (resultKind == Int || resultKind == Short || resultKind == Byte) {
                    new Movstosw(asFloatReg(input), asIntReg(result)).emit(masm);
                } else {
                    new Movstouw(asFloatReg(input), asIntReg(result)).emit(masm);
                }
            } else if (inputKind == Double) {
                if (resultKind == Long) {
                    new Movdtox(asDoubleReg(input), asLongReg(result)).emit(masm);
                } else {
                    throw GraalInternalError.shouldNotReachHere();
                }
            }
        }
    }

    public abstract static class MemOp extends SPARCLIRInstruction implements ImplicitNullCheck {

        protected final Kind kind;
        @Use({COMPOSITE}) protected SPARCAddressValue address;
        @State protected LIRFrameState state;

        public MemOp(Kind kind, SPARCAddressValue address, LIRFrameState state) {
            this.kind = kind;
            this.address = address;
            this.state = state;
        }

        protected abstract void emitMemAccess(CompilationResultBuilder crb, SPARCMacroAssembler masm);

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            emitMemAccess(crb, masm);
        }

        public boolean makeNullCheckFor(Value value, LIRFrameState nullCheckState, int implicitNullCheckLimit) {
            if (state == null && value.equals(address.base) && address.index.equals(Value.ILLEGAL) && address.displacement >= 0 && address.displacement < implicitNullCheckLimit) {
                state = nullCheckState;
                return true;
            }
            return false;
        }
    }

    public static class LoadOp extends MemOp implements SPARCTailDelayedLIRInstruction {

        @Def({REG}) protected AllocatableValue result;

        public LoadOp(Kind kind, AllocatableValue result, SPARCAddressValue address, LIRFrameState state) {
            super(kind, address, state);
            this.result = result;
        }

        @Override
        public void emitMemAccess(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            try (SPARCScratchRegister sc = SPARCScratchRegister.get()) {
                Register scratch = sc.getRegister();
                final SPARCAddress addr = generateSimm13OffsetLoad(address.toAddress(), masm, scratch);
                final Register dst = asRegister(result);
                delayedControlTransfer.emitControlTransfer(crb, masm);
                if (state != null) {
                    crb.recordImplicitException(masm.position(), state);
                }
                switch (kind) {
                    case Boolean:
                    case Byte:
                        new Ldsb(addr, dst).emit(masm);
                        break;
                    case Short:
                        new Ldsh(addr, dst).emit(masm);
                        break;
                    case Char:
                        new Lduh(addr, dst).emit(masm);
                        break;
                    case Int:
                        new Ldsw(addr, dst).emit(masm);
                        break;
                    case Long:
                        new Ldx(addr, dst).emit(masm);
                        break;
                    case Float:
                        new Ldf(addr, dst).emit(masm);
                        break;
                    case Double:
                        new Lddf(addr, dst).emit(masm);
                        break;
                    case Object:
                        new Ldx(addr, dst).emit(masm);
                        break;
                    default:
                        throw GraalInternalError.shouldNotReachHere();
                }
            }
        }
    }

    public static class LoadAddressOp extends SPARCLIRInstruction implements SPARCTailDelayedLIRInstruction {

        @Def({REG}) protected AllocatableValue result;
        @Use({COMPOSITE, UNINITIALIZED}) protected SPARCAddressValue addressValue;

        public LoadAddressOp(AllocatableValue result, SPARCAddressValue address) {
            this.result = result;
            this.addressValue = address;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            SPARCAddress address = addressValue.toAddress();
            loadEffectiveAddress(crb, masm, address, asLongReg(result), delayedControlTransfer);
        }
    }

    public static class LoadDataAddressOp extends SPARCLIRInstruction {

        @Def({REG}) protected AllocatableValue result;
        private final byte[] data;

        public LoadDataAddressOp(AllocatableValue result, byte[] data) {
            this.result = result;
            this.data = data;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            SPARCAddress addr = (SPARCAddress) crb.recordDataReferenceInCode(data, 16);
            assert addr == masm.getPlaceholder();
            final boolean forceRelocatable = true;
            Register dstReg = asRegister(result);
            new Setx(0, dstReg, forceRelocatable).emit(masm);
        }
    }

    public static class MembarOp extends SPARCLIRInstruction {

        private final int barriers;

        public MembarOp(final int barriers) {
            this.barriers = barriers;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            new Membar(barriers).emit(masm);
        }
    }

    public static class NullCheckOp extends SPARCLIRInstruction implements NullCheck, SPARCTailDelayedLIRInstruction {

        @Use({REG}) protected AllocatableValue input;
        @State protected LIRFrameState state;

        public NullCheckOp(Variable input, LIRFrameState state) {
            this.input = input;
            this.state = state;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            delayedControlTransfer.emitControlTransfer(crb, masm);
            crb.recordImplicitException(masm.position(), state);
            new Ldx(new SPARCAddress(asRegister(input), 0), r0).emit(masm);
        }

        public Value getCheckedValue() {
            return input;
        }

        public LIRFrameState getState() {
            return state;
        }
    }

    @Opcode("CAS")
    public static class CompareAndSwapOp extends SPARCLIRInstruction {

        // @Def protected AllocatableValue result;
        @Use protected AllocatableValue address;
        @Use protected AllocatableValue cmpValue;
        @Use protected AllocatableValue newValue;

        public CompareAndSwapOp(AllocatableValue address, AllocatableValue cmpValue, AllocatableValue newValue) {
            this.address = address;
            this.cmpValue = cmpValue;
            this.newValue = newValue;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            compareAndSwap(masm, address, cmpValue, newValue);
        }
    }

    public static class StackLoadAddressOp extends SPARCLIRInstruction implements SPARCTailDelayedLIRInstruction {

        @Def({REG}) protected AllocatableValue result;
        @Use({STACK, UNINITIALIZED}) protected StackSlot slot;

        public StackLoadAddressOp(AllocatableValue result, StackSlot slot) {
            this.result = result;
            this.slot = slot;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            SPARCAddress address = (SPARCAddress) crb.asAddress(slot);
            loadEffectiveAddress(crb, masm, address, asLongReg(result), delayedControlTransfer);
        }
    }

    private static void loadEffectiveAddress(CompilationResultBuilder crb, SPARCMacroAssembler masm, SPARCAddress address, Register result, SPARCDelayedControlTransfer delaySlotHolder) {
        if (address.getIndex().equals(Register.None)) {
            if (isSimm13(address.getDisplacement())) {
                delaySlotHolder.emitControlTransfer(crb, masm);
                new Add(address.getBase(), address.getDisplacement(), result).emit(masm);
            } else {
                assert result.encoding() != address.getBase().encoding();
                new Setx(address.getDisplacement(), result).emit(masm);
                // No relocation, therefore, the add can be delayed as well
                delaySlotHolder.emitControlTransfer(crb, masm);
                new Add(address.getBase(), result, result).emit(masm);
            }
        } else {
            delaySlotHolder.emitControlTransfer(crb, masm);
            new Add(address.getBase(), address.getIndex(), result).emit(masm);
        }
    }

    public static class StoreOp extends MemOp implements SPARCTailDelayedLIRInstruction {

        @Use({REG}) protected AllocatableValue input;

        public StoreOp(Kind kind, SPARCAddressValue address, AllocatableValue input, LIRFrameState state) {
            super(kind, address, state);
            this.input = input;
        }

        @Override
        public void emitMemAccess(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            assert isRegister(input);
            try (SPARCScratchRegister sc = SPARCScratchRegister.get()) {
                Register scratch = sc.getRegister();
                SPARCAddress addr = generateSimm13OffsetLoad(address.toAddress(), masm, scratch);
                delayedControlTransfer.emitControlTransfer(crb, masm);
                if (state != null) {
                    crb.recordImplicitException(masm.position(), state);
                }
                switch (kind) {
                    case Boolean:
                    case Byte:
                        new Stb(asRegister(input), addr).emit(masm);
                        break;
                    case Short:
                    case Char:
                        new Sth(asRegister(input), addr).emit(masm);
                        break;
                    case Int:
                        new Stw(asRegister(input), addr).emit(masm);
                        break;
                    case Long:
                        new Stx(asRegister(input), addr).emit(masm);
                        break;
                    case Object:
                        new Stx(asRegister(input), addr).emit(masm);
                        break;
                    case Float:
                        new Stf(asRegister(input), addr).emit(masm);
                        break;
                    case Double:
                        new Stdf(asRegister(input), addr).emit(masm);
                        break;
                    default:
                        throw GraalInternalError.shouldNotReachHere("missing: " + kind);
                }
            }
        }
    }

    public static class StoreConstantOp extends MemOp implements SPARCTailDelayedLIRInstruction {

        protected final JavaConstant input;

        public StoreConstantOp(Kind kind, SPARCAddressValue address, JavaConstant input, LIRFrameState state) {
            super(kind, address, state);
            this.input = input;
            if (!input.isDefaultForKind()) {
                throw GraalInternalError.shouldNotReachHere("Can only store null constants to memory");
            }
        }

        @Override
        public void emitMemAccess(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            try (SPARCScratchRegister sc = SPARCScratchRegister.get()) {
                Register scratch = sc.getRegister();
                SPARCAddress addr = generateSimm13OffsetLoad(address.toAddress(), masm, scratch);
                delayedControlTransfer.emitControlTransfer(crb, masm);
                if (state != null) {
                    crb.recordImplicitException(masm.position(), state);
                }
                switch (kind) {
                    case Boolean:
                    case Byte:
                        new Stb(g0, addr).emit(masm);
                        break;
                    case Short:
                    case Char:
                        new Sth(g0, addr).emit(masm);
                        break;
                    case Int:
                        new Stw(g0, addr).emit(masm);
                        break;
                    case Long:
                    case Object:
                        new Stx(g0, addr).emit(masm);
                        break;
                    case Float:
                    case Double:
                        throw GraalInternalError.shouldNotReachHere("Cannot store float constants to memory");
                    default:
                        throw GraalInternalError.shouldNotReachHere();
                }
            }
        }
    }

    public static void move(CompilationResultBuilder crb, SPARCMacroAssembler masm, Value result, Value input, SPARCDelayedControlTransfer delaySlotLir) {
        if (isRegister(input)) {
            if (isRegister(result)) {
                reg2reg(crb, masm, result, input, delaySlotLir);
            } else if (isStackSlot(result)) {
                reg2stack(crb, masm, result, input, delaySlotLir);
            } else {
                throw GraalInternalError.shouldNotReachHere();
            }
        } else if (isStackSlot(input)) {
            if (isRegister(result)) {
                stack2reg(crb, masm, result, input, delaySlotLir);
            } else {
                throw GraalInternalError.shouldNotReachHere();
            }
        } else if (isConstant(input)) {
            JavaConstant constant = asConstant(input);
            if (isRegister(result)) {
                const2reg(crb, masm, result, constant, delaySlotLir);
            } else if (isStackSlot(result)) {
                if (constant.isDefaultForKind() || constant.isNull()) {
                    reg2stack(crb, masm, result, g0.asValue(LIRKind.derive(input)), delaySlotLir);
                } else {
                    try (SPARCScratchRegister sc = SPARCScratchRegister.get()) {
                        Register scratch = sc.getRegister();
                        long value = constant.asLong();
                        if (isSimm13(value)) {
                            new Or(g0, (int) value, scratch).emit(masm);
                        } else {
                            new Setx(value, scratch).emit(masm);
                        }
                        reg2stack(crb, masm, result, scratch.asValue(LIRKind.derive(input)), delaySlotLir);
                    }
                }
            } else {
                throw GraalInternalError.shouldNotReachHere("Result is a: " + result);
            }
        } else {
            throw GraalInternalError.shouldNotReachHere();
        }
    }

    private static void reg2reg(CompilationResultBuilder crb, SPARCMacroAssembler masm, Value result, Value input, SPARCDelayedControlTransfer delaySlotLir) {
        final Register src = asRegister(input);
        final Register dst = asRegister(result);
        if (src.equals(dst)) {
            return;
        }
        switch (input.getKind()) {
            case Boolean:
            case Byte:
            case Short:
            case Char:
            case Int:
            case Long:
            case Object:
                delaySlotLir.emitControlTransfer(crb, masm);
                new Mov(src, dst).emit(masm);
                break;
            case Float:
                if (result.getPlatformKind() == Kind.Float) {
                    new Fmovs(src, dst).emit(masm);
                } else {
                    throw GraalInternalError.shouldNotReachHere();
                }
                break;
            case Double:
                if (result.getPlatformKind() == Kind.Double) {
                    new Fmovd(src, dst).emit(masm);
                } else {
                    throw GraalInternalError.shouldNotReachHere();
                }
                break;
            default:
                throw GraalInternalError.shouldNotReachHere("Input is a: " + input.getKind());
        }
    }

    /**
     * Guarantees that the given SPARCAddress given before is loadable by subsequent load/store
     * instruction. If the displacement exceeds the simm13 value range, the value is put into a
     * scratch register.
     *
     * @param addr Address to modify
     * @param masm assembler to output the potential code to store the value in the scratch register
     * @param scratch The register as scratch to use
     * @return a loadable SPARCAddress
     */
    public static SPARCAddress generateSimm13OffsetLoad(SPARCAddress addr, SPARCMacroAssembler masm, Register scratch) {
        boolean displacementOutOfBound = addr.getIndex().equals(Register.None) && !SPARCAssembler.isSimm13(addr.getDisplacement());
        if (displacementOutOfBound) {
            new Setx(addr.getDisplacement(), scratch, false).emit(masm);
            return new SPARCAddress(addr.getBase(), scratch);
        } else {
            return addr;
        }
    }

    private static void reg2stack(CompilationResultBuilder crb, SPARCMacroAssembler masm, Value result, Value input, SPARCDelayedControlTransfer delaySlotLir) {
        SPARCAddress dst = (SPARCAddress) crb.asAddress(result);
        try (SPARCScratchRegister sc = SPARCScratchRegister.get()) {
            Register scratch = sc.getRegister();
            dst = generateSimm13OffsetLoad(dst, masm, scratch);
            Register src = asRegister(input);
            delaySlotLir.emitControlTransfer(crb, masm);
            switch (input.getKind()) {
                case Byte:
                case Boolean:
                    new Stb(src, dst).emit(masm);
                    break;
                case Char:
                case Short:
                    new Sth(src, dst).emit(masm);
                    break;
                case Int:
                    new Stw(src, dst).emit(masm);
                    break;
                case Long:
                case Object:
                    new Stx(src, dst).emit(masm);
                    break;
                case Float:
                    new Stf(src, dst).emit(masm);
                    break;
                case Double:
                    new Stdf(src, dst).emit(masm);
                    break;
                default:
                    throw GraalInternalError.shouldNotReachHere("Input is a: " + input.getKind() + "(" + input + ")");
            }
        }
    }

    private static void stack2reg(CompilationResultBuilder crb, SPARCMacroAssembler masm, Value result, Value input, SPARCDelayedControlTransfer delaySlotLir) {
        SPARCAddress src = (SPARCAddress) crb.asAddress(input);
        try (SPARCScratchRegister sc = SPARCScratchRegister.get()) {
            Register scratch = sc.getRegister();
            src = generateSimm13OffsetLoad(src, masm, scratch);
            Register dst = asRegister(result);
            delaySlotLir.emitControlTransfer(crb, masm);
            switch (input.getKind()) {
                case Boolean:
                case Byte:
                    new Ldsb(src, dst).emit(masm);
                    break;
                case Short:
                    new Ldsh(src, dst).emit(masm);
                    break;
                case Char:
                    new Lduh(src, dst).emit(masm);
                    break;
                case Int:
                    new Ldsw(src, dst).emit(masm);
                    break;
                case Long:
                case Object:
                    new Ldx(src, dst).emit(masm);
                    break;
                case Float:
                    new Ldf(src, dst).emit(masm);
                    break;
                case Double:
                    new Lddf(src, dst).emit(masm);
                    break;
                default:
                    throw GraalInternalError.shouldNotReachHere("Input is a: " + input.getKind());
            }
        }
    }

    private static void const2reg(CompilationResultBuilder crb, SPARCMacroAssembler masm, Value result, JavaConstant input, SPARCDelayedControlTransfer delaySlotLir) {
        try (SPARCScratchRegister sc = SPARCScratchRegister.get()) {
            Register scratch = sc.getRegister();
            boolean hasVIS3 = ((SPARC) masm.target.arch).getFeatures().contains(CPUFeature.VIS3);
            switch (input.getKind().getStackKind()) {
                case Int:
                    if (input.isDefaultForKind()) {
                        delaySlotLir.emitControlTransfer(crb, masm);
                        new Clr(asIntReg(result)).emit(masm);
                    } else if (isSimm13(input.asLong())) {
                        delaySlotLir.emitControlTransfer(crb, masm);
                        new Or(g0, input.asInt(), asIntReg(result)).emit(masm);
                    } else {
                        Setx set = new Setx(input.asLong(), asIntReg(result), false, true);
                        set.emitFirstPartOfDelayed(masm);
                        delaySlotLir.emitControlTransfer(crb, masm);
                        set.emitSecondPartOfDelayed(masm);
                    }
                    break;
                case Long:
                    if (input.isDefaultForKind()) {
                        delaySlotLir.emitControlTransfer(crb, masm);
                        new Clr(asLongReg(result)).emit(masm);
                    } else if (isSimm13(input.asLong())) {
                        delaySlotLir.emitControlTransfer(crb, masm);
                        new Or(g0, (int) input.asLong(), asLongReg(result)).emit(masm);
                    } else {
                        Setx setx = new Setx(input.asLong(), asLongReg(result), false, true);
                        setx.emitFirstPartOfDelayed(masm);
                        delaySlotLir.emitControlTransfer(crb, masm);
                        setx.emitSecondPartOfDelayed(masm);
                    }
                    break;
                case Float: {
                    float constant = input.asFloat();
                    int constantBits = java.lang.Float.floatToIntBits(constant);
                    if (constantBits == 0) {
                        delaySlotLir.emitControlTransfer(crb, masm);
                        new Fzeros(asFloatReg(result)).emit(masm);
                    } else {
                        if (hasVIS3) {
                            if (isSimm13(constantBits)) {
                                new Or(g0, constantBits, scratch).emit(masm);
                            } else {
                                new Setx(constantBits, scratch, false).emit(masm);
                            }
                            delaySlotLir.emitControlTransfer(crb, masm);
                            // Now load the float value
                            new Movwtos(scratch, asFloatReg(result)).emit(masm);
                        } else {
                            crb.asFloatConstRef(input);
                            // First load the address into the scratch register
                            new Setx(0, scratch, true).emit(masm);
                            // Now load the float value
                            delaySlotLir.emitControlTransfer(crb, masm);
                            new Ldf(scratch, asFloatReg(result)).emit(masm);
                        }
                    }
                    break;
                }
                case Double: {
                    double constant = input.asDouble();
                    long constantBits = java.lang.Double.doubleToLongBits(constant);
                    if (constantBits == 0) {
                        delaySlotLir.emitControlTransfer(crb, masm);
                        new Fzerod(asDoubleReg(result)).emit(masm);
                    } else {
                        if (hasVIS3) {
                            if (isSimm13(constantBits)) {
                                new Or(g0, (int) constantBits, scratch).emit(masm);
                            } else {
                                new Setx(constantBits, scratch, false).emit(masm);
                            }
                            delaySlotLir.emitControlTransfer(crb, masm);
                            // Now load the float value
                            new Movxtod(scratch, asDoubleReg(result)).emit(masm);
                        } else {
                            crb.asDoubleConstRef(input);
                            // First load the address into the scratch register
                            new Setx(0, scratch, true).emit(masm);
                            delaySlotLir.emitControlTransfer(crb, masm);
                            // Now load the float value
                            new Lddf(scratch, asDoubleReg(result)).emit(masm);
                        }
                    }
                    break;
                }
                case Object:
                    if (input.isNull()) {
                        delaySlotLir.emitControlTransfer(crb, masm);
                        new Clr(asRegister(result)).emit(masm);
                    } else if (crb.target.inlineObjects) {
                        crb.recordInlineDataInCode(input); // relocatable cannot be delayed
                        new Setx(0xDEADDEADDEADDEADL, asRegister(result), true).emit(masm);
                    } else {
                        throw GraalInternalError.unimplemented();
                    }
                    break;
                default:
                    throw GraalInternalError.shouldNotReachHere("missing: " + input.getKind());
            }
        }
    }

    protected static void compareAndSwap(SPARCMacroAssembler masm, AllocatableValue address, AllocatableValue cmpValue, AllocatableValue newValue) {
        switch (cmpValue.getKind()) {
            case Int:
                new Cas(asRegister(address), asRegister(cmpValue), asRegister(newValue)).emit(masm);
                break;
            case Long:
            case Object:
                new Casx(asRegister(address), asRegister(cmpValue), asRegister(newValue)).emit(masm);
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }
}
