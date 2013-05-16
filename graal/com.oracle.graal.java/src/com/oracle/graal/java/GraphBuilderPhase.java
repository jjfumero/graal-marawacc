/*
 * Copyright (c) 2009, 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.java;

import static com.oracle.graal.api.code.DeoptimizationAction.*;
import static com.oracle.graal.api.code.TypeCheckHints.*;
import static com.oracle.graal.api.meta.DeoptimizationReason.*;
import static com.oracle.graal.bytecode.Bytecodes.*;
import static com.oracle.graal.java.GraphBuilderPhase.RuntimeCalls.*;
import static java.lang.reflect.Modifier.*;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.RuntimeCallTarget.Descriptor;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.meta.ProfilingInfo.TriState;
import com.oracle.graal.api.meta.ResolvedJavaType.Representation;
import com.oracle.graal.bytecode.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.java.BciBlockMapping.Block;
import com.oracle.graal.java.BciBlockMapping.ExceptionDispatchBlock;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.java.MethodCallTargetNode.InvokeKind;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.util.*;

/**
 * The {@code GraphBuilder} class parses the bytecode of a method and builds the IR graph.
 */
public class GraphBuilderPhase extends Phase {

    public static final class RuntimeCalls {

        public static final Descriptor CREATE_NULL_POINTER_EXCEPTION = new Descriptor("createNullPointerException", true, Object.class);
        public static final Descriptor CREATE_OUT_OF_BOUNDS_EXCEPTION = new Descriptor("createOutOfBoundsException", true, Object.class, int.class);
    }

    /**
     * The minimum value to which {@link GraalOptions#TraceBytecodeParserLevel} must be set to trace
     * the bytecode instructions as they are parsed.
     */
    public static final int TRACELEVEL_INSTRUCTIONS = 1;

    /**
     * The minimum value to which {@link GraalOptions#TraceBytecodeParserLevel} must be set to trace
     * the frame state before each bytecode instruction as it is parsed.
     */
    public static final int TRACELEVEL_STATE = 2;

    private LineNumberTable lnt;
    private int previousLineNumber;
    private int currentLineNumber;

    protected StructuredGraph currentGraph;

    private final MetaAccessProvider runtime;
    private ConstantPool constantPool;
    private ResolvedJavaMethod method;
    private int entryBCI;
    private ProfilingInfo profilingInfo;

    private BytecodeStream stream;           // the bytecode stream

    protected FrameStateBuilder frameState;          // the current execution state
    private Block currentBlock;

    private ValueNode methodSynchronizedObject;
    private ExceptionDispatchBlock unwindBlock;
    private Block returnBlock;

    private FixedWithNextNode lastInstr;                 // the last instruction added

    private final GraphBuilderConfiguration graphBuilderConfig;
    private final OptimisticOptimizations optimisticOpts;

    /**
     * Meters the number of actual bytecodes parsed.
     */
    public static final DebugMetric BytecodesParsed = Debug.metric("BytecodesParsed");

    /**
     * Node that marks the begin of block during bytecode parsing. When a block is identified the
     * first time as a jump target, the placeholder is created and used as the successor for the
     * jump. When the block is seen the second time, a MergeNode is created to correctly merge the
     * now two different predecessor states.
     */
    private static class BlockPlaceholderNode extends FixedWithNextNode implements Node.IterableNodeType {

        public BlockPlaceholderNode() {
            super(StampFactory.forVoid());
        }
    }

    private Block[] loopHeaders;

    /**
     * Gets the current frame state being processed by this builder.
     */
    protected FrameStateBuilder getCurrentFrameState() {
        return frameState;
    }

    /**
     * Gets the graph being processed by this builder.
     */
    protected StructuredGraph getGraph() {
        return currentGraph;
    }

    public GraphBuilderPhase(MetaAccessProvider runtime, GraphBuilderConfiguration graphBuilderConfig, OptimisticOptimizations optimisticOpts) {
        this.graphBuilderConfig = graphBuilderConfig;
        this.optimisticOpts = optimisticOpts;
        this.runtime = runtime;
        assert runtime != null;
    }

    @Override
    protected void run(StructuredGraph graph) {
        method = graph.method();
        if (graphBuilderConfig.eagerInfopointMode()) {
            lnt = method.getLineNumberTable();
            previousLineNumber = -1;
        }
        entryBCI = graph.getEntryBCI();
        profilingInfo = method.getProfilingInfo();
        assert method.getCode() != null : "method must contain bytecodes: " + method;
        this.stream = new BytecodeStream(method.getCode());
        this.constantPool = method.getConstantPool();
        unwindBlock = null;
        returnBlock = null;
        methodSynchronizedObject = null;
        this.currentGraph = graph;
        this.frameState = new FrameStateBuilder(method, graph, graphBuilderConfig.eagerResolving());
        TTY.Filter filter = new TTY.Filter(GraalOptions.PrintFilter, method);
        try {
            build();
        } finally {
            filter.remove();
        }
    }

    @Override
    protected String getDetailedName() {
        return getName() + " " + MetaUtil.format("%H.%n(%p):%r", method);
    }

    private BciBlockMapping createBlockMap() {
        BciBlockMapping map = new BciBlockMapping(method);
        map.build();
        if (Debug.isDumpEnabled()) {
            Debug.dump(map, MetaUtil.format("After block building %f %R %H.%n(%P)", method));
        }

        return map;
    }

    private void build() {
        if (GraalOptions.PrintProfilingInformation) {
            TTY.println("Profiling info for " + method);
            TTY.println(MetaUtil.indent(MetaUtil.profileToString(profilingInfo, method, CodeUtil.NEW_LINE), "  "));
        }

        // compute the block map, setup exception handlers and get the entrypoint(s)
        BciBlockMapping blockMap = createBlockMap();
        loopHeaders = blockMap.loopHeaders;

        lastInstr = currentGraph.start();
        if (isSynchronized(method.getModifiers())) {
            // add a monitor enter to the start block
            currentGraph.start().setStateAfter(frameState.create(FrameState.BEFORE_BCI));
            methodSynchronizedObject = synchronizedObject(frameState, method);
            lastInstr = genMonitorEnter(methodSynchronizedObject);
        }
        frameState.clearNonLiveLocals(blockMap.startBlock.localsLiveIn);

        if (graphBuilderConfig.eagerInfopointMode()) {
            ((StateSplit) lastInstr).setStateAfter(frameState.create(0));
            InfopointNode ipn = currentGraph.add(new InfopointNode(InfopointReason.METHOD_START));
            lastInstr.setNext(ipn);
            lastInstr = ipn;
        }

        // finish the start block
        ((StateSplit) lastInstr).setStateAfter(frameState.create(0));

        currentBlock = blockMap.startBlock;
        blockMap.startBlock.entryState = frameState;
        if (blockMap.startBlock.isLoopHeader) {
            /*
             * TODO(lstadler,gduboscq) createTarget might not be safe at this position, since it
             * expects currentBlock, etc. to be set up correctly. A better solution to this problem
             * of start blocks that are loop headers would be to create a dummy block in
             * BciBlockMapping.
             */
            appendGoto(createTarget(blockMap.startBlock, frameState));
        } else {
            blockMap.startBlock.firstInstruction = lastInstr;
        }

        for (Block block : blockMap.blocks) {
            processBlock(block);
        }
        processBlock(returnBlock);
        processBlock(unwindBlock);

        Debug.dump(currentGraph, "After bytecode parsing");

        connectLoopEndToBegin();

        // remove Placeholders
        for (BlockPlaceholderNode n : currentGraph.getNodes(BlockPlaceholderNode.class)) {
            currentGraph.removeFixed(n);
        }

        // remove dead FrameStates
        for (Node n : currentGraph.getNodes(FrameState.class)) {
            if (n.usages().count() == 0 && n.predecessor() == null) {
                n.safeDelete();
            }
        }
    }

    private Block unwindBlock(int bci) {
        if (unwindBlock == null) {
            unwindBlock = new ExceptionDispatchBlock();
            unwindBlock.startBci = -1;
            unwindBlock.endBci = -1;
            unwindBlock.deoptBci = bci;
            unwindBlock.blockID = Integer.MAX_VALUE;
        }
        return unwindBlock;
    }

    private Block returnBlock(int bci) {
        if (returnBlock == null) {
            returnBlock = new Block();
            returnBlock.startBci = bci;
            returnBlock.endBci = bci;
            returnBlock.blockID = Integer.MAX_VALUE;
        }
        return returnBlock;
    }

    public BytecodeStream stream() {
        return stream;
    }

    public int bci() {
        return stream.currentBCI();
    }

    private void loadLocal(int index, Kind kind) {
        frameState.push(kind, frameState.loadLocal(index));
    }

    private void storeLocal(Kind kind, int index) {
        ValueNode value;
        if (kind == Kind.Object) {
            value = frameState.xpop();
            // astore and astore_<n> may be used to store a returnAddress (jsr)
            assert value.kind() == Kind.Object || value.kind() == Kind.Int;
        } else {
            value = frameState.pop(kind);
        }
        frameState.storeLocal(index, value);
    }

    public static boolean covers(ExceptionHandler handler, int bci) {
        return handler.getStartBCI() <= bci && bci < handler.getEndBCI();
    }

    public static boolean isCatchAll(ExceptionHandler handler) {
        return handler.catchTypeCPI() == 0;
    }

    /**
     * @param type the unresolved type of the constant
     */
    protected void handleUnresolvedLoadConstant(JavaType type) {
        append(new DeoptimizeNode(InvalidateRecompile, Unresolved));
        frameState.push(Kind.Object, appendConstant(Constant.NULL_OBJECT));
    }

    /**
     * @param type the unresolved type of the type check
     * @param object the object value whose type is being checked against {@code type}
     */
    protected void handleUnresolvedCheckCast(JavaType type, ValueNode object) {
        append(new FixedGuardNode(currentGraph.unique(new IsNullNode(object)), Unresolved, InvalidateRecompile));
        frameState.apush(appendConstant(Constant.NULL_OBJECT));
    }

