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
package com.oracle.graal.hotspot.snippets;

import static com.oracle.graal.hotspot.snippets.HotSpotSnippetUtils.*;
import static com.oracle.graal.nodes.extended.UnsafeCastNode.*;

import java.lang.reflect.*;

import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.snippets.*;
import com.oracle.graal.snippets.ClassSubstitution.MethodSubstitution;
import com.oracle.graal.word.*;

/**
 * Substitutions for {@link java.lang.Class} methods.
 */
@ClassSubstitution(java.lang.Class.class)
public class ClassSubstitutions {

    @MethodSubstitution(isStatic = false)
    public static int getModifiers(final Class<?> thisObj) {
        Word klass = loadWordFromObject(thisObj, klassOffset());
        if (klass.equal(0)) {
            // Class for primitive type
            return Modifier.ABSTRACT | Modifier.FINAL | Modifier.PUBLIC;
        } else {
            return klass.readInt(klassModifierFlagsOffset());
        }
    }

    @MethodSubstitution(isStatic = false)
    public static boolean isInterface(final Class<?> thisObj) {
        Word klass = loadWordFromObject(thisObj, klassOffset());
        if (klass.equal(0)) {
            return false;
        } else {
            int accessFlags = klass.readInt(klassAccessFlagsOffset());
            return (accessFlags & Modifier.INTERFACE) != 0;
        }
    }

    @MethodSubstitution(isStatic = false)
    public static boolean isArray(final Class<?> thisObj) {
        Word klass = loadWordFromObject(thisObj, klassOffset());
        if (klass.equal(0)) {
            return false;
        } else {
            int layoutHelper = klass.readInt(klassLayoutHelperOffset());
            return (layoutHelper & arrayKlassLayoutHelperIdentifier()) != 0;
        }
    }

    @MethodSubstitution(isStatic = false)
    public static boolean isPrimitive(final Class<?> thisObj) {
        Word klass = loadWordFromObject(thisObj, klassOffset());
        return klass.equal(0);
    }

    @MethodSubstitution(isStatic = false)
    public static Class<?> getSuperclass(final Class<?> thisObj) {
        Word klass = loadWordFromObject(thisObj, klassOffset());
        if (klass.notEqual(0)) {
            int accessFlags = klass.readInt(klassAccessFlagsOffset());
            if ((accessFlags & Modifier.INTERFACE) == 0) {
                int layoutHelper = klass.readInt(klassLayoutHelperOffset());
                if ((layoutHelper & arrayKlassLayoutHelperIdentifier()) != 0) {
                    return Object.class;
                } else {
                    Word superKlass = klass.readWord(klassSuperKlassOffset());
                    if (superKlass.equal(0)) {
                        return null;
                    } else {
                        return unsafeCast(superKlass.readObject(classMirrorOffset()), Class.class, true, true);
                    }
                }
            }
        }
        return null;
    }

    @MethodSubstitution(isStatic = false)
    public static Class<?> getComponentType(final Class<?> thisObj) {
        Word klass = loadWordFromObject(thisObj, klassOffset());
        if (klass.notEqual(0)) {
            int layoutHelper = klass.readInt(klassLayoutHelperOffset());
            if ((layoutHelper & arrayKlassLayoutHelperIdentifier()) != 0) {
                return unsafeCast(klass.readObject(arrayKlassComponentMirrorOffset()), Class.class, true, true);
            }
        }
        return null;
    }

    @MethodSubstitution(isStatic = false)
    public static boolean isInstance(final Class<?> thisObj, Object obj) {
        return !thisObj.isPrimitive() && ConditionalNode.materializeIsInstance(thisObj, obj);
    }
}
