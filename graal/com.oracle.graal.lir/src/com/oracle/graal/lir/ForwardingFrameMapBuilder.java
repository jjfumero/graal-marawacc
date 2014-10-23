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
package com.oracle.graal.lir;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.lir.gen.*;

/**
 * A simple forwarder to {@link FrameMap}.
 */
public class ForwardingFrameMapBuilder implements FrameMapBuilder {

    @FunctionalInterface
    public interface FrameMapFactory {
        FrameMap newFrameMap(FrameMapBuilder frameMapBuilder);
    }

    private final FrameMap frameMap;
    private final RegisterConfig registerConfig;
    private final CodeCacheProvider codeCache;

    public ForwardingFrameMapBuilder(FrameMapFactory factory, CodeCacheProvider codeCache, RegisterConfig registerConfig) {
        this.registerConfig = registerConfig == null ? codeCache.getRegisterConfig() : registerConfig;
        this.codeCache = codeCache;
        // all members must be set up before calling FrameMapFactory#newFrameMap
        this.frameMap = factory.newFrameMap(this);
    }

    public StackSlot allocateSpillSlot(LIRKind kind) {
        return frameMap.allocateSpillSlot(kind);
    }

    public StackSlot allocateStackSlots(int slots, BitSet objects, List<StackSlot> outObjectStackSlots) {
        return frameMap.allocateStackSlots(slots, objects, outObjectStackSlots);
    }

    public RegisterConfig getRegisterConfig() {
        return registerConfig;
    }

    public CodeCacheProvider getCodeCache() {
        return codeCache;
    }

    public void freeSpillSlot(StackSlot slot) {
        frameMap.freeSpillSlot(slot);
    }

    public void callsMethod(CallingConvention cc) {
        frameMap.callsMethod(cc);
    }

    public FrameMap buildFrameMap(LIRGenerationResult res) {
        frameMap.finish();
        return frameMap;
    }

}
