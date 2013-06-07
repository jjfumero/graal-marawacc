/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.api.code;

import java.io.*;

import com.oracle.graal.api.meta.*;

/**
 * Represents the Java bytecode frame state(s) at a given position including {@link Value locations}
 * where to find the local variables, operand stack values and locked objects of the bytecode
 * frame(s).
 */
public class BytecodeFrame extends BytecodePosition implements Serializable {

    private static final long serialVersionUID = -345025397165977565L;

    /**
     * An array of values representing how to reconstruct the state of the Java frame. This is array
     * is partitioned as follows:
     * <p>
     * <table border="1" cellpadding="5" frame="void", rules="all">
     * <tr>
     * <th>Start index (inclusive)</th>
     * <th>End index (exclusive)</th>
     * <th>Description</th>
     * </tr>
     * <tr>
     * <td>0</td>
     * <td>numLocals</td>
     * <td>Local variables</td>
     * </tr>
     * <tr>
     * <td>numLocals</td>
     * <td>numLocals + numStack</td>
     * <td>Operand stack</td>
     * </tr>
     * <tr>
     * <td>numLocals + numStack</td>
     * <td>values.length</td>
     * <td>Locked objects</td>
     * </tr>
     * </table>
     * <p>
     * Note that the number of locals and the number of stack slots may be smaller than the maximum
     * number of locals and stack slots as specified in the compiled method.
     */
    public final Value[] values;

    /**
     * The number of locals in the values array.
     */
    public final int numLocals;

    /**
     * The number of stack slots in the values array.
     */
    public final int numStack;

    /**
     * The number of locks in the values array.
     */
    public final int numLocks;

    /**
     * True if this is a position inside an exception handler before the exception object has been
     * consumed. In this case, {@link #numStack == 1} and {@link #getStackValue(int)
     * getStackValue(0)} is the location of the exception object. If deoptimization happens at this
     * position, the interpreter will rethrow the exception instead of executing the bytecode
     * instruction at this position.
     */
    public final boolean rethrowException;

    public final boolean duringCall;

    /**
     * Creates a new frame object.
     * 
     * @param caller the caller frame (which may be {@code null})
     * @param method the method
     * @param bci a BCI within the method
     * @param rethrowException specifies if the VM should re-throw the pending exception when
     *            deopt'ing using this frame
     * @param values the frame state {@link #values}
     * @param numLocals the number of local variables
     * @param numStack the depth of the stack
     * @param numLocks the number of locked objects
     */
    public BytecodeFrame(BytecodeFrame caller, ResolvedJavaMethod method, int bci, boolean rethrowException, boolean duringCall, Value[] values, int numLocals, int numStack, int numLocks) {
        super(caller, method, bci);
        assert values != null;
        this.rethrowException = rethrowException;
        this.duringCall = duringCall;
        this.values = values;
        this.numLocals = numLocals;
        this.numStack = numStack;
        this.numLocks = numLocks;
        assert !rethrowException || numStack == 1 : "must have exception on top of the stack";
    }

    /**
     * Gets the value representing the specified local variable.
     * 
     * @param i the local variable index
     * @return the value that can be used to reconstruct the local's current value
     */
    public Value getLocalValue(int i) {
        return values[i];
    }

    /**
     * Gets the value representing the specified stack slot.
     * 
     * @param i the stack index
     * @return the value that can be used to reconstruct the stack slot's current value
     */
    public Value getStackValue(int i) {
        return values[i + numLocals];
    }

    /**
     * Gets the value representing the specified lock.
     * 
     * @param i the lock index
     * @return the value that can be used to reconstruct the lock's current value
     */
    public Value getLockValue(int i) {
        return values[i + numLocals + numStack];
    }

    /**
     * Gets the caller of this frame.
     * 
     * @return {@code null} if this frame has no caller
     */
    public BytecodeFrame caller() {
        return (BytecodeFrame) getCaller();
    }

    @Override
    public String toString() {
        return CodeUtil.append(new StringBuilder(100), this).toString();
    }
}
