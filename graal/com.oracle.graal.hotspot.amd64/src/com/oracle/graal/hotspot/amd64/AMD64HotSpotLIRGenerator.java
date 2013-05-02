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
package com.oracle.graal.hotspot.amd64;

import static com.oracle.graal.amd64.AMD64.*;
import static com.oracle.graal.api.code.CallingConvention.Type.*;
import static com.oracle.graal.api.code.ValueUtil.*;
import static com.oracle.graal.hotspot.amd64.AMD64HotSpotUnwindOp.*;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.amd64.*;
import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.asm.amd64.AMD64Address.Scale;
import com.oracle.graal.compiler.amd64.*;
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.hotspot.stubs.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.StandardOp.ParametersOp;
import com.oracle.graal.lir.StandardOp.PlaceholderOp;
import com.oracle.graal.lir.amd64.*;
import com.oracle.graal.lir.amd64.AMD64Move.CompareAndSwapOp;
import com.oracle.graal.lir.amd64.AMD64Move.MoveFromRegOp;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.MethodCallTargetNode.InvokeKind;

/**
 * LIR generator specialized for AMD64 HotSpot.
 */
public class AMD64HotSpotLIRGenerator extends AMD64LIRGenerator implements HotSpotLIRGenerator {

    private HotSpotRuntime runtime() {
        return (HotSpotRuntime) runtime;
    }

    protected AMD64HotSpotLIRGenerator(StructuredGraph graph, CodeCacheProvider runtime, TargetDescription target, FrameMap frameMap, ResolvedJavaMethod method, LIR lir) {
        super(graph, runtime, target, frameMap, method, lir);
    }

    /**
     * The slot reserved for storing the original return address when a frame is marked for
     * deoptimization. The return address slot in the callee is overwritten with the address of a
     * deoptimization stub.
     */
    StackSlot deoptimizationRescueSlot;

    /**
     * Utility for emitting the instruction to save RBP.
     */
    class SaveRbp {

        final PlaceholderOp placeholder;

        /**
         * The slot reserved for saving RBP.
         */
        final StackSlot reservedSlot;

        public SaveRbp(PlaceholderOp placeholder) {
            this.placeholder = placeholder;
            this.reservedSlot = frameMap.allocateSpillSlot(Kind.Long);
            assert reservedSlot.getRawOffset() == -16 : reservedSlot.getRawOffset();
        }

        /**
         * Replaces this operation with the appropriate move for saving rbp.
         * 
         * @param useStack specifies if rbp must be saved to the stack
         */
        public AllocatableValue finalize(boolean useStack) {
            AllocatableValue dst;
            if (useStack) {
                dst = reservedSlot;
            } else {
                frameMap.freeSpillSlot(reservedSlot);
                dst = newVariable(Kind.Long);
            }

            placeholder.replace(lir, new MoveFromRegOp(dst, rbp.asValue(Kind.Long)));
            return dst;
        }
    }

    private SaveRbp saveRbp;

    /**
     * List of epilogue operations that need to restore RBP.
     */
    List<AMD64HotSpotEpilogueOp> epilogueOps = new ArrayList<>(2);

    @SuppressWarnings("hiding")
    @Override
    protected DebugInfoBuilder createDebugInfoBuilder(NodeMap<Value> nodeOperands) {
        assert runtime().config.basicLockSize == 8;
        HotSpotLockStack lockStack = new HotSpotLockStack(frameMap, Kind.Long);
        return new HotSpotDebugInfoBuilder(nodeOperands, lockStack);
    }

    @Override
    public StackSlot getLockSlot(int lockDepth) {
        return ((HotSpotDebugInfoBuilder) debugInfoBuilder).lockStack().makeLockSlot(lockDepth);
    }

    @Override
    protected void emitPrologue() {

        CallingConvention incomingArguments = createCallingConvention();

        RegisterValue rbpParam = rbp.asValue(Kind.Long);
        Value[] params = new Value[incomingArguments.getArgumentCount() + 1];
        for (int i = 0; i < params.length - 1; i++) {
            params[i] = toStackKind(incomingArguments.getArgument(i));
            if (isStackSlot(params[i])) {
                StackSlot slot = ValueUtil.asStackSlot(params[i]);
                if (slot.isInCallerFrame() && !lir.hasArgInCallerFrame()) {
                    lir.setHasArgInCallerFrame();
                }
            }
        }
        params[params.length - 1] = rbpParam;
        ParametersOp paramsOp = new ParametersOp(params);

        append(paramsOp);

        saveRbp = new SaveRbp(new PlaceholderOp(currentBlock, lir.lir(currentBlock).size()));
        append(saveRbp.placeholder);

        for (LocalNode local : graph.getNodes(LocalNode.class)) {
            Value param = params[local.index()];
            assert param.getKind() == local.kind().getStackKind();
            setResult(local, emitMove(param));
        }
    }

