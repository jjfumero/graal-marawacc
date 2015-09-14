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
package com.oracle.graal.hotspot.sparc;

import static com.oracle.graal.hotspot.HotSpotBackend.*;
import static com.oracle.graal.hotspot.HotSpotGraalRuntime.*;
import static com.oracle.graal.lir.LIRValueUtil.*;
import static jdk.internal.jvmci.hotspot.HotSpotCompressedNullConstant.*;
import static jdk.internal.jvmci.sparc.SPARC.*;

import java.util.*;

import jdk.internal.jvmci.code.*;
import jdk.internal.jvmci.common.*;
import jdk.internal.jvmci.hotspot.*;
import jdk.internal.jvmci.hotspot.HotSpotVMConfig.CompressEncoding;
import jdk.internal.jvmci.meta.*;
import jdk.internal.jvmci.sparc.*;

import com.oracle.graal.compiler.common.calc.*;
import com.oracle.graal.compiler.common.spi.*;
import com.oracle.graal.compiler.sparc.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.debug.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.stubs.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.StandardOp.SaveRegistersOp;
import com.oracle.graal.lir.gen.*;
import com.oracle.graal.lir.sparc.*;
import com.oracle.graal.lir.sparc.SPARCControlFlow.StrategySwitchOp;
import com.oracle.graal.lir.sparc.SPARCMove.CompareAndSwapOp;
import com.oracle.graal.lir.sparc.SPARCMove.LoadOp;
import com.oracle.graal.lir.sparc.SPARCMove.NullCheckOp;
import com.oracle.graal.lir.sparc.SPARCMove.StoreConstantOp;
import com.oracle.graal.lir.sparc.SPARCMove.StoreOp;

public class SPARCHotSpotLIRGenerator extends SPARCLIRGenerator implements HotSpotLIRGenerator {

    final HotSpotVMConfig config;
    private HotSpotLockStack lockStack;
    private LIRFrameState currentRuntimeCallInfo;

    public SPARCHotSpotLIRGenerator(HotSpotProviders providers, HotSpotVMConfig config, CallingConvention cc, LIRGenerationResult lirGenRes) {
        this(new DefaultLIRKindTool(providers.getCodeCache().getTarget().wordKind), providers, config, cc, lirGenRes);
    }

    protected SPARCHotSpotLIRGenerator(LIRKindTool lirKindTool, HotSpotProviders providers, HotSpotVMConfig config, CallingConvention cc, LIRGenerationResult lirGenRes) {
        super(lirKindTool, providers, cc, lirGenRes);
        assert config.basicLockSize == 8;
        this.config = config;
    }

    @Override
    public HotSpotProviders getProviders() {
        return (HotSpotProviders) super.getProviders();
    }

    /**
     * The slot reserved for storing the original return address when a frame is marked for
     * deoptimization. The return address slot in the callee is overwritten with the address of a
     * deoptimization stub.
     */
    private StackSlot deoptimizationRescueSlot;

    /**
     * Value where the address for safepoint poll is kept.
     */
    private AllocatableValue safepointAddressValue;

    @Override
    public StackSlotValue getLockSlot(int lockDepth) {
        return getLockStack().makeLockSlot(lockDepth);
    }

    private HotSpotLockStack getLockStack() {
        assert lockStack != null;
        return lockStack;
    }

    protected void setLockStack(HotSpotLockStack lockStack) {
        assert this.lockStack == null;
        this.lockStack = lockStack;
    }

    @Override
    public boolean needOnlyOopMaps() {
        // Stubs only need oop maps
        return getStub() != null;
    }

    public Stub getStub() {
        return ((SPARCHotSpotLIRGenerationResult) getResult()).getStub();
    }

