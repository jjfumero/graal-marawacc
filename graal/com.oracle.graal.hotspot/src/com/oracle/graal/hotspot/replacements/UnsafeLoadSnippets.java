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

import static com.oracle.graal.hotspot.replacements.HotSpotReplacementsUtil.*;
import static com.oracle.graal.replacements.SnippetTemplate.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.nodes.HeapAccess.BarrierType;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.replacements.*;
import com.oracle.graal.replacements.SnippetTemplate.AbstractTemplates;
import com.oracle.graal.replacements.SnippetTemplate.Arguments;
import com.oracle.graal.replacements.SnippetTemplate.SnippetInfo;
import com.oracle.graal.word.*;

public class UnsafeLoadSnippets implements Snippets {

    @Snippet
    public static Object lowerUnsafeLoad(Object object, long displacement) {
        Object fixedObject = FixedValueAnchorNode.getObject(object);
        if (object instanceof java.lang.ref.Reference && referentOffset() == displacement) {
            return Word.fromObject(fixedObject).readObject((int) displacement, BarrierType.PRECISE, true);
        } else {
            return Word.fromObject(fixedObject).readObject((int) displacement, BarrierType.NONE, true);
        }
    }

    public static class Templates extends AbstractTemplates {

        private final SnippetInfo unsafeLoad = snippet(UnsafeLoadSnippets.class, "lowerUnsafeLoad");

        public Templates(MetaAccessProvider metaAccess, ConstantReflectionProvider constantReflection, CodeCacheProvider codeCache, LoweringProvider lowerer, Replacements replacements,
                        TargetDescription target) {
            super(metaAccess, constantReflection, codeCache, lowerer, replacements, target);
        }

        public void lower(UnsafeLoadNode load, @SuppressWarnings("unused") LoweringTool tool) {
            Arguments args = new Arguments(unsafeLoad, load.graph().getGuardsStage());
            args.add("object", load.object());
            args.add("offset", load.offset());
            template(args).instantiate(metaAccess, load, DEFAULT_REPLACER, args);
        }
    }
}
