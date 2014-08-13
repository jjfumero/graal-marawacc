/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package com.oracle.truffle.api.utilities;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.CompilerDirectives.*;

/**
 * Utility class to speculate on conditions to be never true or to be never false. Condition
 * profiles are intended to be used as part of if conditions.
 *
 * Example usage:
 *
 * <pre>
 * private final ConditionProfile zero = new BooleanConditionProfile();
 * 
 * int value = ...;
 * if (zero.profile(value == 0)) {
 *   return 0;
 * } else {
 *   return value;
 * }
 *
 * </pre>
 *
 * @see ConditionProfile
 * @see IntegerConditionProfile
 */
public class BooleanConditionProfile extends ConditionProfile {

    @CompilationFinal private boolean wasTrue;
    @CompilationFinal private boolean wasFalse;

    @Override
    public boolean profile(boolean value) {
        if (value) {
            if (!wasTrue) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                wasTrue = true;
            }
        } else {
            if (!wasFalse) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                wasFalse = true;
            }
        }
        return value;
    }

    public boolean wasTrue() {
        return wasTrue;
    }

    public boolean wasFalse() {
        return wasFalse;
    }

    @Override
    public String toString() {
        return String.format("%s(wasTrue=%s, wasFalse=%s)@%x", getClass().getSimpleName(), wasTrue, wasFalse, hashCode());
    }
}
