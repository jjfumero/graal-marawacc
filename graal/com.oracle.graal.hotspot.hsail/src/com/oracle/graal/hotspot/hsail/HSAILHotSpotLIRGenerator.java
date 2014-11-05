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

package com.oracle.graal.hotspot.hsail;

import static com.oracle.graal.api.code.ValueUtil.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.compiler.common.calc.*;
import com.oracle.graal.compiler.hsail.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.HotSpotVMConfig.CompressEncoding;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.StandardOp.SaveRegistersOp;
import com.oracle.graal.lir.gen.*;
import com.oracle.graal.lir.hsail.*;
import com.oracle.graal.lir.hsail.HSAILControlFlow.CondMoveOp;
import com.oracle.graal.lir.hsail.HSAILControlFlow.DeoptimizeOp;
import com.oracle.graal.lir.hsail.HSAILControlFlow.ForeignCall1ArgOp;
import com.oracle.graal.lir.hsail.HSAILControlFlow.ForeignCall2ArgOp;
import com.oracle.graal.lir.hsail.HSAILControlFlow.ForeignCallNoArgOp;
import com.oracle.graal.lir.hsail.HSAILMove.CompareAndSwapOp;
import com.oracle.graal.lir.hsail.HSAILMove.LoadAcquireOp;
import com.oracle.graal.lir.hsail.HSAILMove.LoadOp;
import com.oracle.graal.lir.hsail.HSAILMove.MoveToRegOp;
import com.oracle.graal.lir.hsail.HSAILMove.StoreConstantOp;
import com.oracle.graal.lir.hsail.HSAILMove.StoreOp;
import com.oracle.graal.lir.hsail.HSAILMove.StoreReleaseOp;
import com.oracle.graal.lir.hsail.HSAILMove.WorkItemAbsIdOp;
import com.oracle.graal.phases.util.*;

/**
 * The HotSpot specific portion of the HSAIL LIR generator.
 */
public class HSAILHotSpotLIRGenerator extends HSAILLIRGenerator implements HotSpotLIRGenerator {

    final HotSpotVMConfig config;

    public HSAILHotSpotLIRGenerator(Providers providers, HotSpotVMConfig config, CallingConvention cc, LIRGenerationResult lirGenRes) {
        super(providers, cc, lirGenRes);
        this.config = config;
    }

    @Override
    public HotSpotProviders getProviders() {
        return (HotSpotProviders) super.getProviders();
    }

    int getLogMinObjectAlignment() {
        return config.logMinObjAlignment();
    }

    int getNarrowOopShift() {
        return config.narrowOopShift;
    }

    long getNarrowOopBase() {
        return config.narrowOopBase;
    }

    int getLogKlassAlignment() {
        return config.logKlassAlignment;
    }

    int getNarrowKlassShift() {
        return config.narrowKlassShift;
    }

    long getNarrowKlassBase() {
        return config.narrowKlassBase;
    }

    private static boolean canStoreConstant(JavaConstant c) {
        return !(c instanceof HotSpotObjectConstant);
    }

    @Override
    public boolean canInlineConstant(JavaConstant c) {
        if (c instanceof HotSpotObjectConstant) {
            return c.isNull();
        } else {
            return super.canInlineConstant(c);
        }
    }

    @Override
    public Variable emitLoad(LIRKind kind, Value address, LIRFrameState state) {
        HSAILAddressValue loadAddress = asAddressValue(address);
        Variable result = newVariable(kind);
        append(new LoadOp((Kind) kind.getPlatformKind(), result, loadAddress, state));
        return result;
    }

    public Variable emitLoadAcquire(LIRKind kind, Value address, LIRFrameState state) {
        HSAILAddressValue loadAddress = asAddressValue(address);
        Variable result = newVariable(kind);
        append(new LoadAcquireOp((Kind) kind.getPlatformKind(), result, loadAddress, state));
        return result;
    }

