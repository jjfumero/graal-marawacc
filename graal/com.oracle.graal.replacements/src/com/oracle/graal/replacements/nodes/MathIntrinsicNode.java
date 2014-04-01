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
package com.oracle.graal.replacements.nodes;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

public class MathIntrinsicNode extends FloatingNode implements Canonicalizable, ArithmeticLIRLowerable {

    @Input private ValueNode x;
    private final Operation operation;

    public enum Operation {
        ABS,
        SQRT,
        LOG,
        LOG10,
        SIN,
        COS,
        TAN
    }

    public ValueNode x() {
        return x;
    }

    public Operation operation() {
        return operation;
    }

    public MathIntrinsicNode(ValueNode x, Operation op) {
        super(StampFactory.forKind(Kind.Double));
        assert x.stamp() instanceof FloatStamp && PrimitiveStamp.getBits(x.stamp()) == 64;
        this.x = x;
        this.operation = op;
    }

    @Override
    public void generate(NodeLIRBuiderTool gen) {
        Value input = gen.operand(x());
        Value result;
        switch (operation()) {
            case ABS:
                result = gen.getLIRGeneratorTool().emitMathAbs(input);
                break;
            case SQRT:
                result = gen.getLIRGeneratorTool().emitMathSqrt(input);
                break;
            case LOG:
                result = gen.getLIRGeneratorTool().emitMathLog(input, false);
                break;
            case LOG10:
                result = gen.getLIRGeneratorTool().emitMathLog(input, true);
                break;
            case SIN:
                result = gen.getLIRGeneratorTool().emitMathSin(input);
                break;
            case COS:
                result = gen.getLIRGeneratorTool().emitMathCos(input);
                break;
            case TAN:
                result = gen.getLIRGeneratorTool().emitMathTan(input);
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        gen.setResult(this, result);
    }

    public Constant evalConst(Constant... inputs) {
        assert inputs.length == 1;
        return Constant.forDouble(doCompute(inputs[0].asDouble(), operation()));
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (x().isConstant()) {
            return ConstantNode.forPrimitive(evalConst(x().asConstant()), graph());
        }
        return this;
    }

    @NodeIntrinsic
    public static double compute(double value, @ConstantNodeParameter Operation op) {
        return doCompute(value, op);
    }

    private static double doCompute(double value, Operation op) throws GraalInternalError {
        switch (op) {
            case ABS:
                return Math.abs(value);
            case SQRT:
                return Math.sqrt(value);
            case LOG:
                return Math.log(value);
            case LOG10:
                return Math.log10(value);
            case SIN:
                return Math.sin(value);
            case COS:
                return Math.cos(value);
            case TAN:
                return Math.tan(value);
        }
        throw new GraalInternalError("unknown op %s", op);
    }
}
