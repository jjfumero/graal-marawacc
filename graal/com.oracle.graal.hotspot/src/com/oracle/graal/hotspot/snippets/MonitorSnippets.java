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
package com.oracle.graal.hotspot.snippets;

import static com.oracle.graal.hotspot.nodes.BeginLockScopeNode.*;
import static com.oracle.graal.hotspot.nodes.DirectCompareAndSwapNode.*;
import static com.oracle.graal.hotspot.nodes.EndLockScopeNode.*;
import static com.oracle.graal.hotspot.nodes.VMErrorNode.*;
import static com.oracle.graal.hotspot.snippets.HotSpotSnippetUtils.*;
import static com.oracle.graal.snippets.nodes.DirectObjectStoreNode.*;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.Node.NodeIntrinsic;
import com.oracle.graal.graph.iterators.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.java.MethodCallTargetNode.InvokeKind;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.snippets.*;
import com.oracle.graal.snippets.Snippet.ConstantParameter;
import com.oracle.graal.snippets.Snippet.Parameter;
import com.oracle.graal.snippets.SnippetTemplate.AbstractTemplates;
import com.oracle.graal.snippets.SnippetTemplate.Arguments;
import com.oracle.graal.snippets.SnippetTemplate.Key;
import com.oracle.graal.snippets.nodes.*;

/**
 * Snippets used for implementing the monitorenter and monitorexit instructions.
 *
 * The locking algorithm used is described in the paper <a href="http://dl.acm.org/citation.cfm?id=1167515.1167496">
 * Eliminating synchronization-related atomic operations with biased locking and bulk rebiasing</a>
 * by Kenneth Russell and David Detlefs.
 */
public class MonitorSnippets implements SnippetsInterface {

    /**
     * Monitor operations on objects whose type contains this substring will be traced.
     */
    private static final String TRACE_TYPE_FILTER = System.getProperty("graal.monitors.trace.typeFilter");

    /**
     * Monitor operations in methods whose fully qualified name contains this substring will be traced.
     */
    private static final String TRACE_METHOD_FILTER = System.getProperty("graal.monitors.trace.methodFilter");

    public static final boolean CHECK_BALANCED_MONITORS = Boolean.getBoolean("graal.monitors.checkBalanced");

