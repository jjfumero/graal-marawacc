/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot;

/**
 * Used to communicate configuration details, runtime offsets, etc. to Graal upon compileMethod.
 */
public final class HotSpotVMConfig extends CompilerObject {

    private static final long serialVersionUID = -4744897993263044184L;

    HotSpotVMConfig() {
    }

    // os information, register layout, code generation, ...
    public boolean windowsOs;
    public int codeEntryAlignment;
    public boolean verifyOops;
    public boolean ciTime;
    public boolean useFastLocking;
    public boolean useTLAB;
    public boolean useBiasedLocking;
    public boolean usePopCountInstruction;
    public boolean useAESIntrinsics;
    public boolean useG1GC;

    // offsets, ...
    public int vmPageSize;
    public int stackShadowPages;

    /**
     * The offset of the mark word in an object's header.
     */
    public int markOffset;

    /**
     * The offset of the hub (i.e. Klass*) in an object's header.
     */
    public int hubOffset;

    /**
     * The offset of the _prototype_header field in a Klass.
     */
    public int prototypeMarkWordOffset;

    /**
     * The offset of the _subklass field in a Klass.
     */
    public int subklassOffset;

    /**
     * The offset of the _next_sibling field in a Klass.
     */
    public int nextSiblingOffset;

    /**
     * The offset of the array length word in an array object's header.
     */
    public int arrayLengthOffset;

    /**
     * The offset of the _length field in an Array metaspace object (see array.hpp).
     */
    public int metaspaceArrayLengthOffset;

    /**
     * The offset of the _data field in an Array metaspace object (see array.hpp).
     */
    public int metaspaceArrayBaseOffset;

    /**
     * The offset of the _super_check_offset field in a Klass.
     */
    public int superCheckOffsetOffset;

    /**
     * The offset of the _secondary_super_cache field in a Klass.
     */
    public int secondarySuperCacheOffset;

    /**
     * The offset of the _secondary_supers field in a Klass.
     */
    public int secondarySupersOffset;

    /**
     * The offset of the _init_state field in an instanceKlass.
     */
    public int klassStateOffset;

    /**
     * The value of instanceKlass::fully_initialized.
     */
    public int klassStateFullyInitialized;

    /**
     * The value of objArrayKlass::element_klass_offset().
     */
    public int arrayClassElementOffset;

    /**
     * The value of JavaThread::tlab_top_offset().
     */
    public int threadTlabTopOffset;

    /**
     * The value of JavaThread::tlab_end_offset().
     */
    public int threadTlabEndOffset;

    public int threadObjectOffset;

    /**
     * The value of JavaThread::osthread_offset().
     */
    public int osThreadOffset;

    /**
     * The value of OSThread::interrupted_offset().
     */
    public int osThreadInterruptedOffset;

    /**
     * The value of markOopDesc::unlocked_value.
     */
    public int unlockedMask;

    /**
     * The value of markOopDesc::biased_lock_mask_in_place.
     */
    public int biasedLockMaskInPlace;

    /**
     * The value of markOopDesc::age_mask_in_place.
     */
    public int ageMaskInPlace;

    /**
     * The value of markOopDesc::epoch_mask_in_place.
     */
    public int epochMaskInPlace;

    /**
     * The value of markOopDesc::biased_lock_pattern.
     */
    public int biasedLockPattern;

    /**
     * Identity hash code value when uninitialized.
     */
    public int uninitializedIdentityHashCodeValue;

    /**
     * Mark word right shift to get identity hash code.
     */
    public int identityHashCodeShift;

    /**
     * Offset of _access_flags in a metaspace Method object.
     */
    public int methodAccessFlagsOffset;

    /**
     * Offset of _max_locals in a metaspace Method object.
     */
    public int methodMaxLocalsOffset;

    /**
     * Offset of _constMethod in a metaspace Method object.
     */
    public int methodConstMethodOffset;

    /**
     * Offset of _max_stack in a metaspace ConstMethod object.
     */
    public int constMethodMaxStackOffset;

    /**
     * Value of extra_stack_entries() in method.hpp.
     */
    public int extraStackEntries;

    /**
     * Value of JVM_ACC_HAS_FINALIZER in accessFlags.hpp.
     */
    public int klassHasFinalizerFlag;

    public int threadExceptionOopOffset;
    public int threadExceptionPcOffset;
    public long cardtableStartAddress;
    public int cardtableShift;
    public long safepointPollingAddress;
    public boolean isPollingPageFar;

    /**
     * G1 Collector Related Values.
     */
    public int g1CardQueueIndexOffset;
    public int g1CardQueueBufferOffset;
    public int logOfHRGrainBytes;
    public int g1SATBQueueMarkingOffset;
    public int g1SATBQueueIndexOffset;
    public int g1SATBQueueBufferOffset;

    /**
     * The offset of the _java_mirror field (of type {@link Class}) in a Klass.
     */
    public int classMirrorOffset;

    public int runtimeCallStackSize;

