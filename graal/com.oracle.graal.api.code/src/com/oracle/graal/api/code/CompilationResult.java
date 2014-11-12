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
package com.oracle.graal.api.code;

import static java.util.Collections.*;

import java.io.*;
import java.util.*;

import com.oracle.graal.api.code.CodeUtil.RefMapFormatter;
import com.oracle.graal.api.meta.*;

/**
 * Represents the output from compiling a method, including the compiled machine code, associated
 * data and references, relocation information, deoptimization information, etc.
 */
public class CompilationResult implements Serializable {

    private static final long serialVersionUID = -1319947729753702434L;

    /**
     * Represents a code position with associated additional information.
     */
    public abstract static class Site implements Serializable {

        private static final long serialVersionUID = -8214214947651979102L;
        /**
         * The position (or offset) of this site with respect to the start of the target method.
         */
        public final int pcOffset;

        public Site(int pos) {
            this.pcOffset = pos;
        }

        @Override
        public final int hashCode() {
            throw new UnsupportedOperationException("hashCode");
        }

        @Override
        public abstract boolean equals(Object obj);
    }

    /**
     * Represents an infopoint with associated debug info. Note that safepoints are also infopoints.
     */
    public static class Infopoint extends Site implements Comparable<Infopoint> {

        private static final long serialVersionUID = 2479806696381720162L;
        public final DebugInfo debugInfo;

        public final InfopointReason reason;

        public Infopoint(int pcOffset, DebugInfo debugInfo, InfopointReason reason) {
            super(pcOffset);
            this.debugInfo = debugInfo;
            this.reason = reason;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(pcOffset);
            sb.append("[<infopoint>]");
            appendDebugInfo(sb, debugInfo);
            return sb.toString();
        }

