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

import static com.oracle.graal.graph.UnsafeAccess.*;

import java.lang.reflect.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.replacements.*;

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

    /**
     * {@link Field} object of {@link Method#slot}.
     */
    @SuppressWarnings("javadoc") private Field reflectionMethodSlot = getReflectionSlotField(Method.class);

    /**
     * {@link Field} object of {@link Constructor#slot}.
     */
    @SuppressWarnings("javadoc") private Field reflectionConstructorSlot = getReflectionSlotField(Constructor.class);

    private static Field getReflectionSlotField(Class<?> reflectionClass) {
        try {
            Field field = reflectionClass.getDeclaredField("slot");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException | SecurityException e) {
            throw new GraalInternalError(e);
        }
    }

    public ResolvedJavaMethod lookupJavaMethod(Method reflectionMethod) {
        try {
            Class<?> holder = reflectionMethod.getDeclaringClass();
            final int slot = reflectionMethodSlot.getInt(reflectionMethod);
            final long metaspaceMethod = runtime.getCompilerToVM().getMetaspaceMethod(holder, slot);
            return HotSpotResolvedJavaMethod.fromMetaspace(metaspaceMethod);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new GraalInternalError(e);
        }
    }

    public ResolvedJavaMethod lookupJavaConstructor(Constructor reflectionConstructor) {
        try {
            Class<?> holder = reflectionConstructor.getDeclaringClass();
            final int slot = reflectionConstructorSlot.getInt(reflectionConstructor);
            final long metaspaceMethod = runtime.getCompilerToVM().getMetaspaceMethod(holder, slot);
            return HotSpotResolvedJavaMethod.fromMetaspace(metaspaceMethod);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new GraalInternalError(e);
        }
    }

    public ResolvedJavaField lookupJavaField(Field reflectionField) {
        String name = reflectionField.getName();
        Class<?> fieldHolder = reflectionField.getDeclaringClass();
        Class<?> fieldType = reflectionField.getType();
        // java.lang.reflect.Field's modifiers should be enough here since VM internal modifier bits
        // are not used (yet).
        final int modifiers = reflectionField.getModifiers();
        final long offset = Modifier.isStatic(modifiers) ? unsafe.staticFieldOffset(reflectionField) : unsafe.objectFieldOffset(reflectionField);
        final boolean internal = false;

        ResolvedJavaType holder = HotSpotResolvedObjectType.fromClass(fieldHolder);
        ResolvedJavaType type = HotSpotResolvedObjectType.fromClass(fieldType);

        if (offset != -1) {
            HotSpotResolvedObjectType resolved = (HotSpotResolvedObjectType) holder;
            return resolved.createField(name, type, offset, modifiers, internal);
        } else {
            // TODO this cast will not succeed
            return (ResolvedJavaField) new HotSpotUnresolvedField(holder, name, type);
        }
    }

    private static int intMaskRight(int n) {
        assert n <= 32;
        return n == 32 ? -1 : (1 << n) - 1;
    }

    @Override
    public Constant encodeDeoptActionAndReason(DeoptimizationAction action, DeoptimizationReason reason, int debugId) {
        HotSpotVMConfig config = runtime.getConfig();
        int actionValue = convertDeoptAction(action);
        int reasonValue = convertDeoptReason(reason);
        int debugValue = debugId & intMaskRight(config.deoptimizationDebugIdBits);
        Constant c = Constant.forInt(~((debugValue << config.deoptimizationDebugIdShift) | (reasonValue << config.deoptimizationReasonShift) | (actionValue << config.deoptimizationActionShift)));
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

    public int decodeDebugId(Constant constant) {
        HotSpotVMConfig config = runtime.getConfig();
        return ((~constant.asInt()) >> config.deoptimizationDebugIdShift) & intMaskRight(config.deoptimizationDebugIdBits);
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

    @Override
    public long getMemorySize(Constant constant) {
        if (constant.getKind() == Kind.Object) {
            HotSpotResolvedObjectType lookupJavaType = (HotSpotResolvedObjectType) this.lookupJavaType(constant);

            if (lookupJavaType == null) {
                return 0;
            } else {
                if (lookupJavaType.isArray()) {
                    // TODO(tw): Add compressed pointer support.
                    int length = Array.getLength(constant.asObject());
                    ResolvedJavaType elementType = lookupJavaType.getComponentType();
                    Kind elementKind = elementType.getKind();
                    final int headerSize = HotSpotGraalRuntime.getArrayBaseOffset(elementKind);
                    int sizeOfElement = HotSpotGraalRuntime.runtime().getTarget().arch.getSizeInBytes(elementKind);
                    int alignment = HotSpotGraalRuntime.runtime().getTarget().wordSize;
                    int log2ElementSize = CodeUtil.log2(sizeOfElement);
                    return NewObjectSnippets.computeArrayAllocationSize(length, alignment, headerSize, log2ElementSize);
                }
                return lookupJavaType.instanceSize();
            }
        } else {
            return constant.getKind().getByteCount();
        }
    }
}
