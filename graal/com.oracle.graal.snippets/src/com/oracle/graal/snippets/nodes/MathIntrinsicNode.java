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
package com.oracle.graal.snippets.nodes;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.compiler.target.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

public class MathIntrinsicNode extends FloatingNode implements Canonicalizable, LIRGenLowerable {

    @Input private ValueNode x;
    private final Operation operation;

    public enum Operation {
        ABS, SQRT, LOG, LOG10, SIN, COS, TAN,
    }

    public ValueNode x() {
        return x;
    }

    public Operation operation() {
        return operation;
    }

    public MathIntrinsicNode(ValueNode x, Operation op) {
        super(StampFactory.forKind(x.kind()));
        assert x.kind() == Kind.Double;
        this.x = x;
        this.operation = op;
    }

    @Override
    public void generate(LIRGenerator gen) {
        Variable input = gen.load(gen.operand(x()));
        Variable result = gen.newVariable(kind());
        switch (operation()) {
            case ABS:   gen.emitMathAbs(result, input); break;
            case SQRT:  gen.emitMathSqrt(result, input); break;
            case LOG:   gen.emitMathLog(result, input, false); break;
            case LOG10: gen.emitMathLog(result, input, true); break;
            case SIN:   gen.emitMathSin(result, input); break;
            case COS:   gen.emitMathCos(result, input); break;
            case TAN:   gen.emitMathTan(result, input); break;
            default:    throw GraalInternalError.shouldNotReachHere();
        }
        gen.setResult(this, result);
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        if (x().isConstant()) {
            double value = x().asConstant().asDouble();
            switch (operation()) {
                case ABS:   return ConstantNode.forDouble(Math.abs(value), graph());
                case SQRT:  return ConstantNode.forDouble(Math.sqrt(value), graph());
                case LOG:   return ConstantNode.forDouble(Math.log(value), graph());
                case LOG10: return ConstantNode.forDouble(Math.log10(value), graph());
                case SIN:   return ConstantNode.forDouble(Math.sin(value), graph());
                case COS:   return ConstantNode.forDouble(Math.cos(value), graph());
                case TAN:   return ConstantNode.forDouble(Math.tan(value), graph());
            }
        }
        return this;
    }

    @NodeIntrinsic
    public static native double compute(double x, @ConstantNodeParameter Operation op);
}