        @Override
        public int compareTo(Infopoint o) {
            if (pcOffset < o.pcOffset) {
                return -1;
            } else if (pcOffset > o.pcOffset) {
                return 1;
            }
            return this.reason.compareTo(o.reason);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj != null && obj.getClass() == getClass()) {
                Infopoint that = (Infopoint) obj;
                if (this.pcOffset == that.pcOffset && Objects.equals(this.debugInfo, that.debugInfo) && Objects.equals(this.reason, that.reason)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Represents a call in the code.
     */
    public static final class Call extends Infopoint {

        private static final long serialVersionUID = 1440741241631046954L;

        /**
         * The target of the call.
         */
        public final InvokeTarget target;

        /**
         * The size of the call instruction.
         */
        public final int size;

        /**
         * Specifies if this call is direct or indirect. A direct call has an immediate operand
         * encoding the absolute or relative (to the call itself) address of the target. An indirect
         * call has a register or memory operand specifying the target address of the call.
         */
        public final boolean direct;

        public Call(InvokeTarget target, int pcOffset, int size, boolean direct, DebugInfo debugInfo) {
            super(pcOffset, debugInfo, InfopointReason.CALL);
            this.size = size;
            this.target = target;
            this.direct = direct;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof Call && super.equals(obj)) {
                Call that = (Call) obj;
                if (this.size == that.size && this.direct == that.direct && Objects.equals(this.target, that.target)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(pcOffset);
            sb.append('[');
            sb.append(target);
            sb.append(']');

            if (debugInfo != null) {
                appendDebugInfo(sb, debugInfo);
            }

            return sb.toString();
        }
    }

    /**
     * Represents some external data that is referenced by the code.
     */
    public abstract static class Reference implements Serializable {

        private static final long serialVersionUID = 4841246083028477946L;

        @Override
        public final int hashCode() {
            throw new UnsupportedOperationException("hashCode");
        }

        @Override
        public abstract boolean equals(Object obj);
    }

    public static final class ConstantReference extends Reference {

        private static final long serialVersionUID = 5841121930949053612L;

        private final VMConstant constant;

        public ConstantReference(VMConstant constant) {
            this.constant = constant;
        }

        public VMConstant getConstant() {
            return constant;
        }

        @Override
        public String toString() {
            return constant.toString();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof ConstantReference) {
                ConstantReference that = (ConstantReference) obj;
                return Objects.equals(this.constant, that.constant);
            }
            return false;
        }
    }

    public static final class DataSectionReference extends Reference {

        private static final long serialVersionUID = 9011681879878139182L;

        private int offset;

        public DataSectionReference() {
            // will be set after the data section layout is fixed
            offset = 0xDEADDEAD;
        }

        public int getOffset() {
            return offset;
        }

        public void setOffset(int offset) {
            this.offset = offset;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof DataSectionReference) {
                DataSectionReference that = (DataSectionReference) obj;
                return this.offset == that.offset;
            }
            return false;
        }
    }

    /**
     * Represents a code site that references some data. The associated data can be either a
     * {@link DataSectionReference reference} to the data section, or it may be an inlined
     * {@link JavaConstant} that needs to be patched.
     */
    public static final class DataPatch extends Site {

        private static final long serialVersionUID = 5771730331604867476L;
        public Reference reference;

        public DataPatch(int pcOffset, Reference reference) {
            super(pcOffset);
            this.reference = reference;
        }

        @Override
        public String toString() {
            return String.format("%d[<data patch referring to %s>]", pcOffset, reference.toString());
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof DataPatch) {
                DataPatch that = (DataPatch) obj;
                if (this.pcOffset == that.pcOffset && Objects.equals(this.reference, that.reference)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Provides extra information about instructions or data at specific positions in
     * {@link CompilationResult#getTargetCode()}. This is optional information that can be used to
     * enhance a disassembly of the code.
     */
    public abstract static class CodeAnnotation implements Serializable {

        private static final long serialVersionUID = -7903959680749520748L;
        public final int position;

        public CodeAnnotation(int position) {
            this.position = position;
        }

        @Override
        public final int hashCode() {
            throw new UnsupportedOperationException("hashCode");
        }

        @Override
        public abstract boolean equals(Object obj);
    }

    /**
     * A string comment about one or more instructions at a specific position in the code.
     */
    public static final class CodeComment extends CodeAnnotation {

        /**
         *
         */
        private static final long serialVersionUID = 6802287188701961401L;
        public final String value;

        public CodeComment(int position, String comment) {
            super(position);
            this.value = comment;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof CodeComment) {
                CodeComment that = (CodeComment) obj;
                if (this.position == that.position && this.value.equals(that.value)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "@" + position + ": " + value;
        }
    }

    /**
     * Describes a table of signed offsets embedded in the code. The offsets are relative to the
     * starting address of the table. This type of table maybe generated when translating a
     * multi-way branch based on a key value from a dense value set (e.g. the {@code tableswitch}
     * JVM instruction).
     *
     * The table is indexed by the contiguous range of integers from {@link #low} to {@link #high}
     * inclusive.
     */
    public static final class JumpTable extends CodeAnnotation {

        private static final long serialVersionUID = 2222194398353801831L;

        /**
         * The low value in the key range (inclusive).
         */
        public final int low;

        /**
         * The high value in the key range (inclusive).
         */
        public final int high;

        /**
         * The size (in bytes) of each table entry.
         */
        public final int entrySize;

        public JumpTable(int position, int low, int high, int entrySize) {
            super(position);
            this.low = low;
            this.high = high;
            this.entrySize = entrySize;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof JumpTable) {
                JumpTable that = (JumpTable) obj;
                if (this.position == that.position && this.entrySize == that.entrySize && this.low == that.low && this.high == that.high) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "@" + position + ": [" + low + " .. " + high + "]";
        }
    }

    /**
     * Represents exception handler information for a specific code position. It includes the catch
     * code position as well as the caught exception type.
     */
    public static final class ExceptionHandler extends Site {

        private static final long serialVersionUID = 4897339464722665281L;
        public final int handlerPos;

        ExceptionHandler(int pcOffset, int handlerPos) {
            super(pcOffset);
            this.handlerPos = handlerPos;
        }

        @Override
        public String toString() {
            return String.format("%d[<exception edge to %d>]", pcOffset, handlerPos);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof ExceptionHandler) {
                ExceptionHandler that = (ExceptionHandler) obj;
                if (this.pcOffset == that.pcOffset && this.handlerPos == that.handlerPos) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Represents a mark in the machine code that can be used by the runtime for its own purposes. A
     * mark can reference other marks.
     */
    public static final class Mark extends Site {

        private static final long serialVersionUID = 3612943150662354844L;
        public final Object id;

        public Mark(int pcOffset, Object id) {
            super(pcOffset);
            this.id = id;
        }

        @Override
        public String toString() {
            if (id == null) {
                return String.format("%d[<mar>]", pcOffset);
            } else if (id instanceof Integer) {
                return String.format("%d[<mark with id %s>]", pcOffset, Integer.toHexString((Integer) id));
            } else {
                return String.format("%d[<mark with id %s>]", pcOffset, id.toString());
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof Mark) {
                Mark that = (Mark) obj;
                if (this.pcOffset == that.pcOffset && Objects.equals(this.id, that.id)) {
                    return true;
                }
            }
            return false;
        }
    }

    private int id = -1;
    private int entryBCI = -1;

    private final DataSection dataSection = new DataSection();

    private final List<Infopoint> infopoints = new ArrayList<>();
    private final List<DataPatch> dataPatches = new ArrayList<>();
    private final List<ExceptionHandler> exceptionHandlers = new ArrayList<>();
    private final List<Mark> marks = new ArrayList<>();

    private int totalFrameSize = -1;
    private int customStackAreaOffset = -1;

    private final String name;

    /**
     * The buffer containing the emitted machine code.
     */
    private byte[] targetCode;

    /**
     * The leading number of bytes in {@link #targetCode} containing the emitted machine code.
     */
    private int targetCodeSize;

    private ArrayList<CodeAnnotation> annotations;

    private Assumptions assumptions;

    public CompilationResult() {
        this(null);
    }

    public CompilationResult(String name) {
        this.name = name;
    }

    @Override
    public int hashCode() {
        // CompilationResult instances should not be used as hash map keys
        throw new UnsupportedOperationException("hashCode");
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj != null && obj.getClass() == getClass()) {
            CompilationResult that = (CompilationResult) obj;
            // @formatter:off
            if (this.entryBCI == that.entryBCI &&
                this.id == that.id &&
                this.customStackAreaOffset == that.customStackAreaOffset &&
                this.totalFrameSize == that.totalFrameSize &&
                this.targetCodeSize == that.targetCodeSize &&
                Objects.equals(this.name, that.name) &&
                Objects.equals(this.annotations, that.annotations) &&
                Objects.equals(this.assumptions, that.assumptions) &&
                Objects.equals(this.dataSection, that.dataSection) &&
                Objects.equals(this.exceptionHandlers, that.exceptionHandlers) &&
                Objects.equals(this.dataPatches, that.dataPatches) &&
                Objects.equals(this.infopoints, that.infopoints) &&
                Objects.equals(this.marks,  that.marks) &&
                Arrays.equals(targetCode, that.targetCode)) {
                return true;
            }
            // @formatter:on
        }
        return false;
    }

    /**
     * @return the compile id
     */
    public int getId() {
        return id;
    }

    /**
     * @param id the compile id to set
     */
    public void setId(int id) {
        this.id = id;
    }

    /**
     * @return the entryBCI
     */
    public int getEntryBCI() {
        return entryBCI;
    }

    /**
     * @param entryBCI the entryBCI to set
     */
    public void setEntryBCI(int entryBCI) {
        this.entryBCI = entryBCI;
    }

    public void setAssumptions(Assumptions assumptions) {
        this.assumptions = assumptions;
    }

    public Assumptions getAssumptions() {
        return assumptions;
    }

    public DataSection getDataSection() {
        return dataSection;
    }

    /**
     * The total frame size of the method in bytes. This includes the return address pushed onto the
     * stack, if any.
     *
     * @return the frame size
     */
    public int getTotalFrameSize() {
        assert totalFrameSize != -1 : "frame size not yet initialized!";
        return totalFrameSize;
    }

    /**
     * Sets the total frame size in bytes. This includes the return address pushed onto the stack,
     * if any.
     *
     * @param size the size of the frame in bytes
     */
    public void setTotalFrameSize(int size) {
        totalFrameSize = size;
    }

    /**
     * Sets the machine that has been generated by the compiler.
     *
     * @param code the machine code generated
     * @param size the size of the machine code
     */
    public void setTargetCode(byte[] code, int size) {
        targetCode = code;
        targetCodeSize = size;
    }

    /**
     * Records a data patch in the code section. The data patch can refer to something in the
     * {@link DataSectionReference data section} or directly to an {@link ConstantReference inlined
     * constant}.
     *
     * @param codePos The position in the code that needs to be patched.
     * @param ref The reference that should be inserted in the code.
     */
    public void recordDataPatch(int codePos, Reference ref) {
        assert codePos >= 0 && ref != null;
        dataPatches.add(new DataPatch(codePos, ref));
    }

    /**
     * Records a call in the code array.
     *
     * @param codePos the position of the call in the code array
     * @param size the size of the call instruction
     * @param target the being called
     * @param debugInfo the debug info for the call
     * @param direct specifies if this is a {@linkplain Call#direct direct} call
     */
    public void recordCall(int codePos, int size, InvokeTarget target, DebugInfo debugInfo, boolean direct) {
        final Call call = new Call(target, codePos, size, direct, debugInfo);
        addInfopoint(call);
    }

    /**
     * Records an exception handler for this method.
     *
     * @param codePos the position in the code that is covered by the handler
     * @param handlerPos the position of the handler
     */
    public void recordExceptionHandler(int codePos, int handlerPos) {
        exceptionHandlers.add(new ExceptionHandler(codePos, handlerPos));
    }

    /**
     * Records an infopoint in the code array.
     *
     * @param codePos the position of the infopoint in the code array
     * @param debugInfo the debug info for the infopoint
     */
    public void recordInfopoint(int codePos, DebugInfo debugInfo, InfopointReason reason) {
        addInfopoint(new Infopoint(codePos, debugInfo, reason));
    }

    /**
     * Records a custom infopoint in the code section.
     *
     * Compiler implementations can use this method to record non-standard infopoints, which are not
     * handled by the dedicated methods like {@link #recordCall}.
     *
     * @param infopoint the infopoint to record, usually a derived class from {@link Infopoint}
     */
    public void addInfopoint(Infopoint infopoint) {
        // The infopoints list must always be sorted
        if (!infopoints.isEmpty()) {
            Infopoint previousInfopoint = infopoints.get(infopoints.size() - 1);
            if (previousInfopoint.pcOffset > infopoint.pcOffset) {
                // This re-sorting should be very rare
                Collections.sort(infopoints);
                previousInfopoint = infopoints.get(infopoints.size() - 1);
            }
            if (previousInfopoint.pcOffset == infopoint.pcOffset) {
                if (infopoint.reason.canBeOmitted()) {
                    return;
                }
                if (previousInfopoint.reason.canBeOmitted()) {
                    Infopoint removed = infopoints.remove(infopoints.size() - 1);
                    assert removed == previousInfopoint;
                } else {
                    throw new RuntimeException("Infopoints that can not be omited should have distinct PCs");
                }
            }
        }
        infopoints.add(infopoint);
    }

    /**
     * Records an instruction mark within this method.
     *
     * @param codePos the position in the code that is covered by the handler
     * @param markId the identifier for this mark
     */
    public Mark recordMark(int codePos, Object markId) {
        Mark mark = new Mark(codePos, markId);
        marks.add(mark);
        return mark;
    }

    /**
     * Offset in bytes for the custom stack area (relative to sp).
     *
     * @return the offset in bytes
     */
    public int getCustomStackAreaOffset() {
        return customStackAreaOffset;
    }

    /**
     * @see #getCustomStackAreaOffset()
     * @param offset
     */
    public void setCustomStackAreaOffset(int offset) {
        customStackAreaOffset = offset;
    }

    /**
     * @return the machine code generated for this method
     */
    public byte[] getTargetCode() {
        return targetCode;
    }

    /**
     * @return the size of the machine code generated for this method
     */
    public int getTargetCodeSize() {
        return targetCodeSize;
    }

    /**
     * @return the code annotations or {@code null} if there are none
     */
    public List<CodeAnnotation> getAnnotations() {
        if (annotations == null) {
            return Collections.emptyList();
        }
        return annotations;
    }

    public void addAnnotation(CodeAnnotation annotation) {
        assert annotation != null;
        if (annotations == null) {
            annotations = new ArrayList<>();
        }
        annotations.add(annotation);
    }

    private static void appendDebugInfo(StringBuilder sb, DebugInfo info) {
        if (info != null) {
            ReferenceMap refMap = info.getReferenceMap();
            if (refMap != null) {
                RefMapFormatter formatter = new CodeUtil.NumberedRefMapFormatter();
                if (refMap.hasFrameRefMap()) {
                    sb.append(" stackMap[");
                    refMap.appendFrameMap(sb, formatter);
                    sb.append(']');
                }
                if (refMap.hasRegisterRefMap()) {
                    sb.append(" registerMap[");
                    refMap.appendRegisterMap(sb, formatter);
                    sb.append(']');
                }
            }
            RegisterSaveLayout calleeSaveInfo = info.getCalleeSaveInfo();
            if (calleeSaveInfo != null) {
                sb.append(" callee-save-info[");
                String sep = "";
                for (Map.Entry<Register, Integer> e : calleeSaveInfo.registersToSlots(true).entrySet()) {
                    sb.append(sep).append(e.getKey()).append("->").append(e.getValue());
                    sep = ", ";
                }
                sb.append(']');
            }
            BytecodePosition codePos = info.getBytecodePosition();
            if (codePos != null) {
                MetaUtil.appendLocation(sb.append(" "), codePos.getMethod(), codePos.getBCI());
                if (info.hasFrame()) {
                    sb.append(" #locals=").append(info.frame().numLocals).append(" #expr=").append(info.frame().numStack);
                    if (info.frame().numLocks > 0) {
                        sb.append(" #locks=").append(info.frame().numLocks);
                    }
                }
            }
        }
    }

    /**
     * @return the list of infopoints, sorted by {@link Site#pcOffset}
     */
    public List<Infopoint> getInfopoints() {
        if (infopoints.isEmpty()) {
            return emptyList();
        }
        return unmodifiableList(infopoints);
    }

    /**
     * @return the list of data references
     */
    public List<DataPatch> getDataPatches() {
        if (dataPatches.isEmpty()) {
            return emptyList();
        }
        return unmodifiableList(dataPatches);
    }

    /**
     * @return the list of exception handlers
     */
    public List<ExceptionHandler> getExceptionHandlers() {
        if (exceptionHandlers.isEmpty()) {
            return emptyList();
        }
        return unmodifiableList(exceptionHandlers);
    }

    /**
     * @return the list of marks
     */
    public List<Mark> getMarks() {
        if (marks.isEmpty()) {
            return emptyList();
        }
        return unmodifiableList(marks);
    }

    public String getName() {
        return name;
    }

    public void reset() {
        infopoints.clear();
        dataPatches.clear();
        exceptionHandlers.clear();
        marks.clear();
        if (annotations != null) {
            annotations.clear();
        }
    }
}