    /**
     * @param type the unresolved type of the type check
     * @param object the object value whose type is being checked against {@code type}
     */
    protected void handleUnresolvedInstanceOf(JavaType type, ValueNode object) {
        BlockPlaceholderNode successor = currentGraph.add(new BlockPlaceholderNode());
        DeoptimizeNode deopt = currentGraph.add(new DeoptimizeNode(InvalidateRecompile, Unresolved));
        append(new IfNode(currentGraph.unique(new IsNullNode(object)), successor, deopt, 1));
        lastInstr = successor;
        frameState.ipush(appendConstant(Constant.INT_0));
    }

    /**
     * @param type the type being instantiated
     */
    protected void handleUnresolvedNewInstance(JavaType type) {
        append(new DeoptimizeNode(InvalidateRecompile, Unresolved));
        frameState.apush(appendConstant(Constant.NULL_OBJECT));
    }

    /**
     * @param type the type of the array being instantiated
     * @param length the length of the array
     */
    protected void handleUnresolvedNewObjectArray(JavaType type, ValueNode length) {
        append(new DeoptimizeNode(InvalidateRecompile, Unresolved));
        frameState.apush(appendConstant(Constant.NULL_OBJECT));
    }

    /**
     * @param type the type being instantiated
     * @param dims the dimensions for the multi-array
     */
    protected void handleUnresolvedNewMultiArray(JavaType type, ValueNode[] dims) {
        append(new DeoptimizeNode(InvalidateRecompile, Unresolved));
        frameState.apush(appendConstant(Constant.NULL_OBJECT));
    }

    /**
     * @param field the unresolved field
     * @param receiver the object containing the field or {@code null} if {@code field} is static
     */
    protected void handleUnresolvedLoadField(JavaField field, ValueNode receiver) {
        Kind kind = field.getKind();
        append(new DeoptimizeNode(InvalidateRecompile, Unresolved));
        frameState.push(kind.getStackKind(), appendConstant(Constant.defaultForKind(kind)));
    }

    /**
     * @param field the unresolved field
     * @param value the value being stored to the field
     * @param receiver the object containing the field or {@code null} if {@code field} is static
     */
    protected void handleUnresolvedStoreField(JavaField field, ValueNode value, ValueNode receiver) {
        append(new DeoptimizeNode(InvalidateRecompile, Unresolved));
    }

    /**
     * @param representation
     * @param type
     */
    protected void handleUnresolvedExceptionType(Representation representation, JavaType type) {
        append(new DeoptimizeNode(InvalidateRecompile, Unresolved));
    }

    protected void handleUnresolvedInvoke(JavaMethod javaMethod, InvokeKind invokeKind) {
        boolean withReceiver = invokeKind != InvokeKind.Static;
        append(new DeoptimizeNode(InvalidateRecompile, Unresolved));
        frameState.popArguments(javaMethod.getSignature().getParameterSlots(withReceiver), javaMethod.getSignature().getParameterCount(withReceiver));
        Kind kind = javaMethod.getSignature().getReturnKind();
        if (kind != Kind.Void) {
            frameState.push(kind.getStackKind(), appendConstant(Constant.defaultForKind(kind)));
        }
    }

    private DispatchBeginNode handleException(ValueNode exceptionObject, int bci) {
        assert bci == FrameState.BEFORE_BCI || bci == bci() : "invalid bci";
        Debug.log("Creating exception dispatch edges at %d, exception object=%s, exception seen=%s", bci, exceptionObject, profilingInfo.getExceptionSeen(bci));

        Block dispatchBlock = currentBlock.exceptionDispatchBlock();
        // The exception dispatch block is always for the last bytecode of a block, so if we are not
        // at the endBci yet,
        // there is no exception handler for this bci and we can unwind immediately.
        if (bci != currentBlock.endBci || dispatchBlock == null) {
            dispatchBlock = unwindBlock(bci);
        }

        FrameStateBuilder dispatchState = frameState.copy();
        dispatchState.clearStack();

        DispatchBeginNode dispatchBegin;
        if (exceptionObject == null) {
            dispatchBegin = currentGraph.add(new ExceptionObjectNode(runtime));
            dispatchState.apush(dispatchBegin);
            dispatchState.setRethrowException(true);
            dispatchBegin.setStateAfter(dispatchState.create(bci));
        } else {
            dispatchBegin = currentGraph.add(new DispatchBeginNode());
            dispatchBegin.setStateAfter(dispatchState.create(bci));
            dispatchState.apush(exceptionObject);
            dispatchState.setRethrowException(true);
        }
        FixedNode target = createTarget(dispatchBlock, dispatchState);
        dispatchBegin.setNext(target);
        return dispatchBegin;
    }

    private void genLoadConstant(int cpi, int opcode) {
        Object con = lookupConstant(cpi, opcode);

        if (con instanceof JavaType) {
            // this is a load of class constant which might be unresolved
            JavaType type = (JavaType) con;
            if (type instanceof ResolvedJavaType) {
                frameState.push(Kind.Object, appendConstant(((ResolvedJavaType) type).getEncoding(Representation.JavaClass)));
            } else {
                handleUnresolvedLoadConstant(type);
            }
        } else if (con instanceof Constant) {
            Constant constant = (Constant) con;
            frameState.push(constant.getKind().getStackKind(), appendConstant(constant));
        } else {
            throw new Error("lookupConstant returned an object of incorrect type");
        }
    }

    private void genLoadIndexed(Kind kind) {
        emitExplicitExceptions(frameState.peek(1), frameState.peek(0));

        ValueNode index = frameState.ipop();
        ValueNode array = frameState.apop();
        frameState.push(kind.getStackKind(), append(new LoadIndexedNode(array, index, kind)));
    }

    private void genStoreIndexed(Kind kind) {
        emitExplicitExceptions(frameState.peek(2), frameState.peek(1));

        ValueNode value = frameState.pop(kind.getStackKind());
        ValueNode index = frameState.ipop();
        ValueNode array = frameState.apop();
        append(new StoreIndexedNode(array, index, kind, value));
    }

