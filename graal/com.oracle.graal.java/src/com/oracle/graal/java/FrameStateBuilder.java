/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.graph.iterators.NodePredicates.*;
import static com.oracle.graal.nodes.ValueUtil.*;
import static java.lang.reflect.Modifier.*;

import java.util.*;

import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.Node.Verbosity;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.PhiNode.PhiType;
import com.oracle.graal.nodes.type.*;
import com.oracle.max.cri.ci.*;
import com.oracle.max.cri.ri.*;

public class FrameStateBuilder {
    private final RiResolvedMethod method;
    private final StructuredGraph graph;

    private final ValueNode[] locals;
    private final ValueNode[] stack;
    private int stackSize;
    private boolean rethrowException;

    public FrameStateBuilder(RiResolvedMethod method, StructuredGraph graph, boolean eagerResolve) {
        assert graph != null;
        this.method = method;
        this.graph = graph;
        this.locals = new ValueNode[method.maxLocals()];
        // we always need at least one stack slot (for exceptions)
        this.stack = new ValueNode[Math.max(1, method.maxStackSize())];

        int javaIndex = 0;
        int index = 0;
        if (!isStatic(method.accessFlags())) {
            // add the receiver
            LocalNode local = graph.unique(new LocalNode(javaIndex, StampFactory.declaredNonNull(method.holder())));
            storeLocal(javaIndex, local);
            javaIndex = 1;
            index = 1;
        }
        RiSignature sig = method.signature();
        int max = sig.argumentCount(false);
        RiResolvedType accessingClass = method.holder();
        for (int i = 0; i < max; i++) {
            RiType type = sig.argumentTypeAt(i, accessingClass);
            if (eagerResolve) {
                type = type.resolve(accessingClass);
            }
            CiKind kind = type.kind(false).stackKind();
            Stamp stamp;
            if (kind == CiKind.Object && type instanceof RiResolvedType) {
                RiResolvedType resolvedType = (RiResolvedType) type;
                stamp = StampFactory.declared(resolvedType);
            } else {
                stamp = StampFactory.forKind(kind);
            }
            LocalNode local = graph.unique(new LocalNode(index, stamp));
            storeLocal(javaIndex, local);
            javaIndex += stackSlots(kind);
            index++;
        }
    }

