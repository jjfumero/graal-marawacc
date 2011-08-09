/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.graal.compiler.nodes.extended;

import com.oracle.max.graal.compiler.ir.*;
import com.oracle.max.graal.compiler.nodes.base.*;
import com.oracle.max.graal.compiler.nodes.spi.*;
import com.oracle.max.graal.graph.*;
import com.sun.cri.bytecode.*;
import com.sun.cri.ci.*;

@NodeInfo(shortName = ">>>")
public final class UnsignedRightShiftNode extends ShiftNode implements Canonicalizable {

    public UnsignedRightShiftNode(CiKind kind, ValueNode x, ValueNode y, Graph graph) {
        super(kind, kind == CiKind.Int ? Bytecodes.IUSHR : Bytecodes.LUSHR, x, y, graph);
    }

    @Override
    public Node canonical(NotifyReProcess reProcess) {
        if (y().isConstant()) {
            int amount = y().asConstant().asInt();
            int originalAmout = amount;
            int mask;
            if (kind == CiKind.Int) {
                mask = 0x1f;
            } else {
                assert kind == CiKind.Long;
                mask = 0x3f;
            }
            amount &= mask;
            if (x().isConstant()) {
                if (kind == CiKind.Int) {
                    return Constant.forInt(x().asConstant().asInt() >>> amount, graph());
                } else {
                    assert kind == CiKind.Long;
                    return Constant.forLong(x().asConstant().asLong() >>> amount, graph());
                }
            }
            if (amount == 0) {
                return x();
            }
            if (x() instanceof ShiftNode) {
                ShiftNode other = (ShiftNode) x();
                if (other.y().isConstant()) {
                    int otherAmount = other.y().asConstant().asInt() & mask;
                    if (other instanceof UnsignedRightShiftNode) {
                        int total = amount + otherAmount;
                        if (total != (total & mask)) {
                            return Constant.forInt(0, graph());
                        }
                        return new UnsignedRightShiftNode(kind, other.x(), Constant.forInt(total, graph()), graph());
                    } else if (other instanceof LeftShift && otherAmount == amount) {
                        if (kind == CiKind.Long) {
                            return new And(kind, other.x(), Constant.forLong(-1L >>> amount, graph()), graph());
                        } else {
                            assert kind == CiKind.Int;
                            return new And(kind, other.x(), Constant.forInt(-1 >>> amount, graph()), graph());
                        }
                    }
                }
            }
            if (originalAmout != amount) {
                return new UnsignedRightShiftNode(kind, x(), Constant.forInt(amount, graph()), graph());
            }
        }
        return this;
    }
}
