/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.compiler.test.ea;

import static org.junit.Assert.*;

import org.junit.*;

import com.oracle.graal.nodes.*;

public class EAMergingTest extends EATestBase {

    @Test
    public void testSimpleMerge() {
        testEscapeAnalysis("simpleMergeSnippet", null, false);
        assertTrue(returnNode.result() instanceof PhiNode);
        PhiNode phi = (PhiNode) returnNode.result();
        assertTrue(phi.valueAt(0) instanceof LocalNode);
        assertTrue(phi.valueAt(1) instanceof LocalNode);
    }

    public static int simpleMergeSnippet(boolean b, int u, int v) {
        TestClassInt obj;
        if (b) {
            obj = new TestClassInt(u, 0);
            notInlineable();
        } else {
            obj = new TestClassInt(v, 0);
            notInlineable();
        }
        return obj.x;
    }
}
