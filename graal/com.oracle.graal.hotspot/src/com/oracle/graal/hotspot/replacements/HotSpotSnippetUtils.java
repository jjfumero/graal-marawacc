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
package com.oracle.graal.hotspot.replacements;

import static com.oracle.graal.hotspot.HotSpotGraalRuntime.*;
import static com.oracle.graal.replacements.nodes.BranchProbabilityNode.*;
import sun.misc.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.Node.ConstantNodeParameter;
import com.oracle.graal.graph.Node.NodeIntrinsic;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.replacements.Snippet.Fold;
import com.oracle.graal.replacements.nodes.*;
import com.oracle.graal.word.*;

//JaCoCo Exclude

/**
 * A collection of methods used in HotSpot snippets and substitutions.
 */
public class HotSpotSnippetUtils {

    public static final Object ANY_LOCATION = LocationNode.ANY_LOCATION;
    public static final Object UNKNOWN_LOCATION = LocationNode.UNKNOWN_LOCATION;
    public static final Object FINAL_LOCATION = LocationNode.FINAL_LOCATION;

    public static HotSpotVMConfig config() {
        return HotSpotGraalRuntime.getInstance().getConfig();
    }

    @Fold
    public static boolean useTLAB() {
        return config().useTLAB;
    }

    @Fold
    public static boolean verifyOops() {
        return config().verifyOops;
    }

    public static final Object TLAB_TOP_LOCATION = LocationNode.createLocation("TlabTop");

    @Fold
    public static int threadTlabTopOffset() {
        return config().threadTlabTopOffset;
    }

    public static final Object TLAB_END_LOCATION = LocationNode.createLocation("TlabEnd");

    @Fold
    private static int threadTlabEndOffset() {
        return config().threadTlabEndOffset;
    }

    public static final Object TLAB_START_LOCATION = LocationNode.createLocation("TlabStart");

    @Fold
    private static int threadTlabStartOffset() {
        return config().threadTlabStartOffset;
    }

    public static Word readTlabTop(Word thread) {
        return thread.readWord(threadTlabTopOffset(), TLAB_TOP_LOCATION);
    }

    public static Word readTlabEnd(Word thread) {
        return thread.readWord(threadTlabEndOffset(), TLAB_END_LOCATION);
    }

    public static Word readTlabStart(Word thread) {
        return thread.readWord(threadTlabStartOffset(), TLAB_START_LOCATION);
    }

    public static void writeTlabTop(Word thread, Word top) {
        thread.writeWord(threadTlabTopOffset(), top, TLAB_TOP_LOCATION);
    }

    public static void initializeTlab(Word thread, Word start, Word end) {
        thread.writeWord(threadTlabStartOffset(), start, TLAB_START_LOCATION);
        thread.writeWord(threadTlabTopOffset(), start, TLAB_TOP_LOCATION);
        thread.writeWord(threadTlabEndOffset(), end, TLAB_END_LOCATION);
    }

    @Fold
    public static int threadObjectOffset() {
        return config().threadObjectOffset;
    }

    @Fold
    public static int osThreadOffset() {
        try {
            return (int) UnsafeAccess.unsafe.objectFieldOffset(Thread.class.getDeclaredField("eetop"));
        } catch (Exception e) {
            throw new GraalInternalError(e);
        }
    }

    @Fold
    public static int osThreadInterruptedOffset() {
        return config().osThreadInterruptedOffset;
    }

    @Fold
    public static Kind wordKind() {
        return HotSpotGraalRuntime.getInstance().getTarget().wordKind;
    }

    @Fold
    public static Register threadRegister() {
        return HotSpotGraalRuntime.getInstance().getRuntime().threadRegister();
    }

    @Fold
    public static Register stackPointerRegister() {
        return HotSpotGraalRuntime.getInstance().getRuntime().stackPointerRegister();
    }

    @Fold
    public static int wordSize() {
        return HotSpotGraalRuntime.getInstance().getTarget().wordSize;
    }

    @Fold
    public static int pageSize() {
        return Unsafe.getUnsafe().pageSize();
    }

    public static final Object PROTOTYPE_MARK_WORD_LOCATION = LocationNode.createLocation("PrototypeMarkWord");

