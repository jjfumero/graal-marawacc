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
package com.sun.c1x.graph;

import static com.sun.cri.bytecode.Bytecodes.*;
import static java.lang.reflect.Modifier.*;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.graph.*;
import com.sun.c1x.*;
import com.sun.c1x.debug.*;
import com.sun.c1x.ir.*;
import com.sun.c1x.util.*;
import com.sun.c1x.value.*;
import com.sun.cri.bytecode.*;
import com.sun.cri.ci.*;
import com.sun.cri.ri.*;
import com.sun.cri.ri.RiType.Representation;

/**
 * The {@code GraphBuilder} class parses the bytecode of a method and builds the IR graph.
 * A number of optimizations may be performed during parsing of the bytecode, including value
 * numbering, inlining, constant folding, strength reduction, etc.
 */
public final class GraphBuilder {

    /**
     * The minimum value to which {@link C1XOptions#TraceBytecodeParserLevel} must be set to trace
     * the bytecode instructions as they are parsed.
     */
    public static final int TRACELEVEL_INSTRUCTIONS = 1;

    /**
     * The minimum value to which {@link C1XOptions#TraceBytecodeParserLevel} must be set to trace
     * the frame state before each bytecode instruction as it is parsed.
     */
    public static final int TRACELEVEL_STATE = 2;

    /**
     * An enumeration of flags describing scope attributes.
     */
    public enum Flag {
        /**
         * Scope is protected by an exception handler.
         * This attribute is inherited by nested scopes.
         */
        HasHandler,

        /**
         * Code in scope cannot contain safepoints.
         * This attribute is inherited by nested scopes.
         */
        NoSafepoints;

        public final int mask = 1 << ordinal();
    }

    private final IR ir;
    private final C1XCompilation compilation;
    private final CiStatistics stats;

    private final BytecodeStream stream;           // the bytecode stream
    // bci-to-block mapping
    private BlockBegin[] blockList;

    // the constant pool
    private final RiConstantPool constantPool;

    // the worklist of blocks, managed like a sorted list
    private BlockBegin[] workList;

    // the current position in the worklist
    private int workListIndex;

    /**
     * Mask of {@link Flag} values.
     */
    private int flags;

    // Exception handler list
    private List<ExceptionHandler> exceptionHandlers;

    private BlockBegin curBlock;                   // the current block
    private FrameStateBuilder frameState;          // the current execution state
    private Instruction lastInstr;                 // the last instruction added
    private final LogStream log;

    private Value rootMethodSynchronizedObject;

    private final Graph graph;

    private BlockBegin unwindBlock;

    /**
     * Creates a new, initialized, {@code GraphBuilder} instance for a given compilation.
     *
     * @param compilation the compilation
     * @param ir the IR to build the graph into
     * @param graph
     */
    public GraphBuilder(C1XCompilation compilation, IR ir, Graph graph) {
        this.compilation = compilation;
        this.ir = ir;
        this.stats = compilation.stats;
        log = C1XOptions.TraceBytecodeParserLevel > 0 ? new LogStream(TTY.out()) : null;
        stream = new BytecodeStream(compilation.method.code());
        constantPool = compilation.runtime.getConstantPool(compilation.method);
        this.graph = graph;
        this.frameState = new FrameStateBuilder(compilation.method, graph);
    }

    /**
     * Builds the graph for a the specified {@code IRScope}.
     * @param scope the top IRScope
     */
    public void build() {
        RiMethod rootMethod = compilation.method;

        if (log != null) {
            log.println();
            log.println("Compiling " + compilation.method);
        }

        if (rootMethod.noSafepoints()) {
            flags |= Flag.NoSafepoints.mask;
        }

        // 2. compute the block map, setup exception handlers and get the entrypoint(s)
        BlockMap blockMap = compilation.getBlockMap(rootMethod);

        blockList = new BlockBegin[rootMethod.code().length];
        for (int i = 0; i < blockMap.blocks.size(); i++) {
            BlockMap.Block block = blockMap.blocks.get(i);
            BlockBegin blockBegin = new BlockBegin(block.startBci, ir.nextBlockNumber(), graph);
            if (block.isLoopHeader) {
                blockBegin.setBlockFlag(BlockBegin.BlockFlag.ParserLoopHeader);
            }
            blockBegin.setDepthFirstNumber(blockBegin.blockID);
            blockList[block.startBci] = blockBegin;
        }


        // 1. create the start block
        ir.startBlock = new BlockBegin(0, ir.nextBlockNumber(), graph);
        BlockBegin startBlock = ir.startBlock;
        graph.root().setStart(startBlock);
        curBlock = startBlock;

        RiExceptionHandler[] handlers = rootMethod.exceptionHandlers();
        if (handlers != null && handlers.length > 0) {
            exceptionHandlers = new ArrayList<ExceptionHandler>(handlers.length);
            for (RiExceptionHandler ch : handlers) {
                BlockBegin entry = blockAtOrNull(ch.handlerBCI());
                // entry == null means that the exception handler is unreachable according to the BlockMap conservative analysis
                if (entry != null) {
                    ExceptionHandler h = new ExceptionHandler(ch);
                    h.setEntryBlock(entry);
                    exceptionHandlers.add(h);
                }
            }
            flags |= Flag.HasHandler.mask;
        }

        startBlock.mergeOrClone(frameState, rootMethod);
        BlockBegin syncHandler = null;

        // 3. setup internal state for appending instructions
        curBlock = startBlock;
        lastInstr = startBlock;
        lastInstr.appendNext(null, -1);

        BlockBegin entryBlock = blockList[0];
        if (isSynchronized(rootMethod.accessFlags())) {
            // 4A.1 add a monitor enter to the start block
            rootMethodSynchronizedObject = synchronizedObject(frameState, compilation.method);
            genMonitorEnter(rootMethodSynchronizedObject, Instruction.SYNCHRONIZATION_ENTRY_BCI);
            // 4A.2 finish the start block
            finishStartBlock(startBlock, entryBlock);

            // 4A.3 setup an exception handler to unlock the root method synchronized object
            syncHandler = new BlockBegin(Instruction.SYNCHRONIZATION_ENTRY_BCI, ir.nextBlockNumber(), graph);
            markOnWorkList(syncHandler);

            ExceptionHandler h = new ExceptionHandler(new CiExceptionHandler(0, rootMethod.code().length, -1, 0, null));
            h.setEntryBlock(syncHandler);
            addExceptionHandler(h);
        } else {
            // 4B.1 simply finish the start block
            finishStartBlock(startBlock, entryBlock);
        }

        // 5. SKIPPED: look for intrinsics

        // 6B.1 do the normal parsing
        addToWorkList(entryBlock);
        iterateAllBlocks();

        if (syncHandler != null && syncHandler.stateBefore() != null) {
            // generate unlocking code if the exception handler is reachable
            fillSyncHandler(rootMethodSynchronizedObject, syncHandler);
        }
    }