    @Override
    public void emitStore(LIRKind kind, Value address, Value inputVal, LIRFrameState state) {
        HSAILAddressValue storeAddress = asAddressValue(address);
        if (isConstant(inputVal)) {
            JavaConstant c = asConstant(inputVal);
            if (HotSpotCompressedNullConstant.COMPRESSED_NULL.equals(c)) {
                c = JavaConstant.INT_0;
            }
            if (canStoreConstant(c)) {
                append(new StoreConstantOp((Kind) kind.getPlatformKind(), storeAddress, c, state));
                return;
            }
        }
        Variable input = load(inputVal);
        append(new StoreOp((Kind) kind.getPlatformKind(), storeAddress, input, state));
    }

    public void emitStoreRelease(LIRKind kind, Value address, Value inputVal, LIRFrameState state) {
        HSAILAddressValue storeAddress = asAddressValue(address);
        // TODO: handle Constants here
        Variable input = load(inputVal);
        append(new StoreReleaseOp((Kind) kind.getPlatformKind(), storeAddress, input, state));
    }

    public Value emitCompareAndSwap(Value address, Value expectedValue, Value newValue, Value trueValue, Value falseValue) {
        LIRKind kind = newValue.getLIRKind();
        assert kind.equals(expectedValue.getLIRKind());
        Kind memKind = (Kind) kind.getPlatformKind();

        HSAILAddressValue addressValue = asAddressValue(address);
        Variable expected = emitMove(expectedValue);
        Variable casResult = newVariable(kind);
        append(new CompareAndSwapOp(memKind, casResult, addressValue, expected, asAllocatable(newValue)));

        assert trueValue.getLIRKind().equals(falseValue.getLIRKind());
        Variable nodeResult = newVariable(trueValue.getLIRKind());
        append(new CondMoveOp(HSAILLIRGenerator.mapKindToCompareOp(memKind), casResult, expected, nodeResult, Condition.EQ, trueValue, falseValue));
        return nodeResult;
    }

    @Override
    public Value emitAtomicReadAndAdd(Value address, Value delta) {
        LIRKind kind = delta.getLIRKind();
        Kind memKind = (Kind) kind.getPlatformKind();
        Variable result = newVariable(kind);
        HSAILAddressValue addressValue = asAddressValue(address);
        append(new HSAILMove.AtomicReadAndAddOp(memKind, result, addressValue, asAllocatable(delta)));
        return result;
    }

    @Override
    public Value emitAtomicReadAndWrite(Value address, Value newValue) {
        LIRKind kind = newValue.getLIRKind();
        Kind memKind = (Kind) kind.getPlatformKind();
        Variable result = newVariable(kind);
        HSAILAddressValue addressValue = asAddressValue(address);
        append(new HSAILMove.AtomicReadAndWriteOp(memKind, result, addressValue, asAllocatable(newValue)));
        return result;
    }

    @Override
    public void emitDeoptimize(Value actionAndReason, Value failedSpeculation, LIRFrameState state) {
        emitDeoptimizeInner(actionAndReason, state, "emitDeoptimize");
    }

    /***
     * We need 64-bit and 32-bit scratch registers for the codegen $s0 can be live at this block.
     */
    private void emitDeoptimizeInner(Value actionAndReason, LIRFrameState lirFrameState, String emitName) {
        DeoptimizeOp deopt = new DeoptimizeOp(actionAndReason, lirFrameState, emitName, config.useHSAILDeoptimization, getMetaAccess());
        ((HSAILHotSpotLIRGenerationResult) getResult()).addDeopt(deopt);
        append(deopt);
    }

    /***
     * This is a very temporary solution to emitForeignCall. We don't really support foreign calls
     * yet, but we do want to generate dummy code for them. The ForeignCallXXXOps just end up
     * emitting a comment as to what Foreign call they would have made.
     */
    @Override
    public Variable emitForeignCall(ForeignCallLinkage linkage, LIRFrameState state, Value... args) {
        Variable result = newVariable(LIRKind.reference(Kind.Object));  // linkage.getDescriptor().getResultType());

        // to make the LIRVerifier happy, we move any constants into registers
        Value[] argLocations = new Value[args.length];
        for (int i = 0; i < args.length; i++) {
            Value arg = args[i];
            AllocatableValue loc = newVariable(arg.getLIRKind());
            emitMove(loc, arg);
            argLocations[i] = loc;
        }

        // here we could check the callName if we wanted to only handle certain callnames
        String callName = linkage.getDescriptor().getName();
        switch (argLocations.length) {
            case 0:
                append(new ForeignCallNoArgOp(callName, result));
                break;
            case 1:
                append(new ForeignCall1ArgOp(callName, result, argLocations[0]));
                break;
            case 2:
                append(new ForeignCall2ArgOp(callName, result, argLocations[0], argLocations[1]));
                break;
            default:
                throw new InternalError("NYI emitForeignCall " + callName + ", " + argLocations.length + ", " + linkage);
        }
        return result;
    }

