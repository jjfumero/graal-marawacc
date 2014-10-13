/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.utilities;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Utility class to speculate on branches to be never visited. If the {@link #enter()} method is
 * invoked first the optimized code is invalidated and the branch where {@link #enter()} is invoked
 * is enabled for compilation. Otherwise if the {@link #enter()} method was never invoked the branch
 * will not get compiled.
 *
 * All {@code BranchProfile} instances must be held in {@code final} fields for compiler
 * optimizations to take effect.
 */
public final class BranchProfile {

    @CompilationFinal private boolean visited;

    private BranchProfile() {
    }

    public void enter() {
        if (!visited) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            visited = true;
        }
    }

    public boolean isVisited() {
        return visited;
    }

    public static BranchProfile create() {
        return new BranchProfile();
    }

    @Override
    public String toString() {
        return String.format("%s(%s)@%x", getClass().getSimpleName(), visited ? "visited" : "not-visited", hashCode());
    }

}