    @Override
    public void beforeRegisterAllocation() {
        super.beforeRegisterAllocation();
        boolean hasDebugInfo = getResult().getLIR().hasDebugInfo();
        if (hasDebugInfo) {
            ((SPARCHotSpotLIRGenerationResult) getResult()).setDeoptimizationRescueSlot(((SPARCFrameMapBuilder) getResult().getFrameMapBuilder()).allocateDeoptimizationRescueSlot());
        }
    }

    @Override
    protected void emitForeignCallOp(ForeignCallLinkage linkage, Value result, Value[] arguments, Value[] temps, LIRFrameState info) {
        currentRuntimeCallInfo = info;
        super.emitForeignCallOp(linkage, result, arguments, temps, info);
    }

    @Override
    public Variable emitForeignCall(ForeignCallLinkage linkage, LIRFrameState state, Value... args) {
        HotSpotForeignCallLinkage hotspotLinkage = (HotSpotForeignCallLinkage) linkage;
        Variable result;
        LIRFrameState debugInfo = null;
        if (hotspotLinkage.needsDebugInfo()) {
            debugInfo = state;
            assert debugInfo != null || getStub() != null;
        }

        if (linkage.destroysRegisters() || hotspotLinkage.needsJavaFrameAnchor()) {
            HotSpotRegistersProvider registers = getProviders().getRegisters();
            Register thread = registers.getThreadRegister();
            Value threadTemp = newVariable(LIRKind.value(JavaKind.Long));
            Register stackPointer = registers.getStackPointerRegister();
            Variable spScratch = newVariable(LIRKind.value(target().wordKind));
            append(new SPARCHotSpotCRuntimeCallPrologueOp(config.threadLastJavaSpOffset(), thread, stackPointer, threadTemp, spScratch));
            result = super.emitForeignCall(hotspotLinkage, debugInfo, args);
            append(new SPARCHotSpotCRuntimeCallEpilogueOp(config.threadLastJavaSpOffset(), config.threadLastJavaPcOffset(), config.threadJavaFrameAnchorFlagsOffset(), thread, threadTemp));
        } else {
            result = super.emitForeignCall(hotspotLinkage, debugInfo, args);
        }

        return result;
    }

    @Override
    public void emitReturn(Value input) {
        AllocatableValue operand = Value.ILLEGAL;
        if (input != null) {
            operand = resultOperandFor(input.getLIRKind());
            emitMove(operand, input);
        }
        append(new SPARCHotSpotReturnOp(operand, getStub() != null, runtime().getConfig(), getSafepointAddressValue()));
    }

    @Override
    public void emitTailcall(Value[] args, Value address) {
        throw JVMCIError.unimplemented();
    }

    @Override
    public void emitUnwind(Value exception) {
        ForeignCallLinkage linkage = getForeignCalls().lookupForeignCall(HotSpotBackend.UNWIND_EXCEPTION_TO_CALLER);
        CallingConvention linkageCc = linkage.getOutgoingCallingConvention();
        assert linkageCc.getArgumentCount() == 2;
        RegisterValue exceptionParameter = (RegisterValue) linkageCc.getArgument(0);
        emitMove(exceptionParameter, exception);
        append(new SPARCHotSpotUnwindOp(exceptionParameter));
    }

    private void moveDeoptValuesToThread(Value actionAndReason, Value speculation) {
        moveValueToThread(actionAndReason, config.pendingDeoptimizationOffset);
        moveValueToThread(speculation, config.pendingFailedSpeculationOffset);
    }

    private void moveValueToThread(Value v, int offset) {
        LIRKind wordKind = LIRKind.value(getProviders().getCodeCache().getTarget().wordKind);
        RegisterValue thread = getProviders().getRegisters().getThreadRegister().asValue(wordKind);
        SPARCAddressValue pendingDeoptAddress = new SPARCImmediateAddressValue(wordKind, thread, offset);
        append(new StoreOp(v.getPlatformKind(), pendingDeoptAddress, load(v), null));
    }