    @Override
    protected HSAILLIRInstruction createMove(AllocatableValue dst, Value src) {
        if (HotSpotCompressedNullConstant.COMPRESSED_NULL.equals(src)) {
            return new MoveToRegOp(Kind.Int, dst, JavaConstant.INT_0);
        } else if (src instanceof HotSpotObjectConstant && HotSpotObjectConstantImpl.isCompressed((JavaConstant) src)) {
            Variable uncompressed = newVariable(LIRKind.reference(Kind.Object));
            append(new MoveToRegOp(Kind.Object, uncompressed, src));
            CompressEncoding oopEncoding = config.getOopEncoding();
            return new HSAILMove.CompressPointer(dst, newVariable(LIRKind.reference(Kind.Object)), uncompressed, oopEncoding.base, oopEncoding.shift, oopEncoding.alignment, true);
        } else {
            return super.createMove(dst, src);
        }
    }

    @Override
    protected void emitForeignCall(ForeignCallLinkage linkage, Value result, Value[] arguments, Value[] temps, LIRFrameState info) {
        // this version of emitForeignCall not used for now
    }

    /**
     * @return a compressed version of the incoming constant lifted from AMD64HotSpotLIRGenerator
     */
    protected static JavaConstant compress(JavaConstant c, CompressEncoding encoding) {
        if (c.getKind() == Kind.Long) {
            int compressedValue = (int) (((c.asLong() - encoding.base) >> encoding.shift) & 0xffffffffL);
            if (c instanceof HotSpotMetaspaceConstant) {
                return HotSpotMetaspaceConstant.forMetaspaceObject(Kind.Int, compressedValue, HotSpotMetaspaceConstant.getMetaspaceObject(c), true);
            } else {
                return JavaConstant.forIntegerKind(Kind.Int, compressedValue);
            }
        } else {
            throw GraalInternalError.shouldNotReachHere();
        }
    }

    public void emitTailcall(Value[] args, Value address) {
        throw GraalInternalError.unimplemented();
    }

    public void emitDeoptimizeCaller(DeoptimizationAction action, DeoptimizationReason reason) {
        throw GraalInternalError.unimplemented();
    }

    public StackSlot getLockSlot(int lockDepth) {
        throw GraalInternalError.unimplemented();
    }

    @Override
    public Value emitCompress(Value pointer, CompressEncoding encoding, boolean nonNull) {
        Variable result = newVariable(LIRKind.reference(Kind.Int));
        append(new HSAILMove.CompressPointer(result, newVariable(pointer.getLIRKind()), asAllocatable(pointer), encoding.base, encoding.shift, encoding.alignment, nonNull));
        return result;
    }

    @Override
    public Value emitUncompress(Value pointer, CompressEncoding encoding, boolean nonNull) {
        Variable result = newVariable(LIRKind.reference(Kind.Object));
        append(new HSAILMove.UncompressPointer(result, asAllocatable(pointer), encoding.base, encoding.shift, encoding.alignment, nonNull));
        return result;
    }

    public SaveRegistersOp emitSaveAllRegisters() {
        throw GraalInternalError.unimplemented();
    }

    public void emitNullCheck(Value address, LIRFrameState state) {
        assert address.getKind() == Kind.Object : address + " - " + address.getKind() + " not an object!";
        Variable obj = newVariable(LIRKind.reference(Kind.Object));
        emitMove(obj, address);
        append(new HSAILMove.NullCheckOp(obj, state));
    }

    public Variable emitWorkItemAbsId() {
        Variable result = newVariable(LIRKind.value(Kind.Int));
        append(new WorkItemAbsIdOp(result));
        return result;
    }
}
