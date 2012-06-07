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
package com.oracle.graal.compiler;

import java.util.*;

import com.oracle.graal.debug.*;
import com.oracle.max.cri.ci.*;
import com.oracle.max.cri.ri.*;
import com.oracle.max.criutils.*;



public final class OptimisticOptimizations {
    public static final OptimisticOptimizations ALL = new OptimisticOptimizations(EnumSet.allOf(Optimization.class));
    public static final OptimisticOptimizations NONE = new OptimisticOptimizations(EnumSet.noneOf(Optimization.class));
    private static final DebugMetric disabledOptimisticOptsMetric = Debug.metric("DisabledOptimisticOpts");

    private static enum Optimization {
        RemoveNeverExecutedCode,
        UseTypeCheckedInlining,
        UseTypeCheckHints,
        UseExceptionProbability
    }

    private final Set<Optimization> enabledOpts;

    public OptimisticOptimizations(RiResolvedMethod method) {
        this.enabledOpts = EnumSet.noneOf(Optimization.class);

        RiProfilingInfo profilingInfo = method.profilingInfo();
        if (checkDeoptimizations(profilingInfo, CiDeoptReason.UnreachedCode)) {
            enabledOpts.add(Optimization.RemoveNeverExecutedCode);
        }
        if (checkDeoptimizations(profilingInfo, CiDeoptReason.TypeCheckedInliningViolated)) {
            enabledOpts.add(Optimization.UseTypeCheckedInlining);
        }
        if (checkDeoptimizations(profilingInfo, CiDeoptReason.OptimizedTypeCheckViolated)) {
            enabledOpts.add(Optimization.UseTypeCheckHints);
        }
        if (checkDeoptimizations(profilingInfo, CiDeoptReason.NotCompiledExceptionHandler)) {
            enabledOpts.add(Optimization.UseExceptionProbability);
        }
    }

    private OptimisticOptimizations(Set<Optimization> enabledOpts) {
        this.enabledOpts = enabledOpts;
    }

    public void log(RiMethod method) {
        for (Optimization opt: Optimization.values()) {
            if (!enabledOpts.contains(opt)) {
                if (GraalOptions.PrintDisabledOptimisticOptimizations) {
                    TTY.println("WARN: deactivated optimistic optimization %s for %s", opt.name(), CiUtil.format("%H.%n(%p)", method));
                }
                disabledOptimisticOptsMetric.increment();
            }
        }
    }

    public boolean removeNeverExecutedCode() {
        return GraalOptions.RemoveNeverExecutedCode && enabledOpts.contains(Optimization.RemoveNeverExecutedCode);
    }

    public boolean useTypeCheckHints() {
        return GraalOptions.UseTypeCheckHints && enabledOpts.contains(Optimization.UseTypeCheckHints);
    }

    public boolean inlineMonomorphicCalls() {
        return GraalOptions.InlineMonomorphicCalls && enabledOpts.contains(Optimization.UseTypeCheckedInlining);
    }

    public boolean inlinePolymorphicCalls() {
        return GraalOptions.InlinePolymorphicCalls && enabledOpts.contains(Optimization.UseTypeCheckedInlining);
    }

    public boolean inlineMegamorphicCalls() {
        return GraalOptions.InlineMegamorphicCalls && enabledOpts.contains(Optimization.UseTypeCheckedInlining);
    }

    public boolean useExceptionProbability() {
        return GraalOptions.UseExceptionProbability && enabledOpts.contains(Optimization.UseExceptionProbability);
    }

    public boolean lessOptimisticThan(OptimisticOptimizations other) {
        for (Optimization opt: Optimization.values()) {
            if (!enabledOpts.contains(opt) && other.enabledOpts.contains(opt)) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkDeoptimizations(RiProfilingInfo profilingInfo, CiDeoptReason reason) {
        return profilingInfo.getDeoptimizationCount(reason) < GraalOptions.DeoptsToDisableOptimisticOptimization;
    }
}
