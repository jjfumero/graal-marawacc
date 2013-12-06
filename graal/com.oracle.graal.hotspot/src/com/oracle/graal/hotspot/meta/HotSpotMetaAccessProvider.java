/*
 * Copyright (c) 2011, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.meta;

import java.lang.reflect.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.bridge.*;

/**
 * HotSpot implementation of {@link MetaAccessProvider}.
 */
public class HotSpotMetaAccessProvider implements MetaAccessProvider {

    protected final HotSpotGraalRuntime runtime;

    public HotSpotMetaAccessProvider(HotSpotGraalRuntime runtime) {
        this.runtime = runtime;
    }

    public ResolvedJavaType lookupJavaType(Class<?> clazz) {
        if (clazz == null) {
            throw new IllegalArgumentException("Class parameter was null");
        }
        return HotSpotResolvedObjectType.fromClass(clazz);
    }

    public ResolvedJavaType lookupJavaType(Constant constant) {
        if (constant.getKind() != Kind.Object || constant.isNull()) {
            return null;
        }
        Object o = constant.asObject();
        return HotSpotResolvedObjectType.fromClass(o.getClass());
    }

    public Signature parseMethodDescriptor(String signature) {
        return new HotSpotSignature(signature);
    }

    public ResolvedJavaMethod lookupJavaMethod(Method reflectionMethod) {
        CompilerToVM c2vm = runtime.getCompilerToVM();
        HotSpotResolvedObjectType[] resultHolder = {null};
        long metaspaceMethod = c2vm.getMetaspaceMethod(reflectionMethod, resultHolder);
        assert metaspaceMethod != 0L;
        return resultHolder[0].createMethod(metaspaceMethod);
    }

    public ResolvedJavaMethod lookupJavaConstructor(Constructor reflectionConstructor) {
        CompilerToVM c2vm = runtime.getCompilerToVM();
        HotSpotResolvedObjectType[] resultHolder = {null};
        long metaspaceMethod = c2vm.getMetaspaceConstructor(reflectionConstructor, resultHolder);
        assert metaspaceMethod != 0L;
        return resultHolder[0].createMethod(metaspaceMethod);
    }

    public ResolvedJavaField lookupJavaField(Field reflectionField) {
        return runtime.getCompilerToVM().getJavaField(reflectionField);
    }

    private static int intMaskRight(int n) {
        assert n <= 32;
        return n == 32 ? -1 : (1 << n) - 1;
    }

    @Override
    public Constant encodeDeoptActionAndReason(DeoptimizationAction action, DeoptimizationReason reason, int speculationId) {
        HotSpotVMConfig config = runtime.getConfig();
        int actionValue = convertDeoptAction(action);
        int reasonValue = convertDeoptReason(reason);
        int speculationValue = speculationId & intMaskRight(config.deoptimizationSpeculationIdBits);
        Constant c = Constant.forInt(~((speculationValue << config.deoptimizationSpeculationIdShift) | (reasonValue << config.deoptimizationReasonShift) | (actionValue << config.deoptimizationActionShift)));
        assert c.asInt() < 0;
        return c;
    }

    public DeoptimizationReason decodeDeoptReason(Constant constant) {
        HotSpotVMConfig config = runtime.getConfig();
        int reasonValue = ((~constant.asInt()) >> config.deoptimizationReasonShift) & intMaskRight(config.deoptimizationReasonBits);
        DeoptimizationReason reason = convertDeoptReason(reasonValue);
        return reason;
    }

    public DeoptimizationAction decodeDeoptAction(Constant constant) {
        HotSpotVMConfig config = runtime.getConfig();
        int actionValue = ((~constant.asInt()) >> config.deoptimizationActionShift) & intMaskRight(config.deoptimizationActionBits);
        DeoptimizationAction action = convertDeoptAction(actionValue);
        return action;
    }

    public short decodeSpeculationId(Constant constant) {
        HotSpotVMConfig config = runtime.getConfig();
        return (short) (((~constant.asInt()) >> config.deoptimizationSpeculationIdShift) & intMaskRight(config.deoptimizationSpeculationIdBits));
    }

