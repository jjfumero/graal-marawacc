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
package com.oracle.graal.hotspot.amd64;

import com.oracle.graal.api.code.*;
import com.oracle.graal.asm.amd64.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.meta.*;

/**
 * AMD64 specific implementation of {@link HotSpotGraalRuntime}.
 */
final class AMD64HotSpotGraalRuntime extends HotSpotGraalRuntime {

    private AMD64HotSpotGraalRuntime() {
    }

    /**
     * Called from C++ code to retrieve the singleton instance, creating it first if necessary.
     */
    public static HotSpotGraalRuntime makeInstance() {
        if (getInstance() == null) {
            setInstance(new AMD64HotSpotGraalRuntime());
        }
        return getInstance();
    }

    @Override
    protected TargetDescription createTarget() {
        final int wordSize = 8;
        final int stackFrameAlignment = 16;
        return new TargetDescription(new AMD64(), true, stackFrameAlignment, config.vmPageSize, wordSize, true, true);
    }

    @Override
    protected HotSpotBackend createBackend() {
        return new AMD64HotSpotBackend(getRuntime(), getTarget());
    }

    @Override
    protected HotSpotRuntime createRuntime() {
        return new AMD64HotSpotRuntime(config, this);
    }
}