    @Fold
    public static int prototypeMarkWordOffset() {
        return config().prototypeMarkWordOffset;
    }

    @Fold
    public static long arrayPrototypeMarkWord() {
        return config().arrayPrototypeMarkWord;
    }

    @Fold
    public static int klassAccessFlagsOffset() {
        return config().klassAccessFlagsOffset;
    }

    @Fold
    private static int klassLayoutHelperOffset() {
        return config().klassLayoutHelperOffset;
    }

    public static int readLayoutHelper(Word hub) {
        return hub.readInt(klassLayoutHelperOffset(), FINAL_LOCATION);
    }

    @Fold
    public static int arrayKlassLayoutHelperIdentifier() {
        return config().arrayKlassLayoutHelperIdentifier;
    }

    @Fold
    public static int arrayKlassComponentMirrorOffset() {
        return config().arrayKlassComponentMirrorOffset;
    }

    @Fold
    public static int klassSuperKlassOffset() {
        return config().klassSuperKlassOffset;
    }

    public static final Object MARK_WORD_LOCATION = LocationNode.createLocation("MarkWord");

    @Fold
    public static int markOffset() {
        return config().markOffset;
    }

    public static final Object HUB_LOCATION = LocationNode.createLocation("Hub");

    @Fold
    private static int hubOffset() {
        return config().hubOffset;
    }

    public static void initializeObjectHeader(Word memory, Word markWord, Word hub) {
        memory.writeWord(markOffset(), markWord, MARK_WORD_LOCATION);
        memory.writeWord(hubOffset(), hub, HUB_LOCATION);
    }

    @Fold
    public static int unlockedMask() {
        return config().unlockedMask;
    }

    /**
     * Mask for a biasable, locked or unlocked mark word.
     * 
     * <pre>
     * +----------------------------------+-+-+
     * |                                 1|1|1|
     * +----------------------------------+-+-+
     * </pre>
     * 
     */
    @Fold
    public static int biasedLockMaskInPlace() {
        return config().biasedLockMaskInPlace;
    }

    @Fold
    public static int epochMaskInPlace() {
        return config().epochMaskInPlace;
    }

    /**
     * Pattern for a biasable, unlocked mark word.
     * 
     * <pre>
     * +----------------------------------+-+-+
     * |                                 1|0|1|
     * +----------------------------------+-+-+
     * </pre>
     * 
     */
    @Fold
    public static int biasedLockPattern() {
        return config().biasedLockPattern;
    }

    @Fold
    public static int ageMaskInPlace() {
        return config().ageMaskInPlace;
    }

    @Fold
    public static int metaspaceArrayLengthOffset() {
        return config().metaspaceArrayLengthOffset;
    }

    @Fold
    public static int metaspaceArrayBaseOffset() {
        return config().metaspaceArrayBaseOffset;
    }

    @Fold
    public static int arrayLengthOffset() {
        return config().arrayLengthOffset;
    }

    @Fold
    public static int arrayBaseOffset(Kind elementKind) {
        return HotSpotRuntime.getArrayBaseOffset(elementKind);
    }

    @Fold
    public static int arrayIndexScale(Kind elementKind) {
        return HotSpotRuntime.getArrayIndexScale(elementKind);
    }

    @Fold
    public static int cardTableShift() {
        return config().cardtableShift;
    }

    @Fold
    public static long cardTableStart() {
        return config().cardtableStartAddress;
    }

    @Fold
    public static int g1CardQueueIndexOffset() {
        return config().g1CardQueueIndexOffset;
    }

    @Fold
    public static int g1CardQueueBufferOffset() {
        return config().g1CardQueueBufferOffset;
    }

    @Fold
    public static int logOfHRGrainBytes() {
        return config().logOfHRGrainBytes;
    }

    @Fold
    public static int g1SATBQueueMarkingOffset() {
        return config().g1SATBQueueMarkingOffset;
    }

    @Fold
    public static int g1SATBQueueIndexOffset() {
        return config().g1SATBQueueIndexOffset;
    }

    @Fold
    public static int g1SATBQueueBufferOffset() {
        return config().g1SATBQueueBufferOffset;
    }

    @Fold
    public static int superCheckOffsetOffset() {
        return config().superCheckOffsetOffset;
    }

