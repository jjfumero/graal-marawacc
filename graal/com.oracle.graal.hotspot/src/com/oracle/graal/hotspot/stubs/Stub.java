/*
 * Copyright (c) 2012, 2014, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.compiler.GraalCompiler.*;
import static com.oracle.graal.hotspot.HotSpotGraalRuntime.*;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.compiler.target.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.debug.internal.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.bridge.CompilerToVM.CodeInstallResult;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.lir.phases.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.schedule.*;
import com.oracle.graal.phases.tiers.*;

//JaCoCo Exclude

/**
 * Base class for implementing some low level code providing the out-of-line slow path for a snippet
 * and/or a callee saved call to a HotSpot C/C++ runtime function or even a another compiled Java
 * method.
 */
public abstract class Stub {

    private static final List<Stub> stubs = new ArrayList<>();

    /**
     * The linkage information for a call to this stub from compiled code.
     */
    protected final HotSpotForeignCallLinkage linkage;

    /**
     * The code installed for the stub.
     */
    protected InstalledCode code;

    /**
     * Compilation result from which {@link #code} was created.
     */
    protected CompilationResult compResult;

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

    protected final HotSpotProviders providers;

    /**
     * Creates a new stub.
     *
     * @param linkage linkage details for a call to the stub
     */
    public Stub(HotSpotProviders providers, HotSpotForeignCallLinkage linkage) {
        this.linkage = linkage;
        this.providers = providers;
        stubs.add(this);
    }

    /**
     * Gets an immutable view of all stubs that have been created.
     */
    public static Collection<Stub> getStubs() {
        return Collections.unmodifiableList(stubs);
    }

    /**
     * Gets the linkage for a call to this stub from compiled code.
     */
    public HotSpotForeignCallLinkage getLinkage() {
        return linkage;
    }

    public RegisterConfig getRegisterConfig() {
        return null;
    }

    /**
     * Gets the graph that from which the code for this stub will be compiled.
     */
    protected abstract StructuredGraph getGraph();

    @Override
    public String toString() {
        return "Stub<" + linkage.getDescriptor() + ">";
    }

    /**
     * Gets the method the stub's code will be associated with once installed. This may be null.
     */
    protected abstract ResolvedJavaMethod getInstalledCodeOwner();

    /**
     * Gets a context object for the debug scope created when producing the code for this stub.
     */
    protected abstract Object debugScopeContext();

    /**
     * Gets the code for this stub, compiling it first if necessary.
     */
    public synchronized InstalledCode getCode(final Backend backend) {
        if (code == null) {
            try (Scope d = Debug.sandbox("CompilingStub", DebugScope.getConfig(), providers.getCodeCache(), debugScopeContext())) {
                final StructuredGraph graph = getGraph();
                assert !graph.getAssumptions().useOptimisticAssumptions();
                if (!(graph.start() instanceof StubStartNode)) {
                    StubStartNode newStart = graph.add(new StubStartNode(Stub.this));
                    newStart.setStateAfter(graph.start().stateAfter());
                    graph.replaceFixed(graph.start(), newStart);
                }

                CodeCacheProvider codeCache = providers.getCodeCache();
                // The stub itself needs the incoming calling convention.
                CallingConvention incomingCc = linkage.getIncomingCallingConvention();
                TargetDescription target = codeCache.getTarget();

                compResult = new CompilationResult(toString());
                try (Scope s0 = Debug.scope("StubCompilation", graph, providers.getCodeCache())) {
                    Suites defaultSuites = providers.getSuites().getDefaultSuites();
                    Suites suites = new Suites(new PhaseSuite<>(), defaultSuites.getMidTier(), defaultSuites.getLowTier());
                    SchedulePhase schedule = emitFrontEnd(providers, target, graph, null, providers.getSuites().getDefaultGraphBuilderSuite(), OptimisticOptimizations.ALL, getProfilingInfo(graph),
                                    null, suites);
                    LIRSuites lirSuites = providers.getSuites().getDefaultLIRSuites();
                    emitBackEnd(graph, Stub.this, incomingCc, getInstalledCodeOwner(), backend, target, compResult, CompilationResultBuilderFactory.Default, schedule, getRegisterConfig(), lirSuites);
                } catch (Throwable e) {
                    throw Debug.handle(e);
                }

                assert destroyedRegisters != null;
                try (Scope s = Debug.scope("CodeInstall")) {
                    Stub stub = Stub.this;
                    HotSpotRuntimeStub installedCode = new HotSpotRuntimeStub(stub);
                    HotSpotCompiledCode hsCompResult = new HotSpotCompiledRuntimeStub(stub, compResult);

                    CodeInstallResult result = runtime().getCompilerToVM().installCode(hsCompResult, installedCode, null);
                    if (result != CodeInstallResult.OK) {
                        throw new GraalInternalError("Error installing stub %s: %s", Stub.this, result);
                    }
                    ((HotSpotCodeCacheProvider) codeCache).logOrDump(installedCode, compResult);
                    code = installedCode;
                } catch (Throwable e) {
                    throw Debug.handle(e);
                }
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
            assert code != null : "error installing stub " + this;
        }

        return code;
    }

    /**
     * Gets the compilation result for this stub, compiling it first if necessary, and installing it
     * in code.
     */
    public synchronized CompilationResult getCompilationResult(final Backend backend) {
        if (code == null) {
            getCode(backend);
        }
        return compResult;
    }
}
