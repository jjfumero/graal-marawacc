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
package com.oracle.graal.hotspot;

import static com.oracle.graal.compiler.common.UnsafeAccess.*;
import static com.oracle.graal.hotspot.HotSpotGraalRuntime.*;
import sun.misc.*;

/**
 * Class to access the C++ {@code vmSymbols} table.
 */
public final class HotSpotVmSymbols {

    /**
     * Returns the {@link HotSpotSymbol} in the {@code vmSymbols} table at position {@code index} as
     * {@link String}.
     * 
     * @param index position in the symbol table
     * @return the symbol at position id
     */
    public static String symbolAt(int index) {
        HotSpotVMConfig config = runtime().getConfig();
        assert config.vmSymbolsFirstSID <= index && index < config.vmSymbolsSIDLimit : "index " + index + " is out of bounds";
        assert config.symbolPointerSize == Unsafe.ADDRESS_SIZE : "the following address read is broken";
        final long metaspaceSymbol = unsafe.getAddress(config.vmSymbolsSymbols + index * config.symbolPointerSize);
        return new HotSpotSymbol(metaspaceSymbol).asString();
    }
}
