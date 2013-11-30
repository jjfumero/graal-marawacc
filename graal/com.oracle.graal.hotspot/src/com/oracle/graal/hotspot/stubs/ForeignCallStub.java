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
package com.oracle.graal.hotspot.stubs;

import static com.oracle.graal.api.code.CallingConvention.Type.*;
import static com.oracle.graal.api.meta.MetaUtil.*;
import static com.oracle.graal.hotspot.HotSpotForeignCallLinkage.RegisterEffect.*;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.HotSpotForeignCallLinkage.Transition;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.hotspot.replacements.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.java.MethodCallTargetNode.InvokeKind;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.replacements.*;
import com.oracle.graal.replacements.nodes.*;
import com.oracle.graal.word.*;
import com.oracle.graal.word.phases.*;

/**
 * A {@linkplain #getGraph() generated} stub for a {@link Transition non-leaf} foreign call from
 * compiled code. A stub is required for such calls as the caller may be scheduled for
 * deoptimization while the call is in progress. And since these are foreign/runtime calls on slow
 * paths, we don't want to force the register allocator to spill around the call. As such, this stub
 * saves and restores all allocatable registers. It also
 * {@linkplain StubUtil#handlePendingException(Word, boolean) handles} any exceptions raised during
 * the foreign call.
 */
public class ForeignCallStub extends Stub {

    /**
     * The target of the call.
     */
    private final HotSpotForeignCallLinkage target;

    /**
     * Specifies if the JavaThread value for the current thread is to be prepended to the arguments
     * for the call to {@link #target}.
     */
    protected final boolean prependThread;

    /**
     * Creates a stub for a call to code at a given address.
     * 
     * @param address the address of the code to call
     * @param descriptor the signature of the call to this stub
     * @param prependThread true if the JavaThread value for the current thread is to be prepended
     *            to the arguments for the call to {@code address}
     * @param reexecutable specifies if the stub call can be re-executed without (meaningful) side
     *            effects. Deoptimization will not return to a point before a stub call that cannot
     *            be re-executed.
     * @param killedLocations the memory locations killed by the stub call
     */
    public ForeignCallStub(HotSpotProviders providers, long address, ForeignCallDescriptor descriptor, boolean prependThread, Transition transition, boolean reexecutable,
                    LocationIdentity... killedLocations) {
        super(providers, HotSpotForeignCallLinkage.create(providers.getMetaAccess(), providers.getCodeCache(), providers.getForeignCalls(), descriptor, 0L, PRESERVES_REGISTERS, JavaCall, JavaCallee,
                        transition, reexecutable, killedLocations));
        this.prependThread = prependThread;
        Class[] targetParameterTypes = createTargetParameters(descriptor);
        ForeignCallDescriptor targetSig = new ForeignCallDescriptor(descriptor.getName() + ":C", descriptor.getResultType(), targetParameterTypes);
        target = HotSpotForeignCallLinkage.create(providers.getMetaAccess(), providers.getCodeCache(), providers.getForeignCalls(), targetSig, address, DESTROYS_REGISTERS, NativeCall, NativeCall,
                        transition, reexecutable, killedLocations);
    }

    /**
     * Gets the linkage information for the call from this stub.
     */
    public HotSpotForeignCallLinkage getTargetLinkage() {
        return target;
    }

    private Class[] createTargetParameters(ForeignCallDescriptor descriptor) {
        Class[] parameters = descriptor.getArgumentTypes();
        if (prependThread) {
            Class[] newParameters = new Class[parameters.length + 1];
            System.arraycopy(parameters, 0, newParameters, 1, parameters.length);
            newParameters[0] = Word.class;
            return newParameters;
        }
        return parameters;
    }

    @Override
    protected ResolvedJavaMethod getInstalledCodeOwner() {
        return null;
    }

