/*
 * Copyright (c) 2011, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.graal.alloc.simple;

import static com.oracle.max.cri.ci.CiValueUtil.*;
import static com.oracle.max.graal.alloc.util.LocationUtil.*;

import java.util.*;

import com.oracle.max.cri.ci.*;
import com.oracle.max.cri.ri.*;
import com.oracle.max.graal.compiler.alloc.*;
import com.oracle.max.graal.compiler.cfg.*;
import com.oracle.max.graal.compiler.lir.*;
import com.oracle.max.graal.compiler.lir.LIRInstruction.ValueProcedure;
import com.oracle.max.graal.debug.*;

public class DataFlowAnalysis {
    private final LIR lir;
    private final RiRegisterConfig registerConfig;

    public DataFlowAnalysis(LIR lir, RiRegisterConfig registerConfig) {
        this.lir = lir;
        this.registerConfig = registerConfig;
    }

    public void execute() {
        numberInstructions();
        backwardDataFlow();
    }


    private List<Block> blocks() {
        return lir.linearScanOrder();
    }

    private int numVariables() {
        return lir.numVariables();
    }

    private boolean isAllocatableRegister(CiValue value) {
        return isRegister(value) && registerConfig.getAttributesMap()[asRegister(value).number].isAllocatable;
    }


    private int[] definitions;
    private BitSet[] blockLiveIn;
    private Block[] opIdBlock;
    private Object[] opIdKilledValues;


    public BitSet liveIn(Block block) {
        return blockLiveIn[block.getId()];
    }
    private void setLiveIn(Block block, BitSet liveIn) {
        blockLiveIn[block.getId()] = liveIn;
    }

    private Block blockOf(int opId) {
        return opIdBlock[opId >> 1];
    }
    private void setBlockOf(int opId, Block block) {
        opIdBlock[opId >> 1] = block;
    }

    private Object killedValues(int opId) {
        return opIdKilledValues[opId];
    }
    private void setKilledValues(int opId, Object killedValues) {
        opIdKilledValues[opId] = killedValues;
    }

    public void forEachKilled(LIRInstruction op, boolean end, ValueProcedure proc) {
        Object entry = killedValues(op.id() + (end ? 1 : 0));
        if (entry == null) {
            // Nothing to do
        } else if (entry instanceof CiValue) {
            CiValue newValue = proc.doValue((CiValue) entry, null, null);
            assert newValue == entry : "procedure does not allow to change values";
        } else {
            CiValue[] values = (CiValue[]) entry;
            for (int i = 0; i < values.length; i++) {
                if (values[i] != null) {
                    CiValue newValue = proc.doValue(values[i], null, null);
                    assert newValue == values[i] : "procedure does not allow to change values";
                }
            }
        }
    }

    public int definition(Variable value) {
        return definitions[value.index];
    }

    /**
     * Numbers all instructions in all blocks. The numbering follows the {@linkplain ComputeLinearScanOrder linear scan order}.
     */
    private void numberInstructions() {
        ValueProcedure defProc = new ValueProcedure() { @Override public CiValue doValue(CiValue value) { return setDef(value); } };

        int numInstructions = 0;
        for (Block block : blocks()) {
            numInstructions += block.lir.size();
        }
        opIdBlock = new Block[numInstructions];
        opIdKilledValues = new Object[numInstructions << 1];
        definitions = new int[numVariables()];

        curOpId = 0;
        for (Block block : blocks()) {
            for (LIRInstruction op : block.lir) {
                op.setId(curOpId);
                setBlockOf(curOpId, block);

                op.forEachTemp(defProc);
                op.forEachOutput(defProc);

                curOpId += 2; // numbering of lirOps by two
            }
        }
        assert curOpId == numInstructions << 1;
    }

    private CiValue setDef(CiValue value) {
        if (isVariable(value)) {
            assert definitions[asVariable(value).index] == 0 : "Variable defined twice";
            definitions[asVariable(value).index] = curOpId;
        }
        return value;
    }


    private BitSet variableLive;
    private BitSet registerLive;
    private int curOpId;

    private void backwardDataFlow() {
        ValueProcedure inputProc =    new ValueProcedure() { @Override public CiValue doValue(CiValue value) { return use(value, curOpId); } };
        ValueProcedure aliveProc =    new ValueProcedure() { @Override public CiValue doValue(CiValue value) { return use(value, curOpId + 1); } };
        ValueProcedure tempProc =     new ValueProcedure() { @Override public CiValue doValue(CiValue value) { return def(value, true); } };
        ValueProcedure outputProc =   new ValueProcedure() { @Override public CiValue doValue(CiValue value) { return def(value, false); } };

        blockLiveIn = new BitSet[blocks().size()];
        registerLive = new BitSet();

        Debug.log("==== start backward data flow analysis ====");
        for (int i = blocks().size() - 1; i >= 0; i--) {
            Block block = blocks().get(i);
            Debug.log("start block %s  loop %s", block, block.getLoop());

            variableLive = new BitSet();
            for (Block sux : block.getSuccessors()) {
                BitSet suxLive = liveIn(sux);
                if (suxLive != null) {
                    Debug.log("  sux %s  suxLive: %s", sux, suxLive);
                    variableLive.or(suxLive);
                }
            }

            assert registerLive.isEmpty() : "no fixed register must be alive before processing a block";

            for (int j = block.lir.size() - 1; j >= 0; j--) {
                LIRInstruction op = block.lir.get(j);
                curOpId = op.id();
                Debug.log("  op %d %s  variableLive: %s  registerLive: %s", curOpId, op, variableLive, registerLive);

                op.forEachOutput(outputProc);
                op.forEachTemp(tempProc);
                op.forEachState(aliveProc);
                op.forEachAlive(aliveProc);
                op.forEachInput(inputProc);
            }

            assert registerLive.isEmpty() : "no fixed register must be alive after processing a block";
            assert liveIn(block) == null;
            setLiveIn(block, variableLive);

            if (block.isLoopHeader()) {
                Debug.log("  loop header, propagating live set to loop blocks  variableLive: %s", variableLive);
                // All variables that are live at the beginning of a loop are also live the whole loop.
                // This is guaranteed by the SSA form.
                for (Block loop : block.getLoop().blocks) {
                    BitSet loopLiveIn = liveIn(loop);
                    assert loopLiveIn != null : "All loop blocks must have been processed before the loop header";
                    loopLiveIn.or(variableLive);
                    Debug.log("    block %s  loopLiveIn %s", loop, loopLiveIn);
                }
            }

            Debug.log("end block %s  variableLive: %s", block, variableLive);
        }
        Debug.log("==== end backward data flow analysis ====");
    }

    private CiValue use(CiValue value, int killOpId) {
        Debug.log("    use %s", value);
        if (isVariable(value)) {
            int variableIdx = asVariable(value).index;
            assert definitions[variableIdx] < curOpId;
            if (!variableLive.get(variableIdx)) {
                Debug.log("      set live variable %d", variableIdx);
                variableLive.set(variableIdx);
                kill(value, killOpId);
            }

        } else if (isAllocatableRegister(value)) {
            int regNum = asRegister(value).number;
            if (!registerLive.get(regNum)) {
                Debug.log("      set live register %d", regNum);
                registerLive.set(regNum);
                kill(value, killOpId);
            }
        }
        return value;
    }

    private CiValue def(CiValue value, boolean isTemp) {
        Debug.log("    def %s", value);
        if (isVariable(value)) {
            int variableIdx = asVariable(value).index;
            assert definitions[variableIdx] == curOpId;
            if (variableLive.get(variableIdx)) {
                Debug.log("      clear live variable %d", variableIdx);
                assert !isTemp : "temp variable cannot be used after the operation";
                variableLive.clear(variableIdx);
            } else {
                // Variable has never been used, so kill it immediately after the definition.
                kill(value, curOpId + 1);
            }

        } else if (isAllocatableRegister(value)) {
            int regNum = asRegister(value).number;
            if (registerLive.get(regNum)) {
                Debug.log("      clear live register %d", regNum);
                assert !isTemp : "temp variable cannot be used after the operation";
                registerLive.clear(regNum);
            } else {
                // Register has never been used, so kill it immediately after the definition.
                kill(value, curOpId + 1);
            }
        }
        return value;
    }

    private void kill(CiValue value, int opId) {
        if (opId < 0) {
            return;
        }
        if (isVariable(value)) {
            int defOpId = definitions[asVariable(value).index];
            assert defOpId > 0 && defOpId <= opId;

            Block defBlock = blockOf(defOpId);
            Block useBlock = blockOf(opId);

            if (useBlock.getLoop() != null && useBlock.getLoop() != defBlock.getLoop()) {
                // This is a value defined outside of the loop it is currently used in.  Therefore, it is live the whole loop
                // and is not killed by the current instruction.
                Debug.log("      no kill because use in %s, definition in %s", useBlock.getLoop(), defBlock.getLoop());
                return;
            }
        }
        Debug.log("      kill %s at %d", value, opId);

        Object entry = killedValues(opId);
        if (entry == null) {
            setKilledValues(opId, value);
        } else if (entry instanceof CiValue) {
            setKilledValues(opId, new CiValue[] {(CiValue) entry, value});
        } else {
            CiValue[] killed = (CiValue[]) entry;
            for (int i = 0; i < killed.length; i++) {
                if (killed[i] == null) {
                    killed[i] = value;
                    return;
                }
            }
            int oldLen = killed.length;
            killed = Arrays.copyOf(killed, oldLen * 2);
            setKilledValues(opId, killed);
            killed[oldLen] = value;
        }
    }
}