    @Override
    public void emitDeoptimize(Value actionAndReason, Value speculation, LIRFrameState state) {
        moveDeoptValuesToThread(actionAndReason, speculation);
        append(new SPARCDeoptimizeOp(state, target().wordKind));
    }

    @Override
    public void emitDeoptimizeCaller(DeoptimizationAction action, DeoptimizationReason reason) {
        Value actionAndReason = emitJavaConstant(getMetaAccess().encodeDeoptActionAndReason(action, reason, 0));
        Value nullValue = emitJavaConstant(JavaConstant.NULL_POINTER);
        moveDeoptValuesToThread(actionAndReason, nullValue);
        append(new SPARCHotSpotDeoptimizeCallerOp());
    }

    @Override
    public Variable emitLoad(LIRKind kind, Value address, LIRFrameState state) {
        SPARCAddressValue loadAddress = asAddressValue(address);
        Variable result = newVariable(toStackKind(kind));
        append(new LoadOp(kind.getPlatformKind(), result, loadAddress, state));
        return result;
    }

    @Override
    public boolean canInlineConstant(JavaConstant c) {
        if (HotSpotCompressedNullConstant.COMPRESSED_NULL.equals(c)) {
            return true;
        } else if (c instanceof HotSpotObjectConstant) {
            return false;
        } else {
            return super.canInlineConstant(c);
        }
    }

    @Override
    public void emitStore(LIRKind kind, Value address, Value inputVal, LIRFrameState state) {
        SPARCAddressValue storeAddress = asAddressValue(address);
        if (isJavaConstant(inputVal)) {
            JavaConstant c = asJavaConstant(inputVal);
            if (c.isDefaultForKind()) {
                append(new StoreConstantOp(kind.getPlatformKind(), storeAddress, c, state));
                return;
            }
        }
        Variable input = load(inputVal);
        append(new StoreOp(kind.getPlatformKind(), storeAddress, input, state));
    }

    public Variable emitCompareAndSwap(Value address, Value expectedValue, Value newValue, Value trueValue, Value falseValue) {
        LIRKind kind = newValue.getLIRKind();
        assert kind.equals(expectedValue.getLIRKind());
        JavaKind memKind = (JavaKind) kind.getPlatformKind();
        Variable result = newVariable(newValue.getLIRKind());
        append(new CompareAndSwapOp(result, asAllocatable(address), asAllocatable(expectedValue), asAllocatable(newValue)));
        return emitConditionalMove(memKind, expectedValue, result, Condition.EQ, true, trueValue, falseValue);
    }

    public void emitPrefetchAllocate(Value address) {
        SPARCAddressValue addr = asAddressValue(address);
        append(new SPARCPrefetchOp(addr, config.allocatePrefetchInstr));
    }

    public StackSlot getDeoptimizationRescueSlot() {
        return deoptimizationRescueSlot;
    }

    @Override
    protected LIRInstruction createMoveConstant(AllocatableValue dst, Constant src) {
        Constant usedSource;
        if (COMPRESSED_NULL.equals(src)) {
            usedSource = INT_0;
        } else {
            usedSource = src;
        }
        return super.createMoveConstant(dst, usedSource);
    }

    @Override
    public void emitCompareBranch(PlatformKind cmpKind, Value x, Value y, Condition cond, boolean unorderedIsTrue, LabelRef trueDestination, LabelRef falseDestination,
                    double trueDestinationProbability) {
        Value localX = x;
        Value localY = y;
        if (localX instanceof HotSpotObjectConstant) {
            localX = load(localX);
        }
        if (localY instanceof HotSpotObjectConstant) {
            localY = load(localY);
        }
        super.emitCompareBranch(cmpKind, localX, localY, cond, unorderedIsTrue, trueDestination, falseDestination, trueDestinationProbability);
    }

