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
package com.oracle.graal.nodes;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.iterators.*;
import com.oracle.graal.nodes.virtual.*;

/**
 * The {@code FrameState} class encapsulates the frame state (i.e. local variables and operand
 * stack) at a particular point in the abstract interpretation.
 */
@NodeInfo(nameTemplate = "FrameState@{p#method/s}:{p#bci}")
public final class FrameState extends VirtualState implements Node.IterableNodeType {

    protected final int localsSize;

    protected final int stackSize;

    private boolean rethrowException;

    private boolean duringCall;

    /**
     * This BCI should be used for frame states that are built for code with no meaningful BCI.
     */
    public static final int UNKNOWN_BCI = -4;

    /**
     * When a node whose frame state has this BCI value is inlined, its frame state will be replaced
     * with the frame state before the inlined invoke node.
     */
    public static final int BEFORE_BCI = -1;

    /**
     * When a node whose frame state has this BCI value is inlined, its frame state will be replaced
     * with the frame state {@linkplain Invoke#stateAfter() after} the inlined invoke node.
     */
    public static final int AFTER_BCI = -2;

    /**
     * When a node whose frame state has this BCI value is inlined, its frame state will be replaced
     * with the frame state at the exception edge of the inlined invoke node.
     */
    public static final int AFTER_EXCEPTION_BCI = -3;

    /**
     * This BCI should be used for frame states that cannot be the target of a deoptimization, like
     * snippet frame states.
     */
    public static final int INVALID_FRAMESTATE_BCI = -5;

    @Input private FrameState outerFrameState;

    @Input private final NodeInputList<ValueNode> values;

    @Input private final NodeInputList<EscapeObjectState> virtualObjectMappings;

    /**
     * The bytecode index to which this frame state applies.
     */
    public final int bci;

    private final ResolvedJavaMethod method;

    /**
     * Creates a {@code FrameState} for the given scope and maximum number of stack and local
     * variables.
     * 
     * @param method the method for this frame state
     * @param bci the bytecode index of the frame state
     * @param stackSize size of the stack
     * @param rethrowException if true the VM should re-throw the exception on top of the stack when
     *            deopt'ing using this framestate
     */
    public FrameState(ResolvedJavaMethod method, int bci, List<ValueNode> values, int localsSize, int stackSize, boolean rethrowException, boolean duringCall,
                    List<EscapeObjectState> virtualObjectMappings) {
        assert stackSize >= 0;
        assert (bci >= 0 && method != null) || (bci < 0 && method == null && values.isEmpty());
        this.method = method;
        this.bci = bci;
        this.localsSize = localsSize;
        this.stackSize = stackSize;
        this.values = new NodeInputList<>(this, values);
        this.virtualObjectMappings = new NodeInputList<>(this, virtualObjectMappings);
        this.rethrowException = rethrowException;
        this.duringCall = duringCall;
        assert !rethrowException || stackSize == 1 : "must have exception on top of the stack";
    }

    /**
     * Simple constructor used to create marker FrameStates.
     * 
     * @param bci marker bci, needs to be < 0
     */
    public FrameState(int bci) {
        this(null, bci, Collections.<ValueNode> emptyList(), 0, 0, false, false, Collections.<EscapeObjectState> emptyList());
    }

    public FrameState(ResolvedJavaMethod method, int bci, ValueNode[] locals, List<ValueNode> stack, ValueNode[] locks, boolean rethrowException, boolean duringCall) {
        this.method = method;
        this.bci = bci;
        this.localsSize = locals.length;
        this.stackSize = stack.size();
        final ValueNode[] newValues = new ValueNode[locals.length + stack.size() + locks.length];
        int pos = 0;
        for (ValueNode value : locals) {
            newValues[pos++] = value;
        }
        for (ValueNode value : stack) {
            newValues[pos++] = value;
        }
        for (ValueNode value : locks) {
            newValues[pos++] = value;
        }
        for (ValueNode value : newValues) {
            assert value == null || value.isAlive();
        }
        this.values = new NodeInputList<>(this, newValues);
        this.virtualObjectMappings = new NodeInputList<>(this);
        this.rethrowException = rethrowException;
        this.duringCall = duringCall;
        assert !rethrowException || stackSize == 1 : "must have exception on top of the stack";
    }

    public NodeInputList<ValueNode> values() {
        return values;
    }

    public FrameState outerFrameState() {
        return outerFrameState;
    }

