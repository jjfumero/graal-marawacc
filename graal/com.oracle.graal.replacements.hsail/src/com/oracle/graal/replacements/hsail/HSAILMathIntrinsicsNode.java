/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.replacements.hsail;

import com.oracle.graal.api.meta.Constant;
import com.oracle.graal.api.meta.Value;
import com.oracle.graal.compiler.hsail.HSAILLIRGenerator;
import com.oracle.graal.graph.GraalInternalError;
import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.spi.Canonicalizable;
import com.oracle.graal.graph.spi.CanonicalizerTool;
import com.oracle.graal.lir.hsail.HSAILArithmetic;
import com.oracle.graal.nodes.calc.FloatingNode;
import com.oracle.graal.nodes.ConstantNode;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.StampFactory;

/**
 * This node implements HSAIL intrinsics for specific {@link Math} routines.
 */
public class HSAILMathIntrinsicsNode extends FloatingNode implements Canonicalizable, ArithmeticLIRLowerable {

    /**
     * The parameter passed to the math operation that this node represents.
     */
    @Input private ValueNode param;

    /**
     * The math operation that this Node represents.
     */
    private final HSAILArithmetic operation;

    /**
     * Gets the parameter passed to the math operation that this node represents.
     * 
     * @return the parameter
     */
    public ValueNode getParameter() {
        return param;
    }

    /**
     * Returns the math operation represented by this node.
     * 
     * @return the operation
     */
    public HSAILArithmetic operation() {
        return operation;
    }

    /**
     * Creates a new HSAILMathIntrinsicNode.
     * 
     * @param x the argument to the math operation
     * @param op the math operation
     */
    public HSAILMathIntrinsicsNode(ValueNode x, HSAILArithmetic op) {
        super(StampFactory.forKind(x.getKind()));
        this.param = x;
        this.operation = op;
    }

    /**
     * Generates the LIR instructions for the math operation represented by this node.
     */
    @Override
    public void generate(NodeLIRBuiderTool gen) {
        Value input = gen.operand(getParameter());
        Value result;
        switch (operation()) {
            case ABS:
                result = gen.getLIRGeneratorTool().emitMathAbs(input);
                break;
            case CEIL:
                result = ((HSAILLIRGenerator) (gen.getLIRGeneratorTool())).emitMathCeil(input);
                break;
            case FLOOR:
                result = ((HSAILLIRGenerator) (gen.getLIRGeneratorTool())).emitMathFloor(input);
                break;
            case RINT:
                result = ((HSAILLIRGenerator) (gen.getLIRGeneratorTool())).emitMathRint(input);
                break;
            case SQRT:
                result = gen.getLIRGeneratorTool().emitMathSqrt(input);
                break;

            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        gen.setResult(this, result);
    }

    /**
     * Converts a constant to a boxed double.
     */
    public Constant evalConst(Constant... inputs) {
        assert inputs.length == 1;
        return Constant.forDouble(compute(inputs[0].asDouble(), operation()));
    }

    /**
     * Converts the result of the math operation to a boxed Double constant node.
     */
    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (getParameter().isConstant()) {
            return ConstantNode.forPrimitive(evalConst(getParameter().asConstant()), graph());
        }
        return this;
    }

    // The routines below are node intrinsics. A call to any of these routines is replaced by a
    // HSAILMathIntrinsicNode to handle the Math library operation represented by the
    // HSAILArithmetic argument.

    /**
     * Node intrinsic for {@link Math} routines taking a single int parameter.
     * 
     * @param value
     * @param op the math operation
     * @return the result of the operation
     */
    @NodeIntrinsic
    public static native int compute(int value, @ConstantNodeParameter HSAILArithmetic op);

    /**
     * Node intrinsic for {@link Math} routines taking a single double parameter.
     * 
     * @param value the input parameter
     * @param op the math operation
     * @return the result of the operation
     */
    @NodeIntrinsic
    public static native long compute(long value, @ConstantNodeParameter HSAILArithmetic op);

    /**
     * Node intrinsic for {@link Math} routines taking a single float parameter.
     * 
     * @param value the input parameter
     * @param op the math operation
     * @return the result of the operation
     */
    @NodeIntrinsic
    public static native float compute(float value, @ConstantNodeParameter HSAILArithmetic op);

    /**
     * Node intrinsic for {@link Math} routines taking a single double parameter.
     * 
     * @param value the input parameter
     * @param op the math operation
     * 
     * @return the result of the operation
     */
    @NodeIntrinsic
    public static native double compute(double value, @ConstantNodeParameter HSAILArithmetic op);

}