    private Set<BlockBegin> blocksOnWorklist = new HashSet<BlockBegin>();

    private void markOnWorkList(BlockBegin block) {
        blocksOnWorklist.add(block);
    }

    private boolean isOnWorkList(BlockBegin block) {
        return blocksOnWorklist.contains(block);
    }

    private Set<BlockBegin> blocksVisited = new HashSet<BlockBegin>();

    private void markVisited(BlockBegin block) {
        blocksVisited.add(block);
    }

    private boolean isVisited(BlockBegin block) {
        return blocksVisited.contains(block);
    }

    private void finishStartBlock(BlockBegin startBlock, BlockBegin stdEntry) {
        assert curBlock == startBlock;
        FrameState stateAfter = frameState.create(bci());
        Goto base = new Goto(stdEntry, stateAfter, graph);
        appendWithoutOptimization(base, 0);
        startBlock.setEnd(base);
        assert stdEntry.stateBefore() == null;
        stdEntry.mergeOrClone(stateAfter, method());
    }

    public RiMethod method() {
        return compilation.method;
    }

    public BytecodeStream stream() {
        return stream;
    }

    public int bci() {
        return stream.currentBCI();
    }

    public int nextBCI() {
        return stream.nextBCI();
    }

    private void loadLocal(int index, CiKind kind) {
        frameState.push(kind, frameState.loadLocal(index));
    }

    private void storeLocal(CiKind kind, int index) {
        frameState.storeLocal(index, frameState.pop(kind));
    }

    private void handleException(Instruction x, int bci) {
        if (!hasHandler()) {
            return;
        }

        ArrayList<ExceptionHandler> exceptionHandlers = new ArrayList<ExceptionHandler>();

        assert bci == Instruction.SYNCHRONIZATION_ENTRY_BCI || bci == bci() : "invalid bci";

        // join with all potential exception handlers
        if (this.exceptionHandlers != null) {
            for (ExceptionHandler handler : this.exceptionHandlers) {
                // if the handler covers this bytecode index, add it to the list
                if (handler.covers(bci)) {
                    ExceptionHandler newHandler = addExceptionHandler(handler, frameState);
                    exceptionHandlers.add(newHandler);

                    // stop when reaching catch all
                    if (handler.isCatchAll()) {
                        break;
                    }
                }
            }
        }

        if (!exceptionHandlers.isEmpty()) {
            BlockBegin successor;

            ArrayList<BlockBegin> newBlocks = new ArrayList<BlockBegin>();

            int current = exceptionHandlers.size() - 1;
            if (exceptionHandlers.get(current).isCatchAll()) {
                successor = exceptionHandlers.get(current).entryBlock();
                current--;
            } else {
                if (unwindBlock == null) {
                    unwindBlock = new BlockBegin(bci, ir.nextBlockNumber(), graph);
                    Unwind unwind = new Unwind(null, graph);
                    unwindBlock.appendNext(unwind, bci);
                    unwindBlock.setEnd(unwind);
                }
                successor = unwindBlock;
            }

            for (; current >= 0; current--) {
                ExceptionHandler handler = exceptionHandlers.get(current);

                BlockBegin newSucc = null;
                for (Instruction pred : successor.blockPredecessors()) {
                    if (pred instanceof ExceptionDispatch) {
                        ExceptionDispatch dispatch = (ExceptionDispatch) pred;
                        if (dispatch.handler().handler == handler.handler) {
                            newSucc = dispatch.begin();
                            break;
                        }
                    }
                }
                if (newSucc != null) {
                    successor = newSucc;
                } else {
                    BlockBegin dispatchEntry = new BlockBegin(handler.handlerBCI(), ir.nextBlockNumber(), graph);
                    if (handler.handler.catchType().isResolved()) {
                        ExceptionDispatch end = new ExceptionDispatch(null, handler.entryBlock(), null, handler, null, graph);
                        end.setBlockSuccessor(0, successor);
                        dispatchEntry.appendNext(end, handler.handlerBCI());
                        dispatchEntry.setEnd(end);
                    } else {
                        Deoptimize deopt = new Deoptimize(graph);
                        dispatchEntry.appendNext(deopt, bci);
                        Goto end = new Goto(successor, null, graph);
                        deopt.appendNext(end, bci);
                        dispatchEntry.setEnd(end);
                    }
                    newBlocks.add(dispatchEntry);
                    successor = dispatchEntry;
                }
            }

            FrameState entryState = frameState.duplicateWithEmptyStack(bci);

            BlockBegin entry = new BlockBegin(bci, ir.nextBlockNumber(), graph);
            entry.setStateBefore(entryState);
            ExceptionObject exception = new ExceptionObject(graph);
            entry.appendNext(exception, bci);
            FrameState stateWithException = entryState.duplicateModified(bci, CiKind.Void, exception);
            BlockEnd end = new Goto(successor, stateWithException, graph);
            exception.appendNext(end, bci);
            entry.setEnd(end);

            if (x instanceof Invoke) {
                ((Invoke) x).setExceptionEdge(entry);
            } else {
                ((Throw) x).setExceptionEdge(entry);
            }

            updateDispatchChain(end.blockSuccessor(0), stateWithException, bci);
        }
    }

