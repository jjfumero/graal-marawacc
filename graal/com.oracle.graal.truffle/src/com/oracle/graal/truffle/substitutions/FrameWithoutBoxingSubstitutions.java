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
package com.oracle.graal.truffle.substitutions;

import com.oracle.graal.api.replacements.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.truffle.*;
import com.oracle.graal.truffle.nodes.frame.*;
import com.oracle.graal.truffle.nodes.typesystem.*;
import com.oracle.truffle.api.frame.*;

@ClassSubstitution(FrameWithoutBoxing.class)
public class FrameWithoutBoxingSubstitutions {

    @MethodSubstitution(isStatic = false)
    public static MaterializedFrame materialize(FrameWithoutBoxing frame) {
        return MaterializeFrameNode.materialize(frame);
    }

    @MacroSubstitution(macro = UnsafeTypeCastMacroNode.class, isStatic = true)
    public static native Object unsafeCast(Object value, Class<?> clazz, boolean condition, boolean nonNull);

    @MacroSubstitution(macro = CustomizedUnsafeLoadMacroNode.class, isStatic = true)
    public static native boolean unsafeGetBoolean(Object receiver, long offset, boolean condition, Object locationIdentity);

    @MacroSubstitution(macro = CustomizedUnsafeLoadMacroNode.class, isStatic = true)
    public static native byte unsafeGetByte(Object receiver, long offset, boolean condition, Object locationIdentity);

    @MacroSubstitution(macro = CustomizedUnsafeLoadMacroNode.class, isStatic = true)
    public static native int unsafeGetInt(Object receiver, long offset, boolean condition, Object locationIdentity);

    @MacroSubstitution(macro = CustomizedUnsafeLoadMacroNode.class, isStatic = true)
    public static native long unsafeGetLong(Object receiver, long offset, boolean condition, Object locationIdentity);

    @MacroSubstitution(macro = CustomizedUnsafeLoadMacroNode.class, isStatic = true)
    public static native float unsafeGetFloat(Object receiver, long offset, boolean condition, Object locationIdentity);

    @MacroSubstitution(macro = CustomizedUnsafeLoadMacroNode.class, isStatic = true)
    public static native double unsafeGetDouble(Object receiver, long offset, boolean condition, Object locationIdentity);

    @MacroSubstitution(macro = CustomizedUnsafeLoadMacroNode.class, isStatic = true)
    public static native Object unsafeGetObject(Object receiver, long offset, boolean condition, Object locationIdentity);

    @MacroSubstitution(macro = CustomizedUnsafeStoreMacroNode.class, isStatic = true)
    public static native void unsafePutBoolean(Object receiver, long offset, boolean value, Object locationIdentity);

    @MacroSubstitution(macro = CustomizedUnsafeStoreMacroNode.class, isStatic = true)
    public static native void unsafePutByte(Object receiver, long offset, byte value, Object locationIdentity);

    @MacroSubstitution(macro = CustomizedUnsafeStoreMacroNode.class, isStatic = true)
    public static native void unsafePutInt(Object receiver, long offset, int value, Object locationIdentity);

    @MacroSubstitution(macro = CustomizedUnsafeStoreMacroNode.class, isStatic = true)
    public static native void unsafePutLong(Object receiver, long offset, long value, Object locationIdentity);

    @MacroSubstitution(macro = CustomizedUnsafeStoreMacroNode.class, isStatic = true)
    public static native void unsafePutFloat(Object receiver, long offset, float value, Object locationIdentity);

    @MacroSubstitution(macro = CustomizedUnsafeStoreMacroNode.class, isStatic = true)
    public static native void unsafePutDouble(Object receiver, long offset, double value, Object locationIdentity);

    @MacroSubstitution(macro = CustomizedUnsafeStoreMacroNode.class, isStatic = true)
    public static native void unsafePutObject(Object receiver, long offset, Object value, Object locationIdentity);
}