    public void setOuterFrameState(FrameState x) {
        updateUsages(this.outerFrameState, x);
        this.outerFrameState = x;
    }

    public boolean rethrowException() {
        return rethrowException;
    }

    public boolean duringCall() {
        return duringCall;
    }

    public void setDuringCall(boolean b) {
        this.duringCall = b;
    }

    public ResolvedJavaMethod method() {
        return method;
    }

    public void addVirtualObjectMapping(EscapeObjectState virtualObject) {
        virtualObjectMappings.add(virtualObject);
    }

    public int virtualObjectMappingCount() {
        return virtualObjectMappings.size();
    }

    public EscapeObjectState virtualObjectMappingAt(int i) {
        return virtualObjectMappings.get(i);
    }

    public NodeInputList<EscapeObjectState> virtualObjectMappings() {
        return virtualObjectMappings;
    }

    /**
     * Gets a copy of this frame state.
     */
    public FrameState duplicate(int newBci) {
        FrameState other = graph().add(new FrameState(method, newBci, values, localsSize, stackSize, rethrowException, duringCall, virtualObjectMappings));
        other.setOuterFrameState(outerFrameState());
        return other;
    }

    /**
     * Gets a copy of this frame state.
     */
    public FrameState duplicate() {
        return duplicate(bci);
    }

    /**
     * Duplicates a FrameState, along with a deep copy of all connected VirtualState (outer
     * FrameStates, VirtualObjectStates, ...).
     */
    @Override
    public FrameState duplicateWithVirtualState() {
        FrameState newOuterFrameState = outerFrameState();
        if (newOuterFrameState != null) {
            newOuterFrameState = newOuterFrameState.duplicateWithVirtualState();
        }
        ArrayList<EscapeObjectState> newVirtualMappings = new ArrayList<>(virtualObjectMappings.size());
        for (EscapeObjectState state : virtualObjectMappings) {
            newVirtualMappings.add(state.duplicateWithVirtualState());
        }
        FrameState other = graph().add(new FrameState(method, bci, values, localsSize, stackSize, rethrowException, duringCall, newVirtualMappings));
        other.setOuterFrameState(newOuterFrameState);
        return other;
    }

    /**
     * Creates a copy of this frame state with one stack element of type popKind popped from the
     * stack and the values in pushedValues pushed on the stack. The pushedValues are expected to be
     * in slot encoding: a long or double is followed by a null slot.
     */
    public FrameState duplicateModified(int newBci, boolean newRethrowException, Kind popKind, ValueNode... pushedValues) {
        ArrayList<ValueNode> copy = new ArrayList<>(values.subList(0, localsSize + stackSize));
        if (popKind != Kind.Void) {
            if (stackAt(stackSize() - 1) == null) {
                copy.remove(copy.size() - 1);
            }
            ValueNode lastSlot = copy.get(copy.size() - 1);
            assert lastSlot.kind().getStackKind() == popKind.getStackKind();
            copy.remove(copy.size() - 1);
        }
        Collections.addAll(copy, pushedValues);
        int newStackSize = copy.size() - localsSize;
        copy.addAll(values.subList(localsSize + stackSize, values.size()));

        FrameState other = graph().add(new FrameState(method, newBci, copy, localsSize, newStackSize, newRethrowException, false, virtualObjectMappings));
        other.setOuterFrameState(outerFrameState());
        return other;
    }

    /**
     * Gets the size of the local variables.
     */
    public int localsSize() {
        return localsSize;
    }

    /**
     * Gets the current size (height) of the stack.
     */
    public int stackSize() {
        return stackSize;
    }

    /**
     * Gets the number of locked monitors in this frame state.
     */
    public int locksSize() {
        return values.size() - localsSize - stackSize;
    }

    /**
     * Gets the number of locked monitors in this frame state and all
     * {@linkplain #outerFrameState() outer} frame states.
     */
    public int nestedLockDepth() {
        int depth = locksSize();
        for (FrameState outer = outerFrameState(); outer != null; outer = outer.outerFrameState()) {
            depth += outer.locksSize();
        }
        return depth;
    }

    /**
     * Gets the value in the local variables at the specified index.
     * 
     * @param i the index into the locals
     * @return the instruction that produced the value for the specified local
     */
    public ValueNode localAt(int i) {
        assert i >= 0 && i < localsSize : "local variable index out of range: " + i;
        return values.get(i);
    }