    @Override
    protected boolean emitCompare(PlatformKind cmpKind, Value a, Value b) {
        Value localA = a;
        Value localB = b;
        if (isConstantValue(a)) {
            Constant c = asConstant(a);
            if (HotSpotCompressedNullConstant.COMPRESSED_NULL.equals(c)) {
                localA = SPARC.g0.asValue(LIRKind.value(JavaKind.Int));
            } else if (c instanceof HotSpotObjectConstant) {
                localA = load(localA);
            }
        }
        if (isConstantValue(b)) {
            Constant c = asConstant(b);
            if (HotSpotCompressedNullConstant.COMPRESSED_NULL.equals(c)) {
                localB = SPARC.g0.asValue(LIRKind.value(JavaKind.Int));
            } else if (c instanceof HotSpotObjectConstant) {
                localB = load(localB);
            }
        }
        return super.emitCompare(cmpKind, localA, localB);
    }

    @Override
    public Value emitCompress(Value pointer, CompressEncoding encoding, boolean nonNull) {
        LIRKind inputKind = pointer.getLIRKind();
        assert inputKind.getPlatformKind() == JavaKind.Long || inputKind.getPlatformKind() == JavaKind.Object;
        if (inputKind.isReference(0)) {
            // oop
            Variable result = newVariable(LIRKind.reference(JavaKind.Int));
            append(new SPARCHotSpotMove.CompressPointer(result, asAllocatable(pointer), getProviders().getRegisters().getHeapBaseRegister().asValue(), encoding, nonNull));
            return result;
        } else {
            // metaspace pointer
            Variable result = newVariable(LIRKind.value(JavaKind.Int));
            AllocatableValue base = Value.ILLEGAL;
            if (encoding.base != 0) {
                base = emitLoadConstant(LIRKind.value(JavaKind.Long), JavaConstant.forLong(encoding.base));
            }
            append(new SPARCHotSpotMove.CompressPointer(result, asAllocatable(pointer), base, encoding, nonNull));
            return result;
        }
    }

    @Override
    public Value emitUncompress(Value pointer, CompressEncoding encoding, boolean nonNull) {
        LIRKind inputKind = pointer.getLIRKind();
        assert inputKind.getPlatformKind() == JavaKind.Int;
        if (inputKind.isReference(0)) {
            // oop
            Variable result = newVariable(LIRKind.reference(JavaKind.Long));
            append(new SPARCHotSpotMove.UncompressPointer(result, asAllocatable(pointer), getProviders().getRegisters().getHeapBaseRegister().asValue(), encoding, nonNull));
            return result;
        } else {
            // metaspace pointer
            Variable result = newVariable(LIRKind.value(JavaKind.Long));
            AllocatableValue base = Value.ILLEGAL;
            if (encoding.base != 0) {
                base = emitLoadConstant(LIRKind.value(JavaKind.Long), JavaConstant.forLong(encoding.base));
            }
            append(new SPARCHotSpotMove.UncompressPointer(result, asAllocatable(pointer), base, encoding, nonNull));
            return result;
        }
    }

    /**
     * @param savedRegisters the registers saved by this operation which may be subject to pruning
     * @param savedRegisterLocations the slots to which the registers are saved
     * @param supportsRemove determines if registers can be pruned
     */
    protected SPARCSaveRegistersOp emitSaveRegisters(Register[] savedRegisters, StackSlotValue[] savedRegisterLocations, boolean supportsRemove) {
        SPARCSaveRegistersOp save = new SPARCSaveRegistersOp(savedRegisters, savedRegisterLocations, supportsRemove);
        append(save);
        return save;
    }

