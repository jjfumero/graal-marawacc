/*
 * Copyright (c) 2012, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.amd64;

import static com.oracle.graal.compiler.common.GraalOptions.CanOmitFrame;
import static com.oracle.graal.compiler.common.GraalOptions.ZapStackOnMethodEntry;
import static jdk.internal.jvmci.amd64.AMD64.r10;
import static jdk.internal.jvmci.amd64.AMD64.rax;
import static jdk.internal.jvmci.amd64.AMD64.rsp;
import static jdk.internal.jvmci.code.CallingConvention.Type.JavaCallee;
import static jdk.internal.jvmci.code.ValueUtil.asRegister;

import java.lang.reflect.Field;
import java.util.Set;

import jdk.internal.jvmci.amd64.AMD64;
import jdk.internal.jvmci.code.CallingConvention;
import jdk.internal.jvmci.code.CompilationResult;
import jdk.internal.jvmci.code.Register;
import jdk.internal.jvmci.code.RegisterConfig;
import jdk.internal.jvmci.code.StackSlot;
import jdk.internal.jvmci.hotspot.HotSpotVMConfig;
import jdk.internal.jvmci.meta.JavaType;
import jdk.internal.jvmci.meta.ResolvedJavaMethod;
import sun.misc.Unsafe;

import com.oracle.graal.asm.Assembler;
import com.oracle.graal.asm.Label;
import com.oracle.graal.asm.amd64.AMD64Address;
import com.oracle.graal.asm.amd64.AMD64Assembler.ConditionFlag;
import com.oracle.graal.asm.amd64.AMD64MacroAssembler;
import com.oracle.graal.compiler.common.alloc.RegisterAllocationConfig;
import com.oracle.graal.compiler.gen.BytecodeLIRBuilder;
import com.oracle.graal.compiler.gen.BytecodeParserTool;
import com.oracle.graal.compiler.target.Backend;
import com.oracle.graal.hotspot.HotSpotGraalRuntimeProvider;
import com.oracle.graal.hotspot.HotSpotHostBackend;
import com.oracle.graal.hotspot.meta.HotSpotForeignCallsProvider;
import com.oracle.graal.hotspot.meta.HotSpotProviders;
import com.oracle.graal.hotspot.stubs.Stub;
import com.oracle.graal.lir.LIR;
import com.oracle.graal.lir.amd64.AMD64Call;
import com.oracle.graal.lir.amd64.AMD64FrameMap;
import com.oracle.graal.lir.amd64.AMD64FrameMapBuilder;
import com.oracle.graal.lir.asm.CompilationResultBuilder;
import com.oracle.graal.lir.asm.CompilationResultBuilderFactory;
import com.oracle.graal.lir.asm.FrameContext;
import com.oracle.graal.lir.framemap.FrameMap;
import com.oracle.graal.lir.framemap.FrameMapBuilder;
import com.oracle.graal.lir.gen.LIRGenerationResult;
import com.oracle.graal.lir.gen.LIRGeneratorTool;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.spi.NodeLIRBuilderTool;

/**
 * HotSpot AMD64 specific backend.
 */
public class AMD64HotSpotBackend extends HotSpotHostBackend {

    public AMD64HotSpotBackend(HotSpotVMConfig config, HotSpotGraalRuntimeProvider runtime, HotSpotProviders providers) {
        super(config, runtime, providers);
    }

    @Override
    public FrameMapBuilder newFrameMapBuilder(RegisterConfig registerConfig) {
        RegisterConfig registerConfigNonNull = registerConfig == null ? getCodeCache().getRegisterConfig() : registerConfig;
        return new AMD64FrameMapBuilder(newFrameMap(registerConfigNonNull), getCodeCache(), registerConfigNonNull);
    }

    @Override
    public FrameMap newFrameMap(RegisterConfig registerConfig) {
        return new AMD64FrameMap(getCodeCache(), registerConfig, this);
    }