    /**
     * Get the value on the stack at the specified stack index.
     * 
     * @param i the index into the stack, with {@code 0} being the bottom of the stack
     * @return the instruction at the specified position in the stack
     */
    public ValueNode stackAt(int i) {
        assert i >= 0 && i < stackSize;
        return values.get(localsSize + i);
    }

    /**
     * Get the monitor owner at the specified index.
     * 
     * @param i the index into the list of locked monitors.
     * @return the lock owner at the given index.
     */
    public ValueNode lockAt(int i) {
        assert i >= 0 && i < locksSize();
        return values.get(localsSize + stackSize + i);
    }

    public NodeIterable<FrameState> innerFrameStates() {
        return usages().filter(FrameState.class);
    }

    private static String toString(FrameState frameState) {
        StringBuilder sb = new StringBuilder();
        String nl = CodeUtil.NEW_LINE;
        FrameState fs = frameState;
        while (fs != null) {
            MetaUtil.appendLocation(sb, fs.method, fs.bci).append(nl);
            sb.append("locals: [");
            for (int i = 0; i < fs.localsSize(); i++) {
                sb.append(i == 0 ? "" : ", ").append(fs.localAt(i) == null ? "_" : fs.localAt(i).toString(Verbosity.Id));
            }
            sb.append("]").append(nl).append("stack: [");
            for (int i = 0; i < fs.stackSize(); i++) {
                sb.append(i == 0 ? "" : ", ").append(fs.stackAt(i) == null ? "_" : fs.stackAt(i).toString(Verbosity.Id));
            }
            sb.append("]").append(nl).append("locks: [");
            for (int i = 0; i < fs.locksSize(); i++) {
                sb.append(i == 0 ? "" : ", ").append(fs.lockAt(i) == null ? "_" : fs.lockAt(i).toString(Verbosity.Id));
            }
            sb.append(']').append(nl);
            fs = fs.outerFrameState();
        }
        return sb.toString();
    }

    @Override
    public String toString(Verbosity verbosity) {
        if (verbosity == Verbosity.Debugger) {
            return toString(this);
        } else if (verbosity == Verbosity.Name) {
            return super.toString(Verbosity.Name) + "@" + bci;
        } else {
            return super.toString(verbosity);
        }
    }

    @Override
    public Map<Object, Object> getDebugProperties(Map<Object, Object> map) {
        Map<Object, Object> properties = super.getDebugProperties(map);
        if (method != null) {
            // properties.put("method", MetaUtil.format("%H.%n(%p):%r", method));
            StackTraceElement ste = method.asStackTraceElement(bci);
            if (ste.getFileName() != null && ste.getLineNumber() >= 0) {
                properties.put("sourceFile", ste.getFileName());
                properties.put("sourceLine", ste.getLineNumber());
            }
        }
        properties.put("locksSize", values.size() - stackSize - localsSize);
        return properties;
    }

    @Override
    public boolean verify() {
        for (ValueNode value : values) {
            assert assertTrue(value == null || !value.isDeleted(), "frame state must not contain deleted nodes");
            assert assertTrue(value == null || value instanceof VirtualObjectNode || (value.kind() != Kind.Void && value.kind() != Kind.Illegal), "unexpected value: %s", value);
        }
        return super.verify();
    }

    @Override
    public void applyToNonVirtual(NodeClosure<? super ValueNode> closure) {
        for (ValueNode value : values.nonNull()) {
            closure.apply(this, value);
        }
        for (EscapeObjectState state : virtualObjectMappings) {
            state.applyToNonVirtual(closure);
        }
        if (outerFrameState() != null) {
            outerFrameState().applyToNonVirtual(closure);
        }
    }

    @Override
    public void applyToVirtual(VirtualClosure closure) {
        closure.apply(this);
        for (EscapeObjectState state : virtualObjectMappings) {
            state.applyToVirtual(closure);
        }
        if (outerFrameState() != null) {
            outerFrameState().applyToVirtual(closure);
        }
    }

    @Override
    public boolean isPartOfThisState(VirtualState state) {
        if (state == this) {
            return true;
        }
        if (outerFrameState() != null && outerFrameState().isPartOfThisState(state)) {
            return true;
        }
        for (EscapeObjectState objectState : virtualObjectMappings) {
            if (objectState.isPartOfThisState(state)) {
                return true;
            }
        }
        return false;
    }
}