    private void updateDispatchChain(BlockBegin dispatchEntry, FrameStateAccess state, int bci) {
        FrameState oldState = dispatchEntry.stateBefore();
        if (oldState != null && dispatchEntry.predecessors().size() == 1) {
            dispatchEntry.setStateBefore(null);
            oldState.delete();
        }
        dispatchEntry.mergeOrClone(state, null);
        FrameState mergedState = dispatchEntry.stateBefore();

        if (dispatchEntry.next() instanceof ExceptionDispatch) {
            // ordinary dispatch handler
            ExceptionDispatch dispatch = (ExceptionDispatch) dispatchEntry.next();
            dispatch.setStateAfter(mergedState.duplicate(bci));
            dispatch.setException(mergedState.stackAt(0));
            dispatch.catchSuccessor().setStateBefore(mergedState.duplicate(bci));
            updateDispatchChain(dispatch.otherSuccessor(), mergedState, bci);
        } else if (dispatchEntry.next() instanceof Deoptimize) {
            // deoptimizing handler
            Deoptimize deopt = (Deoptimize) dispatchEntry.next();
            //deopt.setStateBefore(mergedState.duplicate(bci));
            dispatchEntry.end().setStateAfter(mergedState.duplicate(bci));
            updateDispatchChain(dispatchEntry.end().blockSuccessor(0), mergedState, bci);
        } else if (dispatchEntry.next() instanceof Unwind) {
            // unwind handler
            Unwind unwind = (Unwind) dispatchEntry.next();
            unwind.setStateAfter(mergedState.duplicate(bci));
            unwind.setException(mergedState.stackAt(0));
        } else {
            // synchronization or default exception handler, nothing to do
        }
    }

    /**
     * Adds an exception handler to the {@linkplain BlockBegin#exceptionHandlerBlocks() list}
     * of exception handlers for the {@link #curBlock current block}.
     */
    private ExceptionHandler addExceptionHandler(ExceptionHandler handler, FrameStateAccess curState) {
        compilation.setHasExceptionHandlers();

        BlockBegin entry = handler.entryBlock();

        // clone exception handler
        ExceptionHandler newHandler = new ExceptionHandler(handler);

        // fill in exception handler subgraph lazily
        if (!isVisited(entry)) {
            addToWorkList(entry);
        } else {
            // This will occur for exception handlers that cover themselves. This code
            // pattern is generated by javac for synchronized blocks. See the following
            // for why this change to javac was made:
            //
            //   http://www.cs.arizona.edu/projects/sumatra/hallofshame/java-async-race.html
        }
        return newHandler;
    }

