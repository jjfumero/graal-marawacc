/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.replacements;

import static com.oracle.graal.java.BytecodeParser.Options.*;
import static jdk.internal.jvmci.common.JVMCIError.*;

import java.util.*;

import jdk.internal.jvmci.code.*;

import com.oracle.graal.debug.*;

import jdk.internal.jvmci.meta.*;
import jdk.internal.jvmci.options.*;

import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.graphbuilderconf.*;
import com.oracle.graal.graphbuilderconf.InlineInvokePlugin.InlineInfo;
import com.oracle.graal.graphbuilderconf.InvocationPlugins.InvocationPluginReceiver;
import com.oracle.graal.java.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.CallTargetNode.InvokeKind;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.util.GraphUtil;
import com.oracle.graal.phases.common.inlining.*;

/**
 * A graph decoder that performs partial evaluation, i.e., that performs method inlining and
 * canonicalization/simplification of nodes during decoding.
 *
 * Inlining and loop explosion are configured via the plugin mechanism also used by the
 * {@link GraphBuilderPhase}. However, not all callback methods defined in
 * {@link GraphBuilderContext} are available since decoding is more limited than graph building.
 *
 * The standard {@link Canonicalizable#canonical node canonicalization} interface is used to
 * canonicalize nodes during decoding. Additionally, {@link IfNode branches} and
 * {@link IntegerSwitchNode switches} with constant conditions are simplified.
 */
public abstract class PEGraphDecoder extends SimplifyingGraphDecoder {

    public static class Options {
        @Option(help = "Maximum inlining depth during partial evaluation before reporting an infinite recursion")//
        public static final OptionValue<Integer> InliningDepthError = new OptionValue<>(200);
    }

    protected class PEMethodScope extends MethodScope {
        /** The state of the caller method. Only non-null during method inlining. */
        protected final PEMethodScope caller;
        protected final LoopScope callerLoopScope;
        protected final ResolvedJavaMethod method;
        protected final InvokeData invokeData;
        protected final int inliningDepth;

        protected final LoopExplosionPlugin loopExplosionPlugin;
        protected final InvocationPlugins invocationPlugins;
        protected final InlineInvokePlugin[] inlineInvokePlugins;
        protected final ParameterPlugin parameterPlugin;
        protected final ValueNode[] arguments;

        protected FrameState outerState;
        protected FrameState exceptionState;
        protected ExceptionPlaceholderNode exceptionPlaceholderNode;
        protected BytecodePosition bytecodePosition;

        protected PEMethodScope(StructuredGraph targetGraph, PEMethodScope caller, LoopScope callerLoopScope, EncodedGraph encodedGraph, ResolvedJavaMethod method, InvokeData invokeData,
                        int inliningDepth, LoopExplosionPlugin loopExplosionPlugin, InvocationPlugins invocationPlugins, InlineInvokePlugin[] inlineInvokePlugins, ParameterPlugin parameterPlugin,
                        ValueNode[] arguments) {
            super(targetGraph, encodedGraph, loopExplosionKind(method, loopExplosionPlugin));

            this.caller = caller;
            this.callerLoopScope = callerLoopScope;
            this.method = method;
            this.invokeData = invokeData;
            this.inliningDepth = inliningDepth;
            this.loopExplosionPlugin = loopExplosionPlugin;
            this.invocationPlugins = invocationPlugins;
            this.inlineInvokePlugins = inlineInvokePlugins;
            this.parameterPlugin = parameterPlugin;
            this.arguments = arguments;
        }

        public boolean isInlinedMethod() {
            return caller != null;
        }

        public BytecodePosition getBytecodePosition() {
            if (bytecodePosition == null) {
                ensureOuterStateDecoded(this);
                ensureExceptionStateDecoded(this);
                bytecodePosition = new BytecodePosition(FrameState.toBytecodePosition(outerState), method, invokeData.invoke.bci());
            }
            return bytecodePosition;
        }
    }

    protected class PENonAppendGraphBuilderContext implements GraphBuilderContext {
        protected final PEMethodScope methodScope;
        protected final Invoke invoke;

