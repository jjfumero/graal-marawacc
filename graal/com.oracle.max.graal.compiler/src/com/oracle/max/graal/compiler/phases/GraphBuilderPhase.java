/*
 * Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.graal.compiler.phases;

import static com.sun.cri.bytecode.Bytecodes.*;
import static java.lang.reflect.Modifier.*;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.max.graal.compiler.*;
import com.oracle.max.graal.compiler.debug.*;
import com.oracle.max.graal.compiler.graph.*;
import com.oracle.max.graal.compiler.graph.BlockMap.Block;
import com.oracle.max.graal.compiler.graph.BlockMap.BranchOverride;
import com.oracle.max.graal.compiler.graph.BlockMap.DeoptBlock;
import com.oracle.max.graal.compiler.graph.BlockMap.ExceptionBlock;
import com.oracle.max.graal.compiler.ir.*;
import com.oracle.max.graal.compiler.ir.Deoptimize.DeoptAction;
import com.oracle.max.graal.compiler.schedule.*;
import com.oracle.max.graal.compiler.util.*;
import com.oracle.max.graal.compiler.util.LoopUtil.Loop;
import com.oracle.max.graal.compiler.value.*;
import com.oracle.max.graal.graph.*;
import com.sun.cri.bytecode.*;
import com.sun.cri.ci.*;
import com.sun.cri.ri.*;
import com.sun.cri.ri.RiType.Representation;

/**
 * The {@code GraphBuilder} class parses the bytecode of a method and builds the IR graph.
 * A number of optimizations may be performed during parsing of the bytecode, including value
 * numbering, inlining, constant folding, strength reduction, etc.
 */
public final class GraphBuilderPhase extends Phase {

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

    private final GraalCompilation compilation;
    private CompilerGraph graph;

    private final CiStatistics stats;
    private final RiRuntime runtime;
    private final RiMethod method;
    private final RiConstantPool constantPool;
    private RiExceptionHandler[] exceptionHandlers;

    private final BytecodeStream stream;           // the bytecode stream
    private final LogStream log;
    private FrameStateBuilder frameState;          // the current execution state

    // bci-to-block mapping
    private Block[] blockFromBci;
    private ArrayList<Block> blockList;
    private HashMap<Integer, BranchOverride> branchOverride;

    private int nextBlockNumber;

    private Value methodSynchronizedObject;
    private CiExceptionHandler unwindHandler;

    private ExceptionBlock unwindBlock;
    private Block returnBlock;

    private boolean storeResultGraph;

    // the worklist of blocks, sorted by depth first number
    private final PriorityQueue<Block> workList = new PriorityQueue<Block>(10, new Comparator<Block>() {
        public int compare(Block o1, Block o2) {
            return o1.blockID - o2.blockID;
        }
    });

    private FixedNodeWithNext lastInstr;                 // the last instruction added

    private final Set<Block> blocksOnWorklist = new HashSet<Block>();
    private final Set<Block> blocksVisited = new HashSet<Block>();

    private final boolean createUnwind;

    public static final Map<RiMethod, CompilerGraph> cachedGraphs = new WeakHashMap<RiMethod, CompilerGraph>();

    /**
     * Creates a new, initialized, {@code GraphBuilder} instance for a given compilation.
     *
     * @param compilation the compilation
     * @param ir the IR to build the graph into
     * @param graph
     */
    public GraphBuilderPhase(GraalCompilation compilation, RiMethod method, boolean createUnwind, boolean inline) {
        super(inline ? "BuildInlineGraph" : "BuildGraph");
        this.compilation = compilation;

        this.runtime = compilation.runtime;
        this.method = method;
        this.stats = compilation.stats;
        this.log = GraalOptions.TraceBytecodeParserLevel > 0 ? new LogStream(TTY.out()) : null;
        this.stream = new BytecodeStream(method.code());

        this.constantPool = runtime.getConstantPool(method);
        this.createUnwind = createUnwind;
        this.storeResultGraph = GraalOptions.CacheGraphs;
    }

    @Override
    protected void run(Graph graph) {
        assert graph != null;
        this.graph = (CompilerGraph) graph;
        this.frameState = new FrameStateBuilder(method, graph);
        build();
    }

    /**
     * Builds the graph for a the specified {@code IRScope}.
     *
     * @param createUnwind setting this to true will always generate an unwind block, even if there is no exception
     *            handler and the method is not synchronized
     */
    private void build() {
        if (log != null) {
            log.println();
            log.println("Compiling " + method);
        }

        // 2. compute the block map, setup exception handlers and get the entrypoint(s)
        BlockMap blockMap = compilation.getBlockMap(method);
        this.branchOverride = blockMap.branchOverride;

        exceptionHandlers = blockMap.exceptionHandlers();
        blockList = new ArrayList<Block>(blockMap.blocks);
        blockFromBci = new Block[method.codeSize()];
        for (int i = 0; i < blockList.size(); i++) {
            int blockID = nextBlockNumber();
            assert blockID == i;
            Block block = blockList.get(i);
            if (block.startBci >= 0 && !(block instanceof BlockMap.DeoptBlock)) {
                blockFromBci[block.startBci] = block;
            }
        }

        // 1. create the start block
        Block startBlock = nextBlock(FixedNodeWithNext.SYNCHRONIZATION_ENTRY_BCI);
        markOnWorkList(startBlock);
        lastInstr = (FixedNodeWithNext) createTarget(startBlock, frameState);
        graph.start().setStart(lastInstr);

        if (isSynchronized(method.accessFlags())) {
            // 4A.1 add a monitor enter to the start block
            methodSynchronizedObject = synchronizedObject(frameState, method);
            genMonitorEnter(methodSynchronizedObject, FixedNodeWithNext.SYNCHRONIZATION_ENTRY_BCI);
            // 4A.2 finish the start block
            finishStartBlock(startBlock);

            // 4A.3 setup an exception handler to unlock the root method synchronized object
            unwindHandler = new CiExceptionHandler(0, method.code().length, FixedNodeWithNext.SYNCHRONIZATION_ENTRY_BCI, 0, null);
        } else {
            // 4B.1 simply finish the start block
            finishStartBlock(startBlock);

            if (createUnwind) {
                unwindHandler = new CiExceptionHandler(0, method.code().length, FixedNodeWithNext.SYNCHRONIZATION_ENTRY_BCI, 0, null);
            }
        }

        // 5. SKIPPED: look for intrinsics

        // 6B.1 do the normal parsing
        addToWorkList(blockFromBci[0]);
        iterateAllBlocks();

        List<Loop> loops = LoopUtil.computeLoops(graph);
        NodeBitMap loopExits = graph.createNodeBitMap();
        for (Loop loop : loops) {
            loopExits.markAll(loop.exist());
        }

        // remove Placeholders
        for (Node n : graph.getNodes()) {
            if (n instanceof Placeholder && !loopExits.isMarked(n)) {
                Placeholder p = (Placeholder) n;
                p.replaceAndDelete(p.next());
            }
        }

        // remove FrameStates
        for (Node n : graph.getNodes()) {
            if (n instanceof FrameState) {
                if (n.usages().size() == 0 && n.predecessors().size() == 0) {
                    n.delete();
                }
            }
        }

        if (storeResultGraph) {
            // Create duplicate graph.
            CompilerGraph duplicate = new CompilerGraph(null);
            Map<Node, Node> replacements = new HashMap<Node, Node>();
            replacements.put(graph.start(), duplicate.start());
            duplicate.addDuplicate(graph.getNodes(), replacements);

            cachedGraphs.put(method, duplicate);
        }
    }

    private int nextBlockNumber() {
        stats.blockCount++;
        return nextBlockNumber++;
    }

    private Block nextBlock(int bci) {
        Block block = new Block();
        block.startBci = bci;
        block.endBci = bci;
        block.blockID = nextBlockNumber();
        return block;
    }

    private Block unwindBlock(int bci) {
        if (unwindBlock == null) {
            unwindBlock = new ExceptionBlock();
            unwindBlock.startBci = -1;
            unwindBlock.endBci = -1;
            unwindBlock.deoptBci = bci;
            unwindBlock.blockID = nextBlockNumber();
            addToWorkList(unwindBlock);
        }
        return unwindBlock;
    }

