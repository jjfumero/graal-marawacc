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

    /**
     * Returns the list blocks contained in this control flow graph.
     *
     * It is {@linkplain CFGVerifier guaranteed} that the blocks are numbered according to a reverse
     * post order traversal of the control flow graph.
     *
     * @see CFGVerifier
     */
    List<T> getBlocks();

    Collection<Loop<T>> getLoops();

    T getStartBlock();

    /**
     * Calculates the common dominator of two blocks.
     *
     * Note that this algorithm makes use of special properties regarding the numbering of blocks.
     *
     * @see #getBlocks()
     * @see CFGVerifier
     */
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

    /**
     * @see AbstractControlFlowGraph#commonDominator(AbstractBlock, AbstractBlock)
     */
    @SuppressWarnings("unchecked")
    public static <T extends AbstractBlock<T>> T commonDominatorTyped(T a, T b) {
        return (T) commonDominator(a, b);
    }
}