        public PENonAppendGraphBuilderContext(PEMethodScope methodScope, Invoke invoke) {
            this.methodScope = methodScope;
            this.invoke = invoke;
        }

        @Override
        public BailoutException bailout(String string) {
            BailoutException bailout = new BailoutException(string);
            throw GraphUtil.createBailoutException(string, bailout, GraphUtil.approxSourceStackTraceElement(methodScope.getBytecodePosition()));
        }

        @Override
        public StampProvider getStampProvider() {
            return stampProvider;
        }

        @Override
        public MetaAccessProvider getMetaAccess() {
            return metaAccess;
        }

        @Override
        public ConstantReflectionProvider getConstantReflection() {
            return constantReflection;
        }

        @Override
        public StructuredGraph getGraph() {
            return methodScope.graph;
        }

        @Override
        public int getDepth() {
            return methodScope.inliningDepth;
        }

        @Override
        public IntrinsicContext getIntrinsic() {
            return null;
        }

        @Override
        public <T extends ValueNode> T append(T value) {
            throw unimplemented();
        }

        @Override
        public <T extends ValueNode> T recursiveAppend(T value) {
            throw unimplemented();
        }

        @Override
        public void push(JavaKind kind, ValueNode value) {
            throw unimplemented();
        }

        @Override
        public void handleReplacedInvoke(InvokeKind invokeKind, ResolvedJavaMethod targetMethod, ValueNode[] args, boolean inlineEverything) {
            throw unimplemented();
        }

        @Override
        public void intrinsify(ResolvedJavaMethod targetMethod, ResolvedJavaMethod substitute, ValueNode[] args) {
            throw unimplemented();
        }

        @Override
        public void setStateAfter(StateSplit stateSplit) {
            throw unimplemented();
        }

        @Override
        public GraphBuilderContext getParent() {
            throw unimplemented();
        }

        @Override
        public ResolvedJavaMethod getMethod() {
            throw unimplemented();
        }

        @Override
        public int bci() {
            return invoke.bci();
        }

        @Override
        public InvokeKind getInvokeKind() {
            throw unimplemented();
        }

        @Override
        public JavaType getInvokeReturnType() {
            throw unimplemented();
        }
    }

    protected class PEAppendGraphBuilderContext extends PENonAppendGraphBuilderContext {
        protected FixedWithNextNode lastInstr;
        protected ValueNode pushedNode;

        public PEAppendGraphBuilderContext(PEMethodScope inlineScope, FixedWithNextNode lastInstr) {
            super(inlineScope, inlineScope.invokeData.invoke);
            this.lastInstr = lastInstr;
        }

        @Override
        public void push(JavaKind kind, ValueNode value) {
            if (pushedNode != null) {
                throw unimplemented("Only one push is supported");
            }
            pushedNode = value;
        }

        @Override
        public void setStateAfter(StateSplit stateSplit) {
            Node stateAfter = decodeFloatingNode(methodScope.caller, methodScope.callerLoopScope, methodScope.invokeData.stateAfterOrderId);
            getGraph().add(stateAfter);
            FrameState fs = (FrameState) handleFloatingNodeAfterAdd(methodScope.caller, methodScope.callerLoopScope, stateAfter);
            stateSplit.setStateAfter(fs);
        }

        @Override
        public <T extends ValueNode> T append(T v) {
            if (v.graph() != null) {
                return v;
            }
            T added = getGraph().addOrUnique(v);
            if (added == v) {
                updateLastInstruction(v);
            }
            return added;
        }

        @Override
        public <T extends ValueNode> T recursiveAppend(T v) {
            if (v.graph() != null) {
                return v;
            }
            T added = getGraph().addOrUniqueWithInputs(v);
            if (added == v) {
                updateLastInstruction(v);
            }
            return added;
        }

