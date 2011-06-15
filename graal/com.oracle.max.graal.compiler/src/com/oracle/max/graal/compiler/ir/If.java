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

import com.oracle.max.graal.compiler.debug.*;
import com.oracle.max.graal.graph.*;
import com.sun.cri.ci.*;

/**
 * The {@code If} instruction represents a branch that can go one of two directions
 * depending on the outcome of a comparison.
 */
public final class If extends BlockEnd {

    private static final int INPUT_COUNT = 1;
    private static final int INPUT_COMPARE = 0;

    private static final int SUCCESSOR_COUNT = 0;

    @Override
    protected int inputCount() {
        return super.inputCount() + INPUT_COUNT;
    }

    @Override
    protected int successorCount() {
        return super.successorCount() + SUCCESSOR_COUNT;
    }

    /**
     * The instruction that produces the first input to this comparison.
     */
     public Compare compare() {
        return (Compare) inputs().get(super.inputCount() + INPUT_COMPARE);
    }

    public Value setCompare(Compare n) {
        return (Value) inputs().set(super.inputCount() + INPUT_COMPARE, n);
    }

    public If(Compare compare, Graph graph) {
        super(CiKind.Illegal, 2, INPUT_COUNT, SUCCESSOR_COUNT, graph);
        setCompare(compare);
    }

    /**
     * Gets the block corresponding to the true successor.
     * @return the true successor
     */
    public Instruction trueSuccessor() {
        return blockSuccessor(0);
    }

    /**
     * Gets the block corresponding to the false successor.
     * @return the false successor
     */
    public Instruction falseSuccessor() {
        return blockSuccessor(1);
    }

    /**
     * Gets the block corresponding to the specified outcome of the branch.
     * @param istrue {@code true} if the true successor is requested, {@code false} otherwise
     * @return the corresponding successor
     */
    public Instruction successor(boolean istrue) {
        return blockSuccessor(istrue ? 0 : 1);
    }

    @Override
    public void accept(ValueVisitor v) {
        v.visitIf(this);
    }

    @Override
    public void print(LogStream out) {
        out.print("if ").
        print(compare().x()).
        print(' ').
        print(compare().condition().operator).
        print(' ').
        print(compare().y()).
        print(" then ").
        print(blockSuccessors().get(0)).
        print(" else ").
        print(blockSuccessors().get(1));
    }

    @Override
    public String shortName() {
        return "If";
    }

    @Override
    public Node copy(Graph into) {
        return new If(null, into);
    }
}