    public SaveRegistersOp emitSaveAllRegisters() {
        // We save all registers that were not saved by the save instruction.
        // @formatter:off
        Register[] savedRegisters = {
                        // CPU
                        g1, g3, g4, g5,
                        // FPU, use only every second register as doubles are stored anyways
                        f0,  /*f1, */ f2,  /*f3, */ f4,  /*f5, */ f6,  /*f7, */
                        f8,  /*f9, */ f10, /*f11,*/ f12, /*f13,*/ f14, /*f15,*/
                        f16, /*f17,*/ f18, /*f19,*/ f20, /*f21,*/ f22, /*f23,*/
                        f24, /*f25,*/ f26, /*f27,*/ f28, /*f29,*/ f30, /*f31 */
                        d32,          d34,          d36,          d38,
                        d40,          d42,          d44,          d46,
                        d48,          d50,          d52,          d54,
                        d56,          d58,          d60,          d62
        };
        // @formatter:on
        StackSlotValue[] savedRegisterLocations = new StackSlotValue[savedRegisters.length];
        for (int i = 0; i < savedRegisters.length; i++) {
            PlatformKind kind = target().arch.getLargestStorableKind(savedRegisters[i].getRegisterCategory());
            assert kind != JavaKind.Illegal;
            VirtualStackSlot spillSlot = getResult().getFrameMapBuilder().allocateSpillSlot(LIRKind.value(kind));
            savedRegisterLocations[i] = spillSlot;
        }
        return emitSaveRegisters(savedRegisters, savedRegisterLocations, false);
    }

    public void emitLeaveCurrentStackFrame(SaveRegistersOp saveRegisterOp) {
        append(new SPARCHotSpotLeaveCurrentStackFrameOp());
    }

    public void emitLeaveDeoptimizedStackFrame(Value frameSize, Value initialInfo) {
        append(new SPARCHotSpotLeaveDeoptimizedStackFrameOp());
    }

    public void emitEnterUnpackFramesStackFrame(Value framePc, Value senderSp, Value senderFp, SaveRegistersOp saveRegisterOp) {
        Register thread = getProviders().getRegisters().getThreadRegister();
        Variable framePcVariable = load(framePc);
        Variable senderSpVariable = load(senderSp);
        Variable scratchVariable = newVariable(LIRKind.value(getHostWordKind()));
        append(new SPARCHotSpotEnterUnpackFramesStackFrameOp(thread, config.threadLastJavaSpOffset(), config.threadLastJavaPcOffset(), framePcVariable, senderSpVariable, scratchVariable,
                        target().wordKind));
    }

    public void emitLeaveUnpackFramesStackFrame(SaveRegistersOp saveRegisterOp) {
        Register thread = getProviders().getRegisters().getThreadRegister();
        append(new SPARCHotSpotLeaveUnpackFramesStackFrameOp(thread, config.threadLastJavaSpOffset(), config.threadLastJavaPcOffset(), config.threadJavaFrameAnchorFlagsOffset()));
    }

    public void emitPushInterpreterFrame(Value frameSize, Value framePc, Value senderSp, Value initialInfo) {
        Variable frameSizeVariable = load(frameSize);
        Variable framePcVariable = load(framePc);
        Variable senderSpVariable = load(senderSp);
        Variable initialInfoVariable = load(initialInfo);
        append(new SPARCHotSpotPushInterpreterFrameOp(frameSizeVariable, framePcVariable, senderSpVariable, initialInfoVariable));
    }

    public Value emitUncommonTrapCall(Value trapRequest, SaveRegistersOp saveRegisterOp) {
        ForeignCallLinkage linkage = getForeignCalls().lookupForeignCall(UNCOMMON_TRAP);

        Register threadRegister = getProviders().getRegisters().getThreadRegister();
        Value threadTemp = newVariable(LIRKind.value(target().wordKind));
        Register stackPointerRegister = getProviders().getRegisters().getStackPointerRegister();
        Variable spScratch = newVariable(LIRKind.value(target().wordKind));
        append(new SPARCHotSpotCRuntimeCallPrologueOp(config.threadLastJavaSpOffset(), threadRegister, stackPointerRegister, threadTemp, spScratch));
        Variable result = super.emitForeignCall(linkage, null, threadRegister.asValue(LIRKind.value(target().wordKind)), trapRequest);
        append(new SPARCHotSpotCRuntimeCallEpilogueOp(config.threadLastJavaSpOffset(), config.threadLastJavaPcOffset(), config.threadJavaFrameAnchorFlagsOffset(), threadRegister, threadTemp));

        Map<LIRFrameState, SaveRegistersOp> calleeSaveInfo = ((SPARCHotSpotLIRGenerationResult) getResult()).getCalleeSaveInfo();
        assert currentRuntimeCallInfo != null;
        assert !calleeSaveInfo.containsKey(currentRuntimeCallInfo);
        calleeSaveInfo.put(currentRuntimeCallInfo, saveRegisterOp);

        return result;
    }