    private FrameStateBuilder(RiResolvedMethod method, StructuredGraph graph, ValueNode[] locals, ValueNode[] stack, int stackSize, boolean rethrowException) {
        assert locals.length == method.maxLocals();
        assert stack.length == Math.max(1, method.maxStackSize());

        this.method = method;
        this.graph = graph;
        this.locals = locals;
        this.stack = stack;
        this.stackSize = stackSize;
        this.rethrowException = rethrowException;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[locals: [");
        for (int i = 0; i < locals.length; i++) {
            sb.append(i == 0 ? "" : ",").append(locals[i] == null ? "_" : locals[i].toString(Verbosity.Id));
        }
        sb.append("] stack: [");
        for (int i = 0; i < stackSize; i++) {
            sb.append(i == 0 ? "" : ",").append(stack[i] == null ? "_" : stack[i].toString(Verbosity.Id));
        }
        sb.append("]");
        if (rethrowException) {
            sb.append(" rethrowException");
        }
        sb.append("]");
        return sb.toString();
    }

    public FrameState create(int bci) {
        return graph.add(new FrameState(method, bci, locals, stack, stackSize, rethrowException, false));
    }

    public FrameState duplicateWithoutStack(int bci) {
        return graph.add(new FrameState(method, bci, locals, new ValueNode[0], 0, false, false));
    }


    public FrameStateBuilder copy() {
        return new FrameStateBuilder(method, graph, Arrays.copyOf(locals, locals.length), Arrays.copyOf(stack, stack.length), stackSize, rethrowException);
    }

    public FrameStateBuilder copyWithException(ValueNode exceptionObject) {
        ValueNode[] newStack = new ValueNode[stack.length];
        newStack[0] = exceptionObject;
        return new FrameStateBuilder(method, graph, Arrays.copyOf(locals, locals.length), newStack, 1, true);
    }


    public boolean isCompatibleWith(FrameStateBuilder other) {
        assert method == other.method && graph == other.graph && localsSize() == other.localsSize() : "Can only compare frame states of the same method";

        if (stackSize() != other.stackSize()) {
            return false;
        }
        for (int i = 0; i < stackSize(); i++) {
            ValueNode x = stackAt(i);
            ValueNode y = other.stackAt(i);
            if (x != y && ValueUtil.typeMismatch(x, y)) {
                return false;
            }
        }
        return true;
    }

    public void merge(MergeNode block, FrameStateBuilder other) {
        assert isCompatibleWith(other);

        for (int i = 0; i < localsSize(); i++) {
            storeLocal(i, merge(localAt(i), other.localAt(i), block));
        }
        for (int i = 0; i < stackSize(); i++) {
            storeStack(i, merge(stackAt(i), other.stackAt(i), block));
        }
    }

    private ValueNode merge(ValueNode currentValue, ValueNode otherValue, MergeNode block) {
        if (currentValue == null) {
            return null;

        } else if (block.isPhiAtMerge(currentValue)) {
            if (otherValue == null || currentValue.kind() != otherValue.kind()) {
                deletePhi((PhiNode) currentValue);
                return null;
            }
            ((PhiNode) currentValue).addInput(otherValue);
            return currentValue;

        } else if (currentValue != otherValue) {
            assert !(block instanceof LoopBeginNode) : "Phi functions for loop headers are create eagerly for all locals and stack slots";
            if (otherValue == null || currentValue.kind() != otherValue.kind()) {
                return null;
            }

            PhiNode phi = graph.unique(new PhiNode(currentValue.kind(), block, PhiType.Value));
            for (int i = 0; i < block.phiPredecessorCount(); i++) {
                phi.addInput(currentValue);
            }
            phi.addInput(otherValue);
            assert phi.valueCount() == block.phiPredecessorCount() + 1 : "valueCount=" + phi.valueCount() + " predSize= " + block.phiPredecessorCount();
            return phi;

        } else {
            return currentValue;
        }
    }

    private void deletePhi(PhiNode phi) {
        if (phi.isDeleted()) {
            return;
        }
        // Collect all phi functions that use this phi so that we can delete them recursively (after we delete ourselfs to avoid circles).
        List<PhiNode> phiUsages = phi.usages().filter(PhiNode.class).snapshot();
        List<ValueProxyNode> vpnUsages = phi.usages().filter(ValueProxyNode.class).snapshot();

        // Remove the phi function from all FrameStates where it is used and then delete it.
        assert phi.usages().filter(isNotA(FrameState.class).nor(PhiNode.class).nor(ValueProxyNode.class)).isEmpty() : "phi function that gets deletes must only be used in frame states";
        phi.replaceAtUsages(null);
        phi.safeDelete();

        for (PhiNode phiUsage : phiUsages) {
            deletePhi(phiUsage);
        }
        for (ValueProxyNode proxyUsage : vpnUsages) {
            deleteProxy(proxyUsage);
        }
    }

    private void deleteProxy(ValueProxyNode proxy) {
        if (proxy.isDeleted()) {
            return;
        }
        // Collect all phi functions that use this phi so that we can delete them recursively (after we delete ourselfs to avoid circles).
        List<PhiNode> phiUsages = proxy.usages().filter(PhiNode.class).snapshot();
        List<ValueProxyNode> vpnUsages = proxy.usages().filter(ValueProxyNode.class).snapshot();

        // Remove the proxy function from all FrameStates where it is used and then delete it.
        assert proxy.usages().filter(isNotA(FrameState.class).nor(PhiNode.class).nor(ValueProxyNode.class)).isEmpty() : "phi function that gets deletes must only be used in frame states";
        proxy.replaceAtUsages(null);
        proxy.safeDelete();

        for (PhiNode phiUsage : phiUsages) {
            deletePhi(phiUsage);
        }
        for (ValueProxyNode proxyUsage : vpnUsages) {
            deleteProxy(proxyUsage);
        }
    }

    public void insertLoopPhis(LoopBeginNode loopBegin) {
        for (int i = 0; i < localsSize(); i++) {
            storeLocal(i, createLoopPhi(loopBegin, localAt(i)));
        }
        for (int i = 0; i < stackSize(); i++) {
            storeStack(i, createLoopPhi(loopBegin, stackAt(i)));
        }
    }

    public void insertProxies(LoopExitNode loopExit, FrameStateBuilder loopEntryState) {
        for (int i = 0; i < localsSize(); i++) {
            ValueNode value = localAt(i);
            if (value != null && (!loopEntryState.contains(value) || loopExit.loopBegin().isPhiAtMerge(value))) {
                Debug.log(" inserting proxy for %s", value);
                storeLocal(i, graph.unique(new ValueProxyNode(value, loopExit, PhiType.Value)));
            }
        }
        for (int i = 0; i < stackSize(); i++) {
            ValueNode value = stackAt(i);
            if (value != null && (!loopEntryState.contains(value) || loopExit.loopBegin().isPhiAtMerge(value))) {
                Debug.log(" inserting proxy for %s", value);
                storeStack(i, graph.unique(new ValueProxyNode(value, loopExit, PhiType.Value)));
            }
        }
    }

    private PhiNode createLoopPhi(MergeNode block, ValueNode value) {
        if (value == null) {
            return null;
        }
        assert !block.isPhiAtMerge(value) : "phi function for this block already created";

        PhiNode phi = graph.unique(new PhiNode(value.kind(), block, PhiType.Value));
        phi.addInput(value);
        return phi;
    }

    public void cleanupDeletedPhis() {
        for (int i = 0; i < localsSize(); i++) {
            if (localAt(i) != null && localAt(i).isDeleted()) {
                assert localAt(i) instanceof PhiNode : "Only phi functions can be deleted during parsing";
                storeLocal(i, null);
            }
        }
    }

    public void clearNonLiveLocals(BitMap liveness) {
        if (liveness == null) {
            return;
        }
        assert liveness.size() == locals.length;
        for (int i = 0; i < locals.length; i++) {
            if (!liveness.get(i)) {
                locals[i] = null;
            }
        }
    }

    public boolean rethrowException() {
        return rethrowException;
    }

    public void setRethrowException(boolean b) {
        rethrowException = b;
    }


    /**
     * Returns the size of the local variables.
     *
     * @return the size of the local variables
     */
    public int localsSize() {
        return locals.length;
    }

    /**
     * Gets the current size (height) of the stack.
     */
    public int stackSize() {
        return stackSize;
    }

    /**
     * Gets the value in the local variables at the specified index, without any sanity checking.
     *
     * @param i the index into the locals
     * @return the instruction that produced the value for the specified local
     */
    protected final ValueNode localAt(int i) {
        return locals[i];
    }

    /**
     * Get the value on the stack at the specified stack index.
     *
     * @param i the index into the stack, with {@code 0} being the bottom of the stack
     * @return the instruction at the specified position in the stack
     */
    protected final ValueNode stackAt(int i) {
        return stack[i];
    }

    /**
     * Loads the local variable at the specified index, checking that the returned value is non-null
     * and that two-stack values are properly handled.
     *
     * @param i the index of the local variable to load
     * @return the instruction that produced the specified local
     */
    public ValueNode loadLocal(int i) {
        ValueNode x = locals[i];
        assert !x.isDeleted();
        assert !isTwoSlot(x.kind()) || locals[i + 1] == null;
        assert i == 0 || locals[i - 1] == null || !isTwoSlot(locals[i - 1].kind());
        return x;
    }

    /**
     * Stores a given local variable at the specified index. If the value is a {@linkplain CiKind#isDoubleWord() double word},
     * then the next local variable index is also overwritten.
     *
     * @param i the index at which to store
     * @param x the instruction which produces the value for the local
     */
    public void storeLocal(int i, ValueNode x) {
        assert x == null || x.kind() != CiKind.Void && x.kind() != CiKind.Illegal : "unexpected value: " + x;
        locals[i] = x;
        if (x != null && isTwoSlot(x.kind())) {
            // if this is a double word, then kill i+1
            locals[i + 1] = null;
        }
        if (x != null && i > 0) {
            ValueNode p = locals[i - 1];
            if (p != null && isTwoSlot(p.kind())) {
                // if there was a double word at i - 1, then kill it
                locals[i - 1] = null;
            }
        }
    }

    private void storeStack(int i, ValueNode x) {
        assert x == null || stack[i] == null || x.kind() == stack[i].kind() : "Method does not handle changes from one-slot to two-slot values";
        stack[i] = x;
    }

    /**
     * Pushes an instruction onto the stack with the expected type.
     * @param kind the type expected for this instruction
     * @param x the instruction to push onto the stack
     */
    public void push(CiKind kind, ValueNode x) {
        assert !x.isDeleted() && x.kind() != CiKind.Void && x.kind() != CiKind.Illegal;
        xpush(assertKind(kind, x));
        if (isTwoSlot(kind)) {
            xpush(null);
        }
    }

    /**
     * Pushes a value onto the stack without checking the type.
     * @param x the instruction to push onto the stack
     */
    public void xpush(ValueNode x) {
        assert x == null || (!x.isDeleted() && x.kind() != CiKind.Void && x.kind() != CiKind.Illegal);
        stack[stackSize++] = x;
    }

    /**
     * Pushes a value onto the stack and checks that it is an int.
     * @param x the instruction to push onto the stack
     */
    public void ipush(ValueNode x) {
        xpush(assertInt(x));
    }

    /**
     * Pushes a value onto the stack and checks that it is a float.
     * @param x the instruction to push onto the stack
     */
    public void fpush(ValueNode x) {
        xpush(assertFloat(x));
    }

    /**
     * Pushes a value onto the stack and checks that it is an object.
     * @param x the instruction to push onto the stack
     */
    public void apush(ValueNode x) {
        xpush(assertObject(x));
    }

    /**
     * Pushes a value onto the stack and checks that it is a JSR return address.
     * @param x the instruction to push onto the stack
     */
    public void jpush(ValueNode x) {
        xpush(assertJsr(x));
    }

    /**
     * Pushes a value onto the stack and checks that it is a long.
     *
     * @param x the instruction to push onto the stack
     */
    public void lpush(ValueNode x) {
        xpush(assertLong(x));
        xpush(null);
    }

    /**
     * Pushes a value onto the stack and checks that it is a double.
     * @param x the instruction to push onto the stack
     */
    public void dpush(ValueNode x) {
        xpush(assertDouble(x));
        xpush(null);
    }

    public void pushReturn(CiKind kind, ValueNode x) {
        if (kind != CiKind.Void) {
            push(kind.stackKind(), x);
        }
    }

    /**
     * Pops an instruction off the stack with the expected type.
     * @param kind the expected type
     * @return the instruction on the top of the stack
     */
    public ValueNode pop(CiKind kind) {
        assert kind != CiKind.Void;
        if (isTwoSlot(kind)) {
            xpop();
        }
        return assertKind(kind, xpop());
    }

    /**
     * Pops a value off of the stack without checking the type.
     * @return x the instruction popped off the stack
     */
    public ValueNode xpop() {
        ValueNode result = stack[--stackSize];
        assert result == null || !result.isDeleted();
        return result;
    }

    /**
     * Pops a value off of the stack and checks that it is an int.
     * @return x the instruction popped off the stack
     */
    public ValueNode ipop() {
        return assertInt(xpop());
    }

    /**
     * Pops a value off of the stack and checks that it is a float.
     * @return x the instruction popped off the stack
     */
    public ValueNode fpop() {
        return assertFloat(xpop());
    }

    /**
     * Pops a value off of the stack and checks that it is an object.
     * @return x the instruction popped off the stack
     */
    public ValueNode apop() {
        return assertObject(xpop());
    }

    /**
     * Pops a value off of the stack and checks that it is a JSR return address.
     * @return x the instruction popped off the stack
     */
    public ValueNode jpop() {
        return assertJsr(xpop());
    }

    /**
     * Pops a value off of the stack and checks that it is a long.
     * @return x the instruction popped off the stack
     */
    public ValueNode lpop() {
        assertHigh(xpop());
        return assertLong(xpop());
    }

    /**
     * Pops a value off of the stack and checks that it is a double.
     * @return x the instruction popped off the stack
     */
    public ValueNode dpop() {
        assertHigh(xpop());
        return assertDouble(xpop());
    }

    /**
     * Pop the specified number of slots off of this stack and return them as an array of instructions.
     * @param size the number of arguments off of the stack
     * @return an array containing the arguments off of the stack
     */
    public ValueNode[] popArguments(int slotSize, int argSize) {
        int base = stackSize - slotSize;
        ValueNode[] r = new ValueNode[argSize];
        int argIndex = 0;
        int stackindex = 0;
        while (stackindex < slotSize) {
            ValueNode element = stack[base + stackindex];
            assert element != null;
            r[argIndex++] = element;
            stackindex += stackSlots(element.kind());
        }
        stackSize = base;
        return r;
    }

    /**
     * Peeks an element from the operand stack.
     * @param argumentNumber The number of the argument, relative from the top of the stack (0 = top).
     *        Long and double arguments only count as one argument, i.e., null-slots are ignored.
     * @return The peeked argument.
     */
    public ValueNode peek(int argumentNumber) {
        int idx = stackSize() - 1;
        for (int i = 0; i < argumentNumber; i++) {
            if (stackAt(idx) == null) {
                idx--;
                assert isTwoSlot(stackAt(idx).kind());
            }
            idx--;
        }
        return stackAt(idx);
    }

    /**
     * Clears all values on this stack.
     */
    public void clearStack() {
        stackSize = 0;
    }

    public static int stackSlots(CiKind kind) {
        return isTwoSlot(kind) ? 2 : 1;
    }

    public static boolean isTwoSlot(CiKind kind) {
        assert kind != CiKind.Void && kind != CiKind.Illegal;
        return kind == CiKind.Long || kind == CiKind.Double;
    }

    public boolean contains(ValueNode value) {
        for (int i = 0; i < localsSize(); i++) {
            if (localAt(i) == value) {
                return true;
            }
        }
        for (int i = 0; i < stackSize(); i++) {
            if (stackAt(i) == value) {
                return true;
            }
        }
        return false;
    }
}