    @Snippet
    public static void monitorenter(@Parameter("object") Object object, @ConstantParameter("trace") boolean trace) {
        verifyOop(object);

        // Load the mark word - this includes a null-check on object
        final Word mark = loadWordFromObject(object, markOffset());

        final Word lock = beginLockScope(false, wordKind());

        trace(trace, "           object: 0x%016lx\n", Word.fromObject(object).toLong());
        trace(trace, "             lock: 0x%016lx\n", lock.toLong());
        trace(trace, "             mark: 0x%016lx\n", mark.toLong());

        incCounter();

        if (useBiasedLocking()) {
            // See whether the lock is currently biased toward our thread and
            // whether the epoch is still valid.
            // Note that the runtime guarantees sufficient alignment of JavaThread
            // pointers to allow age to be placed into low bits.
            final Word biasableLockBits = mark.and(biasedLockMaskInPlace());

            // First check to see whether biasing is enabled for this object
            if (biasableLockBits.toLong() != biasedLockPattern()) {
                // Biasing not enabled -> fall through to lightweight locking
            } else {
                // The bias pattern is present in the object's mark word. Need to check
                // whether the bias owner and the epoch are both still current.
                Object hub = loadHub(object);
                final Word prototypeMarkWord = loadWordFromObject(hub, prototypeMarkWordOffset());
                final Word thread = thread();
                final Word tmp = prototypeMarkWord.or(thread).xor(mark).and(~ageMaskInPlace());
                trace(trace, "prototypeMarkWord: 0x%016lx\n", prototypeMarkWord.toLong());
                trace(trace, "           thread: 0x%016lx\n", thread.toLong());
                trace(trace, "              tmp: 0x%016lx\n", tmp.toLong());
                if (tmp == Word.zero()) {
                    // Object is already biased to current thread -> done
                    trace(trace, "+lock{bias:existing}", object);
                    return;
                }

                // At this point we know that the mark word has the bias pattern and
                // that we are not the bias owner in the current epoch. We need to
                // figure out more details about the state of the mark word in order to
                // know what operations can be legally performed on the object's
                // mark word.

                // If the low three bits in the xor result aren't clear, that means
                // the prototype header is no longer biasable and we have to revoke
                // the bias on this object.
                if (tmp.and(biasedLockMaskInPlace()) == Word.zero()) {
                    // Biasing is still enabled for object's type. See whether the
                    // epoch of the current bias is still valid, meaning that the epoch
                    // bits of the mark word are equal to the epoch bits of the
                    // prototype mark word. (Note that the prototype mark word's epoch bits
                    // only change at a safepoint.) If not, attempt to rebias the object
                    // toward the current thread. Note that we must be absolutely sure
                    // that the current epoch is invalid in order to do this because
                    // otherwise the manipulations it performs on the mark word are
                    // illegal.
                    if (tmp.and(epochMaskInPlace()) == Word.zero()) {
                        // The epoch of the current bias is still valid but we know nothing
                        // about the owner; it might be set or it might be clear. Try to
                        // acquire the bias of the object using an atomic operation. If this
                        // fails we will go in to the runtime to revoke the object's bias.
                        // Note that we first construct the presumed unbiased header so we
                        // don't accidentally blow away another thread's valid bias.
                        Word unbiasedMark = mark.and(biasedLockMaskInPlace() | ageMaskInPlace() | epochMaskInPlace());
                        Word biasedMark = unbiasedMark.or(thread);
                        trace(trace, "     unbiasedMark: 0x%016lx\n", unbiasedMark.toLong());
                        trace(trace, "       biasedMark: 0x%016lx\n", biasedMark.toLong());
                        if (compareAndSwap(object, markOffset(), unbiasedMark, biasedMark) == unbiasedMark) {
                            // Object is now biased to current thread -> done
                            trace(trace, "+lock{bias:acquired}", object);
                            return;
                        }
                        // If the biasing toward our thread failed, this means that another thread
                        // owns the bias and we need to revoke that bias. The revocation will occur
                        // in the interpreter runtime.
                        trace(trace, "+lock{stub:revoke}", object);
                        MonitorEnterStubCall.call(object, lock);
                        return;
                    } else {
                        // At this point we know the epoch has expired, meaning that the
                        // current bias owner, if any, is actually invalid. Under these
                        // circumstances _only_, are we allowed to use the current mark word
                        // value as the comparison value when doing the CAS to acquire the
                        // bias in the current epoch. In other words, we allow transfer of
                        // the bias from one thread to another directly in this situation.
                        Word biasedMark = prototypeMarkWord.or(thread);
                        trace(trace, "       biasedMark: 0x%016lx\n", biasedMark.toLong());
                        if (compareAndSwap(object, markOffset(), mark, biasedMark) == mark) {
                            // Object is now biased to current thread -> done
                            trace(trace, "+lock{bias:transfer}", object);
                            return;
                        }
                        // If the biasing toward our thread failed, then another thread
                        // succeeded in biasing it toward itself and we need to revoke that
                        // bias. The revocation will occur in the runtime in the slow case.
                        trace(trace, "+lock{stub:epoch-expired}", object);
                        MonitorEnterStubCall.call(object, lock);
                        return;
                    }
                } else {
                    // The prototype mark word doesn't have the bias bit set any
                    // more, indicating that objects of this data type are not supposed
                    // to be biased any more. We are going to try to reset the mark of
                    // this object to the prototype value and fall through to the
                    // CAS-based locking scheme. Note that if our CAS fails, it means
                    // that another thread raced us for the privilege of revoking the
                    // bias of this particular object, so it's okay to continue in the
                    // normal locking code.
                    Word result = compareAndSwap(object, markOffset(), mark, prototypeMarkWord);

                    // Fall through to the normal CAS-based lock, because no matter what
                    // the result of the above CAS, some thread must have succeeded in
                    // removing the bias bit from the object's header.

                    if (ENABLE_BREAKPOINT) {
                        bkpt(object, mark, tmp, result);
                    }
                }
            }
        }

        // Create the unlocked mark word pattern
        Word unlockedMark = mark.or(unlockedMask());
        trace(trace, "     unlockedMark: 0x%016lx\n", unlockedMark.toLong());

        // Copy this unlocked mark word into the lock slot on the stack
        storeWord(lock, lockDisplacedMarkOffset(), 0, unlockedMark);

        // Test if the object's mark word is unlocked, and if so, store the
        // (address of) the lock slot into the object's mark word.
        Word currentMark = compareAndSwap(object, markOffset(), unlockedMark, lock);
        if (currentMark != unlockedMark) {
            trace(trace, "      currentMark: 0x%016lx\n", currentMark.toLong());
            // The mark word in the object header was not the same.
            // Either the object is locked by another thread or is already locked
            // by the current thread. The latter is true if the mark word
            // is a stack pointer into the current thread's stack, i.e.:
            //
            //   1) (currentMark & aligned_mask) == 0
            //   2)  rsp <= currentMark
            //   3)  currentMark <= rsp + page_size
            //
            // These 3 tests can be done by evaluating the following expression:
            //
            //   (currentMark - rsp) & (aligned_mask - page_size)
            //
            // assuming both the stack pointer and page_size have their least
            // significant 2 bits cleared and page_size is a power of 2
            final Word alignedMask = Word.fromInt(wordSize() - 1);
            final Word stackPointer = stackPointer();
            if (currentMark.minus(stackPointer).and(alignedMask.minus(pageSize())) != Word.zero()) {
                // Most likely not a recursive lock, go into a slow runtime call
                trace(trace, "+lock{stub:failed-cas}", object);
                MonitorEnterStubCall.call(object, lock);
                return;
            } else {
                // Recursively locked => write 0 to the lock slot
                storeWord(lock, lockDisplacedMarkOffset(), 0, Word.zero());
                trace(trace, "+lock{recursive}", object);
            }
        } else {
            trace(trace, "+lock{cas}", object);
        }
    }