    public static final Object SECONDARY_SUPER_CACHE_LOCATION = LocationNode.createLocation("SecondarySuperCache");

    @Fold
    public static int secondarySuperCacheOffset() {
        return config().secondarySuperCacheOffset;
    }

    public static final Object SECONDARY_SUPERS_LOCATION = LocationNode.createLocation("SecondarySupers");

    @Fold
    public static int secondarySupersOffset() {
        return config().secondarySupersOffset;
    }

    public static final Object DISPLACED_MARK_WORD_LOCATION = LocationNode.createLocation("DisplacedMarkWord");

    @Fold
    public static int lockDisplacedMarkOffset() {
        return config().basicLockDisplacedHeaderOffset;
    }

    @Fold
    public static boolean useBiasedLocking() {
        return config().useBiasedLocking;
    }

    @Fold
    public static boolean useG1GC() {
        return config().useG1GC;
    }

    @Fold
    static int uninitializedIdentityHashCodeValue() {
        return config().uninitializedIdentityHashCodeValue;
    }

    @Fold
    static int identityHashCodeShift() {
        return config().identityHashCodeShift;
    }

    /**
     * Loads the hub from a object, null checking it first.
     */
    public static Word loadHub(Object object) {
        return loadHubIntrinsic(object, wordKind());
    }

    public static Object verifyOop(Object object) {
        if (verifyOops()) {
            VerifyOopStubCall.call(object);
        }
        return object;
    }

    /**
     * Gets the value of the stack pointer register as a Word.
     */
    public static Word stackPointer() {
        return HotSpotSnippetUtils.registerAsWord(stackPointerRegister(), true, false);
    }

    /**
     * Gets the value of the thread register as a Word.
     */
    public static Word thread() {
        return HotSpotSnippetUtils.registerAsWord(threadRegister(), true, false);
    }

    public static Word loadWordFromObject(Object object, int offset) {
        return loadWordFromObjectIntrinsic(object, 0, offset, wordKind());
    }

    @NodeIntrinsic(value = ReadRegisterNode.class, setStampFromReturnType = true)
    public static native Word registerAsWord(@ConstantNodeParameter Register register, @ConstantNodeParameter boolean directUse, @ConstantNodeParameter boolean incoming);

    @SuppressWarnings("unused")
    @NodeIntrinsic(value = UnsafeLoadNode.class, setStampFromReturnType = true)
    private static Word loadWordFromObjectIntrinsic(Object object, @ConstantNodeParameter int displacement, long offset, @ConstantNodeParameter Kind wordKind) {
        return Word.box(unsafeReadWord(object, offset + displacement));
    }

    @SuppressWarnings("unused")
    @NodeIntrinsic(value = LoadHubNode.class, setStampFromReturnType = true)
    static Word loadHubIntrinsic(Object object, @ConstantNodeParameter Kind word) {
        return Word.box(unsafeReadWord(object, hubOffset()));
    }

    @Fold
    public static int log2WordSize() {
        return CodeUtil.log2(wordSize());
    }

    public static final Object CLASS_STATE_LOCATION = LocationNode.createLocation("ClassState");

    @Fold
    public static int klassStateOffset() {
        return config().klassStateOffset;
    }

    @Fold
    public static int klassStateFullyInitialized() {
        return config().klassStateFullyInitialized;
    }

    @Fold
    public static int klassModifierFlagsOffset() {
        return config().klassModifierFlagsOffset;
    }

    @Fold
    public static int klassOffset() {
        return config().klassOffset;
    }

    @Fold
    public static int classMirrorOffset() {
        return config().classMirrorOffset;
    }

    @Fold
    public static int klassInstanceSizeOffset() {
        return config().klassInstanceSizeOffset;
    }

    public static final Object HEAP_TOP_LOCATION = LocationNode.createLocation("HeapTop");

    @Fold
    public static long heapTopAddress() {
        return config().heapTopAddress;
    }

    public static final Object HEAP_END_LOCATION = LocationNode.createLocation("HeapEnd");

    @Fold
    public static long heapEndAddress() {
        return config().heapEndAddress;
    }

    @Fold
    public static long tlabIntArrayMarkWord() {
        return config().tlabIntArrayMarkWord;
    }

