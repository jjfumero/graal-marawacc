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

import static com.oracle.graal.graph.util.CollectionsAccess.*;

import java.util.*;

import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;

public class InductionVariables {

    private final LoopEx loop;
    private Map<Node, InductionVariable> ivs;

    public InductionVariables(LoopEx loop) {
        this.loop = loop;
        ivs = newNodeIdentityMap();
        findDerived(findBasic());
    }

    public InductionVariable get(ValueNode v) {
        return ivs.get(v);
    }

    private Collection<BasicInductionVariable> findBasic() {
        List<BasicInductionVariable> bivs = new LinkedList<>();
        LoopBeginNode loopBegin = loop.loopBegin();
        AbstractEndNode forwardEnd = loopBegin.forwardEnd();
        for (PhiNode phi : loopBegin.phis()) {
            ValueNode backValue = phi.singleBackValue();
            if (backValue == null) {
                continue;
            }
            ValueNode stride = addSub(backValue, phi);
            if (stride != null) {
                BasicInductionVariable biv = new BasicInductionVariable(loop, (ValuePhiNode) phi, phi.valueAt(forwardEnd), stride, (IntegerArithmeticNode) backValue);
                ivs.put(phi, biv);
                bivs.add(biv);
            }
        }
        return bivs;
    }

    private void findDerived(Collection<BasicInductionVariable> bivs) {
        Queue<InductionVariable> scanQueue = new LinkedList<>(bivs);
        while (!scanQueue.isEmpty()) {
            InductionVariable baseIv = scanQueue.remove();
            ValueNode baseIvNode = baseIv.valueNode();
            for (ValueNode op : baseIvNode.usages().filter(ValueNode.class)) {
                if (loop.isOutsideLoop(op)) {
                    continue;
                }
                InductionVariable iv = null;
                ValueNode offset = addSub(op, baseIvNode);
                ValueNode scale;
                if (offset != null) {
                    iv = new DerivedOffsetInductionVariable(loop, baseIv, offset, (IntegerArithmeticNode) op);
                } else if (op instanceof NegateNode) {
                    iv = new DerivedScaledInductionVariable(loop, baseIv, (NegateNode) op);
                } else if ((scale = mul(op, baseIvNode)) != null) {
                    iv = new DerivedScaledInductionVariable(loop, baseIv, scale, op);
                }

                if (iv != null) {
                    ivs.put(op, iv);
                    scanQueue.offer(iv);
                }
            }
        }
    }

    private ValueNode addSub(ValueNode op, ValueNode base) {
        if (op instanceof IntegerAddNode || op instanceof IntegerSubNode) {
            IntegerArithmeticNode aritOp = (IntegerArithmeticNode) op;
            if (aritOp.x() == base && loop.isOutsideLoop(aritOp.y())) {
                return aritOp.y();
            } else if (aritOp.y() == base && loop.isOutsideLoop(aritOp.x())) {
                return aritOp.x();
            }
        }
        return null;
    }

    private ValueNode mul(ValueNode op, ValueNode base) {
        if (op instanceof IntegerMulNode) {
            IntegerMulNode mul = (IntegerMulNode) op;
            if (mul.x() == base && loop.isOutsideLoop(mul.y())) {
                return mul.y();
            } else if (mul.y() == base && loop.isOutsideLoop(mul.x())) {
                return mul.x();
            }
        }
        if (op instanceof LeftShiftNode) {
            LeftShiftNode shift = (LeftShiftNode) op;
            if (shift.x() == base && shift.y().isConstant()) {
                return ConstantNode.forInt(1 << shift.y().asConstant().asInt(), base.graph());
            }
        }
        return null;
    }

    /**
     * Deletes any nodes created within the scope of this object that have no usages.
     */
    public void deleteUnusedNodes() {
        for (InductionVariable iv : ivs.values()) {
            iv.deleteUnusedNodes();
        }
    }
}
