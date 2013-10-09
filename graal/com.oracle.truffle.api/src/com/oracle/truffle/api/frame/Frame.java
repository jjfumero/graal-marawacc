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

import com.oracle.truffle.api.*;

/**
 * Represents a frame containing values of local variables of the guest language. Instances of this
 * type must not be stored in a field or cast to {@link java.lang.Object}.
 */
public interface Frame {

    /**
     * @return the object describing the layout of this frame
     */
    FrameDescriptor getFrameDescriptor();

    /**
     * Retrieves the arguments object from this frame. The runtime assumes that the arguments object
     * is never null. Additionally, the runtime may assume that the given parameter indicating the
     * class of the arguments object is correct. The runtime is not required to actually check the
     * type of the arguments object. The parameter must be a value that can be reduced to a compile
     * time constant.
     * 
     * @param clazz the known type of the arguments object as a compile time constant
     * @return the arguments used when calling this method
     */
    <T extends Arguments> T getArguments(Class<T> clazz);

    /**
     * Read access to a local variable of type {@link Object}.
     * 
     * @param slot the slot of the local variable
     * @return the current value of the local variable
     */
    Object getObject(FrameSlot slot) throws FrameSlotTypeException;

    /**
     * Write access to a local variable of type {@link Object}.
     * 
     * @param slot the slot of the local variable
     * @param value the new value of the local variable
     */
    void setObject(FrameSlot slot, Object value);

    /**
     * Read access to a local variable of type byte.
     * 
     * @param slot the slot of the local variable
     * @return the current value of the local variable
     */
    byte getByte(FrameSlot slot) throws FrameSlotTypeException;

    /**
     * Write access to a local variable of type byte.
     * 
     * @param slot the slot of the local variable
     * @param value the new value of the local variable
     */

    void setByte(FrameSlot slot, byte value) throws FrameSlotTypeException;

    /**
     * Read access to a local variable of type boolean.
     * 
     * @param slot the slot of the local variable
     * @return the current value of the local variable
     */
    boolean getBoolean(FrameSlot slot) throws FrameSlotTypeException;

    /**
     * Write access to a local variable of type boolean.
     * 
     * @param slot the slot of the local variable
     * @param value the new value of the local variable
     */
    void setBoolean(FrameSlot slot, boolean value) throws FrameSlotTypeException;

    /**
     * Read access to a local variable of type int.
     * 
     * @param slot the slot of the local variable
     * @return the current value of the local variable
     */
    int getInt(FrameSlot slot) throws FrameSlotTypeException;

    /**
     * Write access to a local variable of type int.
     * 
     * @param slot the slot of the local variable
     * @param value the new value of the local variable
     */
    void setInt(FrameSlot slot, int value) throws FrameSlotTypeException;

    /**
     * Read access to a local variable of type long.
     * 
     * @param slot the slot of the local variable
     * @return the current value of the local variable
     */
    long getLong(FrameSlot slot) throws FrameSlotTypeException;

    /**
     * Write access to a local variable of type long.
     * 
     * @param slot the slot of the local variable
     * @param value the new value of the local variable
     */
    void setLong(FrameSlot slot, long value) throws FrameSlotTypeException;

    /**
     * Read access to a local variable of type float.
     * 
     * @param slot the slot of the local variable
     * @return the current value of the local variable
     */
    float getFloat(FrameSlot slot) throws FrameSlotTypeException;

    /**
     * Write access to a local variable of type float.
     * 
     * @param slot the slot of the local variable
     * @param value the new value of the local variable
     */
    void setFloat(FrameSlot slot, float value) throws FrameSlotTypeException;

    /**
     * Read access to a local variable of type double.
     * 
     * @param slot the slot of the local variable
     * @return the current value of the local variable
     */
    double getDouble(FrameSlot slot) throws FrameSlotTypeException;

    /**
     * Write access to a local variable of type double.
     * 
     * @param slot the slot of the local variable
     * @param value the new value of the local variable
     */
    void setDouble(FrameSlot slot, double value) throws FrameSlotTypeException;

    /**
     * Read access to a local variable of any type.
     * 
     * @param slot the slot of the local variable
     * @return the current value of the local variable or defaultValue if unset
     */
    Object getValue(FrameSlot slot);

    /**
     * Converts this virtual frame into a packed frame that has no longer direct access to the local
     * variables. This packing is an important hint to the Truffle optimizer and therefore passing
     * around a {@link PackedFrame} should be preferred over passing around a {@link VirtualFrame}
     * when the probability that an unpacking will occur is low.
     * 
     * @return the packed frame
     */
    PackedFrame pack();

    /**
     * Materializes this frame, which allows it to be stored in a field or cast to
     * {@link java.lang.Object}. The frame however looses the ability to be packed or to access the
     * caller frame.
     * 
     * @return the new materialized frame
     */
    MaterializedFrame materialize();

    /**
     * To check whether the given {@link FrameSlot} has been initialized or not. An initialized slot
     * has previously been read or modified.
     * 
     * @param slot the slot
     * @return true if the slot is uninitialized.
     */
    boolean isInitialized(FrameSlot slot);
}
