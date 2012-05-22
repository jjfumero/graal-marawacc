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
package com.oracle.graal.hotspot.ri;

import static com.oracle.max.cri.util.MemoryBarriers.*;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.compiler.*;
import com.oracle.graal.compiler.phases.*;
import com.oracle.graal.compiler.target.*;
import com.oracle.graal.cri.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.Compiler;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.hotspot.snippets.*;
import com.oracle.graal.hotspot.snippets.CheckCastSnippets.Counter;
import com.oracle.graal.hotspot.target.amd64.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.snippets.*;
import com.oracle.max.cri.ci.*;
import com.oracle.max.cri.ci.CiTargetMethod.Call;
import com.oracle.max.cri.ci.CiTargetMethod.DataPatch;
import com.oracle.max.cri.ci.CiTargetMethod.Mark;
import com.oracle.max.cri.ci.CiTargetMethod.Safepoint;
import com.oracle.max.cri.ci.CiUtil.RefMapFormatter;
import com.oracle.max.cri.ri.*;
import com.oracle.max.cri.ri.RiType.Representation;
import com.oracle.max.criutils.*;

/**
 * CRI runtime implementation for the HotSpot VM.
 */
public class HotSpotRuntime implements GraalRuntime {
    public final HotSpotVMConfig config;
    final HotSpotRegisterConfig regConfig;
    private final HotSpotRegisterConfig globalStubRegConfig;
    private final Compiler compiler;
    private CheckCastSnippets.Templates checkcasts;

    public HotSpotRuntime(HotSpotVMConfig config, Compiler compiler) {
        this.config = config;
        this.compiler = compiler;
        regConfig = new HotSpotRegisterConfig(config, false);
        globalStubRegConfig = new HotSpotRegisterConfig(config, true);

        System.setProperty(Backend.BACKEND_CLASS_PROPERTY, HotSpotAMD64Backend.class.getName());
    }

    public void installSnippets() {
        Snippets.install(this, compiler.getTarget(), new SystemSnippets());
        Snippets.install(this, compiler.getTarget(), new UnsafeSnippets());
        Snippets.install(this, compiler.getTarget(), new ArrayCopySnippets());
        Snippets.install(this, compiler.getTarget(), new CheckCastSnippets());
        checkcasts = new CheckCastSnippets.Templates(this);
    }


    @Override
    public int codeOffset() {
        return 0;
    }


    public Compiler getCompiler() {
        return compiler;
    }

    @Override
    public String disassemble(RiCodeInfo info) {
        byte[] code = info.code();
        CiTarget target = compiler.getTarget();
        HexCodeFile hcf = new HexCodeFile(code, info.start(), target.arch.name, target.wordSize * 8);
        CiTargetMethod tm = info.targetMethod();
        if (tm != null) {
            HexCodeFile.addAnnotations(hcf, tm.annotations());
            addExceptionHandlersComment(tm, hcf);
            CiRegister fp = regConfig.getFrameRegister();
            RefMapFormatter slotFormatter = new RefMapFormatter(target.arch, target.wordSize, fp, 0);
            for (Safepoint safepoint : tm.safepoints) {
                if (safepoint instanceof Call) {
                    Call call = (Call) safepoint;
                    if (call.debugInfo != null) {
                        hcf.addComment(call.pcOffset + call.size, CiUtil.append(new StringBuilder(100), call.debugInfo, slotFormatter).toString());
                    }
                    addOperandComment(hcf, call.pcOffset, "{" + getTargetName(call) + "}");
                } else {
                    if (safepoint.debugInfo != null) {
                        hcf.addComment(safepoint.pcOffset, CiUtil.append(new StringBuilder(100), safepoint.debugInfo, slotFormatter).toString());
                    }
                    addOperandComment(hcf, safepoint.pcOffset, "{safepoint}");
                }
            }
            for (DataPatch site : tm.dataReferences) {
                hcf.addOperandComment(site.pcOffset, "{" + site.constant + "}");
            }
            for (Mark mark : tm.marks) {
                hcf.addComment(mark.pcOffset, getMarkName(mark));
            }
        }
        return hcf.toEmbeddedString();
    }

