/*
 * Copyright (c) 2009, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.lir;

import static com.oracle.graal.lir.LIRInstruction.OperandFlag.*;

import java.lang.annotation.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.lir.asm.*;

/**
 * The {@code LIRInstruction} interface definition.
 */
public interface LIRInstruction {
    static final Value[] NO_OPERANDS = {};

    /**
     * Constants denoting how a LIR instruction uses an operand.
     */
    enum OperandMode {
        /**
         * The value must have been defined before. It is alive before the instruction until the
         * beginning of the instruction, but not necessarily throughout the instruction. A register
         * assigned to it can also be assigned to a {@link #TEMP} or {@link #DEF} operand. The value
         * can be used again after the instruction, so the instruction must not modify the register.
         */
        USE,

        /**
         * The value must have been defined before. It is alive before the instruction and
         * throughout the instruction. A register assigned to it cannot be assigned to a
         * {@link #TEMP} or {@link #DEF} operand. The value can be used again after the instruction,
         * so the instruction must not modify the register.
         */
        ALIVE,

        /**
         * The value must not have been defined before, and must not be used after the instruction.
         * The instruction can do whatever it wants with the register assigned to it (or not use it
         * at all).
         */
        TEMP,

        /**
         * The value must not have been defined before. The instruction has to assign a value to the
         * register. The value can (and most likely will) be used after the instruction.
         */
        DEF,
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    static @interface Use {

        OperandFlag[] value() default REG;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    static @interface Alive {

        OperandFlag[] value() default REG;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    static @interface Temp {

        OperandFlag[] value() default REG;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    static @interface Def {

        OperandFlag[] value() default REG;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    static @interface State {
    }

    /**
     * Flags for an operand.
     */
    enum OperandFlag {
        /**
         * The value can be a {@link RegisterValue}.
         */
        REG,

        /**
         * The value can be a {@link StackSlot}.
         */
        STACK,

        /**
         * The value can be a {@link CompositeValue}.
         */
        COMPOSITE,

        /**
         * The value can be a {@link Constant}.
         */
        CONST,

        /**
         * The value can be {@link Value#ILLEGAL}.
         */
        ILLEGAL,

        /**
         * The register allocator should try to assign a certain register to improve code quality.
         * Use {@link LIRInstruction#forEachRegisterHint} to access the register hints.
         */
        HINT,

        /**
         * The value can be uninitialized, e.g., a stack slot that has not written to before. This
         * is only used to avoid false positives in verification code.
         */
        UNINITIALIZED,
    }

    void emitCode(CompilationResultBuilder crb);

    int id();

    void setId(int id);

    /**
     * Gets the instruction name.
     */
    String name();

    boolean hasOperands();

    boolean hasState();

    /**
     * Determines if this instruction destroys all caller-saved registers..
     */
    boolean destroysCallerSavedRegisters();

    // ValuePositionProcedures
    void forEachInputPos(ValuePositionProcedure proc);

    void forEachAlivePos(ValuePositionProcedure proc);

    void forEachTempPos(ValuePositionProcedure proc);

    void forEachOutputPos(ValuePositionProcedure proc);

    // InstructionValueProcedures
    void forEachInput(InstructionValueProcedure proc);

    void forEachAlive(InstructionValueProcedure proc);

    void forEachTemp(InstructionValueProcedure proc);

    void forEachOutput(InstructionValueProcedure proc);

    void forEachState(InstructionValueProcedure proc);

    // ValueProcedures
    void forEachInput(ValueProcedure proc);

    void forEachAlive(ValueProcedure proc);

    void forEachTemp(ValueProcedure proc);

    void forEachOutput(ValueProcedure proc);

    void forEachState(ValueProcedure proc);

    // States
    void forEachState(InstructionStateProcedure proc);

    void forEachState(StateProcedure proc);

    // InstructionValueConsumers
    void visitEachInput(InstructionValueConsumer proc);

    void visitEachAlive(InstructionValueConsumer proc);

    void visitEachTemp(InstructionValueConsumer proc);

    void visitEachOutput(InstructionValueConsumer proc);

    void visitEachState(InstructionValueConsumer proc);

    // ValueConsumers
    void visitEachInput(ValueConsumer proc);

    void visitEachAlive(ValueConsumer proc);

    void visitEachTemp(ValueConsumer proc);

    void visitEachOutput(ValueConsumer proc);

    void visitEachState(ValueConsumer proc);

    /**
     * Iterates all register hints for the specified value, i.e., all preferred candidates for the
     * register to be assigned to the value.
     * <p>
     * Subclasses can override this method. The default implementation processes all Input operands
     * as the hints for an Output operand, and all Output operands as the hints for an Input
     * operand.
     *
     * @param value The value the hints are needed for.
     * @param mode The operand mode of the value.
     * @param proc The procedure invoked for all the hints. If the procedure returns a non-null
     *            value, the iteration is stopped and the value is returned by this method, i.e.,
     *            clients can stop the iteration once a suitable hint has been found.
     * @return The non-null value returned by the procedure, or null.
     */
    Value forEachRegisterHint(Value value, OperandMode mode, InstructionValueProcedure proc);

    /**
     * @see #forEachRegisterHint(Value, OperandMode, InstructionValueProcedure)
     * @param value The value the hints are needed for.
     * @param mode The operand mode of the value.
     * @param proc The procedure invoked for all the hints. If the procedure returns a non-null
     *            value, the iteration is stopped and the value is returned by this method, i.e.,
     *            clients can stop the iteration once a suitable hint has been found.
     * @return The non-null value returned by the procedure, or null.
     */
    Value forEachRegisterHint(Value value, OperandMode mode, ValueProcedure proc);

    String toStringWithIdPrefix();

    void verify();

    LIRInstructionClass getLIRInstructionClass();
}