    @Override
    protected void emitReturn(Value input) {
        AMD64HotSpotReturnOp op = new AMD64HotSpotReturnOp(input);
        epilogueOps.add(op);
        append(op);
    }

    @Override
    protected boolean needOnlyOopMaps() {
        // Stubs only need oop maps
        return runtime().asStub(method) != null;
    }

    /**
     * Map from debug infos that need to be updated with callee save information to the operations
     * that provide the information.
     */
    Map<LIRFrameState, AMD64RegistersPreservationOp> calleeSaveInfo = new HashMap<>();

    private LIRFrameState currentRuntimeCallInfo;

    @Override
    protected void emitCall(RuntimeCallTarget callTarget, Value result, Value[] arguments, Value[] temps, LIRFrameState info) {
        currentRuntimeCallInfo = info;
        super.emitCall(callTarget, result, arguments, temps, info);
    }

    protected AMD64SaveRegistersOp emitSaveRegisters(Register[] savedRegisters, StackSlot[] savedRegisterLocations) {
        AMD64SaveRegistersOp save = new AMD64SaveRegistersOp(savedRegisters, savedRegisterLocations);
        append(save);
        return save;
    }

    protected void emitRestoreRegisters(AMD64SaveRegistersOp save) {
        append(new AMD64RestoreRegistersOp(save.getSlots().clone(), save));
    }

    @Override
    public Variable emitCall(RuntimeCallTarget callTarget, CallingConvention cc, DeoptimizingNode info, Value... args) {
        Stub stub = runtime().asStub(method);
        boolean isCRuntimeCall = ((HotSpotRuntimeCallTarget) callTarget).isCRuntimeCall();
        assert !isCRuntimeCall || stub != null : "direct call to C runtime can only be made from compiled stubs, not from " + method;

        AMD64SaveRegistersOp save = null;
        StackSlot[] savedRegisterLocations = null;
        if (isCRuntimeCall) {
            if (stub.preservesRegisters()) {
                Register[] savedRegisters = frameMap.registerConfig.getAllocatableRegisters();
                savedRegisterLocations = new StackSlot[savedRegisters.length];
                for (int i = 0; i < savedRegisters.length; i++) {
                    PlatformKind kind = target.arch.getLargestStorableKind(savedRegisters[i].getRegisterCategory());
                    assert kind != Kind.Illegal;
                    StackSlot spillSlot = frameMap.allocateSpillSlot(kind);
                    savedRegisterLocations[i] = spillSlot;
                }
                save = emitSaveRegisters(savedRegisters, savedRegisterLocations);
            }
            append(new AMD64HotSpotCRuntimeCallPrologueOp());
        }

        Variable result = super.emitCall(callTarget, cc, info, args);

        if (isCRuntimeCall) {
            append(new AMD64HotSpotCRuntimeCallEpilogueOp());
            if (stub.preservesRegisters()) {
                assert !calleeSaveInfo.containsKey(currentRuntimeCallInfo);
                calleeSaveInfo.put(currentRuntimeCallInfo, save);

                emitRestoreRegisters(save);
            } else {
                assert zapRegisters();
            }
        }

        return result;
    }

    protected AMD64ZapRegistersOp emitZapRegisters(Register[] zappedRegisters, Constant[] zapValues) {
        AMD64ZapRegistersOp zap = new AMD64ZapRegistersOp(zappedRegisters, zapValues);
        append(zap);
        return zap;
    }

    protected boolean zapRegisters() {
        Register[] zappedRegisters = frameMap.registerConfig.getAllocatableRegisters();
        Constant[] zapValues = new Constant[zappedRegisters.length];
        for (int i = 0; i < zappedRegisters.length; i++) {
            PlatformKind kind = target.arch.getLargestStorableKind(zappedRegisters[i].getRegisterCategory());
            assert kind != Kind.Illegal;
            zapValues[i] = zapValueForKind(kind);
        }
        calleeSaveInfo.put(currentRuntimeCallInfo, emitZapRegisters(zappedRegisters, zapValues));
        return true;
    }

    @Override
    protected CallingConvention createCallingConvention() {
        Stub stub = runtime().asStub(method);
        if (stub != null) {
            return stub.getLinkage().getCallingConvention();
        }

        if (graph.getEntryBCI() == StructuredGraph.INVOCATION_ENTRY_BCI) {
            return super.createCallingConvention();
        } else {
            return frameMap.registerConfig.getCallingConvention(JavaCallee, method.getSignature().getReturnType(null), new JavaType[]{runtime.lookupJavaType(long.class)}, target, false);
        }
    }

