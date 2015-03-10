/*
 * Copyright (c) 2012, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.replacements;

import static com.oracle.graal.api.code.UnsignedMath.*;
import static com.oracle.graal.compiler.common.GraalOptions.*;
import static com.oracle.graal.hotspot.nodes.CStringNode.*;
import static com.oracle.graal.hotspot.replacements.HotSpotReplacementsUtil.*;
import static com.oracle.graal.hotspot.replacements.NewObjectSnippets.Options.*;
import static com.oracle.graal.nodes.PiArrayNode.*;
import static com.oracle.graal.nodes.extended.BranchProbabilityNode.*;
import static com.oracle.graal.replacements.SnippetTemplate.*;
import static com.oracle.graal.replacements.nodes.ExplodeLoopNode.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.replacements.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.Node.ConstantNodeParameter;
import com.oracle.graal.graph.Node.NodeIntrinsic;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.hotspot.nodes.type.*;
import com.oracle.graal.hotspot.word.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.debug.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.options.*;
import com.oracle.graal.replacements.*;
import com.oracle.graal.replacements.Snippet.ConstantParameter;
import com.oracle.graal.replacements.Snippet.VarargsParameter;
import com.oracle.graal.replacements.SnippetTemplate.AbstractTemplates;
import com.oracle.graal.replacements.SnippetTemplate.Arguments;
import com.oracle.graal.replacements.SnippetTemplate.SnippetInfo;
import com.oracle.graal.replacements.nodes.*;
import com.oracle.graal.word.*;

/**
 * Snippets used for implementing NEW, ANEWARRAY and NEWARRAY.
 */
public class NewObjectSnippets implements Snippets {

    public static final LocationIdentity INIT_LOCATION = NamedLocationIdentity.mutable("Initialization");

    static class Options {

        //@formatter:off
        @Option(help = "", type = OptionType.Debug)
        static final OptionValue<Boolean> ProfileAllocations = new OptionValue<>(false);
        //@formatter:on
    }

    static enum ProfileMode {
        AllocatingMethods,
        InstanceOrArray,
        AllocatedTypes,
        AllocatedTypesInMethods,
        Total
    }

    public static final ProfileMode PROFILE_MODE = ProfileMode.AllocatedTypes;

