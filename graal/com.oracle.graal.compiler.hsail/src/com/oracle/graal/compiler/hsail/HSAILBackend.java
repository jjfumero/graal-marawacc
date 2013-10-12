/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.compiler.hsail;

import static com.oracle.graal.api.code.CallingConvention.Type.*;
import static com.oracle.graal.api.code.ValueUtil.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.asm.hsail.*;
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.compiler.target.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.lir.hsail.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.phases.util.*;
import com.oracle.graal.hsail.*;

import java.util.Map;
import java.util.HashMap;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * HSAIL specific backend.
 */
public class HSAILBackend extends Backend {

    private Map<String, String> paramTypeMap = new HashMap<>();
    private Buffer codeBuffer;

    public HSAILBackend(Providers providers, TargetDescription target) {
        super(providers, target);
        paramTypeMap.put("HotSpotResolvedPrimitiveType<int>", "s32");
        paramTypeMap.put("HotSpotResolvedPrimitiveType<float>", "f32");
        paramTypeMap.put("HotSpotResolvedPrimitiveType<double>", "f64");
        paramTypeMap.put("HotSpotResolvedPrimitiveType<long>", "s64");
    }

    @Override
    public boolean shouldAllocateRegisters() {
        return true;
    }

    /**
     * Use the HSAIL register set when the compilation target is HSAIL.
     */
    @Override
    public FrameMap newFrameMap() {
        return new HSAILFrameMap(getCodeCache(), target, new HSAILRegisterConfig());
    }

    @Override
    public LIRGenerator newLIRGenerator(StructuredGraph graph, FrameMap frameMap, CallingConvention cc, LIR lir) {
        return new HSAILLIRGenerator(graph, getProviders(), target, frameMap, cc, lir);
    }

    public String getPartialCodeString() {
        return (codeBuffer == null ? "" : new String(codeBuffer.copyData(0, codeBuffer.position())));
    }

    class HotSpotFrameContext implements FrameContext {

        @Override
        public void enter(TargetMethodAssembler tasm) {
            Debug.log("Nothing to do here");
        }

        @Override
        public void leave(TargetMethodAssembler tasm) {
            Debug.log("Nothing to do here");
        }
    }

    @Override
    protected AbstractAssembler createAssembler(FrameMap frameMap) {
        return new HSAILAssembler(target);
    }

    @Override
    public TargetMethodAssembler newAssembler(LIRGenerator lirGen, CompilationResult compilationResult) {
        FrameMap frameMap = lirGen.frameMap;
        AbstractAssembler masm = new HSAILAssembler(target);
        HotSpotFrameContext frameContext = new HotSpotFrameContext();
        TargetMethodAssembler tasm = new TargetMethodAssembler(target, getCodeCache(), getForeignCalls(), frameMap, masm, frameContext, compilationResult);
        tasm.setFrameSize(frameMap.frameSize());
        return tasm;
    }

