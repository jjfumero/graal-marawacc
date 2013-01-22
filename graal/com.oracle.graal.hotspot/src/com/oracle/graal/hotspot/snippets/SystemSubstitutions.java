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
package com.oracle.graal.hotspot.snippets;

import static com.oracle.graal.hotspot.snippets.HotSpotSnippetUtils.*;
import static com.oracle.graal.snippets.nodes.BranchProbabilityNode.*;

import com.oracle.graal.api.code.RuntimeCallTarget.Descriptor;
import com.oracle.graal.graph.Node.ConstantNodeParameter;
import com.oracle.graal.graph.Node.NodeIntrinsic;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.snippets.*;
import com.oracle.graal.snippets.ClassSubstitution.MacroSubstitution;
import com.oracle.graal.snippets.ClassSubstitution.MethodSubstitution;
import com.oracle.graal.word.*;

/**
 * Substitutions for {@link java.lang.System} methods.
 */
@ClassSubstitution(java.lang.System.class)
public class SystemSubstitutions {

    public static final Descriptor JAVA_TIME_MILLIS = new Descriptor("javaTimeMillis", false, long.class);
    public static final Descriptor JAVA_TIME_NANOS = new Descriptor("javaTimeNanos", false, long.class);

    @MacroSubstitution(macro = ArrayCopyNode.class, isStatic = true)
    public static native void arraycopy(Object src, int srcPos, Object dest, int destPos, int length);

    @MethodSubstitution
    public static long currentTimeMillis() {
        return callLong(JAVA_TIME_MILLIS);
    }

    @MethodSubstitution
    public static long nanoTime() {
        return callLong(JAVA_TIME_NANOS);
    }

    @MethodSubstitution
    public static int identityHashCode(Object x) {
        if (x == null) {
            probability(0.01);
            return 0;
        }

        Word mark = loadWordFromObject(x, markOffset());

        // this code is independent from biased locking (although it does not look that way)
        final Word biasedLock = mark.and(biasedLockMaskInPlace());
        if (biasedLock == Word.unsigned(unlockedMask())) {
            probability(0.99);
            int hash = (int) mark.unsignedShiftRight(identityHashCodeShift()).rawValue();
            if (hash != uninitializedIdentityHashCodeValue()) {
                probability(0.99);
                return hash;
            }
        }

        return IdentityHashCodeStubCall.call(x);
    }

    @NodeIntrinsic(value = RuntimeCallNode.class, setStampFromReturnType = true)
    public static native long callLong(@ConstantNodeParameter Descriptor descriptor);
}
