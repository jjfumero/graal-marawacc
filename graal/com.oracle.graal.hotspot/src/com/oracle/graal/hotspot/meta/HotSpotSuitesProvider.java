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
package com.oracle.graal.hotspot.meta;

import static com.oracle.graal.compiler.common.GraalOptions.*;

import java.util.function.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.bridge.*;
import com.oracle.graal.hotspot.phases.*;
import com.oracle.graal.java.*;
import com.oracle.graal.java.GraphBuilderConfiguration.DebugInfoMode;
import com.oracle.graal.java.GraphBuilderPlugins.InlineInvokePlugin;
import com.oracle.graal.lir.phases.*;
import com.oracle.graal.options.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.tiers.*;

/**
 * HotSpot implementation of {@link SuitesProvider}.
 */
public class HotSpotSuitesProvider implements SuitesProvider, Supplier<Suites> {

    protected final DerivedOptionValue<Suites> defaultSuites;
    protected final PhaseSuite<HighTierContext> defaultGraphBuilderSuite;
    private final DerivedOptionValue<LowLevelSuites> defaultLowLevelSuites;
    protected final HotSpotGraalRuntimeProvider runtime;

    public HotSpotSuitesProvider(HotSpotGraalRuntimeProvider runtime) {
        this.runtime = runtime;
        this.defaultGraphBuilderSuite = createGraphBuilderSuite();
        this.defaultSuites = new DerivedOptionValue<>(this::get);
        this.defaultLowLevelSuites = new DerivedOptionValue<>(this::createLowLevelSuites);
    }

    public Suites getDefaultSuites() {
        return defaultSuites.getValue();
    }

    public Suites get() {
        return createSuites();
    }

    public PhaseSuite<HighTierContext> getDefaultGraphBuilderSuite() {
        return defaultGraphBuilderSuite;
    }

    public Suites createSuites() {
        Suites ret = Suites.createDefaultSuites();

        if (ImmutableCode.getValue()) {
            // lowering introduces class constants, therefore it must be after lowering
            ret.getHighTier().appendPhase(new LoadJavaMirrorWithKlassPhase(runtime.getConfig().classMirrorOffset, runtime.getConfig().getOopEncoding()));
            if (VerifyPhases.getValue()) {
                ret.getHighTier().appendPhase(new AheadOfTimeVerificationPhase());
            }
        }

        ret.getMidTier().appendPhase(new WriteBarrierAdditionPhase(runtime.getConfig()));
        if (VerifyPhases.getValue()) {
            ret.getMidTier().appendPhase(new WriteBarrierVerificationPhase());
        }

        return ret;
    }

    protected PhaseSuite<HighTierContext> createGraphBuilderSuite() {
        PhaseSuite<HighTierContext> suite = new PhaseSuite<>();
        GraphBuilderConfiguration config = GraphBuilderConfiguration.getDefault();
        config.setInlineInvokePlugin(new InlineInvokePlugin() {
            public boolean shouldInlineInvoke(ResolvedJavaMethod method, int depth) {
                return GraalOptions.InlineDuringParsing.getValue() && method.getCode().length <= GraalOptions.TrivialInliningSize.getValue() &&
                                depth < GraalOptions.InlineDuringParsingMaxDepth.getValue();
            }
        });
        suite.appendPhase(new GraphBuilderPhase(config));
        return suite;
    }

    /**
     * Modifies the {@link GraphBuilderConfiguration} to build extra
     * {@linkplain DebugInfoMode#Simple debug info} if the VM
     * {@linkplain CompilerToVM#shouldDebugNonSafepoints() requests} it.
     *
     * @param gbs the current graph builder suite
     * @return a possibly modified graph builder suite
     */
    public static PhaseSuite<HighTierContext> withSimpleDebugInfoIfRequested(PhaseSuite<HighTierContext> gbs) {
        if (HotSpotGraalRuntime.runtime().getCompilerToVM().shouldDebugNonSafepoints()) {
            PhaseSuite<HighTierContext> newGbs = gbs.copy();
            GraphBuilderPhase graphBuilderPhase = (GraphBuilderPhase) newGbs.findPhase(GraphBuilderPhase.class).previous();
            GraphBuilderConfiguration graphBuilderConfig = graphBuilderPhase.getGraphBuilderConfig();
            GraphBuilderPhase newGraphBuilderPhase = new GraphBuilderPhase(graphBuilderConfig.withDebugInfoMode(DebugInfoMode.Simple));
            newGbs.findPhase(GraphBuilderPhase.class).set(newGraphBuilderPhase);
            return newGbs;
        }
        return gbs;
    }

    public LowLevelSuites getDefaultLowLevelSuites() {
        return defaultLowLevelSuites.getValue();
    }

    public LowLevelSuites createLowLevelSuites() {
        return Suites.createDefaultLowLevelSuites();
    }

}