        private <T extends ValueNode> void updateLastInstruction(T v) {
            if (v instanceof FixedNode) {
                FixedNode fixedNode = (FixedNode) v;
                lastInstr.setNext(fixedNode);
                if (fixedNode instanceof FixedWithNextNode) {
                    FixedWithNextNode fixedWithNextNode = (FixedWithNextNode) fixedNode;
                    assert fixedWithNextNode.next() == null : "cannot append instruction to instruction which isn't end";
                    lastInstr = fixedWithNextNode;
                } else {
                    lastInstr = null;
                }
            }
        }
    }

    @NodeInfo
    static class ExceptionPlaceholderNode extends ValueNode {
        public static final NodeClass<ExceptionPlaceholderNode> TYPE = NodeClass.create(ExceptionPlaceholderNode.class);

        public ExceptionPlaceholderNode() {
            super(TYPE, StampFactory.object());
        }
    }

    public PEGraphDecoder(MetaAccessProvider metaAccess, ConstantReflectionProvider constantReflection, StampProvider stampProvider, Architecture architecture) {
        super(metaAccess, constantReflection, stampProvider, true, architecture);
    }

    protected static LoopExplosionKind loopExplosionKind(ResolvedJavaMethod method, LoopExplosionPlugin loopExplosionPlugin) {
        if (loopExplosionPlugin == null) {
            return LoopExplosionKind.NONE;
        } else if (loopExplosionPlugin.shouldMergeExplosions(method)) {
            return LoopExplosionKind.MERGE_EXPLODE;
        } else if (loopExplosionPlugin.shouldExplodeLoops(method)) {
            return LoopExplosionKind.FULL_EXPLODE;
        } else {
            return LoopExplosionKind.NONE;
        }
    }

    public void decode(StructuredGraph targetGraph, ResolvedJavaMethod method, LoopExplosionPlugin loopExplosionPlugin, InvocationPlugins invocationPlugins, InlineInvokePlugin[] inlineInvokePlugins,
                    ParameterPlugin parameterPlugin) {
        PEMethodScope methodScope = new PEMethodScope(targetGraph, null, null, lookupEncodedGraph(method, false), method, null, 0, loopExplosionPlugin, invocationPlugins, inlineInvokePlugins,
                        parameterPlugin, null);
        decode(methodScope, null);
        cleanupGraph(methodScope, null);
        methodScope.graph.verify();
    }

    @Override
    protected void checkLoopExplosionIteration(MethodScope s, LoopScope loopScope) {
        PEMethodScope methodScope = (PEMethodScope) s;

        if (loopScope.loopIteration > MaximumLoopExplosionCount.getValue()) {
            throw tooManyLoopExplosionIterations(methodScope);
        }
    }

    private static RuntimeException tooManyLoopExplosionIterations(PEMethodScope methodScope) {
        String message = "too many loop explosion iterations - does the explosion not terminate for method " + methodScope.method + "?";
        RuntimeException bailout = FailedLoopExplosionIsFatal.getValue() ? new RuntimeException(message) : new BailoutException(message);
        throw GraphUtil.createBailoutException(message, bailout, GraphUtil.approxSourceStackTraceElement(methodScope.getBytecodePosition()));
    }

    @Override
    protected void handleInvoke(MethodScope s, LoopScope loopScope, InvokeData invokeData) {
        PEMethodScope methodScope = (PEMethodScope) s;

        /*
         * Decode the call target, but do not add it to the graph yet. This avoids adding usages for
         * all the arguments, which are expensive to remove again when we can inline the method.
         */
        assert invokeData.invoke.callTarget() == null : "callTarget edge is ignored during decoding of Invoke";
        CallTargetNode callTarget = (CallTargetNode) decodeFloatingNode(methodScope, loopScope, invokeData.callTargetOrderId);
        if (!(callTarget instanceof MethodCallTargetNode) || !trySimplifyInvoke(methodScope, loopScope, invokeData, (MethodCallTargetNode) callTarget)) {

            /* We know that we need an invoke, so now we can add the call target to the graph. */
            methodScope.graph.add(callTarget);
            registerNode(loopScope, invokeData.callTargetOrderId, callTarget, false, false);
            super.handleInvoke(methodScope, loopScope, invokeData);
        }
    }

