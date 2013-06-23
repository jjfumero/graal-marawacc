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
package com.oracle.graal.truffle.phases;

import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.truffle.nodes.*;

/**
 * Compiler phase for verifying that the Truffle virtual frame does not escape and can therefore be
 * escape analyzed.
 */
public class VerifyFrameDoesNotEscapePhase extends Phase {

    @Override
    protected void run(StructuredGraph graph) {
        NewFrameNode frame = graph.getNodes(NewFrameNode.class).first();
        if (frame != null) {
            for (MethodCallTargetNode callTarget : frame.usages().filter(MethodCallTargetNode.class)) {
                if (callTarget.invoke() != null) {
                    Throwable exception = new VerificationError("Frame escapes at: %s#%s", callTarget, callTarget.targetMethod());
                    throw GraphUtil.approxSourceException(callTarget, exception);
                }
            }
        }
    }
}