    private Block returnBlock(int bci) {
        if (returnBlock == null) {
            returnBlock = new Block();
            returnBlock.startBci = bci;
            returnBlock.endBci = bci;
            returnBlock.blockID = nextBlockNumber();
            addToWorkList(returnBlock);
        }
        return returnBlock;
    }

    private void markOnWorkList(Block block) {
        blocksOnWorklist.add(block);
    }

    private boolean isOnWorkList(Block block) {
        return blocksOnWorklist.contains(block);
    }

    private void markVisited(Block block) {
        blocksVisited.add(block);
    }

    private boolean isVisited(Block block) {
        return blocksVisited.contains(block);
    }

    private void finishStartBlock(Block startBlock) {
        assert bci() == 0;
        appendGoto(createTargetAt(0, frameState));
    }

    public void mergeOrClone(Block target, FrameStateAccess newState) {
        StateSplit first = (StateSplit) target.firstInstruction;

        if (target.isLoopHeader && isVisited(target)) {
            first = (StateSplit) loopBegin(target).loopEnd().singlePredecessor();
        }

        int bci = target.startBci;
        if (target instanceof ExceptionBlock) {
            bci = ((ExceptionBlock) target).deoptBci;
        }

        FrameState existingState = first.stateAfter();

        if (existingState == null) {
            // copy state because it is modified
            first.setStateAfter(newState.duplicate(bci));
        } else {
            if (!GraalOptions.AssumeVerifiedBytecode && !existingState.isCompatibleWith(newState)) {
                // stacks or locks do not match--bytecodes would not verify
                TTY.println(existingState.toString());
                TTY.println(newState.duplicate(0).toString());
                throw new CiBailout("stack or locks do not match");
            }
            assert existingState.localsSize() == newState.localsSize();
            assert existingState.stackSize() == newState.stackSize();

            if (first instanceof Placeholder) {
                Placeholder p = (Placeholder) first;
                if (p.predecessors().size() == 0) {
                    p.setStateAfter(newState.duplicate(bci));
                    return;
                } else {
                    Merge merge = new Merge(graph);
                    assert p.predecessors().size() == 1 : "predecessors size: " + p.predecessors().size();
                    FixedNode next = p.next();
                    p.setNext(null);
                    EndNode end = new EndNode(graph);
                    p.replaceAndDelete(end);
                    merge.setNext(next);
                    merge.addEnd(end);
                    merge.setStateAfter(existingState);
                    if (!(next instanceof LoopEnd)) {
                        target.firstInstruction = merge;
                    }
                    first = merge;
                }
            }

            existingState.merge((Merge) first, newState);
        }
    }

    private void insertLoopPhis(LoopBegin loopBegin, FrameState newState) {
        int stackSize = newState.stackSize();
        for (int i = 0; i < stackSize; i++) {
            // always insert phis for the stack
            Value x = newState.stackAt(i);
            if (x != null) {
                newState.setupPhiForStack(loopBegin, i).addInput(x);
            }
        }
        int localsSize = newState.localsSize();
        for (int i = 0; i < localsSize; i++) {
            Value x = newState.localAt(i);
            if (x != null) {
                newState.setupPhiForLocal(loopBegin, i).addInput(x);
            }
        }
    }

    public BytecodeStream stream() {
        return stream;
    }

    public int bci() {
        return stream.currentBCI();
    }

    private void loadLocal(int index, CiKind kind) {
        frameState.push(kind, frameState.loadLocal(index));
    }

    private void storeLocal(CiKind kind, int index) {
        frameState.storeLocal(index, frameState.pop(kind));
    }

    public boolean covers(RiExceptionHandler handler, int bci) {
        return handler.startBCI() <= bci && bci < handler.endBCI();
    }

    public boolean isCatchAll(RiExceptionHandler handler) {
        return handler.catchTypeCPI() == 0;
    }

    private FixedNode handleException(Value exceptionObject, int bci) {
        assert bci == FixedNodeWithNext.SYNCHRONIZATION_ENTRY_BCI || bci == bci() : "invalid bci";

        if (GraalOptions.UseExceptionProbability && method.invocationCount() > GraalOptions.MatureInvocationCount) {
            if (exceptionObject == null && method.exceptionProbability(bci) == 0) {
                return null;
            }
        }

        RiExceptionHandler firstHandler = null;
        // join with all potential exception handlers
        if (exceptionHandlers != null) {
            for (RiExceptionHandler handler : exceptionHandlers) {
                // if the handler covers this bytecode index, add it to the list
                if (covers(handler, bci)) {
                    firstHandler = handler;
                    break;
                }
            }
        }

        if (firstHandler == null) {
            firstHandler = unwindHandler;
        }

        if (firstHandler != null) {
            compilation.setHasExceptionHandlers();

            Block dispatchBlock = null;
            for (Block block : blockList) {
                if (block instanceof ExceptionBlock) {
                    ExceptionBlock excBlock = (ExceptionBlock) block;
                    if (excBlock.handler == firstHandler) {
                        dispatchBlock = block;
                        break;
                    }
                }
            }
            // if there's no dispatch block then the catch block needs to be a catch all
            if (dispatchBlock == null) {
                assert isCatchAll(firstHandler);
                int handlerBCI = firstHandler.handlerBCI();
                if (handlerBCI == FixedNodeWithNext.SYNCHRONIZATION_ENTRY_BCI) {
                    dispatchBlock = unwindBlock(bci);
                } else {
                    dispatchBlock = blockFromBci[handlerBCI];
                }
            }
            Placeholder p = new Placeholder(graph);
            p.setStateAfter(frameState.duplicateWithoutStack(bci));

            Value currentExceptionObject;
            if (exceptionObject == null) {
                currentExceptionObject = new ExceptionObject(graph);
            } else {
                currentExceptionObject = exceptionObject;
            }
            FrameState stateWithException = frameState.duplicateWithException(bci, currentExceptionObject);
            FixedNode target = createTarget(dispatchBlock, stateWithException);
            if (exceptionObject == null) {
                ExceptionObject eObj = (ExceptionObject) currentExceptionObject;
                eObj.setNext(target);
                p.setNext(eObj);
            } else {
                p.setNext(target);
            }
            return p;
        }
        return null;
    }

    private void genLoadConstant(int cpi) {
        Object con = constantPool.lookupConstant(cpi);

        if (con instanceof RiType) {
            // this is a load of class constant which might be unresolved
            RiType riType = (RiType) con;
            if (!riType.isResolved()) {
                storeResultGraph = false;
                append(new Deoptimize(DeoptAction.InvalidateRecompile, graph));
                frameState.push(CiKind.Object, append(Constant.forObject(null, graph)));
            } else {
                frameState.push(CiKind.Object, append(new Constant(riType.getEncoding(Representation.JavaClass), graph)));
            }
        } else if (con instanceof CiConstant) {
            CiConstant constant = (CiConstant) con;
            frameState.push(constant.kind.stackKind(), appendConstant(constant));
        } else {
            throw new Error("lookupConstant returned an object of incorrect type");
        }
    }

    private void genLoadIndexed(CiKind kind) {
        Value index = frameState.ipop();
        Value array = frameState.apop();
        Value length = append(new ArrayLength(array, graph));
        Value v = append(new LoadIndexed(array, index, length, kind, graph));
        frameState.push(kind.stackKind(), v);
    }