    /**
     * Decodes a call target to a mnemonic if possible.
     */
    private String getTargetName(Call call) {
        Field[] fields = config.getClass().getDeclaredFields();
        for (Field f : fields) {
            if (f.getName().endsWith("Stub")) {
                f.setAccessible(true);
                try {
                    if (f.get(config).equals(call.target)) {
                        return f.getName();
                    }
                } catch (Exception e) {
                }
            }
        }
        return String.valueOf(call.target);
    }

    /**
     * Decodes a mark to a mnemonic if possible.
     */
    private static String getMarkName(Mark mark) {
        Field[] fields = HotSpotXirGenerator.class.getDeclaredFields();
        for (Field f : fields) {
            if (Modifier.isStatic(f.getModifiers()) && f.getName().startsWith("MARK_")) {
                f.setAccessible(true);
                try {
                    if (f.get(null).equals(mark.id)) {
                        return f.getName();
                    }
                } catch (Exception e) {
                }
            }
        }
        return "MARK:" + mark.id;
    }

    private static void addExceptionHandlersComment(CiTargetMethod tm, HexCodeFile hcf) {
        if (!tm.exceptionHandlers.isEmpty()) {
            String nl = HexCodeFile.NEW_LINE;
            StringBuilder buf = new StringBuilder("------ Exception Handlers ------").append(nl);
            for (CiTargetMethod.ExceptionHandler e : tm.exceptionHandlers) {
                buf.append("    ").
                    append(e.pcOffset).append(" -> ").
                    append(e.handlerPos).
                    append(nl);
                hcf.addComment(e.pcOffset, "[exception -> " + e.handlerPos + "]");
                hcf.addComment(e.handlerPos, "[exception handler for " + e.pcOffset + "]");
            }
            hcf.addComment(0, buf.toString());
        }
    }

    private static void addOperandComment(HexCodeFile hcf, int pos, String comment) {
        String oldValue = hcf.addOperandComment(pos, comment);
        assert oldValue == null : "multiple comments for operand of instruction at " + pos + ": " + comment + ", " + oldValue;
    }

    @Override
    public String disassemble(RiResolvedMethod method) {
        return compiler.getCompilerToVM().disassembleJava((HotSpotMethodResolved) method);
    }

    @Override
    public RiResolvedType asRiType(CiKind kind) {
        return (RiResolvedType) compiler.getCompilerToVM().getType(kind.toJavaClass());
    }

    @Override
    public RiResolvedType getTypeOf(CiConstant constant) {
        return (RiResolvedType) compiler.getCompilerToVM().getRiType(constant);
    }

    @Override
    public boolean isExceptionType(RiResolvedType type) {
        return type.isSubtypeOf((RiResolvedType) compiler.getCompilerToVM().getType(Throwable.class));
    }

    @Override
    public Object registerCompilerStub(CiTargetMethod targetMethod, String name, RiCodeInfo info) {
        return HotSpotTargetMethod.installStub(compiler, targetMethod, name, (HotSpotCodeInfo) info);
    }

    @Override
    public int sizeOfLockData() {
        // TODO shouldn't be hard coded
        return 8;
    }

    @Override
    public int sizeOfBasicObjectLock() {
        // TODO shouldn't be hard coded
        return 2 * 8;
    }

    @Override
    public int basicObjectLockOffsetInBytes() {
        return 8;
    }

    @Override
    public boolean areConstantObjectsEqual(CiConstant x, CiConstant y) {
        return compiler.getCompilerToVM().compareConstantObjects(x, y);
    }

    @Override
    public RiRegisterConfig getRegisterConfig(RiMethod method) {
        return regConfig;
    }

    /**
     * HotSpots needs an area suitable for storing a program counter for temporary use during the deoptimization process.
     */
    @Override
    public int getCustomStackAreaSize() {
        // TODO shouldn't be hard coded
        return 8;
    }

    @Override
    public int getMinimumOutgoingSize() {
        return config.runtimeCallStackSize;
    }