    protected boolean trySimplifyInvoke(PEMethodScope methodScope, LoopScope loopScope, InvokeData invokeData, MethodCallTargetNode callTarget) {
        // attempt to devirtualize the call
        ResolvedJavaMethod specialCallTarget = MethodCallTargetNode.findSpecialCallTarget(callTarget.invokeKind(), callTarget.receiver(), callTarget.targetMethod(), invokeData.contextType);
        if (specialCallTarget != null) {
            callTarget.setTargetMethod(specialCallTarget);
            callTarget.setInvokeKind(InvokeKind.Special);
        }

        if (tryInvocationPlugin(methodScope, loopScope, invokeData, callTarget)) {
            return true;
        }
        if (tryInline(methodScope, loopScope, invokeData, callTarget)) {
            return true;
        }

        for (InlineInvokePlugin plugin : methodScope.inlineInvokePlugins) {
            plugin.notifyNotInlined(new PENonAppendGraphBuilderContext(methodScope, invokeData.invoke), callTarget.targetMethod(), invokeData.invoke);
        }
        return false;
    }

    protected boolean tryInvocationPlugin(PEMethodScope methodScope, LoopScope loopScope, InvokeData invokeData, MethodCallTargetNode callTarget) {
        if (methodScope.invocationPlugins == null) {
            return false;
        }

        Invoke invoke = invokeData.invoke;

        ResolvedJavaMethod targetMethod = callTarget.targetMethod();
        InvocationPlugin invocationPlugin = methodScope.invocationPlugins.lookupInvocation(targetMethod);
        if (invocationPlugin == null) {
            return false;
        }

        ValueNode[] arguments = callTarget.arguments().toArray(new ValueNode[0]);
        FixedWithNextNode invokePredecessor = (FixedWithNextNode) invoke.asNode().predecessor();

        /* Remove invoke from graph so that invocation plugin can append nodes to the predecessor. */
        invoke.asNode().replaceAtPredecessor(null);

        PEMethodScope inlineScope = new PEMethodScope(methodScope.graph, methodScope, loopScope, null, targetMethod, invokeData, methodScope.inliningDepth + 1, methodScope.loopExplosionPlugin,
                        methodScope.invocationPlugins, methodScope.inlineInvokePlugins, null, arguments);
        PEAppendGraphBuilderContext graphBuilderContext = new PEAppendGraphBuilderContext(inlineScope, invokePredecessor);
        InvocationPluginReceiver invocationPluginReceiver = new InvocationPluginReceiver(graphBuilderContext);

        if (invocationPlugin.execute(graphBuilderContext, targetMethod, invocationPluginReceiver.init(targetMethod, arguments), arguments)) {

            if (graphBuilderContext.lastInstr != null) {
                registerNode(loopScope, invokeData.invokeOrderId, graphBuilderContext.pushedNode, true, true);
                invoke.asNode().replaceAtUsages(graphBuilderContext.pushedNode);
                graphBuilderContext.lastInstr.setNext(nodeAfterInvoke(methodScope, loopScope, invokeData, AbstractBeginNode.prevBegin(graphBuilderContext.lastInstr)));
            } else {
                assert graphBuilderContext.pushedNode == null : "Why push a node when the invoke does not return anyway?";
                invoke.asNode().replaceAtUsages(null);
            }

            deleteInvoke(invoke);
            return true;

        } else {
            /* Intrinsification failed, restore original state: invoke is in Graph. */
            invokePredecessor.setNext(invoke.asNode());
            return false;
        }
    }

    protected boolean tryInline(PEMethodScope methodScope, LoopScope loopScope, InvokeData invokeData, MethodCallTargetNode callTarget) {
        if (!callTarget.invokeKind().isDirect()) {
            return false;
        }

        ResolvedJavaMethod targetMethod = callTarget.targetMethod();
        if (!targetMethod.canBeInlined()) {
            return false;
        }

        ValueNode[] arguments = callTarget.arguments().toArray(new ValueNode[0]);
        GraphBuilderContext graphBuilderContext = new PENonAppendGraphBuilderContext(methodScope, invokeData.invoke);

        for (InlineInvokePlugin plugin : methodScope.inlineInvokePlugins) {
            InlineInfo inlineInfo = plugin.shouldInlineInvoke(graphBuilderContext, targetMethod, arguments, callTarget.returnType());
            if (inlineInfo != null) {
                if (inlineInfo.getMethodToInline() == null) {
                    return false;
                } else {
                    return doInline(methodScope, loopScope, invokeData, inlineInfo, arguments);
                }
            }
        }
        return false;
    }

