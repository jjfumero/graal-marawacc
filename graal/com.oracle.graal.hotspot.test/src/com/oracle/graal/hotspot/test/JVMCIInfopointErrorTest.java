/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.lir.LIRInstruction.OperandFlag.REG;
import static com.oracle.graal.lir.LIRInstruction.OperandFlag.STACK;

import java.util.function.Consumer;

import jdk.vm.ci.code.BytecodeFrame;
import jdk.vm.ci.code.CompilationResult;
import jdk.vm.ci.code.InfopointReason;
import jdk.vm.ci.code.VirtualObject;
import jdk.vm.ci.common.JVMCIError;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaValue;
import jdk.vm.ci.meta.LIRKind;
import jdk.vm.ci.meta.PlatformKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Value;

import org.junit.Test;

import com.oracle.graal.compiler.common.type.StampFactory;
import com.oracle.graal.compiler.test.GraalCompilerTest;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.DebugConfigScope;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.lir.FullInfopointOp;
import com.oracle.graal.lir.LIRFrameState;
import com.oracle.graal.lir.LIRInstruction;
import com.oracle.graal.lir.LIRInstructionClass;
import com.oracle.graal.lir.Variable;
import com.oracle.graal.lir.asm.CompilationResultBuilder;
import com.oracle.graal.lir.gen.LIRGeneratorTool;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.DeoptimizingFixedWithNextNode;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.spi.LIRLowerable;
import com.oracle.graal.nodes.spi.NodeLIRBuilderTool;

public class JVMCIInfopointErrorTest extends GraalCompilerTest {

    private static class ValueDef extends LIRInstruction {
        private static final LIRInstructionClass<ValueDef> TYPE = LIRInstructionClass.create(ValueDef.class);

        @Def({REG, STACK}) AllocatableValue value;

        public ValueDef(AllocatableValue value) {
            super(TYPE);
            this.value = value;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }
    }

    private static class ValueUse extends LIRInstruction {
        private static final LIRInstructionClass<ValueUse> TYPE = LIRInstructionClass.create(ValueUse.class);

        @Use({REG, STACK}) AllocatableValue value;

        public ValueUse(AllocatableValue value) {
            super(TYPE);
            this.value = value;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }
    }

    @NodeInfo
    private static class TestNode extends DeoptimizingFixedWithNextNode implements LIRLowerable {
        private static final NodeClass<TestNode> TYPE = NodeClass.create(TestNode.class);

        private final TestSpec spec;

        public TestNode(TestSpec spec) {
            super(TYPE, StampFactory.forVoid());
            this.spec = spec;
        }

        public boolean canDeoptimize() {
            return true;
        }

        public void generate(NodeLIRBuilderTool gen) {
            LIRGeneratorTool tool = gen.getLIRGeneratorTool();
            LIRFrameState state = gen.state(this);
            spec.spec(tool, state, st -> {
                tool.append(new FullInfopointOp(st, InfopointReason.SAFEPOINT));
            });
        }
    }

    @FunctionalInterface
    private interface TestSpec {
        void spec(LIRGeneratorTool tool, LIRFrameState state, Consumer<LIRFrameState> safepoint);
    }

    public static void testMethod() {
    }

    private void test(TestSpec spec) {
        ResolvedJavaMethod method = getResolvedJavaMethod("testMethod");

        StructuredGraph graph = parseForCompile(method);
        TestNode test = graph.add(new TestNode(spec));
        graph.addAfterFixed(graph.start(), test);

        CompilationResult compResult = compile(method, graph);
        getCodeCache().addCode(method, compResult, null, null);
    }

    @Test(expected = JVMCIError.class)
    public void testInvalidShortOop() {
        test((tool, state, safepoint) -> {
            PlatformKind kind = tool.target().arch.getPlatformKind(JavaKind.Short);
            LIRKind lirKind = LIRKind.reference(kind);

            Variable var = tool.newVariable(lirKind);
            tool.append(new ValueDef(var));
            safepoint.accept(state);
            tool.append(new ValueUse(var));
        });
    }

