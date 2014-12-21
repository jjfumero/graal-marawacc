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

import static com.oracle.graal.sparc.SPARC.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.asm.*;

public class SPARCMacroAssembler extends SPARCAssembler {

    /**
     * A sentinel value used as a place holder in an instruction stream for an address that will be
     * patched.
     */
    private static final SPARCAddress Placeholder = new SPARCAddress(g0, 0);

    public SPARCMacroAssembler(TargetDescription target, RegisterConfig registerConfig) {
        super(target, registerConfig);
    }

    @Override
    public void align(int modulus) {
        while (position() % modulus != 0) {
            new Nop().emit(this);
        }
    }

    @Override
    public void jmp(Label l) {
        new Bpa(l).emit(this);
        new Nop().emit(this);  // delay slot
    }

    @Override
    protected final void patchJumpTarget(int branch, int branchTarget) {
        final int disp = branchTarget - branch;
        Fmt00 fmt = Fmt00.read(this, branch);
        fmt.setImm(disp);
        fmt.write(this, branch);
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
        new Nop().emit(this);
    }

    public static class Bclr extends Andn {

        public Bclr(Register src, Register dst) {
            super(dst, src, dst);
        }

        public Bclr(int simm13, Register dst) {
            super(dst, simm13, dst);
        }
    }

    public static class Bpgeu extends Bpcc {

        public Bpgeu(CC cc, int simm19) {
            super(cc, simm19);
        }

        public Bpgeu(CC cc, Label label) {
            super(cc, label);
        }

        public Bpgeu(CC cc, boolean annul, boolean predictTaken, Label label) {
            super(cc, annul, predictTaken, label);
        }
    }

    public static class Bplu extends Bpcs {

        public Bplu(CC cc, int simm19) {
            super(cc, simm19);
        }

        public Bplu(CC cc, Label label) {
            super(cc, label);
        }

        public Bplu(CC cc, boolean annul, boolean predictTaken, Label label) {
            super(cc, annul, predictTaken, label);
        }
    }

    public static class Bset extends Or {

        public Bset(Register src, Register dst) {
            super(dst, src, dst);
        }

        public Bset(int simm13, Register dst) {
            super(dst, simm13, dst);
        }
    }

    public static class Btst extends Andcc {

        public Btst(Register src1, Register src2) {
            super(src1, src2, g0);
        }

        public Btst(Register src1, int simm13) {
            super(src1, simm13, g0);
        }
    }

    public static class Cas extends Casa {

        public Cas(Register src1, Register src2, Register dst) {
            super(src1, src2, dst, Asi.ASI_PRIMARY);
        }
    }

    public static class Casx extends Casxa {

        public Casx(Register src1, Register src2, Register dst) {
            super(src1, src2, dst, Asi.ASI_PRIMARY);
        }
    }

    public static class Clr extends Or {

        public Clr(Register dst) {
            super(g0, g0, dst);
        }
    }

    public static class Clrb extends Stb {

        public Clrb(SPARCAddress addr) {
            super(g0, addr);
        }
    }

    public static class Clrh extends Sth {

        public Clrh(SPARCAddress addr) {
            super(g0, addr);
        }
    }

    public static class Clrx extends Stx {

        public Clrx(SPARCAddress addr) {
            super(g0, addr);
        }
    }

    public static class Clruw extends Srl {

        public Clruw(Register src1, Register dst) {
            super(src1, g0, dst);
            assert src1.encoding() != dst.encoding();
        }

        public Clruw(Register dst) {
            super(dst, g0, dst);
        }
    }

    public static class Cmp extends Subcc {

        public Cmp(Register a, Register b) {
            super(a, b, g0);
        }

        public Cmp(Register a, int simm13) {
            super(a, simm13, g0);
        }
    }

    public static class Dec extends Sub {

        public Dec(Register dst) {
            super(dst, 1, dst);
        }

        public Dec(int simm13, Register dst) {
            super(dst, simm13, dst);
        }
    }

    public static class Deccc extends Subcc {

        public Deccc(Register dst) {
            super(dst, 1, dst);
        }

        public Deccc(int simm13, Register dst) {
            super(dst, simm13, dst);
        }
    }

    @SuppressWarnings("unused")
    public static class Inc {

        public Inc(Register dst) {
            new Add(dst, 1, dst);
        }

        public Inc(int simm13, Register dst) {
            new Add(dst, simm13, dst);
        }
    }

    @SuppressWarnings("unused")
    public static class Inccc {

        public Inccc(Register dst) {
            new Addcc(dst, 1, dst);
        }

        public Inccc(int simm13, Register dst) {
            new Addcc(dst, simm13, dst);
        }
    }

    public static class Jmp extends Jmpl {

        public Jmp(SPARCAddress address) {
            super(address.getBase(), address.getDisplacement(), g0);
        }

        public Jmp(Register reg) {
            super(reg, 0, g0);
        }
    }

    public static class Neg extends Sub {

        public Neg(Register src2, Register dst) {
            super(g0, src2, dst);
        }

        public Neg(Register dst) {
            super(g0, dst, dst);
        }
    }

