/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.reflect.*;
import java.util.zip.*;

import sun.misc.*;
import sun.reflect.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.replacements.*;
import com.oracle.graal.api.runtime.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.bridge.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.replacements.*;

@ServiceProvider(ReplacementsProvider.class)
public class HotSpotSubstitutions implements ReplacementsProvider {

    static class NamedType implements Type {
        private final String name;

        public NamedType(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @Override
    public void registerReplacements(MetaAccessProvider metaAccess, LoweringProvider loweringProvider, SnippetReflectionProvider snippetReflection, Replacements replacements, TargetDescription target) {
        replacements.registerSubstitutions(Object.class, ObjectSubstitutions.class);
        replacements.registerSubstitutions(System.class, SystemSubstitutions.class);
        replacements.registerSubstitutions(Thread.class, ThreadSubstitutions.class);
        replacements.registerSubstitutions(Unsafe.class, UnsafeSubstitutions.class);
        replacements.registerSubstitutions(Class.class, HotSpotClassSubstitutions.class);
        replacements.registerSubstitutions(CRC32.class, CRC32Substitutions.class);
        replacements.registerSubstitutions(Reflection.class, ReflectionSubstitutions.class);
        replacements.registerSubstitutions(NodeClass.class, HotSpotNodeClassSubstitutions.class);
        replacements.registerSubstitutions(Node.class, HotSpotNodeSubstitutions.class);
        replacements.registerSubstitutions(CompositeValueClass.class, CompositeValueClassSubstitutions.class);
        replacements.registerSubstitutions(CompilerToVMImpl.class, CompilerToVMImplSubstitutions.class);
        replacements.registerSubstitutions(new NamedType("com.sun.crypto.provider.AESCrypt"), AESCryptSubstitutions.class);
        replacements.registerSubstitutions(new NamedType("com.sun.crypto.provider.CipherBlockChaining"), CipherBlockChainingSubstitutions.class);
    }
}
