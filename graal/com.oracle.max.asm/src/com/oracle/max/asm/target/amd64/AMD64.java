/*
 * Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.asm.target.amd64;

import static com.oracle.graal.api.code.Register.RegisterFlag.*;
import static com.oracle.graal.api.meta.Kind.*;
import static com.oracle.max.cri.util.MemoryBarriers.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.Register.*;

/**
 * Represents the AMD64 architecture.
 */
public class AMD64 extends Architecture {

    // General purpose CPU registers
    public static final Register rax = new Register(0, 0, 8, "rax", CPU, RegisterFlag.Byte);
    public static final Register rcx = new Register(1, 1, 8, "rcx", CPU, RegisterFlag.Byte);
    public static final Register rdx = new Register(2, 2, 8, "rdx", CPU, RegisterFlag.Byte);
    public static final Register rbx = new Register(3, 3, 8, "rbx", CPU, RegisterFlag.Byte);
    public static final Register rsp = new Register(4, 4, 8, "rsp", CPU, RegisterFlag.Byte);
    public static final Register rbp = new Register(5, 5, 8, "rbp", CPU, RegisterFlag.Byte);
    public static final Register rsi = new Register(6, 6, 8, "rsi", CPU, RegisterFlag.Byte);
    public static final Register rdi = new Register(7, 7, 8, "rdi", CPU, RegisterFlag.Byte);

    public static final Register r8  = new Register(8,  8,  8, "r8", CPU, RegisterFlag.Byte);
    public static final Register r9  = new Register(9,  9,  8, "r9", CPU, RegisterFlag.Byte);
    public static final Register r10 = new Register(10, 10, 8, "r10", CPU, RegisterFlag.Byte);
    public static final Register r11 = new Register(11, 11, 8, "r11", CPU, RegisterFlag.Byte);
    public static final Register r12 = new Register(12, 12, 8, "r12", CPU, RegisterFlag.Byte);
    public static final Register r13 = new Register(13, 13, 8, "r13", CPU, RegisterFlag.Byte);
    public static final Register r14 = new Register(14, 14, 8, "r14", CPU, RegisterFlag.Byte);
    public static final Register r15 = new Register(15, 15, 8, "r15", CPU, RegisterFlag.Byte);

    public static final Register[] cpuRegisters = {
        rax, rcx, rdx, rbx, rsp, rbp, rsi, rdi,
        r8, r9, r10, r11, r12, r13, r14, r15
    };

    // XMM registers
    public static final Register xmm0 = new Register(16, 0, 8, "xmm0", FPU);
    public static final Register xmm1 = new Register(17, 1, 8, "xmm1", FPU);
    public static final Register xmm2 = new Register(18, 2, 8, "xmm2", FPU);
    public static final Register xmm3 = new Register(19, 3, 8, "xmm3", FPU);
    public static final Register xmm4 = new Register(20, 4, 8, "xmm4", FPU);
    public static final Register xmm5 = new Register(21, 5, 8, "xmm5", FPU);
    public static final Register xmm6 = new Register(22, 6, 8, "xmm6", FPU);
    public static final Register xmm7 = new Register(23, 7, 8, "xmm7", FPU);

    public static final Register xmm8 =  new Register(24,  8, 8, "xmm8",  FPU);
    public static final Register xmm9 =  new Register(25,  9, 8, "xmm9",  FPU);
    public static final Register xmm10 = new Register(26, 10, 8, "xmm10", FPU);
    public static final Register xmm11 = new Register(27, 11, 8, "xmm11", FPU);
    public static final Register xmm12 = new Register(28, 12, 8, "xmm12", FPU);
    public static final Register xmm13 = new Register(29, 13, 8, "xmm13", FPU);
    public static final Register xmm14 = new Register(30, 14, 8, "xmm14", FPU);
    public static final Register xmm15 = new Register(31, 15, 8, "xmm15", FPU);

    public static final Register[] xmmRegisters = {
        xmm0, xmm1, xmm2,  xmm3,  xmm4,  xmm5,  xmm6,  xmm7,
        xmm8, xmm9, xmm10, xmm11, xmm12, xmm13, xmm14, xmm15
    };

    public static final Register[] cpuxmmRegisters = {
        rax,  rcx,  rdx,   rbx,   rsp,   rbp,   rsi,   rdi,
        r8,   r9,   r10,   r11,   r12,   r13,   r14,   r15,
        xmm0, xmm1, xmm2,  xmm3,  xmm4,  xmm5,  xmm6,  xmm7,
        xmm8, xmm9, xmm10, xmm11, xmm12, xmm13, xmm14, xmm15
    };

    /**
     * Register used to construct an instruction-relative address.
     */
    public static final Register rip = new Register(32, -1, 0, "rip");

    public static final Register[] allRegisters = {
        rax,  rcx,  rdx,   rbx,   rsp,   rbp,   rsi,   rdi,
        r8,   r9,   r10,   r11,   r12,   r13,   r14,   r15,
        xmm0, xmm1, xmm2,  xmm3,  xmm4,  xmm5,  xmm6,  xmm7,
        xmm8, xmm9, xmm10, xmm11, xmm12, xmm13, xmm14, xmm15,
        rip
    };

    public static final RegisterValue RSP = rsp.asValue(Long);

    public AMD64() {
        super("AMD64",
              8,
              ByteOrder.LittleEndian,
              allRegisters,
              LOAD_STORE | STORE_STORE,
              1,
              r15.encoding + 1,
              8);
    }

    @Override
    public boolean isX86() {
        return true;
    }

    @Override
    public boolean twoOperandMode() {
        return true;
    }

}
