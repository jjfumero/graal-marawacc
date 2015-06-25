/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.truffle.test.builtins;

import jdk.internal.jvmci.options.*;

import com.oracle.graal.truffle.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.*;
import com.oracle.truffle.api.nodes.*;

/**
 * Sets an option value in {@link TruffleCompilerOptions}. In the future this builtin might be
 * extend to lookup other options as well.
 */
@NodeInfo(shortName = "setOption")
public abstract class SLSetOptionBuiltin extends SLGraalRuntimeBuiltin {

    @Specialization
    @TruffleBoundary
    public Object setOption(String name, Object value) {
        TruffleCompilerOptions_Options options = new TruffleCompilerOptions_Options();
        for (OptionDescriptor option : options) {
            if (option.getName().equals(name)) {
                option.getOptionValue().setValue(convertValue(value));
            }
        }
        return value;
    }

    private static Object convertValue(Object value) {
        // Improve this method as you need it.
        if (value instanceof Long) {
            long longValue = (long) value;
            if (longValue == (int) longValue) {
                return (int) longValue;
            }
        }
        return value;
    }

}