    private void stackOp(int opcode) {
        switch (opcode) {
            case POP: {
                frameState.xpop();
                break;
            }
            case POP2: {
                frameState.xpop();
                frameState.xpop();
                break;
            }
            case DUP: {
                ValueNode w = frameState.xpop();
                frameState.xpush(w);
                frameState.xpush(w);
                break;
            }
            case DUP_X1: {
                ValueNode w1 = frameState.xpop();
                ValueNode w2 = frameState.xpop();
                frameState.xpush(w1);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case DUP_X2: {
                ValueNode w1 = frameState.xpop();
                ValueNode w2 = frameState.xpop();
                ValueNode w3 = frameState.xpop();
                frameState.xpush(w1);
                frameState.xpush(w3);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case DUP2: {
                ValueNode w1 = frameState.xpop();
                ValueNode w2 = frameState.xpop();
                frameState.xpush(w2);
                frameState.xpush(w1);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case DUP2_X1: {
                ValueNode w1 = frameState.xpop();
                ValueNode w2 = frameState.xpop();
                ValueNode w3 = frameState.xpop();
                frameState.xpush(w2);
                frameState.xpush(w1);
                frameState.xpush(w3);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case DUP2_X2: {
                ValueNode w1 = frameState.xpop();
                ValueNode w2 = frameState.xpop();
                ValueNode w3 = frameState.xpop();
                ValueNode w4 = frameState.xpop();
                frameState.xpush(w2);
                frameState.xpush(w1);
                frameState.xpush(w4);
                frameState.xpush(w3);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case SWAP: {
                ValueNode w1 = frameState.xpop();
                ValueNode w2 = frameState.xpop();
                frameState.xpush(w1);
                frameState.xpush(w2);
                break;
            }
            default:
                throw GraalInternalError.shouldNotReachHere();
        }

    }

    private void genArithmeticOp(Kind result, int opcode) {
        ValueNode y = frameState.pop(result);
        ValueNode x = frameState.pop(result);
        boolean isStrictFP = isStrict(method.getModifiers());
        ArithmeticNode v;
        switch (opcode) {
            case IADD:
            case LADD:
                v = new IntegerAddNode(result, x, y);
                break;
            case FADD:
            case DADD:
                v = new FloatAddNode(result, x, y, isStrictFP);
                break;
            case ISUB:
            case LSUB:
                v = new IntegerSubNode(result, x, y);
                break;
            case FSUB:
            case DSUB:
                v = new FloatSubNode(result, x, y, isStrictFP);
                break;
            case IMUL:
            case LMUL:
                v = new IntegerMulNode(result, x, y);
                break;
            case FMUL:
            case DMUL:
                v = new FloatMulNode(result, x, y, isStrictFP);
                break;
            case FDIV:
            case DDIV:
                v = new FloatDivNode(result, x, y, isStrictFP);
                break;
            case FREM:
            case DREM:
                v = new FloatRemNode(result, x, y, isStrictFP);
                break;
            default:
                throw new GraalInternalError("should not reach");
        }
        frameState.push(result, append(v));
    }

    private void genIntegerDivOp(Kind result, int opcode) {
        ValueNode y = frameState.pop(result);
        ValueNode x = frameState.pop(result);
        FixedWithNextNode v;
        switch (opcode) {
            case IDIV:
            case LDIV:
                v = new IntegerDivNode(result, x, y);
                break;
            case IREM:
            case LREM:
                v = new IntegerRemNode(result, x, y);
                break;
            default:
                throw new GraalInternalError("should not reach");
        }
        frameState.push(result, append(v));
    }

    private void genNegateOp(Kind kind) {
        frameState.push(kind, append(new NegateNode(frameState.pop(kind))));
    }

    private void genShiftOp(Kind kind, int opcode) {
        ValueNode s = frameState.ipop();
        ValueNode x = frameState.pop(kind);
        ShiftNode v;
        switch (opcode) {
            case ISHL:
            case LSHL:
                v = new LeftShiftNode(kind, x, s);
                break;
            case ISHR:
            case LSHR:
                v = new RightShiftNode(kind, x, s);
                break;
            case IUSHR:
            case LUSHR:
                v = new UnsignedRightShiftNode(kind, x, s);
                break;
            default:
                throw new GraalInternalError("should not reach");
        }
        frameState.push(kind, append(v));
    }

    private void genLogicOp(Kind kind, int opcode) {
        ValueNode y = frameState.pop(kind);
        ValueNode x = frameState.pop(kind);
        BitLogicNode v;
        switch (opcode) {
            case IAND:
            case LAND:
                v = new AndNode(kind, x, y);
                break;
            case IOR:
            case LOR:
                v = new OrNode(kind, x, y);
                break;
            case IXOR:
            case LXOR:
                v = new XorNode(kind, x, y);
                break;
            default:
                throw new GraalInternalError("should not reach");
        }
        frameState.push(kind, append(v));
    }

    private void genCompareOp(Kind kind, boolean isUnorderedLess) {
        ValueNode y = frameState.pop(kind);
        ValueNode x = frameState.pop(kind);
        frameState.ipush(append(new NormalizeCompareNode(x, y, isUnorderedLess)));
    }

    private void genConvert(ConvertNode.Op opcode) {
        ValueNode input = frameState.pop(opcode.from.getStackKind());
        frameState.push(opcode.to.getStackKind(), append(new ConvertNode(opcode, input)));
    }

    private void genIncrement() {
        int index = stream().readLocalIndex();
        int delta = stream().readIncrement();
        ValueNode x = frameState.loadLocal(index);
        ValueNode y = appendConstant(Constant.forInt(delta));
        frameState.storeLocal(index, append(new IntegerAddNode(Kind.Int, x, y)));
    }

    private void genGoto() {
        appendGoto(createTarget(currentBlock.successors.get(0), frameState));
        assert currentBlock.numNormalSuccessors() == 1;
    }

    private void ifNode(ValueNode x, Condition cond, ValueNode y) {
        assert !x.isDeleted() && !y.isDeleted();
        assert currentBlock.numNormalSuccessors() == 2;
        Block trueBlock = currentBlock.successors.get(0);
        Block falseBlock = currentBlock.successors.get(1);
        if (trueBlock == falseBlock) {
            appendGoto(createTarget(trueBlock, frameState));
            return;
        }

        double probability = profilingInfo.getBranchTakenProbability(bci());
        if (probability < 0) {
            assert probability == -1 : "invalid probability";
            Debug.log("missing probability in %s at bci %d", method, bci());
            probability = 0.5;
        }

        // the mirroring and negation operations get the condition into canonical form
        boolean mirror = cond.canonicalMirror();
        boolean negate = cond.canonicalNegate();

        ValueNode a = mirror ? y : x;
        ValueNode b = mirror ? x : y;

        CompareNode condition;
        assert a.kind() != Kind.Double && a.kind() != Kind.Float;
        if (cond == Condition.EQ || cond == Condition.NE) {
            if (a.kind() == Kind.Object) {
                condition = new ObjectEqualsNode(a, b);
            } else {
                condition = new IntegerEqualsNode(a, b);
            }
        } else {
            assert a.kind() != Kind.Object && !cond.isUnsigned();
            condition = new IntegerLessThanNode(a, b);
        }
        condition = currentGraph.unique(condition);

        AbstractBeginNode trueSuccessor = createBlockTarget(probability, trueBlock, frameState);
        AbstractBeginNode falseSuccessor = createBlockTarget(1 - probability, falseBlock, frameState);

        IfNode ifNode = negate ? new IfNode(condition, falseSuccessor, trueSuccessor, 1 - probability) : new IfNode(condition, trueSuccessor, falseSuccessor, probability);
        append(ifNode);
    }

    private void genIfZero(Condition cond) {
        ValueNode y = appendConstant(Constant.INT_0);
        ValueNode x = frameState.ipop();
        ifNode(x, cond, y);
    }

    private void genIfNull(Condition cond) {
        ValueNode y = appendConstant(Constant.NULL_OBJECT);
        ValueNode x = frameState.apop();
        ifNode(x, cond, y);
    }

    private void genIfSame(Kind kind, Condition cond) {
        ValueNode y = frameState.pop(kind);
        ValueNode x = frameState.pop(kind);
        assert !x.isDeleted() && !y.isDeleted();
        ifNode(x, cond, y);
    }

    private void genThrow() {
        ValueNode exception = frameState.apop();
        append(new FixedGuardNode(currentGraph.unique(new IsNullNode(exception)), NullCheckException, InvalidateReprofile, true));
        lastInstr.setNext(handleException(exception, bci()));
    }

    private JavaType lookupType(int cpi, int bytecode) {
        eagerResolvingForSnippets(cpi, bytecode);
        JavaType result = constantPool.lookupType(cpi, bytecode);
        assert !graphBuilderConfig.unresolvedIsError() || result instanceof ResolvedJavaType;
        return result;
    }

    private JavaMethod lookupMethod(int cpi, int opcode) {
        eagerResolvingForSnippets(cpi, opcode);
        JavaMethod result = constantPool.lookupMethod(cpi, opcode);
        // assert !graphBuilderConfig.unresolvedIsError() || ((result instanceof ResolvedJavaMethod)
// && ((ResolvedJavaMethod) result).getDeclaringClass().isInitialized()) : result;
        return result;
    }

    private JavaField lookupField(int cpi, int opcode) {
        eagerResolvingForSnippets(cpi, opcode);
        JavaField result = constantPool.lookupField(cpi, opcode);
        assert !graphBuilderConfig.unresolvedIsError() || (result instanceof ResolvedJavaField && ((ResolvedJavaField) result).getDeclaringClass().isInitialized()) : result;
        return result;
    }

    private Object lookupConstant(int cpi, int opcode) {
        eagerResolvingForSnippets(cpi, opcode);
        Object result = constantPool.lookupConstant(cpi);
        assert !graphBuilderConfig.eagerResolving() || !(result instanceof JavaType) || (result instanceof ResolvedJavaType);
        return result;
    }

    private void eagerResolvingForSnippets(int cpi, int bytecode) {
        if (graphBuilderConfig.eagerResolving()) {
            constantPool.loadReferencedType(cpi, bytecode);
        }
    }

    private JavaTypeProfile getProfileForTypeCheck(ResolvedJavaType type) {
        if (!optimisticOpts.useTypeCheckHints() || !canHaveSubtype(type)) {
            return null;
        } else {
            return profilingInfo.getTypeProfile(bci());
        }
    }

    private void genCheckCast() {
        int cpi = stream().readCPI();
        JavaType type = lookupType(cpi, CHECKCAST);
        ValueNode object = frameState.apop();
        if (type instanceof ResolvedJavaType) {
            JavaTypeProfile profileForTypeCheck = getProfileForTypeCheck((ResolvedJavaType) type);
            CheckCastNode checkCastNode = append(new CheckCastNode((ResolvedJavaType) type, object, profileForTypeCheck, false));
            frameState.apush(checkCastNode);
        } else {
            handleUnresolvedCheckCast(type, object);
        }
    }

    private void genInstanceOf() {
        int cpi = stream().readCPI();
        JavaType type = lookupType(cpi, INSTANCEOF);
        ValueNode object = frameState.apop();
        if (type instanceof ResolvedJavaType) {
            ResolvedJavaType resolvedType = (ResolvedJavaType) type;
            InstanceOfNode instanceOfNode = new InstanceOfNode((ResolvedJavaType) type, object, getProfileForTypeCheck(resolvedType));
            frameState.ipush(append(new ConditionalNode(currentGraph.unique(instanceOfNode), ConstantNode.forInt(1, currentGraph), ConstantNode.forInt(0, currentGraph))));
        } else {
            handleUnresolvedInstanceOf(type, object);
        }
    }

    void genNewInstance(int cpi) {
        JavaType type = lookupType(cpi, NEW);
        if (type instanceof ResolvedJavaType && ((ResolvedJavaType) type).isInitialized()) {
            frameState.apush(append(new NewInstanceNode((ResolvedJavaType) type, true)));
        } else {
            handleUnresolvedNewInstance(type);
        }
    }

    /**
     * Gets the kind of array elements for the array type code that appears in a
     * {@link Bytecodes#NEWARRAY} bytecode.
     * 
     * @param code the array type code
     * @return the kind from the array type code
     */
    public static Class<?> arrayTypeCodeToClass(int code) {
        // Checkstyle: stop
        switch (code) {
            case 4:
                return boolean.class;
            case 5:
                return char.class;
            case 6:
                return float.class;
            case 7:
                return double.class;
            case 8:
                return byte.class;
            case 9:
                return short.class;
            case 10:
                return int.class;
            case 11:
                return long.class;
            default:
                throw new IllegalArgumentException("unknown array type code: " + code);
        }
        // Checkstyle: resume
    }

    private void genNewPrimitiveArray(int typeCode) {
        Class<?> clazz = arrayTypeCodeToClass(typeCode);
        ResolvedJavaType elementType = runtime.lookupJavaType(clazz);
        frameState.apush(append(new NewArrayNode(elementType, frameState.ipop(), true)));
    }

    private void genNewObjectArray(int cpi) {
        JavaType type = lookupType(cpi, ANEWARRAY);
        ValueNode length = frameState.ipop();
        if (type instanceof ResolvedJavaType) {
            frameState.apush(append(new NewArrayNode((ResolvedJavaType) type, length, true)));
        } else {
            handleUnresolvedNewObjectArray(type, length);
        }

    }

    private void genNewMultiArray(int cpi) {
        JavaType type = lookupType(cpi, MULTIANEWARRAY);
        int rank = stream().readUByte(bci() + 3);
        ValueNode[] dims = new ValueNode[rank];
        for (int i = rank - 1; i >= 0; i--) {
            dims[i] = frameState.ipop();
        }
        if (type instanceof ResolvedJavaType) {
            frameState.apush(append(new NewMultiArrayNode((ResolvedJavaType) type, dims)));
        } else {
            handleUnresolvedNewMultiArray(type, dims);
        }
    }

    private void genGetField(JavaField field) {
        emitExplicitExceptions(frameState.peek(0), null);

        Kind kind = field.getKind();
        ValueNode receiver = frameState.apop();
        if ((field instanceof ResolvedJavaField) && ((ResolvedJavaField) field).getDeclaringClass().isInitialized()) {
            appendOptimizedLoadField(kind, new LoadFieldNode(receiver, (ResolvedJavaField) field));
        } else {
            handleUnresolvedLoadField(field, receiver);
        }
    }

    public static class ExceptionInfo {

        public final FixedWithNextNode exceptionEdge;
        public final ValueNode exception;

        public ExceptionInfo(FixedWithNextNode exceptionEdge, ValueNode exception) {
            this.exceptionEdge = exceptionEdge;
            this.exception = exception;
        }
    }

    private void emitNullCheck(ValueNode receiver) {
        if (receiver.stamp().nonNull()) {
            return;
        }
        BlockPlaceholderNode trueSucc = currentGraph.add(new BlockPlaceholderNode());
        BlockPlaceholderNode falseSucc = currentGraph.add(new BlockPlaceholderNode());
        append(new IfNode(currentGraph.unique(new IsNullNode(receiver)), trueSucc, falseSucc, 0.1));
        lastInstr = falseSucc;

        if (GraalOptions.OmitHotExceptionStacktrace) {
            ValueNode exception = ConstantNode.forObject(cachedNullPointerException, runtime, currentGraph);
            trueSucc.setNext(handleException(exception, bci()));
        } else {
            RuntimeCallNode call = currentGraph.add(new RuntimeCallNode(CREATE_NULL_POINTER_EXCEPTION));
            call.setStateAfter(frameState.create(bci()));
            trueSucc.setNext(call);
            call.setNext(handleException(call, bci()));
        }
    }

    private static final ArrayIndexOutOfBoundsException cachedArrayIndexOutOfBoundsException = new ArrayIndexOutOfBoundsException();
    private static final NullPointerException cachedNullPointerException = new NullPointerException();
    static {
        cachedArrayIndexOutOfBoundsException.setStackTrace(new StackTraceElement[0]);
        cachedNullPointerException.setStackTrace(new StackTraceElement[0]);
    }

    private void emitBoundsCheck(ValueNode index, ValueNode length) {
        BlockPlaceholderNode trueSucc = currentGraph.add(new BlockPlaceholderNode());
        BlockPlaceholderNode falseSucc = currentGraph.add(new BlockPlaceholderNode());
        append(new IfNode(currentGraph.unique(new IntegerBelowThanNode(index, length)), trueSucc, falseSucc, 0.9));
        lastInstr = trueSucc;

        if (GraalOptions.OmitHotExceptionStacktrace) {
            ValueNode exception = ConstantNode.forObject(cachedArrayIndexOutOfBoundsException, runtime, currentGraph);
            falseSucc.setNext(handleException(exception, bci()));
        } else {
            RuntimeCallNode call = currentGraph.add(new RuntimeCallNode(CREATE_OUT_OF_BOUNDS_EXCEPTION, index));
            call.setStateAfter(frameState.create(bci()));
            falseSucc.setNext(call);
            call.setNext(handleException(call, bci()));
        }
    }

    protected void emitExplicitExceptions(ValueNode receiver, ValueNode outOfBoundsIndex) {
        assert receiver != null;
        if (graphBuilderConfig.omitAllExceptionEdges() || (optimisticOpts.useExceptionProbabilityForOperations() && profilingInfo.getExceptionSeen(bci()) == TriState.FALSE)) {
            return;
        }

        emitNullCheck(receiver);
        if (outOfBoundsIndex != null) {
            ValueNode length = append(new ArrayLengthNode(receiver));
            emitBoundsCheck(outOfBoundsIndex, length);
        }
        Debug.metric("ExplicitExceptions").increment();
    }

    private void genPutField(JavaField field) {
        emitExplicitExceptions(frameState.peek(1), null);

        ValueNode value = frameState.pop(field.getKind().getStackKind());
        ValueNode receiver = frameState.apop();
        if (field instanceof ResolvedJavaField && ((ResolvedJavaField) field).getDeclaringClass().isInitialized()) {
            appendOptimizedStoreField(new StoreFieldNode(receiver, (ResolvedJavaField) field, value));
        } else {
            handleUnresolvedStoreField(field, value, receiver);
        }
    }

    private void genGetStatic(JavaField field) {
        Kind kind = field.getKind();
        if (field instanceof ResolvedJavaField && ((ResolvedJavaType) field.getDeclaringClass()).isInitialized()) {
            appendOptimizedLoadField(kind, new LoadFieldNode(null, (ResolvedJavaField) field));
        } else {
            handleUnresolvedLoadField(field, null);
        }
    }

    private void genPutStatic(JavaField field) {
        ValueNode value = frameState.pop(field.getKind().getStackKind());
        if (field instanceof ResolvedJavaField && ((ResolvedJavaType) field.getDeclaringClass()).isInitialized()) {
            appendOptimizedStoreField(new StoreFieldNode(null, (ResolvedJavaField) field, value));
        } else {
            handleUnresolvedStoreField(field, value, null);
        }
    }

    private ConstantNode genTypeOrDeopt(Representation representation, JavaType type, boolean initialized) {
        if (initialized) {
            return appendConstant(((ResolvedJavaType) type).getEncoding(representation));
        } else {
            handleUnresolvedExceptionType(representation, type);
            return null;
        }
    }

    private void appendOptimizedStoreField(StoreFieldNode store) {
        append(store);
    }

    private void appendOptimizedLoadField(Kind kind, LoadFieldNode load) {
        // append the load to the instruction
        ValueNode optimized = append(load);
        frameState.push(kind.getStackKind(), optimized);
    }

    private void genInvokeStatic(JavaMethod target) {
        if (target instanceof ResolvedJavaMethod) {
            ResolvedJavaMethod resolvedTarget = (ResolvedJavaMethod) target;
            ResolvedJavaType holder = resolvedTarget.getDeclaringClass();
            if (!holder.isInitialized() && GraalOptions.ResolveClassBeforeStaticInvoke) {
                handleUnresolvedInvoke(target, InvokeKind.Static);
            } else {
                ValueNode[] args = frameState.popArguments(resolvedTarget.getSignature().getParameterSlots(false), resolvedTarget.getSignature().getParameterCount(false));
                appendInvoke(InvokeKind.Static, resolvedTarget, args);
            }
        } else {
            handleUnresolvedInvoke(target, InvokeKind.Static);
        }
    }

    private void genInvokeInterface(JavaMethod target) {
        if (target instanceof ResolvedJavaMethod) {
            ValueNode[] args = frameState.popArguments(target.getSignature().getParameterSlots(true), target.getSignature().getParameterCount(true));
            genInvokeIndirect(InvokeKind.Interface, (ResolvedJavaMethod) target, args);
        } else {
            handleUnresolvedInvoke(target, InvokeKind.Interface);
        }
    }

    private void genInvokeDynamic(JavaMethod target) {
        if (target instanceof ResolvedJavaMethod) {
            Object appendix = constantPool.lookupAppendix(stream.readCPI4(), Bytecodes.INVOKEDYNAMIC);
            if (appendix != null) {
                frameState.apush(ConstantNode.forObject(appendix, runtime, currentGraph));
            }
            ValueNode[] args = frameState.popArguments(target.getSignature().getParameterSlots(false), target.getSignature().getParameterCount(false));
            appendInvoke(InvokeKind.Static, (ResolvedJavaMethod) target, args);
        } else {
            handleUnresolvedInvoke(target, InvokeKind.Static);
        }
    }

    private void genInvokeVirtual(JavaMethod target) {
        if (target instanceof ResolvedJavaMethod) {
            // Special handling for runtimes that rewrite an invocation of MethodHandle.invoke(...)
            // or MethodHandle.invokeExact(...) to a static adapter. HotSpot does this - see
            // https://wikis.oracle.com/display/HotSpotInternals/Method+handles+and+invokedynamic
            boolean hasReceiver = !isStatic(((ResolvedJavaMethod) target).getModifiers());
            Object appendix = constantPool.lookupAppendix(stream.readCPI(), Bytecodes.INVOKEVIRTUAL);
            if (appendix != null) {
                frameState.apush(ConstantNode.forObject(appendix, runtime, currentGraph));
            }
            ValueNode[] args = frameState.popArguments(target.getSignature().getParameterSlots(hasReceiver), target.getSignature().getParameterCount(hasReceiver));
            if (hasReceiver) {
                genInvokeIndirect(InvokeKind.Virtual, (ResolvedJavaMethod) target, args);
            } else {
                appendInvoke(InvokeKind.Static, (ResolvedJavaMethod) target, args);
            }
        } else {
            handleUnresolvedInvoke(target, InvokeKind.Virtual);
        }

    }

    private void genInvokeSpecial(JavaMethod target) {
        if (target instanceof ResolvedJavaMethod) {
            assert target != null;
            assert target.getSignature() != null;
            ValueNode[] args = frameState.popArguments(target.getSignature().getParameterSlots(true), target.getSignature().getParameterCount(true));
            invokeDirect((ResolvedJavaMethod) target, args);
        } else {
            handleUnresolvedInvoke(target, InvokeKind.Special);
        }
    }

    private void genInvokeIndirect(InvokeKind invokeKind, ResolvedJavaMethod target, ValueNode[] args) {
        ValueNode receiver = args[0];
        // attempt to devirtualize the call
        ResolvedJavaType klass = target.getDeclaringClass();

        // 0. check for trivial cases
        if (target.canBeStaticallyBound()) {
            // check for trivial cases (e.g. final methods, nonvirtual methods)
            invokeDirect(target, args);
            return;
        }
        // 1. check if the exact type of the receiver can be determined
        ResolvedJavaType exact = klass.asExactType();
        if (exact == null && receiver.objectStamp().isExactType()) {
            exact = receiver.objectStamp().type();
        }
        if (exact != null) {
            // either the holder class is exact, or the receiver object has an exact type
            invokeDirect(exact.resolveMethod(target), args);
            return;
        }
        // devirtualization failed, produce an actual invokevirtual
        appendInvoke(invokeKind, target, args);
    }

    private void invokeDirect(ResolvedJavaMethod target, ValueNode[] args) {
        appendInvoke(InvokeKind.Special, target, args);
    }

    private void appendInvoke(InvokeKind invokeKind, ResolvedJavaMethod targetMethod, ValueNode[] args) {
        Kind resultType = targetMethod.getSignature().getReturnKind();
        if (GraalOptions.DeoptALot) {
            append(new DeoptimizeNode(DeoptimizationAction.None, RuntimeConstraint));
            frameState.pushReturn(resultType, ConstantNode.defaultForKind(resultType, currentGraph));
            return;
        }

        JavaType returnType = targetMethod.getSignature().getReturnType(method.getDeclaringClass());
        if (graphBuilderConfig.eagerResolving()) {
            returnType = returnType.resolve(targetMethod.getDeclaringClass());
        }
        if (invokeKind != InvokeKind.Static && invokeKind != InvokeKind.Special) {
            JavaTypeProfile profile = profilingInfo.getTypeProfile(bci());
            args[0] = TypeProfileProxyNode.create(args[0], profile);
        }
        MethodCallTargetNode callTarget = currentGraph.add(new MethodCallTargetNode(invokeKind, targetMethod, args, returnType));
        createInvokeNode(callTarget, resultType);
    }

    protected Invoke createInvokeNode(CallTargetNode callTarget, Kind resultType) {
        // be conservative if information was not recorded (could result in endless recompiles
        // otherwise)
        if (graphBuilderConfig.omitAllExceptionEdges() || (optimisticOpts.useExceptionProbability() && profilingInfo.getExceptionSeen(bci()) == TriState.FALSE)) {
            frameState.pushReturn(resultType, append(new InvokeNode(callTarget, bci())));
            return new InvokeNode(callTarget, bci());
        } else {
            DispatchBeginNode exceptionEdge = handleException(null, bci());
            InvokeWithExceptionNode invoke = append(new InvokeWithExceptionNode(callTarget, exceptionEdge, bci()));
            frameState.pushReturn(resultType, invoke);
            Block nextBlock = currentBlock.successors.get(0);

            assert bci() == currentBlock.endBci;
            frameState.clearNonLiveLocals(currentBlock.localsLiveOut);

            invoke.setNext(createTarget(nextBlock, frameState));
            invoke.setStateAfter(frameState.create(nextBlock.startBci));
            return invoke;
        }
    }

    private void genReturn(ValueNode x) {
        frameState.clearStack();
        if (x != null) {
            frameState.push(x.kind(), x);
        }
        appendGoto(createTarget(returnBlock(bci()), frameState));
    }

    private MonitorEnterNode genMonitorEnter(ValueNode x) {
        MonitorEnterNode monitorEnter = append(new MonitorEnterNode(x, frameState.lockDepth()));
        frameState.pushLock(x);
        return monitorEnter;
    }

    private MonitorExitNode genMonitorExit(ValueNode x) {
        ValueNode lockedObject = frameState.popLock();
        if (GraphUtil.originalValue(lockedObject) != GraphUtil.originalValue(x)) {
            throw new BailoutException("unbalanced monitors: mismatch at monitorexit, %s != %s", GraphUtil.originalValue(x), GraphUtil.originalValue(lockedObject));
        }
        MonitorExitNode monitorExit = append(new MonitorExitNode(x, frameState.lockDepth()));
        return monitorExit;
    }

    private void genJsr(int dest) {
        Block successor = currentBlock.jsrSuccessor;
        assert successor.startBci == dest : successor.startBci + " != " + dest + " @" + bci();
        JsrScope scope = currentBlock.jsrScope;
        if (!successor.jsrScope.pop().equals(scope)) {
            throw new JsrNotSupportedBailout("unstructured control flow (internal limitation)");
        }
        if (successor.jsrScope.nextReturnAddress() != stream().nextBCI()) {
            throw new JsrNotSupportedBailout("unstructured control flow (internal limitation)");
        }
        frameState.push(Kind.Int, ConstantNode.forInt(stream().nextBCI(), currentGraph));
        appendGoto(createTarget(successor, frameState));
    }

    private void genRet(int localIndex) {
        Block successor = currentBlock.retSuccessor;
        ValueNode local = frameState.loadLocal(localIndex);
        JsrScope scope = currentBlock.jsrScope;
        int retAddress = scope.nextReturnAddress();
        append(new FixedGuardNode(currentGraph.unique(new IntegerEqualsNode(local, ConstantNode.forInt(retAddress, currentGraph))), JavaSubroutineMismatch, InvalidateReprofile));
        if (!successor.jsrScope.equals(scope.pop())) {
            throw new JsrNotSupportedBailout("unstructured control flow (ret leaves more than one scope)");
        }
        appendGoto(createTarget(successor, frameState));
    }

    private double[] switchProbability(int numberOfCases, int bci) {
        double[] prob = profilingInfo.getSwitchProbabilities(bci);
        if (prob != null) {
            assert prob.length == numberOfCases;
        } else {
            Debug.log("Missing probability (switch) in %s at bci %d", method, bci);
            prob = new double[numberOfCases];
            for (int i = 0; i < numberOfCases; i++) {
                prob[i] = 1.0d / numberOfCases;
            }
        }
        assert allPositive(prob);
        return prob;
    }

    private static boolean allPositive(double[] a) {
        for (double d : a) {
            if (d < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Helper function that sums up the probabilities of all keys that lead to a specific successor.
     * 
     * @return an array of size successorCount with the accumulated probability for each successor.
     */
    private static double[] successorProbabilites(int successorCount, int[] keySuccessors, double[] keyProbabilities) {
        double[] probability = new double[successorCount];
        for (int i = 0; i < keySuccessors.length; i++) {
            probability[keySuccessors[i]] += keyProbabilities[i];
        }
        return probability;
    }

    private void genSwitch(BytecodeSwitch bs) {
        int bci = bci();
        ValueNode value = frameState.ipop();

        int nofCases = bs.numberOfCases();
        double[] keyProbabilities = switchProbability(nofCases + 1, bci);

        Map<Integer, SuccessorInfo> bciToBlockSuccessorIndex = new HashMap<>();
        for (int i = 0; i < currentBlock.successors.size(); i++) {
            assert !bciToBlockSuccessorIndex.containsKey(currentBlock.successors.get(i).startBci);
            if (!bciToBlockSuccessorIndex.containsKey(currentBlock.successors.get(i).startBci)) {
                bciToBlockSuccessorIndex.put(currentBlock.successors.get(i).startBci, new SuccessorInfo(i));
            }
        }

        ArrayList<Block> actualSuccessors = new ArrayList<>();
        int[] keys = new int[nofCases];
        int[] keySuccessors = new int[nofCases + 1];
        int deoptSuccessorIndex = -1;
        int nextSuccessorIndex = 0;
        for (int i = 0; i < nofCases + 1; i++) {
            if (i < nofCases) {
                keys[i] = bs.keyAt(i);
            }

            if (isNeverExecutedCode(keyProbabilities[i])) {
                if (deoptSuccessorIndex < 0) {
                    deoptSuccessorIndex = nextSuccessorIndex++;
                    actualSuccessors.add(null);
                }
                keySuccessors[i] = deoptSuccessorIndex;
            } else {
                int targetBci = i >= nofCases ? bs.defaultTarget() : bs.targetAt(i);
                SuccessorInfo info = bciToBlockSuccessorIndex.get(targetBci);
                if (info.actualIndex < 0) {
                    info.actualIndex = nextSuccessorIndex++;
                    actualSuccessors.add(currentBlock.successors.get(info.blockIndex));
                }
                keySuccessors[i] = info.actualIndex;
            }
        }

        double[] successorProbabilities = successorProbabilites(actualSuccessors.size(), keySuccessors, keyProbabilities);
        IntegerSwitchNode switchNode = append(new IntegerSwitchNode(value, actualSuccessors.size(), keys, keyProbabilities, keySuccessors));
        for (int i = 0; i < actualSuccessors.size(); i++) {
            switchNode.setBlockSuccessor(i, createBlockTarget(successorProbabilities[i], actualSuccessors.get(i), frameState));
        }

    }

    private static class SuccessorInfo {

        int blockIndex;
        int actualIndex;

        public SuccessorInfo(int blockSuccessorIndex) {
            this.blockIndex = blockSuccessorIndex;
            actualIndex = -1;
        }
    }

    protected ConstantNode appendConstant(Constant constant) {
        assert constant != null;
        return ConstantNode.forConstant(constant, runtime, currentGraph);
    }

    private <T extends ControlSinkNode> T append(T fixed) {
        assert !fixed.isAlive() && !fixed.isDeleted() : "instruction should not have been appended yet";
        assert lastInstr.next() == null : "cannot append instruction to instruction which isn't end (" + lastInstr + "->" + lastInstr.next() + ")";
        T added = currentGraph.add(fixed);
        lastInstr.setNext(added);
        lastInstr = null;
        return added;
    }

    private <T extends ControlSplitNode> T append(T fixed) {
        assert !fixed.isAlive() && !fixed.isDeleted() : "instruction should not have been appended yet";
        assert lastInstr.next() == null : "cannot append instruction to instruction which isn't end (" + lastInstr + "->" + lastInstr.next() + ")";
        T added = currentGraph.add(fixed);
        lastInstr.setNext(added);
        lastInstr = null;
        return added;
    }

    protected <T extends FixedWithNextNode> T append(T fixed) {
        assert !fixed.isAlive() && !fixed.isDeleted() : "instruction should not have been appended yet";
        assert lastInstr.next() == null : "cannot append instruction to instruction which isn't end (" + lastInstr + "->" + lastInstr.next() + ")";
        T added = currentGraph.add(fixed);
        lastInstr.setNext(added);
        lastInstr = added;
        return added;
    }

    private <T extends FloatingNode> T append(T v) {
        assert !(v instanceof ConstantNode);
        T added = currentGraph.unique(v);
        return added;
    }

    private static class Target {

        FixedNode fixed;
        FrameStateBuilder state;

        public Target(FixedNode fixed, FrameStateBuilder state) {
            this.fixed = fixed;
            this.state = state;
        }
    }

    private Target checkLoopExit(FixedNode target, Block targetBlock, FrameStateBuilder state) {
        if (currentBlock != null) {
            long exits = currentBlock.loops & ~targetBlock.loops;
            if (exits != 0) {
                LoopExitNode firstLoopExit = null;
                LoopExitNode lastLoopExit = null;

                int pos = 0;
                ArrayList<Block> exitLoops = new ArrayList<>(Long.bitCount(exits));
                do {
                    long lMask = 1L << pos;
                    if ((exits & lMask) != 0) {
                        exitLoops.add(loopHeaders[pos]);
                        exits &= ~lMask;
                    }
                    pos++;
                } while (exits != 0);

                Collections.sort(exitLoops, new Comparator<Block>() {

                    @Override
                    public int compare(Block o1, Block o2) {
                        return Long.bitCount(o2.loops) - Long.bitCount(o1.loops);
                    }
                });

                int bci = targetBlock.startBci;
                if (targetBlock instanceof ExceptionDispatchBlock) {
                    bci = ((ExceptionDispatchBlock) targetBlock).deoptBci;
                }
                FrameStateBuilder newState = state.copy();
                for (Block loop : exitLoops) {
                    LoopBeginNode loopBegin = (LoopBeginNode) loop.firstInstruction;
                    LoopExitNode loopExit = currentGraph.add(new LoopExitNode(loopBegin));
                    if (lastLoopExit != null) {
                        lastLoopExit.setNext(loopExit);
                    }
                    if (firstLoopExit == null) {
                        firstLoopExit = loopExit;
                    }
                    lastLoopExit = loopExit;
                    Debug.log("Target %s (%s) Exits %s, scanning framestates...", targetBlock, target, loop);
                    newState.insertLoopProxies(loopExit, loop.entryState);
                    loopExit.setStateAfter(newState.create(bci));
                }

                lastLoopExit.setNext(target);
                return new Target(firstLoopExit, newState);
            }
        }
        return new Target(target, state);
    }

    private FixedNode createTarget(double probability, Block block, FrameStateBuilder stateAfter) {
        assert probability >= 0 && probability <= 1.01 : probability;
        if (isNeverExecutedCode(probability)) {
            return currentGraph.add(new DeoptimizeNode(InvalidateReprofile, UnreachedCode));
        } else {
            assert block != null;
            return createTarget(block, stateAfter);
        }
    }

    private boolean isNeverExecutedCode(double probability) {
        return probability == 0 && optimisticOpts.removeNeverExecutedCode() && entryBCI == StructuredGraph.INVOCATION_ENTRY_BCI;
    }

    private FixedNode createTarget(Block block, FrameStateBuilder state) {
        assert block != null && state != null;
        assert !block.isExceptionEntry || state.stackSize() == 1;

        if (block.firstInstruction == null) {
            // This is the first time we see this block as a branch target.
            // Create and return a placeholder that later can be replaced with a MergeNode when we
            // see this block again.
            block.firstInstruction = currentGraph.add(new BlockPlaceholderNode());
            Target target = checkLoopExit(block.firstInstruction, block, state);
            FixedNode result = target.fixed;
            block.entryState = target.state == state ? state.copy() : target.state;
            block.entryState.clearNonLiveLocals(block.localsLiveIn);

            Debug.log("createTarget %s: first visit, result: %s", block, block.firstInstruction);
            return result;
        }

        // We already saw this block before, so we have to merge states.
        if (!block.entryState.isCompatibleWith(state)) {
            throw new BailoutException("stacks do not match; bytecodes would not verify");
        }

        if (block.firstInstruction instanceof LoopBeginNode) {
            assert block.isLoopHeader && currentBlock.blockID >= block.blockID : "must be backward branch";
            // Backward loop edge. We need to create a special LoopEndNode and merge with the loop
            // begin node created before.
            LoopBeginNode loopBegin = (LoopBeginNode) block.firstInstruction;
            Target target = checkLoopExit(currentGraph.add(new LoopEndNode(loopBegin)), block, state);
            FixedNode result = target.fixed;
            block.entryState.merge(loopBegin, target.state);

            Debug.log("createTarget %s: merging backward branch to loop header %s, result: %s", block, loopBegin, result);
            return result;
        }
        assert currentBlock == null || currentBlock.blockID < block.blockID : "must not be backward branch";
        assert block.firstInstruction.next() == null : "bytecodes already parsed for block";

        if (block.firstInstruction instanceof BlockPlaceholderNode) {
            // This is the second time we see this block. Create the actual MergeNode and the End
            // Node for the already existing edge.
            // For simplicity, we leave the placeholder in the graph and just append the new nodes
            // after the placeholder.
            BlockPlaceholderNode placeholder = (BlockPlaceholderNode) block.firstInstruction;

            // The EndNode for the already existing edge.
            AbstractEndNode end = currentGraph.add(new EndNode());
            // The MergeNode that replaces the placeholder.
            MergeNode mergeNode = currentGraph.add(new MergeNode());
            FixedNode next = placeholder.next();

            placeholder.setNext(end);
            mergeNode.addForwardEnd(end);
            mergeNode.setNext(next);

            block.firstInstruction = mergeNode;
        }

        MergeNode mergeNode = (MergeNode) block.firstInstruction;

        // The EndNode for the newly merged edge.
        AbstractEndNode newEnd = currentGraph.add(new EndNode());
        Target target = checkLoopExit(newEnd, block, state);
        FixedNode result = target.fixed;
        block.entryState.merge(mergeNode, target.state);
        mergeNode.addForwardEnd(newEnd);

        Debug.log("createTarget %s: merging state, result: %s", block, result);
        return result;
    }

    /**
     * Returns a block begin node with the specified state. If the specified probability is 0, the
     * block deoptimizes immediately.
     */
    private AbstractBeginNode createBlockTarget(double probability, Block block, FrameStateBuilder stateAfter) {
        FixedNode target = createTarget(probability, block, stateAfter);
        AbstractBeginNode begin = AbstractBeginNode.begin(target);

        assert !(target instanceof DeoptimizeNode && begin.stateAfter() != null) : "We are not allowed to set the stateAfter of the begin node, because we have to deoptimize "
                        + "to a bci _before_ the actual if, so that the interpreter can update the profiling information.";
        return begin;
    }

    private ValueNode synchronizedObject(FrameStateBuilder state, ResolvedJavaMethod target) {
        if (isStatic(target.getModifiers())) {
            return appendConstant(target.getDeclaringClass().getEncoding(Representation.JavaClass));
        } else {
            return state.loadLocal(0);
        }
    }

    private void processBlock(Block block) {
        // Ignore blocks that have no predecessors by the time their bytecodes are parsed
        if (block == null || block.firstInstruction == null) {
            Debug.log("Ignoring block %s", block);
            return;
        }
        Debug.log("Parsing block %s  firstInstruction: %s  loopHeader: %b", block, block.firstInstruction, block.isLoopHeader);

        lastInstr = block.firstInstruction;
        frameState = block.entryState;
        currentBlock = block;

        frameState.cleanupDeletedPhis();
        if (lastInstr instanceof MergeNode) {
            int bci = block.startBci;
            if (block instanceof ExceptionDispatchBlock) {
                bci = ((ExceptionDispatchBlock) block).deoptBci;
            }
            ((MergeNode) lastInstr).setStateAfter(frameState.create(bci));
        }

        if (block == returnBlock) {
            frameState.setRethrowException(false);
            createReturn();
        } else if (block == unwindBlock) {
            frameState.setRethrowException(false);
            createUnwind();
        } else if (block instanceof ExceptionDispatchBlock) {
            createExceptionDispatch((ExceptionDispatchBlock) block);
        } else {
            frameState.setRethrowException(false);
            iterateBytecodesForBlock(block);
        }
    }

    private void connectLoopEndToBegin() {
        for (LoopBeginNode begin : currentGraph.getNodes(LoopBeginNode.class)) {
            if (begin.loopEnds().isEmpty()) {
                // @formatter:off
                // Remove loop header without loop ends.
                // This can happen with degenerated loops like this one:
                // for (;;) {
                //     try {
                //         break;
                //     } catch (UnresolvedException iioe) {
                //     }
                // }
                // @formatter:on
                assert begin.forwardEndCount() == 1;
                currentGraph.reduceDegenerateLoopBegin(begin);
            } else {
                GraphUtil.normalizeLoopBegin(begin);
            }
        }
    }

    private void createUnwind() {
        assert frameState.stackSize() == 1 : frameState;
        ValueNode exception = frameState.apop();
        append(new FixedGuardNode(currentGraph.unique(new IsNullNode(exception)), NullCheckException, InvalidateReprofile, true));
        synchronizedEpilogue(FrameState.AFTER_EXCEPTION_BCI);
        append(new UnwindNode(exception));
    }

    private void createReturn() {
        Kind returnKind = method.getSignature().getReturnKind().getStackKind();
        ValueNode x = returnKind == Kind.Void ? null : frameState.pop(returnKind);
        assert frameState.stackSize() == 0;

        if (Modifier.isSynchronized(method.getModifiers())) {
            append(new ValueAnchorNode(true, x));
            assert !frameState.rethrowException();
        }

        synchronizedEpilogue(FrameState.AFTER_BCI);
        if (frameState.lockDepth() != 0) {
            throw new BailoutException("unbalanced monitors");
        }

        if (graphBuilderConfig.eagerInfopointMode()) {
            InfopointNode ipn = append(new InfopointNode(InfopointReason.METHOD_END));
            ipn.setStateAfter(frameState.create(FrameState.AFTER_BCI));
        }

        append(new ReturnNode(x));
    }

    private void synchronizedEpilogue(int bci) {
        if (Modifier.isSynchronized(method.getModifiers())) {
            MonitorExitNode monitorExit = genMonitorExit(methodSynchronizedObject);
            monitorExit.setStateAfter(frameState.create(bci));
            assert !frameState.rethrowException();
        }
    }

    private void createExceptionDispatch(ExceptionDispatchBlock block) {
        assert frameState.stackSize() == 1 : frameState;
        if (block.handler.isCatchAll()) {
            assert block.successors.size() == 1;
            appendGoto(createTarget(block.successors.get(0), frameState));
            return;
        }

        JavaType catchType = block.handler.getCatchType();
        if (graphBuilderConfig.eagerResolving()) {
            catchType = lookupType(block.handler.catchTypeCPI(), INSTANCEOF);
        }
        boolean initialized = (catchType instanceof ResolvedJavaType);
        if (initialized && graphBuilderConfig.getSkippedExceptionTypes() != null) {
            ResolvedJavaType resolvedCatchType = (ResolvedJavaType) catchType;
            for (ResolvedJavaType skippedType : graphBuilderConfig.getSkippedExceptionTypes()) {
                initialized &= !skippedType.isAssignableFrom(resolvedCatchType);
                if (!initialized) {
                    break;
                }
            }
        }

        ConstantNode typeInstruction = genTypeOrDeopt(Representation.ObjectHub, catchType, initialized);
        if (typeInstruction != null) {
            Block nextBlock = block.successors.size() == 1 ? unwindBlock(block.deoptBci) : block.successors.get(1);
            ValueNode exception = frameState.stackAt(0);
            CheckCastNode checkCast = currentGraph.add(new CheckCastNode((ResolvedJavaType) catchType, exception, null, false));
            frameState.apop();
            frameState.push(Kind.Object, checkCast);
            FixedNode catchSuccessor = createTarget(block.successors.get(0), frameState);
            frameState.apop();
            frameState.push(Kind.Object, exception);
            FixedNode nextDispatch = createTarget(nextBlock, frameState);
            checkCast.setNext(catchSuccessor);
            append(new IfNode(currentGraph.unique(new InstanceOfNode((ResolvedJavaType) catchType, exception, null)), checkCast, nextDispatch, 0.5));
        }
    }

    private void appendGoto(FixedNode target) {
        if (lastInstr != null) {
            lastInstr.setNext(target);
        }
    }

    private static boolean isBlockEnd(Node n) {
        return trueSuccessorCount(n) > 1 || n instanceof ReturnNode || n instanceof UnwindNode || n instanceof DeoptimizeNode;
    }

    private static int trueSuccessorCount(Node n) {
        if (n == null) {
            return 0;
        }
        int i = 0;
        for (Node s : n.successors()) {
            if (Util.isFixed(s)) {
                i++;
            }
        }
        return i;
    }

    private void iterateBytecodesForBlock(Block block) {
        if (block.isLoopHeader) {
            // Create the loop header block, which later will merge the backward branches of the
            // loop.
            AbstractEndNode preLoopEnd = currentGraph.add(new EndNode());
            LoopBeginNode loopBegin = currentGraph.add(new LoopBeginNode());
            lastInstr.setNext(preLoopEnd);
            // Add the single non-loop predecessor of the loop header.
            loopBegin.addForwardEnd(preLoopEnd);
            lastInstr = loopBegin;

            // Create phi functions for all local variables and operand stack slots.
            frameState.insertLoopPhis(loopBegin);
            loopBegin.setStateAfter(frameState.create(block.startBci));

            // We have seen all forward branches. All subsequent backward branches will merge to the
            // loop header.
            // This ensures that the loop header has exactly one non-loop predecessor.
            block.firstInstruction = loopBegin;
            // We need to preserve the frame state builder of the loop header so that we can merge
            // values for
            // phi functions, so make a copy of it.
            block.entryState = frameState.copy();

            Debug.log("  created loop header %s", loopBegin);
        }
        assert lastInstr.next() == null : "instructions already appended at block " + block;
        Debug.log("  frameState: %s", frameState);

        int endBCI = stream.endBCI();

        stream.setBCI(block.startBci);
        int bci = block.startBci;
        BytecodesParsed.add(block.endBci - bci);

        while (bci < endBCI) {
            if (graphBuilderConfig.eagerInfopointMode() && lnt != null) {
                currentLineNumber = lnt.getLineNumber(bci);
                if (currentLineNumber != previousLineNumber) {
                    InfopointNode ipn = append(new InfopointNode(InfopointReason.LINE_NUMBER));
                    ipn.setStateAfter(frameState.create(bci));
                    previousLineNumber = currentLineNumber;
                }
            }

            // read the opcode
            int opcode = stream.currentBC();
            traceState();
            traceInstruction(bci, opcode, bci == block.startBci);
            if (bci == entryBCI) {
                if (block.jsrScope != JsrScope.EMPTY_SCOPE) {
                    throw new BailoutException("OSR into a JSR scope is not supported");
                }
                EntryMarkerNode x = append(new EntryMarkerNode());
                frameState.insertProxies(x);
                x.setStateAfter(frameState.create(bci));
            }
            processBytecode(bci, opcode);

            if (lastInstr == null || isBlockEnd(lastInstr) || lastInstr.next() != null) {
                break;
            }

            stream.next();
            bci = stream.currentBCI();

            if (bci > block.endBci) {
                frameState.clearNonLiveLocals(currentBlock.localsLiveOut);
            }
            if (lastInstr instanceof StateSplit) {
                if (lastInstr.getClass() == AbstractBeginNode.class) {
                    // BeginNodes do not need a frame state
                } else {
                    StateSplit stateSplit = (StateSplit) lastInstr;
                    if (stateSplit.stateAfter() == null) {
                        stateSplit.setStateAfter(frameState.create(bci));
                    }
                }
            }
            if (bci < endBCI) {
                if (bci > block.endBci) {
                    assert !block.successors.get(0).isExceptionEntry;
                    assert block.numNormalSuccessors() == 1;
                    // we fell through to the next block, add a goto and break
                    appendGoto(createTarget(block.successors.get(0), frameState));
                    break;
                }
            }
        }
    }

    private void traceState() {
        if (GraalOptions.TraceBytecodeParserLevel >= TRACELEVEL_STATE && Debug.isLogEnabled()) {
            Debug.log(String.format("|   state [nr locals = %d, stack depth = %d, method = %s]", frameState.localsSize(), frameState.stackSize(), method));
            for (int i = 0; i < frameState.localsSize(); ++i) {
                ValueNode value = frameState.localAt(i);
                Debug.log(String.format("|   local[%d] = %-8s : %s", i, value == null ? "bogus" : value.kind().getJavaName(), value));
            }
            for (int i = 0; i < frameState.stackSize(); ++i) {
                ValueNode value = frameState.stackAt(i);
                Debug.log(String.format("|   stack[%d] = %-8s : %s", i, value == null ? "bogus" : value.kind().getJavaName(), value));
            }
        }
    }

    private void processBytecode(int bci, int opcode) {
        int cpi;

        // Checkstyle: stop
        // @formatter:off
        switch (opcode) {
            case NOP            : /* nothing to do */ break;
            case ACONST_NULL    : frameState.apush(appendConstant(Constant.NULL_OBJECT)); break;
            case ICONST_M1      : frameState.ipush(appendConstant(Constant.INT_MINUS_1)); break;
            case ICONST_0       : frameState.ipush(appendConstant(Constant.INT_0)); break;
            case ICONST_1       : frameState.ipush(appendConstant(Constant.INT_1)); break;
            case ICONST_2       : frameState.ipush(appendConstant(Constant.INT_2)); break;
            case ICONST_3       : frameState.ipush(appendConstant(Constant.INT_3)); break;
            case ICONST_4       : frameState.ipush(appendConstant(Constant.INT_4)); break;
            case ICONST_5       : frameState.ipush(appendConstant(Constant.INT_5)); break;
            case LCONST_0       : frameState.lpush(appendConstant(Constant.LONG_0)); break;
            case LCONST_1       : frameState.lpush(appendConstant(Constant.LONG_1)); break;
            case FCONST_0       : frameState.fpush(appendConstant(Constant.FLOAT_0)); break;
            case FCONST_1       : frameState.fpush(appendConstant(Constant.FLOAT_1)); break;
            case FCONST_2       : frameState.fpush(appendConstant(Constant.FLOAT_2)); break;
            case DCONST_0       : frameState.dpush(appendConstant(Constant.DOUBLE_0)); break;
            case DCONST_1       : frameState.dpush(appendConstant(Constant.DOUBLE_1)); break;
            case BIPUSH         : frameState.ipush(appendConstant(Constant.forInt(stream.readByte()))); break;
            case SIPUSH         : frameState.ipush(appendConstant(Constant.forInt(stream.readShort()))); break;
            case LDC            : // fall through
            case LDC_W          : // fall through
            case LDC2_W         : genLoadConstant(stream.readCPI(), opcode); break;
            case ILOAD          : loadLocal(stream.readLocalIndex(), Kind.Int); break;
            case LLOAD          : loadLocal(stream.readLocalIndex(), Kind.Long); break;
            case FLOAD          : loadLocal(stream.readLocalIndex(), Kind.Float); break;
            case DLOAD          : loadLocal(stream.readLocalIndex(), Kind.Double); break;
            case ALOAD          : loadLocal(stream.readLocalIndex(), Kind.Object); break;
            case ILOAD_0        : // fall through
            case ILOAD_1        : // fall through
            case ILOAD_2        : // fall through
            case ILOAD_3        : loadLocal(opcode - ILOAD_0, Kind.Int); break;
            case LLOAD_0        : // fall through
            case LLOAD_1        : // fall through
            case LLOAD_2        : // fall through
            case LLOAD_3        : loadLocal(opcode - LLOAD_0, Kind.Long); break;
            case FLOAD_0        : // fall through
            case FLOAD_1        : // fall through
            case FLOAD_2        : // fall through
            case FLOAD_3        : loadLocal(opcode - FLOAD_0, Kind.Float); break;
            case DLOAD_0        : // fall through
            case DLOAD_1        : // fall through
            case DLOAD_2        : // fall through
            case DLOAD_3        : loadLocal(opcode - DLOAD_0, Kind.Double); break;
            case ALOAD_0        : // fall through
            case ALOAD_1        : // fall through
            case ALOAD_2        : // fall through
            case ALOAD_3        : loadLocal(opcode - ALOAD_0, Kind.Object); break;
            case IALOAD         : genLoadIndexed(Kind.Int   ); break;
            case LALOAD         : genLoadIndexed(Kind.Long  ); break;
            case FALOAD         : genLoadIndexed(Kind.Float ); break;
            case DALOAD         : genLoadIndexed(Kind.Double); break;
            case AALOAD         : genLoadIndexed(Kind.Object); break;
            case BALOAD         : genLoadIndexed(Kind.Byte  ); break;
            case CALOAD         : genLoadIndexed(Kind.Char  ); break;
            case SALOAD         : genLoadIndexed(Kind.Short ); break;
            case ISTORE         : storeLocal(Kind.Int, stream.readLocalIndex()); break;
            case LSTORE         : storeLocal(Kind.Long, stream.readLocalIndex()); break;
            case FSTORE         : storeLocal(Kind.Float, stream.readLocalIndex()); break;
            case DSTORE         : storeLocal(Kind.Double, stream.readLocalIndex()); break;
            case ASTORE         : storeLocal(Kind.Object, stream.readLocalIndex()); break;
            case ISTORE_0       : // fall through
            case ISTORE_1       : // fall through
            case ISTORE_2       : // fall through
            case ISTORE_3       : storeLocal(Kind.Int, opcode - ISTORE_0); break;
            case LSTORE_0       : // fall through
            case LSTORE_1       : // fall through
            case LSTORE_2       : // fall through
            case LSTORE_3       : storeLocal(Kind.Long, opcode - LSTORE_0); break;
            case FSTORE_0       : // fall through
            case FSTORE_1       : // fall through
            case FSTORE_2       : // fall through
            case FSTORE_3       : storeLocal(Kind.Float, opcode - FSTORE_0); break;
            case DSTORE_0       : // fall through
            case DSTORE_1       : // fall through
            case DSTORE_2       : // fall through
            case DSTORE_3       : storeLocal(Kind.Double, opcode - DSTORE_0); break;
            case ASTORE_0       : // fall through
            case ASTORE_1       : // fall through
            case ASTORE_2       : // fall through
            case ASTORE_3       : storeLocal(Kind.Object, opcode - ASTORE_0); break;
            case IASTORE        : genStoreIndexed(Kind.Int   ); break;
            case LASTORE        : genStoreIndexed(Kind.Long  ); break;
            case FASTORE        : genStoreIndexed(Kind.Float ); break;
            case DASTORE        : genStoreIndexed(Kind.Double); break;
            case AASTORE        : genStoreIndexed(Kind.Object); break;
            case BASTORE        : genStoreIndexed(Kind.Byte  ); break;
            case CASTORE        : genStoreIndexed(Kind.Char  ); break;
            case SASTORE        : genStoreIndexed(Kind.Short ); break;
            case POP            : // fall through
            case POP2           : // fall through
            case DUP            : // fall through
            case DUP_X1         : // fall through
            case DUP_X2         : // fall through
            case DUP2           : // fall through
            case DUP2_X1        : // fall through
            case DUP2_X2        : // fall through
            case SWAP           : stackOp(opcode); break;
            case IADD           : // fall through
            case ISUB           : // fall through
            case IMUL           : genArithmeticOp(Kind.Int, opcode); break;
            case IDIV           : // fall through
            case IREM           : genIntegerDivOp(Kind.Int, opcode); break;
            case LADD           : // fall through
            case LSUB           : // fall through
            case LMUL           : genArithmeticOp(Kind.Long, opcode); break;
            case LDIV           : // fall through
            case LREM           : genIntegerDivOp(Kind.Long, opcode); break;
            case FADD           : // fall through
            case FSUB           : // fall through
            case FMUL           : // fall through
            case FDIV           : // fall through
            case FREM           : genArithmeticOp(Kind.Float, opcode); break;
            case DADD           : // fall through
            case DSUB           : // fall through
            case DMUL           : // fall through
            case DDIV           : // fall through
            case DREM           : genArithmeticOp(Kind.Double, opcode); break;
            case INEG           : genNegateOp(Kind.Int); break;
            case LNEG           : genNegateOp(Kind.Long); break;
            case FNEG           : genNegateOp(Kind.Float); break;
            case DNEG           : genNegateOp(Kind.Double); break;
            case ISHL           : // fall through
            case ISHR           : // fall through
            case IUSHR          : genShiftOp(Kind.Int, opcode); break;
            case IAND           : // fall through
            case IOR            : // fall through
            case IXOR           : genLogicOp(Kind.Int, opcode); break;
            case LSHL           : // fall through
            case LSHR           : // fall through
            case LUSHR          : genShiftOp(Kind.Long, opcode); break;
            case LAND           : // fall through
            case LOR            : // fall through
            case LXOR           : genLogicOp(Kind.Long, opcode); break;
            case IINC           : genIncrement(); break;
            case I2L            : genConvert(ConvertNode.Op.I2L); break;
            case I2F            : genConvert(ConvertNode.Op.I2F); break;
            case I2D            : genConvert(ConvertNode.Op.I2D); break;
            case L2I            : genConvert(ConvertNode.Op.L2I); break;
            case L2F            : genConvert(ConvertNode.Op.L2F); break;
            case L2D            : genConvert(ConvertNode.Op.L2D); break;
            case F2I            : genConvert(ConvertNode.Op.F2I); break;
            case F2L            : genConvert(ConvertNode.Op.F2L); break;
            case F2D            : genConvert(ConvertNode.Op.F2D); break;
            case D2I            : genConvert(ConvertNode.Op.D2I); break;
            case D2L            : genConvert(ConvertNode.Op.D2L); break;
            case D2F            : genConvert(ConvertNode.Op.D2F); break;
            case I2B            : genConvert(ConvertNode.Op.I2B); break;
            case I2C            : genConvert(ConvertNode.Op.I2C); break;
            case I2S            : genConvert(ConvertNode.Op.I2S); break;
            case LCMP           : genCompareOp(Kind.Long, false); break;
            case FCMPL          : genCompareOp(Kind.Float, true); break;
            case FCMPG          : genCompareOp(Kind.Float, false); break;
            case DCMPL          : genCompareOp(Kind.Double, true); break;
            case DCMPG          : genCompareOp(Kind.Double, false); break;
            case IFEQ           : genIfZero(Condition.EQ); break;
            case IFNE           : genIfZero(Condition.NE); break;
            case IFLT           : genIfZero(Condition.LT); break;
            case IFGE           : genIfZero(Condition.GE); break;
            case IFGT           : genIfZero(Condition.GT); break;
            case IFLE           : genIfZero(Condition.LE); break;
            case IF_ICMPEQ      : genIfSame(Kind.Int, Condition.EQ); break;
            case IF_ICMPNE      : genIfSame(Kind.Int, Condition.NE); break;
            case IF_ICMPLT      : genIfSame(Kind.Int, Condition.LT); break;
            case IF_ICMPGE      : genIfSame(Kind.Int, Condition.GE); break;
            case IF_ICMPGT      : genIfSame(Kind.Int, Condition.GT); break;
            case IF_ICMPLE      : genIfSame(Kind.Int, Condition.LE); break;
            case IF_ACMPEQ      : genIfSame(Kind.Object, Condition.EQ); break;
            case IF_ACMPNE      : genIfSame(Kind.Object, Condition.NE); break;
            case GOTO           : genGoto(); break;
            case JSR            : genJsr(stream.readBranchDest()); break;
            case RET            : genRet(stream.readLocalIndex()); break;
            case TABLESWITCH    : genSwitch(new BytecodeTableSwitch(stream(), bci())); break;
            case LOOKUPSWITCH   : genSwitch(new BytecodeLookupSwitch(stream(), bci())); break;
            case IRETURN        : genReturn(frameState.ipop()); break;
            case LRETURN        : genReturn(frameState.lpop()); break;
            case FRETURN        : genReturn(frameState.fpop()); break;
            case DRETURN        : genReturn(frameState.dpop()); break;
            case ARETURN        : genReturn(frameState.apop()); break;
            case RETURN         : genReturn(null); break;
            case GETSTATIC      : cpi = stream.readCPI(); genGetStatic(lookupField(cpi, opcode)); break;
            case PUTSTATIC      : cpi = stream.readCPI(); genPutStatic(lookupField(cpi, opcode)); break;
            case GETFIELD       : cpi = stream.readCPI(); genGetField(lookupField(cpi, opcode)); break;
            case PUTFIELD       : cpi = stream.readCPI(); genPutField(lookupField(cpi, opcode)); break;
            case INVOKEVIRTUAL  : cpi = stream.readCPI(); genInvokeVirtual(lookupMethod(cpi, opcode)); break;
            case INVOKESPECIAL  : cpi = stream.readCPI(); genInvokeSpecial(lookupMethod(cpi, opcode)); break;
            case INVOKESTATIC   : cpi = stream.readCPI(); genInvokeStatic(lookupMethod(cpi, opcode)); break;
            case INVOKEINTERFACE: cpi = stream.readCPI(); genInvokeInterface(lookupMethod(cpi, opcode)); break;
            case INVOKEDYNAMIC  : cpi = stream.readCPI4(); genInvokeDynamic(lookupMethod(cpi, opcode)); break;
            case NEW            : genNewInstance(stream.readCPI()); break;
            case NEWARRAY       : genNewPrimitiveArray(stream.readLocalIndex()); break;
            case ANEWARRAY      : genNewObjectArray(stream.readCPI()); break;
            case ARRAYLENGTH    : genArrayLength(); break;
            case ATHROW         : genThrow(); break;
            case CHECKCAST      : genCheckCast(); break;
            case INSTANCEOF     : genInstanceOf(); break;
            case MONITORENTER   : genMonitorEnter(frameState.apop()); break;
            case MONITOREXIT    : genMonitorExit(frameState.apop()); break;
            case MULTIANEWARRAY : genNewMultiArray(stream.readCPI()); break;
            case IFNULL         : genIfNull(Condition.EQ); break;
            case IFNONNULL      : genIfNull(Condition.NE); break;
            case GOTO_W         : genGoto(); break;
            case JSR_W          : genJsr(stream.readBranchDest()); break;
            case BREAKPOINT:
                throw new BailoutException("concurrent setting of breakpoint");
            default:
                throw new BailoutException("Unsupported opcode " + opcode + " (" + nameOf(opcode) + ") [bci=" + bci + "]");
        }
        // @formatter:on
        // Checkstyle: resume
    }

    private void traceInstruction(int bci, int opcode, boolean blockStart) {
        if (GraalOptions.TraceBytecodeParserLevel >= TRACELEVEL_INSTRUCTIONS && Debug.isLogEnabled()) {
            StringBuilder sb = new StringBuilder(40);
            sb.append(blockStart ? '+' : '|');
            if (bci < 10) {
                sb.append("  ");
            } else if (bci < 100) {
                sb.append(' ');
            }
            sb.append(bci).append(": ").append(Bytecodes.nameOf(opcode));
            for (int i = bci + 1; i < stream.nextBCI(); ++i) {
                sb.append(' ').append(stream.readUByte(i));
            }
            if (!currentBlock.jsrScope.isEmpty()) {
                sb.append(' ').append(currentBlock.jsrScope);
            }
            Debug.log(sb.toString());
        }
    }

    private void genArrayLength() {
        frameState.ipush(append(new ArrayLengthNode(frameState.apop())));
    }
}
