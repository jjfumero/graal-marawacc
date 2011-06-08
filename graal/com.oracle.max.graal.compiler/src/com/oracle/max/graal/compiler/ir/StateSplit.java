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

import com.oracle.max.graal.compiler.value.*;
import com.oracle.max.graal.graph.*;
import com.sun.cri.ci.*;

/**
 * The {@code StateSplit} class is the abstract base class of all instructions
 * that store an immutable copy of the frame state.
 */
public abstract class StateSplit extends Instruction {

    private static final int INPUT_COUNT = 1;
    private static final int INPUT_STATE_BEFORE = 0;

    private static final int SUCCESSOR_COUNT = 1;
    private static final int SUCCESSOR_STATE_AFTER = 0;

    @Override
    protected int inputCount() {
        return super.inputCount() + INPUT_COUNT;
    }

    @Override
    protected int successorCount() {
        return super.successorCount() + SUCCESSOR_COUNT;
    }

    /**
     * The state for this instruction.
     */
    public FrameState stateBefore() {
        return (FrameState) inputs().get(super.inputCount() + INPUT_STATE_BEFORE);
    }

    public FrameState setStateBefore(FrameState n) {
        FrameState oldState = stateBefore();
        try {
            return (FrameState) inputs().set(super.inputCount() + INPUT_STATE_BEFORE, n);
        } finally {
            if (oldState != n && oldState != null) {
                oldState.delete();
            }
        }
    }

    /**
     * The state for this instruction.
     */
     @Override
    public FrameState stateAfter() {
        return (FrameState) successors().get(super.successorCount() + SUCCESSOR_STATE_AFTER);
    }

    public FrameState setStateAfter(FrameState n) {
        return (FrameState) successors().set(super.successorCount() + SUCCESSOR_STATE_AFTER, n);
    }

    /**
     * Creates a new state split with the specified value type.
     * @param kind the type of the value that this instruction produces
     * @param inputCount
     * @param successorCount
     * @param graph
     */
    public StateSplit(CiKind kind, int inputCount, int successorCount, Graph graph) {
        super(kind, inputCount + INPUT_COUNT, successorCount + SUCCESSOR_COUNT, graph);
    }

    public boolean needsStateAfter() {
        return true;
    }
}
