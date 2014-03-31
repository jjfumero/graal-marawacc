/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.baseline;

import static com.oracle.graal.api.code.TypeCheckHints.*;
import static com.oracle.graal.bytecode.Bytecodes.*;
import static com.oracle.graal.phases.GraalOptions.*;
import static java.lang.reflect.Modifier.*;

import java.util.*;

import com.oracle.graal.alloc.*;
import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.meta.ResolvedJavaType.Representation;
import com.oracle.graal.bytecode.*;
import com.oracle.graal.compiler.*;
import com.oracle.graal.compiler.alloc.*;
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.compiler.target.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.graph.*;
import com.oracle.graal.java.*;
import com.oracle.graal.java.BciBlockMapping.BciBlock;
import com.oracle.graal.java.BciBlockMapping.ExceptionDispatchBlock;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.calc.FloatConvertNode.FloatConvert;
import com.oracle.graal.nodes.cfg.*;
import com.oracle.graal.nodes.java.MethodCallTargetNode.InvokeKind;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.phases.*;

/**
 * The {@code GraphBuilder} class parses the bytecode of a method and builds the IR graph.
 */
@SuppressWarnings("all")
public class BaselineCompiler implements BytecodeParser<BciBlock> {

    public BaselineCompiler(GraphBuilderConfiguration graphBuilderConfig, MetaAccessProvider metaAccess) {
        this.graphBuilderConfig = graphBuilderConfig;
        this.metaAccess = metaAccess;
    }

    private final MetaAccessProvider metaAccess;

    private final GraphBuilderConfiguration graphBuilderConfig;

    public CompilationResult generate(ResolvedJavaMethod method, int entryBCI, Backend backend, CompilationResult compilationResult, ResolvedJavaMethod installedCodeOwner,
                    CompilationResultBuilderFactory factory, OptimisticOptimizations optimisticOpts) {
        ProfilingInfo profilingInfo = method.getProfilingInfo();
        assert method.getCode() != null : "method must contain bytecodes: " + method;
        BytecodeStream stream = new BytecodeStream(method.getCode());
        ConstantPool constantPool = method.getConstantPool();
        TTY.Filter filter = new TTY.Filter(PrintFilter.getValue(), method);

        LIRFrameStateBuilder frameState = new LIRFrameStateBuilder(method);

        BytecodeParser parser = new BytecodeParser(metaAccess, method, graphBuilderConfig, optimisticOpts, frameState, stream, profilingInfo, constantPool, entryBCI, backend);

        // build blocks and LIR instructions
        try {
            parser.build();
        } finally {
            filter.remove();
        }

        // emitCode
        Assumptions assumptions = new Assumptions(OptAssumptions.getValue());
        GraalCompiler.emitCode(backend, assumptions, parser.lirGenRes, compilationResult, installedCodeOwner, factory);

        return compilationResult;
    }

    public void setParameter(int i, Variable emitMove) {
        // TODO Auto-generated method stub
        throw GraalInternalError.unimplemented("Auto-generated method stub");
    }

    public void processBlock(BciBlock block) {
        // TODO Auto-generated method stub
        throw GraalInternalError.unimplemented("Auto-generated method stub");
    }

    private static class BytecodeParser extends AbstractBytecodeParser<Value, LIRFrameStateBuilder> {
        private Backend backend;
        private LIRGenerator lirGen;
        private LIRGenerationResult lirGenRes;
        private BciBlock[] loopHeaders;

        public BytecodeParser(MetaAccessProvider metaAccess, ResolvedJavaMethod method, GraphBuilderConfiguration graphBuilderConfig, OptimisticOptimizations optimisticOpts,
                        LIRFrameStateBuilder frameState, BytecodeStream stream, ProfilingInfo profilingInfo, ConstantPool constantPool, int entryBCI, Backend backend) {

            super(metaAccess, method, graphBuilderConfig, optimisticOpts, frameState, stream, profilingInfo, constantPool, entryBCI);
            this.backend = backend;
        }

