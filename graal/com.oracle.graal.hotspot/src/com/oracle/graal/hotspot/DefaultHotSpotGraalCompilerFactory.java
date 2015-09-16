/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.util.IdentityHashMap;

import jdk.internal.jvmci.code.Architecture;
import jdk.internal.jvmci.compiler.CompilerFactory;
import jdk.internal.jvmci.service.ServiceProvider;

import com.oracle.graal.compiler.phases.BasicCompilerConfiguration;
import com.oracle.graal.phases.tiers.CompilerConfiguration;

@ServiceProvider(CompilerFactory.class)
public class DefaultHotSpotGraalCompilerFactory extends HotSpotGraalCompilerFactory {

    private static IdentityHashMap<Class<? extends Architecture>, HotSpotBackendFactory> backends = new IdentityHashMap<>();

    public static void registerBackend(Class<? extends Architecture> arch, HotSpotBackendFactory factory) {
        assert !backends.containsKey(arch) : "duplicate graal backend";
        backends.put(arch, factory);
    }

    @Override
    public String getCompilerName() {
        return "graal";
    }

    @Override
    protected CompilerConfiguration createCompilerConfiguration() {
        return new BasicCompilerConfiguration();
    }

    @Override
    protected HotSpotBackendFactory getBackendFactory(Architecture arch) {
        return backends.get(arch.getClass());
    }
}
