/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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

/**
 * Denotes a register that stores a value of a fixed kind. There is exactly one (canonical) instance
 * of {@link RegisterValue} for each ({@link Register}, {@link Kind}) pair. Use
 * {@link Register#asValue(Kind)} to retrieve the canonical {@link RegisterValue} instance for a
 * given (register,kind) pair.
 */
public final class RegisterValue extends Value {

    private static final long serialVersionUID = 7999341472196897163L;

    private final Register reg;

    /**
     * Should only be called from {@link Register#Register} to ensure canonicalization.
     */
    protected RegisterValue(Kind kind, Register register) {
        super(kind);
        this.reg = register;
    }

    @Override
    public int hashCode() {
        return (getRegister().number << 4) ^ getKind().ordinal();
    }

    @Override
    public String toString() {
        return getRegister().name + getKindSuffix();
    }

    /**
     * @return the register that contains the value
     */
    public Register getRegister() {
        return reg;
    }
}
