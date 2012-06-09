/*
 * Copyright (c) 2012, 2012, Oracle and/or its affiliates. All rights reserved.
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

import com.oracle.graal.api.meta.*;

public class ValueUtil {
    public static boolean isIllegal(Value value) {
        assert value != null;
        return value == Value.IllegalValue;
    }

    public static boolean isLegal(Value value) {
        return !isIllegal(value);
    }

    public static boolean isVirtualObject(Value value) {
        assert value != null;
        return value instanceof VirtualObject;
    }

    public static VirtualObject asVirtualObject(Value value) {
        assert value != null;
        return (VirtualObject) value;
    }

    public static boolean isConstant(Value value) {
        assert value != null;
        return value instanceof Constant;
    }

    public static Constant asConstant(Value value) {
        assert value != null;
        return (Constant) value;
    }


    public static boolean isStackSlot(Value value) {
        assert value != null;
        return value instanceof StackSlot;
    }

    public static StackSlot asStackSlot(Value value) {
        assert value != null;
        return (StackSlot) value;
    }

    public static boolean isAddress(Value value) {
        assert value != null;
        return value instanceof Address;
    }

    public static Address asAddress(Value value) {
        assert value != null;
        return (Address) value;
    }


    public static boolean isRegister(Value value) {
        assert value != null;
        return value instanceof RegisterValue;
    }

    public static Register asRegister(Value value) {
        assert value != null;
        return ((RegisterValue) value).getRegister();
    }

    public static Register asIntReg(Value value) {
        assert value.kind == Kind.Int || value.kind == Kind.Jsr;
        return asRegister(value);
    }

    public static Register asLongReg(Value value) {
        assert value.kind == Kind.Long : value.kind;
        return asRegister(value);
    }

    public static Register asObjectReg(Value value) {
        assert value.kind == Kind.Object;
        return asRegister(value);
    }

    public static Register asFloatReg(Value value) {
        assert value.kind == Kind.Float;
        return asRegister(value);
    }

    public static Register asDoubleReg(Value value) {
        assert value.kind == Kind.Double;
        return asRegister(value);
    }


    public static boolean sameRegister(Value v1, Value v2) {
        return isRegister(v1) && isRegister(v2) && asRegister(v1) == asRegister(v2);
    }

    public static boolean sameRegister(Value v1, Value v2, Value v3) {
        return sameRegister(v1, v2) && sameRegister(v1, v3);
    }

    public static boolean differentRegisters(Value v1, Value v2) {
        return !isRegister(v1) || !isRegister(v2) || asRegister(v1) != asRegister(v2);
    }

    public static boolean differentRegisters(Value v1, Value v2, Value v3) {
        return differentRegisters(v1, v2) && differentRegisters(v1, v3) && differentRegisters(v2, v3);
    }
}
