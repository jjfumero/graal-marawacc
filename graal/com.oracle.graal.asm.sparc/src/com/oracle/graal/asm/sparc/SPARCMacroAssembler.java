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
package com.oracle.graal.asm.sparc;

import static com.oracle.graal.asm.sparc.SPARCAssembler.Annul.*;
import static com.oracle.graal.asm.sparc.SPARCAssembler.ConditionFlag.*;
import static jdk.internal.jvmci.sparc.SPARC.*;

import java.util.function.*;

import jdk.internal.jvmci.code.*;

import com.oracle.graal.asm.*;

public class SPARCMacroAssembler extends SPARCAssembler {

    /**
     * A sentinel value used as a place holder in an instruction stream for an address that will be
     * patched.
     */
    private static final SPARCAddress Placeholder = new SPARCAddress(g0, 0);
    private final ScratchRegister[] scratchRegister = new ScratchRegister[]{new ScratchRegister(g3), new ScratchRegister(o7)};
    // Points to the next free scratch register
    private int nextFreeScratchRegister = 0;
    /**
     * Use ld [reg+simm13], reg for loading constants (User has to make sure, that the size of the
     * constant table does not exceed simm13).
     */
    private boolean immediateConstantLoad;

    public SPARCMacroAssembler(TargetDescription target, RegisterConfig registerConfig) {
        super(target, registerConfig);
    }

    /**
     * @see #immediateConstantLoad
     */
    public void setImmediateConstantLoad(boolean immediateConstantLoad) {
        this.immediateConstantLoad = immediateConstantLoad;
    }

    @Override
    public void align(int modulus) {
        while (position() % modulus != 0) {
            nop();
        }
    }

    @Override
    public void jmp(Label l) {
        bicc(Always, NOT_ANNUL, l);
        nop();  // delay slot
    }

    @Override
    protected final void patchJumpTarget(int branch, int branchTarget) {
        final int disp = (branchTarget - branch) / 4;
        final int inst = getInt(branch);
        ControlTransferOp op = (ControlTransferOp) getSPARCOp(inst);
        int newInst = op.setDisp(inst, disp);
        emitInt(newInst, branch);
    }

    @Override
    public AbstractAddress makeAddress(Register base, int displacement) {
        return new SPARCAddress(base, displacement);
    }

    @Override
    public AbstractAddress getPlaceholder() {
        return Placeholder;
    }

    @Override
    public final void ensureUniquePC() {
        nop();
    }

    public void cas(Register rs1, Register rs2, Register rd) {
        casa(rs1, rs2, rd, Asi.ASI_PRIMARY);
    }

    public void casx(Register rs1, Register rs2, Register rd) {
        casxa(rs1, rs2, rd, Asi.ASI_PRIMARY);
    }

    public void clr(Register dst) {
        or(g0, g0, dst);
    }

    public void clrb(SPARCAddress addr) {
        stb(g0, addr);
    }

    public void clrh(SPARCAddress addr) {
        sth(g0, addr);
    }

    public void clrx(SPARCAddress addr) {
        stx(g0, addr);
    }

    public void cmp(Register rs1, Register rs2) {
        subcc(rs1, rs2, g0);
    }

    public void cmp(Register rs1, int simm13) {
        subcc(rs1, simm13, g0);
    }

    public void dec(Register rd) {
        sub(rd, 1, rd);
    }

    public void dec(int simm13, Register rd) {
        sub(rd, simm13, rd);
    }

    public void jmp(SPARCAddress address) {
        jmpl(address.getBase(), address.getDisplacement(), g0);
    }

    public void jmp(Register rd) {
        jmpl(rd, 0, g0);
    }

    public void neg(Register rs1, Register rd) {
        sub(g0, rs1, rd);
    }

    public void neg(Register rd) {
        sub(g0, rd, rd);
    }

    public void mov(Register rs, Register rd) {
        or(g0, rs, rd);
    }

