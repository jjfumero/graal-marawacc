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

import static com.oracle.graal.hotspot.replacements.HotSpotSnippetUtils.*;
import static com.oracle.graal.replacements.SnippetTemplate.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.replacements.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.replacements.*;
import com.oracle.graal.replacements.Snippet.ConstantParameter;
import com.oracle.graal.replacements.Snippet.Parameter;
import com.oracle.graal.replacements.SnippetTemplate.AbstractTemplates;
import com.oracle.graal.replacements.SnippetTemplate.Arguments;
import com.oracle.graal.replacements.SnippetTemplate.Key;
import com.oracle.graal.word.*;

public class WriteBarrierSnippets implements Snippets {

    @Snippet
    public static void g1PreWriteBarrier(@Parameter("object") Object obj, @Parameter("expectedObject") Object expobj, @Parameter("location") Object location,
                    @ConstantParameter("doLoad") boolean doLoad) {
        Word thread = thread();
        Object object = FixedValueAnchorNode.getObject(obj);
        Object expectedObject = FixedValueAnchorNode.getObject(expobj);
        Word field = (Word) Word.fromArray(object, location);
        Word previousOop = (Word) Word.fromObject(expectedObject);
        byte markingValue = thread.readByte(HotSpotSnippetUtils.g1SATBQueueMarkingOffset());

        Word bufferAddress = thread.readWord(HotSpotSnippetUtils.g1SATBQueueBufferOffset());
        Word indexAddress = thread.add(HotSpotSnippetUtils.g1SATBQueueIndexOffset());
        Word indexValue = indexAddress.readWord(0);

        if (markingValue != (byte) 0) {
            if (doLoad) {
                previousOop = field.readWord(0);
            }
            if (previousOop.notEqual(Word.zero())) {
                if (indexValue.notEqual(Word.zero())) {
                    Word nextIndex = indexValue.subtract(HotSpotSnippetUtils.wordSize());
                    Word logAddress = bufferAddress.add(nextIndex);
                    logAddress.writeWord(0, previousOop);
                    indexAddress.writeWord(0, nextIndex);
                } else {
                    WriteBarrierPreStubCall.call(previousOop);

                }
            }
        }
    }

    @Snippet
    public static void g1PostWriteBarrier(@Parameter("object") Object obj, @Parameter("value") Object value, @Parameter("location") Object location, @ConstantParameter("usePrecise") boolean usePrecise) {
        Word thread = thread();
        Object object = FixedValueAnchorNode.getObject(obj);
        Object wrObject = FixedValueAnchorNode.getObject(value);
        Word oop = (Word) Word.fromObject(object);
        Word field;
        if (usePrecise) {
            field = (Word) Word.fromArray(object, location);
        } else {
            field = oop;
        }
        Word writtenValue = (Word) Word.fromObject(wrObject);
        Word bufferAddress = thread.readWord(HotSpotSnippetUtils.g1CardQueueBufferOffset());
        Word indexAddress = thread.add(HotSpotSnippetUtils.g1CardQueueIndexOffset());
        Word indexValue = thread.readWord(HotSpotSnippetUtils.g1CardQueueIndexOffset());
        Word xorResult = (field.xor(writtenValue)).unsignedShiftRight(HotSpotSnippetUtils.logOfHRGrainBytes());

        // Card Table
        Word cardBase = field.unsignedShiftRight(cardTableShift());
        long startAddress = cardTableStart();
        int displacement = 0;
        if (((int) startAddress) == startAddress) {
            displacement = (int) startAddress;
        } else {
            cardBase = cardBase.add(Word.unsigned(cardTableStart()));
        }
        Word cardAddress = cardBase.add(displacement);

        if (xorResult.notEqual(Word.zero())) {
            if (writtenValue.notEqual(Word.zero())) {
                byte cardByte = cardAddress.readByte(0);
                if (cardByte != (byte) 0) {
                    cardAddress.writeByte(0, (byte) 0); // smash zero into card
                    if (indexValue.notEqual(Word.zero())) {
                        Word nextIndex = indexValue.subtract(HotSpotSnippetUtils.wordSize());
                        Word logAddress = bufferAddress.add(nextIndex);
                        logAddress.writeWord(0, cardAddress);
                        indexAddress.writeWord(0, nextIndex);
                    } else {
                        WriteBarrierPostStubCall.call(object, cardAddress);
                    }
                }
            }
        }
    }

