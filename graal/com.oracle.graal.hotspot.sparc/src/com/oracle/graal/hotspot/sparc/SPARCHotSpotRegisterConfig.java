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
package com.oracle.graal.hotspot.sparc;

import static com.oracle.graal.compiler.common.GraalOptions.*;
import static com.oracle.graal.sparc.SPARC.*;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.CallingConvention.Type;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.lir.framemap.*;
import com.oracle.graal.sparc.*;

public class SPARCHotSpotRegisterConfig implements RegisterConfig {

    private final Architecture architecture;

    private final Register[] allocatable;

    private final HashMap<PlatformKind, Register[]> categorized = new HashMap<>();

    private final RegisterAttributes[] attributesMap;

    @Override
    public Register[] getAllocatableRegisters() {
        return allocatable.clone();
    }

    public Register[] getAllocatableRegisters(PlatformKind kind) {
        if (categorized.containsKey(kind)) {
            return categorized.get(kind);
        }

        ArrayList<Register> list = new ArrayList<>();
        for (Register reg : getAllocatableRegisters()) {
            if (architecture.canStoreValue(reg.getRegisterCategory(), kind)) {
                // Special treatment for double precision
                // TODO: This is wasteful it uses only half of the registers as float.
                if (kind == Kind.Double) {
                    if (reg.name.startsWith("d")) {
                        list.add(reg);
                    }
                } else if (kind == Kind.Float) {
                    if (reg.name.startsWith("f")) {
                        list.add(reg);
                    }
                } else {
                    list.add(reg);
                }
            }
        }

        Register[] ret = list.toArray(new Register[list.size()]);
        categorized.put(kind, ret);
        return ret;
    }

    @Override
    public RegisterAttributes[] getAttributesMap() {
        return attributesMap.clone();
    }

    private final Register[] cpuCallerParameterRegisters = {o0, o1, o2, o3, o4, o5};
    private final Register[] cpuCalleeParameterRegisters = {i0, i1, i2, i3, i4, i5};

    private final Register[] fpuParameterRegisters = {f0, f1, f2, f3, f4, f5, f6, f7};
    // @formatter:off
    private final Register[] callerSaveRegisters =
                   {g1, g3, g4, g5, o0, o1, o2, o3, o4, o5, o7,
                    f0,  f1,  f2,  f3,  f4,  f5,  f6,  f7,
                    f8,  f9,  f10, f11, f12, f13, f14, f15,
                    f16, f17, f18, f19, f20, f21, f22, f23,
                    f24, f25, f26, f27, f28, f29, f30, f31,
                    d32, d34, d36, d38, d40, d42, d44, d46,
                    d48, d50, d52, d54, d56, d58, d60, d62};
    // @formatter:on

    /**
     * Registers saved by the callee. This lists all L and I registers which are saved in the
     * register window. {@link FrameMap} uses this array to calculate the spill area size.
     */
    private final Register[] calleeSaveRegisters = {l0, l1, l2, l3, l4, l5, l6, l7, i0, i1, i2, i3, i4, i5, i6, i7};

    private final CalleeSaveLayout csl;

    private static Register findRegister(String name, Register[] all) {
        for (Register reg : all) {
            if (reg.name.equals(name)) {
                return reg;
            }
        }
        throw new IllegalArgumentException("register " + name + " is not allocatable");
    }

    private static Register[] initAllocatable(boolean reserveForHeapBase) {
        Register[] registers = null;
        if (reserveForHeapBase) {
            // @formatter:off
            registers = new Register[]{
                        // TODO this is not complete
                        // o7 cannot be used as register because it is always overwritten on call
                        // and the current register handler would ignore this fact if the called
                        // method still does not modify registers, in fact o7 is modified by the Call instruction
                        // There would be some extra handlin necessary to be able to handle the o7 properly for local usage
                        o0, o1, o2, o3, o4, o5, /*o6, o7,*/
                        l0, l1, l2, l3, l4, l5, l6, l7,
                        i0, i1, i2, i3, i4, i5, /*i6,*/ /*i7,*/
                        //f0, f1, f2, f3, f4, f5, f6, f7,
                        f8,  f9,  f10, f11, f12, f13, f14, f15,
                        f16, f17, f18, f19, f20, f21, f22, f23,
                        f24, f25, f26, f27, f28, f29, f30, f31,
                        d32, d34, d36, d38, d40, d42, d44, d46,
                        d48, d50, d52, d54, d56, d58, d60, d62
            };
            // @formatter:on
        } else {
            // @formatter:off
            registers = new Register[]{
                        // TODO this is not complete
                        o0, o1, o2, o3, o4, o5, /*o6, o7,*/
                        l0, l1, l2, l3, l4, l5, l6, l7,
                        i0, i1, i2, i3, i4, i5, /*i6,*/ /*i7,*/
//                        f0, f1, f2, f3, f4, f5, f6, f7
                        f8,  f9,  f10, f11, f12, f13, f14, f15,
                        f16, f17, f18, f19, f20, f21, f22, f23,
                        f24, f25, f26, f27, f28, f29, f30, f31,
                        d32, d34, d36, d38, d40, d42, d44, d46,
                        d48, d50, d52, d54, d56, d58, d60, d62
            };
            // @formatter:on
        }

        if (RegisterPressure.getValue() != null) {
            String[] names = RegisterPressure.getValue().split(",");
            Register[] regs = new Register[names.length];
            for (int i = 0; i < names.length; i++) {
                regs[i] = findRegister(names[i], registers);
            }
            return regs;
        }

        return registers;
    }