    @Override
    public int getArrayLength(CiConstant array) {
        return compiler.getCompilerToVM().getArrayLength(array);
    }

    @Override
    public Class<?> asJavaClass(CiConstant c) {
        return (Class<?>) c.asObject();
    }

    @Override
    public Object asJavaObject(CiConstant c) {
        return c.asObject();
    }

    @Override
    public void lower(Node n, CiLoweringTool tool) {
        if (!GraalOptions.Lower) {
            return;
        }
        StructuredGraph graph = (StructuredGraph) n.graph();

        if (n instanceof ArrayLengthNode) {
            ArrayLengthNode arrayLengthNode = (ArrayLengthNode) n;
            SafeReadNode safeReadArrayLength = safeReadArrayLength(arrayLengthNode.array(), StructuredGraph.INVALID_GRAPH_ID);
            graph.replaceFixedWithFixed(arrayLengthNode, safeReadArrayLength);
        } else if (n instanceof LoadFieldNode) {
            LoadFieldNode field = (LoadFieldNode) n;
            int displacement = ((HotSpotField) field.field()).offset();
            assert field.kind() != CiKind.Illegal;
            ReadNode memoryRead = graph.add(new ReadNode(field.object(), LocationNode.create(field.field(), field.field().kind(true), displacement, graph), field.stamp()));
            memoryRead.dependencies().add(tool.createGuard(graph.unique(new NullCheckNode(field.object(), false)), RiDeoptReason.NullCheckException, RiDeoptAction.InvalidateReprofile, field.leafGraphId()));
            graph.replaceFixedWithFixed(field, memoryRead);
            if (field.isVolatile()) {
                MembarNode preMembar = graph.add(new MembarNode(JMM_PRE_VOLATILE_READ));
                graph.addBeforeFixed(memoryRead, preMembar);
                MembarNode postMembar = graph.add(new MembarNode(JMM_POST_VOLATILE_READ));
                graph.addAfterFixed(memoryRead, postMembar);
            }
        } else if (n instanceof StoreFieldNode) {
            StoreFieldNode storeField = (StoreFieldNode) n;
            HotSpotField field = (HotSpotField) storeField.field();
            WriteNode memoryWrite = graph.add(new WriteNode(storeField.object(), storeField.value(), LocationNode.create(storeField.field(), storeField.field().kind(true), field.offset(), graph)));
            memoryWrite.dependencies().add(tool.createGuard(graph.unique(new NullCheckNode(storeField.object(), false)), RiDeoptReason.NullCheckException, RiDeoptAction.InvalidateReprofile, storeField.leafGraphId()));
            memoryWrite.setStateAfter(storeField.stateAfter());
            graph.replaceFixedWithFixed(storeField, memoryWrite);

            FixedWithNextNode last = memoryWrite;
            if (field.kind(true) == CiKind.Object && !memoryWrite.value().isNullConstant()) {
                FieldWriteBarrier writeBarrier = graph.add(new FieldWriteBarrier(memoryWrite.object()));
                graph.addAfterFixed(memoryWrite, writeBarrier);
                last = writeBarrier;
            }
            if (storeField.isVolatile()) {
                MembarNode preMembar = graph.add(new MembarNode(JMM_PRE_VOLATILE_WRITE));
                graph.addBeforeFixed(memoryWrite, preMembar);
                MembarNode postMembar = graph.add(new MembarNode(JMM_POST_VOLATILE_WRITE));
                graph.addAfterFixed(last, postMembar);
            }
        } else if (n instanceof CompareAndSwapNode) {
            // Separate out GC barrier semantics
            CompareAndSwapNode cas = (CompareAndSwapNode) n;
            ValueNode expected = cas.expected();
            if (expected.kind() == CiKind.Object && !cas.newValue().isNullConstant()) {
                RiResolvedType declaredType = cas.object().declaredType();
                if (declaredType != null && !declaredType.isArrayClass() && declaredType.toJava() != Object.class) {
                    // Use a field write barrier since it's not an array store
                    FieldWriteBarrier writeBarrier = graph.add(new FieldWriteBarrier(cas.object()));
                    graph.addAfterFixed(cas, writeBarrier);
                } else {
                    // This may be an array store so use an array write barrier
                    LocationNode location = IndexedLocationNode.create(LocationNode.ANY_LOCATION, cas.expected().kind(), cas.displacement(), cas.offset(), graph, false);
                    graph.addAfterFixed(cas, graph.add(new ArrayWriteBarrier(cas.object(), location)));
                }
            }
        } else if (n instanceof LoadIndexedNode) {
            LoadIndexedNode loadIndexed = (LoadIndexedNode) n;

            Node boundsCheck = createBoundsCheck(loadIndexed, tool);

            CiKind elementKind = loadIndexed.elementKind();
            LocationNode arrayLocation = createArrayLocation(graph, elementKind, loadIndexed.index());
            ReadNode memoryRead = graph.add(new ReadNode(loadIndexed.array(), arrayLocation, loadIndexed.stamp()));
            memoryRead.dependencies().add(boundsCheck);
            graph.replaceFixedWithFixed(loadIndexed, memoryRead);
        } else if (n instanceof StoreIndexedNode) {
            StoreIndexedNode storeIndexed = (StoreIndexedNode) n;
            Node boundsCheck = createBoundsCheck(storeIndexed, tool);

            CiKind elementKind = storeIndexed.elementKind();
            LocationNode arrayLocation = createArrayLocation(graph, elementKind, storeIndexed.index());
            ValueNode value = storeIndexed.value();
            CheckCastNode checkcast = null;
            ValueNode array = storeIndexed.array();
            if (elementKind == CiKind.Object && !value.isNullConstant()) {
                // Store check!
                if (array.exactType() != null) {
                    RiResolvedType elementType = array.exactType().componentType();
                    if (elementType.superType() != null) {
                        ConstantNode type = ConstantNode.forCiConstant(elementType.getEncoding(Representation.ObjectHub), this, graph);
                        checkcast = graph.add(new CheckCastNode(type, elementType, value));
                        graph.addBeforeFixed(storeIndexed, checkcast);
                        value = checkcast;
                    } else {
                        assert elementType.name().equals("Ljava/lang/Object;") : elementType.name();
                    }
                } else {
                    Node guard = tool.createGuard(graph.unique(new NullCheckNode(array, false)), RiDeoptReason.NullCheckException, RiDeoptAction.InvalidateReprofile, StructuredGraph.INVALID_GRAPH_ID);
                    FloatingReadNode arrayClass = graph.unique(new FloatingReadNode(array, LocationNode.create(LocationNode.FINAL_LOCATION, CiKind.Object, config.hubOffset, graph), null, StampFactory.objectNonNull()));
                    arrayClass.dependencies().add(guard);
                    FloatingReadNode arrayElementKlass = graph.unique(new FloatingReadNode(arrayClass, LocationNode.create(LocationNode.FINAL_LOCATION, CiKind.Object, config.arrayClassElementOffset, graph), null, StampFactory.objectNonNull()));
                    checkcast = graph.add(new CheckCastNode(arrayElementKlass, null, value));
                    graph.addBeforeFixed(storeIndexed, checkcast);
                    value = checkcast;
                }
            }
            WriteNode memoryWrite = graph.add(new WriteNode(array, value, arrayLocation));
            memoryWrite.dependencies().add(boundsCheck);
            memoryWrite.setStateAfter(storeIndexed.stateAfter());

            graph.replaceFixedWithFixed(storeIndexed, memoryWrite);

            if (elementKind == CiKind.Object && !value.isNullConstant()) {
                graph.addAfterFixed(memoryWrite, graph.add(new ArrayWriteBarrier(array, arrayLocation)));
            }
        } else if (n instanceof UnsafeLoadNode) {
            UnsafeLoadNode load = (UnsafeLoadNode) n;
            assert load.kind() != CiKind.Illegal;
            IndexedLocationNode location = IndexedLocationNode.create(LocationNode.ANY_LOCATION, load.loadKind(), load.displacement(), load.offset(), graph);
            location.setIndexScalingEnabled(false);
            ReadNode memoryRead = graph.add(new ReadNode(load.object(), location, load.stamp()));
            memoryRead.dependencies().add(tool.createGuard(graph.unique(new NullCheckNode(load.object(), false)), RiDeoptReason.NullCheckException, RiDeoptAction.InvalidateReprofile, StructuredGraph.INVALID_GRAPH_ID));
            graph.replaceFixedWithFixed(load, memoryRead);
        } else if (n instanceof UnsafeStoreNode) {
            UnsafeStoreNode store = (UnsafeStoreNode) n;
            IndexedLocationNode location = IndexedLocationNode.create(LocationNode.ANY_LOCATION, store.storeKind(), store.displacement(), store.offset(), graph);
            location.setIndexScalingEnabled(false);
            WriteNode write = graph.add(new WriteNode(store.object(), store.value(), location));
            write.setStateAfter(store.stateAfter());
            graph.replaceFixedWithFixed(store, write);
            if (write.value().kind() == CiKind.Object && !write.value().isNullConstant()) {
                FieldWriteBarrier barrier = graph.add(new FieldWriteBarrier(write.object()));
                graph.addBeforeFixed(write, barrier);
            }
        } else if (n instanceof ReadHubNode) {
            ReadHubNode objectClassNode = (ReadHubNode) n;
            LocationNode location = LocationNode.create(LocationNode.FINAL_LOCATION, CiKind.Object, config.hubOffset, graph);
            ReadNode memoryRead = graph.add(new ReadNode(objectClassNode.object(), location, StampFactory.objectNonNull()));
            memoryRead.dependencies().add(tool.createGuard(graph.unique(new NullCheckNode(objectClassNode.object(), false)), RiDeoptReason.NullCheckException, RiDeoptAction.InvalidateReprofile, StructuredGraph.INVALID_GRAPH_ID));
            graph.replaceFixed(objectClassNode, memoryRead);
        } else if (n instanceof CheckCastNode) {
            if (shouldLowerCheckcast(graph)) {
                CheckCastNode checkcast = (CheckCastNode) n;
                ValueNode hub = checkcast.targetClassInstruction();
                ValueNode object = checkcast.object();
                TypeCheckHints hints = new TypeCheckHints(checkcast.targetClass(), checkcast.profile(), tool.assumptions(), GraalOptions.CheckcastMinHintHitProbability, GraalOptions.CheckcastMaxHints);
                HotSpotKlassOop[] hintHubs = new HotSpotKlassOop[hints.types.length];
                for (int i = 0; i < hintHubs.length; i++) {
                    hintHubs[i] = ((HotSpotType) hints.types[i]).klassOop();
                }
                Debug.log("Lowering checkcast in %s: node=%s, hintsHubs=%s, exact=%b", graph, checkcast, Arrays.toString(hints.types), hints.exact);

                final Counter noHintsCounter;
                if (GraalOptions.CheckcastCounters) {
                    if (checkcast.targetClass() == null) {
                        noHintsCounter = Counter.noHints_unknown;
                    } else if (checkcast.targetClass().isInterface()) {
                        noHintsCounter = Counter.noHints_iface;
                    } else {
                        noHintsCounter = Counter.noHints_class;
                    }
                } else {
                    noHintsCounter = null;
                }
                boolean checkNull = !object.stamp().nonNull();
                checkcasts.get(hintHubs.length, hints.exact, checkNull, noHintsCounter).instantiate(this, checkcast, checkcast, hub, object, hintHubs, noHintsCounter);
                new DeadCodeEliminationPhase().apply(graph);
            }
        } else {
            assert false : "Node implementing Lowerable not handled: " + n;
        }
    }

