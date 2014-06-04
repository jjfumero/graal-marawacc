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

public interface AbstractBlock<T extends AbstractBlock<T>> {

    int getId();

    Loop<T> getLoop();

    void setLoop(Loop<T> loop);

    int getLoopDepth();

    boolean isLoopHeader();

    boolean isLoopEnd();

    boolean isExceptionEntry();

    List<T> getPredecessors();

    int getPredecessorCount();

    List<T> getSuccessors();

    int getSuccessorCount();

    int getLinearScanNumber();

    void setLinearScanNumber(int linearScanNumber);

    boolean isAligned();

    void setAlign(boolean align);

    T getDominator();

    double probability();

    /**
     * True if block {@code a} dominates block {@code b}.
     */
    static boolean dominates(AbstractBlock<?> a, AbstractBlock<?> b) {
        assert a != null;
        return isDominatedBy(b, a);
    }

    /**
     * True if block {@code a} is dominated by block {@code b}.
     */
    static boolean isDominatedBy(AbstractBlock<?> a, AbstractBlock<?> b) {
        assert a != null;
        if (a == b) {
            return true;
        }
        if (a.getDominator() == null) {
            return false;
        }
        return isDominatedBy(a.getDominator(), b);
    }

}