    private void genStoreIndexed(CiKind kind) {
        Value value = frameState.pop(kind.stackKind());
        Value index = frameState.ipop();
        Value array = frameState.apop();
        Value length = append(new ArrayLength(array, graph));
        StoreIndexed result = new StoreIndexed(array, index, length, kind, value, graph);
        append(result);
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
                Value w = frameState.xpop();
                frameState.xpush(w);
                frameState.xpush(w);
                break;
            }
            case DUP_X1: {
                Value w1 = frameState.xpop();
                Value w2 = frameState.xpop();
                frameState.xpush(w1);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case DUP_X2: {
                Value w1 = frameState.xpop();
                Value w2 = frameState.xpop();
                Value w3 = frameState.xpop();
                frameState.xpush(w1);
                frameState.xpush(w3);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case DUP2: {
                Value w1 = frameState.xpop();
                Value w2 = frameState.xpop();
                frameState.xpush(w2);
                frameState.xpush(w1);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case DUP2_X1: {
                Value w1 = frameState.xpop();
                Value w2 = frameState.xpop();
                Value w3 = frameState.xpop();
                frameState.xpush(w2);
                frameState.xpush(w1);
                frameState.xpush(w3);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case DUP2_X2: {
                Value w1 = frameState.xpop();
                Value w2 = frameState.xpop();
                Value w3 = frameState.xpop();
                Value w4 = frameState.xpop();
                frameState.xpush(w2);
                frameState.xpush(w1);
                frameState.xpush(w4);
                frameState.xpush(w3);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case SWAP: {
                Value w1 = frameState.xpop();
                Value w2 = frameState.xpop();
                frameState.xpush(w1);
                frameState.xpush(w2);
                break;
            }
            default:
                throw Util.shouldNotReachHere();
        }

    }

    private void genArithmeticOp(CiKind kind, int opcode) {
        genArithmeticOp(kind, opcode, false);
    }

    private void genArithmeticOp(CiKind result, int opcode, boolean canTrap) {
        Value y = frameState.pop(result);
        Value x = frameState.pop(result);
        boolean isStrictFP = isStrict(method.accessFlags());
        Arithmetic v;
        switch(opcode){
            case IADD:
            case LADD: v = new IntegerAdd(result, x, y, graph); break;
            case FADD:
            case DADD: v = new FloatAdd(result, x, y, isStrictFP, graph); break;
            case ISUB:
            case LSUB: v = new IntegerSub(result, x, y, graph); break;
            case FSUB:
            case DSUB: v = new FloatSub(result, x, y, isStrictFP, graph); break;
            case IMUL:
            case LMUL: v = new IntegerMul(result, x, y, graph); break;
            case FMUL:
            case DMUL: v = new FloatMul(result, x, y, isStrictFP, graph); break;
            case IDIV:
            case LDIV: v = new IntegerDiv(result, x, y, graph); break;
            case FDIV:
            case DDIV: v = new FloatDiv(result, x, y, isStrictFP, graph); break;
            case IREM:
            case LREM: v = new IntegerRem(result, x, y, graph); break;
            case FREM:
            case DREM: v = new FloatRem(result, x, y, isStrictFP, graph); break;
            default:
                throw new CiBailout("should not reach");
        }
        Value result1 = append(v);
        if (canTrap) {
            append(new ValueAnchor(result1, graph));
        }
        frameState.push(result, result1);
    }

    private void genNegateOp(CiKind kind) {
        frameState.push(kind, append(new Negate(frameState.pop(kind), graph)));
    }

    private void genShiftOp(CiKind kind, int opcode) {
        Value s = frameState.ipop();
        Value x = frameState.pop(kind);
        Shift v;
        switch(opcode){
            case ISHL:
            case LSHL: v = new LeftShift(kind, x, s, graph); break;
            case ISHR:
            case LSHR: v = new RightShift(kind, x, s, graph); break;
            case IUSHR:
            case LUSHR: v = new UnsignedRightShift(kind, x, s, graph); break;
            default:
                throw new CiBailout("should not reach");
        }
        frameState.push(kind, append(v));
    }

    private void genLogicOp(CiKind kind, int opcode) {
        Value y = frameState.pop(kind);
        Value x = frameState.pop(kind);
        Logic v;
        switch(opcode){
            case IAND:
            case LAND: v = new And(kind, x, y, graph); break;
            case IOR:
            case LOR: v = new Or(kind, x, y, graph); break;
            case IXOR:
            case LXOR: v = new Xor(kind, x, y, graph); break;
            default:
                throw new CiBailout("should not reach");
        }
        frameState.push(kind, append(v));
    }

    private void genCompareOp(CiKind kind, int opcode, CiKind resultKind) {
        Value y = frameState.pop(kind);
        Value x = frameState.pop(kind);
        Value value = append(new NormalizeCompare(opcode, resultKind, x, y, graph));
        if (!resultKind.isVoid()) {
            frameState.ipush(value);
        }
    }

    private void genConvert(int opcode, CiKind from, CiKind to) {
        CiKind tt = to.stackKind();
        frameState.push(tt, append(new Convert(opcode, frameState.pop(from.stackKind()), tt, graph)));
    }

    private void genIncrement() {
        int index = stream().readLocalIndex();
        int delta = stream().readIncrement();
        Value x = frameState.localAt(index);
        Value y = append(Constant.forInt(delta, graph));
        frameState.storeLocal(index, append(new IntegerAdd(CiKind.Int, x, y, graph)));
    }

    private void genGoto(int fromBCI, int toBCI) {
        appendGoto(createTargetAt(toBCI, frameState));
    }

    private void ifNode(Value x, Condition cond, Value y) {
        assert !x.isDeleted() && !y.isDeleted();
        If ifNode = new If(new Compare(x, cond, y, graph), graph);
        append(ifNode);
        BlockMap.BranchOverride override = branchOverride.get(bci());
        FixedNode tsucc;
        if (override == null || override.taken == false) {
            tsucc = createTargetAt(stream().readBranchDest(), frameState);
        } else {
            tsucc = createTarget(override.block, frameState);
        }
        ifNode.setBlockSuccessor(0, tsucc);
        FixedNode fsucc;
        if (override == null || override.taken == true) {
            fsucc = createTargetAt(stream().nextBCI(), frameState);
        } else {
            fsucc = createTarget(override.block, frameState);
        }
        ifNode.setBlockSuccessor(1, fsucc);
    }

    private void genIfZero(Condition cond) {
        Value y = appendConstant(CiConstant.INT_0);
        Value x = frameState.ipop();
        ifNode(x, cond, y);
    }

    private void genIfNull(Condition cond) {
        Value y = appendConstant(CiConstant.NULL_OBJECT);
        Value x = frameState.apop();
        ifNode(x, cond, y);
    }

    private void genIfSame(CiKind kind, Condition cond) {
        Value y = frameState.pop(kind);
        Value x = frameState.pop(kind);
        assert !x.isDeleted() && !y.isDeleted();
        ifNode(x, cond, y);
    }

    private void genThrow(int bci) {
        Value exception = frameState.apop();
        FixedGuard node = new FixedGuard(graph);
        node.setNode(new IsNonNull(exception, graph));
        append(node);

        FixedNode entry = handleException(exception, bci);
        if (entry != null) {
            append(entry);
        } else {
            frameState.clearStack();
            frameState.apush(exception);
            appendGoto(createTarget(unwindBlock(bci), frameState));
        }
    }

    private void genCheckCast() {
        int cpi = stream().readCPI();
        RiType type = constantPool.lookupType(cpi, CHECKCAST);
        boolean isInitialized = type.isResolved();
        Constant typeInstruction = genTypeOrDeopt(RiType.Representation.ObjectHub, type, isInitialized, cpi);
        Value object = frameState.apop();
        if (typeInstruction != null) {
            frameState.apush(append(new CheckCast(typeInstruction, object, graph)));
        } else {
            frameState.apush(appendConstant(CiConstant.NULL_OBJECT));
        }
    }

    private void genInstanceOf() {
        int cpi = stream().readCPI();
        RiType type = constantPool.lookupType(cpi, INSTANCEOF);
        boolean isInitialized = type.isResolved();
        Constant typeInstruction = genTypeOrDeopt(RiType.Representation.ObjectHub, type, isInitialized, cpi);
        Value object = frameState.apop();
        if (typeInstruction != null) {
            frameState.ipush(append(new MaterializeNode(new InstanceOf(typeInstruction, object, graph), graph)));
        } else {
            frameState.ipush(appendConstant(CiConstant.INT_0));
        }
    }

    void genNewInstance(int cpi) {
        RiType type = constantPool.lookupType(cpi, NEW);
        if (type.isResolved()) {
            NewInstance n = new NewInstance(type, cpi, constantPool, graph);
            frameState.apush(append(n));
        } else {
            storeResultGraph = false;
            append(new Deoptimize(DeoptAction.InvalidateRecompile, graph));
            frameState.apush(appendConstant(CiConstant.NULL_OBJECT));
        }
    }

    private void genNewTypeArray(int typeCode) {
        CiKind kind = CiKind.fromArrayTypeCode(typeCode);
        RiType elementType = runtime.asRiType(kind);
        NewTypeArray nta = new NewTypeArray(frameState.ipop(), elementType, graph);
        frameState.apush(append(nta));
    }

    private void genNewObjectArray(int cpi) {
        RiType type = constantPool.lookupType(cpi, ANEWARRAY);
        Value length = frameState.ipop();
        if (type.isResolved()) {
            NewArray n = new NewObjectArray(type, length, graph);
            frameState.apush(append(n));
        } else {
            storeResultGraph = false;
            append(new Deoptimize(DeoptAction.InvalidateRecompile, graph));
            frameState.apush(appendConstant(CiConstant.NULL_OBJECT));
        }

    }

    private void genNewMultiArray(int cpi) {
        RiType type = constantPool.lookupType(cpi, MULTIANEWARRAY);
        int rank = stream().readUByte(bci() + 3);
        Value[] dims = new Value[rank];
        for (int i = rank - 1; i >= 0; i--) {
            dims[i] = frameState.ipop();
        }
        if (type.isResolved()) {
            NewArray n = new NewMultiArray(type, dims, cpi, constantPool, graph);
            frameState.apush(append(n));
        } else {
            storeResultGraph = false;
            append(new Deoptimize(DeoptAction.InvalidateRecompile, graph));
            frameState.apush(appendConstant(CiConstant.NULL_OBJECT));
        }
    }

    private void genGetField(int cpi, RiField field) {
        CiKind kind = field.kind();
        Value receiver = frameState.apop();
        if (field.isResolved() && field.holder().isInitialized()) {
            LoadField load = new LoadField(receiver, field, graph);
            appendOptimizedLoadField(kind, load);
        } else {
            storeResultGraph = false;
            append(new Deoptimize(DeoptAction.InvalidateRecompile, graph));
            frameState.push(kind.stackKind(), append(Constant.defaultForKind(kind, graph)));
        }
    }

    private void genPutField(int cpi, RiField field) {
        Value value = frameState.pop(field.kind().stackKind());
        Value receiver = frameState.apop();
        if (field.isResolved() && field.holder().isInitialized()) {
            StoreField store = new StoreField(receiver, field, value, graph);
            appendOptimizedStoreField(store);
        } else {
            storeResultGraph = false;
            append(new Deoptimize(DeoptAction.InvalidateRecompile, graph));
        }
    }

    private void genGetStatic(int cpi, RiField field) {
        RiType holder = field.holder();
        boolean isInitialized = field.isResolved() && field.holder().isInitialized();
        CiConstant constantValue = null;
        if (isInitialized) {
            constantValue = field.constantValue(null);
        }
        if (constantValue != null) {
            frameState.push(constantValue.kind.stackKind(), appendConstant(constantValue));
        } else {
            Value container = genTypeOrDeopt(RiType.Representation.StaticFields, holder, isInitialized, cpi);
            CiKind kind = field.kind();
            if (container != null) {
                LoadField load = new LoadField(container, field, graph);
                appendOptimizedLoadField(kind, load);
            } else {
                // deopt will be generated by genTypeOrDeopt, not needed here
                frameState.push(kind.stackKind(), append(Constant.defaultForKind(kind, graph)));
            }
        }
    }

    private void genPutStatic(int cpi, RiField field) {
        RiType holder = field.holder();
        Value container = genTypeOrDeopt(RiType.Representation.StaticFields, holder, field.isResolved() && field.holder().isInitialized(), cpi);
        Value value = frameState.pop(field.kind().stackKind());
        if (container != null) {
            StoreField store = new StoreField(container, field, value, graph);
            appendOptimizedStoreField(store);
        } else {
            // deopt will be generated by genTypeOrDeopt, not needed here
        }
    }

    private Constant genTypeOrDeopt(RiType.Representation representation, RiType holder, boolean initialized, int cpi) {
        if (initialized) {
            return appendConstant(holder.getEncoding(representation));
        } else {
            storeResultGraph = false;
            append(new Deoptimize(DeoptAction.InvalidateRecompile, graph));
            return null;
        }
    }

    private void appendOptimizedStoreField(StoreField store) {
        append(store);
    }

    private void appendOptimizedLoadField(CiKind kind, LoadField load) {
        // append the load to the instruction
        Value optimized = append(load);
        frameState.push(kind.stackKind(), optimized);
    }

    private void genInvokeStatic(RiMethod target, int cpi, RiConstantPool constantPool) {
        RiType holder = target.holder();
        boolean isInitialized = target.isResolved() && holder.isInitialized();
        if (!isInitialized && GraalOptions.ResolveClassBeforeStaticInvoke) {
            // Re-use the same resolution code as for accessing a static field. Even though
            // the result of resolution is not used by the invocation (only the side effect
            // of initialization is required), it can be commoned with static field accesses.
            genTypeOrDeopt(RiType.Representation.StaticFields, holder, isInitialized, cpi);
        }
        Value[] args = frameState.popArguments(target.signature().argumentSlots(false));
        appendInvoke(INVOKESTATIC, target, args, cpi, constantPool);
    }

    private void genInvokeInterface(RiMethod target, int cpi, RiConstantPool constantPool) {
        Value[] args = frameState.popArguments(target.signature().argumentSlots(true));
        genInvokeIndirect(INVOKEINTERFACE, target, args, cpi, constantPool);

    }

    private void genInvokeVirtual(RiMethod target, int cpi, RiConstantPool constantPool) {
        Value[] args = frameState.popArguments(target.signature().argumentSlots(true));
        genInvokeIndirect(INVOKEVIRTUAL, target, args, cpi, constantPool);

    }

    private void genInvokeSpecial(RiMethod target, RiType knownHolder, int cpi, RiConstantPool constantPool) {
        assert target != null;
        assert target.signature() != null;
        Value[] args = frameState.popArguments(target.signature().argumentSlots(true));
        invokeDirect(target, args, knownHolder, cpi, constantPool);

    }

    private void genInvokeIndirect(int opcode, RiMethod target, Value[] args, int cpi, RiConstantPool constantPool) {
        Value receiver = args[0];
        // attempt to devirtualize the call
        if (target.isResolved()) {
            RiType klass = target.holder();

            // 0. check for trivial cases
            if (target.canBeStaticallyBound() && !isAbstract(target.accessFlags())) {
                // check for trivial cases (e.g. final methods, nonvirtual methods)
                invokeDirect(target, args, target.holder(), cpi, constantPool);
                return;
            }
            // 1. check if the exact type of the receiver can be determined
            RiType exact = getExactType(klass, receiver);
            if (exact != null && exact.isResolved()) {
                // either the holder class is exact, or the receiver object has an exact type
                invokeDirect(exact.resolveMethodImpl(target), args, exact, cpi, constantPool);
                return;
            }
        }
        // devirtualization failed, produce an actual invokevirtual
        appendInvoke(opcode, target, args, cpi, constantPool);
    }

    private CiKind returnKind(RiMethod target) {
        return target.signature().returnKind();
    }

    private void invokeDirect(RiMethod target, Value[] args, RiType knownHolder, int cpi, RiConstantPool constantPool) {
        appendInvoke(INVOKESPECIAL, target, args, cpi, constantPool);
    }

    private void appendInvoke(int opcode, RiMethod target, Value[] args, int cpi, RiConstantPool constantPool) {
        CiKind resultType = returnKind(target);
        if (GraalOptions.DeoptALot) {
            storeResultGraph = false;
            Deoptimize deoptimize = new Deoptimize(DeoptAction.None, graph);
            deoptimize.setMessage("invoke " + target.name());
            append(deoptimize);
            frameState.pushReturn(resultType, Constant.defaultForKind(resultType, graph));
        } else {
            Invoke invoke = new Invoke(bci(), opcode, resultType.stackKind(), args, target, target.signature().returnType(method.holder()), method.typeProfile(bci()), graph);
            Value result = appendWithBCI(invoke);
            invoke.setExceptionEdge(handleException(null, bci()));
            frameState.pushReturn(resultType, result);
        }
    }

    private RiType getExactType(RiType staticType, Value receiver) {
        RiType exact = staticType.exactType();
        if (exact == null) {
            exact = receiver.exactType();
            if (exact == null) {
                if (receiver.isConstant()) {
                    exact = runtime.getTypeOf(receiver.asConstant());
                }
                if (exact == null) {
                    RiType declared = receiver.declaredType();
                    exact = declared == null || !declared.isResolved() ? null : declared.exactType();
                }
            }
        }
        return exact;
    }

    private void callRegisterFinalizer() {
        Value receiver = frameState.loadLocal(0);
        RiType declaredType = receiver.declaredType();
        RiType receiverType = declaredType;
        RiType exactType = receiver.exactType();
        if (exactType == null && declaredType != null) {
            exactType = declaredType.exactType();
        }
        if (exactType == null && receiver instanceof Local && ((Local) receiver).index() == 0) {
            // the exact type isn't known, but the receiver is parameter 0 => use holder
            receiverType = method.holder();
            exactType = receiverType.exactType();
        }
        boolean needsCheck = true;
        if (exactType != null) {
            // we have an exact type
            needsCheck = exactType.hasFinalizer();
        } else {
            // if either the declared type of receiver or the holder can be assumed to have no finalizers
            if (declaredType != null && !declaredType.hasFinalizableSubclass()) {
                if (compilation.recordNoFinalizableSubclassAssumption(declaredType)) {
                    needsCheck = false;
                }
            }

            if (receiverType != null && !receiverType.hasFinalizableSubclass()) {
                if (compilation.recordNoFinalizableSubclassAssumption(receiverType)) {
                    needsCheck = false;
                }
            }
        }

        if (needsCheck) {
            // append a call to the finalizer registration
            append(new RegisterFinalizer(frameState.loadLocal(0), graph));
            GraalMetrics.InlinedFinalizerChecks++;
        }
    }

    private void genReturn(Value x) {
        frameState.clearStack();
        if (x != null) {
            frameState.push(x.kind, x);
        }
        appendGoto(createTarget(returnBlock(bci()), frameState));
    }

    private void genMonitorEnter(Value x, int bci) {
        int lockNumber = frameState.locksSize();
        MonitorAddress lockAddress = null;
        if (runtime.sizeOfBasicObjectLock() != 0) {
            lockAddress = new MonitorAddress(lockNumber, graph);
            append(lockAddress);
        }
        MonitorEnter monitorEnter = new MonitorEnter(x, lockAddress, lockNumber, graph);
        appendWithBCI(monitorEnter);
        frameState.lock(x);
        if (bci == FixedNodeWithNext.SYNCHRONIZATION_ENTRY_BCI) {
            monitorEnter.setStateAfter(frameState.create(0));
        }
    }

    private void genMonitorExit(Value x) {
        int lockNumber = frameState.locksSize() - 1;
        if (lockNumber < 0) {
            throw new CiBailout("monitor stack underflow");
        }
        MonitorAddress lockAddress = null;
        if (runtime.sizeOfBasicObjectLock() != 0) {
            lockAddress = new MonitorAddress(lockNumber, graph);
            append(lockAddress);
        }
        appendWithBCI(new MonitorExit(x, lockAddress, lockNumber, graph));
        frameState.unlock();
    }

    private void genJsr(int dest) {
        throw new JSRNotSupportedBailout();
    }

    private void genRet(int localIndex) {
        throw new JSRNotSupportedBailout();
    }

    private void genTableswitch() {
        int bci = bci();
        Value value = frameState.ipop();
        BytecodeTableSwitch ts = new BytecodeTableSwitch(stream(), bci);
        int max = ts.numberOfCases();
        List<FixedNodeWithNext> list = new ArrayList<FixedNodeWithNext>(max + 1);
        List<Integer> offsetList = new ArrayList<Integer>(max + 1);
        for (int i = 0; i < max; i++) {
            // add all successors to the successor list
            int offset = ts.offsetAt(i);
            list.add(null);
            offsetList.add(offset);
        }
        int offset = ts.defaultOffset();
        list.add(null);
        offsetList.add(offset);
        TableSwitch tableSwitch = new TableSwitch(value, list, ts.lowKey(), graph);
        for (int i = 0; i < offsetList.size(); ++i) {
            tableSwitch.setBlockSuccessor(i, createTargetAt(bci + offsetList.get(i), frameState));
        }
        append(tableSwitch);
    }

    private void genLookupswitch() {
        int bci = bci();
        Value value = frameState.ipop();
        BytecodeLookupSwitch ls = new BytecodeLookupSwitch(stream(), bci);
        int max = ls.numberOfCases();
        List<FixedNodeWithNext> list = new ArrayList<FixedNodeWithNext>(max + 1);
        List<Integer> offsetList = new ArrayList<Integer>(max + 1);
        int[] keys = new int[max];
        for (int i = 0; i < max; i++) {
            // add all successors to the successor list
            int offset = ls.offsetAt(i);
            list.add(null);
            offsetList.add(offset);
            keys[i] = ls.keyAt(i);
        }
        int offset = ls.defaultOffset();
        list.add(null);
        offsetList.add(offset);
        LookupSwitch lookupSwitch = new LookupSwitch(value, list, keys, graph);
        for (int i = 0; i < offsetList.size(); ++i) {
            lookupSwitch.setBlockSuccessor(i, createTargetAt(bci + offsetList.get(i), frameState));
        }
        append(lookupSwitch);
    }

    private Constant appendConstant(CiConstant constant) {
        return new Constant(constant, graph);
    }

    private Value append(FixedNode fixed) {
        if (fixed instanceof Deoptimize && lastInstr.predecessors().size() > 0) {
            Node cur = lastInstr;
            Node prev = cur;
            while (cur != cur.graph().start() && !(cur instanceof ControlSplit)) {
                assert cur.predecessors().size() == 1;
                prev = cur;
                cur = cur.predecessors().get(0);
                if (cur.predecessors().size() == 0) {
                    break;
                }
            }

            if (cur instanceof If) {
                If ifNode = (If) cur;
                if (ifNode.falseSuccessor() == prev) {
                    FixedNode successor = ifNode.trueSuccessor();
                    BooleanNode condition = ifNode.compare();
                    FixedGuard fixedGuard = new FixedGuard(graph);
                    fixedGuard.setNext(successor);
                    fixedGuard.setNode(condition);
                    ifNode.replaceAndDelete(fixedGuard);
                    lastInstr = null;
                    return fixed;
                }
            } else if (prev != cur) {
                prev.replaceAtPredecessors(fixed);
                lastInstr = null;
                return fixed;
            }
        }
        lastInstr.setNext(fixed);
        lastInstr = null;
        return fixed;
    }

    private Value append(FixedNodeWithNext x) {
        return appendWithBCI(x);
    }

    private Value append(Value v) {
        return v;
    }

    private Value appendWithBCI(FixedNodeWithNext x) {
        assert x.predecessors().size() == 0 : "instruction should not have been appended yet";
        assert lastInstr.next() == null : "cannot append instruction to instruction which isn't end (" + lastInstr + "->" + lastInstr.next() + ")";
        lastInstr.setNext(x);

        lastInstr = x;
        if (++stats.nodeCount >= GraalOptions.MaximumInstructionCount) {
            // bailout if we've exceeded the maximum inlining size
            throw new CiBailout("Method and/or inlining is too large");
        }

        return x;
    }

    private FixedNode createTargetAt(int bci, FrameStateAccess stateAfter) {
        return createTarget(blockFromBci[bci], stateAfter);
    }

    private FixedNode createTarget(Block block, FrameStateAccess stateAfter) {
        assert block != null && stateAfter != null;
        assert block.isLoopHeader || block.firstInstruction == null || block.firstInstruction.next() == null :
            "non-loop block must be iterated after all its predecessors. startBci=" + block.startBci + ", " + block.getClass().getSimpleName() + ", " + block.firstInstruction.next();

        if (block.isExceptionEntry) {
            assert stateAfter.stackSize() == 1;
        }

        if (block.firstInstruction == null) {
            if (block.isLoopHeader) {
                LoopBegin loopBegin = new LoopBegin(graph);
                LoopEnd loopEnd = new LoopEnd(graph);
                loopEnd.setLoopBegin(loopBegin);
                Placeholder pBegin = new Placeholder(graph);
                pBegin.setNext(loopBegin.forwardEdge());
                Placeholder pEnd = new Placeholder(graph);
                pEnd.setNext(loopEnd);
                loopBegin.setStateAfter(stateAfter.duplicate(block.startBci));
                block.firstInstruction = pBegin;
            } else {
                block.firstInstruction = new Placeholder(graph);
            }
        }
        mergeOrClone(block, stateAfter);
        addToWorkList(block);

        FixedNode result = null;
        if (block.isLoopHeader && isVisited(block)) {
            result = (StateSplit) loopBegin(block).loopEnd().singlePredecessor();
        } else {
            result = block.firstInstruction;
        }

        assert result instanceof Merge || result instanceof Placeholder : result;

        if (result instanceof Merge) {
            if (result instanceof LoopBegin) {
                result = ((LoopBegin) result).forwardEdge();
            } else {
                EndNode end = new EndNode(graph);
                ((Merge) result).addEnd(end);
                result = end;
            }
        }
        assert !(result instanceof LoopBegin || result instanceof Merge);
        return result;
    }

    private Value synchronizedObject(FrameStateAccess state, RiMethod target) {
        if (isStatic(target.accessFlags())) {
            Constant classConstant = new Constant(target.holder().getEncoding(Representation.JavaClass), graph);
            return append(classConstant);
        } else {
            return state.localAt(0);
        }
    }

    private void iterateAllBlocks() {
        Block block;
        while ((block = removeFromWorkList()) != null) {

            // remove blocks that have no predecessors by the time it their bytecodes are parsed
            if (block.firstInstruction == null) {
                markVisited(block);
                continue;
            }

            if (!isVisited(block)) {
                markVisited(block);
                // now parse the block
                if (block.isLoopHeader) {
                    LoopBegin begin = loopBegin(block);
                    FrameState preLoopState = ((StateSplit) block.firstInstruction).stateAfter();
                    assert preLoopState != null;
                    FrameState duplicate = preLoopState.duplicate(preLoopState.bci);
                    begin.setStateAfter(duplicate);
                    insertLoopPhis(begin, duplicate);
                    lastInstr = begin;
                } else {
                    lastInstr = block.firstInstruction;
                }
                frameState.initializeFrom(((StateSplit) lastInstr).stateAfter());
                assert lastInstr.next() == null : "instructions already appended at block " + block.blockID;

                if (block == returnBlock) {
                    createReturnBlock(block);
                } else if (block == unwindBlock) {
                    createUnwindBlock(block);
                } else if (block instanceof ExceptionBlock) {
                    createExceptionDispatch((ExceptionBlock) block);
                } else if (block instanceof DeoptBlock) {
                    createDeoptBlock((DeoptBlock) block);
                } else {
                    iterateBytecodesForBlock(block);
                }
            }
        }
        for (Block b : blocksVisited) {
            if (b.isLoopHeader) {
                LoopBegin begin = loopBegin(b);
                LoopEnd loopEnd = begin.loopEnd();
                StateSplit loopEndPred = (StateSplit) loopEnd.singlePredecessor();

//              This can happen with degenerated loops like this one:
//                for (;;) {
//                    try {
//                        break;
//                    } catch (UnresolvedException iioe) {
//                    }
//                }
                if (loopEndPred.stateAfter() != null) {
                    //loopHeaderMerge.stateBefore().merge(begin, end.stateBefore());
                    //assert loopHeaderMerge.equals(end.stateBefore());
                    begin.stateAfter().merge(begin, loopEndPred.stateAfter());
                } else {
                    loopEndPred.delete();
                    loopEnd.delete();
                    Merge merge = new Merge(graph);
                    merge.addEnd(begin.forwardEdge());
                    merge.setNext(begin.next());
                    merge.setStateAfter(begin.stateAfter());
                    begin.replaceAndDelete(merge);
                }
            }
        }
    }

    private static LoopBegin loopBegin(Block block) {
        EndNode endNode = (EndNode) block.firstInstruction.next();
        LoopBegin loopBegin = (LoopBegin) endNode.merge();
        return loopBegin;
    }

    private void createDeoptBlock(DeoptBlock block) {
        storeResultGraph = false;
        append(new Deoptimize(DeoptAction.InvalidateReprofile, graph));
    }

    private void createUnwindBlock(Block block) {
        if (Modifier.isSynchronized(method.accessFlags())) {
            genMonitorExit(methodSynchronizedObject);
        }
        Unwind unwindNode = new Unwind(frameState.apop(), graph);
        graph.setUnwind(unwindNode);
        append(unwindNode);
    }

    private void createReturnBlock(Block block) {
        if (method.isConstructor() && method.holder().superType() == null) {
            callRegisterFinalizer();
        }
        CiKind returnKind = method.signature().returnKind().stackKind();
        Value x = returnKind == CiKind.Void ? null : frameState.pop(returnKind);
        assert frameState.stackSize() == 0;

        if (Modifier.isSynchronized(method.accessFlags())) {
            genMonitorExit(methodSynchronizedObject);
        }
        Return returnNode = new Return(x, graph);
        graph.setReturn(returnNode);
        append(returnNode);
    }

    private void createExceptionDispatch(ExceptionBlock block) {
        if (block.handler == null) {
            assert frameState.stackSize() == 1 : "only exception object expected on stack, actual size: " + frameState.stackSize();
            createUnwindBlock(block);
        } else {
            assert frameState.stackSize() == 1 : frameState;

            Block nextBlock = block.next == null ? unwindBlock(block.deoptBci) : block.next;
            if (block.handler.catchType().isResolved()) {
                FixedNode catchSuccessor = createTarget(blockFromBci[block.handler.handlerBCI()], frameState);
                FixedNode nextDispatch = createTarget(nextBlock, frameState);
                append(new ExceptionDispatch(frameState.stackAt(0), catchSuccessor, nextDispatch, block.handler.catchType(), graph));
            } else {
                Deoptimize deopt = new Deoptimize(DeoptAction.InvalidateRecompile, graph);
                deopt.setMessage("unresolved " + block.handler.catchType().name());
                append(deopt);
            }
        }
    }

    private void appendGoto(FixedNode target) {
        if (lastInstr != null) {
            lastInstr.setNext(target);
        }
    }

    private void iterateBytecodesForBlock(Block block) {
        assert frameState != null;

        stream.setBCI(block.startBci);

        int endBCI = stream.endBCI();

        int bci = block.startBci;
        while (bci < endBCI) {
            Block nextBlock = blockFromBci[bci];
            if (nextBlock != null && nextBlock != block) {
                assert !nextBlock.isExceptionEntry;
                // we fell through to the next block, add a goto and break
                appendGoto(createTarget(nextBlock, frameState));
                break;
            }
            // read the opcode
            int opcode = stream.currentBC();
            traceState();
            traceInstruction(bci, opcode, bci == block.startBci);
            processBytecode(bci, opcode);

            if (lastInstr == null || IdentifyBlocksPhase.isBlockEnd(lastInstr) || lastInstr.next() != null) {
                break;
            }

            stream.next();
            bci = stream.currentBCI();
            if (lastInstr instanceof StateSplit) {
                StateSplit stateSplit = (StateSplit) lastInstr;
                if (stateSplit.stateAfter() == null && stateSplit.needsStateAfter()) {
                    stateSplit.setStateAfter(frameState.create(bci));
                }
            }
        }
    }

    private void traceState() {
        if (GraalOptions.TraceBytecodeParserLevel >= TRACELEVEL_STATE && !TTY.isSuppressed()) {
            log.println(String.format("|   state [nr locals = %d, stack depth = %d, method = %s]", frameState.localsSize(), frameState.stackSize(), method));
            for (int i = 0; i < frameState.localsSize(); ++i) {
                Value value = frameState.localAt(i);
                log.println(String.format("|   local[%d] = %-8s : %s", i, value == null ? "bogus" : value.kind.javaName, value));
            }
            for (int i = 0; i < frameState.stackSize(); ++i) {
                Value value = frameState.stackAt(i);
                log.println(String.format("|   stack[%d] = %-8s : %s", i, value == null ? "bogus" : value.kind.javaName, value));
            }
            for (int i = 0; i < frameState.locksSize(); ++i) {
                Value value = frameState.lockAt(i);
                log.println(String.format("|   lock[%d] = %-8s : %s", i, value == null ? "bogus" : value.kind.javaName, value));
            }
        }
    }

    private void processBytecode(int bci, int opcode) {
        int cpi;

        // Checkstyle: stop
        switch (opcode) {
            case NOP            : /* nothing to do */ break;
            case ACONST_NULL    : frameState.apush(appendConstant(CiConstant.NULL_OBJECT)); break;
            case ICONST_M1      : frameState.ipush(appendConstant(CiConstant.INT_MINUS_1)); break;
            case ICONST_0       : frameState.ipush(appendConstant(CiConstant.INT_0)); break;
            case ICONST_1       : frameState.ipush(appendConstant(CiConstant.INT_1)); break;
            case ICONST_2       : frameState.ipush(appendConstant(CiConstant.INT_2)); break;
            case ICONST_3       : frameState.ipush(appendConstant(CiConstant.INT_3)); break;
            case ICONST_4       : frameState.ipush(appendConstant(CiConstant.INT_4)); break;
            case ICONST_5       : frameState.ipush(appendConstant(CiConstant.INT_5)); break;
            case LCONST_0       : frameState.lpush(appendConstant(CiConstant.LONG_0)); break;
            case LCONST_1       : frameState.lpush(appendConstant(CiConstant.LONG_1)); break;
            case FCONST_0       : frameState.fpush(appendConstant(CiConstant.FLOAT_0)); break;
            case FCONST_1       : frameState.fpush(appendConstant(CiConstant.FLOAT_1)); break;
            case FCONST_2       : frameState.fpush(appendConstant(CiConstant.FLOAT_2)); break;
            case DCONST_0       : frameState.dpush(appendConstant(CiConstant.DOUBLE_0)); break;
            case DCONST_1       : frameState.dpush(appendConstant(CiConstant.DOUBLE_1)); break;
            case BIPUSH         : frameState.ipush(appendConstant(CiConstant.forInt(stream.readByte()))); break;
            case SIPUSH         : frameState.ipush(appendConstant(CiConstant.forInt(stream.readShort()))); break;
            case LDC            : // fall through
            case LDC_W          : // fall through
            case LDC2_W         : genLoadConstant(stream.readCPI()); break;
            case ILOAD          : loadLocal(stream.readLocalIndex(), CiKind.Int); break;
            case LLOAD          : loadLocal(stream.readLocalIndex(), CiKind.Long); break;
            case FLOAD          : loadLocal(stream.readLocalIndex(), CiKind.Float); break;
            case DLOAD          : loadLocal(stream.readLocalIndex(), CiKind.Double); break;
            case ALOAD          : loadLocal(stream.readLocalIndex(), CiKind.Object); break;
            case ILOAD_0        : // fall through
            case ILOAD_1        : // fall through
            case ILOAD_2        : // fall through
            case ILOAD_3        : loadLocal(opcode - ILOAD_0, CiKind.Int); break;
            case LLOAD_0        : // fall through
            case LLOAD_1        : // fall through
            case LLOAD_2        : // fall through
            case LLOAD_3        : loadLocal(opcode - LLOAD_0, CiKind.Long); break;
            case FLOAD_0        : // fall through
            case FLOAD_1        : // fall through
            case FLOAD_2        : // fall through
            case FLOAD_3        : loadLocal(opcode - FLOAD_0, CiKind.Float); break;
            case DLOAD_0        : // fall through
            case DLOAD_1        : // fall through
            case DLOAD_2        : // fall through
            case DLOAD_3        : loadLocal(opcode - DLOAD_0, CiKind.Double); break;
            case ALOAD_0        : // fall through
            case ALOAD_1        : // fall through
            case ALOAD_2        : // fall through
            case ALOAD_3        : loadLocal(opcode - ALOAD_0, CiKind.Object); break;
            case IALOAD         : genLoadIndexed(CiKind.Int   ); break;
            case LALOAD         : genLoadIndexed(CiKind.Long  ); break;
            case FALOAD         : genLoadIndexed(CiKind.Float ); break;
            case DALOAD         : genLoadIndexed(CiKind.Double); break;
            case AALOAD         : genLoadIndexed(CiKind.Object); break;
            case BALOAD         : genLoadIndexed(CiKind.Byte  ); break;
            case CALOAD         : genLoadIndexed(CiKind.Char  ); break;
            case SALOAD         : genLoadIndexed(CiKind.Short ); break;
            case ISTORE         : storeLocal(CiKind.Int, stream.readLocalIndex()); break;
            case LSTORE         : storeLocal(CiKind.Long, stream.readLocalIndex()); break;
            case FSTORE         : storeLocal(CiKind.Float, stream.readLocalIndex()); break;
            case DSTORE         : storeLocal(CiKind.Double, stream.readLocalIndex()); break;
            case ASTORE         : storeLocal(CiKind.Object, stream.readLocalIndex()); break;
            case ISTORE_0       : // fall through
            case ISTORE_1       : // fall through
            case ISTORE_2       : // fall through
            case ISTORE_3       : storeLocal(CiKind.Int, opcode - ISTORE_0); break;
            case LSTORE_0       : // fall through
            case LSTORE_1       : // fall through
            case LSTORE_2       : // fall through
            case LSTORE_3       : storeLocal(CiKind.Long, opcode - LSTORE_0); break;
            case FSTORE_0       : // fall through
            case FSTORE_1       : // fall through
            case FSTORE_2       : // fall through
            case FSTORE_3       : storeLocal(CiKind.Float, opcode - FSTORE_0); break;
            case DSTORE_0       : // fall through
            case DSTORE_1       : // fall through
            case DSTORE_2       : // fall through
            case DSTORE_3       : storeLocal(CiKind.Double, opcode - DSTORE_0); break;
            case ASTORE_0       : // fall through
            case ASTORE_1       : // fall through
            case ASTORE_2       : // fall through
            case ASTORE_3       : storeLocal(CiKind.Object, opcode - ASTORE_0); break;
            case IASTORE        : genStoreIndexed(CiKind.Int   ); break;
            case LASTORE        : genStoreIndexed(CiKind.Long  ); break;
            case FASTORE        : genStoreIndexed(CiKind.Float ); break;
            case DASTORE        : genStoreIndexed(CiKind.Double); break;
            case AASTORE        : genStoreIndexed(CiKind.Object); break;
            case BASTORE        : genStoreIndexed(CiKind.Byte  ); break;
            case CASTORE        : genStoreIndexed(CiKind.Char  ); break;
            case SASTORE        : genStoreIndexed(CiKind.Short ); break;
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
            case IMUL           : genArithmeticOp(CiKind.Int, opcode); break;
            case IDIV           : // fall through
            case IREM           : genArithmeticOp(CiKind.Int, opcode, true); break;
            case LADD           : // fall through
            case LSUB           : // fall through
            case LMUL           : genArithmeticOp(CiKind.Long, opcode); break;
            case LDIV           : // fall through
            case LREM           : genArithmeticOp(CiKind.Long, opcode, true); break;
            case FADD           : // fall through
            case FSUB           : // fall through
            case FMUL           : // fall through
            case FDIV           : // fall through
            case FREM           : genArithmeticOp(CiKind.Float, opcode); break;
            case DADD           : // fall through
            case DSUB           : // fall through
            case DMUL           : // fall through
            case DDIV           : // fall through
            case DREM           : genArithmeticOp(CiKind.Double, opcode); break;
            case INEG           : genNegateOp(CiKind.Int); break;
            case LNEG           : genNegateOp(CiKind.Long); break;
            case FNEG           : genNegateOp(CiKind.Float); break;
            case DNEG           : genNegateOp(CiKind.Double); break;
            case ISHL           : // fall through
            case ISHR           : // fall through
            case IUSHR          : genShiftOp(CiKind.Int, opcode); break;
            case IAND           : // fall through
            case IOR            : // fall through
            case IXOR           : genLogicOp(CiKind.Int, opcode); break;
            case LSHL           : // fall through
            case LSHR           : // fall through
            case LUSHR          : genShiftOp(CiKind.Long, opcode); break;
            case LAND           : // fall through
            case LOR            : // fall through
            case LXOR           : genLogicOp(CiKind.Long, opcode); break;
            case IINC           : genIncrement(); break;
            case I2L            : genConvert(opcode, CiKind.Int   , CiKind.Long  ); break;
            case I2F            : genConvert(opcode, CiKind.Int   , CiKind.Float ); break;
            case I2D            : genConvert(opcode, CiKind.Int   , CiKind.Double); break;
            case L2I            : genConvert(opcode, CiKind.Long  , CiKind.Int   ); break;
            case L2F            : genConvert(opcode, CiKind.Long  , CiKind.Float ); break;
            case L2D            : genConvert(opcode, CiKind.Long  , CiKind.Double); break;
            case F2I            : genConvert(opcode, CiKind.Float , CiKind.Int   ); break;
            case F2L            : genConvert(opcode, CiKind.Float , CiKind.Long  ); break;
            case F2D            : genConvert(opcode, CiKind.Float , CiKind.Double); break;
            case D2I            : genConvert(opcode, CiKind.Double, CiKind.Int   ); break;
            case D2L            : genConvert(opcode, CiKind.Double, CiKind.Long  ); break;
            case D2F            : genConvert(opcode, CiKind.Double, CiKind.Float ); break;
            case I2B            : genConvert(opcode, CiKind.Int   , CiKind.Byte  ); break;
            case I2C            : genConvert(opcode, CiKind.Int   , CiKind.Char  ); break;
            case I2S            : genConvert(opcode, CiKind.Int   , CiKind.Short ); break;
            case LCMP           : genCompareOp(CiKind.Long, opcode, CiKind.Int); break;
            case FCMPL          : genCompareOp(CiKind.Float, opcode, CiKind.Int); break;
            case FCMPG          : genCompareOp(CiKind.Float, opcode, CiKind.Int); break;
            case DCMPL          : genCompareOp(CiKind.Double, opcode, CiKind.Int); break;
            case DCMPG          : genCompareOp(CiKind.Double, opcode, CiKind.Int); break;
            case IFEQ           : genIfZero(Condition.EQ); break;
            case IFNE           : genIfZero(Condition.NE); break;
            case IFLT           : genIfZero(Condition.LT); break;
            case IFGE           : genIfZero(Condition.GE); break;
            case IFGT           : genIfZero(Condition.GT); break;
            case IFLE           : genIfZero(Condition.LE); break;
            case IF_ICMPEQ      : genIfSame(CiKind.Int, Condition.EQ); break;
            case IF_ICMPNE      : genIfSame(CiKind.Int, Condition.NE); break;
            case IF_ICMPLT      : genIfSame(CiKind.Int, Condition.LT); break;
            case IF_ICMPGE      : genIfSame(CiKind.Int, Condition.GE); break;
            case IF_ICMPGT      : genIfSame(CiKind.Int, Condition.GT); break;
            case IF_ICMPLE      : genIfSame(CiKind.Int, Condition.LE); break;
            case IF_ACMPEQ      : genIfSame(frameState.peekKind(), Condition.EQ); break;
            case IF_ACMPNE      : genIfSame(frameState.peekKind(), Condition.NE); break;
            case GOTO           : genGoto(stream.currentBCI(), stream.readBranchDest()); break;
            case JSR            : genJsr(stream.readBranchDest()); break;
            case RET            : genRet(stream.readLocalIndex()); break;
            case TABLESWITCH    : genTableswitch(); break;
            case LOOKUPSWITCH   : genLookupswitch(); break;
            case IRETURN        : genReturn(frameState.ipop()); break;
            case LRETURN        : genReturn(frameState.lpop()); break;
            case FRETURN        : genReturn(frameState.fpop()); break;
            case DRETURN        : genReturn(frameState.dpop()); break;
            case ARETURN        : genReturn(frameState.apop()); break;
            case RETURN         : genReturn(null  ); break;
            case GETSTATIC      : cpi = stream.readCPI(); genGetStatic(cpi, constantPool.lookupField(cpi, opcode)); break;
            case PUTSTATIC      : cpi = stream.readCPI(); genPutStatic(cpi, constantPool.lookupField(cpi, opcode)); break;
            case GETFIELD       : cpi = stream.readCPI(); genGetField(cpi, constantPool.lookupField(cpi, opcode)); break;
            case PUTFIELD       : cpi = stream.readCPI(); genPutField(cpi, constantPool.lookupField(cpi, opcode)); break;
            case INVOKEVIRTUAL  : cpi = stream.readCPI(); genInvokeVirtual(constantPool.lookupMethod(cpi, opcode), cpi, constantPool); break;
            case INVOKESPECIAL  : cpi = stream.readCPI(); genInvokeSpecial(constantPool.lookupMethod(cpi, opcode), null, cpi, constantPool); break;
            case INVOKESTATIC   : cpi = stream.readCPI(); genInvokeStatic(constantPool.lookupMethod(cpi, opcode), cpi, constantPool); break;
            case INVOKEINTERFACE: cpi = stream.readCPI(); genInvokeInterface(constantPool.lookupMethod(cpi, opcode), cpi, constantPool); break;
            case NEW            : genNewInstance(stream.readCPI()); break;
            case NEWARRAY       : genNewTypeArray(stream.readLocalIndex()); break;
            case ANEWARRAY      : genNewObjectArray(stream.readCPI()); break;
            case ARRAYLENGTH    : genArrayLength(); break;
            case ATHROW         : genThrow(stream.currentBCI()); break;
            case CHECKCAST      : genCheckCast(); break;
            case INSTANCEOF     : genInstanceOf(); break;
            case MONITORENTER   : genMonitorEnter(frameState.apop(), stream.currentBCI()); break;
            case MONITOREXIT    : genMonitorExit(frameState.apop()); break;
            case MULTIANEWARRAY : genNewMultiArray(stream.readCPI()); break;
            case IFNULL         : genIfNull(Condition.EQ); break;
            case IFNONNULL      : genIfNull(Condition.NE); break;
            case GOTO_W         : genGoto(stream.currentBCI(), stream.readFarBranchDest()); break;
            case JSR_W          : genJsr(stream.readFarBranchDest()); break;
            case BREAKPOINT:
                throw new CiBailout("concurrent setting of breakpoint");
            default:
                throw new CiBailout("Unsupported opcode " + opcode + " (" + nameOf(opcode) + ") [bci=" + bci + "]");
        }
        // Checkstyle: resume
    }

    private void traceInstruction(int bci, int opcode, boolean blockStart) {
        if (GraalOptions.TraceBytecodeParserLevel >= TRACELEVEL_INSTRUCTIONS && !TTY.isSuppressed()) {
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
            log.println(sb.toString());
        }
    }

    private void genArrayLength() {
        frameState.ipush(append(new ArrayLength(frameState.apop(), graph)));
    }

    /**
     * Adds a block to the worklist, if it is not already in the worklist.
     * This method will keep the worklist topologically stored (i.e. the lower
     * DFNs are earlier in the list).
     * @param block the block to add to the work list
     */
    private void addToWorkList(Block block) {
        if (!isOnWorkList(block)) {
            markOnWorkList(block);
            sortIntoWorkList(block);
        }
    }

    private void sortIntoWorkList(Block top) {
        workList.offer(top);
    }

    /**
     * Removes the next block from the worklist. The list is sorted topologically, so the
     * block with the lowest depth first number in the list will be removed and returned.
     * @return the next block from the worklist; {@code null} if there are no blocks
     * in the worklist
     */
    private Block removeFromWorkList() {
        return workList.poll();
    }
}