    private void genLoadConstant(int cpi) {
        Object con = constantPool().lookupConstant(cpi);

        if (con instanceof RiType) {
            // this is a load of class constant which might be unresolved
            RiType riType = (RiType) con;
            if (!riType.isResolved()) {
                append(new Deoptimize(graph));
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

    private void genArithmeticOp(CiKind kind, int opcode, boolean canTrap) {
        genArithmeticOp(kind, opcode, kind, kind, canTrap);
    }

    private void genArithmeticOp(CiKind result, int opcode, CiKind x, CiKind y, boolean canTrap) {
        Value yValue = frameState.pop(y);
        Value xValue = frameState.pop(x);
        Value result1 = append(new ArithmeticOp(opcode, result, xValue, yValue, isStrict(method().accessFlags()), canTrap, graph));
        frameState.push(result, result1);
    }

    private void genNegateOp(CiKind kind) {
        frameState.push(kind, append(new NegateOp(frameState.pop(kind), graph)));
    }

    private void genShiftOp(CiKind kind, int opcode) {
        Value s = frameState.ipop();
        Value x = frameState.pop(kind);
        frameState.push(kind, append(new ShiftOp(opcode, x, s, graph)));
    }

    private void genLogicOp(CiKind kind, int opcode) {
        Value y = frameState.pop(kind);
        Value x = frameState.pop(kind);
        frameState.push(kind, append(new LogicOp(opcode, x, y, graph)));
    }

    private void genCompareOp(CiKind kind, int opcode, CiKind resultKind) {
        Value y = frameState.pop(kind);
        Value x = frameState.pop(kind);
        Value value = append(new CompareOp(opcode, resultKind, x, y, graph));
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
        frameState.storeLocal(index, append(new ArithmeticOp(IADD, CiKind.Int, x, y, isStrict(method().accessFlags()), false, graph)));
    }

    private void genGoto(int fromBCI, int toBCI) {
        append(new Goto(blockAt(toBCI), null, graph));
    }

    private void ifNode(Value x, Condition cond, Value y, FrameState stateBefore) {
        BlockBegin tsucc = blockAt(stream().readBranchDest());
        BlockBegin fsucc = blockAt(stream().nextBCI());
        int bci = stream().currentBCI();
        boolean isSafepoint = !noSafepoints() && (tsucc.bci() <= bci || fsucc.bci() <= bci);
        if (isSafepoint) {
            append(new If(x, cond, y, tsucc, fsucc, stateBefore, graph));
        } else {
            append(new If(x, cond, y, tsucc, fsucc, null, graph));
            stateBefore.delete();
        }
    }

    private void genIfZero(Condition cond) {
        Value y = appendConstant(CiConstant.INT_0);
        FrameState stateBefore = frameState.create(bci());
        Value x = frameState.ipop();
        ifNode(x, cond, y, stateBefore);
    }

    private void genIfNull(Condition cond) {
        FrameState stateBefore = frameState.create(bci());
        Value y = appendConstant(CiConstant.NULL_OBJECT);
        Value x = frameState.apop();
        ifNode(x, cond, y, stateBefore);
    }

    private void genIfSame(CiKind kind, Condition cond) {
        FrameState stateBefore = frameState.create(bci());
        Value y = frameState.pop(kind);
        Value x = frameState.pop(kind);
        ifNode(x, cond, y, stateBefore);
    }

    private void genThrow(int bci) {
        FrameState stateBefore = frameState.create(bci);
        Throw t = new Throw(frameState.apop(), graph);
        t.setStateBefore(stateBefore);
        appendWithoutOptimization(t, bci);
    }

    private void genCheckCast() {
        int cpi = stream().readCPI();
        RiType type = constantPool().lookupType(cpi, CHECKCAST);
        boolean isInitialized = type.isResolved();
        Value typeInstruction = genTypeOrDeopt(RiType.Representation.ObjectHub, type, isInitialized, cpi);
        Value object = frameState.apop();
        if (typeInstruction != null) {
            frameState.apush(append(new CheckCast(type, typeInstruction, object, graph)));
        } else {
            frameState.apush(appendConstant(CiConstant.NULL_OBJECT));
        }
    }

    private void genInstanceOf() {
        int cpi = stream().readCPI();
        RiType type = constantPool().lookupType(cpi, INSTANCEOF);
        boolean isInitialized = type.isResolved();
        Value typeInstruction = genTypeOrDeopt(RiType.Representation.ObjectHub, type, isInitialized, cpi);
        Value object = frameState.apop();
        if (typeInstruction != null) {
            frameState.ipush(append(new InstanceOf(type, typeInstruction, object, graph)));
        } else {
            frameState.ipush(appendConstant(CiConstant.INT_0));
        }
    }

    void genNewInstance(int cpi) {
        RiType type = constantPool().lookupType(cpi, NEW);
        if (type.isResolved()) {
            NewInstance n = new NewInstance(type, cpi, constantPool(), graph);
            frameState.apush(append(n));
        } else {
            append(new Deoptimize(graph));
            frameState.apush(appendConstant(CiConstant.NULL_OBJECT));
        }
    }

    private void genNewTypeArray(int typeCode) {
        CiKind kind = CiKind.fromArrayTypeCode(typeCode);
        RiType elementType = compilation.runtime.asRiType(kind);
        NewTypeArray nta = new NewTypeArray(frameState.ipop(), elementType, graph);
        frameState.apush(append(nta));
    }

    private void genNewObjectArray(int cpi) {
        RiType type = constantPool().lookupType(cpi, ANEWARRAY);
        Value length = frameState.ipop();
        if (type.isResolved()) {
            NewArray n = new NewObjectArray(type, length, graph);
            frameState.apush(append(n));
        } else {
            append(new Deoptimize(graph));
            frameState.apush(appendConstant(CiConstant.NULL_OBJECT));
        }

    }

    private void genNewMultiArray(int cpi) {
        RiType type = constantPool().lookupType(cpi, MULTIANEWARRAY);
        int rank = stream().readUByte(bci() + 3);
        Value[] dims = new Value[rank];
        for (int i = rank - 1; i >= 0; i--) {
            dims[i] = frameState.ipop();
        }
        if (type.isResolved()) {
            NewArray n = new NewMultiArray(type, dims, cpi, constantPool(), graph);
            frameState.apush(append(n));
        } else {
            append(new Deoptimize(graph));
            frameState.apush(appendConstant(CiConstant.NULL_OBJECT));
        }
    }

    private void genGetField(int cpi, RiField field) {
        CiKind kind = field.kind();
        Value receiver = frameState.apop();
        if (field.isResolved()) {
            LoadField load = new LoadField(receiver, field, graph);
            appendOptimizedLoadField(kind, load);
        } else {
            append(new Deoptimize(graph));
            frameState.push(kind.stackKind(), append(Constant.defaultForKind(kind, graph)));
        }
    }

    private void genPutField(int cpi, RiField field) {
        Value value = frameState.pop(field.kind().stackKind());
        Value receiver = frameState.apop();
        if (field.isResolved()) {
            StoreField store = new StoreField(receiver, field, value, graph);
            appendOptimizedStoreField(store);
        } else {
            append(new Deoptimize(graph));
        }
    }

    private void genGetStatic(int cpi, RiField field) {
        RiType holder = field.holder();
        boolean isInitialized = field.isResolved();
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
                append(new Deoptimize(graph));
                frameState.push(kind.stackKind(), append(Constant.defaultForKind(kind, graph)));
            }
        }
    }

    private void genPutStatic(int cpi, RiField field) {
        RiType holder = field.holder();
        Value container = genTypeOrDeopt(RiType.Representation.StaticFields, holder, field.isResolved(), cpi);
        Value value = frameState.pop(field.kind().stackKind());
        if (container != null) {
            StoreField store = new StoreField(container, field, value, graph);
            appendOptimizedStoreField(store);
        } else {
            append(new Deoptimize(graph));
        }
    }

    private Value genTypeOrDeopt(RiType.Representation representation, RiType holder, boolean initialized, int cpi) {
        if (initialized) {
            return appendConstant(holder.getEncoding(representation));
        } else {
            append(new Deoptimize(graph));
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
        if (!isInitialized && C1XOptions.ResolveClassBeforeStaticInvoke) {
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
        Value result = append(new Invoke(opcode, resultType.stackKind(), args, target, target.signature().returnType(compilation.method.holder()), graph));
        frameState.pushReturn(resultType, result);
    }

    private RiType getExactType(RiType staticType, Value receiver) {
        RiType exact = staticType.exactType();
        if (exact == null) {
            exact = receiver.exactType();
            if (exact == null) {
                if (receiver.isConstant()) {
                    exact = compilation.runtime.getTypeOf(receiver.asConstant());
                }
                if (exact == null) {
                    RiType declared = receiver.declaredType();
                    exact = declared == null || !declared.isResolved() ? null : declared.exactType();
                }
            }
        }
        return exact;
    }

    private RiType getAssumedLeafType(RiType type) {
        if (isFinal(type.accessFlags())) {
            return type;
        }
        RiType assumed = null;
        if (C1XOptions.UseAssumptions) {
            assumed = type.uniqueConcreteSubtype();
            if (assumed != null) {
                if (C1XOptions.PrintAssumptions) {
                    TTY.println("Recording concrete subtype assumption in context of " + type.name() + ": " + assumed.name());
                }
                compilation.assumptions.recordConcreteSubtype(type, assumed);
            }
        }
        return assumed;
    }

    private void callRegisterFinalizer() {
        Value receiver = frameState.loadLocal(0);
        RiType declaredType = receiver.declaredType();
        RiType receiverType = declaredType;
        RiType exactType = receiver.exactType();
        if (exactType == null && declaredType != null) {
            exactType = declaredType.exactType();
        }
        if (exactType == null && receiver instanceof Local && ((Local) receiver).javaIndex() == 0) {
            // the exact type isn't known, but the receiver is parameter 0 => use holder
            receiverType = compilation.method.holder();
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
            append(new RegisterFinalizer(frameState.loadLocal(0), frameState.create(bci()), graph));
            C1XMetrics.InlinedFinalizerChecks++;
        }
    }

    private void genReturn(Value x) {
        if (method().isConstructor() && method().holder().superType() == null) {
            callRegisterFinalizer();
        }

        frameState.clearStack();
        if (Modifier.isSynchronized(method().accessFlags())) {
            // unlock before exiting the method
            int lockNumber = frameState.locksSize() - 1;
            MonitorAddress lockAddress = null;
            if (compilation.runtime.sizeOfBasicObjectLock() != 0) {
                lockAddress = new MonitorAddress(lockNumber, graph);
                append(lockAddress);
            }
            append(new MonitorExit(rootMethodSynchronizedObject, lockAddress, lockNumber, graph));
            frameState.unlock();
        }
        append(new Return(x, graph));
    }

    private void genMonitorEnter(Value x, int bci) {
        int lockNumber = frameState.locksSize();
        MonitorAddress lockAddress = null;
        if (compilation.runtime.sizeOfBasicObjectLock() != 0) {
            lockAddress = new MonitorAddress(lockNumber, graph);
            append(lockAddress);
        }
        MonitorEnter monitorEnter = new MonitorEnter(x, lockAddress, lockNumber, graph);
        appendWithoutOptimization(monitorEnter, bci);
        frameState.lock(ir, x, lockNumber + 1);
        if (bci == Instruction.SYNCHRONIZATION_ENTRY_BCI) {
            monitorEnter.setStateAfter(frameState.create(0));
        }
    }

    private void genMonitorExit(Value x, int bci) {
        int lockNumber = frameState.locksSize() - 1;
        if (lockNumber < 0) {
            throw new CiBailout("monitor stack underflow");
        }
        MonitorAddress lockAddress = null;
        if (compilation.runtime.sizeOfBasicObjectLock() != 0) {
            lockAddress = new MonitorAddress(lockNumber, graph);
            append(lockAddress);
        }
        appendWithoutOptimization(new MonitorExit(x, lockAddress, lockNumber, graph), bci);
        frameState.unlock();
    }

    private void genJsr(int dest) {
        throw new CiBailout("jsr/ret not supported");
    }

    private void genRet(int localIndex) {
        throw new CiBailout("jsr/ret not supported");
    }

    private void genTableswitch() {
        int bci = bci();
        BytecodeTableSwitch ts = new BytecodeTableSwitch(stream(), bci);
        int max = ts.numberOfCases();
        List<BlockBegin> list = new ArrayList<BlockBegin>(max + 1);
        boolean isBackwards = false;
        for (int i = 0; i < max; i++) {
            // add all successors to the successor list
            int offset = ts.offsetAt(i);
            list.add(blockAt(bci + offset));
            isBackwards |= offset < 0; // track if any of the successors are backwards
        }
        int offset = ts.defaultOffset();
        isBackwards |= offset < 0; // if the default successor is backwards
        list.add(blockAt(bci + offset));
        boolean isSafepoint = isBackwards && !noSafepoints();
        FrameState stateBefore = isSafepoint ? frameState.create(bci()) : null;
        append(new TableSwitch(frameState.ipop(), list, ts.lowKey(), stateBefore, graph));
    }

    private void genLookupswitch() {
        int bci = bci();
        BytecodeLookupSwitch ls = new BytecodeLookupSwitch(stream(), bci);
        int max = ls.numberOfCases();
        List<BlockBegin> list = new ArrayList<BlockBegin>(max + 1);
        int[] keys = new int[max];
        boolean isBackwards = false;
        for (int i = 0; i < max; i++) {
            // add all successors to the successor list
            int offset = ls.offsetAt(i);
            list.add(blockAt(bci + offset));
            keys[i] = ls.keyAt(i);
            isBackwards |= offset < 0; // track if any of the successors are backwards
        }
        int offset = ls.defaultOffset();
        isBackwards |= offset < 0; // if the default successor is backwards
        list.add(blockAt(bci + offset));
        boolean isSafepoint = isBackwards && !noSafepoints();
        FrameState stateBefore = isSafepoint ? frameState.create(bci()) : null;
        append(new LookupSwitch(frameState.ipop(), list, keys, stateBefore, graph));
    }

    private Value appendConstant(CiConstant constant) {
        return appendWithBCI(new Constant(constant, graph), bci());
    }

    private Value append(Instruction x) {
        return appendWithBCI(x, bci());
    }

    private Value appendWithoutOptimization(Instruction x, int bci) {
        return appendWithBCI(x, bci);
    }

    private Value appendWithBCI(Instruction x, int bci) {
        if (x.isAppended()) {
            // the instruction has already been added
            return x;
        }

        assert x.next() == null : "instruction should not have been appended yet";
        assert lastInstr.next() == null : "cannot append instruction to instruction which isn't end (" + lastInstr + "->" + lastInstr.next() + ")";

        lastInstr = lastInstr.appendNext(x, bci);
        if (++stats.nodeCount >= C1XOptions.MaximumInstructionCount) {
            // bailout if we've exceeded the maximum inlining size
            throw new CiBailout("Method and/or inlining is too large");
        }

        if (x instanceof Invoke || x instanceof Throw) {
            // connect the instruction to any exception handlers
            handleException(x, bci);
        }

        return x;
    }

    private BlockBegin blockAtOrNull(int bci) {
        return blockList[bci];
    }

    private BlockBegin blockAt(int bci) {
        BlockBegin result = blockAtOrNull(bci);
        assert result != null : "Expected a block to begin at " + bci;
        return result;
    }

    private Value synchronizedObject(FrameStateAccess state, RiMethod target) {
        if (isStatic(target.accessFlags())) {
            Constant classConstant = new Constant(target.holder().getEncoding(Representation.JavaClass), graph);
            return appendWithoutOptimization(classConstant, Instruction.SYNCHRONIZATION_ENTRY_BCI);
        } else {
            return state.localAt(0);
        }
    }

    private void fillSyncHandler(Value lock, BlockBegin syncHandler) {
        BlockBegin origBlock = curBlock;
        FrameState origState = frameState.create(-1);
        Instruction origLast = lastInstr;

        lastInstr = curBlock = syncHandler;
        while (lastInstr.next() != null) {
            // go forward to the end of the block
            lastInstr = lastInstr.next();
        }
        frameState.initializeFrom(syncHandler.stateBefore());

        int bci = Instruction.SYNCHRONIZATION_ENTRY_BCI;

        assert lock != null;
        assert frameState.locksSize() > 0 && frameState.lockAt(frameState.locksSize() - 1) == lock;
        if (lock instanceof Instruction) {
            Instruction l = (Instruction) lock;
            if (!l.isAppended()) {
                lock = appendWithoutOptimization(l, Instruction.SYNCHRONIZATION_ENTRY_BCI);
            }
        }
        // exit the monitor
        genMonitorExit(lock, Instruction.SYNCHRONIZATION_ENTRY_BCI);

        genThrow(bci);
        BlockEnd end = (BlockEnd) lastInstr;
        curBlock.setEnd(end);
        end.setStateAfter(frameState.create(bci()));

        curBlock = origBlock;
        frameState.initializeFrom(origState);
        origState.delete();
        lastInstr = origLast;
    }

    private void iterateAllBlocks() {
        BlockBegin b;
        while ((b = removeFromWorkList()) != null) {

            // remove blocks that have no predecessors by the time it their bytecodes are parsed
            if (b.blockPredecessors().size() == 0) {
                markVisited(b);
                continue;
            }

            if (!isVisited(b)) {
                markVisited(b);
                // now parse the block
                curBlock = b;
                frameState.initializeFrom(b.stateBefore());
                lastInstr = b;
                b.appendNext(null, -1);

                iterateBytecodesForBlock(b.bci());
            }
        }
    }

    private BlockEnd iterateBytecodesForBlock(int bci) {
        assert frameState != null;
        stream.setBCI(bci);

        BlockBegin block = curBlock;
        BlockEnd end = null;
        int prevBCI = bci;
        int endBCI = stream.endBCI();
        boolean blockStart = true;

        while (bci < endBCI) {
            BlockBegin nextBlock = blockAtOrNull(bci);
            if (nextBlock != null && nextBlock != block) {
                // we fell through to the next block, add a goto and break
                end = new Goto(nextBlock, null, graph);
                lastInstr = lastInstr.appendNext(end, prevBCI);
                break;
            }
            // read the opcode
            int opcode = stream.currentBC();

            traceState();
            traceInstruction(bci, stream, opcode, blockStart);
            processBytecode(bci, stream, opcode);

            prevBCI = bci;

            if (lastInstr instanceof BlockEnd) {
                end = (BlockEnd) lastInstr;
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
            blockStart = false;
        }

        // if the method terminates, we don't need the stack anymore
        if (end instanceof Return || end instanceof Throw) {
            frameState.clearStack();
        }

        // connect to begin and set state
        // NOTE that inlining may have changed the block we are parsing
        assert end != null : "end should exist after iterating over bytecodes";
        FrameState stateAtEnd = frameState.create(bci());
        end.setStateAfter(stateAtEnd);
        curBlock.setEnd(end);

        // propagate the state
        for (BlockBegin succ : end.blockSuccessors()) {
            assert succ.blockPredecessors().contains(curBlock.end());
            succ.mergeOrClone(stateAtEnd, method());
            addToWorkList(succ);
        }
        return end;
    }

    private void traceState() {
        if (C1XOptions.TraceBytecodeParserLevel >= TRACELEVEL_STATE && !TTY.isSuppressed()) {
            log.println(String.format("|   state [nr locals = %d, stack depth = %d, method = %s]", frameState.localsSize(), frameState.stackSize(), method()));
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

    private void processBytecode(int bci, BytecodeStream s, int opcode) {
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
            case BIPUSH         : frameState.ipush(appendConstant(CiConstant.forInt(s.readByte()))); break;
            case SIPUSH         : frameState.ipush(appendConstant(CiConstant.forInt(s.readShort()))); break;
            case LDC            : // fall through
            case LDC_W          : // fall through
            case LDC2_W         : genLoadConstant(s.readCPI()); break;
            case ILOAD          : loadLocal(s.readLocalIndex(), CiKind.Int); break;
            case LLOAD          : loadLocal(s.readLocalIndex(), CiKind.Long); break;
            case FLOAD          : loadLocal(s.readLocalIndex(), CiKind.Float); break;
            case DLOAD          : loadLocal(s.readLocalIndex(), CiKind.Double); break;
            case ALOAD          : loadLocal(s.readLocalIndex(), CiKind.Object); break;
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
            case ISTORE         : storeLocal(CiKind.Int, s.readLocalIndex()); break;
            case LSTORE         : storeLocal(CiKind.Long, s.readLocalIndex()); break;
            case FSTORE         : storeLocal(CiKind.Float, s.readLocalIndex()); break;
            case DSTORE         : storeLocal(CiKind.Double, s.readLocalIndex()); break;
            case ASTORE         : storeLocal(CiKind.Object, s.readLocalIndex()); break;
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
            case GOTO           : genGoto(s.currentBCI(), s.readBranchDest()); break;
            case JSR            : genJsr(s.readBranchDest()); break;
            case RET            : genRet(s.readLocalIndex()); break;
            case TABLESWITCH    : genTableswitch(); break;
            case LOOKUPSWITCH   : genLookupswitch(); break;
            case IRETURN        : genReturn(frameState.ipop()); break;
            case LRETURN        : genReturn(frameState.lpop()); break;
            case FRETURN        : genReturn(frameState.fpop()); break;
            case DRETURN        : genReturn(frameState.dpop()); break;
            case ARETURN        : genReturn(frameState.apop()); break;
            case RETURN         : genReturn(null  ); break;
            case GETSTATIC      : cpi = s.readCPI(); genGetStatic(cpi, constantPool().lookupField(cpi, opcode)); break;
            case PUTSTATIC      : cpi = s.readCPI(); genPutStatic(cpi, constantPool().lookupField(cpi, opcode)); break;
            case GETFIELD       : cpi = s.readCPI(); genGetField(cpi, constantPool().lookupField(cpi, opcode)); break;
            case PUTFIELD       : cpi = s.readCPI(); genPutField(cpi, constantPool().lookupField(cpi, opcode)); break;
            case INVOKEVIRTUAL  : cpi = s.readCPI(); genInvokeVirtual(constantPool().lookupMethod(cpi, opcode), cpi, constantPool()); break;
            case INVOKESPECIAL  : cpi = s.readCPI(); genInvokeSpecial(constantPool().lookupMethod(cpi, opcode), null, cpi, constantPool()); break;
            case INVOKESTATIC   : cpi = s.readCPI(); genInvokeStatic(constantPool().lookupMethod(cpi, opcode), cpi, constantPool()); break;
            case INVOKEINTERFACE: cpi = s.readCPI(); genInvokeInterface(constantPool().lookupMethod(cpi, opcode), cpi, constantPool()); break;
            case NEW            : genNewInstance(s.readCPI()); break;
            case NEWARRAY       : genNewTypeArray(s.readLocalIndex()); break;
            case ANEWARRAY      : genNewObjectArray(s.readCPI()); break;
            case ARRAYLENGTH    : genArrayLength(); break;
            case ATHROW         : genThrow(s.currentBCI()); break;
            case CHECKCAST      : genCheckCast(); break;
            case INSTANCEOF     : genInstanceOf(); break;
            case MONITORENTER   : genMonitorEnter(frameState.apop(), s.currentBCI()); break;
            case MONITOREXIT    : genMonitorExit(frameState.apop(), s.currentBCI()); break;
            case MULTIANEWARRAY : genNewMultiArray(s.readCPI()); break;
            case IFNULL         : genIfNull(Condition.EQ); break;
            case IFNONNULL      : genIfNull(Condition.NE); break;
            case GOTO_W         : genGoto(s.currentBCI(), s.readFarBranchDest()); break;
            case JSR_W          : genJsr(s.readFarBranchDest()); break;
            case BREAKPOINT:
                throw new CiBailout("concurrent setting of breakpoint");
            default:
                throw new CiBailout("Unsupported opcode " + opcode + " (" + nameOf(opcode) + ") [bci=" + bci + "]");
        }
        // Checkstyle: resume
    }

    private void traceInstruction(int bci, BytecodeStream s, int opcode, boolean blockStart) {
        if (C1XOptions.TraceBytecodeParserLevel >= TRACELEVEL_INSTRUCTIONS && !TTY.isSuppressed()) {
            StringBuilder sb = new StringBuilder(40);
            sb.append(blockStart ? '+' : '|');
            if (bci < 10) {
                sb.append("  ");
            } else if (bci < 100) {
                sb.append(' ');
            }
            sb.append(bci).append(": ").append(Bytecodes.nameOf(opcode));
            for (int i = bci + 1; i < s.nextBCI(); ++i) {
                sb.append(' ').append(s.readUByte(i));
            }
            log.println(sb.toString());
        }
    }

    private void genArrayLength() {
        frameState.ipush(append(new ArrayLength(frameState.apop(), graph)));
    }

    private RiConstantPool constantPool() {
        return constantPool;
    }

    /**
     * Adds an exception handler.
     * @param handler the handler to add
     */
    private void addExceptionHandler(ExceptionHandler handler) {
        if (exceptionHandlers == null) {
            exceptionHandlers = new ArrayList<ExceptionHandler>();
        }
        exceptionHandlers.add(handler);
        flags |= Flag.HasHandler.mask;
    }

    /**
     * Adds a block to the worklist, if it is not already in the worklist.
     * This method will keep the worklist topologically stored (i.e. the lower
     * DFNs are earlier in the list).
     * @param block the block to add to the work list
     */
    private void addToWorkList(BlockBegin block) {
        if (!isOnWorkList(block)) {
            markOnWorkList(block);
            sortIntoWorkList(block);
        }
    }

    private void sortIntoWorkList(BlockBegin top) {
        // XXX: this is O(n), since the whole list is sorted; a heap could achieve O(nlogn), but
        //      would only pay off for large worklists
        if (workList == null) {
            // need to allocate the worklist
            workList = new BlockBegin[5];
        } else if (workListIndex == workList.length) {
            // need to grow the worklist
            BlockBegin[] nworkList = new BlockBegin[workList.length * 3];
            System.arraycopy(workList, 0, nworkList, 0, workList.length);
            workList = nworkList;
        }
        // put the block at the end of the array
        workList[workListIndex++] = top;
        int dfn = top.depthFirstNumber();
        assert dfn >= 0 : top + " does not have a depth first number";
        int i = workListIndex - 2;
        // push top towards the beginning of the array
        for (; i >= 0; i--) {
            BlockBegin b = workList[i];
            if (b.depthFirstNumber() >= dfn) {
                break; // already in the right position
            }
            workList[i + 1] = b; // bubble b down by one
            workList[i] = top;   // and overwrite it with top
        }
    }

    /**
     * Removes the next block from the worklist. The list is sorted topologically, so the
     * block with the lowest depth first number in the list will be removed and returned.
     * @return the next block from the worklist; {@code null} if there are no blocks
     * in the worklist
     */
    private BlockBegin removeFromWorkList() {
        if (workListIndex == 0) {
            return null;
        }
        // pop the last item off the end
        return workList[--workListIndex];
    }

    /**
     * Checks whether this graph has any handlers.
     * @return {@code true} if there are any exception handlers
     */
    private boolean hasHandler() {
        return (flags & Flag.HasHandler.mask) != 0;
    }

    /**
     * Checks whether this graph can contain safepoints.
     */
    private boolean noSafepoints() {
        return (flags & Flag.NoSafepoints.mask) != 0;
    }
}
