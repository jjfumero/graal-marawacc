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
package com.oracle.graal.hotspot.stubs;

import static com.oracle.graal.hotspot.HotSpotGraalRuntime.*;

import java.util.*;
import java.util.concurrent.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.CompilationResult.Call;
import com.oracle.graal.api.code.CompilationResult.DataPatch;
import com.oracle.graal.api.code.CompilationResult.Infopoint;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.*;
import com.oracle.graal.compiler.target.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.internal.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.java.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.PhasePlan.PhasePosition;

//JaCoCo Exclude

/**
 * Base class for implementing some low level code providing the out-of-line slow path for a snippet
 * and/or a callee saved call to a HotSpot C/C++ runtime function or even a another compiled Java
 * method.
 */
public abstract class Stub {

    /**
     * The linkage information for the stub.
     */
    protected final HotSpotRuntimeCallTarget linkage;

    /**
     * The code installed for the stub.
     */
    protected InstalledCode code;

    /**
     * The registers destroyed by this stub.
     */
    private Set<Register> destroyedRegisters;

    public void initDestroyedRegisters(Set<Register> registers) {
        assert registers != null;
        assert destroyedRegisters == null || registers.equals(destroyedRegisters) : "cannot redefine";
        destroyedRegisters = registers;
    }

    /**
     * Gets the registers defined by this stub. These are the temporaries of this stub and must thus
     * be caller saved by a callers of this stub.
     */
    public Set<Register> getDestroyedRegisters() {
        assert destroyedRegisters != null : "not yet initialized";
        return destroyedRegisters;
    }

    /**
     * Determines if this stub preserves all registers apart from those it
     * {@linkplain #getDestroyedRegisters() destroys}.
     */
    public boolean preservesRegisters() {
        return true;
    }

    protected final HotSpotRuntime runtime;

    protected final Replacements replacements;

    /**
     * Creates a new stub.
     * 
     * @param linkage linkage details for a call to the stub
     */
    public Stub(HotSpotRuntime runtime, Replacements replacements, HotSpotRuntimeCallTarget linkage) {
        this.linkage = linkage;
        this.runtime = runtime;
        this.replacements = replacements;
    }

    public HotSpotRuntimeCallTarget getLinkage() {
        return linkage;
    }

    /**
     * Checks the conditions a compilation must satisfy to be installed as a RuntimeStub.
     */
    private boolean checkStubInvariants(CompilationResult compResult) {
        for (DataPatch data : compResult.getDataReferences()) {
            Constant constant = data.constant;
            assert constant.getKind() != Kind.Object : this + " cannot have embedded object constant: " + constant;
            assert constant.getPrimitiveAnnotation() == null : this + " cannot have embedded metadata: " + constant;
        }
        for (Infopoint infopoint : compResult.getInfopoints()) {
            assert infopoint instanceof Call : this + " cannot have non-call infopoint: " + infopoint;
            Call call = (Call) infopoint;
            assert call.target instanceof HotSpotRuntimeCallTarget : this + " cannot have non runtime call: " + call.target;
            HotSpotRuntimeCallTarget callTarget = (HotSpotRuntimeCallTarget) call.target;
            assert callTarget.getAddress() == graalRuntime().getConfig().uncommonTrapStub || callTarget.isCRuntimeCall() : this + "must only call C runtime or deoptimization stub, not " + call.target;
        }
        return true;
    }

    protected abstract StructuredGraph getGraph();

    @Override
    public abstract String toString();

    /**
     * Gets the method under which the compiled code for this stub is
     * {@linkplain CodeCacheProvider#addMethod(ResolvedJavaMethod, CompilationResult) installed}.
     */
    protected abstract ResolvedJavaMethod getInstallationMethod();

    protected Object debugScopeContext() {
        return getInstallationMethod();
    }

    /**
     * Gets the code for this stub, compiling it first if necessary.
     */
    public synchronized InstalledCode getCode(final Backend backend) {
        if (code == null) {
            Debug.sandbox("CompilingStub", new Object[]{runtime, debugScopeContext()}, DebugScope.getConfig(), new Runnable() {

                @Override
                public void run() {

                    final StructuredGraph graph = getGraph();
                    StubStartNode newStart = graph.add(new StubStartNode(Stub.this));
                    newStart.setStateAfter(graph.start().stateAfter());
                    graph.replaceFixed(graph.start(), newStart);
                    graph.setStart(newStart);

                    PhasePlan phasePlan = new PhasePlan();
                    GraphBuilderPhase graphBuilderPhase = new GraphBuilderPhase(runtime, GraphBuilderConfiguration.getDefault(), OptimisticOptimizations.ALL);
                    phasePlan.addPhase(PhasePosition.AFTER_PARSING, graphBuilderPhase);
                    CallingConvention cc = linkage.getCallingConvention();
                    final CompilationResult compResult = GraalCompiler.compileGraph(graph, cc, runtime, replacements, backend, runtime.getTarget(), null, phasePlan, OptimisticOptimizations.ALL,
                                    new SpeculationLog());

                    assert checkStubInvariants(compResult);

                    assert destroyedRegisters != null;
                    code = Debug.scope("CodeInstall", new Callable<InstalledCode>() {

                        @Override
                        public InstalledCode call() {
                            InstalledCode installedCode = runtime.addMethod(getInstallationMethod(), compResult);
                            assert installedCode != null : "error installing stub " + this;
                            if (Debug.isDumpEnabled()) {
                                Debug.dump(new Object[]{compResult, installedCode}, "After code installation");
                            }
                            // TTY.println(getMethod().toString());
                            // TTY.println(runtime().disassemble(installedCode));
                            return installedCode;
                        }
                    });
                }
            });
            assert code != null : "error installing stub " + this;
        }
        return code;
    }
}
