/*
 * Copyright (c) 2012, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.loop;

import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.type.*;

public class BasicInductionVariable extends InductionVariable {

    private PhiNode phi;
    private ValueNode init;
    private ValueNode rawStride;
    private IntegerArithmeticNode op;

    public BasicInductionVariable(LoopEx loop, PhiNode phi, ValueNode init, ValueNode rawStride, IntegerArithmeticNode op) {
        super(loop);
        this.phi = phi;
        this.init = init;
        this.rawStride = rawStride;
        this.op = op;
    }

    @Override
    public Direction direction() {
        Stamp stamp = rawStride.stamp();
        if (stamp instanceof IntegerStamp) {
            IntegerStamp integerStamp = (IntegerStamp) stamp;
            Direction dir = null;
            if (integerStamp.isStrictlyPositive()) {
                dir = Direction.Up;
            } else if (integerStamp.isStrictlyNegative()) {
                dir = Direction.Down;
            }
            if (dir != null) {
                if (op instanceof IntegerAddNode) {
                    return dir;
                } else {
                    assert op instanceof IntegerSubNode;
                    return dir.opposite();
                }
            }
        }
        return null;
    }

    @Override
    public PhiNode valueNode() {
        return phi;
    }

    @Override
    public ValueNode initNode() {
        return init;
    }

    @Override
    public ValueNode strideNode() {
        if (op instanceof IntegerAddNode) {
            return rawStride;
        }
        if (op instanceof IntegerSubNode) {
            return rawStride.graph().unique(new NegateNode(rawStride));
        }
        throw GraalInternalError.shouldNotReachHere();
    }

    @Override
    public boolean isConstantInit() {
        return init.isConstant();
    }

    @Override
    public boolean isConstantStride() {
        return rawStride.isConstant();
    }

    @Override
    public long constantInit() {
        return init.asConstant().asLong();
    }

    @Override
    public long constantStride() {
        if (op instanceof IntegerAddNode) {
            return rawStride.asConstant().asLong();
        }
        if (op instanceof IntegerSubNode) {
            return -rawStride.asConstant().asLong();
        }
        throw GraalInternalError.shouldNotReachHere();
    }

    @Override
    public ValueNode extremumNode() {
        return IntegerArithmeticNode.add(IntegerArithmeticNode.mul(strideNode(), loop.counted().maxTripCountNode()), init);
    }

    @Override
    public boolean isConstantExtremum() {
        return isConstantInit() && isConstantStride() && loop.counted().isConstantMaxTripCount();
    }

    @Override
    public long constantExtremum() {
        return constantStride() * loop.counted().constantMaxTripCount() + constantInit();
    }
}
