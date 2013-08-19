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

import static com.oracle.graal.api.code.DeoptimizationAction.*;
import static com.oracle.graal.api.meta.DeoptimizationReason.*;
import static com.oracle.graal.hotspot.replacements.HotSpotReplacementsUtil.*;
import static com.oracle.graal.hotspot.replacements.TypeCheckSnippetUtils.*;
import static com.oracle.graal.nodes.extended.BranchProbabilityNode.*;
import static com.oracle.graal.nodes.extended.UnsafeCastNode.*;
import static com.oracle.graal.replacements.SnippetTemplate.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.replacements.*;
import com.oracle.graal.replacements.SnippetTemplate.AbstractTemplates;
import com.oracle.graal.replacements.SnippetTemplate.Arguments;
import com.oracle.graal.replacements.SnippetTemplate.SnippetInfo;
import com.oracle.graal.word.*;

/**
 * Snippet used for lowering {@link CheckCastDynamicNode}.
 */
public class CheckCastDynamicSnippets implements Snippets {

    @Snippet
    public static Object checkcastDynamic(Word hub, Object object) {
        if (probability(NOT_FREQUENT_PROBABILITY, object == null)) {
            isNull.inc();
        } else {
            BeginNode anchorNode = BeginNode.anchor();
            Word objectHub = loadHubIntrinsic(object, getWordKind(), anchorNode);
            if (!checkUnknownSubType(hub, objectHub)) {
                DeoptimizeNode.deopt(InvalidateReprofile, ClassCastException);
            }
        }
        BeginNode anchorNode = BeginNode.anchor();
        return unsafeCast(verifyOop(object), StampFactory.forNodeIntrinsic(), anchorNode);
    }

    public static class Templates extends AbstractTemplates {

        private final SnippetInfo dynamic = snippet(CheckCastDynamicSnippets.class, "checkcastDynamic");

        public Templates(CodeCacheProvider runtime, Replacements replacements, TargetDescription target) {
            super(runtime, replacements, target);
        }

        public void lower(CheckCastDynamicNode checkcast) {
            StructuredGraph graph = checkcast.graph();
            ValueNode object = checkcast.object();

            Arguments args = new Arguments(dynamic);
            args.add("hub", checkcast.hub());
            args.add("object", object);

            SnippetTemplate template = template(args);
            Debug.log("Lowering dynamic checkcast in %s: node=%s, template=%s, arguments=%s", graph, checkcast, template, args);
            template.instantiate(runtime, checkcast, DEFAULT_REPLACER, args);
        }
    }
}
