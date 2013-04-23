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
package com.oracle.truffle.sl.nodes;

import java.math.*;

import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;
import com.oracle.truffle.sl.*;

public abstract class TypedNode extends ConditionNode {

    @Override
    public final boolean executeCondition(VirtualFrame frame) {
        try {
            return executeBoolean(frame);
        } catch (UnexpectedResultException ex) {
            throw new RuntimeException("Illegal type for condition: " + ex.getResult().getClass().getSimpleName());
        }
    }

    public abstract Object executeGeneric(VirtualFrame frame);

    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        return SLTypesGen.SLTYPES.expectBoolean(executeGeneric(frame));
    }

    public int executeInteger(VirtualFrame frame) throws UnexpectedResultException {
        return SLTypesGen.SLTYPES.expectInteger(executeGeneric(frame));
    }

    public BigInteger executeBigInteger(VirtualFrame frame) throws UnexpectedResultException {
        return SLTypesGen.SLTYPES.expectBigInteger(executeGeneric(frame));
    }

    public String executeString(VirtualFrame frame) throws UnexpectedResultException {
        return SLTypesGen.SLTYPES.expectString(executeGeneric(frame));
    }

    @Override
    public void executeVoid(VirtualFrame frame) {
        executeGeneric(frame);
    }

    public boolean isString(Object a, Object b) {
        return a instanceof String || b instanceof String;
    }

    @SuppressWarnings("unused")
    public Object executeEvaluated(VirtualFrame frame, Object val1) {
        return executeGeneric(frame);
    }

    @SuppressWarnings("unused")
    public Object executeEvaluated(VirtualFrame frame, Object val1, Object val2) {
        return executeEvaluated(frame, val1);
    }

    @SuppressWarnings("unused")
    public Object executeEvaluated(VirtualFrame frame, Object val1, Object val2, Object val3) {
        return executeEvaluated(frame, val1, val2);
    }

}