    private static boolean shouldLowerCheckcast(StructuredGraph graph) {
        String option = GraalOptions.HIRLowerCheckcast;
        if (option != null) {
            if (option.length() == 0) {
                return true;
            }
            RiResolvedMethod method = graph.method();
            return method != null && CiUtil.format("%H.%n", method).contains(option);
        }
        return false;
    }

    private IndexedLocationNode createArrayLocation(Graph graph, CiKind elementKind, ValueNode index) {
        return IndexedLocationNode.create(LocationNode.getArrayLocation(elementKind), elementKind, config.getArrayOffset(elementKind), index, graph);
    }

    private SafeReadNode safeReadArrayLength(ValueNode array, long leafGraphId) {
        return safeRead(array.graph(), CiKind.Int, array, config.arrayLengthOffset, StampFactory.positiveInt(), leafGraphId);
    }

    private static Node createBoundsCheck(AccessIndexedNode n, CiLoweringTool tool) {
        StructuredGraph graph = (StructuredGraph) n.graph();
        ArrayLengthNode arrayLength = graph.add(new ArrayLengthNode(n.array()));
        Node guard = tool.createGuard(graph.unique(new CompareNode(n.index(), Condition.BT, arrayLength)), RiDeoptReason.BoundsCheckException, RiDeoptAction.InvalidateReprofile, n.leafGraphId());

        graph.addBeforeFixed(n, arrayLength);
        return guard;
    }

