/*
 * Copyright (c) 2014, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.lir.gen;

import com.oracle.graal.lir.LIR;
import com.oracle.graal.lir.framemap.FrameMap;
import com.oracle.graal.lir.framemap.FrameMapBuilder;
import com.oracle.graal.lir.stackslotalloc.StackSlotAllocator;

public interface LIRGenerationResult {

    /**
     * Returns the {@link FrameMapBuilder} for collecting the information to build a
     * {@link FrameMap}.
     *
     * This method can only be used prior calling {@link #buildFrameMap}.
     */
    FrameMapBuilder getFrameMapBuilder();

    /**
     * Creates a {@link FrameMap} out of the {@link FrameMapBuilder}. This method should only be
     * called once. After calling it, {@link #getFrameMapBuilder()} can no longer be used.
     *
     * @see FrameMapBuilder#buildFrameMap
     */
    void buildFrameMap(StackSlotAllocator allocator);

    /**
     * Returns the {@link FrameMap} associated with this {@link LIRGenerationResult}.
     *
     * This method can only be called after {@link #buildFrameMap}.
     */
    FrameMap getFrameMap();

    LIR getLIR();

    boolean hasForeignCall();

    void setForeignCall(boolean b);

    String getCompilationUnitName();
}