        protected void build() {
            if (PrintProfilingInformation.getValue()) {
                TTY.println("Profiling info for " + MetaUtil.format("%H.%n(%p)", method));
                TTY.println(MetaUtil.indent(MetaUtil.profileToString(profilingInfo, method, CodeUtil.NEW_LINE), "  "));
            }

            try (Indent indent = Debug.logAndIndent("build graph for %s", method)) {

                // compute the block map, setup exception handlers and get the entrypoint(s)
                BciBlockMapping blockMap = BciBlockMapping.create(method);
                loopHeaders = blockMap.loopHeaders;

                // add predecessors
                for (BciBlock block : blockMap.blocks) {
                    for (BciBlock successor : block.getSuccessors()) {
                        successor.getPredecessors().add(block);
                    }
                }

                if (isSynchronized(method.getModifiers())) {
                    throw GraalInternalError.unimplemented("Handle synchronized methods");
                }

                // TODO: clear non live locals

                currentBlock = blockMap.startBlock;
                if (blockMap.startBlock.isLoopHeader) {
                    throw GraalInternalError.unimplemented("Handle start block as loop header");
                }

                // add loops ? how do we add looks when we haven't parsed the bytecode?

                // create the control flow graph
                LIRControlFlowGraph cfg = new LIRControlFlowGraph(blockMap.blocks.toArray(new BciBlock[0]), new Loop[0]);

                BlocksToDoubles blockProbabilities = new BlocksToDoubles(blockMap.blocks.size());
                for (BciBlock b : blockMap.blocks) {
                    blockProbabilities.put(b, 1);
                }

                // create the LIR
                List<? extends AbstractBlock<?>> linearScanOrder = ComputeBlockOrder.computeLinearScanOrder(blockMap.blocks.size(), blockMap.startBlock, blockProbabilities);
                List<? extends AbstractBlock<?>> codeEmittingOrder = ComputeBlockOrder.computeCodeEmittingOrder(blockMap.blocks.size(), blockMap.startBlock, blockProbabilities);
                LIR lir = new LIR(cfg, linearScanOrder, codeEmittingOrder);

                FrameMap frameMap = backend.newFrameMap();
                TargetDescription target = backend.getTarget();
                CallingConvention cc = CodeUtil.getCallingConvention(backend.getProviders().getCodeCache(), CallingConvention.Type.JavaCallee, method, false);
                this.lirGenRes = backend.newLIRGenerationResult(lir, frameMap, null);
                this.lirGen = backend.newLIRGenerator(cc, lirGenRes);

                try (Scope ds = Debug.scope("BackEnd", lir)) {
                    try (Scope s = Debug.scope("LIRGen", lirGen)) {

                        // possibly add all the arguments to slots in the local variable array

                        for (BciBlock block : blockMap.blocks) {
                        }

                        lirGen.beforeRegisterAllocation();
                        Debug.dump(lir, "After LIR generation");
                    } catch (Throwable e) {
                        throw Debug.handle(e);
                    }

                    try (Scope s = Debug.scope("Allocator")) {

                        if (backend.shouldAllocateRegisters()) {
                            new LinearScan(target, lir, frameMap).allocate();
                        }
                    } catch (Throwable e) {
                        throw Debug.handle(e);
                    }
                } catch (Throwable e) {
                    throw Debug.handle(e);
                }
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
        }

        @Override
        protected void handleUnresolvedLoadConstant(JavaType type) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void handleUnresolvedCheckCast(JavaType type, Value object) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void handleUnresolvedInstanceOf(JavaType type, Value object) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void handleUnresolvedNewInstance(JavaType type) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void handleUnresolvedNewObjectArray(JavaType type, Value length) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void handleUnresolvedNewMultiArray(JavaType type, List<Value> dims) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void handleUnresolvedLoadField(JavaField field, Value receiver) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void handleUnresolvedStoreField(JavaField field, Value value, Value receiver) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void handleUnresolvedExceptionType(Representation representation, JavaType type) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genLoadIndexed(Value index, Value array, Kind kind) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genStoreIndexed(Value array, Value index, Kind kind, Value value) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genIntegerAdd(Kind kind, Value x, Value y) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genIntegerSub(Kind kind, Value x, Value y) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genIntegerMul(Kind kind, Value x, Value y) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genFloatAdd(Kind kind, Value x, Value y, boolean isStrictFP) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genFloatSub(Kind kind, Value x, Value y, boolean isStrictFP) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genFloatMul(Kind kind, Value x, Value y, boolean isStrictFP) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genFloatDiv(Kind kind, Value x, Value y, boolean isStrictFP) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genFloatRem(Kind kind, Value x, Value y, boolean isStrictFP) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genIntegerDiv(Kind kind, Value x, Value y) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genIntegerRem(Kind kind, Value x, Value y) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genNegateOp(Value x) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genLeftShift(Kind kind, Value x, Value y) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genRightShift(Kind kind, Value x, Value y) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genUnsignedRightShift(Kind kind, Value x, Value y) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genAnd(Kind kind, Value x, Value y) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genOr(Kind kind, Value x, Value y) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genXor(Kind kind, Value x, Value y) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genNormalizeCompare(Value x, Value y, boolean isUnorderedLess) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genFloatConvert(FloatConvert op, Value input) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genNarrow(Value input, int bitCount) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genSignExtend(Value input, int bitCount) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genZeroExtend(Value input, int bitCount) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genObjectEquals(Value x, Value y) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genIntegerEquals(Value x, Value y) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genIntegerLessThan(Value x, Value y) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genUnique(Value x) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genIf(Value condition, Value falseSuccessor, Value trueSuccessor, double d) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void genThrow() {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genCheckCast(ResolvedJavaType type, Value object, JavaTypeProfile profileForTypeCheck, boolean b) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genInstanceOf(ResolvedJavaType type, Value object, JavaTypeProfile profileForTypeCheck) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genConditional(Value x) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value createNewInstance(ResolvedJavaType type, boolean fillContents) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value createNewArray(ResolvedJavaType elementType, Value length, boolean fillContents) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value createNewMultiArray(ResolvedJavaType type, List<Value> dims) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genLoadField(Value receiver, ResolvedJavaField field) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void emitNullCheck(Value receiver) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void emitBoundsCheck(Value index, Value length) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genArrayLength(Value x) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genStoreField(Value receiver, ResolvedJavaField field, Value value) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void genInvokeStatic(JavaMethod target) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void genInvokeInterface(JavaMethod target) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void genInvokeDynamic(JavaMethod target) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void genInvokeVirtual(JavaMethod target) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void genInvokeSpecial(JavaMethod target) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void genReturn(Value x) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genMonitorEnter(Value x) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genMonitorExit(Value x, Value returnValue) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void genJsr(int dest) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void genRet(int localIndex) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void setBlockSuccessor(Value switchNode, int i, Value createBlockTarget) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genIntegerSwitch(Value value, int size, int[] keys, double[] keyProbabilities, int[] keySuccessors) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value appendConstant(Constant constant) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value append(Value v) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value genDeoptimization() {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value createTarget(BciBlock trueBlock, AbstractFrameStateBuilder<Value> state) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected Value createBlockTarget(double probability, BciBlock bciBlock, AbstractFrameStateBuilder<Value> stateAfter) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void processBlock(BciBlock block) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void appendGoto(Value target) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

        @Override
        protected void iterateBytecodesForBlock(BciBlock block) {
            // TODO Auto-generated method stub
            throw GraalInternalError.unimplemented("Auto-generated method stub");
        }

    }
}
