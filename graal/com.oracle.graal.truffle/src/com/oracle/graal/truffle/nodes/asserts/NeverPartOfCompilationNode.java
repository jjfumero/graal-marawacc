/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.truffle.nodes.asserts;

import com.oracle.graal.graph.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.replacements.nodes.*;

@NodeInfo
public class NeverPartOfCompilationNode extends MacroStateSplitNode implements IterableNodeType {

    public static final NodeClass TYPE = NodeClass.get(NeverPartOfCompilationNode.class);
    protected final String message;

    public NeverPartOfCompilationNode(Invoke invoke) {
        this(TYPE, invoke, "This code path should never be part of a compilation.");
    }

    protected NeverPartOfCompilationNode(NodeClass c, Invoke invoke, String message) {
        super(c, invoke);
        this.message = message;
    }

    public final String getMessage() {
        return message + " " + arguments.toString();
    }

    public static void verifyNotFoundIn(final StructuredGraph graph) {
        for (NeverPartOfCompilationNode neverPartOfCompilationNode : graph.getNodes(NeverPartOfCompilationNode.class)) {
            Throwable exception = new VerificationError(neverPartOfCompilationNode.getMessage());
            throw GraphUtil.approxSourceException(neverPartOfCompilationNode, exception);
        }
    }
}