    public SPARCHotSpotRegisterConfig(TargetDescription target, HotSpotVMConfig config) {
        this(target, initAllocatable(config.useCompressedOops));
    }

    public SPARCHotSpotRegisterConfig(TargetDescription target, Register[] allocatable) {
        this.architecture = target.arch;

        csl = new CalleeSaveLayout(target, -1, -1, target.arch.getWordSize(), calleeSaveRegisters);
        this.allocatable = allocatable.clone();
        attributesMap = RegisterAttributes.createMap(this, SPARC.allRegisters);
    }

    @Override
    public Register[] getCallerSaveRegisters() {
        return callerSaveRegisters;
    }

    @Override
    public boolean areAllAllocatableRegistersCallerSaved() {
        return false;
    }

    @Override
    public Register getRegisterForRole(int index) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CallingConvention getCallingConvention(Type type, JavaType returnType, JavaType[] parameterTypes, TargetDescription target, boolean stackOnly) {
        if (type == Type.JavaCall || type == Type.NativeCall) {
            return callingConvention(cpuCallerParameterRegisters, returnType, parameterTypes, type, target, stackOnly);
        }
        if (type == Type.JavaCallee) {
            return callingConvention(cpuCalleeParameterRegisters, returnType, parameterTypes, type, target, stackOnly);
        }
        throw GraalInternalError.shouldNotReachHere();
    }

    public Register[] getCallingConventionRegisters(Type type, Kind kind) {
        if (architecture.canStoreValue(FPU, kind)) {
            return fpuParameterRegisters;
        }
        assert architecture.canStoreValue(CPU, kind);
        return type == Type.JavaCallee ? cpuCalleeParameterRegisters : cpuCallerParameterRegisters;
    }

    private CallingConvention callingConvention(Register[] generalParameterRegisters, JavaType returnType, JavaType[] parameterTypes, Type type, TargetDescription target, boolean stackOnly) {
        AllocatableValue[] locations = new AllocatableValue[parameterTypes.length];

        int currentGeneral = 0;
        int currentFloating = 0;
        int currentStackOffset = 0;

        for (int i = 0; i < parameterTypes.length; i++) {
            final Kind kind = parameterTypes[i].getKind();

            switch (kind) {
                case Byte:
                case Boolean:
                case Short:
                case Char:
                case Int:
                case Long:
                case Object:
                    if (!stackOnly && currentGeneral < generalParameterRegisters.length) {
                        Register register = generalParameterRegisters[currentGeneral++];
                        locations[i] = register.asValue(target.getLIRKind(kind));
                    }
                    break;
                case Double:
                    if (!stackOnly && currentFloating < fpuParameterRegisters.length) {
                        if (currentFloating % 2 != 0) {
                            // Make register number even to be a double reg
                            currentFloating++;
                        }
                        Register register = fpuParameterRegisters[currentFloating];
                        currentFloating += 2; // Only every second is a double register
                        locations[i] = register.asValue(target.getLIRKind(kind));
                    }
                    break;
                case Float:
                    if (!stackOnly && currentFloating < fpuParameterRegisters.length) {
                        Register register = fpuParameterRegisters[currentFloating++];
                        locations[i] = register.asValue(target.getLIRKind(kind));
                    }
                    break;
                default:
                    throw GraalInternalError.shouldNotReachHere();
            }

            if (locations[i] == null) {
                // Stack slot is always aligned to its size in bytes but minimum wordsize
                int typeSize = SPARC.spillSlotSize(target, kind);
                currentStackOffset = NumUtil.roundUp(currentStackOffset, typeSize);
                locations[i] = StackSlot.get(target.getLIRKind(kind.getStackKind()), currentStackOffset, !type.out);
                currentStackOffset += typeSize;
            }
        }

        Kind returnKind = returnType == null ? Kind.Void : returnType.getKind();
        AllocatableValue returnLocation = returnKind == Kind.Void ? Value.ILLEGAL : getReturnRegister(returnKind, type).asValue(target.getLIRKind(returnKind.getStackKind()));
        return new CallingConvention(currentStackOffset, returnLocation, locations);
    }

    @Override
    public Register getReturnRegister(Kind kind) {
        return getReturnRegister(kind, Type.JavaCallee);
    }

    private static Register getReturnRegister(Kind kind, Type type) {
        switch (kind) {
            case Boolean:
            case Byte:
            case Char:
            case Short:
            case Int:
            case Long:
            case Object:
                return type == Type.JavaCallee ? i0 : o0;
            case Float:
            case Double:
                return f0;
            case Void:
            case Illegal:
                return null;
            default:
                throw new UnsupportedOperationException("no return register for type " + kind);
        }
    }

    @Override
    public Register getFrameRegister() {
        return sp;
    }

    public CalleeSaveLayout getCalleeSaveLayout() {
        return csl;
    }

    @Override
    public String toString() {
        return String.format("Allocatable: " + Arrays.toString(getAllocatableRegisters()) + "%n" + "CallerSave:  " + Arrays.toString(getCallerSaveRegisters()) + "%n");
    }
}
