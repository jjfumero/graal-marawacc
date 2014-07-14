/*
 * Copyright (c) 2014, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.compiler.common.cfg;

import java.util.*;

public interface AbstractControlFlowGraph<T extends AbstractBlock<T>> {

    static final int BLOCK_ID_INITIAL = -1;
    static final int BLOCK_ID_VISITED = -2;

    List<T> getBlocks();

    Collection<Loop<T>> getLoops();

    T getStartBlock();

    public static AbstractBlock<?> commonDominator(AbstractBlock<?> a, AbstractBlock<?> b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        AbstractBlock<?> iterA = a;
        AbstractBlock<?> iterB = b;
        while (iterA != iterB) {
            if (iterA.getId() > iterB.getId()) {
                iterA = iterA.getDominator();
            } else {
                assert iterB.getId() > iterA.getId();
                iterB = iterB.getDominator();
            }
        }
        return iterA;
    }

    @SuppressWarnings("unchecked")
    public static <T extends AbstractBlock<T>> T commonDominatorTyped(T a, T b) {
        return (T) commonDominator(a, b);
    }
}