    @Snippet
    public static void monitorenterEliminated() {
        incCounter();
        beginLockScope(true, wordKind());
    }

    /**
     * Calls straight out to the monitorenter stub.
     */
    @Snippet
    public static void monitorenterStub(@Parameter("object") Object object, @ConstantParameter("checkNull") boolean checkNull, @ConstantParameter("trace") boolean trace) {
        verifyOop(object);
        incCounter();
        if (checkNull && object == null) {
            DeoptimizeNode.deopt(DeoptimizationAction.InvalidateReprofile, DeoptimizationReason.NullCheckException);
        }
        // BeginLockScope nodes do not read from object so a use of object
        // cannot float about the null check above
        final Word lock = beginLockScope(false, wordKind());
        trace(trace, "+lock{stub}", object);
        MonitorEnterStubCall.call(object, lock);
    }

    @Snippet
    public static void monitorexit(@Parameter("object") Object object, @ConstantParameter("trace") boolean trace) {
        trace(trace, "           object: 0x%016lx\n", Word.fromObject(object).toLong());
        if (useBiasedLocking()) {
            // Check for biased locking unlock case, which is a no-op
            // Note: we do not have to check the thread ID for two reasons.
            // First, the interpreter checks for IllegalMonitorStateException at
            // a higher level. Second, if the bias was revoked while we held the
            // lock, the object could not be rebiased toward another thread, so
            // the bias bit would be clear.
            final Word mark = loadWordFromObject(object, markOffset());
            trace(trace, "             mark: 0x%016lx\n", mark.toLong());
            if (mark.and(biasedLockMaskInPlace()).toLong() == biasedLockPattern()) {
                endLockScope();
                decCounter();
                trace(trace, "-lock{bias}", object);
                return;
            }
        }

        final Word lock = CurrentLockNode.currentLock(wordKind());

        // Load displaced mark
        final Word displacedMark = loadWordFromWord(lock, lockDisplacedMarkOffset());
        trace(trace, "    displacedMark: 0x%016lx\n", displacedMark.toLong());

        if (displacedMark == Word.zero()) {
            // Recursive locking => done
            trace(trace, "-lock{recursive}", object);
        } else {
            verifyOop(object);
            // Test if object's mark word is pointing to the displaced mark word, and if so, restore
            // the displaced mark in the object - if the object's mark word is not pointing to
            // the displaced mark word, do unlocking via runtime call.
            if (DirectCompareAndSwapNode.compareAndSwap(object, markOffset(), lock, displacedMark) != lock) {
              // The object's mark word was not pointing to the displaced header,
              // we do unlocking via runtime call.
                trace(trace, "-lock{stub}", object);
                MonitorExitStubCall.call(object);
            } else {
                trace(trace, "-lock{cas}", object);
            }
        }
        endLockScope();
        decCounter();
    }

    /**
     * Calls straight out to the monitorexit stub.
     */
    @Snippet
    public static void monitorexitStub(@Parameter("object") Object object, @ConstantParameter("trace") boolean trace) {
        verifyOop(object);
        trace(trace, "-lock{stub}", object);
        MonitorExitStubCall.call(object);
        endLockScope();
        decCounter();
    }

