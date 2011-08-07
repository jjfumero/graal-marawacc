/*
 * Copyright (c) 2009, 2010, Oracle and/or its affiliates. All rights reserved.
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

import com.oracle.max.graal.graph.*;
import com.sun.cri.ci.*;

/**
 * The {@code Switch} class is the base of both lookup and table switches.
 */
public abstract class Switch extends ControlSplit {

    @Input    private Value value;

    public Value value() {
        return value;
    }

    public void setValue(Value x) {
        updateUsages(value, x);
        value = x;
    }

    /**
     * Constructs a new Switch.
     * @param value the instruction that provides the value to be switched over
     * @param successors the list of successors of this switch
     * @param stateAfter the state after the switch
     * @param graph
     */
    public Switch(Value value, List<? extends FixedNode> successors, double[] probability, int inputCount, int successorCount, Graph graph) {
        super(CiKind.Illegal, successors, probability, graph);
        setValue(value);
    }

    /**
     * Gets the number of cases that this switch covers (excluding the default case).
     * @return the number of cases
     */
    public int numberOfCases() {
        return blockSuccessorCount() - 1;
    }

}
