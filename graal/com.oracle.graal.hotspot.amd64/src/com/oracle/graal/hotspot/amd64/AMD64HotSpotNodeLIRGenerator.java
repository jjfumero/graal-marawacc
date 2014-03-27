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
import static com.oracle.graal.api.code.ValueUtil.*;
import static com.oracle.graal.hotspot.HotSpotBackend.*;

import java.lang.reflect.*;

import com.oracle.graal.amd64.*;
import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.asm.amd64.AMD64Address.Scale;
import com.oracle.graal.compiler.amd64.*;
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.amd64.AMD64HotSpotLIRGenerator.SaveRbp;
import com.oracle.graal.hotspot.amd64.AMD64HotSpotMove.CompareAndSwapCompressedOp;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.StandardOp.NoOp;
import com.oracle.graal.lir.amd64.*;
import com.oracle.graal.lir.amd64.AMD64ControlFlow.CondMoveOp;
import com.oracle.graal.lir.amd64.AMD64Move.CompareAndSwapOp;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.java.MethodCallTargetNode.InvokeKind;

/**
 * LIR generator specialized for AMD64 HotSpot.
 */
public class AMD64HotSpotNodeLIRGenerator extends AMD64NodeLIRGenerator implements HotSpotNodeLIRGenerator {

    public AMD64HotSpotNodeLIRGenerator(StructuredGraph graph, LIRGenerationResult res, LIRGenerator gen) {
        super(graph, res, gen);
        memoryPeephole = new AMD64HotSpotMemoryPeephole(this);
    }

    private AMD64HotSpotLIRGenerator getGen() {
        return (AMD64HotSpotLIRGenerator) gen;
    }

    private SaveRbp getSaveRbp() {
        return getGen().saveRbp;
    }

    private void setSaveRbp(SaveRbp saveRbp) {
        getGen().saveRbp = saveRbp;
    }

    @Override
    protected DebugInfoBuilder createDebugInfoBuilder(NodeMap<Value> nodeOperands) {
        HotSpotLockStack lockStack = new HotSpotLockStack(res.getFrameMap(), Kind.Long);
        return new HotSpotDebugInfoBuilder(nodeOperands, lockStack);
    }

    @Override
    protected void emitPrologue(StructuredGraph graph) {

        CallingConvention incomingArguments = gen.getCallingConvention();

        Value[] params = new Value[incomingArguments.getArgumentCount() + 1];
        for (int i = 0; i < params.length - 1; i++) {
            params[i] = toStackKind(incomingArguments.getArgument(i));
            if (isStackSlot(params[i])) {
                StackSlot slot = ValueUtil.asStackSlot(params[i]);
                if (slot.isInCallerFrame() && !res.getLIR().hasArgInCallerFrame()) {
                    res.getLIR().setHasArgInCallerFrame();
                }
            }
        }
        params[params.length - 1] = rbp.asValue(Kind.Long);

        emitIncomingValues(params);

        setSaveRbp(((AMD64HotSpotLIRGenerator) gen).new SaveRbp(new NoOp(gen.getCurrentBlock(), res.getLIR().getLIRforBlock(gen.getCurrentBlock()).size())));
        append(getSaveRbp().placeholder);

        for (ParameterNode param : graph.getNodes(ParameterNode.class)) {
            Value paramValue = params[param.index()];
            assert paramValue.getKind() == param.getKind().getStackKind();
            setResult(param, gen.emitMove(paramValue));
        }
    }

    @Override
    public void visitSafepointNode(SafepointNode i) {
        LIRFrameState info = gen.state(i);
        append(new AMD64HotSpotSafepointOp(info, getGen().config, this));
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
        if (callTarget instanceof HotSpotIndirectCallTargetNode) {
            AllocatableValue metaspaceMethod = AMD64.rbx.asValue();
            gen.emitMove(metaspaceMethod, operand(((HotSpotIndirectCallTargetNode) callTarget).metaspaceMethod()));
            AllocatableValue targetAddress = AMD64.rax.asValue();
            gen.emitMove(targetAddress, operand(callTarget.computedAddress()));
            append(new AMD64IndirectCallOp(callTarget.target(), result, parameters, temps, metaspaceMethod, targetAddress, callState));
        } else {
            super.emitIndirectCall(callTarget, result, parameters, temps, callState);
        }
    }

