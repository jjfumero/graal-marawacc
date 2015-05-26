/*
 * Copyright (c) 2011, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.jvmci.code;

import com.oracle.jvmci.meta.JavaValue;
import com.oracle.jvmci.meta.AbstractValue;
import com.oracle.jvmci.meta.Value;
import com.oracle.jvmci.meta.LIRKind;
import static com.oracle.jvmci.code.ValueUtil.*;


/**
 * Represents lock information in the debug information.
 */
public final class StackLockValue extends AbstractValue implements JavaValue {

    private Value owner;
    private StackSlotValue slot;
    private final boolean eliminated;

    public StackLockValue(Value object, StackSlotValue slot, boolean eliminated) {
        super(LIRKind.Illegal);
        this.owner = object;
        this.slot = slot;
        this.eliminated = eliminated;
    }

    public Value getOwner() {
        return owner;
    }

    public void setOwner(Value newOwner) {
        this.owner = newOwner;
    }

    public Value getSlot() {
        return slot;
    }

    public boolean isEliminated() {
        return eliminated;
    }

    @Override
    public String toString() {
        return "monitor[" + owner + (slot != null ? ", " + slot : "") + (eliminated ? ", eliminated" : "") + "]";
    }

    @Override
    public int hashCode() {
        final int prime = 43;
        int result = super.hashCode();
        result = prime * result + (eliminated ? 1231 : 1237);
        result = prime * result + owner.hashCode();
        result = prime * result + slot.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof StackLockValue) {
            StackLockValue other = (StackLockValue) obj;
            return super.equals(obj) && eliminated == other.eliminated && owner.equals(other.owner) && slot.equals(other.slot);
        }
        return false;
    }

    public void setSlot(StackSlotValue stackSlot) {
        assert slot == null || (isVirtualStackSlot(slot) && (slot.equals(stackSlot) || isStackSlot(stackSlot))) : String.format("Can not set slot for %s to %s", this, stackSlot);
        slot = stackSlot;
    }
}
