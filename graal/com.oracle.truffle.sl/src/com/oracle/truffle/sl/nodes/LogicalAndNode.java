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

import com.oracle.truffle.api.dsl.*;

@SuppressWarnings("unused")
public abstract class LogicalAndNode extends BinaryNode {

    @ShortCircuit("rightNode")
    public boolean needsRightNode(boolean left) {
        return left;
    }

    @ShortCircuit("rightNode")
    public boolean needsRightNode(Object left) {
        return left instanceof Boolean && (Boolean) left;
    }

    @Specialization
    public boolean doBoolean(boolean left, boolean hasRight, boolean right) {
        return hasRight && right;
    }

    @Generic
    public Object doGeneric(Object left, boolean hasRight, Object right) {
        throw new RuntimeException("operation not defined for type " + left.getClass().getSimpleName());
    }
}