    @Override
    public void emitPatchReturnAddress(ValueNode address) {
        append(new AMD64HotSpotPatchReturnAddressOp(gen.load(operand(address))));
    }

    @Override
    public void emitJumpToExceptionHandlerInCaller(ValueNode handlerInCallerPc, ValueNode exception, ValueNode exceptionPc) {
        Variable handler = gen.load(operand(handlerInCallerPc));
        ForeignCallLinkage linkage = gen.getForeignCalls().lookupForeignCall(EXCEPTION_HANDLER_IN_CALLER);
        CallingConvention outgoingCc = linkage.getOutgoingCallingConvention();
        assert outgoingCc.getArgumentCount() == 2;
        RegisterValue exceptionFixed = (RegisterValue) outgoingCc.getArgument(0);
        RegisterValue exceptionPcFixed = (RegisterValue) outgoingCc.getArgument(1);
        gen.emitMove(exceptionFixed, operand(exception));
        gen.emitMove(exceptionPcFixed, operand(exceptionPc));
        Register thread = getGen().getProviders().getRegisters().getThreadRegister();
        AMD64HotSpotJumpToExceptionHandlerInCallerOp op = new AMD64HotSpotJumpToExceptionHandlerInCallerOp(handler, exceptionFixed, exceptionPcFixed, getGen().config.threadIsMethodHandleReturnOffset,
                        thread);
        append(op);
    }

    @Override
    public void visitInfopointNode(InfopointNode i) {
        if (i.getState() != null && i.getState().bci == FrameState.AFTER_BCI) {
            Debug.log("Ignoring InfopointNode for AFTER_BCI");
        } else {
            super.visitInfopointNode(i);
        }
    }

    public void emitPrefetchAllocate(ValueNode address, ValueNode distance) {
        AMD64AddressValue addr = getGen().emitAddress(operand(address), 0, gen.loadNonConst(operand(distance)), 1);
        append(new AMD64PrefetchOp(addr, getGen().config.allocatePrefetchInstr));
    }

    @Override
    public void visitDirectCompareAndSwap(DirectCompareAndSwapNode x) {
        Kind kind = x.newValue().getKind();
        assert kind == x.expectedValue().getKind();

        Value expected = gen.loadNonConst(operand(x.expectedValue()));
        Variable newVal = gen.load(operand(x.newValue()));

        int disp = 0;
        AMD64AddressValue address;
        Value index = operand(x.offset());
        if (ValueUtil.isConstant(index) && NumUtil.isInt(ValueUtil.asConstant(index).asLong() + disp)) {
            assert !gen.getCodeCache().needsDataPatch(asConstant(index));
            disp += (int) ValueUtil.asConstant(index).asLong();
            address = new AMD64AddressValue(kind, gen.load(operand(x.object())), disp);
        } else {
            address = new AMD64AddressValue(kind, gen.load(operand(x.object())), gen.load(index), Scale.Times1, disp);
        }

        RegisterValue rax_local = AMD64.rax.asValue(kind);
        gen.emitMove(rax_local, expected);
        append(new CompareAndSwapOp(rax_local, address, rax_local, newVal));

        Variable result = newVariable(x.getKind());
        gen.emitMove(result, rax_local);
        setResult(x, result);
    }

    @Override
    public void visitCompareAndSwap(LoweredCompareAndSwapNode node, Value address) {
        Kind kind = node.getNewValue().getKind();
        assert kind == node.getExpectedValue().getKind();
        Value expected = gen.loadNonConst(operand(node.getExpectedValue()));
        Variable newValue = gen.load(operand(node.getNewValue()));
        AMD64AddressValue addressValue = getGen().asAddressValue(address);
        RegisterValue raxRes = AMD64.rax.asValue(kind);
        gen.emitMove(raxRes, expected);
        if (getGen().config.useCompressedOops && node.isCompressible()) {
            Variable scratch = newVariable(Kind.Long);
            Register heapBaseReg = getGen().getProviders().getRegisters().getHeapBaseRegister();
            append(new CompareAndSwapCompressedOp(raxRes, addressValue, raxRes, newValue, scratch, getGen().config.getOopEncoding(), heapBaseReg));
        } else {
            append(new CompareAndSwapOp(raxRes, addressValue, raxRes, newValue));
        }
        Variable result = newVariable(node.getKind());
        append(new CondMoveOp(result, Condition.EQ, gen.load(Constant.TRUE), Constant.FALSE));
        setResult(node, result);
    }

}
