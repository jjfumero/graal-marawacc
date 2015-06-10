/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.compiler.common.GraalOptions.*;
import static com.oracle.jvmci.code.CallingConvention.Type.*;
import static com.oracle.jvmci.code.CodeUtil.*;

import com.oracle.graal.compiler.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.phases.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.lir.phases.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.StructuredGraph.AllowAssumptions;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.OptimisticOptimizations.Optimization;
import com.oracle.graal.phases.tiers.*;
import com.oracle.jvmci.code.*;
import com.oracle.jvmci.code.CallingConvention.Type;
import com.oracle.jvmci.compiler.Compiler;
import com.oracle.jvmci.meta.*;
import com.oracle.jvmci.service.*;

@ServiceProvider(Compiler.class)
public class HotSpotGraalCompiler implements Compiler {

    public CompilationResult compile(ResolvedJavaMethod method, int entryBCI, boolean mustRecordMethodInlining) {
        HotSpotBackend backend = HotSpotGraalRuntime.runtime().getHostBackend();
        HotSpotProviders providers = HotSpotGraalRuntime.runtime().getHostProviders();
        final boolean isOSR = entryBCI != INVOCATION_ENTRY_BCI;

        StructuredGraph graph = new StructuredGraph(method, entryBCI, AllowAssumptions.from(OptAssumptions.getValue()));
        if (!mustRecordMethodInlining) {
            graph.disableInlinedMethodRecording();
        }

        CallingConvention cc = getCallingConvention(providers.getCodeCache(), Type.JavaCallee, graph.method(), false);
        if (isOSR) {
            // for OSR, only a pointer is passed to the method.
            JavaType[] parameterTypes = new JavaType[]{providers.getMetaAccess().lookupJavaType(long.class)};
            CallingConvention tmp = providers.getCodeCache().getRegisterConfig().getCallingConvention(JavaCallee, providers.getMetaAccess().lookupJavaType(void.class), parameterTypes,
                            backend.getTarget(), false);
            cc = new CallingConvention(cc.getStackSize(), cc.getReturn(), tmp.getArgument(0));
        }
        Suites suites = getSuites(providers);
        LIRSuites lirSuites = getLIRSuites(providers);
        ProfilingInfo profilingInfo = method.getProfilingInfo(!isOSR, isOSR);
        OptimisticOptimizations optimisticOpts = getOptimisticOpts(profilingInfo);
        if (isOSR) {
            // In OSR compiles, we cannot rely on never executed code profiles, because
            // all code after the OSR loop is never executed.
            optimisticOpts.remove(Optimization.RemoveNeverExecutedCode);
        }
        CompilationResult result = GraalCompiler.compileGraph(graph, cc, method, providers, backend, backend.getTarget(), getGraphBuilderSuite(providers, isOSR), optimisticOpts, profilingInfo,
                        method.getSpeculationLog(), suites, lirSuites, new CompilationResult(), CompilationResultBuilderFactory.Default);

        result.setEntryBCI(entryBCI);

        if (!isOSR) {
            ProfilingInfo profile = method.getProfilingInfo();
            profile.setCompilerIRSize(StructuredGraph.class, graph.getNodeCount());
        }

        return result;
    }

    protected OptimisticOptimizations getOptimisticOpts(ProfilingInfo profilingInfo) {
        return new OptimisticOptimizations(profilingInfo);
    }

    protected Suites getSuites(HotSpotProviders providers) {
        return providers.getSuites().getDefaultSuites();
    }

    protected LIRSuites getLIRSuites(HotSpotProviders providers) {
        return providers.getSuites().getDefaultLIRSuites();
    }

    protected PhaseSuite<HighTierContext> getGraphBuilderSuite(HotSpotProviders providers, boolean isOSR) {
        PhaseSuite<HighTierContext> suite = HotSpotSuitesProvider.withSimpleDebugInfoIfRequested(providers.getSuites().getDefaultGraphBuilderSuite());
        if (isOSR) {
            suite = suite.copy();
            suite.appendPhase(new OnStackReplacementPhase());
        }
        return suite;
    }
}
