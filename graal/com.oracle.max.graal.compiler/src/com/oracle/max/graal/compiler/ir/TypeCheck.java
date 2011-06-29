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

import com.oracle.max.graal.graph.*;
import com.sun.cri.ci.*;
import com.sun.cri.ri.*;

/**
 * The {@code TypeCheck} instruction is the base class of casts and instanceof tests.
 */
public abstract class TypeCheck extends FloatingNode {

    private static final int INPUT_COUNT = 2;
    private static final int INPUT_OBJECT = 0;
    private static final int INPUT_TARGET_CLASS_INSTRUCTION = 1;

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
     * The instruction which produces the object input.
     */
     public Value object() {
        return (Value) inputs().get(super.inputCount() + INPUT_OBJECT);
    }

    public Value setObject(Value n) {
        return (Value) inputs().set(super.inputCount() + INPUT_OBJECT, n);
    }

    /**
     * The instruction that loads the target class object that is used by this checkcast.
     */
     public Constant targetClassInstruction() {
        return (Constant) inputs().get(super.inputCount() + INPUT_TARGET_CLASS_INSTRUCTION);
    }

    private void setTargetClassInstruction(Constant n) {
        inputs().set(super.inputCount() + INPUT_TARGET_CLASS_INSTRUCTION, n);
    }


    /**
     * Gets the target class, i.e. the class being cast to, or the class being tested against.
     * @return the target class
     */
    public RiType targetClass() {
        return (RiType) targetClassInstruction().asConstant().asObject();
    }

    /**
     * Creates a new TypeCheck instruction.
     * @param targetClass the class which is being casted to or checked against
     * @param object the instruction which produces the object
     * @param kind the result type of this instruction
     * @param inputCount
     * @param successorCount
     * @param graph
     */
    public TypeCheck(Constant targetClassInstruction, Value object, CiKind kind, int inputCount, int successorCount, Graph graph) {
        super(kind, inputCount + INPUT_COUNT, successorCount + SUCCESSOR_COUNT, graph);
        setObject(object);
        setTargetClassInstruction(targetClassInstruction);
    }
}
