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

import com.oracle.truffle.api.*;

/**
 * Represents a frame containing values of local variables of the guest language. Instances of this
 * type must not be stored in a field or cast to {@link java.lang.Object}. If this is necessary, the
 * frame must be explicitly converted into a materialized frame using the
 * {@link VirtualFrame#materialize()} method. Whenever fast access to the local variables of a frame
 * is no longer necessary, a virtual frame should be converted into a packed frame using the
 * {@link VirtualFrame#pack()} method.
 */
public interface VirtualFrame extends Frame {

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
     * Accesses the caller frame passed in via {@link CallTarget#call}. To get full access, it must
     * be first unpacked using {@link PackedFrame#unpack()}.
     * 
     * @return the caller frame or null if this was a root method call
     */
    PackedFrame getCaller();

    /**
     * Materializes this frame, which allows it to be stored in a field or cast to
     * {@link java.lang.Object}. The frame however looses the ability to be packed or to access the
     * caller frame.
     * 
     * @return the new materialized frame
     */
    MaterializedFrame materialize();
}
