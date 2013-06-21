/*
 * Copyright (c) 2009, 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.sparc;

import static com.oracle.graal.api.code.MemoryBarriers.*;

import java.nio.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.Register.RegisterCategory;
import com.oracle.graal.api.meta.*;

/**
 * Represents the SPARC architecture.
 */
public class SPARC extends Architecture {

    // @formatter:off

    public static final RegisterCategory CPU = new RegisterCategory("CPU");
    public static final RegisterCategory FPU = new RegisterCategory("FPU");

    // General purpose registers
    public static final Register r0  = new Register(0, 0, "r0", CPU);
    public static final Register r1  = new Register(1, 1, "r1", CPU);
    public static final Register r2  = new Register(2, 2, "r2", CPU);
    public static final Register r3  = new Register(3, 3, "r3", CPU);
    public static final Register r4  = new Register(4, 4, "r4", CPU);
    public static final Register r5  = new Register(5, 5, "r5", CPU);
    public static final Register r6  = new Register(6, 6, "r6", CPU);
    public static final Register r7  = new Register(7, 7, "r7", CPU);
    public static final Register r8  = new Register(8, 8, "r8", CPU);
    public static final Register r9  = new Register(9, 9, "r9", CPU);
    public static final Register r10 = new Register(10, 10, "r10", CPU);
    public static final Register r11 = new Register(11, 11, "r11", CPU);
    public static final Register r12 = new Register(12, 12, "r12", CPU);
    public static final Register r13 = new Register(13, 13, "r13", CPU);
    public static final Register r14 = new Register(14, 14, "r14", CPU);
    public static final Register r15 = new Register(15, 15, "r15", CPU);
    public static final Register r16 = new Register(16, 16, "r16", CPU);
    public static final Register r17 = new Register(17, 17, "r17", CPU);
    public static final Register r18 = new Register(18, 18, "r18", CPU);
    public static final Register r19 = new Register(19, 19, "r19", CPU);
    public static final Register r20 = new Register(20, 20, "r20", CPU);
    public static final Register r21 = new Register(21, 21, "r21", CPU);
    public static final Register r22 = new Register(22, 22, "r22", CPU);
    public static final Register r23 = new Register(23, 23, "r23", CPU);
    public static final Register r24 = new Register(24, 24, "r24", CPU);
    public static final Register r25 = new Register(25, 25, "r25", CPU);
    public static final Register r26 = new Register(26, 26, "r26", CPU);
    public static final Register r27 = new Register(27, 27, "r27", CPU);
    public static final Register r28 = new Register(28, 28, "r28", CPU);
    public static final Register r29 = new Register(29, 29, "r29", CPU);
    public static final Register r30 = new Register(30, 30, "r30", CPU);
    public static final Register r31 = new Register(31, 31, "r31", CPU);

    public static final Register g0 = r0;
    public static final Register g1 = r1;
    public static final Register g2 = r2;
    public static final Register g3 = r3;
    public static final Register g4 = r4;
    public static final Register g5 = r5;
    public static final Register g6 = r6;
    public static final Register g7 = r7;

    public static final Register o0 = r8;
    public static final Register o1 = r9;
    public static final Register o2 = r10;
    public static final Register o3 = r11;
    public static final Register o4 = r12;
    public static final Register o5 = r13;
    public static final Register o6 = r14;
    public static final Register o7 = r15;

    public static final Register l0 = r16;
    public static final Register l1 = r17;
    public static final Register l2 = r18;
    public static final Register l3 = r19;
    public static final Register l4 = r20;
    public static final Register l5 = r21;
    public static final Register l6 = r22;
    public static final Register l7 = r23;

    public static final Register i0 = r24;
    public static final Register i1 = r25;
    public static final Register i2 = r26;
    public static final Register i3 = r27;
    public static final Register i4 = r28;
    public static final Register i5 = r29;
    public static final Register i6 = r30;
    public static final Register i7 = r31;

    public static final Register fp = i6;
    public static final Register sp = o6;

    public static final Register[] gprRegisters = {
        r0,  r1,  r2,  r3,  r4,  r5,  r6,  r7,
         r8,  r9, r10, r11, r12, r13, r14, r15,
        r16, r17, r18, r19, r20, r21, r22, r23,
        r24, r25, r26, r27, r28, r29, r30, r31
    };

    // Floating point registers
    public static final Register f0 = new Register(32, 0, "f0", FPU);
    public static final Register f1 = new Register(33, 1, "f1", FPU);
    public static final Register f2 = new Register(34, 2, "f2", FPU);
    public static final Register f3 = new Register(35, 3, "f3", FPU);
    public static final Register f4 = new Register(36, 4, "f4", FPU);
    public static final Register f5 = new Register(37, 5, "f5", FPU);
    public static final Register f6 = new Register(38, 6, "f6", FPU);
    public static final Register f7 = new Register(39, 7, "f7", FPU);

    public static final Register[] allRegisters = {
        // GPR
        r0,  r1,  r2,  r3,  r4,  r5,  r6,  r7,
        r8,  r9, r10, r11, r12, r13, r14, r15,
       r16, r17, r18, r19, r20, r21, r22, r23,
       r24, r25, r26, r27, r28, r29, r30, r31,
        // FPU
        f0,  f1,  f2,  f3,  f4,  f5,  f6,  f7,
    };

    public SPARC() {
        super("SPARC",
              8,
              ByteOrder.BIG_ENDIAN,
              allRegisters,
              LOAD_STORE | STORE_STORE,
              1,
              r31.encoding + 1,
              8);
    }

    @Override
    public boolean canStoreValue(RegisterCategory category, PlatformKind platformKind) {
        if (!(platformKind instanceof Kind)) {
            return false;
        }

        Kind kind = (Kind) platformKind;
        if (category == CPU) {
            switch (kind) {
                case Boolean:
                case Byte:
                case Char:
                case Short:
                case Int:
                case Long:
                case Object:
                    return true;
            }
        } else if (category == FPU) {
            switch (kind) {
                case Float:
                case Double:
                    return true;
            }
        }

        return false;
    }

    @Override
    public PlatformKind getLargestStorableKind(RegisterCategory category) {
        throw new InternalError("NYI");
    }
}
