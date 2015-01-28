/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.test.nodes;

import static org.junit.Assert.*;

import org.junit.*;

import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;

/**
 * Tests optional method for ensuring that a node replacement is type safe. Ordinary node
 * replacement is performed by unsafe assignment of a parent node's child field.
 */
public class SafeReplaceTest {

    @Test
    public void testCorrectReplacement() {
        TestRootNode root = new TestRootNode();
        final TestNode oldChild = new TestNode();
        root.child = oldChild;
        assertFalse(oldChild.isReplaceable());  // No parent node
        root.adoptChildren();
        assertTrue(oldChild.isReplaceable());   // Now adopted by parent
        final TestNode newChild = new TestNode();
        assertTrue(oldChild.isSafelyReplaceableBy(newChild));  // Parent field type is assignable by
        // new node
        oldChild.replace(newChild);
        root.execute(null);
        assertEquals(root.executed, 1);
        assertEquals(oldChild.executed, 0);
        assertEquals(newChild.executed, 1);
    }

    @Test
    public void testIncorrectReplacement() {
        TestRootNode root = new TestRootNode();
        final TestNode oldChild = new TestNode();
        root.child = oldChild;
        root.adoptChildren();
        final WrongTestNode newChild = new WrongTestNode();
        assertFalse(oldChild.isSafelyReplaceableBy(newChild));
        // Can't test: oldChild.replace(newChild);
        // Fails if assertions checked; else unsafe assignment will eventually crash the VM
    }

    private static class TestNode extends Node {

        private int executed;

        public Object execute() {
            executed++;
            return null;
        }
    }

    private static class TestRootNode extends RootNode {

        @Child TestNode child;

        private int executed;

        @Override
        public Object execute(VirtualFrame frame) {
            executed++;
            child.execute();
            return null;
        }
    }

    private static class WrongTestNode extends Node {
    }

}
