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
import static com.oracle.graal.sparc.SPARC.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.compiler.sparc.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.gen.*;
import com.oracle.graal.lir.sparc.*;
import com.oracle.graal.lir.sparc.SPARCMove.CompareAndSwapOp;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.CallTargetNode.InvokeKind;

public class SPARCHotSpotNodeLIRBuilder extends SPARCNodeLIRBuilder implements HotSpotNodeLIRBuilder {

    public SPARCHotSpotNodeLIRBuilder(StructuredGraph graph, LIRGeneratorTool lirGen) {
        super(graph, lirGen);
        assert gen instanceof SPARCHotSpotLIRGenerator;
        assert getDebugInfoBuilder() instanceof HotSpotDebugInfoBuilder;
        ((SPARCHotSpotLIRGenerator) gen).setLockStack(((HotSpotDebugInfoBuilder) getDebugInfoBuilder()).lockStack());
    }

    @Override
    protected DebugInfoBuilder createDebugInfoBuilder(StructuredGraph graph, NodeMap<Value> nodeOperands) {
        HotSpotLockStack lockStack = new HotSpotLockStack(gen.getResult().getFrameMapBuilder(), LIRKind.value(Kind.Long));
        return new HotSpotDebugInfoBuilder(nodeOperands, lockStack);
    }

    private SPARCHotSpotLIRGenerator getGen() {
        return (SPARCHotSpotLIRGenerator) gen;
    }

    @Override
    public void visitSafepointNode(SafepointNode i) {
        LIRFrameState info = state(i);
        append(new SPARCHotSpotSafepointOp(info, getGen().config, gen));
    }

    @Override
    public void visitDirectCompareAndSwap(DirectCompareAndSwapNode x) {
        Variable address = gen.load(operand(x.object()));
        Value offset = operand(x.offset());
        Variable cmpValue = (Variable) gen.loadNonConst(operand(x.expectedValue()));
        Variable newValue = gen.load(operand(x.newValue()));
        Variable newValueTemp = gen.newVariable(newValue.getLIRKind());
        getGen().emitMove(newValueTemp, newValue);
        LIRKind kind = cmpValue.getLIRKind();
        assert kind.equals(newValue.getLIRKind());

        if (ValueUtil.isConstant(offset)) {
            assert !gen.getCodeCache().needsDataPatch(asConstant(offset));
            Variable longAddress = gen.newVariable(LIRKind.value(Kind.Long));
            gen.emitMove(longAddress, address);
            address = getGen().emitAdd(longAddress, asConstant(offset));
        } else {
            if (isLegal(offset)) {
                address = getGen().emitAdd(address, offset);
            }
        }

        append(new CompareAndSwapOp(address, cmpValue, newValueTemp));
        setResult(x, gen.emitMove(newValueTemp));
    }

    @Override
    protected void emitDirectCall(DirectCallTargetNode callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState callState) {
        InvokeKind invokeKind = ((HotSpotDirectCallTargetNode) callTarget).invokeKind();
        if (invokeKind == InvokeKind.Interface || invokeKind == InvokeKind.Virtual) {
            append(new SPARCHotspotDirectVirtualCallOp(callTarget.targetMethod(), result, parameters, temps, callState, invokeKind));
        } else {
            assert invokeKind == InvokeKind.Static || invokeKind == InvokeKind.Special;
            HotSpotResolvedJavaMethod resolvedMethod = (HotSpotResolvedJavaMethod) callTarget.targetMethod();
            assert !resolvedMethod.isAbstract() : "Cannot make direct call to abstract method.";
            append(new SPARCHotspotDirectStaticCallOp(callTarget.targetMethod(), result, parameters, temps, callState, invokeKind));
        }
    }

    @Override
    protected void emitIndirectCall(IndirectCallTargetNode callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState callState) {
        AllocatableValue metaspaceMethod = g5.asValue();
        gen.emitMove(metaspaceMethod, operand(((HotSpotIndirectCallTargetNode) callTarget).metaspaceMethod()));

        AllocatableValue targetAddress = o7.asValue();
        gen.emitMove(targetAddress, operand(callTarget.computedAddress()));
        append(new SPARCIndirectCallOp(callTarget.targetMethod(), result, parameters, temps, metaspaceMethod, targetAddress, callState));
    }

    @Override
    public void emitPatchReturnAddress(ValueNode address) {
        append(new SPARCHotSpotPatchReturnAddressOp(gen.load(operand(address))));
    }

    @Override
    public void emitJumpToExceptionHandlerInCaller(ValueNode handlerInCallerPc, ValueNode exception, ValueNode exceptionPc) {
        Variable handler = gen.load(operand(handlerInCallerPc));
        ForeignCallLinkage linkage = gen.getForeignCalls().lookupForeignCall(EXCEPTION_HANDLER_IN_CALLER);
        CallingConvention linkageCc = linkage.getOutgoingCallingConvention();
        assert linkageCc.getArgumentCount() == 2;
        RegisterValue exceptionFixed = (RegisterValue) linkageCc.getArgument(0);
        RegisterValue exceptionPcFixed = (RegisterValue) linkageCc.getArgument(1);
        gen.emitMove(exceptionFixed, operand(exception));
        gen.emitMove(exceptionPcFixed, operand(exceptionPc));
        Register thread = getGen().getProviders().getRegisters().getThreadRegister();
        SPARCHotSpotJumpToExceptionHandlerInCallerOp op = new SPARCHotSpotJumpToExceptionHandlerInCallerOp(handler, exceptionFixed, exceptionPcFixed, getGen().config.threadIsMethodHandleReturnOffset,
                        thread);
        append(op);
    }

    public void emitPrefetchAllocate(ValueNode address, ValueNode distance) {
        SPARCAddressValue addr = getGen().emitAddress(operand(address), 0, getGen().loadNonConst(operand(distance)), 1);
        append(new SPARCPrefetchOp(addr, getGen().config.allocatePrefetchInstr));
    }

    @Override
    public void visitFullInfopointNode(FullInfopointNode i) {
        if (i.getState() != null && i.getState().bci == BytecodeFrame.AFTER_BCI) {
            Debug.log("Ignoring InfopointNode for AFTER_BCI");
        } else {
            super.visitFullInfopointNode(i);
        }
    }
}
