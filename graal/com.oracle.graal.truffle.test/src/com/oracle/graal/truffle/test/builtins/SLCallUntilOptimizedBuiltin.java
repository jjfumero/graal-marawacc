/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.truffle.test.builtins;

import java.util.concurrent.*;

import com.oracle.graal.truffle.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.*;
import com.oracle.truffle.api.dsl.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;
import com.oracle.truffle.sl.*;
import com.oracle.truffle.sl.runtime.*;

/**
 * Calls a given function until the Graal runtime decides to optimize the function. Use
 * <code>waitForOptimization(function)</code> to wait until the runtime system has completed the
 * possibly parallel optimization.
 *
 * @see SLWaitForOptimizationBuiltin
 */
@NodeInfo(shortName = "callUntilOptimized")
public abstract class SLCallUntilOptimizedBuiltin extends SLGraalRuntimeBuiltin {

    private static final int MAX_CALLS = 10000;
    private static final Object[] EMPTY_ARGS = new Object[0];

    @Child private IndirectCallNode indirectCall = Truffle.getRuntime().createIndirectCallNode();

    @Specialization
    public SLFunction callUntilCompiled(VirtualFrame frame, SLFunction function) {
        OptimizedCallTarget target = ((OptimizedCallTarget) function.getCallTarget());
        for (int i = 0; i < MAX_CALLS; i++) {
            if (isCompiling(target)) {
                break;
            } else {
                indirectCall.call(frame, target, EMPTY_ARGS);
            }
        }
        waitForCompilation(target);

        // call one more in compiled
        indirectCall.call(frame, target, EMPTY_ARGS);

        checkTarget(target);

        return function;
    }

    @TruffleBoundary
    private static void checkTarget(OptimizedCallTarget target) throws SLAssertionError {
        if (!target.isValid()) {
            throw new SLAssertionError("Function " + target + " invalidated.");
        }
    }

    @TruffleBoundary
    private static void waitForCompilation(OptimizedCallTarget target) {
        try {
            ((GraalTruffleRuntime) Truffle.getRuntime()).waitForCompilation(target, 640000);
        } catch (ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    @TruffleBoundary
    private static boolean isCompiling(OptimizedCallTarget target) {
        return ((GraalTruffleRuntime) Truffle.getRuntime()).isCompiling(target) || target.isValid();
    }
}
