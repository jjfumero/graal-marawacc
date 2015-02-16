/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.calc;

import java.io.*;
import java.util.function.*;

import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.compiler.common.type.ArithmeticOpTable.UnaryOp;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;

@NodeInfo
public abstract class UnaryArithmeticNode<OP> extends UnaryNode implements ArithmeticLIRLowerable {

    @SuppressWarnings("rawtypes") public static final NodeClass<UnaryArithmeticNode> TYPE = NodeClass.get(UnaryArithmeticNode.class);

    protected interface SerializableUnaryFunction<T> extends Function<ArithmeticOpTable, UnaryOp<T>>, Serializable {
    }

    protected final SerializableUnaryFunction<OP> getOp;

    protected UnaryArithmeticNode(NodeClass<?> c, SerializableUnaryFunction<OP> getOp, ValueNode value) {
        super(c, getOp.apply(ArithmeticOpTable.forStamp(value.stamp())).foldStamp(value.stamp()), value);
        this.getOp = getOp;
    }

    protected final UnaryOp<OP> getOp(ValueNode forValue) {
        return getOp.apply(ArithmeticOpTable.forStamp(forValue.stamp()));
    }

    @Override
    public boolean inferStamp() {
        return updateStamp(getOp(getValue()).foldStamp(getValue().stamp()));
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forValue) {
        ValueNode synonym = findSynonym(forValue, getOp(forValue));
        if (synonym != null) {
            return synonym;
        }
        return this;
    }

    protected static <OP> ValueNode findSynonym(ValueNode forValue, UnaryOp<OP> op) {
        if (forValue.isConstant()) {
            return ConstantNode.forPrimitive(op.foldStamp(forValue.stamp()), op.foldConstant(forValue.asConstant()));
        }
        return null;
    }
}