    @Override
    public StructuredGraph intrinsicGraph(RiResolvedMethod caller, int bci, RiResolvedMethod method, List<? extends Node> parameters) {
        RiType holder = method.holder();
        String fullName = method.name() + method.signature().asString();
        String holderName = holder.name();
        if (holderName.equals("Ljava/lang/Object;")) {
            if (fullName.equals("getClass()Ljava/lang/Class;")) {
                ValueNode obj = (ValueNode) parameters.get(0);
                if (obj.stamp().nonNull() && obj.stamp().exactType() != null) {
                    StructuredGraph graph = new StructuredGraph();
                    ValueNode result = ConstantNode.forObject(obj.stamp().exactType().toJava(), this, graph);
                    ReturnNode ret = graph.add(new ReturnNode(result));
                    graph.start().setNext(ret);
                    return graph;
                }
                StructuredGraph graph = new StructuredGraph();
                LocalNode receiver = graph.unique(new LocalNode(CiKind.Object, 0));
                SafeReadNode klassOop = safeReadHub(graph, receiver, StructuredGraph.INVALID_GRAPH_ID);
                FloatingReadNode result = graph.unique(new FloatingReadNode(klassOop, LocationNode.create(LocationNode.FINAL_LOCATION, CiKind.Object, config.classMirrorOffset, graph), null, StampFactory.objectNonNull()));
                ReturnNode ret = graph.add(new ReturnNode(result));
                graph.start().setNext(klassOop);
                klassOop.setNext(ret);
                return graph;
            }
        } else if (holderName.equals("Ljava/lang/Class;")) {
            if (fullName.equals("getModifiers()I")) {
                StructuredGraph graph = new StructuredGraph();
                LocalNode receiver = graph.unique(new LocalNode(CiKind.Object, 0));
                SafeReadNode klassOop = safeRead(graph, CiKind.Object, receiver, config.klassOopOffset, StampFactory.objectNonNull(), StructuredGraph.INVALID_GRAPH_ID);
                graph.start().setNext(klassOop);
                // TODO(thomaswue): Care about primitive classes! Crashes for primitive classes at the moment (klassOop == null)
                FloatingReadNode result = graph.unique(new FloatingReadNode(klassOop, LocationNode.create(LocationNode.FINAL_LOCATION, CiKind.Int, config.klassModifierFlagsOffset, graph), null, StampFactory.forKind(CiKind.Int)));
                ReturnNode ret = graph.add(new ReturnNode(result));
                klassOop.setNext(ret);
                return graph;
            }
        } else if (holderName.equals("Ljava/lang/Thread;")) {
            if (fullName.equals("currentThread()Ljava/lang/Thread;")) {
                StructuredGraph graph = new StructuredGraph();
                ReturnNode ret = graph.add(new ReturnNode(graph.unique(new CurrentThread(config.threadObjectOffset))));
                graph.start().setNext(ret);
                return graph;
            }
        }
        return null;
    }

