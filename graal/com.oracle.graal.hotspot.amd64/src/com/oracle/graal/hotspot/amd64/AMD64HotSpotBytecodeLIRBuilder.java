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
package com.oracle.graal.hotspot.amd64;

import static jdk.vm.ci.amd64.AMD64.rbp;
import jdk.vm.ci.amd64.AMD64Kind;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaField;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.LIRKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Signature;
import jdk.vm.ci.meta.Value;

import com.oracle.graal.compiler.gen.BytecodeLIRBuilder;
import com.oracle.graal.compiler.gen.BytecodeParserTool;
import com.oracle.graal.lir.gen.LIRGeneratorTool;

public class AMD64HotSpotBytecodeLIRBuilder extends BytecodeLIRBuilder {

    public AMD64HotSpotBytecodeLIRBuilder(LIRGeneratorTool gen, BytecodeParserTool parser) {
        super(gen, parser);
    }

    private AMD64HotSpotLIRGenerator getGen() {
        return (AMD64HotSpotLIRGenerator) gen;
    }

    @Override
    public void emitPrologue(ResolvedJavaMethod method) {
        CallingConvention incomingArguments = gen.getCallingConvention();

        Value[] params = new Value[incomingArguments.getArgumentCount() + 1];
        for (int i = 0; i < params.length - 1; i++) {
            params[i] = incomingArguments.getArgument(i);
            if (ValueUtil.isStackSlot(params[i])) {
                StackSlot slot = ValueUtil.asStackSlot(params[i]);
                if (slot.isInCallerFrame() && !gen.getResult().getLIR().hasArgInCallerFrame()) {
                    gen.getResult().getLIR().setHasArgInCallerFrame();
                }
            }
        }
        params[params.length - 1] = rbp.asValue(LIRKind.value(AMD64Kind.QWORD));

        gen.emitIncomingValues(params);

        getGen().emitSaveRbp();

        Signature sig = method.getSignature();
        boolean isStatic = method.isStatic();
        for (int i = 0; i < sig.getParameterCount(!isStatic); i++) {
            Value paramValue = params[i];
            assert paramValue.getLIRKind().equals(gen.target().getLIRKind(sig.getParameterKind(i).getStackKind()));
            parser.storeLocal(i, gen.emitMove(paramValue));
        }
    }

    @Override
    public int getArrayLengthOffset() {
        return getGen().config.arrayOopDescLengthOffset();
    }

    @Override
    public JavaConstant getClassConstant(ResolvedJavaType declaringClass) {
        return declaringClass.getJavaClass();
    }

    @Override
    public int getFieldOffset(ResolvedJavaField field) {
        return ((HotSpotResolvedJavaField) field).offset();
    }

}
