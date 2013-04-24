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
package com.oracle.truffle.api.codegen.test;

import org.junit.*;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.codegen.*;
import com.oracle.truffle.api.codegen.test.AssumptionsTestFactory.DerivedAssumptionNodeFactory;
import com.oracle.truffle.api.codegen.test.AssumptionsTestFactory.DerivedAssumptionRedeclaredNodeFactory;
import com.oracle.truffle.api.codegen.test.AssumptionsTestFactory.MultipleAssumptionsNodeFactory;
import com.oracle.truffle.api.codegen.test.AssumptionsTestFactory.SingleAssumptionNodeFactory;
import com.oracle.truffle.api.codegen.test.TypeSystemTest.TestRootNode;
import com.oracle.truffle.api.codegen.test.TypeSystemTest.ValueNode;

public class AssumptionsTest {

    @Test
    public void testSingleAssumption() {
        Assumption assumption = Truffle.getRuntime().createAssumption();
        TestRootNode<?> root = TestHelper.create(SingleAssumptionNodeFactory.getInstance(), assumption);

        Assert.assertEquals(42, TestHelper.executeWith(root));
        assumption.invalidate();
        Assert.assertEquals("42", TestHelper.executeWith(root));
    }

    @NodeAssumptions("assumption")
    abstract static class SingleAssumptionNode extends ValueNode {

        @Specialization(order = 0, assumptions = "assumption")
        int doInt() {
            return 42;
        }

        @Specialization
        Object doObject() {
            return "42";
        }
    }

    @Test
    public void testMultipleAssumption() {
        Assumption assumption1 = Truffle.getRuntime().createAssumption();
        Assumption assumption2 = Truffle.getRuntime().createAssumption();
        TestRootNode<?> root = TestHelper.create(MultipleAssumptionsNodeFactory.getInstance(), assumption1, assumption2);

        Assert.assertEquals(42, TestHelper.executeWith(root));
        assumption2.invalidate();
        Assert.assertEquals("42", TestHelper.executeWith(root));
        assumption1.invalidate();
        Assert.assertEquals("43", TestHelper.executeWith(root));
    }

    @NodeAssumptions({"assumption1", "assumption2"})
    abstract static class MultipleAssumptionsNode extends ValueNode {

        @Specialization(assumptions = {"assumption1", "assumption2"})
        int doInt() {
            return 42;
        }

        @Specialization(assumptions = "assumption1")
        Object doObject() {
            return "42";
        }

        @Generic
        Object doGeneric() {
            return "43";
        }
    }

    @Test
    public void testDerivedAssumption() {
        Assumption additionalAssumption = Truffle.getRuntime().createAssumption();
        Assumption assumption = Truffle.getRuntime().createAssumption();
        TestRootNode<?> root = TestHelper.create(DerivedAssumptionNodeFactory.getInstance(), assumption, additionalAssumption);

        Assert.assertEquals(42, TestHelper.executeWith(root));
        assumption.invalidate();
        Assert.assertEquals(43, TestHelper.executeWith(root));
        additionalAssumption.invalidate();
        Assert.assertEquals("42", TestHelper.executeWith(root));
    }

    @NodeAssumptions({"additionalAssumption"})
    abstract static class DerivedAssumptionNode extends SingleAssumptionNode {

        @Specialization(order = 1, assumptions = "additionalAssumption")
        int doIntDerived() {
            return 43;
        }
    }

    @Test
    public void testDerivedAssumptionRedeclared() {
        Assumption additionalAssumption = Truffle.getRuntime().createAssumption();
        Assumption assumption = Truffle.getRuntime().createAssumption();
        TestRootNode<?> root = TestHelper.create(DerivedAssumptionRedeclaredNodeFactory.getInstance(), additionalAssumption, assumption);

        Assert.assertEquals(42, TestHelper.executeWith(root));
        assumption.invalidate();
        Assert.assertEquals(43, TestHelper.executeWith(root));
        additionalAssumption.invalidate();
        Assert.assertEquals("42", TestHelper.executeWith(root));
    }

    @NodeAssumptions({"additionalAssumption", "assumption"})
    abstract static class DerivedAssumptionRedeclaredNode extends SingleAssumptionNode {

        @Specialization(order = 1, assumptions = "additionalAssumption")
        int doIntDerived() {
            return 43;
        }

    }

}
