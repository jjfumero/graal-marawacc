/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.truffle.hotspot.nfi;

import static com.oracle.graal.truffle.hotspot.nfi.NativeCallStubGraphBuilder.getGraph;
import static jdk.internal.jvmci.code.CodeUtil.getCallingConvention;
import static jdk.internal.jvmci.common.UnsafeUtil.createCString;
import static jdk.internal.jvmci.common.UnsafeUtil.writeCString;

import java.lang.reflect.Field;

import jdk.internal.jvmci.code.CallingConvention;
import jdk.internal.jvmci.code.CallingConvention.Type;
import jdk.internal.jvmci.code.CompilationResult;
import jdk.internal.jvmci.code.InstalledCode;
import jdk.internal.jvmci.hotspot.HotSpotVMConfig;
import jdk.internal.jvmci.meta.DefaultProfilingInfo;
import jdk.internal.jvmci.meta.TriState;
import sun.misc.Unsafe;

import com.oracle.graal.compiler.GraalCompiler;
import com.oracle.graal.compiler.target.Backend;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.hotspot.meta.HotSpotProviders;
import com.oracle.graal.lir.asm.CompilationResultBuilderFactory;
import com.oracle.graal.lir.phases.LIRSuites;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.phases.OptimisticOptimizations;
import com.oracle.graal.phases.PhaseSuite;
import com.oracle.graal.phases.tiers.HighTierContext;
import com.oracle.graal.phases.tiers.Suites;
import com.oracle.nfi.api.NativeFunctionInterface;
import com.oracle.nfi.api.NativeFunctionPointer;
import com.oracle.nfi.api.NativeLibraryHandle;

public class HotSpotNativeFunctionInterface implements NativeFunctionInterface {

    private final HotSpotProviders providers;
    private final Backend backend;
    private final HotSpotNativeLibraryHandle rtldDefault;
    private final HotSpotNativeFunctionPointer libraryLoadFunctionPointer;
    private final HotSpotNativeFunctionPointer functionLookupFunctionPointer;
    private final RawNativeCallNodeFactory factory;

    private HotSpotNativeFunctionHandle libraryLookupFunctionHandle;
    private HotSpotNativeFunctionHandle dllLookupFunctionHandle;

    public HotSpotNativeFunctionInterface(HotSpotProviders providers, RawNativeCallNodeFactory factory, Backend backend, long dlopen, long dlsym, long rtldDefault) {
        this.rtldDefault = rtldDefault == HotSpotVMConfig.INVALID_RTLD_DEFAULT_HANDLE ? null : new HotSpotNativeLibraryHandle("RTLD_DEFAULT", rtldDefault);
        this.providers = providers;
        assert backend != null;
        this.backend = backend;
        this.factory = factory;
        this.libraryLoadFunctionPointer = new HotSpotNativeFunctionPointer(dlopen, "os::dll_load");
        this.functionLookupFunctionPointer = new HotSpotNativeFunctionPointer(dlsym, "os::dll_lookup");
    }

    @Override
    public HotSpotNativeLibraryHandle getLibraryHandle(String libPath) {
        if (libraryLookupFunctionHandle == null) {
            libraryLookupFunctionHandle = createHandle(libraryLoadFunctionPointer, long.class, long.class, long.class, int.class);
        }

        int ebufLen = 1024;
        // Allocating a single chunk for both the error message buffer and the
        // file name simplifies deallocation below.
        long buffer = UNSAFE.allocateMemory(ebufLen + libPath.length() + 1);
        long ebuf = buffer;
        long libPathCString = writeCString(UNSAFE, libPath, buffer + ebufLen);
        try {
            long handle = (long) libraryLookupFunctionHandle.call(libPathCString, ebuf, ebufLen);
            if (handle == 0) {
                throw new UnsatisfiedLinkError(libPath);
            }
            return new HotSpotNativeLibraryHandle(libPath, handle);
        } finally {
            UNSAFE.freeMemory(buffer);
        }
    }

    @Override
    public HotSpotNativeFunctionHandle getFunctionHandle(NativeLibraryHandle library, String name, Class<?> returnType, Class<?>... argumentTypes) {
        HotSpotNativeFunctionPointer functionPointer = lookupFunctionPointer(name, library, false);
        return createHandle(functionPointer, returnType, argumentTypes);
    }

    @Override
    public HotSpotNativeFunctionHandle getFunctionHandle(NativeLibraryHandle[] libraries, String name, Class<?> returnType, Class<?>... argumentTypes) {
        HotSpotNativeFunctionPointer functionPointer = null;
        for (NativeLibraryHandle libraryHandle : libraries) {
            functionPointer = lookupFunctionPointer(name, libraryHandle, false);
            if (functionPointer != null) {
                return createHandle(functionPointer, returnType, argumentTypes);
            }
        }
        // Fall back to default library path
        return getFunctionHandle(name, returnType, argumentTypes);
    }

