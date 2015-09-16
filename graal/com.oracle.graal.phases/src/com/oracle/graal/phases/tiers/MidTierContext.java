/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.phases.tiers;

import jdk.internal.jvmci.code.TargetDescription;
import jdk.internal.jvmci.meta.ProfilingInfo;

import com.oracle.graal.phases.OptimisticOptimizations;
import com.oracle.graal.phases.util.Providers;

public class MidTierContext extends PhaseContext {

    private final TargetProvider target;
    private final OptimisticOptimizations optimisticOpts;
    private final ProfilingInfo profilingInfo;

    public MidTierContext(Providers copyFrom, TargetProvider target, OptimisticOptimizations optimisticOpts, ProfilingInfo profilingInfo) {
        super(copyFrom);
        this.target = target;
        this.optimisticOpts = optimisticOpts;
        this.profilingInfo = profilingInfo;
    }

    public TargetDescription getTarget() {
        return target.getTarget();
    }

    public TargetProvider getTargetProvider() {
        return target;
    }

    public OptimisticOptimizations getOptimisticOptimizations() {
        return optimisticOpts;
    }

    public ProfilingInfo getProfilingInfo() {
        return profilingInfo;
    }
}
