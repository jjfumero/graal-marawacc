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

import com.oracle.truffle.api.codegen.*;
import com.oracle.truffle.api.intrinsics.*;

public abstract class ArithmeticNode extends BinaryNode {

    public ArithmeticNode(TypedNode left, TypedNode right) {
        super(left, right);
    }

    protected ArithmeticNode(ArithmeticNode node) {
        super(node);
    }

    @Generic
    public Object doGeneric(Object left, Object right) {
        throw new RuntimeException("Arithmetic not defined for types " + left.getClass().getSimpleName() + ", " + right.getClass().getSimpleName());
    }

    public abstract static class AddNode extends ArithmeticNode {

        public AddNode(TypedNode left, TypedNode right) {
            super(left, right);
        }

        protected AddNode(AddNode node) {
            super(node);
        }

        @Specialization(rewriteOn = ArithmeticException.class)
        int doInteger(int left, int right) {
            return ExactMath.addExact(left, right);
        }

        @Specialization
        BigInteger doBigInteger(BigInteger left, BigInteger right) {
            return left.add(right);
        }

        @Specialization
        String doStringDirect(String left, String right) {
            return left + right;
        }

        @Specialization(guards = "isString")
        String doString(Object left, Object right) {
            return left.toString() + right.toString();
        }
    }

    public abstract static class SubNode extends ArithmeticNode {

        public SubNode(TypedNode left, TypedNode right) {
            super(left, right);
        }

        protected SubNode(SubNode node) {
            super(node);
        }

        @Specialization(rewriteOn = ArithmeticException.class)
        int doInteger(int left, int right) {
            return ExactMath.subtractExact(left, right);
        }

        @Specialization
        BigInteger doBigInteger(BigInteger left, BigInteger right) {
            return left.subtract(right);
        }
    }

    public abstract static class DivNode extends ArithmeticNode {

        public DivNode(TypedNode left, TypedNode right) {
            super(left, right);
        }

        protected DivNode(DivNode node) {
            super(node);
        }

        @Specialization(rewriteOn = ArithmeticException.class)
        int doInteger(int left, int right) {
            return left / right;
        }

        @Specialization
        BigInteger doBigInteger(BigInteger left, BigInteger right) {
            return left.divide(right);
        }
    }

    public abstract static class MulNode extends ArithmeticNode {

        public MulNode(TypedNode left, TypedNode right) {
            super(left, right);
        }

        protected MulNode(MulNode node) {
            super(node);
        }

        @Specialization(rewriteOn = ArithmeticException.class)
        int doInteger(int left, int right) {
            return ExactMath.multiplyExact(left, right);
        }

        @Specialization
        BigInteger doBigInteger(BigInteger left, BigInteger right) {
            return left.multiply(right);
        }
    }

}
