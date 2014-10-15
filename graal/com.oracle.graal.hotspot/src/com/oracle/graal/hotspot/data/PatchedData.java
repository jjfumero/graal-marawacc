/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.data;

import java.util.stream.*;

import com.oracle.graal.api.code.CompilationResult.Data;
import com.oracle.graal.api.code.CompilationResult.DataPatch;

/**
 * Represents a data item that needs to be patched.
 */
public abstract class PatchedData extends Data {

    protected PatchedData(int alignment) {
        super(alignment);
    }

    public int getPatchCount() {
        return 1;
    }

    public Stream<DataPatch> getPatches(int offset) {
        return Stream.of(new DataPatch(offset, this, true));
    }

    public static int getPatchCount(Data data) {
        if (data instanceof PatchedData) {
            return ((PatchedData) data).getPatchCount();
        } else {
            return 0;
        }
    }

    public static Stream<DataPatch> getPatches(Data data, int offset) {
        if (data instanceof PatchedData) {
            return ((PatchedData) data).getPatches(offset);
        } else {
            return Stream.empty();
        }
    }
}