    @Override
    public LIRGeneratorTool newLIRGenerator(CallingConvention cc, LIRGenerationResult lirGenRes) {
        return new AMD64HotSpotLIRGenerator(getProviders(), getRuntime().getConfig(), cc, lirGenRes);
    }

    @Override
    public LIRGenerationResult newLIRGenerationResult(String compilationUnitName, LIR lir, FrameMapBuilder frameMapBuilder, ResolvedJavaMethod method, Object stub) {
        return new AMD64HotSpotLIRGenerationResult(compilationUnitName, lir, frameMapBuilder, stub);
    }

    @Override
    public NodeLIRBuilderTool newNodeLIRBuilder(StructuredGraph graph, LIRGeneratorTool lirGen) {
        return new AMD64HotSpotNodeLIRBuilder(getRuntime(), graph, lirGen);
    }

    @Override
    public BytecodeLIRBuilder newBytecodeLIRBuilder(LIRGeneratorTool gen, BytecodeParserTool parser) {
        return new AMD64HotSpotBytecodeLIRBuilder(gen, parser);

    }

    /**
     * Emits code to do stack overflow checking.
     *
     * @param afterFrameInit specifies if the stack pointer has already been adjusted to allocate
     *            the current frame
     * @param isVerifiedEntryPoint specifies if the code buffer is currently at the verified entry
     *            point
     */
    protected static void emitStackOverflowCheck(CompilationResultBuilder crb, int pagesToBang, boolean afterFrameInit, boolean isVerifiedEntryPoint) {
        if (pagesToBang > 0) {

            AMD64MacroAssembler asm = (AMD64MacroAssembler) crb.asm;
            int frameSize = crb.frameMap.frameSize();
            if (frameSize > 0) {
                int lastFramePage = frameSize / UNSAFE.pageSize();
                // emit multiple stack bangs for methods with frames larger than a page
                for (int i = 0; i <= lastFramePage; i++) {
                    int disp = (i + pagesToBang) * UNSAFE.pageSize();
                    if (afterFrameInit) {
                        disp -= frameSize;
                    }
                    crb.blockComment("[stack overflow check]");
                    int pos = asm.position();
                    asm.movl(new AMD64Address(rsp, -disp), AMD64.rax);
                    assert i > 0 || !isVerifiedEntryPoint || asm.position() - pos >= PATCHED_VERIFIED_ENTRY_POINT_INSTRUCTION_SIZE;
                }
            }
        }
    }

    /**
     * The size of the instruction used to patch the verified entry point of an nmethod when the
     * nmethod is made non-entrant or a zombie (e.g. during deopt or class unloading). The first
     * instruction emitted at an nmethod's verified entry point must be at least this length to
     * ensure mt-safe patching.
     */
    public static final int PATCHED_VERIFIED_ENTRY_POINT_INSTRUCTION_SIZE = 5;

    /**
     * Emits code at the verified entry point and return point(s) of a method.
     */
    class HotSpotFrameContext implements FrameContext {

        final boolean isStub;
        final boolean omitFrame;

        HotSpotFrameContext(boolean isStub, boolean omitFrame) {
            this.isStub = isStub;
            this.omitFrame = omitFrame;
        }

        public boolean hasFrame() {
            return !omitFrame;
        }

        @Override
        public void enter(CompilationResultBuilder crb) {
            FrameMap frameMap = crb.frameMap;
            int frameSize = frameMap.frameSize();
            AMD64MacroAssembler asm = (AMD64MacroAssembler) crb.asm;
            if (omitFrame) {
                if (!isStub) {
                    asm.nop(PATCHED_VERIFIED_ENTRY_POINT_INSTRUCTION_SIZE);
                }
            } else {
                int verifiedEntryPointOffset = asm.position();
                if (!isStub && pagesToBang > 0) {
                    emitStackOverflowCheck(crb, pagesToBang, false, true);
                    assert asm.position() - verifiedEntryPointOffset >= PATCHED_VERIFIED_ENTRY_POINT_INSTRUCTION_SIZE;
                }
                if (!isStub && asm.position() == verifiedEntryPointOffset) {
                    asm.subqWide(rsp, frameSize);
                    assert asm.position() - verifiedEntryPointOffset >= PATCHED_VERIFIED_ENTRY_POINT_INSTRUCTION_SIZE;
                } else {
                    asm.decrementq(rsp, frameSize);
                }
                if (ZapStackOnMethodEntry.getValue()) {
                    final int intSize = 4;
                    for (int i = 0; i < frameSize / intSize; ++i) {
                        asm.movl(new AMD64Address(rsp, i * intSize), 0xC1C1C1C1);
                    }
                }
                assert frameMap.getRegisterConfig().getCalleeSaveRegisters() == null;
            }
        }