    @Fold
    private static String createName(String path, String typeContext) {
        switch (PROFILE_MODE) {
            case AllocatingMethods:
                return "";
            case InstanceOrArray:
                return path;
            case AllocatedTypes:
            case AllocatedTypesInMethods:
                return typeContext;
            case Total:
                return "bytes";
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    @Fold
    private static boolean doProfile() {
        return ProfileAllocations.getValue();
    }

    protected static void profileAllocation(String path, long size, String typeContext) {
        if (doProfile()) {
            String name = createName(path, typeContext);

            boolean context = PROFILE_MODE == ProfileMode.AllocatingMethods || PROFILE_MODE == ProfileMode.AllocatedTypesInMethods;
            DynamicCounterNode.counter(name, "number of bytes allocated", size, context);
            DynamicCounterNode.counter(name, "number of allocations", 1, context);
        }
    }

    public static void emitPrefetchAllocate(Word address, boolean isArray) {
        if (config().allocatePrefetchStyle > 0) {
            // Insert a prefetch for each allocation only on the fast-path
            // Generate several prefetch instructions.
            int lines = isArray ? config().allocatePrefetchLines : config().allocateInstancePrefetchLines;
            int stepSize = config().allocatePrefetchStepSize;
            int distance = config().allocatePrefetchDistance;
            ExplodeLoopNode.explodeLoop();
            for (int i = 0; i < lines; i++) {
                PrefetchAllocateNode.prefetch(address, Word.signed(distance));
                distance += stepSize;
            }
        }
    }

    @Snippet
    public static Object allocateInstance(@ConstantParameter int size, KlassPointer hub, Word prototypeMarkWord, @ConstantParameter boolean fillContents, @ConstantParameter Register threadRegister,
                    @ConstantParameter boolean constantSize, @ConstantParameter String typeContext) {
        Object result;
        Word thread = registerAsWord(threadRegister);
        Word top = readTlabTop(thread);
        Word end = readTlabEnd(thread);
        Word newTop = top.add(size);
        if (useTLAB() && probability(FAST_PATH_PROBABILITY, newTop.belowOrEqual(end))) {
            writeTlabTop(thread, newTop);
            emitPrefetchAllocate(newTop, false);
            result = formatObject(hub, size, top, prototypeMarkWord, fillContents, constantSize, true);
        } else {
            new_stub.inc();
            result = NewInstanceStubCall.call(hub);
        }
        profileAllocation("instance", size, typeContext);
        return piCast(verifyOop(result), StampFactory.forNodeIntrinsic());
    }

    @Snippet
    public static Object allocateInstanceDynamic(Class<?> type, @ConstantParameter boolean fillContents, @ConstantParameter Register threadRegister, @ConstantParameter String typeContext) {
        KlassPointer hub = ClassGetHubNode.readClass(type);
        if (probability(FAST_PATH_PROBABILITY, !hub.isNull())) {
            if (probability(FAST_PATH_PROBABILITY, isInstanceKlassFullyInitialized(hub))) {
                int layoutHelper = readLayoutHelper(hub);
                /*
                 * src/share/vm/oops/klass.hpp: For instances, layout helper is a positive number,
                 * the instance size. This size is already passed through align_object_size and
                 * scaled to bytes. The low order bit is set if instances of this class cannot be
                 * allocated using the fastpath.
                 */
                if (probability(FAST_PATH_PROBABILITY, (layoutHelper & 1) == 0)) {
                    Word prototypeMarkWord = hub.readWord(prototypeMarkWordOffset(), PROTOTYPE_MARK_WORD_LOCATION);
                    return allocateInstance(layoutHelper, hub, prototypeMarkWord, fillContents, threadRegister, false, typeContext);
                }
            }
        }
        return dynamicNewInstanceStub(type);
    }

    /**
     * Maximum array length for which fast path allocation is used.
     */
    public static final int MAX_ARRAY_FAST_PATH_ALLOCATION_LENGTH = 0x00FFFFFF;

    @Snippet
    public static Object allocateArray(KlassPointer hub, int length, Word prototypeMarkWord, @ConstantParameter int headerSize, @ConstantParameter int log2ElementSize,
                    @ConstantParameter boolean fillContents, @ConstantParameter Register threadRegister, @ConstantParameter boolean maybeUnroll, @ConstantParameter String typeContext) {
        return allocateArrayImpl(hub, length, prototypeMarkWord, headerSize, log2ElementSize, fillContents, threadRegister, maybeUnroll, typeContext, false);
    }

    private static Object allocateArrayImpl(KlassPointer hub, int length, Word prototypeMarkWord, int headerSize, int log2ElementSize, boolean fillContents,
                    @ConstantParameter Register threadRegister, @ConstantParameter boolean maybeUnroll, String typeContext, boolean skipNegativeCheck) {
        Object result;
        int alignment = wordSize();
        int allocationSize = computeArrayAllocationSize(length, alignment, headerSize, log2ElementSize);
        Word thread = registerAsWord(threadRegister);
        Word top = readTlabTop(thread);
        Word end = readTlabEnd(thread);
        Word newTop = top.add(allocationSize);
        if ((skipNegativeCheck || belowThan(length, MAX_ARRAY_FAST_PATH_ALLOCATION_LENGTH)) && useTLAB() && probability(FAST_PATH_PROBABILITY, newTop.belowOrEqual(end))) {
            writeTlabTop(thread, newTop);
            emitPrefetchAllocate(newTop, true);
            newarray_loopInit.inc();
            result = formatArray(hub, allocationSize, length, headerSize, top, prototypeMarkWord, fillContents, maybeUnroll, true);
        } else {
            newarray_stub.inc();
            result = NewArrayStubCall.call(hub, length);
        }
        profileAllocation("array", allocationSize, typeContext);
        return piArrayCast(verifyOop(result), length, StampFactory.forNodeIntrinsic());
    }

    public static final ForeignCallDescriptor DYNAMIC_NEW_ARRAY = new ForeignCallDescriptor("dynamic_new_array", Object.class, Class.class, int.class);
    public static final ForeignCallDescriptor DYNAMIC_NEW_INSTANCE = new ForeignCallDescriptor("dynamic_new_instance", Object.class, Class.class);

    @NodeIntrinsic(ForeignCallNode.class)
    public static native Object dynamicNewArrayStub(@ConstantNodeParameter ForeignCallDescriptor descriptor, Class<?> elementType, int length);

    public static Object dynamicNewInstanceStub(Class<?> elementType) {
        return dynamicNewInstanceStubCall(DYNAMIC_NEW_INSTANCE, elementType);
    }

    @NodeIntrinsic(ForeignCallNode.class)
    public static native Object dynamicNewInstanceStubCall(@ConstantNodeParameter ForeignCallDescriptor descriptor, Class<?> elementType);

    @Snippet
    public static Object allocateArrayDynamic(Class<?> elementType, int length, @ConstantParameter boolean fillContents, @ConstantParameter Register threadRegister) {
        Word hub = loadWordFromObject(elementType, arrayKlassOffset(), CLASS_ARRAY_KLASS_LOCATION);
        if (hub.equal(Word.zero()) || !belowThan(length, MAX_ARRAY_FAST_PATH_ALLOCATION_LENGTH)) {
            return dynamicNewArrayStub(DYNAMIC_NEW_ARRAY, elementType, length);
        }

        KlassPointer klass = KlassPointer.fromWord(hub);
        int layoutHelper = readLayoutHelper(klass);
        //@formatter:off
        // from src/share/vm/oops/klass.hpp:
        //
        // For arrays, layout helper is a negative number, containing four
        // distinct bytes, as follows:
        //    MSB:[tag, hsz, ebt, log2(esz)]:LSB
        // where:
        //    tag is 0x80 if the elements are oops, 0xC0 if non-oops
        //    hsz is array header size in bytes (i.e., offset of first element)
        //    ebt is the BasicType of the elements
        //    esz is the element size in bytes
        //@formatter:on

        int headerSize = (layoutHelper >> layoutHelperHeaderSizeShift()) & layoutHelperHeaderSizeMask();
        int log2ElementSize = (layoutHelper >> layoutHelperLog2ElementSizeShift()) & layoutHelperLog2ElementSizeMask();
        Word prototypeMarkWord = hub.readWord(prototypeMarkWordOffset(), PROTOTYPE_MARK_WORD_LOCATION);

        return allocateArrayImpl(klass, length, prototypeMarkWord, headerSize, log2ElementSize, fillContents, threadRegister, false, "dynamic type", true);
    }

    /**
     * Computes the size of the memory chunk allocated for an array. This size accounts for the
     * array header size, body size and any padding after the last element to satisfy object
     * alignment requirements.
     *
     * @param length the number of elements in the array
     * @param alignment the object alignment requirement
     * @param headerSize the size of the array header
     * @param log2ElementSize log2 of the size of an element in the array
     */
    public static int computeArrayAllocationSize(int length, int alignment, int headerSize, int log2ElementSize) {
        int size = (length << log2ElementSize) + headerSize + (alignment - 1);
        int mask = ~(alignment - 1);
        return size & mask;
    }

    /**
     * Calls the runtime stub for implementing MULTIANEWARRAY.
     */
    @Snippet
    public static Object newmultiarray(Word hub, @ConstantParameter int rank, @VarargsParameter int[] dimensions) {
        Word dims = DimensionsNode.allocaDimsArray(rank);
        ExplodeLoopNode.explodeLoop();
        for (int i = 0; i < rank; i++) {
            dims.writeInt(i * 4, dimensions[i], INIT_LOCATION);
        }
        return NewMultiArrayStubCall.call(hub, rank, dims);
    }

    /**
     * Maximum number of long stores to emit when zeroing an object with a constant size. Larger
     * objects have their bodies initialized in a loop.
     */
    private static final int MAX_UNROLLED_OBJECT_ZEROING_STORES = 8;

    /**
     * Zero uninitialized memory in a newly allocated object, unrolling as necessary and ensuring
     * that stores are aligned.
     *
     * @param size number of bytes to zero
     * @param memory beginning of object which is being zeroed
     * @param constantSize is @ size} known to be constant in the snippet
     * @param startOffset offset to begin zeroing. May not be word aligned.
     * @param manualUnroll maximally unroll zeroing
     */
    private static void zeroMemory(int size, Word memory, boolean constantSize, int startOffset, boolean manualUnroll, boolean useSnippetCounters) {
        ReplacementsUtil.runtimeAssert((size & 0x7) == 0, "unaligned object size");
        int offset = startOffset;
        if ((offset & 0x7) != 0) {
            memory.writeInt(offset, 0, INIT_LOCATION);
            offset += 4;
        }
        ReplacementsUtil.runtimeAssert((offset & 0x7) == 0, "unaligned offset");
        if (manualUnroll && ((size - offset) / 8) <= MAX_UNROLLED_OBJECT_ZEROING_STORES) {
            ReplacementsUtil.staticAssert(!constantSize, "size shouldn't be constant at instantiation time");
            // This case handles arrays of constant length. Instead of having a snippet variant for
            // each length, generate a chain of stores of maximum length. Once it's inlined the
            // break statement will trim excess stores.
            if (useSnippetCounters) {
                new_seqInit.inc();
            }
            explodeLoop();
            for (int i = 0; i < MAX_UNROLLED_OBJECT_ZEROING_STORES; i++, offset += 8) {
                if (offset == size) {
                    break;
                }
                memory.initializeLong(offset, 0, INIT_LOCATION);
            }
        } else {
            // Use Word instead of int to avoid extension to long in generated code
            Word off = Word.signed(offset);
            if (constantSize && ((size - offset) / 8) <= MAX_UNROLLED_OBJECT_ZEROING_STORES) {
                if (useSnippetCounters) {
                    new_seqInit.inc();
                }
                explodeLoop();
            } else {
                if (useSnippetCounters) {
                    new_loopInit.inc();
                }
            }
            for (; off.rawValue() < size; off = off.add(8)) {
                memory.initializeLong(off, 0, INIT_LOCATION);
            }
        }
    }

    /**
     * Formats some allocated memory with an object header and zeroes out the rest. Disables asserts
     * since they can't be compiled in stubs.
     */
    public static Object formatObjectForStub(KlassPointer hub, int size, Word memory, Word compileTimePrototypeMarkWord) {
        return formatObject(hub, size, memory, compileTimePrototypeMarkWord, true, false, false);
    }

    /**
     * Formats some allocated memory with an object header and zeroes out the rest.
     */
    protected static Object formatObject(KlassPointer hub, int size, Word memory, Word compileTimePrototypeMarkWord, boolean fillContents, boolean constantSize, boolean useSnippetCounters) {
        Word prototypeMarkWord = useBiasedLocking() ? hub.readWord(prototypeMarkWordOffset(), PROTOTYPE_MARK_WORD_LOCATION) : compileTimePrototypeMarkWord;
        initializeObjectHeader(memory, prototypeMarkWord, hub);
        if (fillContents) {
            zeroMemory(size, memory, constantSize, instanceHeaderSize(), false, useSnippetCounters);
        }
        return memory.toObject();
    }

    @Snippet
    protected static void verifyHeap(@ConstantParameter Register threadRegister) {
        Word thread = registerAsWord(threadRegister);
        Word topValue = readTlabTop(thread);
        if (!topValue.equal(Word.zero())) {
            Word topValueContents = topValue.readWord(0, MARK_WORD_LOCATION);
            if (topValueContents.equal(Word.zero())) {
                AssertionSnippets.vmMessageC(AssertionSnippets.ASSERTION_VM_MESSAGE_C, true, cstring("overzeroing of TLAB detected"), 0L, 0L, 0L);
            }
        }
    }

    /**
     * Formats some allocated memory with an object header and zeroes out the rest.
     */
    public static Object formatArray(KlassPointer hub, int allocationSize, int length, int headerSize, Word memory, Word prototypeMarkWord, boolean fillContents, boolean maybeUnroll,
                    boolean useSnippetCounters) {
        memory.writeInt(arrayLengthOffset(), length, INIT_LOCATION);
        /*
         * store hub last as the concurrent garbage collectors assume length is valid if hub field
         * is not null
         */
        initializeObjectHeader(memory, prototypeMarkWord, hub);
        if (fillContents) {
            zeroMemory(allocationSize, memory, false, headerSize, maybeUnroll, useSnippetCounters);
        }
        return memory.toObject();
    }

    public static class Templates extends AbstractTemplates {

        private final SnippetInfo allocateInstance = snippet(NewObjectSnippets.class, "allocateInstance");
        private final SnippetInfo allocateArray = snippet(NewObjectSnippets.class, "allocateArray");
        private final SnippetInfo allocateArrayDynamic = snippet(NewObjectSnippets.class, "allocateArrayDynamic");
        private final SnippetInfo allocateInstanceDynamic = snippet(NewObjectSnippets.class, "allocateInstanceDynamic");
        private final SnippetInfo newmultiarray = snippet(NewObjectSnippets.class, "newmultiarray");
        private final SnippetInfo verifyHeap = snippet(NewObjectSnippets.class, "verifyHeap");

        public Templates(HotSpotProviders providers, TargetDescription target) {
            super(providers, providers.getSnippetReflection(), target);
        }

        /**
         * Lowers a {@link NewInstanceNode}.
         */
        public void lower(NewInstanceNode newInstanceNode, HotSpotRegistersProvider registers, LoweringTool tool) {
            StructuredGraph graph = newInstanceNode.graph();
            HotSpotResolvedObjectType type = (HotSpotResolvedObjectType) newInstanceNode.instanceClass();
            assert !type.isArray();
            ConstantNode hub = ConstantNode.forConstant(KlassPointerStamp.klassNonNull(), type.klass(), providers.getMetaAccess(), graph);
            int size = instanceSize(type);

            Arguments args = new Arguments(allocateInstance, graph.getGuardsStage(), tool.getLoweringStage());
            args.addConst("size", size);
            args.add("hub", hub);
            args.add("prototypeMarkWord", type.prototypeMarkWord());
            args.addConst("fillContents", newInstanceNode.fillContents());
            args.addConst("threadRegister", registers.getThreadRegister());
            args.addConst("constantSize", true);
            args.addConst("typeContext", ProfileAllocations.getValue() ? type.toJavaName(false) : "");

            SnippetTemplate template = template(args);
            Debug.log("Lowering allocateInstance in %s: node=%s, template=%s, arguments=%s", graph, newInstanceNode, template, args);
            template.instantiate(providers.getMetaAccess(), newInstanceNode, DEFAULT_REPLACER, args);
        }

        /**
         * Lowers a {@link NewArrayNode}.
         */
        public void lower(NewArrayNode newArrayNode, HotSpotRegistersProvider registers, HotSpotGraalRuntimeProvider runtime, LoweringTool tool) {
            StructuredGraph graph = newArrayNode.graph();
            ResolvedJavaType elementType = newArrayNode.elementType();
            HotSpotResolvedObjectType arrayType = (HotSpotResolvedObjectType) elementType.getArrayClass();
            Kind elementKind = elementType.getKind();
            ConstantNode hub = ConstantNode.forConstant(KlassPointerStamp.klassNonNull(), arrayType.klass(), providers.getMetaAccess(), graph);
            final int headerSize = runtime.getArrayBaseOffset(elementKind);
            HotSpotLoweringProvider lowerer = (HotSpotLoweringProvider) providers.getLowerer();
            int log2ElementSize = CodeUtil.log2(lowerer.arrayScalingFactor(elementKind));

            Arguments args = new Arguments(allocateArray, graph.getGuardsStage(), tool.getLoweringStage());
            args.add("hub", hub);
            ValueNode length = newArrayNode.length();
            args.add("length", length.isAlive() ? length : graph.addOrUniqueWithInputs(length));
            args.add("prototypeMarkWord", arrayType.prototypeMarkWord());
            args.addConst("headerSize", headerSize);
            args.addConst("log2ElementSize", log2ElementSize);
            args.addConst("fillContents", newArrayNode.fillContents());
            args.addConst("threadRegister", registers.getThreadRegister());
            args.addConst("maybeUnroll", length.isConstant());
            args.addConst("typeContext", ProfileAllocations.getValue() ? arrayType.toJavaName(false) : "");

            SnippetTemplate template = template(args);
            Debug.log("Lowering allocateArray in %s: node=%s, template=%s, arguments=%s", graph, newArrayNode, template, args);
            template.instantiate(providers.getMetaAccess(), newArrayNode, DEFAULT_REPLACER, args);
        }

        public void lower(DynamicNewInstanceNode newInstanceNode, HotSpotRegistersProvider registers, LoweringTool tool) {
            Arguments args = new Arguments(allocateInstanceDynamic, newInstanceNode.graph().getGuardsStage(), tool.getLoweringStage());
            args.add("type", newInstanceNode.getInstanceType());
            args.addConst("fillContents", newInstanceNode.fillContents());
            args.addConst("threadRegister", registers.getThreadRegister());

            SnippetTemplate template = template(args);
            template.instantiate(providers.getMetaAccess(), newInstanceNode, DEFAULT_REPLACER, args);
        }

        public void lower(DynamicNewArrayNode newArrayNode, HotSpotRegistersProvider registers, LoweringTool tool) {
            StructuredGraph graph = newArrayNode.graph();
            Arguments args = new Arguments(allocateArrayDynamic, newArrayNode.graph().getGuardsStage(), tool.getLoweringStage());
            args.add("elementType", newArrayNode.getElementType());
            ValueNode length = newArrayNode.length();
            args.add("length", length.isAlive() ? length : graph.addOrUniqueWithInputs(length));
            args.addConst("fillContents", newArrayNode.fillContents());
            args.addConst("threadRegister", registers.getThreadRegister());

            SnippetTemplate template = template(args);
            template.instantiate(providers.getMetaAccess(), newArrayNode, DEFAULT_REPLACER, args);
        }

        public void lower(NewMultiArrayNode newmultiarrayNode, LoweringTool tool) {
            StructuredGraph graph = newmultiarrayNode.graph();
            int rank = newmultiarrayNode.dimensionCount();
            ValueNode[] dims = new ValueNode[rank];
            for (int i = 0; i < newmultiarrayNode.dimensionCount(); i++) {
                dims[i] = newmultiarrayNode.dimension(i);
            }
            HotSpotResolvedObjectType type = (HotSpotResolvedObjectType) newmultiarrayNode.type();
            ConstantNode hub = ConstantNode.forConstant(KlassPointerStamp.klassNonNull(), type.klass(), providers.getMetaAccess(), graph);

            Arguments args = new Arguments(newmultiarray, graph.getGuardsStage(), tool.getLoweringStage());
            args.add("hub", hub);
            args.addConst("rank", rank);
            args.addVarargs("dimensions", int.class, StampFactory.forKind(Kind.Int), dims);
            template(args).instantiate(providers.getMetaAccess(), newmultiarrayNode, DEFAULT_REPLACER, args);
        }

        private static int instanceSize(HotSpotResolvedObjectType type) {
            int size = type.instanceSize();
            assert size >= 0;
            return size;
        }

        public void lower(VerifyHeapNode verifyHeapNode, HotSpotRegistersProvider registers, HotSpotGraalRuntimeProvider runtime, LoweringTool tool) {
            if (runtime.getConfig().cAssertions) {
                Arguments args = new Arguments(verifyHeap, verifyHeapNode.graph().getGuardsStage(), tool.getLoweringStage());
                args.addConst("threadRegister", registers.getThreadRegister());

                SnippetTemplate template = template(args);
                template.instantiate(providers.getMetaAccess(), verifyHeapNode, DEFAULT_REPLACER, args);
            } else {
                GraphUtil.removeFixedWithUnusedInputs(verifyHeapNode);
            }
        }
    }

    private static final SnippetCounter.Group countersNew = SnippetCounters.getValue() ? new SnippetCounter.Group("NewInstance") : null;
    private static final SnippetCounter new_seqInit = new SnippetCounter(countersNew, "tlabSeqInit", "TLAB alloc with unrolled zeroing");
    private static final SnippetCounter new_loopInit = new SnippetCounter(countersNew, "tlabLoopInit", "TLAB alloc with zeroing in a loop");
    private static final SnippetCounter new_stub = new SnippetCounter(countersNew, "stub", "alloc and zeroing via stub");

    private static final SnippetCounter.Group countersNewArray = SnippetCounters.getValue() ? new SnippetCounter.Group("NewArray") : null;
    private static final SnippetCounter newarray_loopInit = new SnippetCounter(countersNewArray, "tlabLoopInit", "TLAB alloc with zeroing in a loop");
    private static final SnippetCounter newarray_stub = new SnippetCounter(countersNewArray, "stub", "alloc and zeroing via stub");
}
