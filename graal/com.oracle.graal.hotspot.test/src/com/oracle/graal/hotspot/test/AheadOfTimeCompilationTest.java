/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.test;

import static com.oracle.graal.api.code.CodeUtil.*;
import static com.oracle.graal.phases.GraalOptions.*;

import org.junit.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.CallingConvention.Type;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.runtime.*;
import com.oracle.graal.compiler.*;
import com.oracle.graal.compiler.test.*;
import com.oracle.graal.graph.iterators.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.java.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.PhasePlan.PhasePosition;
import com.oracle.graal.phases.tiers.*;

/**
 * use
 * 
 * <pre>
 * mx unittest AheadOfTimeCompilationTest @-XX:CompileCommand='print,*AheadOfTimeCompilationTest.*'
 * </pre>
 * 
 * to print disassembly.
 */
public class AheadOfTimeCompilationTest extends GraalCompilerTest {

    public static final Object STATICFINALOBJECT = new Object();
    public static final String STATICFINALSTRING = "test string";

    public static Object getStaticFinalObject() {
        return AheadOfTimeCompilationTest.STATICFINALOBJECT;
    }

    @Test
    public void testStaticFinalObjectAOT() {
        StructuredGraph result = compile("getStaticFinalObject", true);
        assertEquals(1, result.getNodes().filter(ConstantNode.class).count());
        assertEquals(runtime.getTarget().wordKind, result.getNodes().filter(ConstantNode.class).first().kind());
        assertEquals(2, result.getNodes(FloatingReadNode.class).count());
        assertEquals(0, result.getNodes().filter(ReadNode.class).count());
    }

    @Test
    public void testStaticFinalObject() {
        StructuredGraph result = compile("getStaticFinalObject", false);
        assertEquals(1, result.getNodes().filter(ConstantNode.class).count());
        assertEquals(Kind.Object, result.getNodes().filter(ConstantNode.class).first().kind());
        assertEquals(0, result.getNodes(FloatingReadNode.class).count());
        assertEquals(0, result.getNodes().filter(ReadNode.class).count());
    }

    public static Class getClassObject() {
        return AheadOfTimeCompilationTest.class;
    }

    @Test
    public void testClassObjectAOT() {
        StructuredGraph result = compile("getClassObject", true);

        NodeIterable<ConstantNode> filter = result.getNodes().filter(ConstantNode.class);
        assertEquals(1, filter.count());
        HotSpotResolvedObjectType type = (HotSpotResolvedObjectType) runtime.lookupJavaType(AheadOfTimeCompilationTest.class);
        assertEquals(type.klass(), filter.first().asConstant());

        assertEquals(1, result.getNodes(FloatingReadNode.class).count());
        assertEquals(0, result.getNodes().filter(ReadNode.class).count());
    }

    @Test
    public void testClassObject() {
        StructuredGraph result = compile("getClassObject", false);

        NodeIterable<ConstantNode> filter = result.getNodes().filter(ConstantNode.class);
        assertEquals(1, filter.count());
        Object mirror = filter.first().asConstant().asObject();
        assertEquals(Class.class, mirror.getClass());
        assertEquals(AheadOfTimeCompilationTest.class, mirror);

        assertEquals(0, result.getNodes(FloatingReadNode.class).count());
        assertEquals(0, result.getNodes().filter(ReadNode.class).count());
    }

    public static Class getPrimitiveClassObject() {
        return int.class;
    }

    @Test
    public void testPrimitiveClassObjectAOT() {
        StructuredGraph result = compile("getPrimitiveClassObject", true);
        NodeIterable<ConstantNode> filter = result.getNodes().filter(ConstantNode.class);
        assertEquals(1, filter.count());
        assertEquals(runtime.getTarget().wordKind, filter.first().kind());

        assertEquals(2, result.getNodes(FloatingReadNode.class).count());
        assertEquals(0, result.getNodes().filter(ReadNode.class).count());
    }

