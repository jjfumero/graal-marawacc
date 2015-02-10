/*
 * Copyright (c) 2014, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.compiler.test.backend;

import static com.oracle.graal.api.code.CodeUtil.*;
import static com.oracle.graal.compiler.common.GraalOptions.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.CallingConvention.Type;
import com.oracle.graal.compiler.*;
import com.oracle.graal.compiler.test.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.lir.gen.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.schedule.*;

public abstract class BackendTest extends GraalCompilerTest {

    public BackendTest() {
        super();
    }

    public BackendTest(Class<? extends Architecture> arch) {
        super(arch);
    }

    protected LIRGenerationResult getLIRGenerationResult(final StructuredGraph graph) {
        final Assumptions assumptions = new Assumptions(OptAssumptions.getValue());

        SchedulePhase schedule = null;
        try (Scope s = Debug.scope("FrontEnd")) {
            schedule = GraalCompiler.emitFrontEnd(getProviders(), getBackend().getTarget(), graph, assumptions, null, getDefaultGraphBuilderSuite(), OptimisticOptimizations.NONE,
                            graph.method().getProfilingInfo(), null, getSuites());
        } catch (Throwable e) {
            throw Debug.handle(e);
        }

        CallingConvention cc = getCallingConvention(getCodeCache(), Type.JavaCallee, graph.method(), false);
        LIRGenerationResult lirGen = GraalCompiler.emitLIR(getBackend(), getBackend().getTarget(), schedule, graph, null, cc, null, getLowLevelSuites());
        return lirGen;
    }

}