    @Override
    public Value emitDeoptimizationFetchUnrollInfoCall(SaveRegistersOp saveRegisterOp) {
        ForeignCallLinkage linkage = getForeignCalls().lookupForeignCall(FETCH_UNROLL_INFO);

        Register threadRegister = getProviders().getRegisters().getThreadRegister();
        Value threadTemp = newVariable(LIRKind.value(target().wordKind));
        Register stackPointerRegister = getProviders().getRegisters().getStackPointerRegister();
        Variable spScratch = newVariable(LIRKind.value(target().wordKind));
        append(new SPARCHotSpotCRuntimeCallPrologueOp(config.threadLastJavaSpOffset(), threadRegister, stackPointerRegister, threadTemp, spScratch));
        Variable result = super.emitForeignCall(linkage, null, threadRegister.asValue(LIRKind.value(target().wordKind)));
        append(new SPARCHotSpotCRuntimeCallEpilogueOp(config.threadLastJavaSpOffset(), config.threadLastJavaPcOffset(), config.threadJavaFrameAnchorFlagsOffset(), threadRegister, threadTemp));

        Map<LIRFrameState, SaveRegistersOp> calleeSaveInfo = ((SPARCHotSpotLIRGenerationResult) getResult()).getCalleeSaveInfo();
        assert currentRuntimeCallInfo != null;
        assert !calleeSaveInfo.containsKey(currentRuntimeCallInfo);
        calleeSaveInfo.put(currentRuntimeCallInfo, saveRegisterOp);

        return result;
    }

    @Override
    public void emitNullCheck(Value address, LIRFrameState state) {
        PlatformKind kind = address.getPlatformKind();
        if (kind == JavaKind.Int) {
            CompressEncoding encoding = config.getOopEncoding();
            Value uncompressed = emitUncompress(address, encoding, false);
            append(new NullCheckOp(asAddressValue(uncompressed), state));
        } else {
            super.emitNullCheck(address, state);
        }
    }

    @Override
    public LIRInstruction createBenchmarkCounter(String name, String group, Value increment) {
        if (BenchmarkCounters.enabled) {
            return new SPARCHotSpotCounterOp(name, group, increment, getProviders().getRegisters(), config);
        } else {
            return null;
        }
    }

    @Override
    public LIRInstruction createMultiBenchmarkCounter(String[] names, String[] groups, Value[] increments) {
        if (BenchmarkCounters.enabled) {
            return new SPARCHotSpotCounterOp(names, groups, increments, getProviders().getRegisters(), config);
        } else {
            return null;
        }
    }

    public AllocatableValue getSafepointAddressValue() {
        if (this.safepointAddressValue == null) {
            this.safepointAddressValue = newVariable(LIRKind.value(target().wordKind));
        }
        return this.safepointAddressValue;
    }

    @Override
    protected StrategySwitchOp createStrategySwitchOp(AllocatableValue base, SwitchStrategy strategy, LabelRef[] keyTargets, LabelRef defaultTarget, Variable key, AllocatableValue scratchValue) {
        return new SPARCHotSpotStrategySwitchOp(base, strategy, keyTargets, defaultTarget, key, scratchValue);
    }
}