    @Override
    public void emitCode(TargetMethodAssembler tasm, LIRGenerator lirGen, ResolvedJavaMethod method) {
        assert method != null : lirGen.getGraph() + " is not associated with a method";
        // Emit the prologue.
        codeBuffer = tasm.asm.codeBuffer;
        codeBuffer.emitString0("version 0:95: $full : $large;");
        codeBuffer.emitString("");
        Signature signature = method.getSignature();
        int sigParamCount = signature.getParameterCount(false);
        // We're subtracting 1 because we're not making the final gid as a parameter.
        int nonConstantParamCount = sigParamCount - 1;
        boolean isStatic = (Modifier.isStatic(method.getModifiers()));
        // Determine if this is an object lambda.
        boolean isObjectLambda = true;
        if (signature.getParameterType(nonConstantParamCount, null).getKind() == Kind.Int) {
            isObjectLambda = false;
        } else {
            // Add space for gid int reg.
            nonConstantParamCount++;
        }

        // If this is an instance method, include mappings for the "this" parameter
        // as the first parameter.
        if (!isStatic) {
            nonConstantParamCount++;
        }
        // Add in any "constant" parameters (currently none).
        int totalParamCount = nonConstantParamCount;
        JavaType[] paramtypes = new JavaType[totalParamCount];
        String[] paramNames = new String[totalParamCount];
        int pidx = 0;
        for (int i = 0; i < totalParamCount; i++) {
            MetaAccessProvider metaAccess = getProviders().getMetaAccess();
            if (i == 0 && !isStatic) {
                paramtypes[i] = metaAccess.lookupJavaType(Object.class);
                paramNames[i] = "%_this";
            } else if (i < nonConstantParamCount) {
                if (isObjectLambda && (i == (nonConstantParamCount))) {
                    // Set up the gid register mapping.
                    paramtypes[i] = metaAccess.lookupJavaType(int.class);
                    paramNames[i] = "%_gid";
                } else {
                    paramtypes[i] = signature.getParameterType(pidx++, null);
                    paramNames[i] = "%_arg" + i;
                }
            }
        }
        codeBuffer.emitString0("// " + (isStatic ? "static" : "instance") + " method " + method);
        codeBuffer.emitString("");
        codeBuffer.emitString0("kernel &run (");
        codeBuffer.emitString("");
        FrameMap frameMap = tasm.frameMap;
        RegisterConfig regConfig = frameMap.registerConfig;
        // Build list of param types which does include the gid (for cc register mapping query).
        JavaType[] ccParamTypes = new JavaType[nonConstantParamCount + 1];
        // Include the gid.
        System.arraycopy(paramtypes, 0, ccParamTypes, 0, nonConstantParamCount);
        // Last entry comes from the signature.
        ccParamTypes[ccParamTypes.length - 1] = signature.getParameterType(sigParamCount - 1, null);
        CallingConvention cc = regConfig.getCallingConvention(JavaCallee, null, ccParamTypes, target, false);
        /**
         * Compute the hsail size mappings up to but not including the last non-constant parameter
         * (which is the gid).
         * 
         */
        String[] paramHsailSizes = new String[totalParamCount];
        for (int i = 0; i < totalParamCount; i++) {
            String paramtypeStr = paramtypes[i].toString();
            String sizeStr = paramTypeMap.get(paramtypeStr);
            // Catch all for any unmapped paramtype that is u64 (address of an object).
            paramHsailSizes[i] = (sizeStr != null ? sizeStr : "u64");
        }
        // Emit the kernel function parameters.
        for (int i = 0; i < totalParamCount; i++) {
            String str = "kernarg_" + paramHsailSizes[i] + " " + paramNames[i];
            if (i != totalParamCount - 1) {
                str += ",";
            }
            codeBuffer.emitString(str);
        }
        codeBuffer.emitString(") {");

        /*
         * End of parameters start of prolog code. Emit the load instructions for loading of the
         * kernel non-constant parameters into registers. The constant class parameters will not be
         * loaded up front but will be loaded as needed.
         */
        for (int i = 0; i < nonConstantParamCount; i++) {
            codeBuffer.emitString("ld_kernarg_" + paramHsailSizes[i] + "  " + HSAIL.mapRegister(cc.getArgument(i)) + ", [" + paramNames[i] + "];");
        }

        /*
         * Emit the workitemaid instruction for loading the hidden gid parameter. This is assigned
         * the register as if it were the last of the nonConstant parameters.
         */
        String workItemReg = "$s" + Integer.toString(asRegister(cc.getArgument(nonConstantParamCount)).encoding());
        codeBuffer.emitString("workitemabsid_u32 " + workItemReg + ", 0;");

        /*
         * Note the logic used for this spillseg size is to leave space and then go back and patch
         * in the correct size once we have generated all the instructions. This should probably be
         * done in a more robust way by implementing something like codeBuffer.insertString.
         */
        int spillsegDeclarationPosition = codeBuffer.position() + 1;
        String spillsegTemplate = "align 4 spill_u8 %spillseg[123456];";
        codeBuffer.emitString(spillsegTemplate);
        // Emit object array load prologue here.
        if (isObjectLambda) {
            final int arrayElementsOffset = 24;
            String iterationObjArgReg = HSAIL.mapRegister(cc.getArgument(nonConstantParamCount - 1));
            String tmpReg = workItemReg.replace("s", "d"); // "$d1";
            // Convert gid to long.
            codeBuffer.emitString("cvt_u64_s32 " + tmpReg + ", " + workItemReg + "; // Convert gid to long");
            // Adjust index for sizeof ref.
            codeBuffer.emitString("mul_u64 " + tmpReg + ", " + tmpReg + ", " + 8 + "; // Adjust index for sizeof ref");
            // Adjust for actual data start.
            codeBuffer.emitString("add_u64 " + tmpReg + ", " + tmpReg + ", " + arrayElementsOffset + "; // Adjust for actual elements data start");
            // Add to array ref ptr.
            codeBuffer.emitString("add_u64 " + tmpReg + ", " + tmpReg + ", " + iterationObjArgReg + "; // Add to array ref ptr");
            // Load the object into the parameter reg.
            codeBuffer.emitString("ld_global_u64 " + iterationObjArgReg + ", " + "[" + tmpReg + "]" + "; // Load from array element into parameter reg");
        }
        // Prologue done, Emit code for the LIR.
        lirGen.lir.emitCode(tasm);
        // Now that code is emitted go back and figure out what the upper Bound stack size was.
        long maxStackSize = ((HSAILAssembler) tasm.asm).upperBoundStackSize();
        String spillsegStringFinal;
        if (maxStackSize == 0) {
            // If no spilling, get rid of spillseg declaration.
            char[] array = new char[spillsegTemplate.length()];
            Arrays.fill(array, ' ');
            spillsegStringFinal = new String(array);
        } else {
            spillsegStringFinal = spillsegTemplate.replace("123456", String.format("%6d", maxStackSize));
        }
        codeBuffer.emitString(spillsegStringFinal, spillsegDeclarationPosition);
        // Emit the epilogue.
        codeBuffer.emitString0("};");
        codeBuffer.emitString("");
    }
}
