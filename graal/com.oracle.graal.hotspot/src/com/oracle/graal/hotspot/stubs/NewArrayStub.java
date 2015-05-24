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
package com.oracle.graal.hotspot.stubs;

import static com.oracle.graal.hotspot.jvmci.HotSpotMetaAccessProvider.*;
import static com.oracle.graal.hotspot.replacements.HotSpotReplacementsUtil.*;
import static com.oracle.graal.hotspot.replacements.NewObjectSnippets.*;
import static com.oracle.graal.hotspot.stubs.NewInstanceStub.*;
import static com.oracle.graal.hotspot.stubs.StubUtil.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.replacements.*;
import com.oracle.graal.graph.Node.ConstantNodeParameter;
import com.oracle.graal.graph.Node.NodeIntrinsic;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.jvmci.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.hotspot.nodes.type.*;
import com.oracle.graal.hotspot.replacements.*;
import com.oracle.graal.hotspot.word.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.replacements.*;
import com.oracle.graal.replacements.Snippet.ConstantParameter;
import com.oracle.graal.word.*;

/**
 * Stub implementing the fast path for TLAB refill during instance class allocation. This stub is
 * called from the {@linkplain NewObjectSnippets inline} allocation code when TLAB allocation fails.
 * If this stub fails to refill the TLAB or allocate the object, it calls out to the HotSpot C++
 * runtime to complete the allocation.
 */
public class NewArrayStub extends SnippetStub {

    public NewArrayStub(HotSpotProviders providers, HotSpotForeignCallLinkage linkage) {
        super("newArray", providers, linkage);
    }

    @Override
    protected Object[] makeConstArgs() {
        HotSpotResolvedObjectType intArrayType = (HotSpotResolvedObjectType) providers.getMetaAccess().lookupJavaType(int[].class);
        int count = method.getSignature().getParameterCount(false);
        Object[] args = new Object[count];
        assert checkConstArg(2, "intArrayHub");
        assert checkConstArg(3, "threadRegister");
        args[2] = ConstantNode.forConstant(KlassPointerStamp.klassNonNull(), intArrayType.klass(), null);
        args[3] = providers.getRegisters().getThreadRegister();
        return args;
    }

    @Fold
    private static boolean logging() {
        return Boolean.getBoolean("graal.logNewArrayStub");
    }

    /**
     * Re-attempts allocation after an initial TLAB allocation failed or was skipped (e.g., due to
     * -XX:-UseTLAB).
     *
     * @param hub the hub of the object to be allocated
     * @param length the length of the array
     * @param intArrayHub the hub for {@code int[].class}
     */
    @Snippet
    private static Object newArray(KlassPointer hub, int length, @ConstantParameter KlassPointer intArrayHub, @ConstantParameter Register threadRegister) {
        int layoutHelper = loadKlassLayoutHelperIntrinsic(hub);
        int log2ElementSize = (layoutHelper >> layoutHelperLog2ElementSizeShift()) & layoutHelperLog2ElementSizeMask();
        int headerSize = (layoutHelper >> layoutHelperHeaderSizeShift()) & layoutHelperHeaderSizeMask();
        int elementKind = (layoutHelper >> layoutHelperElementTypeShift()) & layoutHelperElementTypeMask();
        int sizeInBytes = computeArrayAllocationSize(length, wordSize(), headerSize, log2ElementSize);
        if (logging()) {
            printf("newArray: element kind %d\n", elementKind);
            printf("newArray: array length %d\n", length);
            printf("newArray: array size %d\n", sizeInBytes);
            printf("newArray: hub=%p\n", hub.asWord().rawValue());
        }

        // check that array length is small enough for fast path.
        Word thread = registerAsWord(threadRegister);
        if (length >= 0 && length <= MAX_ARRAY_FAST_PATH_ALLOCATION_LENGTH) {
            Word memory = refillAllocate(thread, intArrayHub, sizeInBytes, logging());
            if (memory.notEqual(0)) {
                if (logging()) {
                    printf("newArray: allocated new array at %p\n", memory.rawValue());
                }
                return verifyObject(formatArray(hub, sizeInBytes, length, headerSize, memory, Word.unsigned(arrayPrototypeMarkWord()), true, false, false));
            }
        }
        if (logging()) {
            printf("newArray: calling new_array_c\n");
        }

        newArrayC(NEW_ARRAY_C, thread, hub, length);
        handlePendingException(thread, true);
        return verifyObject(getAndClearObjectResult(thread));
    }

    public static final ForeignCallDescriptor NEW_ARRAY_C = newDescriptor(NewArrayStub.class, "newArrayC", void.class, Word.class, KlassPointer.class, int.class);

    @NodeIntrinsic(StubForeignCallNode.class)
    public static native void newArrayC(@ConstantNodeParameter ForeignCallDescriptor newArrayC, Word thread, KlassPointer hub, int length);
}