    protected boolean doInline(PEMethodScope methodScope, LoopScope loopScope, InvokeData invokeData, InlineInfo inlineInfo, ValueNode[] arguments) {
        ResolvedJavaMethod inlineMethod = inlineInfo.getMethodToInline();
        EncodedGraph graphToInline = lookupEncodedGraph(inlineMethod, inlineInfo.isIntrinsic());
        if (graphToInline == null) {
            return false;
        }

        if (methodScope.inliningDepth > Options.InliningDepthError.getValue()) {
            throw tooDeepInlining(methodScope);
        }

        for (InlineInvokePlugin plugin : methodScope.inlineInvokePlugins) {
            plugin.notifyBeforeInline(inlineMethod);
        }

        Invoke invoke = invokeData.invoke;
        FixedNode invokeNode = invoke.asNode();
        FixedWithNextNode predecessor = (FixedWithNextNode) invokeNode.predecessor();
        invokeNode.replaceAtPredecessor(null);

        PEMethodScope inlineScope = new PEMethodScope(methodScope.graph, methodScope, loopScope, graphToInline, inlineMethod, invokeData, methodScope.inliningDepth + 1,
                        methodScope.loopExplosionPlugin, methodScope.invocationPlugins, methodScope.inlineInvokePlugins, null, arguments);
        /* Do the actual inlining by decoding the inlineMethod */
        decode(inlineScope, predecessor);

        ValueNode exceptionValue = null;
        if (inlineScope.unwindNode != null) {
            exceptionValue = inlineScope.unwindNode.exception();
        }
        UnwindNode unwindNode = inlineScope.unwindNode;

        if (invoke instanceof InvokeWithExceptionNode) {
            InvokeWithExceptionNode invokeWithException = ((InvokeWithExceptionNode) invoke);
            assert invokeWithException.next() == null;
            assert invokeWithException.exceptionEdge() == null;

            if (unwindNode != null) {
                assert unwindNode.predecessor() != null;
                Node n = makeStubNode(methodScope, loopScope, invokeData.exceptionNextOrderId);
                unwindNode.replaceAndDelete(n);
            }

        } else {
            if (unwindNode != null && !unwindNode.isDeleted()) {
                DeoptimizeNode deoptimizeNode = methodScope.graph.add(new DeoptimizeNode(DeoptimizationAction.InvalidateRecompile, DeoptimizationReason.NotCompiledExceptionHandler));
                unwindNode.replaceAndDelete(deoptimizeNode);
            }
        }

        assert invoke.next() == null;

        ValueNode returnValue;
        List<ReturnNode> returnNodes = inlineScope.returnNodes;
        if (!returnNodes.isEmpty()) {
            if (returnNodes.size() == 1) {
                ReturnNode returnNode = returnNodes.get(0);
                returnValue = returnNode.result();
                FixedNode n = nodeAfterInvoke(methodScope, loopScope, invokeData, AbstractBeginNode.prevBegin(returnNode));
                returnNode.replaceAndDelete(n);
            } else {
                AbstractMergeNode merge = methodScope.graph.add(new MergeNode());
                merge.setStateAfter((FrameState) ensureNodeCreated(methodScope, loopScope, invokeData.stateAfterOrderId));
                returnValue = InliningUtil.mergeReturns(merge, returnNodes, null);
                FixedNode n = nodeAfterInvoke(methodScope, loopScope, invokeData, merge);
                merge.setNext(n);
            }
        } else {
            returnValue = null;
        }
        invokeNode.replaceAtUsages(returnValue);

        /*
         * Usage the handles that we have on the return value and the exception to update the
         * orderId->Node table.
         */
        registerNode(loopScope, invokeData.invokeOrderId, returnValue, true, true);
        if (invoke instanceof InvokeWithExceptionNode) {
            registerNode(loopScope, invokeData.exceptionOrderId, exceptionValue, true, true);
        }
        if (inlineScope.exceptionPlaceholderNode != null) {
            inlineScope.exceptionPlaceholderNode.replaceAtUsages(exceptionValue);
            inlineScope.exceptionPlaceholderNode.safeDelete();
        }
        deleteInvoke(invoke);

        for (InlineInvokePlugin plugin : methodScope.inlineInvokePlugins) {
            plugin.notifyAfterInline(inlineMethod);
        }

        if (Debug.isDumpEnabled() && DumpDuringGraphBuilding.getValue()) {
            Debug.dump(methodScope.graph, "Inline finished: " + inlineMethod.getDeclaringClass().getUnqualifiedName() + "." + inlineMethod.getName());
        }
        return true;
    }

