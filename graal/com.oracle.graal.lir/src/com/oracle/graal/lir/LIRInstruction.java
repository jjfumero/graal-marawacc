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
package com.oracle.graal.lir;

import static com.oracle.graal.lir.LIRInstruction.OperandFlag.*;
import static com.oracle.graal.lir.LIRInstruction.OperandMode.*;

import java.lang.annotation.*;
import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.lir.asm.*;

/**
 * The {@code LIRInstruction} class definition.
 */
public abstract class LIRInstruction {

    public static final Value[] NO_OPERANDS = {};

    /**
     * Iterator for iterating over a list of values. Subclasses must overwrite one of the doValue
     * methods. Clients of the class must only call the doValue method that takes additional
     * parameters.
     */
    public abstract static class ValueProcedure {

        /**
         * Iterator method to be overwritten. This version of the iterator does not take additional
         * parameters to keep the signature short.
         * 
         * @param value The value that is iterated.
         * @return The new value to replace the value that was passed in.
         */
        protected Value doValue(Value value) {
            throw GraalInternalError.shouldNotReachHere("One of the doValue() methods must be overwritten");
        }

        /**
         * Iterator method to be overwritten. This version of the iterator gets additional
         * parameters about the processed value.
         * 
         * @param value The value that is iterated.
         * @param mode The operand mode for the value.
         * @param flags A set of flags for the value.
         * @return The new value to replace the value that was passed in.
         */
        public Value doValue(Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            return doValue(value);
        }
    }

    public abstract static class StateProcedure {

        protected abstract void doState(LIRFrameState state);
    }

    /**
     * Constants denoting how a LIR instruction uses an operand.
     */
    public enum OperandMode {
        /**
         * The value must have been defined before. It is alive before the instruction until the
         * beginning of the instruction, but not necessarily throughout the instruction. A register
         * assigned to it can also be assigend to a Temp or Output operand. The value can be used
         * again after the instruction, so the instruction must not modify the register.
         */
        USE,

        /**
         * The value must have been defined before. It is alive before the instruction and
         * throughout the instruction. A register assigned to it cannot be assigned to a Temp or
         * Output operand. The value can be used again after the instruction, so the instruction
         * must not modify the register.
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
    public static @interface Use {

        OperandFlag[] value() default REG;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public static @interface Alive {

        OperandFlag[] value() default REG;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public static @interface Temp {

        OperandFlag[] value() default REG;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public static @interface Def {

        OperandFlag[] value() default REG;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public static @interface State {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD})
    public static @interface Opcode {

        String value() default "";
    }

    /**
     * Flags for an operand.
     */
    public enum OperandFlag {
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
         * The value can be {@link AllocatableValue#UNUSED}.
         */
        UNUSED,

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

    /**
     * For validity checking of the operand flags defined by instruction subclasses.
     */
    protected static final EnumMap<OperandMode, EnumSet<OperandFlag>> ALLOWED_FLAGS;

    static {
        ALLOWED_FLAGS = new EnumMap<>(OperandMode.class);
        ALLOWED_FLAGS.put(USE, EnumSet.of(REG, STACK, COMPOSITE, CONST, ILLEGAL, HINT, UNUSED, UNINITIALIZED));
        ALLOWED_FLAGS.put(ALIVE, EnumSet.of(REG, STACK, COMPOSITE, CONST, ILLEGAL, HINT, UNUSED, UNINITIALIZED));
        ALLOWED_FLAGS.put(TEMP, EnumSet.of(REG, COMPOSITE, CONST, ILLEGAL, UNUSED, HINT));
        ALLOWED_FLAGS.put(DEF, EnumSet.of(REG, STACK, COMPOSITE, ILLEGAL, UNUSED, HINT));
    }

    /**
     * The flags of the base and index value of an address.
     */
    protected static final EnumSet<OperandFlag> ADDRESS_FLAGS = EnumSet.of(REG, ILLEGAL);

    private final LIRInstructionClass instructionClass;

    /**
     * Instruction id for register allocation.
     */
    private int id;

    /**
     * Constructs a new LIR instruction.
     */
    public LIRInstruction() {
        instructionClass = LIRInstructionClass.get(getClass());
        id = -1;
    }

    public abstract void emitCode(TargetMethodAssembler tasm);

    public final int id() {
        return id;
    }

    public final void setId(int id) {
        this.id = id;
    }

    /**
     * Gets the instruction name.
     */
    public final String name() {
        return instructionClass.getOpcode(this);
    }

    public final boolean hasOperands() {
        return instructionClass.hasOperands() || hasState() || hasCall();
    }

    public final boolean hasState() {
        return instructionClass.hasState(this);
    }

    /**
     * Returns true when this instruction is a call instruction that destroys all caller-saved
     * registers.
     */
    public final boolean hasCall() {
        return this instanceof StandardOp.CallOp;
    }

    public final void forEachInput(ValueProcedure proc) {
        instructionClass.forEachUse(this, proc);
    }

    public final void forEachAlive(ValueProcedure proc) {
        instructionClass.forEachAlive(this, proc);
    }

    public final void forEachTemp(ValueProcedure proc) {
        instructionClass.forEachTemp(this, proc);
    }

    public final void forEachOutput(ValueProcedure proc) {
        instructionClass.forEachDef(this, proc);
    }

    public final void forEachState(ValueProcedure proc) {
        instructionClass.forEachState(this, proc);
    }

    public final void forEachState(StateProcedure proc) {
        instructionClass.forEachState(this, proc);
    }

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
    public Value forEachRegisterHint(Value value, OperandMode mode, ValueProcedure proc) {
        return instructionClass.forEachRegisterHint(this, mode, proc);
    }

    protected void verify() {
    }

    public final String toStringWithIdPrefix() {
        if (id != -1) {
            return String.format("%4d %s", id, toString());
        }
        return "     " + toString();
    }

    @Override
    public String toString() {
        return instructionClass.toString(this);
    }
}
