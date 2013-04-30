/*
 * Copyright (c) 2011, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.spi;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;

public interface LIRGeneratorTool {

    TargetDescription target();

    CodeCacheProvider getRuntime();

    /**
     * Checks whether the supplied constant can be used without loading it into a register for most
     * operations, i.e., for commonly used arithmetic, logical, and comparison operations.
     * 
     * @param c The constant to check.
     * @return True if the constant can be used directly, false if the constant needs to be in a
     *         register.
     */
    boolean canInlineConstant(Constant c);

    RegisterAttributes attributes(Register register);

    Value operand(ValueNode object);

    AllocatableValue newVariable(PlatformKind kind);

    Value setResult(ValueNode x, Value operand);

    AllocatableValue emitMove(Value input);

    void emitMove(AllocatableValue dst, Value src);

    Value emitAddress(Value base, long displacement, Value index, int scale);

    Value emitAddress(StackSlot slot);

    Value emitLoad(Kind kind, Value address, DeoptimizingNode deopting);

    void emitStore(Kind kind, Value address, Value input, DeoptimizingNode deopting);

    Value emitNegate(Value input);

    Value emitAdd(Value a, Value b);

    Value emitSub(Value a, Value b);

    Value emitMul(Value a, Value b);

    Value emitDiv(Value a, Value b, DeoptimizingNode deopting);

    Value emitRem(Value a, Value b, DeoptimizingNode deopting);

    Value emitUDiv(Value a, Value b, DeoptimizingNode deopting);

    Value emitURem(Value a, Value b, DeoptimizingNode deopting);

    Value emitAnd(Value a, Value b);

    Value emitOr(Value a, Value b);

    Value emitXor(Value a, Value b);

    Value emitShl(Value a, Value b);

    Value emitShr(Value a, Value b);

    Value emitUShr(Value a, Value b);

    Value emitConvert(ConvertNode.Op opcode, Value inputVal);

    void emitMembar(int barriers);

    void emitDeoptimize(DeoptimizationAction action, DeoptimizingNode deopting);

    void emitNullCheck(ValueNode v, DeoptimizingNode deopting);

    Value emitCall(RuntimeCallTarget callTarget, CallingConvention cc, DeoptimizingNode info, Value... args);

    void emitIf(IfNode i);

    void emitConditional(ConditionalNode i);

    void emitSwitch(SwitchNode i);

    void emitInvoke(Invoke i);

    void visitRuntimeCall(RuntimeCallNode i);

    // Handling of block-end nodes still needs to be unified in the LIRGenerator.
    void visitMerge(MergeNode i);

    void visitEndNode(AbstractEndNode i);

    void visitLoopEnd(LoopEndNode i);

    void visitCompareAndSwap(CompareAndSwapNode i);

    // These methods define the contract a runtime specific backend must provide.

    void visitReturn(ReturnNode i);

    void visitSafepointNode(SafepointNode i);

    void visitBreakpointNode(BreakpointNode i);

    void emitUnwind(Value operand);

    /**
     * Called just before register allocation is performed on the LIR owned by this generator.
     */
    void beforeRegisterAllocation();

    void visitInfopointNode(InfopointNode i);
}
