/*
 * Copyright (c) 2012, 2013, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.hotspot.replacements.HotSpotReplacementsUtil.*;
import static com.oracle.graal.phases.GraalOptions.*;
import static com.oracle.graal.replacements.SnippetTemplate.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.Node.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.replacements.*;
import com.oracle.graal.replacements.Snippet.ConstantParameter;
import com.oracle.graal.replacements.SnippetTemplate.AbstractTemplates;
import com.oracle.graal.replacements.SnippetTemplate.Arguments;
import com.oracle.graal.replacements.SnippetTemplate.SnippetInfo;
import com.oracle.graal.replacements.nodes.*;
import com.oracle.graal.word.*;

public class WriteBarrierSnippets implements Snippets {

    private static final SnippetCounter.Group countersWriteBarriers = SnippetCounters.getValue() ? new SnippetCounter.Group("WriteBarriers") : null;
    private static final SnippetCounter serialFieldWriteBarrierCounter = new SnippetCounter(countersWriteBarriers, "serialFieldWriteBarrier", "Number of Serial Field Write Barriers");
    private static final SnippetCounter serialArrayWriteBarrierCounter = new SnippetCounter(countersWriteBarriers, "serialArrayWriteBarrier", "Number of Serial Array Write Barriers");

    @Snippet
    public static void serialArrayWriteBarrier(Object obj, Object location, @ConstantParameter boolean usePrecise) {
        Object object = FixedValueAnchorNode.getObject(obj);
        Pointer oop;
        if (usePrecise) {
            oop = Word.fromArray(object, location);
            serialArrayWriteBarrierCounter.inc();
        } else {
            oop = Word.fromObject(object);
            serialFieldWriteBarrierCounter.inc();
        }
        Word base = (Word) oop.unsignedShiftRight(cardTableShift());
        long startAddress = cardTableStart();
        int displacement = 0;
        if (((int) startAddress) == startAddress) {
            displacement = (int) startAddress;
        } else {
            base = base.add(Word.unsigned(cardTableStart()));
        }
        base.writeByte(displacement, (byte) 0);
    }

    @Snippet
    public static void serialArrayRangeWriteBarrier(Object object, int startIndex, int length) {
        Object dest = FixedValueAnchorNode.getObject(object);
        int cardShift = cardTableShift();
        long cardStart = cardTableStart();
        final int scale = arrayIndexScale(Kind.Object);
        int header = arrayBaseOffset(Kind.Object);
        long dstAddr = GetObjectAddressNode.get(dest);
        long start = (dstAddr + header + (long) startIndex * scale) >>> cardShift;
        long end = (dstAddr + header + ((long) startIndex + length - 1) * scale) >>> cardShift;
        long count = end - start + 1;
        while (count-- > 0) {
            DirectStoreNode.store((start + cardStart) + count, false, Kind.Boolean);
        }
    }

    /**
     * Log method of debugging purposes.
     */
    static void log(boolean enabled, String format, WordBase value) {
        if (enabled) {
            Log.printf(format, value.rawValue());
        }
    }

    @Snippet
    public static void g1PreWriteBarrier(Object object, Object expectedObject, Object location, @ConstantParameter boolean doLoad) {
        Word thread = thread();
        Object fixedObject = FixedValueAnchorNode.getObject(object);
        Object fixedExpectedObject = FixedValueAnchorNode.getObject(expectedObject);
        Word field = (Word) Word.fromArray(fixedObject, location);
        Word previousOop = (Word) Word.fromObject(fixedExpectedObject);
        byte markingValue = thread.readByte(HotSpotReplacementsUtil.g1SATBQueueMarkingOffset());
        Word bufferAddress = thread.readWord(HotSpotReplacementsUtil.g1SATBQueueBufferOffset());
        Word indexAddress = thread.add(HotSpotReplacementsUtil.g1SATBQueueIndexOffset());
        Word indexValue = indexAddress.readWord(0);

        // If the concurrent marker is enabled, the barrier is issued.
        if (markingValue != (byte) 0) {
            // If the previous value has to be loaded (before the write), the load is issued.
            // The load is always issued except the cases of CAS and referent field.
            if (doLoad) {
                previousOop = (Word) Word.fromObject(field.readObject(0));
            }
            // If the previous value is null the barrier should not be issued.
            if (previousOop.notEqual(0)) {
                // If the thread-local SATB buffer is full issue a native call which will
                // initialize a new one and add the entry.
                if (indexValue.notEqual(0)) {
                    Word nextIndex = indexValue.subtract(HotSpotReplacementsUtil.wordSize());
                    Word logAddress = bufferAddress.add(nextIndex);
                    // Log the object to be marked as well as update the SATB's buffer next index.
                    logAddress.writeWord(0, previousOop);
                    indexAddress.writeWord(0, nextIndex);
                } else {
                    g1PreBarrierStub(G1WBPRECALL, previousOop.toObject());
                }
            }
        }
    }

    @Snippet
    public static void g1PostWriteBarrier(Object object, Object value, Object location, @ConstantParameter boolean usePrecise) {
        Word thread = thread();
        Object fixedObject = FixedValueAnchorNode.getObject(object);
        Object fixedValue = FixedValueAnchorNode.getObject(value);
        Word oop = (Word) Word.fromObject(fixedObject);
        Word field;
        if (usePrecise) {
            field = (Word) Word.fromArray(fixedObject, location);
        } else {
            field = oop;
        }

        Word writtenValue = (Word) Word.fromObject(fixedValue);
        Word bufferAddress = thread.readWord(HotSpotReplacementsUtil.g1CardQueueBufferOffset());
        Word indexAddress = thread.add(HotSpotReplacementsUtil.g1CardQueueIndexOffset());
        Word indexValue = thread.readWord(HotSpotReplacementsUtil.g1CardQueueIndexOffset());
        // The result of the xor reveals whether the installed pointer crosses heap regions.
        // In case it does the write barrier has to be issued.
        Word xorResult = (field.xor(writtenValue)).unsignedShiftRight(HotSpotReplacementsUtil.logOfHeapRegionGrainBytes());

        // Calculate the address of the card to be enqueued to the
        // thread local card queue.
        Word cardBase = field.unsignedShiftRight(cardTableShift());
        long startAddress = cardTableStart();
        int displacement = 0;
        if (((int) startAddress) == startAddress) {
            displacement = (int) startAddress;
        } else {
            cardBase = cardBase.add(Word.unsigned(cardTableStart()));
        }
        Word cardAddress = cardBase.add(displacement);

        if (xorResult.notEqual(0)) {
            // If the written value is not null continue with the barrier addition.
            if (writtenValue.notEqual(0)) {
                byte cardByte = cardAddress.readByte(0);
                // If the card is already dirty, (hence already enqueued) skip the insertion.
                if (cardByte != (byte) 0) {
                    cardAddress.writeByte(0, (byte) 0);
                    // If the thread local card queue is full, issue a native call which will
                    // initialize a new one and add the card entry.
                    if (indexValue.notEqual(0)) {
                        Word nextIndex = indexValue.subtract(HotSpotReplacementsUtil.wordSize());
                        Word logAddress = bufferAddress.add(nextIndex);
                        // Log the object to be scanned as well as update
                        // the card queue's next index.
                        logAddress.writeWord(0, cardAddress);
                        indexAddress.writeWord(0, nextIndex);
                    } else {
                        g1PostBarrierStub(G1WBPOSTCALL, cardAddress);
                    }
                }
            }
        }
    }

    public static final ForeignCallDescriptor G1WBPRECALL = new ForeignCallDescriptor("write_barrier_pre", void.class, Object.class);

    @NodeIntrinsic(ForeignCallNode.class)
    private static native void g1PreBarrierStub(@ConstantNodeParameter ForeignCallDescriptor descriptor, Object object);

    public static final ForeignCallDescriptor G1WBPOSTCALL = new ForeignCallDescriptor("write_barrier_post", void.class, Word.class);

    @NodeIntrinsic(ForeignCallNode.class)
    private static native void g1PostBarrierStub(@ConstantNodeParameter ForeignCallDescriptor descriptor, Word card);

    public static class Templates extends AbstractTemplates {

        private final SnippetInfo serialArrayWriteBarrier = snippet(WriteBarrierSnippets.class, "serialArrayWriteBarrier");
        private final SnippetInfo serialArrayRangeWriteBarrier = snippet(WriteBarrierSnippets.class, "serialArrayRangeWriteBarrier");
        private final SnippetInfo g1PreWriteBarrier = snippet(WriteBarrierSnippets.class, "g1PreWriteBarrier");
        private final SnippetInfo g1PostWriteBarrier = snippet(WriteBarrierSnippets.class, "g1PostWriteBarrier");

        public Templates(CodeCacheProvider runtime, Replacements replacements, TargetDescription target) {
            super(runtime, replacements, target);
        }

        public void lower(SerialWriteBarrier arrayWriteBarrier, @SuppressWarnings("unused") LoweringTool tool) {
            Arguments args = new Arguments(serialArrayWriteBarrier);
            args.add("obj", arrayWriteBarrier.getObject());
            args.add("location", arrayWriteBarrier.getLocation());
            args.addConst("usePrecise", arrayWriteBarrier.usePrecise());
            template(args).instantiate(runtime, arrayWriteBarrier, DEFAULT_REPLACER, args);
        }

        public void lower(SerialArrayRangeWriteBarrier arrayRangeWriteBarrier, @SuppressWarnings("unused") LoweringTool tool) {
            Arguments args = new Arguments(serialArrayRangeWriteBarrier);
            args.add("object", arrayRangeWriteBarrier.getObject());
            args.add("startIndex", arrayRangeWriteBarrier.getStartIndex());
            args.add("length", arrayRangeWriteBarrier.getLength());
            template(args).instantiate(runtime, arrayRangeWriteBarrier, DEFAULT_REPLACER, args);
        }

        public void lower(G1PreWriteBarrier writeBarrierPre, @SuppressWarnings("unused") LoweringTool tool) {
            Arguments args = new Arguments(g1PreWriteBarrier);
            args.add("object", writeBarrierPre.getObject());
            args.add("expectedObject", writeBarrierPre.getExpectedObject());
            args.add("location", writeBarrierPre.getLocation());
            args.addConst("doLoad", writeBarrierPre.doLoad());
            template(args).instantiate(runtime, writeBarrierPre, DEFAULT_REPLACER, args);
        }

        public void lower(G1PostWriteBarrier writeBarrierPost, @SuppressWarnings("unused") LoweringTool tool) {
            Arguments args = new Arguments(g1PostWriteBarrier);
            args.add("object", writeBarrierPost.getObject());
            args.add("value", writeBarrierPost.getValue());
            args.add("location", writeBarrierPost.getLocation());
            args.addConst("usePrecise", writeBarrierPost.usePrecise());
            template(args).instantiate(runtime, writeBarrierPost, DEFAULT_REPLACER, args);
        }
    }
}