    private static RuntimeException tooDeepInlining(PEMethodScope methodScope) {
        HashMap<ResolvedJavaMethod, Integer> methodCounts = new HashMap<>();
        for (PEMethodScope cur = methodScope; cur != null; cur = cur.caller) {
            Integer oldCount = methodCounts.get(cur.method);
            methodCounts.put(cur.method, oldCount == null ? 1 : oldCount + 1);
        }

        List<Map.Entry<ResolvedJavaMethod, Integer>> methods = new ArrayList<>(methodCounts.entrySet());
        methods.sort((e1, e2) -> -Integer.compare(e1.getValue(), e2.getValue()));

        StringBuilder msg = new StringBuilder("Too deep inlining, probably caused by recursive inlining. Inlined methods ordered by inlining frequency:");
        for (Map.Entry<ResolvedJavaMethod, Integer> entry : methods) {
            msg.append(System.lineSeparator()).append(entry.getKey().format("%H.%n(%p) [")).append(entry.getValue()).append("]");
        }
        throw new BailoutException(msg.toString());
    }

    public FixedNode nodeAfterInvoke(PEMethodScope methodScope, LoopScope loopScope, InvokeData invokeData, AbstractBeginNode lastBlock) {
        assert lastBlock.isAlive();
        FixedNode n;
        if (invokeData.invoke instanceof InvokeWithExceptionNode) {
            registerNode(loopScope, invokeData.nextOrderId, lastBlock, false, false);
            n = makeStubNode(methodScope, loopScope, invokeData.nextNextOrderId);
        } else {
            n = makeStubNode(methodScope, loopScope, invokeData.nextOrderId);
        }
        return n;
    }

    private static void deleteInvoke(Invoke invoke) {
        /*
         * Clean up unused nodes. We cannot just call killCFG on the invoke node because that can
         * kill too much: nodes that are decoded later can use values that appear unused by now.
         */
        FrameState frameState = invoke.stateAfter();
        invoke.asNode().safeDelete();
        assert invoke.callTarget() == null : "must not have been added to the graph yet";
        if (frameState != null && frameState.hasNoUsages()) {
            frameState.safeDelete();
        }
    }

    protected abstract EncodedGraph lookupEncodedGraph(ResolvedJavaMethod method, boolean isIntrinsic);

    @Override
    protected void handleFixedNode(MethodScope s, LoopScope loopScope, int nodeOrderId, FixedNode node) {
        PEMethodScope methodScope = (PEMethodScope) s;
        if (node instanceof SimpleInfopointNode && methodScope.isInlinedMethod()) {
            InliningUtil.addSimpleInfopointCaller((SimpleInfopointNode) node, methodScope.getBytecodePosition());
        }
        super.handleFixedNode(s, loopScope, nodeOrderId, node);
    }

