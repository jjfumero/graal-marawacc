/*
 * Copyright (c) 2014, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.lir.constopt;

import static com.oracle.graal.api.code.ValueUtil.*;
import static com.oracle.graal.lir.LIRValueUtil.*;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.cfg.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.LIRInstruction.OperandFlag;
import com.oracle.graal.lir.LIRInstruction.OperandMode;
import com.oracle.graal.lir.StandardOp.MoveOp;
import com.oracle.graal.lir.constopt.ConstantTree.Flags;
import com.oracle.graal.lir.constopt.ConstantTree.NodeCost;
import com.oracle.graal.lir.gen.*;
import com.oracle.graal.lir.phases.*;
import com.oracle.graal.options.*;

/**
 * This optimization tries to improve the handling of constants by replacing a single definition of
 * a constant, which is potentially scheduled into a block with high probability, with one or more
 * definitions in blocks with a lower probability.
 */
public final class ConstantLoadOptimization extends PreAllocationOptimizationPhase {

    public static class Options {
        // @formatter:off
        @Option(help = "Enable constant load optimization.", type = OptionType.Debug)
        public static final OptionValue<Boolean> LIROptConstantLoadOptimization = new OptionValue<>(true);
        // @formatter:on
    }

    @Override
    protected <B extends AbstractBlockBase<B>> void run(TargetDescription target, LIRGenerationResult lirGenRes, List<B> codeEmittingOrder, List<B> linearScanOrder, LIRGeneratorTool lirGen) {
        new Optimization(lirGenRes.getLIR(), lirGen).apply();
    }

    private static final DebugMetric constantsTotal = Debug.metric("ConstantLoadOptimization[total]");
    private static final DebugMetric phiConstantsSkipped = Debug.metric("ConstantLoadOptimization[PhisSkipped]");
    private static final DebugMetric singleUsageConstantsSkipped = Debug.metric("ConstantLoadOptimization[SingleUsageSkipped]");
    private static final DebugMetric usageAtDefinitionSkipped = Debug.metric("ConstantLoadOptimization[UsageAtDefinitionSkipped]");
    private static final DebugMetric materializeAtDefinitionSkipped = Debug.metric("ConstantLoadOptimization[MaterializeAtDefinitionSkipped]");
    private static final DebugMetric constantsOptimized = Debug.metric("ConstantLoadOptimization[optimized]");

    private static final class Optimization {
        private final LIR lir;
        private final LIRGeneratorTool lirGen;
        private final VariableMap<DefUseTree> map;
        private final BitSet phiConstants;
        private final BitSet defined;
        private final BlockMap<List<UseEntry>> blockMap;
        private final BlockMap<LIRInsertionBuffer> insertionBuffers;

        private Optimization(LIR lir, LIRGeneratorTool lirGen) {
            this.lir = lir;
            this.lirGen = lirGen;
            this.map = new VariableMap<>();
            this.phiConstants = new BitSet();
            this.defined = new BitSet();
            this.insertionBuffers = new BlockMap<>(lir.getControlFlowGraph());
            this.blockMap = new BlockMap<>(lir.getControlFlowGraph());
        }

        private void apply() {
            try (Indent indent = Debug.logAndIndent("ConstantLoadOptimization")) {
                try (Scope s = Debug.scope("BuildDefUseTree")) {
                    // build DefUseTree
                    lir.getControlFlowGraph().getBlocks().forEach(this::analyzeBlock);
                    // remove all with only one use
                    map.filter(t -> {
                        if (t.usageCount() > 1) {
                            return true;
                        } else {
                            singleUsageConstantsSkipped.increment();
                            return false;
                        }
                    });
                    // collect block map
                    map.forEach(tree -> tree.forEach(this::addUsageToBlockMap));
                } catch (Throwable e) {
                    throw Debug.handle(e);
                }

                try (Scope s = Debug.scope("BuildConstantTree")) {
                    // create ConstantTree
                    map.forEach(this::createConstantTree);

                    // insert moves, delete null instructions and reset instruction ids
                    lir.getControlFlowGraph().getBlocks().forEach(this::rewriteBlock);

                    assert verifyStates();
                } catch (Throwable e) {
                    throw Debug.handle(e);
                }
            }
        }

