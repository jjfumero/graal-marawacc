/*
 * Copyright (c) 2011, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.lir.gen;

import com.oracle.jvmci.code.CallingConvention;
import com.oracle.jvmci.code.ForeignCallLinkage;
import com.oracle.jvmci.code.RegisterAttributes;
import com.oracle.jvmci.code.TargetDescription;
import com.oracle.jvmci.code.StackSlotValue;
import com.oracle.jvmci.code.CodeCacheProvider;
import com.oracle.jvmci.code.Register;
import com.oracle.jvmci.code.ForeignCallsProvider;
import com.oracle.jvmci.meta.PlatformKind;
import com.oracle.jvmci.meta.Constant;
import com.oracle.jvmci.meta.Kind;
import com.oracle.jvmci.meta.JavaConstant;
import com.oracle.jvmci.meta.Value;
import com.oracle.jvmci.meta.LIRKind;
import com.oracle.jvmci.meta.AllocatableValue;
import com.oracle.jvmci.meta.MetaAccessProvider;
import com.oracle.graal.compiler.common.calc.*;
import com.oracle.graal.compiler.common.cfg.*;
import com.oracle.graal.compiler.common.spi.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.StandardOp.StackMove;
import com.oracle.jvmci.common.*;

public interface LIRGeneratorTool extends ArithmeticLIRGenerator, BenchmarkCounterFactory {

    public interface SpillMoveFactory {

        LIRInstruction createMove(AllocatableValue result, Value input);

        default LIRInstruction createStackMove(AllocatableValue result, Value input) {
            return new StackMove(result, input);
        }
    }

    public abstract class BlockScope implements AutoCloseable {

        public abstract AbstractBlockBase<?> getCurrentBlock();

        public abstract void close();

    }

    CodeGenProviders getProviders();

    TargetDescription target();

    MetaAccessProvider getMetaAccess();

    CodeCacheProvider getCodeCache();

    ForeignCallsProvider getForeignCalls();

    AbstractBlockBase<?> getCurrentBlock();

    LIRGenerationResult getResult();

    boolean hasBlockEnd(AbstractBlockBase<?> block);

    SpillMoveFactory getSpillMoveFactory();

    BlockScope getBlockScope(AbstractBlockBase<?> block);

    Value emitLoadConstant(LIRKind kind, Constant constant);

    Variable emitLoad(LIRKind kind, Value address, LIRFrameState state);

    void emitStore(LIRKind kind, Value address, Value input, LIRFrameState state);

    void emitNullCheck(Value address, LIRFrameState state);

    Variable emitCompareAndSwap(Value address, Value expectedValue, Value newValue, Value trueValue, Value falseValue);

    /**
     * Emit an atomic read-and-add instruction.
     *
     * @param address address of the value to be read and written
     * @param delta the value to be added
     */
    default Value emitAtomicReadAndAdd(Value address, Value delta) {
        throw JVMCIError.unimplemented();
    }

    /**
     * Emit an atomic read-and-write instruction.
     *
     * @param address address of the value to be read and written
     * @param newValue the new value to be written
     */
    default Value emitAtomicReadAndWrite(Value address, Value newValue) {
        throw JVMCIError.unimplemented();
    }

    void emitDeoptimize(Value actionAndReason, Value failedSpeculation, LIRFrameState state);

    Variable emitForeignCall(ForeignCallLinkage linkage, LIRFrameState state, Value... args);

    RegisterAttributes attributes(Register register);

    /**
     * Create a new {@link Variable}.
     *
     * @param kind The type of the value that will be stored in this {@link Variable}. See
     *            {@link LIRKind} for documentation on what to pass here. Note that in most cases,
     *            simply passing {@link Value#getLIRKind()} is wrong.
     * @return A new {@link Variable}.
     */
    Variable newVariable(LIRKind kind);

    Variable emitMove(Value input);

    void emitMove(AllocatableValue dst, Value src);

    /**
     * Emits an op that loads the address of some raw data.
     *
     * @param dst the variable into which the address is loaded
     * @param data the data to be installed with the generated code
     */
    void emitData(AllocatableValue dst, byte[] data);

    Value emitAddress(Value base, long displacement, Value index, int scale);

    Variable emitAddress(StackSlotValue slot);

    void emitMembar(int barriers);

    void emitUnwind(Value operand);

    /**
     * Called just before register allocation is performed on the LIR owned by this generator.
     * Overriding implementations of this method must call the overridden method.
     */
    void beforeRegisterAllocation();

    void emitIncomingValues(Value[] params);

    /**
     * Emits a return instruction. Implementations need to insert a move if the input is not in the
     * correct location.
     */
    void emitReturn(Value input);

    AllocatableValue asAllocatable(Value value);

    Variable load(Value value);

    Value loadNonConst(Value value);

    /**
     * Determines if only oop maps are required for the code generated from the LIR.
     */
    boolean needOnlyOopMaps();

    /**
     * Gets the ABI specific operand used to return a value of a given kind from a method.
     *
     * @param kind the kind of value being returned
     * @return the operand representing the ABI defined location used return a value of kind
     *         {@code kind}
     */
    AllocatableValue resultOperandFor(LIRKind kind);

    <I extends LIRInstruction> I append(I op);

    void emitJump(LabelRef label);

    void emitCompareBranch(PlatformKind cmpKind, Value left, Value right, Condition cond, boolean unorderedIsTrue, LabelRef trueDestination, LabelRef falseDestination,
                    double trueDestinationProbability);

    void emitOverflowCheckBranch(LabelRef overflow, LabelRef noOverflow, LIRKind cmpKind, double overflowProbability);

    void emitIntegerTestBranch(Value left, Value right, LabelRef trueDestination, LabelRef falseDestination, double trueSuccessorProbability);

    Variable emitConditionalMove(PlatformKind cmpKind, Value leftVal, Value right, Condition cond, boolean unorderedIsTrue, Value trueValue, Value falseValue);

    Variable emitIntegerTestMove(Value leftVal, Value right, Value trueValue, Value falseValue);

    void emitStrategySwitch(JavaConstant[] keyConstants, double[] keyProbabilities, LabelRef[] keyTargets, LabelRef defaultTarget, Variable value);

    void emitStrategySwitch(SwitchStrategy strategy, Variable key, LabelRef[] keyTargets, LabelRef defaultTarget);

    CallingConvention getCallingConvention();

    Variable emitBitCount(Value operand);

    Variable emitBitScanForward(Value operand);

    Variable emitBitScanReverse(Value operand);

    Variable emitByteSwap(Value operand);

    Variable emitArrayEquals(Kind kind, Value array1, Value array2, Value length);

    void emitBlackhole(Value operand);

    @SuppressWarnings("unused")
    default Value emitCountLeadingZeros(Value value) {
        throw JVMCIError.unimplemented();
    }

    @SuppressWarnings("unused")
    default Value emitCountTrailingZeros(Value value) {
        throw JVMCIError.unimplemented();
    }

}