        @Override
        public void leave(CompilationResultBuilder crb) {
            if (!omitFrame) {
                AMD64MacroAssembler asm = (AMD64MacroAssembler) crb.asm;
                assert crb.frameMap.getRegisterConfig().getCalleeSaveRegisters() == null;

                int frameSize = crb.frameMap.frameSize();
                asm.incrementq(rsp, frameSize);
            }
        }
    }

    @Override
    protected Assembler createAssembler(FrameMap frameMap) {
        return new AMD64MacroAssembler(getTarget(), frameMap.getRegisterConfig());
    }

    @Override
    public CompilationResultBuilder newCompilationResultBuilder(LIRGenerationResult lirGenRen, FrameMap frameMap, CompilationResult compilationResult, CompilationResultBuilderFactory factory) {
        // Omit the frame if the method:
        // - has no spill slots or other slots allocated during register allocation
        // - has no callee-saved registers
        // - has no incoming arguments passed on the stack
        // - has no deoptimization points
        // - makes no foreign calls (which require an aligned stack)
        AMD64HotSpotLIRGenerationResult gen = (AMD64HotSpotLIRGenerationResult) lirGenRen;
        LIR lir = gen.getLIR();
        assert gen.getDeoptimizationRescueSlot() == null || frameMap.frameNeedsAllocating() : "method that can deoptimize must have a frame";
        boolean omitFrame = CanOmitFrame.getValue() && !frameMap.frameNeedsAllocating() && !lir.hasArgInCallerFrame() && !gen.hasForeignCall();

        Stub stub = gen.getStub();
        Assembler masm = createAssembler(frameMap);
        HotSpotFrameContext frameContext = new HotSpotFrameContext(stub != null, omitFrame);
        CompilationResultBuilder crb = factory.createBuilder(getCodeCache(), getForeignCalls(), frameMap, masm, frameContext, compilationResult);
        crb.setTotalFrameSize(frameMap.totalFrameSize());
        StackSlot deoptimizationRescueSlot = gen.getDeoptimizationRescueSlot();
        if (deoptimizationRescueSlot != null && stub == null) {
            crb.compilationResult.setCustomStackAreaOffset(frameMap.offsetForStackSlot(deoptimizationRescueSlot));
        }

        if (stub != null) {
            Set<Register> definedRegisters = gatherDefinedRegisters(lir);
            updateStub(stub, definedRegisters, gen.getCalleeSaveInfo(), frameMap);
        }

        return crb;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, LIR lir, ResolvedJavaMethod installedCodeOwner) {
        AMD64MacroAssembler asm = (AMD64MacroAssembler) crb.asm;
        FrameMap frameMap = crb.frameMap;
        RegisterConfig regConfig = frameMap.getRegisterConfig();
        HotSpotVMConfig config = getRuntime().getConfig();
        Label verifiedEntry = new Label();

        // Emit the prefix
        emitCodePrefix(installedCodeOwner, crb, asm, regConfig, config, verifiedEntry);

        // Emit code for the LIR
        emitCodeBody(installedCodeOwner, crb, lir);

        // Emit the suffix
        emitCodeSuffix(installedCodeOwner, crb, asm, config, frameMap);

        // Profile assembler instructions
        profileInstructions(lir, crb);
    }