    @Override
    public HotSpotNativeFunctionHandle getFunctionHandle(String name, Class<?> returnType, Class<?>... argumentTypes) {
        if (rtldDefault == null) {
            throw new UnsatisfiedLinkError(name);
        }
        return getFunctionHandle(rtldDefault, name, returnType, argumentTypes);
    }

    private HotSpotNativeFunctionPointer lookupFunctionPointer(String name, NativeLibraryHandle library, boolean linkageErrorIfMissing) {
        if (name == null || library == null) {
            throw new NullPointerException();
        }

        if (dllLookupFunctionHandle == null) {
            dllLookupFunctionHandle = createHandle(functionLookupFunctionPointer, long.class, long.class, long.class);
        }

        long nameCString = createCString(UNSAFE, name);
        try {
            long functionPointer = (long) dllLookupFunctionHandle.call(((HotSpotNativeLibraryHandle) library).value, nameCString);
            if (functionPointer == 0L) {
                if (!linkageErrorIfMissing) {
                    return null;
                }
                throw new UnsatisfiedLinkError(name);
            }
            return new HotSpotNativeFunctionPointer(functionPointer, name);
        } finally {
            UNSAFE.freeMemory(nameCString);
        }
    }

    @Override
    public HotSpotNativeFunctionHandle getFunctionHandle(NativeFunctionPointer functionPointer, Class<?> returnType, Class<?>... argumentTypes) {
        if (!(functionPointer instanceof HotSpotNativeFunctionPointer)) {
            throw new UnsatisfiedLinkError(functionPointer.getName());
        }
        return createHandle(functionPointer, returnType, argumentTypes);
    }

    private HotSpotNativeFunctionHandle createHandle(NativeFunctionPointer functionPointer, Class<?> returnType, Class<?>... argumentTypes) {
        HotSpotNativeFunctionPointer hs = (HotSpotNativeFunctionPointer) functionPointer;
        if (hs != null) {
            InstalledCode code = installNativeFunctionStub(hs.value, returnType, argumentTypes);
            return new HotSpotNativeFunctionHandle(code, hs.name, argumentTypes);
        } else {
            return null;
        }
    }

    /**
     * Creates and installs a stub for calling a native function.
     */
    @SuppressWarnings("try")
    private InstalledCode installNativeFunctionStub(long functionPointer, Class<?> returnType, Class<?>... argumentTypes) {
        StructuredGraph g = getGraph(providers, factory, functionPointer, returnType, argumentTypes);
        Suites suites = providers.getSuites().createSuites();
        LIRSuites lirSuites = providers.getSuites().createLIRSuites();
        PhaseSuite<HighTierContext> phaseSuite = backend.getSuites().getDefaultGraphBuilderSuite().copy();
        CallingConvention cc = getCallingConvention(providers.getCodeCache(), Type.JavaCallee, g.method(), false);
        CompilationResult compResult = GraalCompiler.compileGraph(g, cc, g.method(), providers, backend, phaseSuite, OptimisticOptimizations.ALL, DefaultProfilingInfo.get(TriState.UNKNOWN), suites,
                        lirSuites, new CompilationResult(), CompilationResultBuilderFactory.Default);
        InstalledCode installedCode;
        try (Scope s = Debug.scope("CodeInstall", providers.getCodeCache(), g.method())) {
            installedCode = providers.getCodeCache().addCode(g.method(), compResult, null, null);
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
        return installedCode;
    }

    @Override
    public HotSpotNativeFunctionPointer getFunctionPointer(NativeLibraryHandle[] libraries, String name) {
        for (NativeLibraryHandle libraryHandle : libraries) {
            HotSpotNativeFunctionPointer functionPointer = lookupFunctionPointer(name, libraryHandle, false);
            if (functionPointer != null) {
                return functionPointer;
            }
        }
        // Fall back to default library path
        if (rtldDefault == null) {
            throw new UnsatisfiedLinkError(name);
        }
        return lookupFunctionPointer(name, rtldDefault, false);
    }

    public boolean isDefaultLibrarySearchSupported() {
        return rtldDefault != null;
    }

    @Override
    public NativeFunctionPointer getNativeFunctionPointerFromRawValue(long rawValue) {
        return new HotSpotNativeFunctionPointer(rawValue, null);
    }

    private static final Unsafe UNSAFE = initUnsafe();

    private static Unsafe initUnsafe() {
        try {
            return Unsafe.getUnsafe();
        } catch (SecurityException se) {
            try {
                Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                return (Unsafe) theUnsafe.get(Unsafe.class);
            } catch (Exception e) {
                throw new RuntimeException("exception while trying to get Unsafe", e);
            }
        }
    }
}