    public static class Mov extends Or {

        public Mov(Register src1, Register dst) {
            super(g0, src1, dst);
            assert src1.encoding() != dst.encoding();
        }

        public Mov(int simm13, Register dst) {
            super(g0, simm13, dst);
        }
    }

    public static class Nop extends Sethi {

        public Nop() {
            super(0, r0);
        }
    }

    public static class Not extends Xnor {

        public Not(Register src1, Register dst) {
            super(src1, g0, dst);
        }

        public Not(Register dst) {
            super(dst, g0, dst);
        }
    }

    public static class RestoreWindow extends Restore {

        public RestoreWindow() {
            super(g0, g0, g0);
        }
    }

    public static class Ret extends Jmpl {

        public Ret() {
            super(i7, 8, g0);
        }
    }

    public static class SaveWindow extends Save {

        public SaveWindow() {
            super(g0, g0, g0);
        }
    }

    public static class Setuw {

        private int value;
        private Register dst;

        public Setuw(int value, Register dst) {
            this.value = value;
            this.dst = dst;
        }

        public void emit(SPARCMacroAssembler masm) {
            if (value == 0) {
                new Clr(dst).emit(masm);
            } else if (isSimm13(value)) {
                new Or(g0, value, dst).emit(masm);
            } else if (value >= 0 && ((value & 0x3FFF) == 0)) {
                new Sethi(hi22(value), dst).emit(masm);
            } else {
                new Sethi(hi22(value), dst).emit(masm);
                new Or(dst, lo10(value), dst).emit(masm);
            }
        }
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
        private AssemblerEmittable delayedInstruction;

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

        private void emitInstruction(AssemblerEmittable insn, SPARCMacroAssembler masm) {
            if (delayed) {
                if (this.delayedInstruction != null) {
                    delayedInstruction.emit(masm);
                }
                delayedInstruction = insn;
            } else {
                insn.emit(masm);
            }
        }

        public void emit(SPARCMacroAssembler masm) {
            int hi = (int) (value >> 32);
            int lo = (int) (value & ~0);

            // This is the same logic as MacroAssembler::internal_set.
            final int startPc = masm.position();

            if (hi == 0 && lo >= 0) {
                emitInstruction(new Sethi(hi22(lo), dst), masm);
            } else if (hi == -1) {
                emitInstruction(new Sethi(hi22(~lo), dst), masm);
                emitInstruction(new Xor(dst, ~lo10(~0), dst), masm);
            } else {
                int shiftcnt = 0;
                emitInstruction(new Sethi(hi22(hi), dst), masm);
                if ((hi & 0x3ff) != 0) {                                  // Any bits?
                    // msb 32-bits are now in lsb 32
                    emitInstruction(new Or(dst, hi & 0x3ff, dst), masm);
                }
                if ((lo & 0xFFFFFC00) != 0) {                             // done?
                    if (((lo >> 20) & 0xfff) != 0) {                      // Any bits set?
                        // Make room for next 12 bits
                        emitInstruction(new Sllx(dst, 12, dst), masm);
                        // Or in next 12
                        emitInstruction(new Or(dst, (lo >> 20) & 0xfff, dst), masm);
                        shiftcnt = 0;                                     // We already shifted
                    } else {
                        shiftcnt = 12;
                    }
                    if (((lo >> 10) & 0x3ff) != 0) {
                        // Make room for last 10 bits
                        emitInstruction(new Sllx(dst, shiftcnt + 10, dst), masm);
                        // Or in next 10
                        emitInstruction(new Or(dst, (lo >> 10) & 0x3ff, dst), masm);
                        shiftcnt = 0;
                    } else {
                        shiftcnt = 10;
                    }
                    // Shift leaving disp field 0'd
                    emitInstruction(new Sllx(dst, shiftcnt + 10, dst), masm);
                } else {
                    emitInstruction(new Sllx(dst, 32, dst), masm);
                }
            }
            // Pad out the instruction sequence so it can be patched later.
            if (forceRelocatable) {
                while (masm.position() < (startPc + (INSTRUCTION_SIZE * 4))) {
                    emitInstruction(new Nop(), masm);
                }
            }
        }

        public void emitDelayed(SPARCMacroAssembler masm) {
            assert delayedInstruction != null;
            delayedInstruction.emit(masm);
        }
    }

    public static class Setx {

        private long value;
        private Register dst;
        private boolean forceRelocatable;
        private boolean delayed = false;
        private boolean delayedFirstEmitted = false;
        private Sethix sethix;
        private AssemblerEmittable delayedAdd;

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
                Add add = new Add(dst, lo10(lo), dst);
                if (delayed) {
                    sethix.emitDelayed(masm);
                    sethix = null;
                    delayedAdd = add;
                } else {
                    sethix = null;
                    add.emit(masm);
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
                delayedAdd.emit(masm);
            } else {
                sethix.emitDelayed(masm);
            }

        }
    }

    public static class Signx extends Sra {

        public Signx(Register src1, Register dst) {
            super(src1, g0, dst);
        }

        public Signx(Register dst) {
            super(dst, g0, dst);
        }
    }
}
