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
package com.oracle.truffle.api.test;

import org.junit.*;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;

/**
 * <h3>Specializing Return Types</h3>
 * 
 * <p>
 * In order to avoid boxing and/or type casts on the return value of a node, the return value the
 * method for executing a node can have a specific type and need not be of type
 * {@link java.lang.Object}. For dynamically typed languages, this return type is something that
 * should be speculated on. When the speculation fails and the child node cannot return the
 * appropriate type of value, it can use an {@link UnexpectedResultException} to still pass the
 * result to the caller. In such a case, the caller must rewrite itself to a more general version in
 * order to avoid future failures of this kind.
 * </p>
 */
public class ReturnTypeSpecializationTest {

    @Test
    public void test() {
        TruffleRuntime runtime = Truffle.getRuntime();
        FrameDescriptor frameDescriptor = new FrameDescriptor();
        FrameSlot slot = frameDescriptor.addFrameSlot("localVar", Integer.class);
        TestRootNode rootNode = new TestRootNode(new IntAssignLocal(slot, new StringTestChildNode()), new IntReadLocal(slot));
        CallTarget target = runtime.createCallTarget(rootNode, frameDescriptor);
        Assert.assertEquals(Integer.class, slot.getType());
        Object result = target.call();
        Assert.assertEquals("42", result);
        Assert.assertEquals(Object.class, slot.getType());
    }

    class TestRootNode extends RootNode {

        @Child TestChildNode left;
        @Child TestChildNode right;

        public TestRootNode(TestChildNode left, TestChildNode right) {
            this.left = adoptChild(left);
            this.right = adoptChild(right);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            left.execute(frame);
            return right.execute(frame);
        }
    }

    abstract class TestChildNode extends Node {

        abstract Object execute(VirtualFrame frame);

        int executeInt(VirtualFrame frame) throws UnexpectedResultException {
            Object result = execute(frame);
            if (result instanceof Integer) {
                return (Integer) result;
            }
            throw new UnexpectedResultException(result);
        }
    }

    abstract class FrameSlotNode extends TestChildNode {

        protected final FrameSlot slot;

        public FrameSlotNode(FrameSlot slot) {
            this.slot = slot;
        }
    }

    class StringTestChildNode extends TestChildNode {

        @Override
        Object execute(VirtualFrame frame) {
            return "42";
        }

    }

    class IntAssignLocal extends FrameSlotNode implements FrameSlotTypeListener {

        @Child private TestChildNode value;

        IntAssignLocal(FrameSlot slot, TestChildNode value) {
            super(slot);
            this.value = adoptChild(value);
            slot.registerOneShotTypeListener(this);
        }

        @Override
        Object execute(VirtualFrame frame) {
            try {
                frame.setInt(slot, value.executeInt(frame));
            } catch (UnexpectedResultException e) {
                slot.setType(Object.class);
                frame.updateToLatestVersion();
                frame.setObject(slot, e.getResult());
            }
            return null;
        }

        @Override
        public void typeChanged(FrameSlot changedSlot, Class<?> oldType) {
            if (changedSlot.getType() == Object.class) {
                this.replace(new ObjectAssignLocal(changedSlot, value));
            }
        }
    }

    class ObjectAssignLocal extends FrameSlotNode {

        @Child private TestChildNode value;

        ObjectAssignLocal(FrameSlot slot, TestChildNode value) {
            super(slot);
            this.value = adoptChild(value);
        }

        @Override
        Object execute(VirtualFrame frame) {
            Object o = value.execute(frame);
            frame.setObject(slot, o);
            return null;
        }
    }

    class IntReadLocal extends FrameSlotNode implements FrameSlotTypeListener {

        IntReadLocal(FrameSlot slot) {
            super(slot);
            slot.registerOneShotTypeListener(this);
        }

        @Override
        Object execute(VirtualFrame frame) {
            return executeInt(frame);
        }

        @Override
        int executeInt(VirtualFrame frame) {
            return frame.getInt(slot);
        }

        @Override
        public void typeChanged(FrameSlot changedSlot, Class<?> oldType) {
            if (changedSlot.getType() == Object.class) {
                this.replace(new ObjectReadLocal(changedSlot));
            }
        }
    }

    class ObjectReadLocal extends FrameSlotNode {

        ObjectReadLocal(FrameSlot slot) {
            super(slot);
        }

        @Override
        Object execute(VirtualFrame frame) {
            return frame.getObject(slot);
        }
    }
}