    /**
     * Emits the code prior to the verified entry point.
     *
     * @param installedCodeOwner see {@link Backend#emitCode}
     */
    public void emitCodePrefix(ResolvedJavaMethod installedCodeOwner, CompilationResultBuilder crb, AMD64MacroAssembler asm, RegisterConfig regConfig, HotSpotVMConfig config, Label verifiedEntry) {
        HotSpotProviders providers = getProviders();
        if (installedCodeOwner != null && !installedCodeOwner.isStatic()) {
            crb.recordMark(config.MARKID_UNVERIFIED_ENTRY);
            CallingConvention cc = regConfig.getCallingConvention(JavaCallee, null, new JavaType[]{providers.getMetaAccess().lookupJavaType(Object.class)}, getTarget(), false);
            Register inlineCacheKlass = rax; // see definition of IC_Klass in
                                             // c1_LIRAssembler_x86.cpp
            Register receiver = asRegister(cc.getArgument(0));
            AMD64Address src = new AMD64Address(receiver, config.hubOffset);

            if (config.useCompressedClassPointers) {
                Register register = r10;
                AMD64HotSpotMove.decodeKlassPointer(asm, register, providers.getRegisters().getHeapBaseRegister(), src, config.getKlassEncoding());
                if (config.narrowKlassBase != 0) {
                    // The heap base register was destroyed above, so restore it
                    asm.movq(providers.getRegisters().getHeapBaseRegister(), config.narrowOopBase);
                }
                asm.cmpq(inlineCacheKlass, register);
            } else {
                asm.cmpq(inlineCacheKlass, src);
            }
            AMD64Call.directConditionalJmp(crb, asm, getForeignCalls().lookupForeignCall(IC_MISS_HANDLER), ConditionFlag.NotEqual);
        }

        asm.align(config.codeEntryAlignment);
        crb.recordMark(config.MARKID_OSR_ENTRY);
        asm.bind(verifiedEntry);
        crb.recordMark(config.MARKID_VERIFIED_ENTRY);
    }

    /**
     * Emits the code which starts at the verified entry point.
     *
     * @param installedCodeOwner see {@link Backend#emitCode}
     */
    public void emitCodeBody(ResolvedJavaMethod installedCodeOwner, CompilationResultBuilder crb, LIR lir) {
        crb.emit(lir);
    }

    /**
     * @param installedCodeOwner see {@link Backend#emitCode}
     * @param config
     */
    public void emitCodeSuffix(ResolvedJavaMethod installedCodeOwner, CompilationResultBuilder crb, AMD64MacroAssembler asm, HotSpotVMConfig config, FrameMap frameMap) {
        HotSpotProviders providers = getProviders();
        HotSpotFrameContext frameContext = (HotSpotFrameContext) crb.frameContext;
        if (!frameContext.isStub) {
            HotSpotForeignCallsProvider foreignCalls = providers.getForeignCalls();
            crb.recordMark(config.MARKID_EXCEPTION_HANDLER_ENTRY);
            AMD64Call.directCall(crb, asm, foreignCalls.lookupForeignCall(EXCEPTION_HANDLER), null, false, null);
            crb.recordMark(config.MARKID_DEOPT_HANDLER_ENTRY);
            AMD64Call.directCall(crb, asm, foreignCalls.lookupForeignCall(DEOPTIMIZATION_HANDLER), null, false, null);
        } else {
            // No need to emit the stubs for entries back into the method since
            // it has no calls that can cause such "return" entries

            if (frameContext.omitFrame) {
                // Cannot access slots in caller's frame if my frame is omitted
                assert !frameMap.accessesCallerFrame();
            }
        }
    }

    @Override
    public RegisterAllocationConfig newRegisterAllocationConfig(RegisterConfig registerConfig) {
        RegisterConfig registerConfigNonNull = registerConfig == null ? getCodeCache().getRegisterConfig() : registerConfig;
        return new AMD64HotSpotRegisterAllocationConfig(registerConfigNonNull);
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
