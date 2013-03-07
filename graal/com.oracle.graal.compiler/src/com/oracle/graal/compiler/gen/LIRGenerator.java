/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.compiler.gen;

import static com.oracle.graal.api.code.CallingConvention.Type.*;
import static com.oracle.graal.api.code.ValueUtil.*;
import static com.oracle.graal.api.meta.Value.*;
import static com.oracle.graal.lir.LIRValueUtil.*;

import java.util.*;
import java.util.Map.Entry;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.StandardOp.JumpOp;
import com.oracle.graal.lir.StandardOp.LabelOp;
import com.oracle.graal.lir.StandardOp.ParametersOp;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.PhiNode.PhiType;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.cfg.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.virtual.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.util.*;

/**
 * This class traverses the HIR instructions and generates LIR instructions from them.
 */
public abstract class LIRGenerator extends LIRGeneratorTool {

    protected final StructuredGraph graph;
    protected final CodeCacheProvider runtime;
    protected final TargetDescription target;
    protected final ResolvedJavaMethod method;
    protected final FrameMap frameMap;
    public final NodeMap<Value> nodeOperands;

    protected final LIR lir;
    private final DebugInfoBuilder debugInfoBuilder;

    private Block currentBlock;
    private ValueNode currentInstruction;
    private ValueNode lastInstructionPrinted; // Debugging only
    private FrameState lastState;

    /**
     * Mapping from blocks to the last encountered frame state at the end of the block.
     */
    private final BlockMap<FrameState> blockLastState;

    /**
     * The number of currently locked monitors.
     */
    private int currentLockCount;

    /**
     * Mapping from blocks to the number of locked monitors at the end of the block.
     */
    private final BlockMap<Integer> blockLastLockCount;

    /**
     * Contains the lock data slot for each lock depth (so these may be reused within a compiled
     * method).
     */
    private final ArrayList<StackSlot> lockDataSlots;

    /**
     * Checks whether the supplied constant can be used without loading it into a register for store
     * operations, i.e., on the right hand side of a memory access.
     * 
     * @param c The constant to check.
     * @return True if the constant can be used directly, false if the constant needs to be in a
     *         register.
     */
    public abstract boolean canStoreConstant(Constant c);

    public LIRGenerator(StructuredGraph graph, CodeCacheProvider runtime, TargetDescription target, FrameMap frameMap, ResolvedJavaMethod method, LIR lir) {
        this.graph = graph;
        this.runtime = runtime;
        this.target = target;
        this.frameMap = frameMap;
        this.method = method;
        this.nodeOperands = graph.createNodeMap();
        this.lir = lir;
        this.debugInfoBuilder = new DebugInfoBuilder(nodeOperands);
        this.blockLastLockCount = new BlockMap<>(lir.cfg);
        this.lockDataSlots = new ArrayList<>();
        this.blockLastState = new BlockMap<>(lir.cfg);
    }

    @Override
    public TargetDescription target() {
        return target;
    }

    @Override
    public CodeCacheProvider getRuntime() {
        return runtime;
    }

    public ResolvedJavaMethod method() {
        return method;
    }

    /**
     * Returns the operand that has been previously initialized by
     * {@link #setResult(ValueNode, Value)} with the result of an instruction.
     * 
     * @param node A node that produces a result value.
     */
    @Override
    public Value operand(ValueNode node) {
        if (nodeOperands == null) {
            return null;
        }
        return nodeOperands.get(node);
    }

    public ValueNode valueForOperand(Value value) {
        for (Entry<Node, Value> entry : nodeOperands.entries()) {
            if (entry.getValue() == value) {
                return (ValueNode) entry.getKey();
            }
        }
        return null;
    }