    public void mov(int simm13, Register rd) {
        or(g0, simm13, rd);
    }

    public void not(Register rs1, Register rd) {
        xnor(rs1, g0, rd);
    }

    public void not(Register rd) {
        xnor(rd, g0, rd);
    }

    public void restoreWindow() {
        restore(g0, g0, g0);
    }

    public void ret() {
        jmpl(i7, 8, g0);
    }

    /**
     * This instruction is like sethi but for 64-bit values.
     */
    public static class Sethix {

        private static final int INSTRUCTION_SIZE = 7;

        private long value;
        private Register dst;
        private boolean forceRelocatable;
        private boolean delayed = false;
        private Consumer<SPARCAssembler> delayedInstructionEmitter;

        public Sethix(long value, Register dst, boolean forceRelocatable, boolean delayed) {
            this(value, dst, forceRelocatable);
            assert !(forceRelocatable && delayed) : "Relocatable sethix cannot be delayed";
            this.delayed = delayed;
        }

        public Sethix(long value, Register dst, boolean forceRelocatable) {
            this.value = value;
            this.dst = dst;
            this.forceRelocatable = forceRelocatable;
        }

        public Sethix(long value, Register dst) {
            this(value, dst, false);
        }

        private void emitInstruction(Consumer<SPARCAssembler> cb, SPARCMacroAssembler masm) {
            if (delayed) {
                if (this.delayedInstructionEmitter != null) {
                    delayedInstructionEmitter.accept(masm);
                }
                delayedInstructionEmitter = cb;
            } else {
                cb.accept(masm);
            }
        }

        public void emit(SPARCMacroAssembler masm) {
            final int hi = (int) (value >> 32);
            final int lo = (int) (value & ~0);

            // This is the same logic as MacroAssembler::internal_set.
            final int startPc = masm.position();

            if (hi == 0 && lo >= 0) {
                Consumer<SPARCAssembler> cb = eMasm -> eMasm.sethi(hi22(lo), dst);
                emitInstruction(cb, masm);
            } else if (hi == -1) {
                Consumer<SPARCAssembler> cb = eMasm -> eMasm.sethi(hi22(~lo), dst);
                emitInstruction(cb, masm);
                cb = eMasm -> eMasm.xor(dst, ~lo10(~0), dst);
                emitInstruction(cb, masm);
            } else {
                final int shiftcnt;
                final int shiftcnt2;
                Consumer<SPARCAssembler> cb = eMasm -> eMasm.sethi(hi22(hi), dst);
                emitInstruction(cb, masm);
                if ((hi & 0x3ff) != 0) {                                  // Any bits?
                    // msb 32-bits are now in lsb 32
                    cb = eMasm -> eMasm.or(dst, hi & 0x3ff, dst);
                    emitInstruction(cb, masm);
                }
                if ((lo & 0xFFFFFC00) != 0) {                             // done?
                    if (((lo >> 20) & 0xfff) != 0) {                      // Any bits set?
                        // Make room for next 12 bits
                        cb = eMasm -> eMasm.sllx(dst, 12, dst);
                        emitInstruction(cb, masm);
                        // Or in next 12
                        cb = eMasm -> eMasm.or(dst, (lo >> 20) & 0xfff, dst);
                        emitInstruction(cb, masm);
                        shiftcnt = 0;                                     // We already shifted
                    } else {
                        shiftcnt = 12;
                    }
                    if (((lo >> 10) & 0x3ff) != 0) {
                        // Make room for last 10 bits
                        cb = eMasm -> eMasm.sllx(dst, shiftcnt + 10, dst);
                        emitInstruction(cb, masm);
                        // Or in next 10
                        cb = eMasm -> eMasm.or(dst, (lo >> 10) & 0x3ff, dst);
                        emitInstruction(cb, masm);
                        shiftcnt2 = 0;
                    } else {
                        shiftcnt2 = 10;
                    }
                    // Shift leaving disp field 0'd
                    cb = eMasm -> eMasm.sllx(dst, shiftcnt2 + 10, dst);
                    emitInstruction(cb, masm);
                } else {
                    cb = eMasm -> eMasm.sllx(dst, 32, dst);
                    emitInstruction(cb, masm);
                }
            }
            // Pad out the instruction sequence so it can be patched later.
            if (forceRelocatable) {
                while (masm.position() < (startPc + (INSTRUCTION_SIZE * 4))) {
                    Consumer<SPARCAssembler> cb = eMasm -> eMasm.nop();
                    emitInstruction(cb, masm);
                }
            }
        }