    private SafeReadNode safeReadHub(Graph graph, ValueNode value, long leafGraphId) {
        return safeRead(graph, CiKind.Object, value, config.hubOffset, StampFactory.objectNonNull(), leafGraphId);
    }

    private static SafeReadNode safeRead(Graph graph, CiKind kind, ValueNode value, int offset, Stamp stamp, long leafGraphId) {
        return graph.add(new SafeReadNode(value, LocationNode.create(LocationNode.FINAL_LOCATION, kind, offset, graph), stamp, leafGraphId));
    }

    public RiResolvedType getType(Class<?> clazz) {
        return (RiResolvedType) compiler.getCompilerToVM().getType(clazz);
    }

    public Object asCallTarget(Object target) {
        return target;
    }

    public long getMaxCallTargetOffset(CiRuntimeCall rtcall) {
        return compiler.getCompilerToVM().getMaxCallTargetOffset(rtcall);
    }

    public RiResolvedMethod getRiMethod(Method reflectionMethod) {
        return (RiResolvedMethod) compiler.getCompilerToVM().getRiMethod(reflectionMethod);
    }

    private HotSpotCodeInfo makeInfo(RiResolvedMethod method, CiTargetMethod code, RiCodeInfo[] info) {
        HotSpotCodeInfo hsInfo = null;
        if (info != null && info.length > 0) {
            hsInfo = new HotSpotCodeInfo(compiler, code, (HotSpotMethodResolved) method);
            info[0] = hsInfo;
        }
        return hsInfo;
    }

