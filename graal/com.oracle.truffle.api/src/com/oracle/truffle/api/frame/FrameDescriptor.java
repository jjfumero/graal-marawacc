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
package com.oracle.truffle.api.frame;

import java.util.*;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.impl.*;

/**
 * Descriptor of the slots of frame objects. Multiple frame instances are associated with one such
 * descriptor.
 */
public final class FrameDescriptor implements Cloneable {

    private final FrameTypeConversion typeConversion;
    private final ArrayList<FrameSlotImpl> slots;
    private final HashMap<Object, FrameSlotImpl> identifierToSlotMap;
    private Assumption version;

    public FrameDescriptor() {
        this(DefaultFrameTypeConversion.getInstance());
    }

    public FrameDescriptor(FrameTypeConversion typeConversion) {
        this.typeConversion = typeConversion;
        slots = new ArrayList<>();
        identifierToSlotMap = new HashMap<>();
        version = createVersion();
    }

    public FrameSlot addFrameSlot(Object identifier) {
        return addFrameSlot(identifier, FrameSlotKind.Illegal);
    }

    public FrameSlot addFrameSlot(Object identifier, FrameSlotKind kind) {
        assert !identifierToSlotMap.containsKey(identifier);
        FrameSlotImpl slot = new FrameSlotImpl(this, identifier, slots.size(), kind);
        slots.add(slot);
        identifierToSlotMap.put(identifier, slot);
        updateVersion();
        return slot;
    }

    public FrameSlot findFrameSlot(Object identifier) {
        return identifierToSlotMap.get(identifier);
    }

    public FrameSlot findOrAddFrameSlot(Object identifier) {
        FrameSlot result = findFrameSlot(identifier);
        if (result != null) {
            return result;
        }
        return addFrameSlot(identifier);
    }

    public FrameSlot findOrAddFrameSlot(Object identifier, FrameSlotKind kind) {
        FrameSlot result = findFrameSlot(identifier);
        if (result != null) {
            return result;
        }
        return addFrameSlot(identifier, kind);
    }

    public int getSize() {
        return slots.size();
    }

    public List<? extends FrameSlot> getSlots() {
        return Collections.unmodifiableList(slots);
    }

    /**
     * (db) to retrieve the list of all the identifiers associated with this frame descriptor.
     * 
     * @return the list of all the identifiers in this frame descriptor
     */
    public Set<Object> getIdentifiers() {
        return Collections.unmodifiableSet(identifierToSlotMap.keySet());
    }

    /**
     * (db): this method is used for creating a clone of the {@link FrameDescriptor} object ready
     * for parallel execution.
     */
    public FrameDescriptor copy() {
        FrameDescriptor clonedFrameDescriptor = new FrameDescriptor(this.typeConversion);
        for (int i = 0; i < this.getSlots().size(); i++) {
            Object identifier = this.getSlots().get(i).getIdentifier();
            clonedFrameDescriptor.addFrameSlot(identifier);
        }
        return clonedFrameDescriptor;
    }

    public FrameDescriptor shallowCopy() {
        FrameDescriptor clonedFrameDescriptor = new FrameDescriptor(this.typeConversion);
        clonedFrameDescriptor.slots.addAll(slots);
        clonedFrameDescriptor.identifierToSlotMap.putAll(identifierToSlotMap);
        return clonedFrameDescriptor;
    }

    void updateVersion() {
        version.invalidate();
        version = createVersion();
    }

    public Assumption getVersion() {
        return version;
    }

    private static Assumption createVersion() {
        return Truffle.getRuntime().createAssumption("frame version");
    }

    public FrameTypeConversion getTypeConversion() {
        return typeConversion;
    }
}