        public void emitDelayed(SPARCMacroAssembler masm) {
            assert delayedInstructionEmitter != null;
            delayedInstructionEmitter.accept(masm);
        }
    }

    public static class Setx {

        private long value;
        private Register dst;
        private boolean forceRelocatable;
        private boolean delayed = false;
        private boolean delayedFirstEmitted = false;
        private Sethix sethix;
        private Consumer<SPARCMacroAssembler> delayedAdd;

        public Setx(long value, Register dst, boolean forceRelocatable, boolean delayed) {
            assert !(forceRelocatable && delayed) : "Cannot use relocatable setx as delayable";
            this.value = value;
            this.dst = dst;
            this.forceRelocatable = forceRelocatable;
            this.delayed = delayed;
        }

        public Setx(long value, Register dst, boolean forceRelocatable) {
            this(value, dst, forceRelocatable, false);
        }

        public Setx(long value, Register dst) {
            this(value, dst, false);
        }

        public void emit(SPARCMacroAssembler masm) {
            assert !delayed;
            doEmit(masm);
        }

        private void doEmit(SPARCMacroAssembler masm) {
            sethix = new Sethix(value, dst, forceRelocatable, delayed);
            sethix.emit(masm);
            int lo = (int) (value & ~0);
            if (lo10(lo) != 0 || forceRelocatable) {
                Consumer<SPARCMacroAssembler> add = eMasm -> eMasm.add(dst, lo10(lo), dst);
                if (delayed) {
                    sethix.emitDelayed(masm);
                    sethix = null;
                    delayedAdd = add;
                } else {
                    sethix = null;
                    add.accept(masm);
                }
            }
        }

        public void emitFirstPartOfDelayed(SPARCMacroAssembler masm) {
            assert !forceRelocatable : "Cannot use delayed mode with relocatable setx";
            assert delayed : "Can only be used in delayed mode";
            doEmit(masm);
            delayedFirstEmitted = true;
        }

        public void emitSecondPartOfDelayed(SPARCMacroAssembler masm) {
            assert !forceRelocatable : "Cannot use delayed mode with relocatable setx";
            assert delayed : "Can only be used in delayed mode";
            assert delayedFirstEmitted : "First part has not been emitted so far.";
            assert delayedAdd == null && sethix != null || delayedAdd != null && sethix == null : "Either add or sethix must be set";
            if (delayedAdd != null) {
                delayedAdd.accept(masm);
            } else {
                sethix.emitDelayed(masm);
            }

        }
    }

    public void signx(Register rs, Register rd) {
        sra(rs, g0, rd);
    }

    public void signx(Register rd) {
        sra(rd, g0, rd);
    }

    public boolean isImmediateConstantLoad() {
        return immediateConstantLoad;
    }

    public ScratchRegister getScratchRegister() {
        return scratchRegister[nextFreeScratchRegister++];
    }

    public class ScratchRegister implements AutoCloseable {
        private final Register register;

        public ScratchRegister(Register register) {
            super();
            this.register = register;
        }

        public Register getRegister() {
            return register;
        }

        public void close() {
            assert nextFreeScratchRegister > 0 : "Close called too often";
            nextFreeScratchRegister--;
        }
    }
}