    /**
     * The offset of the _modifier_flags field in a Klass.
     */
    public int klassModifierFlagsOffset;

    /**
     * The offset of the _access_flags field in a Klass.
     */
    public int klassAccessFlagsOffset;

    /**
     * The offset of the _layout_helper field in a Klass.
     */
    public int klassLayoutHelperOffset;

    /**
     * Bit pattern in the klass layout helper that can be used to identify arrays.
     */
    public int arrayKlassLayoutHelperIdentifier;

    /**
     * The offset of the _componentMirror field in an ArrayKlass.
     */
    public int arrayKlassComponentMirrorOffset;

    /**
     * The offset of the _super field in a Klass.
     */
    public int klassSuperKlassOffset;

    /**
     * The offset of the injected klass field in a {@link Class}.
     */
    public int klassOffset;

    /**
     * The offset of the injected graal_mirror field in a {@link Class}.
     */
    public int graalMirrorInClassOffset;

    /**
     * The offset of the _method_data field in a metaspace Method.
     */
    public int methodDataOffset;

    public int nmethodEntryOffset;
    public int methodCompiledEntryOffset;
    public int basicLockSize;
    public int basicLockDisplacedHeaderOffset;
    public long tlabIntArrayMarkWord;
    public long heapEndAddress;
    public long heapTopAddress;
    public int threadTlabStartOffset;
    public int threadTlabSizeOffset;
    public int threadAllocatedBytesOffset;
    public int tlabRefillWasteLimitOffset;
    public int tlabRefillWasteIncrement;
    public int tlabAlignmentReserve;
    public int tlabSlowAllocationsOffset;
    public int tlabFastRefillWasteOffset;
    public int tlabNumberOfRefillsOffset;
    public boolean tlabStats;
    public int klassInstanceSizeOffset;
    public boolean inlineContiguousAllocationSupported;
    public long arrayPrototypeMarkWord;
    public int layoutHelperLog2ElementSizeShift;
    public int layoutHelperLog2ElementSizeMask;
    public int layoutHelperElementTypeShift;
    public int layoutHelperElementTypeMask;
    public int layoutHelperElementTypePrimitiveInPlace;
    public int layoutHelperHeaderSizeShift;
    public int layoutHelperHeaderSizeMask;
    public int layoutHelperOffset;

    // methodData information
    public int methodDataOopDataOffset;
    public int methodDataOopTrapHistoryOffset;
    public int dataLayoutHeaderSize;
    public int dataLayoutTagOffset;
    public int dataLayoutFlagsOffset;
    public int dataLayoutBCIOffset;
    public int dataLayoutCellsOffset;
    public int dataLayoutCellSize;
    public int bciProfileWidth;
    public int typeProfileWidth;

    // runtime stubs
    public long debugStub;
    public long instanceofStub;
    public long newInstanceStub;
    public long newArrayStub;
    public long newMultiArrayStub;
    public long inlineCacheMissStub;
    public long handleExceptionStub;
    public long handleDeoptStub;
    public long monitorEnterStub;
    public long monitorExitStub;
    public long wbPreCallStub;
    public long wbPostCallStub;
    public long verifyOopStub;
    public long vmErrorStub;
    public long deoptimizeStub;
    public long unwindExceptionStub;
    public long osrMigrationEndStub;
    public long registerFinalizerStub;
    public long setDeoptInfoStub;
    public long createNullPointerExceptionStub;
    public long createOutOfBoundsExceptionStub;
    public long javaTimeMillisStub;
    public long javaTimeNanosStub;
    public long arithmeticFremStub;
    public long arithmeticDremStub;
    public long arithmeticSinStub;
    public long arithmeticCosStub;
    public long arithmeticTanStub;
    public long logPrimitiveStub;
    public long logObjectStub;
    public long logPrintfStub;
    public int deoptReasonNone;
    public long threadIsInterruptedStub;
    public long identityHashCodeStub;
    public long aescryptEncryptBlockStub;
    public long aescryptDecryptBlockStub;
    public long cipherBlockChainingEncryptAESCryptStub;
    public long cipherBlockChainingDecryptAESCryptStub;

    public int deoptReasonNullCheck;
    public int deoptReasonRangeCheck;
    public int deoptReasonClassCheck;
    public int deoptReasonArrayCheck;
    public int deoptReasonUnreached0;
    public int deoptReasonTypeCheckInlining;
    public int deoptReasonOptimizedTypeCheck;
    public int deoptReasonNotCompiledExceptionHandler;
    public int deoptReasonUnresolved;
    public int deoptReasonJsrMismatch;
    public int deoptReasonDiv0Check;
    public int deoptReasonConstraint;

    public int deoptActionNone;
    public int deoptActionMaybeRecompile;
    public int deoptActionReinterpret;
    public int deoptActionMakeNotEntrant;
    public int deoptActionMakeNotCompilable;

    public void check() {
        assert vmPageSize >= 16;
        assert codeEntryAlignment > 0;
        assert stackShadowPages > 0;
    }
}