    @Override
    public void visitSafepointNode(SafepointNode i) {
        LIRFrameState info = state(i);
        append(new AMD64SafepointOp(info, runtime().config, this));
    }

    @SuppressWarnings("hiding")
    @Override
    public void visitDirectCompareAndSwap(DirectCompareAndSwapNode x) {
        Kind kind = x.newValue().kind();
        assert kind == x.expectedValue().kind();

        Value expected = loadNonConst(operand(x.expectedValue()));
        Variable newVal = load(operand(x.newValue()));

        int disp = 0;
        AMD64AddressValue address;
        Value index = operand(x.offset());
        if (ValueUtil.isConstant(index) && NumUtil.isInt(ValueUtil.asConstant(index).asLong() + disp)) {
            assert !runtime.needsDataPatch(asConstant(index));
            disp += (int) ValueUtil.asConstant(index).asLong();
            address = new AMD64AddressValue(kind, load(operand(x.object())), disp);
        } else {
            address = new AMD64AddressValue(kind, load(operand(x.object())), load(index), Scale.Times1, disp);
        }

        RegisterValue rax = AMD64.rax.asValue(kind);
        emitMove(rax, expected);
        append(new CompareAndSwapOp(rax, address, rax, newVal));

        Variable result = newVariable(x.kind());
        emitMove(result, rax);
        setResult(x, result);
    }

    @Override
    public void emitTailcall(Value[] args, Value address) {
        append(new AMD64TailcallOp(args, address));
    }

    @Override
    protected void emitDirectCall(DirectCallTargetNode callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState callState) {
        InvokeKind invokeKind = ((HotSpotDirectCallTargetNode) callTarget).invokeKind();
        if (invokeKind == InvokeKind.Interface || invokeKind == InvokeKind.Virtual) {
            append(new AMD64HotspotDirectVirtualCallOp(callTarget.target(), result, parameters, temps, callState, invokeKind));
        } else {
            assert invokeKind == InvokeKind.Static || invokeKind == InvokeKind.Special;
            HotSpotResolvedJavaMethod resolvedMethod = (HotSpotResolvedJavaMethod) callTarget.target();
            assert !Modifier.isAbstract(resolvedMethod.getModifiers()) : "Cannot make direct call to abstract method.";
            Constant metaspaceMethod = resolvedMethod.getMetaspaceMethodConstant();
            append(new AMD64HotspotDirectStaticCallOp(callTarget.target(), result, parameters, temps, callState, invokeKind, metaspaceMethod));
        }
    }

    @Override
    protected void emitIndirectCall(IndirectCallTargetNode callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState callState) {
        AllocatableValue metaspaceMethod = AMD64.rbx.asValue();
        emitMove(metaspaceMethod, operand(((HotSpotIndirectCallTargetNode) callTarget).metaspaceMethod()));
        AllocatableValue targetAddress = AMD64.rax.asValue();
        emitMove(targetAddress, operand(callTarget.computedAddress()));
        append(new AMD64IndirectCallOp(callTarget.target(), result, parameters, temps, metaspaceMethod, targetAddress, callState));
    }

    @Override
    public void emitUnwind(Value exception) {
        RegisterValue exceptionParameter = EXCEPTION.asValue();
        emitMove(exceptionParameter, exception);
        AMD64HotSpotUnwindOp op = new AMD64HotSpotUnwindOp(exceptionParameter);
        epilogueOps.add(op);
        append(op);
    }

    @Override
    public void emitDeoptimize(DeoptimizationAction action, DeoptimizingNode deopting) {
        append(new AMD64DeoptimizeOp(action, deopting.getDeoptimizationReason(), state(deopting)));
    }

    @Override
    public void emitDeoptimizeCaller(DeoptimizationAction action, DeoptimizationReason reason) {
        AMD64HotSpotDeoptimizeCallerOp op = new AMD64HotSpotDeoptimizeCallerOp(action, reason);
        epilogueOps.add(op);
        append(op);
    }

    @Override
    public void emitPatchReturnAddress(ValueNode address) {
        load(operand(address));
        AMD64HotSpotPatchReturnAddressOp op = new AMD64HotSpotPatchReturnAddressOp(load(operand(address)));
        append(op);
    }

    @Override
    public void beforeRegisterAllocation() {
        boolean hasDebugInfo = lir.hasDebugInfo();
        AllocatableValue savedRbp = saveRbp.finalize(hasDebugInfo);
        if (hasDebugInfo) {
            deoptimizationRescueSlot = frameMap.allocateSpillSlot(Kind.Long);
        }

        for (AMD64HotSpotEpilogueOp op : epilogueOps) {
            op.savedRbp = savedRbp;
        }
    }
}