    @Test
    public void testPrimitiveClassObject() {
        StructuredGraph result = compile("getPrimitiveClassObject", false);
        NodeIterable<ConstantNode> filter = result.getNodes().filter(ConstantNode.class);
        assertEquals(1, filter.count());
        Object mirror = filter.first().asConstant().asObject();
        assertEquals(Class.class, mirror.getClass());
        assertEquals(Integer.TYPE, mirror);

        assertEquals(0, result.getNodes(FloatingReadNode.class).count());
        assertEquals(0, result.getNodes().filter(ReadNode.class).count());
    }

    public static String getStringObject() {
        return AheadOfTimeCompilationTest.STATICFINALSTRING;
    }

    @Test
    public void testStringObjectAOT() {
        // embedded strings are fine
        testStringObjectCommon(true);
    }

    @Test
    public void testStringObject() {
        testStringObjectCommon(false);
    }

    private void testStringObjectCommon(boolean compileAOT) {
        StructuredGraph result = compile("getStringObject", compileAOT);

        NodeIterable<ConstantNode> filter = result.getNodes().filter(ConstantNode.class);
        assertEquals(1, filter.count());
        Object mirror = filter.first().asConstant().asObject();
        assertEquals(String.class, mirror.getClass());
        assertEquals("test string", mirror);

        assertEquals(0, result.getNodes(FloatingReadNode.class).count());
        assertEquals(0, result.getNodes().filter(ReadNode.class).count());
    }

    public static Boolean getBoxedBoolean() {
        return Boolean.valueOf(true);
    }

    @Test
    @Ignore
    public void testBoxedBooleanAOT() {
        StructuredGraph result = compile("getBoxedBoolean", true);

        assertEquals(2, result.getNodes(FloatingReadNode.class).count());
        assertEquals(1, result.getNodes(UnsafeCastNode.class).count());
        assertEquals(1, result.getNodes().filter(ConstantNode.class).count());
        ConstantNode constant = result.getNodes().filter(ConstantNode.class).first();
        assertEquals(Kind.Long, constant.kind());
        assertEquals(((HotSpotResolvedObjectType) runtime.lookupJavaType(Boolean.class)).klass(), constant.asConstant());
    }

    @Test
    public void testBoxedBoolean() {
        StructuredGraph result = compile("getBoxedBoolean", false);
        assertEquals(0, result.getNodes(FloatingReadNode.class).count());
        assertEquals(0, result.getNodes(UnsafeCastNode.class).count());
        assertEquals(1, result.getNodes().filter(ConstantNode.class).count());
        ConstantNode constant = result.getNodes().filter(ConstantNode.class).first();
        assertEquals(Kind.Object, constant.kind());
        assertEquals(Boolean.TRUE, constant.asConstant().asObject());
    }

    private StructuredGraph compile(String test, boolean compileAOT) {
        StructuredGraph graph = parse(test);
        ResolvedJavaMethod method = graph.method();

        boolean originalSetting = AOTCompilation.getValue();
        AOTCompilation.setValue(compileAOT);
        PhasePlan phasePlan = new PhasePlan();
        GraphBuilderPhase graphBuilderPhase = new GraphBuilderPhase(runtime, GraphBuilderConfiguration.getDefault(), OptimisticOptimizations.ALL);
        phasePlan.addPhase(PhasePosition.AFTER_PARSING, graphBuilderPhase);
        CallingConvention cc = getCallingConvention(runtime, Type.JavaCallee, graph.method(), false);
        // create suites everytime, as we modify options for the compiler
        final Suites suitesLocal = Graal.getRequiredCapability(SuitesProvider.class).createSuites();
        final CompilationResult compResult = GraalCompiler.compileGraph(graph, cc, method, runtime, replacements, backend, runtime().getTarget(), null, phasePlan, OptimisticOptimizations.ALL,
                        new SpeculationLog(), suitesLocal, new CompilationResult());
        addMethod(method, compResult);

        AOTCompilation.setValue(originalSetting);

        return graph;
    }
}