    @Override
    public void installMethod(RiResolvedMethod method, CiTargetMethod code, RiCodeInfo[] info) {
        HotSpotCodeInfo hsInfo = makeInfo(method, code, info);
        compiler.getCompilerToVM().installMethod(new HotSpotTargetMethod(compiler, (HotSpotMethodResolved) method, code), true, hsInfo);
    }

    @Override
    public RiCompiledMethod addMethod(RiResolvedMethod method, CiTargetMethod code, RiCodeInfo[] info) {
        HotSpotCodeInfo hsInfo = makeInfo(method, code, info);
        return compiler.getCompilerToVM().installMethod(new HotSpotTargetMethod(compiler, (HotSpotMethodResolved) method, code), false, hsInfo);
    }

    @Override
    public RiRegisterConfig getGlobalStubRegisterConfig() {
        return globalStubRegConfig;
    }

    @Override
    public CiTargetMethod compile(RiResolvedMethod method, StructuredGraph graph) {
        OptimisticOptimizations optimisticOpts = OptimisticOptimizations.ALL;
        return compiler.getCompiler().compileMethod(method, graph, -1, compiler.getCache(), compiler.getVMToCompiler().createPhasePlan(optimisticOpts), optimisticOpts);
    }

    @Override
    public int encodeDeoptActionAndReason(RiDeoptAction action, RiDeoptReason reason) {
        final int actionShift = 0;
        final int reasonShift = 3;

        int actionValue = convertDeoptAction(action);
        int reasonValue = convertDeoptReason(reason);
        return (~(((reasonValue) << reasonShift) + ((actionValue) << actionShift)));
    }

    @Override
    public int convertDeoptAction(RiDeoptAction action) {
        switch(action) {
            case None: return 0;
            case RecompileIfTooManyDeopts: return 1;
            case InvalidateReprofile: return 2;
            case InvalidateRecompile: return 3;
            case InvalidateStopCompiling: return 4;
            default: throw GraalInternalError.shouldNotReachHere();
        }
    }

    @Override
    public int convertDeoptReason(RiDeoptReason reason) {
        switch(reason) {
            case None: return 0;
            case NullCheckException: return 1;
            case BoundsCheckException: return 2;
            case ClassCastException: return 3;
            case ArrayStoreException: return 4;
            case UnreachedCode: return 5;
            case TypeCheckedInliningViolated: return 6;
            case OptimizedTypeCheckViolated: return 7;
            case NotCompiledExceptionHandler: return 8;
            case Unresolved: return 9;
            case JavaSubroutineMismatch: return 10;
            case ArithmeticException: return 11;
            case RuntimeConstraint: return 12;
            default: throw GraalInternalError.shouldNotReachHere();
        }
    }
}