    @Snippet
    public static void serialFieldWriteBarrier(@Parameter("object") Object obj) {
        Object object = FixedValueAnchorNode.getObject(obj);
        Pointer oop = Word.fromObject(object);
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
    public static void serialArrayWriteBarrier(@Parameter("object") Object obj, @Parameter("location") Object location) {
        Object object = FixedValueAnchorNode.getObject(obj);
        Pointer oop = Word.fromArray(object, location);
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

    public static class Templates extends AbstractTemplates<WriteBarrierSnippets> {

        private final ResolvedJavaMethod serialFieldWriteBarrier;
        private final ResolvedJavaMethod serialArrayWriteBarrier;
        private final ResolvedJavaMethod g1PreWriteBarrier;
        private final ResolvedJavaMethod g1PostWriteBarrier;

        public Templates(CodeCacheProvider runtime, Replacements replacements, TargetDescription target) {
            super(runtime, replacements, target, WriteBarrierSnippets.class);
            serialFieldWriteBarrier = snippet("serialFieldWriteBarrier", Object.class);
            serialArrayWriteBarrier = snippet("serialArrayWriteBarrier", Object.class, Object.class);
            g1PreWriteBarrier = snippet("g1PreWriteBarrier", Object.class, Object.class, Object.class, boolean.class);
            g1PostWriteBarrier = snippet("g1PostWriteBarrier", Object.class, Object.class, Object.class, boolean.class);
        }

        public void lower(ArrayWriteBarrier arrayWriteBarrier, @SuppressWarnings("unused") LoweringTool tool) {
            ResolvedJavaMethod method = serialArrayWriteBarrier;
            Key key = new Key(method);
            Arguments arguments = new Arguments();
            arguments.add("object", arrayWriteBarrier.getObject());
            arguments.add("location", arrayWriteBarrier.getLocation());
            SnippetTemplate template = cache.get(key);
            template.instantiate(runtime, arrayWriteBarrier, DEFAULT_REPLACER, arguments);
        }

        public void lower(FieldWriteBarrier fieldWriteBarrier, @SuppressWarnings("unused") LoweringTool tool) {
            ResolvedJavaMethod method = serialFieldWriteBarrier;
            Key key = new Key(method);
            Arguments arguments = new Arguments();
            arguments.add("object", fieldWriteBarrier.getObject());
            SnippetTemplate template = cache.get(key);
            template.instantiate(runtime, fieldWriteBarrier, DEFAULT_REPLACER, arguments);
        }

        public void lower(WriteBarrierPre writeBarrierPre, @SuppressWarnings("unused") LoweringTool tool) {
            ResolvedJavaMethod method = g1PreWriteBarrier;
            Key key = new Key(method);
            key.add("doLoad", writeBarrierPre.doLoad());
            Arguments arguments = new Arguments();
            arguments.add("object", writeBarrierPre.getObject());
            arguments.add("expectedObject", writeBarrierPre.getExpectedObject());
            arguments.add("location", writeBarrierPre.getLocation());
            SnippetTemplate template = cache.get(key);
            template.instantiate(runtime, writeBarrierPre, DEFAULT_REPLACER, arguments);
        }

        public void lower(WriteBarrierPost writeBarrierPost, @SuppressWarnings("unused") LoweringTool tool) {
            ResolvedJavaMethod method = g1PostWriteBarrier;
            Key key = new Key(method);
            key.add("usePrecise", writeBarrierPost.usePrecise());
            Arguments arguments = new Arguments();
            arguments.add("object", writeBarrierPost.getObject());
            arguments.add("location", writeBarrierPost.getLocation());
            arguments.add("value", writeBarrierPost.getValue());
            SnippetTemplate template = cache.get(key);
            template.instantiate(runtime, writeBarrierPost, DEFAULT_REPLACER, arguments);
        }

    }
}
