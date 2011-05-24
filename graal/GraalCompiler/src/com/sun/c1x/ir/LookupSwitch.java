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
package com.sun.c1x.ir;

import static com.sun.c1x.debug.InstructionPrinter.InstructionLineColumn.*;

import java.util.*;

import com.oracle.graal.graph.*;
import com.sun.c1x.debug.*;
import com.sun.c1x.value.*;

/**
 * The {@code LookupSwitch} instruction represents a lookup switch bytecode, which has a sorted
 * array of key values.
 */
public final class LookupSwitch extends Switch {

    private static final int INPUT_COUNT = 0;
    private static final int SUCCESSOR_COUNT = 0;

    final int[] keys;

    /**
     * Constructs a new LookupSwitch instruction.
     * @param value the instruction producing the value being switched on
     * @param successors the list of successors
     * @param keys the list of keys, sorted
     * @param stateAfter the state after the switch
     * @param graph
     */
    public LookupSwitch(Value value, List<? extends Instruction> successors, int[] keys, Graph graph) {
        super(value, successors, INPUT_COUNT, SUCCESSOR_COUNT, graph);
        this.keys = keys;
    }

    /**
     * Gets the key at the specified index.
     * @param i the index
     * @return the key at that index
     */
    public int keyAt(int i) {
        return keys[i];
    }

    public int keysLength() {
        return keys.length;
    }

    @Override
    public void accept(ValueVisitor v) {
        v.visitLookupSwitch(this);
    }

    @Override
    public void print(LogStream out) {
        out.print("lookupswitch ");
        out.println(value());
        int l = numberOfCases();
        for (int i = 0; i < l; i++) {
            INSTRUCTION.advance(out);
            out.printf("case %5d: B%d%n", keyAt(i), blockSuccessors().get(i).blockID);
        }
        INSTRUCTION.advance(out);
        out.print("default   : B").print(defaultSuccessor().blockID);
    }
}
