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
package com.oracle.graal.java;

import java.util.function.*;

import com.oracle.graal.lir.phases.*;
import com.oracle.graal.options.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.tiers.*;

public class DefaultSuitesProvider implements SuitesProvider, Supplier<Suites> {

    private final DerivedOptionValue<Suites> defaultSuites;
    private final PhaseSuite<HighTierContext> defaultGraphBuilderSuite;
    private final DerivedOptionValue<LowLevelSuites> defaultLowLevelSuites;

    public DefaultSuitesProvider() {
        this.defaultGraphBuilderSuite = createGraphBuilderSuite();
        this.defaultSuites = new DerivedOptionValue<>(this::createSuites);
        this.defaultLowLevelSuites = new DerivedOptionValue<>(this::createLowLevelSuites);
    }

    public Suites getDefaultSuites() {
        return defaultSuites.getValue();
    }

    public Suites get() {
        return createSuites();
    }

    public Suites createSuites() {
        return Suites.createDefaultSuites();
    }

    public PhaseSuite<HighTierContext> getDefaultGraphBuilderSuite() {
        return defaultGraphBuilderSuite;
    }

    protected PhaseSuite<HighTierContext> createGraphBuilderSuite() {
        PhaseSuite<HighTierContext> suite = new PhaseSuite<>();
        suite.appendPhase(new GraphBuilderPhase(GraphBuilderConfiguration.getDefault()));
        return suite;
    }

    public LowLevelSuites getDefaultLowLevelSuites() {
        return defaultLowLevelSuites.getValue();
    }

    public LowLevelSuites createLowLevelSuites() {
        return Suites.createDefaultLowLevelSuites();
    }

}
