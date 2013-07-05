/*
 * Copyright (c) 2012, 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.compiler.test.inlining;

import static org.junit.Assert.*;

import java.lang.reflect.*;
import java.util.concurrent.*;

import org.junit.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.compiler.test.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.test.*;

public class InliningTest extends GraalCompilerTest {

    @Test
    public void testInvokeStaticInlining() {
        assertInlined(getGraph("invokeStaticSnippet", false));
        assertInlined(getGraph("invokeStaticOnInstanceSnippet", false));
    }

    @SuppressWarnings("all")
    public static Boolean invokeStaticSnippet(boolean value) {
        return Boolean.valueOf(value);
    }

    @SuppressWarnings("all")
    public static Boolean invokeStaticOnInstanceSnippet(Boolean obj, boolean value) {
        return obj.valueOf(value);
    }

    @Test
    public void testStaticBindableInlining() {
        assertInlined(getGraph("invokeConstructorSnippet", false));
        assertInlined(getGraph("invokeFinalMethodSnippet", false));
        assertInlined(getGraph("invokeMethodOnFinalClassSnippet", false));
        assertInlined(getGraph("invokeMethodOnStaticFinalFieldSnippet", false));
    }

    @Ignore("would need read elimination/EA before inlining")
    @Test
    public void testDependentStaticBindableInlining() {
        assertInlined(getGraph("invokeMethodOnFinalFieldSnippet", false));
        assertInlined(getGraph("invokeMethodOnFieldSnippet", false));
    }

    @LongTest
    public void testStaticBindableInliningIP() {
        assertManyMethodInfopoints(assertInlined(getGraph("invokeConstructorSnippet", true)));
        assertManyMethodInfopoints(assertInlined(getGraph("invokeFinalMethodSnippet", true)));
        assertManyMethodInfopoints(assertInlined(getGraph("invokeMethodOnFinalClassSnippet", true)));
        assertManyMethodInfopoints(assertInlined(getGraph("invokeMethodOnStaticFinalFieldSnippet", true)));
    }

    @Ignore("would need read elimination/EA before inlining")
    @LongTest
    public void testDependentStaticBindableInliningIP() {
        assertManyMethodInfopoints(assertInlined(getGraph("invokeMethodOnFinalFieldSnippet", true)));
        assertManyMethodInfopoints(assertInlined(getGraph("invokeMethodOnFieldSnippet", true)));
    }

    @SuppressWarnings("all")
    public static Object invokeConstructorSnippet(int value) {
        return new SuperClass(value);
    }

    @SuppressWarnings("all")
    public static int invokeFinalMethodSnippet(SuperClass superClass, SubClassA subClassA, FinalSubClass finalSubClass) {
        return superClass.publicFinalMethod() + subClassA.publicFinalMethod() + finalSubClass.publicFinalMethod() + superClass.protectedFinalMethod() + subClassA.protectedFinalMethod() +
                        finalSubClass.protectedFinalMethod();
    }

    @SuppressWarnings("all")
    public static int invokeMethodOnFinalClassSnippet(FinalSubClass finalSubClass) {
        return finalSubClass.publicFinalMethod() + finalSubClass.publicNotOverriddenMethod() + finalSubClass.publicOverriddenMethod() + finalSubClass.protectedFinalMethod() +
                        finalSubClass.protectedNotOverriddenMethod() + finalSubClass.protectedOverriddenMethod();
    }

    @SuppressWarnings("all")
    public static int invokeMethodOnStaticFinalFieldSnippet() {
        return StaticFinalFields.NumberStaticFinalField.intValue() + StaticFinalFields.SuperClassStaticFinalField.publicOverriddenMethod() +
                        StaticFinalFields.FinalSubClassStaticFinalField.publicOverriddenMethod() + StaticFinalFields.SingleImplementorStaticFinalField.publicOverriddenMethod() +
                        StaticFinalFields.MultipleImplementorsStaticFinalField.publicOverriddenMethod() + StaticFinalFields.SubClassAStaticFinalField.publicOverriddenMethod() +
                        StaticFinalFields.SubClassBStaticFinalField.publicOverriddenMethod() + StaticFinalFields.SubClassCStaticFinalField.publicOverriddenMethod();
    }

    @SuppressWarnings("all")
    public static int invokeMethodOnFinalFieldSnippet() {
        FinalFields fields = new FinalFields();
        return fields.numberFinalField.intValue() + fields.superClassFinalField.publicOverriddenMethod() + fields.finalSubClassFinalField.publicOverriddenMethod() +
                        fields.singleImplementorFinalField.publicOverriddenMethod() + fields.multipleImplementorsFinalField.publicOverriddenMethod() +
                        fields.subClassAFinalField.publicOverriddenMethod() + fields.subClassBFinalField.publicOverriddenMethod() + fields.subClassCFinalField.publicOverriddenMethod();
    }

    @SuppressWarnings("all")
    public static int invokeMethodOnFieldSnippet() {
        Fields fields = new Fields();
        return fields.numberField.intValue() + fields.superClassField.publicOverriddenMethod() + fields.finalSubClassField.publicOverriddenMethod() +
                        fields.singleImplementorField.publicOverriddenMethod() + fields.multipleImplementorsField.publicOverriddenMethod() + fields.subClassAField.publicOverriddenMethod() +
                        fields.subClassBField.publicOverriddenMethod() + fields.subClassCField.publicOverriddenMethod();
    }

    public interface Attributes {

        int getLength();
    }

    public class NullAttributes implements Attributes {

        @Override
        public int getLength() {
            return 0;
        }

    }

    public class TenAttributes implements Attributes {

        @Override
        public int getLength() {
            return 10;
        }

    }

    public int getAttributesLength(Attributes a) {
        return a.getLength();
    }

    @Test
    public void testGuardedInline() {
        NullAttributes nullAttributes = new NullAttributes();
        for (int i = 0; i < 10000; i++) {
            getAttributesLength(nullAttributes);
        }
        getAttributesLength(new TenAttributes());

        test("getAttributesLength", nullAttributes);
        test("getAttributesLength", (Object) null);
    }

    @Test
    public void testClassHierarchyAnalysis() {
        assertInlined(getGraph("invokeLeafClassMethodSnippet", false));
        assertInlined(getGraph("invokeConcreteMethodSnippet", false));
        assertInlined(getGraph("invokeSingleImplementorInterfaceSnippet", false));
        // assertInlined(getGraph("invokeConcreteInterfaceMethodSnippet", false));

        assertNotInlined(getGraph("invokeOverriddenPublicMethodSnippet", false));
        assertNotInlined(getGraph("invokeOverriddenProtectedMethodSnippet", false));
        assertNotInlined(getGraph("invokeOverriddenInterfaceMethodSnippet", false));
    }

    @LongTest
    public void testClassHierarchyAnalysisIP() {
        assertManyMethodInfopoints(assertInlined(getGraph("invokeLeafClassMethodSnippet", true)));
        assertManyMethodInfopoints(assertInlined(getGraph("invokeConcreteMethodSnippet", true)));
        assertManyMethodInfopoints(assertInlined(getGraph("invokeSingleImplementorInterfaceSnippet", true)));
        //@formatter:off
        // assertInlineInfopoints(assertInlined(getGraph("invokeConcreteInterfaceMethodSnippet", true)));
        //@formatter:on

        assertFewMethodInfopoints(assertNotInlined(getGraph("invokeOverriddenPublicMethodSnippet", true)));
        assertFewMethodInfopoints(assertNotInlined(getGraph("invokeOverriddenProtectedMethodSnippet", true)));
        assertFewMethodInfopoints(assertNotInlined(getGraph("invokeOverriddenInterfaceMethodSnippet", true)));
    }

    @SuppressWarnings("all")
    public static int invokeLeafClassMethodSnippet(SubClassA subClassA) {
        return subClassA.publicFinalMethod() + subClassA.publicNotOverriddenMethod() + subClassA.publicOverriddenMethod();
    }

    @SuppressWarnings("all")
    public static int invokeConcreteMethodSnippet(SuperClass superClass) {
        return superClass.publicNotOverriddenMethod() + superClass.protectedNotOverriddenMethod();
    }

    @SuppressWarnings("all")
    public static int invokeSingleImplementorInterfaceSnippet(SingleImplementorInterface testInterface) {
        return testInterface.publicNotOverriddenMethod() + testInterface.publicOverriddenMethod();
    }

    @SuppressWarnings("all")
    public static int invokeConcreteInterfaceMethodSnippet(MultipleImplementorsInterface testInterface) {
        return testInterface.publicNotOverriddenMethod();
    }

    @SuppressWarnings("all")
    public static int invokeOverriddenInterfaceMethodSnippet(MultipleImplementorsInterface testInterface) {
        return testInterface.publicOverriddenMethod();
    }

    @SuppressWarnings("all")
    public static int invokeOverriddenPublicMethodSnippet(SuperClass superClass) {
        return superClass.publicOverriddenMethod();
    }

    @SuppressWarnings("all")
    public static int invokeOverriddenProtectedMethodSnippet(SuperClass superClass) {
        return superClass.protectedOverriddenMethod();
    }

    private StructuredGraph getGraph(final String snippet, final boolean eagerInfopointMode) {
        return Debug.scope("InliningTest", new DebugDumpScope(snippet), new Callable<StructuredGraph>() {

            @Override
            public StructuredGraph call() {
                Method method = getMethod(snippet);
                StructuredGraph graph = eagerInfopointMode ? parseDebug(method) : parse(method);
                PhasePlan phasePlan = getDefaultPhasePlan(eagerInfopointMode);
                Assumptions assumptions = new Assumptions(true);
                Debug.dump(graph, "Graph");
                new CanonicalizerPhase.Instance(runtime(), assumptions, true).apply(graph);
                new InliningPhase(runtime(), null, replacements, assumptions, null, phasePlan, OptimisticOptimizations.ALL).apply(graph);
                Debug.dump(graph, "Graph");
                new CanonicalizerPhase.Instance(runtime(), assumptions, true).apply(graph);
                new DeadCodeEliminationPhase().apply(graph);
                return graph;
            }
        });
    }

    private static StructuredGraph assertInlined(StructuredGraph graph) {
        return assertNotInGraph(graph, Invoke.class);
    }

    private static StructuredGraph assertNotInlined(StructuredGraph graph) {
        return assertInGraph(graph, Invoke.class);
    }

    private static StructuredGraph assertNotInGraph(StructuredGraph graph, Class<?> clazz) {
        for (Node node : graph.getNodes()) {
            if (clazz.isInstance(node)) {
                fail(node.toString());
            }
        }
        return graph;
    }

    private static StructuredGraph assertInGraph(StructuredGraph graph, Class<?> clazz) {
        for (Node node : graph.getNodes()) {
            if (clazz.isInstance(node)) {
                return graph;
            }
        }
        fail("Graph does not contain a node of class " + clazz.getName());
        return graph;
    }

    private static int[] countMethodInfopoints(StructuredGraph graph) {
        int start = 0;
        int end = 0;
        for (InfopointNode ipn : graph.getNodes(InfopointNode.class)) {
            if (ipn.reason == InfopointReason.METHOD_START) {
                ++start;
            } else if (ipn.reason == InfopointReason.METHOD_END) {
                ++end;
            }
        }
        return new int[]{start, end};
    }

    private static StructuredGraph assertManyMethodInfopoints(StructuredGraph graph) {
        int[] counts = countMethodInfopoints(graph);
        if (counts[0] <= 1 || counts[1] <= 1) {
            fail(String.format("Graph contains too few required method boundary infopoints: %d starts, %d ends.", counts[0], counts[1]));
        }
        return graph;
    }

    private static StructuredGraph assertFewMethodInfopoints(StructuredGraph graph) {
        int[] counts = countMethodInfopoints(graph);
        if (counts[0] > 1 || counts[1] > 1) {
            fail(String.format("Graph contains too many method boundary infopoints: %d starts, %d ends.", counts[0], counts[1]));
        }
        return graph;
    }

    // some interfaces and classes for testing
    private interface MultipleImplementorsInterface {

        int publicNotOverriddenMethod();

        int publicOverriddenMethod();
    }

    private interface SingleImplementorInterface {

        int publicNotOverriddenMethod();

        int publicOverriddenMethod();
    }

    private static class SuperClass implements MultipleImplementorsInterface {

        protected int value;

        public SuperClass(int value) {
            this.value = value;
        }

        public int publicNotOverriddenMethod() {
            return value;
        }

        public int publicOverriddenMethod() {
            return value;
        }

        protected int protectedNotOverriddenMethod() {
            return value;
        }

        protected int protectedOverriddenMethod() {
            return value;
        }

        public final int publicFinalMethod() {
            return value + 255;
        }

        protected final int protectedFinalMethod() {
            return value + 255;
        }
    }

    private static class SubClassA extends SuperClass implements SingleImplementorInterface {

        public SubClassA(int value) {
            super(value);
        }

        @Override
        public int publicOverriddenMethod() {
            return value + 2;
        }

        @Override
        protected int protectedOverriddenMethod() {
            return value * 2;
        }
    }

    private static class SubClassB extends SuperClass {

        public SubClassB(int value) {
            super(value);
        }

        @Override
        public int publicOverriddenMethod() {
            return value + 3;
        }

        @Override
        protected int protectedOverriddenMethod() {
            return value * 3;
        }
    }

    private static class SubClassC extends SuperClass {

        public SubClassC(int value) {
            super(value);
        }

        @Override
        public int publicOverriddenMethod() {
            return value + 4;
        }

        @Override
        protected int protectedOverriddenMethod() {
            return value * 4;
        }
    }

    private static final class FinalSubClass extends SuperClass {

        public FinalSubClass(int value) {
            super(value);
        }

        @Override
        public int publicOverriddenMethod() {
            return value + 5;
        }

        @Override
        protected int protectedOverriddenMethod() {
            return value * 5;
        }
    }

    private static final class StaticFinalFields {

        private static final Number NumberStaticFinalField = new Integer(1);
        private static final SuperClass SuperClassStaticFinalField = new SubClassA(2);
        private static final FinalSubClass FinalSubClassStaticFinalField = new FinalSubClass(3);
        private static final SingleImplementorInterface SingleImplementorStaticFinalField = new SubClassA(4);
        private static final MultipleImplementorsInterface MultipleImplementorsStaticFinalField = new SubClassC(5);
        private static final SubClassA SubClassAStaticFinalField = new SubClassA(6);
        private static final SubClassB SubClassBStaticFinalField = new SubClassB(7);
        private static final SubClassC SubClassCStaticFinalField = new SubClassC(8);
    }

    private static final class FinalFields {

        private final Number numberFinalField = new Integer(1);
        private final SuperClass superClassFinalField = new SubClassA(2);
        private final FinalSubClass finalSubClassFinalField = new FinalSubClass(3);
        private final SingleImplementorInterface singleImplementorFinalField = new SubClassA(4);
        private final MultipleImplementorsInterface multipleImplementorsFinalField = new SubClassC(5);
        private final SubClassA subClassAFinalField = new SubClassA(6);
        private final SubClassB subClassBFinalField = new SubClassB(7);
        private final SubClassC subClassCFinalField = new SubClassC(8);
    }

    private static final class Fields {

        private Number numberField = new Integer(1);
        private SuperClass superClassField = new SubClassA(2);
        private FinalSubClass finalSubClassField = new FinalSubClass(3);
        private SingleImplementorInterface singleImplementorField = new SubClassA(4);
        private MultipleImplementorsInterface multipleImplementorsField = new SubClassC(5);
        private SubClassA subClassAField = new SubClassA(6);
        private SubClassB subClassBField = new SubClassB(7);
        private SubClassC subClassCField = new SubClassC(8);
    }
}
