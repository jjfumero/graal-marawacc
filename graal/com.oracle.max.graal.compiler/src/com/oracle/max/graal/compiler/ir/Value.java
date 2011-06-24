/*
 * Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.graal.compiler.ir;

import java.util.*;

import com.oracle.max.graal.compiler.debug.*;
import com.oracle.max.graal.compiler.gen.*;
import com.oracle.max.graal.compiler.value.*;
import com.oracle.max.graal.graph.*;
import com.sun.cri.ci.*;
import com.sun.cri.ri.*;

/**
 * This class represents a value within the HIR graph, including local variables, phis, and
 * all other instructions.
 */
public abstract class Value extends Node {

    /**
     * The kind of this value. This is {@link CiKind#Void} for instructions that produce no value.
     * This kind is guaranteed to be a {@linkplain CiKind#stackKind() stack kind}.
     */
    public final CiKind kind;

    protected CiValue operand = CiValue.IllegalValue;

    /**
     * Creates a new value with the specified kind.
     * @param kind the type of this value
     * @param inputCount
     * @param successorCount
     * @param graph
     */
    public Value(CiKind kind, int inputCount, int successorCount, Graph graph) {
        super(inputCount, successorCount, graph);
        assert kind == kind.stackKind() : kind + " != " + kind.stackKind();
        this.kind = kind;
    }

    /**
     * Checks whether this value is a constant (i.e. it is of type {@link Constant}.
     * @return {@code true} if this value is a constant
     */
    public final boolean isConstant() {
        return this instanceof Constant;
    }

    /**
     * Checks whether this value represents the null constant.
     * @return {@code true} if this value represents the null constant
     */
    public final boolean isNullConstant() {
        return this instanceof Constant && ((Constant) this).value.isNull();
    }

    /**
     * Convert this value to a constant if it is a constant, otherwise return null.
     * @return the {@link CiConstant} represented by this value if it is a constant; {@code null}
     * otherwise
     */
    public final CiConstant asConstant() {
        if (this instanceof Constant) {
            return ((Constant) this).value;
        }
        return null;
    }

    /**
     * Gets the LIR operand associated with this instruction.
     * @return the LIR operand for this instruction
     */
    public final CiValue operand() {
        return operand;
    }

    /**
     * Sets the LIR operand associated with this instruction.
     * @param operand the operand to associate with this instruction
     */
    public final void setOperand(CiValue operand) {
        assert this.operand.isIllegal() : "operand cannot be set twice";
        assert operand != null && operand.isLegal() : "operand must be legal";
        assert operand.kind.stackKind() == this.kind;
        this.operand = operand;
    }

    /**
     * Clears the LIR operand associated with this instruction.
     */
    public final void clearOperand() {
        this.operand = CiValue.IllegalValue;
    }

    /**
     * Computes the exact type of the result of this instruction, if possible.
     * @return the exact type of the result of this instruction, if it is known; {@code null} otherwise
     */
    public RiType exactType() {
        return null; // default: unknown exact type
    }

    /**
     * Computes the declared type of the result of this instruction, if possible.
     * @return the declared type of the result of this instruction, if it is known; {@code null} otherwise
     */
    public RiType declaredType() {
        return null; // default: unknown declared type
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("#");
        builder.append(id());
        builder.append(' ');
        if (id() < 10) {
            builder.append(' ');
        }
        builder.append(getClass().getSimpleName());
        builder.append(" [").append(flagsToString()).append("]");
        return builder.toString();
    }

    public String flagsToString() {
        StringBuilder sb = new StringBuilder();
        return sb.toString();
    }

    /**
     * This method supports the visitor pattern by accepting a visitor and calling the
     * appropriate {@code visit()} method.
     *
     * @param v the visitor to accept
     */
    public void accept(ValueVisitor v) {
        throw new IllegalStateException("No visit method for this node");
    }

    public void print(LogStream out) {
        out.print(this.getClass().toString());
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends Op> T lookup(Class<T> clazz) {
        if (clazz == LIRGenerator.LIRGeneratorOp.class) {
            return (T) LIRGenerator.DELEGATE_TO_VALUE_VISITOR;
        }
        return super.lookup(clazz);
    }


    @Override
    public Map<Object, Object> getDebugProperties() {
        Map<Object, Object> properties = super.getDebugProperties();
        properties.put("kind", kind.toString());
        properties.put("operand", operand == null ? "null" : operand.toString());
        return properties;
    }

    @Override
    public Iterable<? extends Node> dataUsages() {
        final Iterator<? extends Node> dataUsages = super.dataUsages().iterator();
        return new Iterable<Node>() {
            @Override
            public Iterator<Node> iterator() {
                return new StateSplit.FilteringIterator(dataUsages, FrameState.class);
            }
        };
    }
}
