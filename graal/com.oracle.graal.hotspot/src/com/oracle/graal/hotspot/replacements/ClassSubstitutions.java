/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.replacements;

import static com.oracle.graal.hotspot.replacements.HotSpotReplacementsUtil.*;
import static com.oracle.graal.nodes.PiNode.*;

import java.lang.reflect.*;

import com.oracle.graal.api.replacements.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.word.*;

/**
 * Substitutions for {@link java.lang.Class} methods.
 */
@ClassSubstitution(java.lang.Class.class)
public class ClassSubstitutions {

    @MacroSubstitution(macro = ClassGetModifiersNode.class, isStatic = false)
    @MethodSubstitution(isStatic = false, forced = true)
    public static int getModifiers(final Class<?> thisObj) {
        TypePointer klass = ClassGetHubNode.readClass(thisObj);
        TypePointer zero = Word.unsigned(0).toTypePointer();
        if (Word.equal(klass, zero)) {
            // Class for primitive type
            return Modifier.ABSTRACT | Modifier.FINAL | Modifier.PUBLIC;
        } else {
            return Word.fromTypePointer(klass).readInt(klassModifierFlagsOffset(), KLASS_MODIFIER_FLAGS_LOCATION);
        }
    }

    // This MacroSubstitution should be removed once non-null klass pointers can be optimized
    @MacroSubstitution(macro = ClassIsInterfaceNode.class, isStatic = false)
    @MethodSubstitution(isStatic = false, forced = true)
    public static boolean isInterface(final Class<?> thisObj) {
        Pointer klass = Word.fromTypePointer(ClassGetHubNode.readClass(thisObj));
        if (klass.equal(0)) {
            return false;
        } else {
            int accessFlags = klass.readInt(klassAccessFlagsOffset(), KLASS_ACCESS_FLAGS_LOCATION);
            return (accessFlags & Modifier.INTERFACE) != 0;
        }
    }

    // This MacroSubstitution should be removed once non-null klass pointers can be optimized
    @MacroSubstitution(macro = ClassIsArrayNode.class, isStatic = false)
    @MethodSubstitution(isStatic = false, forced = true)
    public static boolean isArray(final Class<?> thisObj) {
        TypePointer klassPtr = ClassGetHubNode.readClass(thisObj);
        Pointer klass = Word.fromTypePointer(klassPtr);
        if (klass.equal(0)) {
            return false;
        } else {
            return klassIsArray(klassPtr);
        }
    }

    // This MacroSubstitution should be removed once non-null klass pointers can be optimized
    @MacroSubstitution(macro = ClassIsPrimitiveNode.class, isStatic = false)
    @MethodSubstitution(isStatic = false, forced = true)
    public static boolean isPrimitive(final Class<?> thisObj) {
        Pointer klass = Word.fromTypePointer(ClassGetHubNode.readClass(thisObj));
        return klass.equal(0);
    }

    @MacroSubstitution(macro = ClassGetClassLoader0Node.class, isStatic = false)
    public static native ClassLoader getClassLoader0(Class<?> thisObj);

    @MacroSubstitution(macro = ClassGetSuperclassNode.class, isStatic = false)
    @MethodSubstitution(isStatic = false)
    public static Class<?> getSuperclass(final Class<?> thisObj) {
        TypePointer klassPtr = ClassGetHubNode.readClass(thisObj);
        Pointer klass = Word.fromTypePointer(klassPtr);
        if (klass.notEqual(0)) {
            int accessFlags = klass.readInt(klassAccessFlagsOffset(), KLASS_ACCESS_FLAGS_LOCATION);
            if ((accessFlags & Modifier.INTERFACE) == 0) {
                if (klassIsArray(klassPtr)) {
                    return Object.class;
                } else {
                    Word superKlass = klass.readWord(klassSuperKlassOffset(), KLASS_SUPER_KLASS_LOCATION);
                    if (superKlass.equal(0)) {
                        return null;
                    } else {
                        return piCastExactNonNull(superKlass.readObject(classMirrorOffset(), CLASS_MIRROR_LOCATION), Class.class);
                    }
                }
            }
        }
        return null;
    }

    @MacroSubstitution(macro = ClassGetComponentTypeNode.class, isStatic = false)
    @MethodSubstitution(isStatic = false)
    public static Class<?> getComponentType(final Class<?> thisObj) {
        TypePointer klassPtr = ClassGetHubNode.readClass(thisObj);
        Pointer klass = Word.fromTypePointer(klassPtr);
        if (klass.notEqual(0)) {
            if (klassIsArray(klassPtr)) {
                return piCastExactNonNull(klass.readObject(arrayKlassComponentMirrorOffset(), ARRAY_KLASS_COMPONENT_MIRROR), Class.class);
            }
        }
        return null;
    }

    @MacroSubstitution(macro = ClassIsInstanceNode.class, isStatic = false)
    @MethodSubstitution(isStatic = false)
    public static boolean isInstance(Class<?> thisObj, Object obj) {
        return ConditionalNode.materializeIsInstance(thisObj, obj);
    }

    @MacroSubstitution(macro = ClassCastNode.class, isStatic = false)
    public static native Object cast(final Class<?> thisObj, Object obj);
}
