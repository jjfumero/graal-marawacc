/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.lir.ptx;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.lir.*;

/**
 * PTX specific frame map.
 * 
 * This is the format of a PTX stack frame:
 * 
 * <pre>
 * TODO stack frame layout
 * </pre>
 */
public final class PTXFrameMap extends FrameMap {

    public PTXFrameMap(CodeCacheProvider runtime, TargetDescription target, RegisterConfig registerConfig) {
        super(runtime, target, registerConfig);
    }

    @Override
    public int totalFrameSize() {
        // FIXME this is amd64 code
        return frameSize() + returnAddressSize();
    }

    @Override
    public int currentFrameSize() {
        // FIXME this is amd64 code
        return alignFrameSize(outgoingSize + spillSize - returnAddressSize());
    }

    @Override
    protected int alignFrameSize(int size) {
        // FIXME this is amd64 code
        int x = size + returnAddressSize() + (target.stackAlignment - 1);
        return (x / target.stackAlignment) * target.stackAlignment - returnAddressSize();
    }

    @Override
    public int offsetToCalleeSaveArea() {
        return frameSize() - calleeSaveAreaSize();
    }

    @Override
    protected StackSlot allocateNewSpillSlot(PlatformKind kind, int additionalOffset) {
        return StackSlot.get(kind, -spillSize + additionalOffset, true);
    }
}
