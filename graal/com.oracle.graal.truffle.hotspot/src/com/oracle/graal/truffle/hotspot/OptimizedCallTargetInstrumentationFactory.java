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
package com.oracle.graal.truffle.hotspot;

import jdk.vm.ci.hotspot.HotSpotVMConfig;

import com.oracle.graal.hotspot.meta.HotSpotRegistersProvider;
import com.oracle.graal.lir.asm.CompilationResultBuilder;
import com.oracle.graal.lir.asm.CompilationResultBuilderFactory;
import com.oracle.graal.truffle.OptimizedCallTarget;

/**
 * A service for creating a specialized {@link CompilationResultBuilder} used to inject code into
 * {@link OptimizedCallTarget#call(Object[])}.
 */
public abstract class OptimizedCallTargetInstrumentationFactory implements CompilationResultBuilderFactory {

    protected HotSpotVMConfig config;
    protected HotSpotRegistersProvider registers;

    @SuppressWarnings("hiding")
    public final void init(HotSpotVMConfig config, HotSpotRegistersProvider registers) {
        this.config = config;
        this.registers = registers;
    }

    /**
     * Gets the architecture supported by this factory.
     */
    public abstract String getArchitecture();
}