    @Test(expected = JVMCIError.class)
    public void testInvalidShortDerivedOop() {
        test((tool, state, safepoint) -> {
            Variable baseOop = tool.newVariable(tool.target().getLIRKind(JavaKind.Object));
            tool.append(new ValueDef(baseOop));

            PlatformKind kind = tool.target().arch.getPlatformKind(JavaKind.Short);
            LIRKind lirKind = LIRKind.derivedReference(kind, baseOop);

            Variable var = tool.newVariable(lirKind);
            tool.append(new ValueDef(var));
            safepoint.accept(state);
            tool.append(new ValueUse(var));
        });
    }

    private static LIRFrameState modifyTopFrame(LIRFrameState state, JavaValue[] values, JavaKind[] slotKinds, int locals, int stack, int locks) {
        return modifyTopFrame(state, null, values, slotKinds, locals, stack, locks);
    }

    private static LIRFrameState modifyTopFrame(LIRFrameState state, VirtualObject[] vobj, JavaValue[] values, JavaKind[] slotKinds, int locals, int stack, int locks) {
        BytecodeFrame top = state.topFrame;
        top = new BytecodeFrame(top.caller(), top.getMethod(), top.getBCI(), top.rethrowException, top.duringCall, values, slotKinds, locals, stack, locks);
        return new LIRFrameState(top, vobj, state.exceptionEdge);
    }

    @Test(expected = JVMCIError.class)
    public void testUnexpectedScopeValuesLength() {
        test((tool, state, safepoint) -> {
            LIRFrameState newState = modifyTopFrame(state, new JavaValue[]{JavaConstant.FALSE}, new JavaKind[0], 0, 0, 0);
            safepoint.accept(newState);
        });
    }

    @Test(expected = JVMCIError.class)
    public void testUnexpectedScopeSlotKindsLength() {
        test((tool, state, safepoint) -> {
            LIRFrameState newState = modifyTopFrame(state, new JavaValue[0], new JavaKind[]{JavaKind.Boolean}, 0, 0, 0);
            safepoint.accept(newState);
        });
    }

    @Test(expected = JVMCIError.class)
    public void testWrongMonitorType() {
        test((tool, state, safepoint) -> {
            LIRFrameState newState = modifyTopFrame(state, new JavaValue[]{JavaConstant.INT_0}, new JavaKind[]{}, 0, 0, 1);
            safepoint.accept(newState);
        });
    }

    @Test(expected = JVMCIError.class)
    public void testUnexpectedIllegalValue() {
        test((tool, state, safepoint) -> {
            LIRFrameState newState = modifyTopFrame(state, new JavaValue[]{Value.ILLEGAL}, new JavaKind[]{JavaKind.Int}, 1, 0, 0);
            safepoint.accept(newState);
        });
    }

    @Test(expected = JVMCIError.class)
    public void testUnexpectedTypeInRegister() {
        test((tool, state, safepoint) -> {
            Variable var = tool.newVariable(tool.target().getLIRKind(JavaKind.Int));
            tool.append(new ValueDef(var));
            LIRFrameState newState = modifyTopFrame(state, new JavaValue[]{var}, new JavaKind[]{JavaKind.Illegal}, 1, 0, 0);
            safepoint.accept(newState);
        });
    }

    @Test(expected = JVMCIError.class)
    public void testWrongConstantType() {
        test((tool, state, safepoint) -> {
            LIRFrameState newState = modifyTopFrame(state, new JavaValue[]{JavaConstant.INT_0}, new JavaKind[]{JavaKind.Object}, 1, 0, 0);
            safepoint.accept(newState);
        });
    }

    @Test(expected = JVMCIError.class)
    public void testUnsupportedConstantType() {
        test((tool, state, safepoint) -> {
            LIRFrameState newState = modifyTopFrame(state, new JavaValue[]{JavaConstant.forShort((short) 0)}, new JavaKind[]{JavaKind.Short}, 1, 0, 0);
            safepoint.accept(newState);
        });
    }

    @Test(expected = JVMCIError.class)
    public void testUnexpectedNull() {
        test((tool, state, safepoint) -> {
            LIRFrameState newState = modifyTopFrame(state, new JavaValue[]{JavaConstant.NULL_POINTER}, new JavaKind[]{JavaKind.Int}, 1, 0, 0);
            safepoint.accept(newState);
        });
    }