    public int convertDeoptAction(DeoptimizationAction action) {
        HotSpotVMConfig config = runtime.getConfig();
        switch (action) {
            case None:
                return config.deoptActionNone;
            case RecompileIfTooManyDeopts:
                return config.deoptActionMaybeRecompile;
            case InvalidateReprofile:
                return config.deoptActionReinterpret;
            case InvalidateRecompile:
                return config.deoptActionMakeNotEntrant;
            case InvalidateStopCompiling:
                return config.deoptActionMakeNotCompilable;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    public DeoptimizationAction convertDeoptAction(int action) {
        HotSpotVMConfig config = runtime.getConfig();
        if (action == config.deoptActionNone) {
            return DeoptimizationAction.None;
        }
        if (action == config.deoptActionMaybeRecompile) {
            return DeoptimizationAction.RecompileIfTooManyDeopts;
        }
        if (action == config.deoptActionReinterpret) {
            return DeoptimizationAction.InvalidateReprofile;
        }
        if (action == config.deoptActionMakeNotEntrant) {
            return DeoptimizationAction.InvalidateRecompile;
        }
        if (action == config.deoptActionMakeNotCompilable) {
            return DeoptimizationAction.InvalidateStopCompiling;
        }
        throw GraalInternalError.shouldNotReachHere();
    }

    public int convertDeoptReason(DeoptimizationReason reason) {
        HotSpotVMConfig config = runtime.getConfig();
        switch (reason) {
            case None:
                return config.deoptReasonNone;
            case NullCheckException:
                return config.deoptReasonNullCheck;
            case BoundsCheckException:
                return config.deoptReasonRangeCheck;
            case ClassCastException:
                return config.deoptReasonClassCheck;
            case ArrayStoreException:
                return config.deoptReasonArrayCheck;
            case UnreachedCode:
                return config.deoptReasonUnreached0;
            case TypeCheckedInliningViolated:
                return config.deoptReasonTypeCheckInlining;
            case OptimizedTypeCheckViolated:
                return config.deoptReasonOptimizedTypeCheck;
            case NotCompiledExceptionHandler:
                return config.deoptReasonNotCompiledExceptionHandler;
            case Unresolved:
                return config.deoptReasonUnresolved;
            case JavaSubroutineMismatch:
                return config.deoptReasonJsrMismatch;
            case ArithmeticException:
                return config.deoptReasonDiv0Check;
            case RuntimeConstraint:
                return config.deoptReasonConstraint;
            case LoopLimitCheck:
                return config.deoptReasonLoopLimitCheck;
            case Aliasing:
                return config.deoptReasonAliasing;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    public DeoptimizationReason convertDeoptReason(int reason) {
        HotSpotVMConfig config = runtime.getConfig();
        if (reason == config.deoptReasonNone) {
            return DeoptimizationReason.None;
        }
        if (reason == config.deoptReasonNullCheck) {
            return DeoptimizationReason.NullCheckException;
        }
        if (reason == config.deoptReasonRangeCheck) {
            return DeoptimizationReason.BoundsCheckException;
        }
        if (reason == config.deoptReasonClassCheck) {
            return DeoptimizationReason.ClassCastException;
        }
        if (reason == config.deoptReasonArrayCheck) {
            return DeoptimizationReason.ArrayStoreException;
        }
        if (reason == config.deoptReasonUnreached0) {
            return DeoptimizationReason.UnreachedCode;
        }
        if (reason == config.deoptReasonTypeCheckInlining) {
            return DeoptimizationReason.TypeCheckedInliningViolated;
        }
        if (reason == config.deoptReasonOptimizedTypeCheck) {
            return DeoptimizationReason.OptimizedTypeCheckViolated;
        }
        if (reason == config.deoptReasonNotCompiledExceptionHandler) {
            return DeoptimizationReason.NotCompiledExceptionHandler;
        }
        if (reason == config.deoptReasonUnresolved) {
            return DeoptimizationReason.Unresolved;
        }
        if (reason == config.deoptReasonJsrMismatch) {
            return DeoptimizationReason.JavaSubroutineMismatch;
        }
        if (reason == config.deoptReasonDiv0Check) {
            return DeoptimizationReason.ArithmeticException;
        }
        if (reason == config.deoptReasonConstraint) {
            return DeoptimizationReason.RuntimeConstraint;
        }
        if (reason == config.deoptReasonLoopLimitCheck) {
            return DeoptimizationReason.LoopLimitCheck;
        }
        if (reason == config.deoptReasonAliasing) {
            return DeoptimizationReason.Aliasing;
        }
        throw GraalInternalError.shouldNotReachHere(Integer.toHexString(reason));
    }
}