    /**
     * Creates a new {@linkplain Variable variable}.
     * 
     * @param kind The kind of the new variable.
     * @return a new variable
     */
    @Override
    public Variable newVariable(Kind kind) {
        Kind stackKind = kind.getStackKind();
        switch (stackKind) {
            case Int:
            case Long:
            case Object:
                return new Variable(stackKind, lir.nextVariable(), Register.RegisterFlag.CPU);
            case Float:
            case Double:
                return new Variable(stackKind, lir.nextVariable(), Register.RegisterFlag.FPU);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    @Override
    public RegisterAttributes attributes(Register register) {
        return frameMap.registerConfig.getAttributesMap()[register.number];
    }

    @Override
    public Value setResult(ValueNode x, Value operand) {
        assert (isVariable(operand) && x.kind() == operand.getKind()) || (isRegister(operand) && !attributes(asRegister(operand)).isAllocatable()) ||
                        (isConstant(operand) && x.kind() == operand.getKind().getStackKind()) : operand.getKind() + " for node " + x;
        assert operand(x) == null : "operand cannot be set twice";
        assert operand != null && isLegal(operand) : "operand must be legal";
        assert operand.getKind().getStackKind() == x.kind() : operand.getKind().getStackKind() + " must match " + x.kind();
        assert !(x instanceof VirtualObjectNode);
        nodeOperands.set(x, operand);
        return operand;
    }

    @Override
    public abstract Variable emitMove(Value input);

    public AllocatableValue asAllocatable(Value value) {
        if (isAllocatableValue(value)) {
            return asAllocatableValue(value);
        } else {
            return emitMove(value);
        }
    }

    public Variable load(Value value) {
        if (!isVariable(value)) {
            return emitMove(value);
        }
        return (Variable) value;
    }

    public Value loadNonConst(Value value) {
        if (isConstant(value) && !canInlineConstant((Constant) value)) {
            return emitMove(value);
        }
        return value;
    }

    protected LabelRef getLIRBlock(FixedNode b) {
        Block result = lir.cfg.blockFor(b);
        int suxIndex = currentBlock.getSuccessors().indexOf(result);
        assert suxIndex != -1 : "Block not in successor list of current block";

        return LabelRef.forSuccessor(lir, currentBlock, suxIndex);
    }

    /**
     * Determines if only oop maps are required for the code generated from the LIR.
     */
    protected boolean needOnlyOopMaps() {
        return false;
    }

    public LIRFrameState state() {
        assert lastState != null || needOnlyOopMaps() : "must have state before instruction";
        return stateFor(lastState);
    }

    public LIRFrameState stateFor(FrameState state) {
        return stateFor(state, null);
    }

    public LIRFrameState stateFor(FrameState state, LabelRef exceptionEdge) {
        if (needOnlyOopMaps()) {
            return new LIRFrameState(null, null, null);
        }
        return debugInfoBuilder.build(state, lockDataSlots.subList(0, currentLockCount), exceptionEdge);
    }

    /**
     * Gets the ABI specific operand used to return a value of a given kind from a method.
     * 
     * @param kind the kind of value being returned
     * @return the operand representing the ABI defined location used return a value of kind
     *         {@code kind}
     */
    public Value resultOperandFor(Kind kind) {
        if (kind == Kind.Void) {
            return ILLEGAL;
        }
        return frameMap.registerConfig.getReturnRegister(kind).asValue(kind);
    }

    public void append(LIRInstruction op) {
        assert LIRVerifier.verify(op);
        if (GraalOptions.PrintIRWithLIR && !TTY.isSuppressed()) {
            if (currentInstruction != null && lastInstructionPrinted != currentInstruction) {
                lastInstructionPrinted = currentInstruction;
                InstructionPrinter ip = new InstructionPrinter(TTY.out());
                ip.printInstructionListing(currentInstruction);
            }
            TTY.println(op.toStringWithIdPrefix());
            TTY.println();
        }
        lir.lir(currentBlock).add(op);
    }

    public void doBlock(Block block) {
        if (GraalOptions.PrintIRWithLIR) {
            TTY.print(block.toString());
        }

        currentBlock = block;
        // set up the list of LIR instructions
        assert lir.lir(block) == null : "LIR list already computed for this block";
        lir.setLir(block, new ArrayList<LIRInstruction>());

        append(new LabelOp(new Label(), block.isAligned()));

        if (GraalOptions.TraceLIRGeneratorLevel >= 1) {
            TTY.println("BEGIN Generating LIR for block B" + block.getId());
        }

        if (block == lir.cfg.getStartBlock()) {
            assert block.getPredecessorCount() == 0;
            currentLockCount = 0;
            emitPrologue();

        } else {
            assert block.getPredecessorCount() > 0;

            currentLockCount = -1;
            for (Block pred : block.getPredecessors()) {
                Integer predLocks = blockLastLockCount.get(pred);
                if (currentLockCount == -1) {
                    currentLockCount = predLocks;
                } else {
                    assert (predLocks == null && pred.isLoopEnd()) || currentLockCount == predLocks;
                }
            }

            FrameState fs = null;

            for (Block pred : block.getPredecessors()) {
                if (fs == null) {
                    fs = blockLastState.get(pred);
                } else {
                    if (blockLastState.get(pred) == null) {
                        // Only a back edge can have a null state for its enclosing block.
                        assert pred.getEndNode() instanceof LoopEndNode;

                        if (block.getBeginNode().stateAfter() == null) {
                            // We'll assert later that the begin and end of a framestate-less loop
                            // share the frame state that flowed into the loop
                            blockLastState.put(pred, fs);
                        }
                    } else if (fs != blockLastState.get(pred)) {
                        fs = null;
                        break;
                    }
                }
            }
            if (GraalOptions.TraceLIRGeneratorLevel >= 2) {
                if (fs == null) {
                    TTY.println("STATE RESET");
                } else {
                    TTY.println("STATE CHANGE (singlePred)");
                    if (GraalOptions.TraceLIRGeneratorLevel >= 3) {
                        TTY.println(fs.toString(Node.Verbosity.Debugger));
                    }
                }
            }
            lastState = fs;
        }

        List<ScheduledNode> nodes = lir.nodesFor(block);
        for (int i = 0; i < nodes.size(); i++) {
            Node instr = nodes.get(i);

            if (GraalOptions.OptImplicitNullChecks) {
                Node nextInstr = null;
                if (i < nodes.size() - 1) {
                    nextInstr = nodes.get(i + 1);
                }

                if (instr instanceof GuardNode) {
                    GuardNode guardNode = (GuardNode) instr;
                    if (guardNode.condition() instanceof IsNullNode && guardNode.negated()) {
                        IsNullNode isNullNode = (IsNullNode) guardNode.condition();
                        if (nextInstr instanceof Access) {
                            Access access = (Access) nextInstr;
                            if (isNullNode.object() == access.object() && canBeNullCheck(access.location())) {
                                access.setNullCheck(true);
                                continue;
                            }
                        }
                    }
                }
            }
            if (GraalOptions.TraceLIRGeneratorLevel >= 3) {
                TTY.println("LIRGen for " + instr);
            }
            FrameState stateAfter = null;
            if (instr instanceof StateSplit) {
                stateAfter = ((StateSplit) instr).stateAfter();
            }
            if (instr instanceof ValueNode) {

                ValueNode valueNode = (ValueNode) instr;
                if (operand(valueNode) == null) {
                    if (!peephole(valueNode)) {
                        try {
                            doRoot((ValueNode) instr);
                        } catch (GraalInternalError e) {
                            throw e.addContext(instr);
                        } catch (Throwable e) {
                            throw new GraalInternalError(e).addContext(instr);
                        }
                    }
                } else {
                    // There can be cases in which the result of an instruction is already set
                    // before by other instructions.
                }
            }
            if (stateAfter != null) {
                lastState = stateAfter;
                assert checkStateReady(lastState);
                if (GraalOptions.TraceLIRGeneratorLevel >= 2) {
                    TTY.println("STATE CHANGE");
                    if (GraalOptions.TraceLIRGeneratorLevel >= 3) {
                        TTY.println(stateAfter.toString(Node.Verbosity.Debugger));
                    }
                }
            }
        }
        if (block.getSuccessorCount() >= 1 && !endsWithJump(block)) {
            NodeClassIterable successors = block.getEndNode().successors();
            assert successors.isNotEmpty() : "should have at least one successor : " + block.getEndNode();

            emitJump(getLIRBlock((FixedNode) successors.first()), null);
        }

        if (GraalOptions.TraceLIRGeneratorLevel >= 1) {
            TTY.println("END Generating LIR for block B" + block.getId());
        }

        // Check that the begin and end of a framestate-less loop
        // share the frame state that flowed into the loop
        assert blockLastState.get(block) == null || blockLastState.get(block) == lastState;

        blockLastLockCount.put(currentBlock, currentLockCount);
        blockLastState.put(block, lastState);
        currentBlock = null;

        if (GraalOptions.PrintIRWithLIR) {
            TTY.println();
        }
    }

    protected abstract boolean peephole(ValueNode valueNode);

    private boolean checkStateReady(FrameState state) {
        FrameState fs = state;
        while (fs != null) {
            for (ValueNode v : fs.values()) {
                if (v != null && !(v instanceof VirtualObjectNode)) {
                    assert operand(v) != null : "Value " + v + " in " + fs + " is not ready!";
                }
            }
            fs = fs.outerFrameState();
        }
        return true;
    }

    private boolean endsWithJump(Block block) {
        List<LIRInstruction> instructions = lir.lir(block);
        if (instructions.size() == 0) {
            return false;
        }
        LIRInstruction lirInstruction = instructions.get(instructions.size() - 1);
        return lirInstruction instanceof StandardOp.JumpOp;
    }

    private void doRoot(ValueNode instr) {
        if (GraalOptions.TraceLIRGeneratorLevel >= 2) {
            TTY.println("Emitting LIR for instruction " + instr);
        }
        currentInstruction = instr;

        Debug.log("Visiting %s", instr);
        emitNode(instr);
        Debug.log("Operand for %s = %s", instr, operand(instr));
    }

    protected void emitNode(ValueNode node) {
        ((LIRLowerable) node).generate(this);
    }

    private boolean canBeNullCheck(LocationNode location) {
        return !(location instanceof IndexedLocationNode) && location.displacement() < this.target().implicitNullCheckLimit;
    }

    protected CallingConvention createCallingConvention() {
        return frameMap.registerConfig.getCallingConvention(JavaCallee, method.getSignature().getReturnType(null), MetaUtil.signatureToTypes(method), target, false);
    }

    protected void emitPrologue() {
        CallingConvention incomingArguments = createCallingConvention();

        Value[] params = new Value[incomingArguments.getArgumentCount()];
        for (int i = 0; i < params.length; i++) {
            params[i] = toStackKind(incomingArguments.getArgument(i));
            if (ValueUtil.isStackSlot(params[i])) {
                StackSlot slot = ValueUtil.asStackSlot(params[i]);
                if (slot.isInCallerFrame() && !lir.hasArgInCallerFrame()) {
                    lir.setHasArgInCallerFrame();
                }
            }
        }

        append(new ParametersOp(params));

        for (LocalNode local : graph.getNodes(LocalNode.class)) {
            Value param = params[local.index()];
            assert param.getKind() == local.kind().getStackKind();
            setResult(local, emitMove(param));
        }
    }

    /**
     * Increases the number of currently locked monitors and makes sure that a lock data slot is
     * available for the new lock.
     */
    public void lock() {
        if (lockDataSlots.size() == currentLockCount) {
            lockDataSlots.add(frameMap.allocateStackBlock(runtime.getSizeOfLockData(), false));
        }
        currentLockCount++;
    }

    /**
     * Decreases the number of currently locked monitors.
     * 
     * @throws GraalInternalError if the number of currently locked monitors is already zero.
     */
    public void unlock() {
        if (currentLockCount == 0) {
            throw new GraalInternalError("unmatched locks");
        }
        currentLockCount--;
    }

    /**
     * @return The lock data slot for the topmost locked monitor.
     */
    public StackSlot peekLock() {
        return lockDataSlots.get(currentLockCount - 1);
    }

    @Override
    public void visitReturn(ReturnNode x) {
        Value operand = Value.ILLEGAL;
        if (x.result() != null) {
            operand = resultOperandFor(x.result().kind());
            emitMove(operand, operand(x.result()));
        }
        emitReturn(operand);
    }

    protected abstract void emitReturn(Value input);

    @Override
    public void visitMerge(MergeNode x) {
    }

    @Override
    public void visitEndNode(EndNode end) {
        moveToPhi(end.merge(), end);
    }

    /**
     * Runtime specific classes can override this to insert a safepoint at the end of a loop.
     */
    @Override
    public void visitLoopEnd(LoopEndNode x) {
    }

    private void moveToPhi(MergeNode merge, EndNode pred) {
        if (GraalOptions.TraceLIRGeneratorLevel >= 1) {
            TTY.println("MOVE TO PHI from " + pred + " to " + merge);
        }
        PhiResolver resolver = new PhiResolver(this);
        for (PhiNode phi : merge.phis()) {
            if (phi.type() == PhiType.Value) {
                ValueNode curVal = phi.valueAt(pred);
                resolver.move(operandForPhi(phi), operand(curVal));
            }
        }
        resolver.dispose();

        append(new JumpOp(getLIRBlock(merge), null));
    }

    private Value operandForPhi(PhiNode phi) {
        assert phi.type() == PhiType.Value : "wrong phi type: " + phi;
        Value result = operand(phi);
        if (result == null) {
            // allocate a variable for this phi
            Variable newOperand = newVariable(phi.kind());
            setResult(phi, newOperand);
            return newOperand;
        } else {
            return result;
        }
    }

    @Override
    public void emitIf(IfNode x) {
        emitBranch(x.condition(), getLIRBlock(x.trueSuccessor()), getLIRBlock(x.falseSuccessor()), null);
    }

    @Override
    public void emitGuardCheck(LogicNode comp, DeoptimizationReason deoptReason, DeoptimizationAction action, boolean negated) {
        if (comp instanceof LogicConstantNode && ((LogicConstantNode) comp).getValue() != negated) {
            // True constant, nothing to emit.
            // False constants are handled within emitBranch.
        } else {
            // Fall back to a normal branch.
            LIRFrameState info = state();
            LabelRef stubEntry = createDeoptStub(action, deoptReason, info);
            if (negated) {
                emitBranch(comp, stubEntry, null, info);
            } else {
                emitBranch(comp, null, stubEntry, info);
            }
        }
    }

    public void emitBranch(LogicNode node, LabelRef trueSuccessor, LabelRef falseSuccessor, LIRFrameState info) {
        if (node instanceof IsNullNode) {
            emitNullCheckBranch((IsNullNode) node, trueSuccessor, falseSuccessor, info);
        } else if (node instanceof CompareNode) {
            emitCompareBranch((CompareNode) node, trueSuccessor, falseSuccessor, info);
        } else if (node instanceof LogicConstantNode) {
            emitConstantBranch(((LogicConstantNode) node).getValue(), trueSuccessor, falseSuccessor, info);
        } else if (node instanceof IntegerTestNode) {
            emitIntegerTestBranch((IntegerTestNode) node, trueSuccessor, falseSuccessor, info);
        } else {
            throw GraalInternalError.unimplemented(node.toString());
        }
    }

    private void emitNullCheckBranch(IsNullNode node, LabelRef trueSuccessor, LabelRef falseSuccessor, LIRFrameState info) {
        if (falseSuccessor != null) {
            emitCompareBranch(operand(node.object()), Constant.NULL_OBJECT, Condition.NE, false, falseSuccessor, info);
            if (trueSuccessor != null) {
                emitJump(trueSuccessor, null);
            }
        } else {
            emitCompareBranch(operand(node.object()), Constant.NULL_OBJECT, Condition.EQ, false, trueSuccessor, info);
        }
    }

    public void emitCompareBranch(CompareNode compare, LabelRef trueSuccessorBlock, LabelRef falseSuccessorBlock, LIRFrameState info) {
        if (falseSuccessorBlock != null) {
            emitCompareBranch(operand(compare.x()), operand(compare.y()), compare.condition().negate(), !compare.unorderedIsTrue(), falseSuccessorBlock, info);
            if (trueSuccessorBlock != null) {
                emitJump(trueSuccessorBlock, null);
            }
        } else {
            emitCompareBranch(operand(compare.x()), operand(compare.y()), compare.condition(), compare.unorderedIsTrue(), trueSuccessorBlock, info);
        }
    }

    public void emitIntegerTestBranch(IntegerTestNode test, LabelRef trueSuccessorBlock, LabelRef falseSuccessorBlock, LIRFrameState info) {
        if (falseSuccessorBlock != null) {
            emitIntegerTestBranch(operand(test.x()), operand(test.y()), true, falseSuccessorBlock, info);
            if (trueSuccessorBlock != null) {
                emitJump(trueSuccessorBlock, null);
            }
        } else {
            emitIntegerTestBranch(operand(test.x()), operand(test.y()), false, trueSuccessorBlock, info);
        }
    }

    public void emitConstantBranch(boolean value, LabelRef trueSuccessorBlock, LabelRef falseSuccessorBlock, LIRFrameState info) {
        LabelRef block = value ? trueSuccessorBlock : falseSuccessorBlock;
        if (block != null) {
            emitJump(block, info);
        }
    }

    @Override
    public void emitConditional(ConditionalNode conditional) {
        Value tVal = operand(conditional.trueValue());
        Value fVal = operand(conditional.falseValue());
        setResult(conditional, emitConditional(conditional.condition(), tVal, fVal));
    }

    public Variable emitConditional(LogicNode node, Value trueValue, Value falseValue) {
        if (node instanceof IsNullNode) {
            IsNullNode isNullNode = (IsNullNode) node;
            return emitConditionalMove(operand(isNullNode.object()), Constant.NULL_OBJECT, Condition.EQ, false, trueValue, falseValue);
        } else if (node instanceof CompareNode) {
            CompareNode compare = (CompareNode) node;
            return emitConditionalMove(operand(compare.x()), operand(compare.y()), compare.condition(), compare.unorderedIsTrue(), trueValue, falseValue);
        } else if (node instanceof LogicConstantNode) {
            return emitMove(((LogicConstantNode) node).getValue() ? trueValue : falseValue);
        } else if (node instanceof IntegerTestNode) {
            IntegerTestNode test = (IntegerTestNode) node;
            return emitIntegerTestMove(operand(test.x()), operand(test.y()), trueValue, falseValue);
        } else {
            throw GraalInternalError.unimplemented(node.toString());
        }
    }

    public abstract void emitJump(LabelRef label, LIRFrameState info);

    public abstract void emitCompareBranch(Value left, Value right, Condition cond, boolean unorderedIsTrue, LabelRef label, LIRFrameState info);

    public abstract void emitIntegerTestBranch(Value left, Value right, boolean negated, LabelRef label, LIRFrameState info);

    public abstract Variable emitConditionalMove(Value leftVal, Value right, Condition cond, boolean unorderedIsTrue, Value trueValue, Value falseValue);

    public abstract Variable emitIntegerTestMove(Value leftVal, Value right, Value trueValue, Value falseValue);

    @Override
    public void emitInvoke(Invoke x) {
        AbstractCallTargetNode callTarget = (AbstractCallTargetNode) x.callTarget();
        CallingConvention cc = frameMap.registerConfig.getCallingConvention(callTarget.callType(), x.node().stamp().javaType(runtime), callTarget.signature(), target(), false);
        frameMap.callsMethod(cc);

        Value[] parameters = visitInvokeArguments(cc, callTarget.arguments());

        LIRFrameState callState = null;
        if (x.stateAfter() != null) {
            callState = stateFor(x.stateDuring(), x instanceof InvokeWithExceptionNode ? getLIRBlock(((InvokeWithExceptionNode) x).exceptionEdge()) : null);
        }

        Value result = cc.getReturn();

        if (callTarget instanceof DirectCallTargetNode) {
            emitDirectCall((DirectCallTargetNode) callTarget, result, parameters, cc.getTemporaries(), callState);
        } else if (callTarget instanceof IndirectCallTargetNode) {
            emitIndirectCall((IndirectCallTargetNode) callTarget, result, parameters, cc.getTemporaries(), callState);
        } else {
            throw GraalInternalError.shouldNotReachHere();
        }

        if (isLegal(result)) {
            setResult(x.node(), emitMove(result));
        }
    }

    protected abstract void emitDirectCall(DirectCallTargetNode callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState callState);

    protected abstract void emitIndirectCall(IndirectCallTargetNode callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState callState);

    protected abstract void emitCall(RuntimeCallTarget callTarget, Value result, Value[] arguments, Value[] temps, Value targetAddress, LIRFrameState info);

    private static Value toStackKind(Value value) {
        if (value.getKind().getStackKind() != value.getKind()) {
            // We only have stack-kinds in the LIR, so convert the operand kind for values from the
            // calling convention.
            if (isRegister(value)) {
                return asRegister(value).asValue(value.getKind().getStackKind());
            } else if (isStackSlot(value)) {
                return StackSlot.get(value.getKind().getStackKind(), asStackSlot(value).getRawOffset(), asStackSlot(value).getRawAddFrameSize());
            } else {
                throw GraalInternalError.shouldNotReachHere();
            }
        }
        return value;
    }

    public Value[] visitInvokeArguments(CallingConvention cc, Collection<ValueNode> arguments) {
        // for each argument, load it into the correct location
        Value[] result = new Value[arguments.size()];
        int j = 0;
        for (ValueNode arg : arguments) {
            if (arg != null) {
                Value operand = toStackKind(cc.getArgument(j));
                emitMove(operand, operand(arg));
                result[j] = operand;
                j++;
            } else {
                throw GraalInternalError.shouldNotReachHere("I thought we no longer have null entries for two-slot types...");
            }
        }
        return result;
    }

    protected abstract LabelRef createDeoptStub(DeoptimizationAction action, DeoptimizationReason reason, LIRFrameState info);

    @Override
    public Variable emitCall(RuntimeCallTarget callTarget, CallingConvention cc, boolean canTrap, Value... args) {
        LIRFrameState info = canTrap ? state() : null;

        // move the arguments into the correct location
        frameMap.callsMethod(cc);
        assert cc.getArgumentCount() == args.length : "argument count mismatch";
        Value[] argLocations = new Value[args.length];
        for (int i = 0; i < args.length; i++) {
            Value arg = args[i];
            Value loc = cc.getArgument(i);
            emitMove(loc, arg);
            argLocations[i] = loc;
        }
        emitCall(callTarget, cc.getReturn(), argLocations, cc.getTemporaries(), Constant.forLong(0), info);

        if (isLegal(cc.getReturn())) {
            return emitMove(cc.getReturn());
        } else {
            return null;
        }
    }

    @Override
    public void visitRuntimeCall(RuntimeCallNode x) {
        RuntimeCallTarget call = runtime.lookupRuntimeCall(x.getDescriptor());
        CallingConvention cc = call.getCallingConvention();
        frameMap.callsMethod(cc);
        Value resultOperand = cc.getReturn();
        Value[] args = visitInvokeArguments(cc, x.arguments());

        LIRFrameState info = null;
        FrameState stateAfter = x.stateAfter();
        if (stateAfter != null) {
            // (cwimmer) I made the code that modifies the operand stack conditional. My scenario:
            // runtime calls to, e.g.,
            // CreateNullPointerException have no equivalent in the bytecodes, so there is no invoke
            // bytecode.
            // Therefore, the result of the runtime call was never pushed to the stack, and we
            // cannot pop it here.
            FrameState stateBeforeReturn = stateAfter;
            if ((stateAfter.stackSize() > 0 && stateAfter.stackAt(stateAfter.stackSize() - 1) == x) || (stateAfter.stackSize() > 1 && stateAfter.stackAt(stateAfter.stackSize() - 2) == x)) {
                stateBeforeReturn = stateAfter.duplicateModified(stateAfter.bci, stateAfter.rethrowException(), x.kind());
            }
            info = stateFor(stateBeforeReturn);
        } else {
            info = state();
        }

        emitCall(call, resultOperand, args, cc.getTemporaries(), Constant.forLong(0), info);

        if (isLegal(resultOperand)) {
            setResult(x, emitMove(resultOperand));
        }
    }

    /**
     * This method tries to create a switch implementation that is optimal for the given switch. It
     * will either generate a sequential if/then/else cascade, a set of range tests or a table
     * switch.
     * 
     * If the given switch does not contain int keys, it will always create a sequential
     * implementation.
     */
    @Override
    public void emitSwitch(SwitchNode x) {
        int keyCount = x.keyCount();
        if (keyCount == 0) {
            emitJump(getLIRBlock(x.defaultSuccessor()), null);
        } else {
            Variable value = load(operand(x.value()));
            LabelRef defaultTarget = x.defaultSuccessor() == null ? null : getLIRBlock(x.defaultSuccessor());
            if (value.getKind() != Kind.Int) {
                // hopefully only a few entries
                emitSequentialSwitch(x, value, defaultTarget);
            } else {
                assert value.getKind() == Kind.Int;
                long valueRange = x.keyAt(keyCount - 1).asLong() - x.keyAt(0).asLong() + 1;
                int switchRangeCount = switchRangeCount(x);
                if (switchRangeCount == 0) {
                    emitJump(getLIRBlock(x.defaultSuccessor()), null);
                } else if (switchRangeCount >= GraalOptions.MinimumJumpTableSize && keyCount / (double) valueRange >= GraalOptions.MinTableSwitchDensity) {
                    int minValue = x.keyAt(0).asInt();
                    assert valueRange < Integer.MAX_VALUE;
                    LabelRef[] targets = new LabelRef[(int) valueRange];
                    for (int i = 0; i < valueRange; i++) {
                        targets[i] = defaultTarget;
                    }
                    for (int i = 0; i < keyCount; i++) {
                        targets[x.keyAt(i).asInt() - minValue] = getLIRBlock(x.keySuccessor(i));
                    }
                    emitTableSwitch(minValue, defaultTarget, targets, value);
                } else if (keyCount / switchRangeCount >= GraalOptions.RangeTestsSwitchDensity) {
                    emitSwitchRanges(x, switchRangeCount, value, defaultTarget);
                } else {
                    emitSequentialSwitch(x, value, defaultTarget);
                }
            }
        }
    }

    private void emitSequentialSwitch(final SwitchNode x, Variable key, LabelRef defaultTarget) {
        int keyCount = x.keyCount();
        Integer[] indexes = Util.createSortedPermutation(keyCount, new Comparator<Integer>() {

            @Override
            public int compare(Integer o1, Integer o2) {
                return x.keyProbability(o1) < x.keyProbability(o2) ? 1 : x.keyProbability(o1) > x.keyProbability(o2) ? -1 : 0;
            }
        });
        LabelRef[] keyTargets = new LabelRef[keyCount];
        Constant[] keyConstants = new Constant[keyCount];
        for (int i = 0; i < keyCount; i++) {
            keyTargets[i] = getLIRBlock(x.keySuccessor(indexes[i]));
            keyConstants[i] = x.keyAt(indexes[i]);
        }
        emitSequentialSwitch(keyConstants, keyTargets, defaultTarget, key);
    }

    protected abstract void emitSequentialSwitch(Constant[] keyConstants, LabelRef[] keyTargets, LabelRef defaultTarget, Value key);

    protected abstract void emitSwitchRanges(int[] lowKeys, int[] highKeys, LabelRef[] targets, LabelRef defaultTarget, Value key);

    protected abstract void emitTableSwitch(int lowKey, LabelRef defaultTarget, LabelRef[] targets, Value key);

    private static int switchRangeCount(SwitchNode x) {
        int keyCount = x.keyCount();
        int switchRangeCount = 0;
        int defaultSux = x.defaultSuccessorIndex();

        int key = x.keyAt(0).asInt();
        int sux = x.keySuccessorIndex(0);
        for (int i = 0; i < keyCount; i++) {
            int newKey = x.keyAt(i).asInt();
            int newSux = x.keySuccessorIndex(i);
            if (newSux != defaultSux && (newKey != key + 1 || sux != newSux)) {
                switchRangeCount++;
            }
            key = newKey;
            sux = newSux;
        }
        return switchRangeCount;
    }

    private void emitSwitchRanges(SwitchNode x, int switchRangeCount, Variable keyValue, LabelRef defaultTarget) {
        assert switchRangeCount >= 1 : "switch ranges should not be used for emitting only the default case";

        int[] lowKeys = new int[switchRangeCount];
        int[] highKeys = new int[switchRangeCount];
        LabelRef[] targets = new LabelRef[switchRangeCount];

        int keyCount = x.keyCount();
        int defaultSuccessor = x.defaultSuccessorIndex();

        int current = -1;
        int key = -1;
        int successor = -1;
        for (int i = 0; i < keyCount; i++) {
            int newSuccessor = x.keySuccessorIndex(i);
            int newKey = x.keyAt(i).asInt();
            if (newSuccessor != defaultSuccessor) {
                if (key + 1 == newKey && successor == newSuccessor) {
                    // still in same range
                    highKeys[current] = newKey;
                } else {
                    current++;
                    lowKeys[current] = newKey;
                    highKeys[current] = newKey;
                    targets[current] = getLIRBlock(x.blockSuccessor(newSuccessor));
                }
            }
            key = newKey;
            successor = newSuccessor;
        }
        assert current == switchRangeCount - 1;
        emitSwitchRanges(lowKeys, highKeys, targets, defaultTarget, keyValue);
    }

    public FrameMap frameMap() {
        return frameMap;
    }

    public abstract void emitBitCount(Variable result, Value operand);

    public abstract void emitBitScanForward(Variable result, Value operand);

    public abstract void emitBitScanReverse(Variable result, Value operand);

    public abstract void emitMathAbs(Variable result, Variable input);

    public abstract void emitMathSqrt(Variable result, Variable input);

    public abstract void emitMathLog(Variable result, Variable input, boolean base10);

    public abstract void emitMathCos(Variable result, Variable input);

    public abstract void emitMathSin(Variable result, Variable input);

    public abstract void emitMathTan(Variable result, Variable input);

    public abstract void emitByteSwap(Variable result, Value operand);
}
