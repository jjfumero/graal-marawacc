/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

/**
 * Descriptor of the slots of frame objects. Multiple frame instances are associated with one such
 * descriptor.
 */
public final class FrameDescriptor implements Cloneable {

    private final Object defaultValue;
    private final ArrayList<FrameSlot> slots;
    private final HashMap<Object, FrameSlot> identifierToSlotMap;
    private Assumption version;
    private HashMap<Object, Assumption> identifierToNotInFrameAssumptionMap;

    public FrameDescriptor() {
        this(null);
    }

    public FrameDescriptor(Object defaultValue) {
        this.defaultValue = defaultValue;
        slots = new ArrayList<>();
        identifierToSlotMap = new HashMap<>();
        version = createVersion();
    }

    public static FrameDescriptor create() {
        return new FrameDescriptor();
    }

    public static FrameDescriptor create(Object defaultValue) {
        return new FrameDescriptor(defaultValue);
    }

    public FrameSlot addFrameSlot(Object identifier) {
        return addFrameSlot(identifier, null, FrameSlotKind.Illegal);
    }

    public FrameSlot addFrameSlot(Object identifier, FrameSlotKind kind) {
        return addFrameSlot(identifier, null, kind);
    }

    public FrameSlot addFrameSlot(Object identifier, Object info, FrameSlotKind kind) {
        CompilerAsserts.neverPartOfCompilation("interpreter-only.  includes hashmap operations.");
        assert !identifierToSlotMap.containsKey(identifier);
        FrameSlot slot = new FrameSlot(this, identifier, info, slots.size(), kind);
        slots.add(slot);
        identifierToSlotMap.put(identifier, slot);
        updateVersion();
        invalidateNotInFrameAssumption(identifier);
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

    public FrameSlot findOrAddFrameSlot(Object identifier, Object info, FrameSlotKind kind) {
        FrameSlot result = findFrameSlot(identifier);
        if (result != null) {
            return result;
        }
        return addFrameSlot(identifier, info, kind);
    }

    public void removeFrameSlot(Object identifier) {
        CompilerAsserts.neverPartOfCompilation("interpreter-only.  includes hashmap operations.");
        assert identifierToSlotMap.containsKey(identifier);
        slots.remove(identifierToSlotMap.get(identifier));
        identifierToSlotMap.remove(identifier);
        updateVersion();
        getNotInFrameAssumption(identifier);
    }

    public int getSize() {
        return slots.size();
    }

    public List<? extends FrameSlot> getSlots() {
        return Collections.unmodifiableList(slots);
    }

    /**
     * Retrieve the list of all the identifiers associated with this frame descriptor.
     *
     * @return the list of all the identifiers in this frame descriptor
     */
    public Set<Object> getIdentifiers() {
        return Collections.unmodifiableSet(identifierToSlotMap.keySet());
    }

    public FrameDescriptor copy() {
        FrameDescriptor clonedFrameDescriptor = new FrameDescriptor(this.defaultValue);
        for (int i = 0; i < this.getSlots().size(); i++) {
            Object identifier = this.getSlots().get(i).getIdentifier();
            clonedFrameDescriptor.addFrameSlot(identifier);
        }
        return clonedFrameDescriptor;
    }

    public FrameDescriptor shallowCopy() {
        FrameDescriptor clonedFrameDescriptor = new FrameDescriptor(this.defaultValue);
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

    public Object getDefaultValue() {
        return defaultValue;
    }

    public Assumption getNotInFrameAssumption(Object identifier) {
        if (identifierToSlotMap.containsKey(identifier)) {
            throw new IllegalArgumentException("Cannot get not-in-frame assumption for existing frame slot!");
        }

        if (identifierToNotInFrameAssumptionMap == null) {
            identifierToNotInFrameAssumptionMap = new HashMap<>();
        } else {
            Assumption assumption = identifierToNotInFrameAssumptionMap.get(identifier);
            if (assumption != null) {
                return assumption;
            }
        }
        Assumption assumption = Truffle.getRuntime().createAssumption("not in frame: " + identifier);
        identifierToNotInFrameAssumptionMap.put(identifier, assumption);
        return assumption;
    }

    private void invalidateNotInFrameAssumption(Object identifier) {
        if (identifierToNotInFrameAssumptionMap != null) {
            Assumption assumption = identifierToNotInFrameAssumptionMap.get(identifier);
            if (assumption != null) {
                assumption.invalidate();
                identifierToNotInFrameAssumptionMap.remove(identifier);
            }
        }
    }
}