    @Override
    protected Object debugScopeContext() {
        return new JavaMethod() {

            public Signature getSignature() {
                ForeignCallDescriptor d = linkage.getDescriptor();
                MetaAccessProvider metaAccess = providers.getMetaAccess();
                Class<?>[] arguments = d.getArgumentTypes();
                JavaType[] parameters = new JavaType[arguments.length];
                for (int i = 0; i < arguments.length; i++) {
                    parameters[i] = metaAccess.lookupJavaType(arguments[i]);
                }
                return new HotSpotSignature(metaAccess.lookupJavaType(d.getResultType()), parameters);
            }

            public String getName() {
                return linkage.getDescriptor().getName();
            }

            public JavaType getDeclaringClass() {
                return providers.getMetaAccess().lookupJavaType(ForeignCallStub.class);
            }

            @Override
            public String toString() {
                return format("ForeignCallStub<%n(%p)>", this);
            }
        };
    }

    static class GraphBuilder {

        public GraphBuilder(Stub stub) {
            this.graph = new StructuredGraph(stub.toString(), null);
            graph.replaceFixed(graph.start(), graph.add(new StubStartNode(stub)));
            this.lastFixedNode = graph.start();
        }

        final StructuredGraph graph;
        private FixedWithNextNode lastFixedNode;

        <T extends FloatingNode> T add(T node) {
            return graph.unique(node);
        }

        <T extends FixedNode> T append(T node) {
            T result = graph.add(node);
            assert lastFixedNode != null;
            assert result.predecessor() == null;
            graph.addAfterFixed(lastFixedNode, result);
            if (result instanceof FixedWithNextNode) {
                lastFixedNode = (FixedWithNextNode) result;
            } else {
                lastFixedNode = null;
            }
            return result;
        }
    }

    /**
     * Creates a graph for this stub.
     * <p>
     * If the stub returns an object, the graph created corresponds to this pseudo code:
     * 
     * <pre>
     *     Object foreignFunctionStub(args...) {
     *         foreignFunction(currentThread,  args);
     *         if (clearPendingException(thread())) {
     *             getAndClearObjectResult(thread());
     *             DeoptimizeCallerNode.deopt(InvalidateReprofile, RuntimeConstraint);
     *         }
     *         return verifyObject(getAndClearObjectResult(thread()));
     *     }
     * </pre>
     * 
     * If the stub returns a primitive or word, the graph created corresponds to this pseudo code
     * (using {@code int} as the primitive return type):
     * 
     * <pre>
     *     int foreignFunctionStub(args...) {
     *         int result = foreignFunction(currentThread,  args);
     *         if (clearPendingException(thread())) {
     *             DeoptimizeCallerNode.deopt(InvalidateReprofile, RuntimeConstraint);
     *         }
     *         return result;
     *     }
     * </pre>
     * 
     * If the stub is void, the graph created corresponds to this pseudo code:
     * 
     * <pre>
     *     void foreignFunctionStub(args...) {
     *         foreignFunction(currentThread,  args);
     *         if (clearPendingException(thread())) {
     *             DeoptimizeCallerNode.deopt(InvalidateReprofile, RuntimeConstraint);
     *         }
     *     }
     * </pre>
     * 
     * In each example above, the {@code currentThread} argument is the C++ JavaThread value (i.e.,
     * %r15 on AMD64) and is only prepended if {@link #prependThread} is true.
     */
    @Override
    protected StructuredGraph getGraph() {
        Class<?>[] args = linkage.getDescriptor().getArgumentTypes();
        boolean isObjectResult = linkage.getOutgoingCallingConvention().getReturn().getKind() == Kind.Object;
        GraphBuilder builder = new GraphBuilder(this);
        LocalNode[] locals = createLocals(builder, args);
        List<InvokeNode> invokes = new ArrayList<>(3);

        ReadRegisterNode thread = builder.append(new ReadRegisterNode(providers.getRegisters().getThreadRegister(), true, false));
        ValueNode result = createTargetCall(builder, locals, thread);
        invokes.add(createInvoke(builder, StubUtil.class, "handlePendingException", thread, ConstantNode.forBoolean(isObjectResult, builder.graph)));
        if (isObjectResult) {
            InvokeNode object = createInvoke(builder, HotSpotReplacementsUtil.class, "getAndClearObjectResult", thread);
            result = createInvoke(builder, StubUtil.class, "verifyObject", object);
            invokes.add(object);
            invokes.add((InvokeNode) result);
        }
        builder.append(new ReturnNode(linkage.getDescriptor().getResultType() == void.class ? null : result));

        if (Debug.isDumpEnabled()) {
            Debug.dump(builder.graph, "Initial stub graph");
        }

        /* Rewrite all word types that can come in from the method argument types. */
        new WordTypeRewriterPhase(providers.getMetaAccess(), providers.getCodeCache().getTarget().wordKind).apply(builder.graph);
        /* Inline all method calls that are create above. */
        for (InvokeNode invoke : invokes) {
            inline(invoke);
        }
        /* Clean up all code that is now dead after inlining. */
        new DeadCodeEliminationPhase().apply(builder.graph);
        assert builder.graph.getNodes().filter(InvokeNode.class).isEmpty();

        if (Debug.isDumpEnabled()) {
            Debug.dump(builder.graph, "Stub graph before compilation");
        }

        return builder.graph;
    }

