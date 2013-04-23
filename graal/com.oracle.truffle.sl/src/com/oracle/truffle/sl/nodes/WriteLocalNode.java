/*
 * Copyright (c) 2012, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.sl.nodes;

import com.oracle.truffle.api.codegen.*;
import com.oracle.truffle.api.frame.*;

@NodeChild(value = "rightNode", type = TypedNode.class)
public abstract class WriteLocalNode extends FrameSlotNode {

    public WriteLocalNode(FrameSlot slot) {
        super(slot);
    }

    public WriteLocalNode(WriteLocalNode node) {
        this(node.slot);
    }

    @Specialization(rewriteOn = FrameSlotTypeException.class)
    public int write(VirtualFrame frame, int right) throws FrameSlotTypeException {
        try {
            frame.setInt(slot, right);
        } catch (FrameSlotTypeException e) {
            if (slot.getType() == null) {
                FrameUtil.setIntSafe(frame, slot, right);
            } else {
                throw e;
            }
        }
        return right;
    }

    @Specialization(rewriteOn = FrameSlotTypeException.class)
    public boolean write(VirtualFrame frame, boolean right) throws FrameSlotTypeException {
        try {
            frame.setBoolean(slot, right);
        } catch (FrameSlotTypeException e) {
            if (slot.getType() == null) {
                FrameUtil.setBooleanSafe(frame, slot, right);
            } else {
                throw e;
            }
        }
        return right;
    }

    @Generic(useSpecializations = false)
    public Object writeGeneric(VirtualFrame frame, Object right) {
        try {
            frame.setObject(slot, right);
        } catch (FrameSlotTypeException e) {
            FrameUtil.setObjectSafe(frame, slot, right);
        }
        return right;
    }

    @Override
    protected FrameSlotNode specialize(Class<?> clazz) {
        return WriteLocalNodeFactory.createSpecialized(this, clazz);
    }

}
