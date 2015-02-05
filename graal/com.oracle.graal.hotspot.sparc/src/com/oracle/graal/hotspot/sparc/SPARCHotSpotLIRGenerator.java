/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.api.code.ValueUtil.*;
import static com.oracle.graal.hotspot.HotSpotBackend.*;
import static com.oracle.graal.hotspot.HotSpotGraalRuntime.*;
import static com.oracle.graal.sparc.SPARC.*;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.compiler.common.calc.*;
import com.oracle.graal.compiler.sparc.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.HotSpotVMConfig.CompressEncoding;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.stubs.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.StandardOp.SaveRegistersOp;
import com.oracle.graal.lir.gen.*;
import com.oracle.graal.lir.sparc.*;
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
        super(new DefaultLIRKindTool(providers.getCodeCache().getTarget().wordKind), providers, cc, lirGenRes);
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
        // TODO (je) check if this can be removed
        LIRFrameState deoptInfo = null;
        if (hotspotLinkage.canDeoptimize()) {
            deoptInfo = state;
            assert deoptInfo != null || getStub() != null;
        }

        if (hotspotLinkage.needsJavaFrameAnchor()) {
            HotSpotRegistersProvider registers = getProviders().getRegisters();
            Register thread = registers.getThreadRegister();
            Value threadTemp = newVariable(LIRKind.value(Kind.Long));
            Register stackPointer = registers.getStackPointerRegister();
            append(new SPARCHotSpotCRuntimeCallPrologueOp(config.threadLastJavaSpOffset(), thread, stackPointer, threadTemp));
            result = super.emitForeignCall(hotspotLinkage, deoptInfo, args);
            append(new SPARCHotSpotCRuntimeCallEpilogueOp(config.threadLastJavaSpOffset(), config.threadLastJavaPcOffset(), config.threadJavaFrameAnchorFlagsOffset(), thread, threadTemp));
        } else {
            result = super.emitForeignCall(hotspotLinkage, deoptInfo, args);
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
        append(new SPARCHotSpotReturnOp(operand, getStub() != null, runtime().getConfig()));
    }

    @Override
    public void emitTailcall(Value[] args, Value address) {
        // append(new AMD64TailcallOp(args, address));
        throw GraalInternalError.unimplemented();
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
        SPARCAddressValue pendingDeoptAddress = new SPARCAddressValue(wordKind, thread, offset);
        append(new StoreOp(v.getKind(), pendingDeoptAddress, load(v), null));
    }

    @Override
    public void emitDeoptimize(Value actionAndReason, Value speculation, LIRFrameState state) {
        moveDeoptValuesToThread(actionAndReason, speculation);
        append(new SPARCDeoptimizeOp(state));
    }

    @Override
    public void emitDeoptimizeCaller(DeoptimizationAction action, DeoptimizationReason reason) {
        moveDeoptValuesToThread(getMetaAccess().encodeDeoptActionAndReason(action, reason, 0), JavaConstant.NULL_POINTER);
        append(new SPARCHotSpotDeoptimizeCallerOp());
    }

    @Override
    public Variable emitLoad(LIRKind kind, Value address, LIRFrameState state) {
        SPARCAddressValue loadAddress = asAddressValue(address);
        Variable result = newVariable(kind);
        append(new LoadOp((Kind) kind.getPlatformKind(), result, loadAddress, state));
        return result;
    }

    private static boolean canStoreConstant(JavaConstant c) {
        // SPARC can only store integer null constants (via g0)
        switch (c.getKind()) {
            case Float:
            case Double:
                return false;
            default:
                return c.isDefaultForKind();
        }
    }

    @Override
    public void emitStore(LIRKind kind, Value address, Value inputVal, LIRFrameState state) {
        SPARCAddressValue storeAddress = asAddressValue(address);
        if (isConstant(inputVal)) {
            JavaConstant c = asConstant(inputVal);
            if (canStoreConstant(c)) {
                append(new StoreConstantOp((Kind) kind.getPlatformKind(), storeAddress, c, state));
                return;
            }
        }
        Variable input = load(inputVal);
        append(new StoreOp((Kind) kind.getPlatformKind(), storeAddress, input, state));
    }

    public Variable emitCompareAndSwap(Value address, Value expectedValue, Value newValue, Value trueValue, Value falseValue) {
        Variable newValueTemp = newVariable(newValue.getLIRKind());
        emitMove(newValueTemp, newValue);

        LIRKind kind = newValue.getLIRKind();
        assert kind.equals(expectedValue.getLIRKind());
        Kind memKind = (Kind) kind.getPlatformKind();
        SPARCAddressValue addressValue = asAddressValue(address);
        append(new CompareAndSwapOp(asAllocatable(addressValue), asAllocatable(expectedValue), asAllocatable(newValueTemp)));
        return emitConditionalMove(memKind, expectedValue, newValueTemp, Condition.EQ, true, trueValue, falseValue);
    }

    public StackSlot getDeoptimizationRescueSlot() {
        return deoptimizationRescueSlot;
    }

    @Override
    public Value emitCompress(Value pointer, CompressEncoding encoding, boolean nonNull) {
        // TODO
        throw GraalInternalError.unimplemented();
    }

    @Override
    public Value emitUncompress(Value pointer, CompressEncoding encoding, boolean nonNull) {
        // TODO
        throw GraalInternalError.unimplemented();
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
            assert kind != Kind.Illegal;
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
        append(new SPARCHotSpotEnterUnpackFramesStackFrameOp(thread, config.threadLastJavaSpOffset(), config.threadLastJavaPcOffset(), framePcVariable, senderSpVariable, scratchVariable));
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
        Value threadTemp = newVariable(LIRKind.value(Kind.Long));
        Register stackPointerRegister = getProviders().getRegisters().getStackPointerRegister();
        append(new SPARCHotSpotCRuntimeCallPrologueOp(config.threadLastJavaSpOffset(), threadRegister, stackPointerRegister, threadTemp));
        Variable result = super.emitForeignCall(linkage, null, threadRegister.asValue(LIRKind.value(Kind.Long)), trapRequest);
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
        Value threadTemp = newVariable(LIRKind.value(Kind.Long));
        Register stackPointerRegister = getProviders().getRegisters().getStackPointerRegister();
        append(new SPARCHotSpotCRuntimeCallPrologueOp(config.threadLastJavaSpOffset(), threadRegister, stackPointerRegister, threadTemp));
        Variable result = super.emitForeignCall(linkage, null, threadRegister.asValue(LIRKind.value(Kind.Long)));
        append(new SPARCHotSpotCRuntimeCallEpilogueOp(config.threadLastJavaSpOffset(), config.threadLastJavaPcOffset(), config.threadJavaFrameAnchorFlagsOffset(), threadRegister, threadTemp));

        Map<LIRFrameState, SaveRegistersOp> calleeSaveInfo = ((SPARCHotSpotLIRGenerationResult) getResult()).getCalleeSaveInfo();
        assert currentRuntimeCallInfo != null;
        assert !calleeSaveInfo.containsKey(currentRuntimeCallInfo);
        calleeSaveInfo.put(currentRuntimeCallInfo, saveRegisterOp);

        return result;
    }

    public void emitNullCheck(Value address, LIRFrameState state) {
        PlatformKind kind = address.getLIRKind().getPlatformKind();
        assert kind == Kind.Object || kind == Kind.Long : address + " - " + kind + " not an object!";
        append(new NullCheckOp(load(address), state));
    }
}