    private LocalNode[] createLocals(GraphBuilder builder, Class<?>[] args) {
        LocalNode[] locals = new LocalNode[args.length];
        ResolvedJavaType accessingClass = providers.getMetaAccess().lookupJavaType(getClass());
        for (int i = 0; i < args.length; i++) {
            ResolvedJavaType type = providers.getMetaAccess().lookupJavaType(args[i]).resolve(accessingClass);
            Kind kind = type.getKind().getStackKind();
            Stamp stamp;
            if (kind == Kind.Object) {
                stamp = StampFactory.declared(type);
            } else {
                stamp = StampFactory.forKind(type.getKind());
            }
            LocalNode local = builder.add(new LocalNode(i, stamp));
            locals[i] = local;
        }
        return locals;
    }

    private InvokeNode createInvoke(GraphBuilder builder, Class<?> declaringClass, String name, ValueNode... args) {
        ResolvedJavaMethod method = null;
        for (Method m : declaringClass.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getName().equals(name)) {
                assert method == null : "found more than one method in " + declaringClass + " named " + name;
                method = providers.getMetaAccess().lookupJavaMethod(m);
            }
        }
        assert method != null : "did not find method in " + declaringClass + " named " + name;
        Signature signature = method.getSignature();
        JavaType returnType = signature.getReturnType(null);
        assert checkArgs(method, args);
        MethodCallTargetNode callTarget = builder.graph.add(new MethodCallTargetNode(InvokeKind.Static, method, args, returnType));
        InvokeNode invoke = builder.append(new InvokeNode(callTarget, FrameState.UNKNOWN_BCI));
        return invoke;
    }

    private boolean checkArgs(ResolvedJavaMethod method, ValueNode... args) {
        Signature signature = method.getSignature();
        assert signature.getParameterCount(false) == args.length : target + ": wrong number of arguments to " + method;
        for (int i = 0; i != args.length; i++) {
            Kind expected = signature.getParameterKind(i).getStackKind();
            Kind actual = args[i].stamp().kind();
            assert expected == actual : target + ": wrong kind of value for argument " + i + " of calls to " + method + " [" + actual + " != " + expected + "]";
        }
        return true;
    }

    private StubForeignCallNode createTargetCall(GraphBuilder builder, LocalNode[] locals, ReadRegisterNode thread) {
        if (prependThread) {
            ValueNode[] targetArguments = new ValueNode[1 + locals.length];
            targetArguments[0] = thread;
            System.arraycopy(locals, 0, targetArguments, 1, locals.length);
            return builder.append(new StubForeignCallNode(providers.getForeignCalls(), target.getDescriptor(), targetArguments));
        } else {
            return builder.append(new StubForeignCallNode(providers.getForeignCalls(), target.getDescriptor(), locals));
        }
    }

    private void inline(InvokeNode invoke) {
        ResolvedJavaMethod method = ((MethodCallTargetNode) invoke.callTarget()).targetMethod();
        ReplacementsImpl repl = new ReplacementsImpl(providers, new Assumptions(false), providers.getCodeCache().getTarget());
        StructuredGraph calleeGraph = repl.makeGraph(method, null, null, false);
        InliningUtil.inline(invoke, calleeGraph, false);
    }
}
