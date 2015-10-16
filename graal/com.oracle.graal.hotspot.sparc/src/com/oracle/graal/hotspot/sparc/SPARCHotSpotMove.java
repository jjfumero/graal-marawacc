/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.sparc;

import static com.oracle.graal.lir.LIRInstruction.OperandFlag.ILLEGAL;
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.REG;
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.STACK;
import static com.oracle.graal.lir.sparc.SPARCMove.loadFromConstantTable;
import static jdk.vm.ci.code.ValueUtil.asRegister;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.hotspot.HotSpotConstant;
import jdk.vm.ci.hotspot.HotSpotVMConfig.CompressEncoding;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Constant;

import com.oracle.graal.asm.Label;
import com.oracle.graal.asm.sparc.SPARCAddress;
import com.oracle.graal.asm.sparc.SPARCAssembler.Annul;
import com.oracle.graal.asm.sparc.SPARCAssembler.BranchPredict;
import com.oracle.graal.asm.sparc.SPARCAssembler.CC;
import com.oracle.graal.asm.sparc.SPARCAssembler.ConditionFlag;
import com.oracle.graal.asm.sparc.SPARCAssembler.RCondition;
import com.oracle.graal.asm.sparc.SPARCMacroAssembler;
import com.oracle.graal.asm.sparc.SPARCMacroAssembler.ScratchRegister;
import com.oracle.graal.lir.LIRInstructionClass;
import com.oracle.graal.lir.StandardOp.LoadConstantOp;
import com.oracle.graal.lir.asm.CompilationResultBuilder;
import com.oracle.graal.lir.sparc.SPARCDelayedControlTransfer;
import com.oracle.graal.lir.sparc.SPARCLIRInstruction;
import com.oracle.graal.lir.sparc.SPARCTailDelayedLIRInstruction;

public class SPARCHotSpotMove {

    public static class LoadHotSpotObjectConstantInline extends SPARCLIRInstruction implements SPARCTailDelayedLIRInstruction, LoadConstantOp {
        public static final LIRInstructionClass<LoadHotSpotObjectConstantInline> TYPE = LIRInstructionClass.create(LoadHotSpotObjectConstantInline.class);

        public static final SizeEstimate SIZE = SizeEstimate.create(8);
        private HotSpotConstant constant;
        @Def({REG, STACK}) AllocatableValue result;

        public LoadHotSpotObjectConstantInline(HotSpotConstant constant, AllocatableValue result) {
            super(TYPE, SIZE);
            this.constant = constant;
            this.result = result;
        }

        @Override
        protected void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            crb.recordInlineDataInCode(constant);
            if (constant.isCompressed()) {
                masm.setw(0, asRegister(result), true);
            } else {
                masm.setx(0, asRegister(result), true);
            }
        }

        @Override
        public AllocatableValue getResult() {
            return result;
        }

        @Override
        public Constant getConstant() {
            return constant;
        }
    }

    public static class LoadHotSpotObjectConstantFromTable extends SPARCLIRInstruction implements SPARCTailDelayedLIRInstruction {
        public static final LIRInstructionClass<LoadHotSpotObjectConstantFromTable> TYPE = LIRInstructionClass.create(LoadHotSpotObjectConstantFromTable.class);

        public static final SizeEstimate SIZE = SizeEstimate.create(2, 8);
        private final HotSpotConstant constant;
        @Use({REG}) private AllocatableValue constantTableBase;
        @Def({REG, STACK}) AllocatableValue result;

        public LoadHotSpotObjectConstantFromTable(HotSpotConstant constant, AllocatableValue result, AllocatableValue constantTableBase) {
            super(TYPE, SIZE);
            this.constant = constant;
            this.result = result;
            this.constantTableBase = constantTableBase;
        }

        @Override
        protected void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            try (ScratchRegister scratch = masm.getScratchRegister()) {
                boolean isStack = ValueUtil.isStackSlot(result);
                Register register;
                if (isStack) {
                    register = scratch.getRegister();
                } else {
                    register = asRegister(result);
                }
                int bytes = result.getPlatformKind().getSizeInBytes();
                loadFromConstantTable(crb, masm, bytes, asRegister(constantTableBase), constant, register, SPARCDelayedControlTransfer.DUMMY);
                if (isStack) {
                    masm.st(register, (SPARCAddress) crb.asAddress(result), bytes);
                }
            }
        }
    }

    public static final class CompressPointer extends SPARCLIRInstruction {
        public static final LIRInstructionClass<CompressPointer> TYPE = LIRInstructionClass.create(CompressPointer.class);
        public static final SizeEstimate SIZE = SizeEstimate.create(5);

        private final CompressEncoding encoding;
        private final boolean nonNull;

        @Def({REG}) protected AllocatableValue result;
        @Use({REG}) protected AllocatableValue input;
        @Alive({REG, ILLEGAL}) protected AllocatableValue baseRegister;

        public CompressPointer(AllocatableValue result, AllocatableValue input, AllocatableValue baseRegister, CompressEncoding encoding, boolean nonNull) {
            super(TYPE, SIZE);
            this.result = result;
            this.input = input;
            this.baseRegister = baseRegister;
            this.encoding = encoding;
            this.nonNull = nonNull;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            Register inputRegister = asRegister(input);
            Register resReg = asRegister(result);
            if (encoding.base != 0) {
                Register baseReg = asRegister(baseRegister);
                if (!nonNull) {
                    masm.cmp(inputRegister, baseReg);
                    masm.movcc(ConditionFlag.Equal, CC.Xcc, baseReg, resReg);
                    masm.sub(resReg, baseReg, resReg);
                } else {
                    masm.sub(inputRegister, baseReg, resReg);
                }
                if (encoding.shift != 0) {
                    masm.srlx(resReg, encoding.shift, resReg);
                }
            } else {
                masm.srlx(inputRegister, encoding.shift, resReg);
            }
        }
    }

    public static final class UncompressPointer extends SPARCLIRInstruction {
        public static final LIRInstructionClass<UncompressPointer> TYPE = LIRInstructionClass.create(UncompressPointer.class);
        public static final SizeEstimate SIZE = SizeEstimate.create(4);

        private final CompressEncoding encoding;
        private final boolean nonNull;

        @Def({REG}) protected AllocatableValue result;
        @Use({REG}) protected AllocatableValue input;
        @Alive({REG, ILLEGAL}) protected AllocatableValue baseRegister;

        public UncompressPointer(AllocatableValue result, AllocatableValue input, AllocatableValue baseRegister, CompressEncoding encoding, boolean nonNull) {
            super(TYPE, SIZE);
            this.result = result;
            this.input = input;
            this.baseRegister = baseRegister;
            this.encoding = encoding;
            this.nonNull = nonNull;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, SPARCMacroAssembler masm) {
            Register inputRegister = asRegister(input);
            Register resReg = asRegister(result);
            Register secondaryInput;
            if (encoding.shift != 0) {
                masm.sll(inputRegister, encoding.shift, resReg);
                secondaryInput = resReg;
            } else {
                secondaryInput = inputRegister;
            }

            if (encoding.base != 0) {
                if (nonNull) {
                    masm.add(secondaryInput, asRegister(baseRegister), resReg);
                } else {
                    Label done = new Label();
                    masm.bpr(RCondition.Rc_nz, Annul.ANNUL, done, BranchPredict.PREDICT_TAKEN, secondaryInput);
                    masm.add(asRegister(baseRegister), secondaryInput, resReg);
                    masm.bind(done);
                }
            }
        }
    }

}
