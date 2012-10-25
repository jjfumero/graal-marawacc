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
package com.oracle.graal.api.code;

import java.util.*;

import com.oracle.graal.api.code.Register.*;


/**
 * Represents a CPU architecture, including information such as its endianness, CPU
 * registers, word width, etc.
 */
public abstract class Architecture {

    /**
     * The endianness of the architecture.
     */
    public static enum ByteOrder {
        LittleEndian,
        BigEndian
    }

    /**
     * The number of bits required in a bit map covering all the registers that may store references.
     * The bit position of a register in the map is the register's {@linkplain Register#number number}.
     */
    public final int registerReferenceMapBitCount;

    /**
     * Represents the natural size of words (typically registers and pointers) of this architecture, in bytes.
     */
    public final int wordSize;

    /**
     * The name of this architecture (e.g. "AMD64", "SPARCv9").
     */
    public final String name;

    /**
     * Array of all available registers on this architecture. The index of each register in this
     * array is equal to its {@linkplain Register#number number}.
     */
    public final Register[] registers;

    /**
     * Map of all registers keyed by their {@linkplain Register#name names}.
     */
    public final HashMap<String, Register> registersByName;

    /**
     * The byte ordering can be either little or big endian.
     */
    public final ByteOrder byteOrder;

    /**
     * Mask of the barrier constants denoting the barriers that
     * are not required to be explicitly inserted under this architecture.
     */
    public final int implicitMemoryBarriers;

    /**
     * Determines the barriers in a given barrier mask that are explicitly required on this architecture.
     *
     * @param barriers a mask of the barrier constants
     * @return the value of {@code barriers} minus the barriers unnecessary on this architecture
     */
    public final int requiredBarriers(int barriers) {
        return barriers & ~implicitMemoryBarriers;
    }

    /**
     * Offset in bytes from the beginning of a call instruction to the displacement.
     */
    public final int machineCodeCallDisplacementOffset;

    /**
     * The size of the return address pushed to the stack by a call instruction.
     * A value of 0 denotes that call linkage uses registers instead (e.g. SPARC).
     */
    public final int returnAddressSize;

    private final EnumMap<RegisterFlag, Register[]> registersByTypeAndEncoding;

    /**
     * Gets the register for a given {@linkplain Register#encoding encoding} and type.
     *
     * @param encoding a register value as used in a machine instruction
     * @param type the type of the register
     */
    public Register registerFor(int encoding, RegisterFlag type) {
        Register[] regs = registersByTypeAndEncoding.get(type);
        assert encoding >= 0 && encoding < regs.length;
        Register reg = regs[encoding];
        assert reg != null;
        return reg;
    }

    protected Architecture(String name,
                    int wordSize,
                    ByteOrder byteOrder,
                    Register[] registers,
                    int implicitMemoryBarriers,
                    int nativeCallDisplacementOffset,
                    int registerReferenceMapBitCount,
                    int returnAddressSize) {
        this.name = name;
        this.registers = registers;
        this.wordSize = wordSize;
        this.byteOrder = byteOrder;
        this.implicitMemoryBarriers = implicitMemoryBarriers;
        this.machineCodeCallDisplacementOffset = nativeCallDisplacementOffset;
        this.registerReferenceMapBitCount = registerReferenceMapBitCount;
        this.returnAddressSize = returnAddressSize;

        registersByName = new HashMap<>(registers.length);
        for (Register register : registers) {
            registersByName.put(register.name, register);
            assert registers[register.number] == register;
        }

        registersByTypeAndEncoding = new EnumMap<>(RegisterFlag.class);
        EnumMap<RegisterFlag, Register[]> categorizedRegs = Register.categorize(registers);
        for (RegisterFlag type : RegisterFlag.values()) {
            Register[] regs = categorizedRegs.get(type);
            int max = Register.maxRegisterEncoding(regs);
            Register[] regsByEnc = new Register[max + 1];
            for (Register reg : regs) {
                regsByEnc[reg.encoding] = reg;
            }
            registersByTypeAndEncoding.put(type, regsByEnc);
        }
    }

    /**
     * Converts this architecture to a string.
     * @return the string representation of this architecture
     */
    @Override
    public final String toString() {
        return name.toLowerCase();
    }

    /**
     * Checks whether this is a 32-bit architecture.
     * @return {@code true} if this architecture is 32-bit
     */
    public final boolean is32bit() {
        return wordSize == 4;
    }

    /**
     * Checks whether this is a 64-bit architecture.
     * @return {@code true} if this architecture is 64-bit
     */
    public final boolean is64bit() {
        return wordSize == 8;
    }

    /**
     * Checks whether this architecture's normal arithmetic instructions use a two-operand form
     * (e.g. x86 which overwrites one operand register with the result when adding).
     * @return {@code true} if this architecture uses two-operand mode
     */
    public abstract boolean twoOperandMode();
}
