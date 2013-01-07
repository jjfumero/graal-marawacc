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
package com.oracle.graal.hotspot.nodes;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.RuntimeCallTarget.Descriptor;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.compiler.target.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.type.*;

/**
 * Node implementing a call to HotSpot's ThreadIsInterrupted stub.
 */
public class ThreadIsInterruptedStubCall extends FixedWithNextNode implements LIRGenLowerable {
    @Input private final ValueNode thread;
    @Input private final ValueNode clearIsInterrupted;
    public static final Descriptor THREAD_IS_INTERRUPTED = new Descriptor("thread_is_interrupted", false, int.class, Object.class, boolean.class);

    public ThreadIsInterruptedStubCall(ValueNode thread, ValueNode clearIsInterrupted) {
        super(StampFactory.forInteger(Kind.Int, 0, 1));
        this.thread = thread;
        this.clearIsInterrupted = clearIsInterrupted;
    }

    @Override
    public void generate(LIRGenerator gen) {
        RuntimeCallTarget stub = gen.getRuntime().lookupRuntimeCall(ThreadIsInterruptedStubCall.THREAD_IS_INTERRUPTED);
        Variable result = gen.emitCall(stub, stub.getCallingConvention(), true, gen.operand(thread), gen.operand(clearIsInterrupted));
        gen.setResult(this, result);
    }

    @NodeIntrinsic
    public static native int call(Thread thread, boolean clearIsInterrupted);
}