    @Override
    protected Node handleFloatingNodeBeforeAdd(MethodScope s, LoopScope loopScope, Node node) {
        PEMethodScope methodScope = (PEMethodScope) s;

        if (node instanceof ParameterNode) {
            if (methodScope.arguments != null) {
                Node result = methodScope.arguments[((ParameterNode) node).index()];
                assert result != null;
                return result;

            } else if (methodScope.parameterPlugin != null) {
                GraphBuilderContext graphBuilderContext = new PENonAppendGraphBuilderContext(methodScope, null);
                Node result = methodScope.parameterPlugin.interceptParameter(graphBuilderContext, ((ParameterNode) node).index(), ((ParameterNode) node).stamp());
                if (result != null) {
                    return result;
                }
            }

        }

        return super.handleFloatingNodeBeforeAdd(methodScope, loopScope, node);
    }

    protected void ensureOuterStateDecoded(PEMethodScope methodScope) {
        if (methodScope.outerState == null && methodScope.caller != null) {
            FrameState stateAtReturn = methodScope.invokeData.invoke.stateAfter();
            if (stateAtReturn == null) {
                stateAtReturn = (FrameState) decodeFloatingNode(methodScope.caller, methodScope.callerLoopScope, methodScope.invokeData.stateAfterOrderId);
            }

            JavaKind invokeReturnKind = methodScope.invokeData.invoke.asNode().getStackKind();
            FrameState outerState = stateAtReturn.duplicateModified(methodScope.graph, methodScope.invokeData.invoke.bci(), stateAtReturn.rethrowException(), true, invokeReturnKind, null, null);

            /*
             * When the encoded graph has methods inlining, we can already have a proper caller
             * state. If not, we set the caller state here.
             */
            if (outerState.outerFrameState() == null && methodScope.caller != null) {
                ensureOuterStateDecoded(methodScope.caller);
                outerState.setOuterFrameState(methodScope.caller.outerState);
            }
            methodScope.outerState = outerState;
        }
    }

    protected void ensureStateAfterDecoded(PEMethodScope methodScope) {
        if (methodScope.invokeData.invoke.stateAfter() == null) {
            methodScope.invokeData.invoke.setStateAfter((FrameState) ensureNodeCreated(methodScope.caller, methodScope.callerLoopScope, methodScope.invokeData.stateAfterOrderId));
        }
    }

    protected void ensureExceptionStateDecoded(PEMethodScope methodScope) {
        if (methodScope.exceptionState == null && methodScope.caller != null && methodScope.invokeData.invoke instanceof InvokeWithExceptionNode) {
            ensureStateAfterDecoded(methodScope);

            assert methodScope.exceptionPlaceholderNode == null;
            methodScope.exceptionPlaceholderNode = methodScope.graph.add(new ExceptionPlaceholderNode());
            registerNode(methodScope.callerLoopScope, methodScope.invokeData.exceptionOrderId, methodScope.exceptionPlaceholderNode, false, false);
            FrameState exceptionState = (FrameState) ensureNodeCreated(methodScope.caller, methodScope.callerLoopScope, methodScope.invokeData.exceptionStateOrderId);

            if (exceptionState.outerFrameState() == null && methodScope.caller != null) {
                ensureOuterStateDecoded(methodScope.caller);
                exceptionState.setOuterFrameState(methodScope.caller.outerState);
            }
            methodScope.exceptionState = exceptionState;
        }
    }

    @Override
    protected Node handleFloatingNodeAfterAdd(MethodScope s, LoopScope loopScope, Node node) {
        PEMethodScope methodScope = (PEMethodScope) s;

        if (methodScope.isInlinedMethod()) {
            if (node instanceof FrameState) {
                FrameState frameState = (FrameState) node;

                ensureOuterStateDecoded(methodScope);
                if (frameState.bci < 0) {
                    ensureExceptionStateDecoded(methodScope);
                }
                return InliningUtil.processFrameState(frameState, methodScope.invokeData.invoke, methodScope.method, methodScope.exceptionState, methodScope.outerState, true);

            } else if (node instanceof MonitorIdNode) {
                ensureOuterStateDecoded(methodScope);
                InliningUtil.processMonitorId(methodScope.outerState, (MonitorIdNode) node);
                return node;
            }
        }

        return node;
    }
}