    @Test(expected = JVMCIError.class)
    public void testUnexpectedObject() {
        JavaValue wrapped = getSnippetReflection().forObject(this);
        test((tool, state, safepoint) -> {
            LIRFrameState newState = modifyTopFrame(state, new JavaValue[]{wrapped}, new JavaKind[]{JavaKind.Int}, 1, 0, 0);
            safepoint.accept(newState);
        });
    }

    private static class UnknownJavaValue implements JavaValue {
    }

    @SuppressWarnings("try")
    @Test(expected = Error.class)
    public void testUnknownJavaValue() {
        try (DebugConfigScope s = Debug.setConfig(Debug.silentConfig())) {
            /*
             * Expected: either AssertionError or JVMCIError, depending on whether the unit test run
             * is with assertions enabled or disabled.
             */
            test((tool, state, safepoint) -> {
                LIRFrameState newState = modifyTopFrame(state, new JavaValue[]{new UnknownJavaValue()}, new JavaKind[]{JavaKind.Int}, 1, 0, 0);
                safepoint.accept(newState);
            });
        }
    }

    @Test(expected = Error.class)
    public void testMissingIllegalAfterDouble() {
        /*
         * Expected: either AssertionError or JVMCIError, depending on whether the unit test run is
         * with assertions enabled or disabled.
         */
        test((tool, state, safepoint) -> {
            LIRFrameState newState = modifyTopFrame(state, new JavaValue[]{JavaConstant.DOUBLE_0, JavaConstant.INT_0}, new JavaKind[]{JavaKind.Double, JavaKind.Int}, 2, 0, 0);
            safepoint.accept(newState);
        });
    }

    @Test(expected = JVMCIError.class)
    public void testInvalidVirtualObjectId() {
        ResolvedJavaType obj = getMetaAccess().lookupJavaType(Object.class);
        test((tool, state, safepoint) -> {
            VirtualObject o = VirtualObject.get(obj, 5);
            o.setValues(new JavaValue[0], new JavaKind[0]);

            safepoint.accept(new LIRFrameState(state.topFrame, new VirtualObject[]{o}, state.exceptionEdge));
        });
    }

    @Test(expected = JVMCIError.class)
    public void testDuplicateVirtualObject() {
        ResolvedJavaType obj = getMetaAccess().lookupJavaType(Object.class);
        test((tool, state, safepoint) -> {
            VirtualObject o1 = VirtualObject.get(obj, 0);
            o1.setValues(new JavaValue[0], new JavaKind[0]);

            VirtualObject o2 = VirtualObject.get(obj, 0);
            o2.setValues(new JavaValue[0], new JavaKind[0]);

            safepoint.accept(new LIRFrameState(state.topFrame, new VirtualObject[]{o1, o2}, state.exceptionEdge));
        });
    }

    @Test(expected = JVMCIError.class)
    public void testUnexpectedVirtualObject() {
        ResolvedJavaType obj = getMetaAccess().lookupJavaType(Object.class);
        test((tool, state, safepoint) -> {
            VirtualObject o = VirtualObject.get(obj, 0);
            o.setValues(new JavaValue[0], new JavaKind[0]);

            LIRFrameState newState = modifyTopFrame(state, new VirtualObject[]{o}, new JavaValue[]{o}, new JavaKind[]{JavaKind.Int}, 1, 0, 0);
            safepoint.accept(newState);
        });
    }

    @Test(expected = JVMCIError.class)
    public void testUndefinedVirtualObject() {
        ResolvedJavaType obj = getMetaAccess().lookupJavaType(Object.class);
        test((tool, state, safepoint) -> {
            VirtualObject o0 = VirtualObject.get(obj, 0);
            o0.setValues(new JavaValue[0], new JavaKind[0]);

            VirtualObject o1 = VirtualObject.get(obj, 1);
            o1.setValues(new JavaValue[0], new JavaKind[0]);

            LIRFrameState newState = modifyTopFrame(state, new VirtualObject[]{o0}, new JavaValue[]{o1}, new JavaKind[]{JavaKind.Object}, 1, 0, 0);
            safepoint.accept(newState);
        });
    }
}