    @Snippet
    public static void monitorexitEliminated() {
        endLockScope();
        decCounter();
    }

    private static void trace(boolean enabled, String action, Object object) {
        if (enabled) {
            Log.print(action);
            Log.print(' ');
            Log.printlnObject(object);
        }
    }

    private static void trace(boolean enabled, String format, long value) {
        if (enabled) {
            Log.printf(format, value);
        }
    }

    /**
     * Leaving the breakpoint code in to provide an example of how to use the {@link BreakpointNode} intrinsic.
     */
    private static final boolean ENABLE_BREAKPOINT = false;

    @NodeIntrinsic(BreakpointNode.class)
    static native void bkpt(Object object, Word mark, Word tmp, Word value);

    private static void incCounter() {
        if (CHECK_BALANCED_MONITORS) {
            final Word counter = MonitorCounterNode.counter(wordKind());
            final int count = UnsafeLoadNode.load(counter, 0, 0, Kind.Int);
            DirectObjectStoreNode.storeInt(counter, 0, 0, count + 1);
        }
    }

    private static void decCounter() {
        if (CHECK_BALANCED_MONITORS) {
            final Word counter = MonitorCounterNode.counter(wordKind());
            final int count = UnsafeLoadNode.load(counter, 0, 0, Kind.Int);
            DirectObjectStoreNode.storeInt(counter, 0, 0, count - 1);
        }
    }

    @Snippet
    private static void initCounter() {
        final Word counter = MonitorCounterNode.counter(wordKind());
        DirectObjectStoreNode.storeInt(counter, 0, 0, 0);
    }

    @Snippet
    private static void checkCounter(String errMsg) {
        final Word counter = MonitorCounterNode.counter(wordKind());
        final int count = UnsafeLoadNode.load(counter, 0, 0, Kind.Int);
        if (count != 0) {
            vmError(errMsg, count);
        }
    }

    public static class Templates extends AbstractTemplates<MonitorSnippets> {

        private final ResolvedJavaMethod monitorenter;
        private final ResolvedJavaMethod monitorexit;
        private final ResolvedJavaMethod monitorenterStub;
        private final ResolvedJavaMethod monitorexitStub;
        private final ResolvedJavaMethod monitorenterEliminated;
        private final ResolvedJavaMethod monitorexitEliminated;
        private final ResolvedJavaMethod initCounter;
        private final ResolvedJavaMethod checkCounter;
        private final boolean useFastLocking;

        public Templates(CodeCacheProvider runtime, boolean useFastLocking) {
            super(runtime, MonitorSnippets.class);
            monitorenter = snippet("monitorenter", Object.class, boolean.class);
            monitorexit = snippet("monitorexit", Object.class, boolean.class);
            monitorenterStub = snippet("monitorenterStub", Object.class, boolean.class, boolean.class);
            monitorexitStub = snippet("monitorexitStub", Object.class, boolean.class);
            monitorenterEliminated = snippet("monitorenterEliminated");
            monitorexitEliminated = snippet("monitorexitEliminated");
            initCounter = snippet("initCounter");
            checkCounter = snippet("checkCounter", String.class);
            this.useFastLocking = useFastLocking;
        }

        public void lower(MonitorEnterNode monitorenterNode, @SuppressWarnings("unused") LoweringTool tool) {
            StructuredGraph graph = (StructuredGraph) monitorenterNode.graph();

            checkBalancedMonitors(graph);

            FrameState stateAfter = monitorenterNode.stateAfter();
            boolean eliminated = monitorenterNode.eliminated();
            ResolvedJavaMethod method = eliminated ? monitorenterEliminated : useFastLocking ? monitorenter : monitorenterStub;
            boolean checkNull = !monitorenterNode.object().stamp().nonNull();
            Key key = new Key(method);
            if (method == monitorenterStub) {
                key.add("checkNull", checkNull);
            }
            if (!eliminated) {
                key.add("trace", isTracingEnabledForType(monitorenterNode.object()) ||
                                 isTracingEnabledForMethod(stateAfter.method()) ||
                                 isTracingEnabledForMethod(graph.method()));
            }

            Arguments arguments = new Arguments();
            if (!eliminated) {
                arguments.add("object", monitorenterNode.object());
            }
            SnippetTemplate template = cache.get(key);
            Map<Node, Node> nodes = template.instantiate(runtime, monitorenterNode, arguments);
            for (Node n : nodes.values()) {
                if (n instanceof BeginLockScopeNode) {
                    BeginLockScopeNode begin = (BeginLockScopeNode) n;
                    begin.setStateAfter(stateAfter);
                }
            }
        }