        private boolean verifyStates() {
            map.forEach(this::verifyStateUsage);
            return true;
        }

        private void verifyStateUsage(DefUseTree tree) {
            Variable var = tree.getVariable();
            ValueConsumer stateConsumer = new ValueConsumer() {

                @Override
                public void visitValue(Value operand, OperandMode mode, EnumSet<OperandFlag> flags) {
                    assert !operand.equals(var) : "constant usage through variable in frame state " + var;
                }
            };
            for (AbstractBlockBase<?> block : lir.getControlFlowGraph().getBlocks()) {
                for (LIRInstruction inst : lir.getLIRforBlock(block)) {
                    // set instruction id to the index in the lir instruction list
                    inst.visitEachState(stateConsumer);
                }
            }
        }

        private static boolean isConstantLoad(LIRInstruction inst) {
            if (!(inst instanceof MoveOp)) {
                return false;
            }
            MoveOp move = (MoveOp) inst;
            return isConstant(move.getInput()) && isVariable(move.getResult());
        }

        private void addUsageToBlockMap(UseEntry entry) {
            AbstractBlockBase<?> block = entry.getBlock();
            List<UseEntry> list = blockMap.get(block);
            if (list == null) {
                list = new ArrayList<>();
                blockMap.put(block, list);
            }
            list.add(entry);
        }

        /**
         * Collects def-use information for a {@code block}.
         */
        private void analyzeBlock(AbstractBlockBase<?> block) {
            try (Indent indent = Debug.logAndIndent("Block: %s", block)) {

                InstructionValueConsumer loadConsumer = (instruction, value, mode, flags) -> {
                    if (isVariable(value)) {
                        Variable var = (Variable) value;

                        if (!phiConstants.get(var.index)) {
                            if (!defined.get(var.index)) {
                                defined.set(var.index);
                                if (isConstantLoad(instruction)) {
                                    Debug.log("constant load: %s", instruction);
                                    map.put(var, new DefUseTree(instruction, block));
                                    constantsTotal.increment();
                                }
                            } else {
                                // Variable is redefined, this only happens for constant loads
                                // introduced by phi resolution -> ignore.
                                DefUseTree removed = map.remove(var);
                                if (removed != null) {
                                    phiConstantsSkipped.increment();
                                }
                                phiConstants.set(var.index);
                                Debug.log(3, "Removing phi variable: %s", var);
                            }
                        } else {
                            assert defined.get(var.index) : "phi but not defined? " + var;
                        }
                    }
                };

                ValuePositionProcedure useProcedure = (instruction, position) -> {
                    Value value = position.get(instruction);
                    if (isVariable(value)) {
                        Variable var = (Variable) value;
                        if (!phiConstants.get(var.index)) {
                            DefUseTree tree = map.get(var);
                            if (tree != null) {
                                tree.addUsage(block, instruction, position);
                                Debug.log("usage of %s : %s", var, instruction);
                            }
                        }
                    }
                };

                int opId = 0;
                for (LIRInstruction inst : lir.getLIRforBlock(block)) {
                    // set instruction id to the index in the lir instruction list
                    inst.setId(opId++);
                    inst.visitEachOutput(loadConsumer);
                    inst.forEachInputPos(useProcedure);
                    inst.forEachAlivePos(useProcedure);

                }
            }
        }

