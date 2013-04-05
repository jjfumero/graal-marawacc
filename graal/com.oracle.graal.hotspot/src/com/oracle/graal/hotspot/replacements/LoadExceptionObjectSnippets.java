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

import static com.oracle.graal.hotspot.replacements.HotSpotSnippetUtils.*;
import static com.oracle.graal.nodes.extended.UnsafeCastNode.*;
import static com.oracle.graal.replacements.SnippetTemplate.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.replacements.*;
import com.oracle.graal.replacements.SnippetTemplate.AbstractTemplates;
import com.oracle.graal.replacements.SnippetTemplate.Arguments;
import com.oracle.graal.replacements.SnippetTemplate.Key;
import com.oracle.graal.word.*;

/**
 * Snippet for loading the exception object at the start of an exception dispatcher.
 */
public class LoadExceptionObjectSnippets implements Snippets {

    @Snippet
    public static Object loadException() {
        Word thread = thread();
        Object exception = readExceptionOop(thread);
        writeExceptionOop(thread, null);
        writeExceptionPc(thread, Word.zero());
        return unsafeCast(exception, StampFactory.forNodeIntrinsic());
    }

    public static class Templates extends AbstractTemplates<LoadExceptionObjectSnippets> {

        private final ResolvedJavaMethod loadException;

        public Templates(CodeCacheProvider runtime, Replacements replacements, TargetDescription target) {
            super(runtime, replacements, target, LoadExceptionObjectSnippets.class);
            loadException = snippet("loadException");
        }

        public void lower(LoadExceptionObjectNode loadExceptionObject) {
            StructuredGraph graph = (StructuredGraph) loadExceptionObject.graph();
            Arguments arguments = new Arguments();

            Key key = new Key(loadException);
            SnippetTemplate template = cache.get(key);
            Debug.log("Lowering exception object in %s: node=%s, template=%s, arguments=%s", graph, loadExceptionObject, template, arguments);
            template.instantiate(runtime, loadExceptionObject, DEFAULT_REPLACER, arguments);
        }
    }
}