    @Fold
    public static boolean inlineContiguousAllocationSupported() {
        return config().inlineContiguousAllocationSupported;
    }

    @Fold
    public static int tlabAlignmentReserveInHeapWords() {
        return config().tlabAlignmentReserve;
    }

    public static final Object TLAB_SIZE_LOCATION = LocationNode.createLocation("TlabSize");

    @Fold
    public static int threadTlabSizeOffset() {
        return config().threadTlabSizeOffset;
    }

    public static final Object TLAB_THREAD_ALLOCATED_BYTES_LOCATION = LocationNode.createLocation("TlabThreadAllocatedBytes");

    @Fold
    public static int threadAllocatedBytesOffset() {
        return config().threadAllocatedBytesOffset;
    }

    public static final Object TLAB_REFILL_WASTE_LIMIT_LOCATION = LocationNode.createLocation("RefillWasteLimit");

    @Fold
    public static int tlabRefillWasteLimitOffset() {
        return config().tlabRefillWasteLimitOffset;
    }

    public static final Object TLAB_NOF_REFILLS_LOCATION = LocationNode.createLocation("TlabNOfRefills");

    @Fold
    public static int tlabNumberOfRefillsOffset() {
        return config().tlabNumberOfRefillsOffset;
    }

    public static final Object TLAB_FAST_REFILL_WASTE_LOCATION = LocationNode.createLocation("TlabFastRefillWaste");

    @Fold
    public static int tlabFastRefillWasteOffset() {
        return config().tlabFastRefillWasteOffset;
    }

    public static final Object TLAB_SLOW_ALLOCATIONS_LOCATION = LocationNode.createLocation("TlabSlowAllocations");

    @Fold
    public static int tlabSlowAllocationsOffset() {
        return config().tlabSlowAllocationsOffset;
    }

    @Fold
    public static int tlabRefillWasteIncrement() {
        return config().tlabRefillWasteIncrement;
    }

    @Fold
    public static boolean tlabStats() {
        return config().tlabStats;
    }

    @Fold
    public static int layoutHelperOffset() {
        return config().layoutHelperOffset;
    }

    @Fold
    public static int layoutHelperHeaderSizeShift() {
        return config().layoutHelperHeaderSizeShift;
    }

    @Fold
    public static int layoutHelperHeaderSizeMask() {
        return config().layoutHelperHeaderSizeMask;
    }

    @Fold
    public static int layoutHelperLog2ElementSizeShift() {
        return config().layoutHelperLog2ElementSizeShift;
    }

    @Fold
    public static int layoutHelperLog2ElementSizeMask() {
        return config().layoutHelperLog2ElementSizeMask;
    }

    @Fold
    public static int layoutHelperElementTypeShift() {
        return config().layoutHelperElementTypeShift;
    }

    @Fold
    public static int layoutHelperElementTypeMask() {
        return config().layoutHelperElementTypeMask;
    }

    @Fold
    public static int layoutHelperElementTypePrimitiveInPlace() {
        return config().layoutHelperElementTypePrimitiveInPlace;
    }

    static {
        assert arrayIndexScale(Kind.Byte) == 1;
        assert arrayIndexScale(Kind.Boolean) == 1;
        assert arrayIndexScale(Kind.Char) == 2;
        assert arrayIndexScale(Kind.Short) == 2;
        assert arrayIndexScale(Kind.Int) == 4;
        assert arrayIndexScale(Kind.Long) == 8;
        assert arrayIndexScale(Kind.Float) == 4;
        assert arrayIndexScale(Kind.Double) == 8;
    }

    static int computeHashCode(Object x) {
        Word mark = loadWordFromObject(x, markOffset());

        // this code is independent from biased locking (although it does not look that way)
        final Word biasedLock = mark.and(biasedLockMaskInPlace());
        if (biasedLock.equal(Word.unsigned(unlockedMask()))) {
            probability(FAST_PATH_PROBABILITY);
            int hash = (int) mark.unsignedShiftRight(identityHashCodeShift()).rawValue();
            if (hash != uninitializedIdentityHashCodeValue()) {
                probability(FAST_PATH_PROBABILITY);
                return hash;
            }
        }

        return IdentityHashCodeStubCall.call(x);
    }
}
