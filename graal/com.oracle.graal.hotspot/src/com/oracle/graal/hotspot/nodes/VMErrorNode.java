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
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.compiler.target.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.replacements.*;

/**
 * Causes the VM to exit with a description of the current Java location and an optional
 * {@linkplain Log#printf(String, long) formatted} error message specified.
 */
public final class VMErrorNode extends DeoptimizingStubCall implements LIRGenLowerable {

    private final String format;
    @Input private ValueNode value;
    public static final ForeignCallDescriptor VM_ERROR = new ForeignCallDescriptor("vm_error", void.class, Object.class, Object.class, long.class);

    private VMErrorNode(String format, ValueNode value) {
        super(StampFactory.forVoid());
        this.format = format;
        this.value = value;
    }

    @Override
    public void generate(LIRGenerator gen) {
        String whereString = "in compiled code for " + graph();

        // As these strings will end up embedded as oops in the code, they
        // must be interned or else they will cause the nmethod to be unloaded
        // (nmethods are a) weak GC roots and b) unloaded if any of their
        // embedded oops become unreachable).
        Constant whereArg = Constant.forObject(whereString.intern());
        Constant formatArg = Constant.forObject(format.intern());

        RuntimeCallTarget stub = gen.getRuntime().lookupRuntimeCall(VMErrorNode.VM_ERROR);
        gen.emitCall(stub, stub.getCallingConvention(), null, whereArg, formatArg, gen.operand(value));
    }

    @NodeIntrinsic
    public static native void vmError(@ConstantNodeParameter String format, long value);
}