        /**
         * Creates the dominator tree and searches for an solution.
         */
        private void createConstantTree(DefUseTree tree) {
            ConstantTree constTree = new ConstantTree(lir.getControlFlowGraph(), tree);
            constTree.set(Flags.SUBTREE, tree.getBlock());
            tree.forEach(u -> constTree.set(Flags.USAGE, u.getBlock()));

            if (constTree.get(Flags.USAGE, tree.getBlock())) {
                // usage in the definition block -> no optimization
                usageAtDefinitionSkipped.increment();
                return;
            }

            constTree.markBlocks();

            NodeCost cost = ConstantTreeAnalyzer.analyze(constTree, tree.getBlock());
            int usageCount = cost.getUsages().size();
            assert usageCount == tree.usageCount() : "Usage count differs: " + usageCount + " vs. " + tree.usageCount();

            if (Debug.isLogEnabled()) {
                try (Indent i = Debug.logAndIndent("Variable: %s, Block: %s, prob.: %f", tree.getVariable(), tree.getBlock(), tree.getBlock().probability())) {
                    Debug.log("Usages result: %s", cost);
                }

            }

            if (cost.getNumMaterializations() > 1 || cost.getBestCost() < tree.getBlock().probability()) {
                try (Scope s = Debug.scope("CLOmodify", constTree); Indent i = Debug.logAndIndent("Replacing %s = %s", tree.getVariable(), tree.getConstant().toValueString())) {
                    // mark original load for removal
                    deleteInstruction(tree);
                    constantsOptimized.increment();

                    // collect result
                    createLoads(tree, constTree, tree.getBlock());

                } catch (Throwable e) {
                    throw Debug.handle(e);
                }
            } else {
                // no better solution found
                materializeAtDefinitionSkipped.increment();
            }
            Debug.dump(constTree, "ConstantTree for " + tree.getVariable());
        }

        private void createLoads(DefUseTree tree, ConstantTree constTree, AbstractBlockBase<?> startBlock) {
            Deque<AbstractBlockBase<?>> worklist = new ArrayDeque<>();
            worklist.add(startBlock);
            while (!worklist.isEmpty()) {
                AbstractBlockBase<?> block = worklist.pollLast();
                if (constTree.get(Flags.CANDIDATE, block)) {
                    constTree.set(Flags.MATERIALIZE, block);
                    // create and insert load
                    insertLoad(tree.getConstant(), tree.getVariable().getLIRKind(), block, constTree.getCost(block).getUsages());
                } else {
                    for (AbstractBlockBase<?> dominated : block.getDominated()) {
                        if (constTree.isMarked(dominated)) {
                            worklist.addLast(dominated);
                        }
                    }
                }
            }
        }

        private void insertLoad(JavaConstant constant, LIRKind kind, AbstractBlockBase<?> block, List<UseEntry> usages) {
            assert usages != null && usages.size() > 0 : String.format("No usages %s %s %s", constant, block, usages);
            // create variable
            Variable variable = lirGen.newVariable(kind);
            // create move
            LIRInstruction move = lirGen.getSpillMoveFactory().createMove(variable, constant);
            // insert instruction
            getInsertionBuffer(block).append(1, move);
            Debug.log("new move (%s) and inserted in block %s", move, block);
            // update usages
            for (UseEntry u : usages) {
                u.setValue(variable);
                Debug.log("patched instruction %s", u.getInstruction());
            }
        }

        /**
         * Inserts the constant loads created in {@link #createConstantTree} and deletes the
         * original definition.
         */
        private void rewriteBlock(AbstractBlockBase<?> block) {
            // insert moves
            LIRInsertionBuffer buffer = insertionBuffers.get(block);
            if (buffer != null) {
                assert buffer.initialized() : "not initialized?";
                buffer.finish();
            }

            // delete instructions
            List<LIRInstruction> instructions = lir.getLIRforBlock(block);
            boolean hasDead = false;
            for (LIRInstruction inst : instructions) {
                if (inst == null) {
                    hasDead = true;
                } else {
                    inst.setId(-1);
                }
            }
            if (hasDead) {
                // Remove null values from the list.
                instructions.removeAll(Collections.singleton(null));
            }
        }

        private void deleteInstruction(DefUseTree tree) {
            AbstractBlockBase<?> block = tree.getBlock();
            LIRInstruction instruction = tree.getInstruction();
            Debug.log("deleting instruction %s from block %s", instruction, block);
            lir.getLIRforBlock(block).set(instruction.id(), null);
        }

        private LIRInsertionBuffer getInsertionBuffer(AbstractBlockBase<?> block) {
            LIRInsertionBuffer insertionBuffer = insertionBuffers.get(block);
            if (insertionBuffer == null) {
                insertionBuffer = new LIRInsertionBuffer();
                insertionBuffers.put(block, insertionBuffer);
                assert !insertionBuffer.initialized() : "already initialized?";
                List<LIRInstruction> instructions = lir.getLIRforBlock(block);
                insertionBuffer.init(instructions);
            }
            return insertionBuffer;
        }
    }
}