        public void lower(MonitorExitNode monitorexitNode, @SuppressWarnings("unused") LoweringTool tool) {
            StructuredGraph graph = (StructuredGraph) monitorexitNode.graph();
            FrameState stateAfter = monitorexitNode.stateAfter();
            boolean eliminated = monitorexitNode.eliminated();
            ResolvedJavaMethod method = eliminated ? monitorexitEliminated : useFastLocking ? monitorexit : monitorexitStub;
            Key key = new Key(method);
            if (!eliminated) {
                key.add("trace", isTracingEnabledForType(monitorexitNode.object()) ||
                                 isTracingEnabledForMethod(stateAfter.method()) ||
                                 isTracingEnabledForMethod(graph.method()));
            }
            Arguments arguments = new Arguments();
            if (!eliminated) {
                arguments.add("object", monitorexitNode.object());
            }
            SnippetTemplate template = cache.get(key);
            Map<Node, Node> nodes = template.instantiate(runtime, monitorexitNode, arguments);
            for (Node n : nodes.values()) {
                if (n instanceof EndLockScopeNode) {
                    EndLockScopeNode end = (EndLockScopeNode) n;
                    end.setStateAfter(stateAfter);
                }
            }
        }

        static boolean isTracingEnabledForType(ValueNode object) {
            ResolvedJavaType type = object.objectStamp().type();
            if (TRACE_TYPE_FILTER == null) {
                return false;
            } else {
                if (TRACE_TYPE_FILTER.length() == 0) {
                    return true;
                }
                if (type == null) {
                    return false;
                }
                return (type.name().contains(TRACE_TYPE_FILTER));
            }
        }

        static boolean isTracingEnabledForMethod(ResolvedJavaMethod method) {
            if (TRACE_METHOD_FILTER == null) {
                return false;
            } else {
                if (TRACE_METHOD_FILTER.length() == 0) {
                    return true;
                }
                if (method == null) {
                    return false;
                }
                return (MetaUtil.format("%H.%n", method).contains(TRACE_METHOD_FILTER));
            }
        }

        /**
         * If balanced monitor checking is enabled then nodes are inserted at the start and
         * all return points of the graph to initialize and check the monitor counter
         * respectively.
         */
        private void checkBalancedMonitors(StructuredGraph graph) {
            if (CHECK_BALANCED_MONITORS) {
                NodeIterable<MonitorCounterNode> nodes = graph.getNodes().filter(MonitorCounterNode.class);
                if (nodes.isEmpty()) {
                    // Only insert the nodes if this is the first monitorenter being lowered.
                    JavaType returnType = initCounter.signature().returnType(initCounter.holder());
                    MethodCallTargetNode callTarget = graph.add(new MethodCallTargetNode(InvokeKind.Static, initCounter, new ValueNode[0], returnType));
                    InvokeNode invoke = graph.add(new InvokeNode(callTarget, 0, -1));
                    invoke.setStateAfter(graph.start().stateAfter());
                    graph.addAfterFixed(graph.start(), invoke);
                    StructuredGraph inlineeGraph = (StructuredGraph) initCounter.compilerStorage().get(Graph.class);
                    InliningUtil.inline(invoke, inlineeGraph, false);

                    List<ReturnNode> rets = graph.getNodes().filter(ReturnNode.class).snapshot();
                    for (ReturnNode ret : rets) {
                        returnType = checkCounter.signature().returnType(checkCounter.holder());
                        ConstantNode errMsg = ConstantNode.forObject("unbalanced monitors in " + MetaUtil.format("%H.%n(%p)", graph.method()) + ", count = %d", runtime, graph);
                        callTarget = graph.add(new MethodCallTargetNode(InvokeKind.Static, checkCounter, new ValueNode[] {errMsg}, returnType));
                        invoke = graph.add(new InvokeNode(callTarget, 0, -1));
                        List<ValueNode> stack = Collections.emptyList();
                        FrameState stateAfter = new FrameState(graph.method(), FrameState.AFTER_BCI, new ValueNode[0], stack, new ValueNode[0], false, false, null);
                        invoke.setStateAfter(graph.add(stateAfter));
                        graph.addBeforeFixed(ret, invoke);
                        inlineeGraph = (StructuredGraph) checkCounter.compilerStorage().get(Graph.class);
                        InliningUtil.inline(invoke, inlineeGraph, false);
                    }
                }
            }
        }
    }
}
