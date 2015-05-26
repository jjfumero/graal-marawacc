/*
 * Copyright (c) 2011, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.jvmci.hotspot;

import static com.oracle.jvmci.common.UnsafeAccess.*;
import static com.oracle.jvmci.hotspot.HotSpotJVMCIRuntime.*;
import static com.oracle.jvmci.hotspot.HotSpotResolvedJavaMethodImpl.Options.*;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.jvmci.common.*;
import com.oracle.jvmci.debug.*;
import com.oracle.jvmci.options.*;

/**
 * Implementation of {@link JavaMethod} for resolved HotSpot methods.
 */
public final class HotSpotResolvedJavaMethodImpl extends HotSpotMethod implements HotSpotResolvedJavaMethod, HotSpotProxified, MethodIdHolder {

    static class Options {
        // @formatter:off
        @Option(help = "", type = OptionType.Debug)
        public static final OptionValue<Boolean> UseProfilingInformation = new OptionValue<>(true);
        // @formatter:on
    }

    /**
     * Reference to metaspace Method object.
     */
    private final long metaspaceMethod;

    private final HotSpotResolvedObjectTypeImpl holder;
    private final HotSpotConstantPool constantPool;
    private final HotSpotSignature signature;
    private HotSpotMethodData methodData;
    private byte[] code;
    private Member toJavaCache;

    /**
     * Gets the holder of a HotSpot metaspace method native object.
     *
     * @param metaspaceMethod a metaspace Method object
     * @return the {@link ResolvedJavaType} corresponding to the holder of the
     *         {@code metaspaceMethod}
     */
    public static HotSpotResolvedObjectTypeImpl getHolder(long metaspaceMethod) {
        HotSpotVMConfig config = runtime().getConfig();
        final long metaspaceConstMethod = unsafe.getAddress(metaspaceMethod + config.methodConstMethodOffset);
        final long metaspaceConstantPool = unsafe.getAddress(metaspaceConstMethod + config.constMethodConstantsOffset);
        final long metaspaceKlass = unsafe.getAddress(metaspaceConstantPool + config.constantPoolHolderOffset);
        return HotSpotResolvedObjectTypeImpl.fromMetaspaceKlass(metaspaceKlass);
    }

    /**
     * Gets the {@link ResolvedJavaMethod} for a HotSpot metaspace method native object.
     *
     * @param metaspaceMethod a metaspace Method object
     * @return the {@link ResolvedJavaMethod} corresponding to {@code metaspaceMethod}
     */
    public static HotSpotResolvedJavaMethod fromMetaspace(long metaspaceMethod) {
        HotSpotResolvedObjectTypeImpl holder = getHolder(metaspaceMethod);
        return holder.createMethod(metaspaceMethod);
    }

    public HotSpotResolvedJavaMethodImpl(HotSpotResolvedObjectTypeImpl holder, long metaspaceMethod) {
        // It would be too much work to get the method name here so we fill it in later.
        super(null);
        this.metaspaceMethod = metaspaceMethod;
        this.holder = holder;

        HotSpotVMConfig config = runtime().getConfig();
        final long constMethod = getConstMethod();

        /*
         * Get the constant pool from the metaspace method. Some methods (e.g. intrinsics for
         * signature-polymorphic method handle methods) have their own constant pool instead of the
         * one from their holder.
         */
        final long metaspaceConstantPool = unsafe.getAddress(constMethod + config.constMethodConstantsOffset);
        this.constantPool = new HotSpotConstantPool(metaspaceConstantPool);

        final int nameIndex = unsafe.getChar(constMethod + config.constMethodNameIndexOffset);
        this.name = constantPool.lookupUtf8(nameIndex);

        final int signatureIndex = unsafe.getChar(constMethod + config.constMethodSignatureIndexOffset);
        this.signature = (HotSpotSignature) constantPool.lookupSignature(signatureIndex);
    }

    /**
     * Returns a pointer to this method's constant method data structure (
     * {@code Method::_constMethod}).
     *
     * @return pointer to this method's ConstMethod
     */
    private long getConstMethod() {
        assert metaspaceMethod != 0;
        return unsafe.getAddress(metaspaceMethod + runtime().getConfig().methodConstMethodOffset);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof HotSpotResolvedJavaMethodImpl) {
            HotSpotResolvedJavaMethodImpl that = (HotSpotResolvedJavaMethodImpl) obj;
            return that.metaspaceMethod == metaspaceMethod;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return (int) metaspaceMethod;
    }

    /**
     * Returns this method's flags ({@code Method::_flags}).
     *
     * @return flags of this method
     */
    private int getFlags() {
        return unsafe.getByte(metaspaceMethod + runtime().getConfig().methodFlagsOffset);
    }

    /**
     * Returns this method's constant method flags ({@code ConstMethod::_flags}).
     *
     * @return flags of this method's ConstMethod
     */
    private int getConstMethodFlags() {
        return unsafe.getChar(getConstMethod() + runtime().getConfig().constMethodFlagsOffset);
    }

    @Override
    public HotSpotResolvedObjectTypeImpl getDeclaringClass() {
        return holder;
    }

    /**
     * Gets the address of the C++ Method object for this method.
     */
    public JavaConstant getMetaspaceMethodConstant() {
        return HotSpotMetaspaceConstantImpl.forMetaspaceObject(getHostWordKind(), metaspaceMethod, this, false);
    }

    public long getMetaspaceMethod() {
        return metaspaceMethod;
    }

    @Override
    public JavaConstant getEncoding() {
        return getMetaspaceMethodConstant();
    }

    /**
     * Gets the complete set of modifiers for this method which includes the JVM specification
     * modifiers as well as the HotSpot internal modifiers.
     */
    public int getAllModifiers() {
        return unsafe.getInt(metaspaceMethod + runtime().getConfig().methodAccessFlagsOffset);
    }

    @Override
    public int getModifiers() {
        return getAllModifiers() & Modifier.methodModifiers();
    }

    @Override
    public boolean canBeStaticallyBound() {
        return (isFinal() || isPrivate() || isStatic() || holder.isFinal()) && isConcrete();
    }

    @Override
    public byte[] getCode() {
        if (getCodeSize() == 0) {
            return null;
        }
        if (code == null && holder.isLinked()) {
            code = runtime().getCompilerToVM().getBytecode(metaspaceMethod);
            assert code.length == getCodeSize() : "expected: " + getCodeSize() + ", actual: " + code.length;
        }
        return code;
    }

    @Override
    public int getCodeSize() {
        return unsafe.getChar(getConstMethod() + runtime().getConfig().constMethodCodeSizeOffset);
    }

    @Override
    public ExceptionHandler[] getExceptionHandlers() {
        final boolean hasExceptionTable = (getConstMethodFlags() & runtime().getConfig().constMethodHasExceptionTable) != 0;
        if (!hasExceptionTable) {
            return new ExceptionHandler[0];
        }

        HotSpotVMConfig config = runtime().getConfig();
        final int exceptionTableLength = runtime().getCompilerToVM().exceptionTableLength(metaspaceMethod);
        ExceptionHandler[] handlers = new ExceptionHandler[exceptionTableLength];
        long exceptionTableElement = runtime().getCompilerToVM().exceptionTableStart(metaspaceMethod);

        for (int i = 0; i < exceptionTableLength; i++) {
            final int startPc = unsafe.getChar(exceptionTableElement + config.exceptionTableElementStartPcOffset);
            final int endPc = unsafe.getChar(exceptionTableElement + config.exceptionTableElementEndPcOffset);
            final int handlerPc = unsafe.getChar(exceptionTableElement + config.exceptionTableElementHandlerPcOffset);
            int catchTypeIndex = unsafe.getChar(exceptionTableElement + config.exceptionTableElementCatchTypeIndexOffset);

            JavaType catchType;
            if (catchTypeIndex == 0) {
                catchType = null;
            } else {
                final int opcode = -1;  // opcode is not used
                catchType = constantPool.lookupType(catchTypeIndex, opcode);

                // Check for Throwable which catches everything.
                if (catchType instanceof HotSpotResolvedObjectTypeImpl) {
                    HotSpotResolvedObjectTypeImpl resolvedType = (HotSpotResolvedObjectTypeImpl) catchType;
                    if (resolvedType.mirror() == Throwable.class) {
                        catchTypeIndex = 0;
                        catchType = null;
                    }
                }
            }
            handlers[i] = new ExceptionHandler(startPc, endPc, handlerPc, catchTypeIndex, catchType);

            // Go to the next ExceptionTableElement
            exceptionTableElement += config.exceptionTableElementSize;
        }

        return handlers;
    }

    /**
     * Returns true if this method has a {@code CallerSensitive} annotation.
     *
     * @return true if CallerSensitive annotation present, false otherwise
     */
    public boolean isCallerSensitive() {
        return (getFlags() & runtime().getConfig().methodFlagsCallerSensitive) != 0;
    }

    /**
     * Returns true if this method has a {@code ForceInline} annotation.
     *
     * @return true if ForceInline annotation present, false otherwise
     */
    public boolean isForceInline() {
        return (getFlags() & runtime().getConfig().methodFlagsForceInline) != 0;
    }

    /**
     * Returns true if this method has a {@code DontInline} annotation.
     *
     * @return true if DontInline annotation present, false otherwise
     */
    public boolean isDontInline() {
        return (getFlags() & runtime().getConfig().methodFlagsDontInline) != 0;
    }

    /**
     * Manually adds a DontInline annotation to this method.
     */
    public void setNotInlineable() {
        runtime().getCompilerToVM().doNotInlineOrCompile(metaspaceMethod);
    }

    /**
     * Returns true if this method is one of the special methods that is ignored by security stack
     * walks.
     *
     * @return true if special method ignored by security stack walks, false otherwise
     */
    public boolean ignoredBySecurityStackWalk() {
        return runtime().getCompilerToVM().methodIsIgnoredBySecurityStackWalk(metaspaceMethod);
    }

    public boolean hasBalancedMonitors() {
        HotSpotVMConfig config = runtime().getConfig();
        final int modifiers = getAllModifiers();

        // Method has no monitorenter/exit bytecodes.
        if ((modifiers & config.jvmAccHasMonitorBytecodes) == 0) {
            return false;
        }

        // Check to see if a previous compilation computed the monitor-matching analysis.
        if ((modifiers & config.jvmAccMonitorMatch) != 0) {
            return true;
        }

        // This either happens only once if monitors are balanced or very rarely multiple-times.
        return runtime().getCompilerToVM().hasBalancedMonitors(metaspaceMethod);
    }

    @Override
    public boolean isClassInitializer() {
        return "<clinit>".equals(name) && isStatic();
    }

    @Override
    public boolean isConstructor() {
        return "<init>".equals(name) && !isStatic();
    }

    @Override
    public int getMaxLocals() {
        if (isAbstract() || isNative()) {
            return 0;
        }
        HotSpotVMConfig config = runtime().getConfig();
        return unsafe.getChar(getConstMethod() + config.methodMaxLocalsOffset);
    }

    @Override
    public int getMaxStackSize() {
        if (isAbstract() || isNative()) {
            return 0;
        }
        HotSpotVMConfig config = runtime().getConfig();
        return config.extraStackEntries + unsafe.getChar(getConstMethod() + config.constMethodMaxStackOffset);
    }

    @Override
    public StackTraceElement asStackTraceElement(int bci) {
        if (bci < 0 || bci >= getCodeSize()) {
            // HotSpot code can only construct stack trace elements for valid bcis
            StackTraceElement ste = runtime().getCompilerToVM().getStackTraceElement(metaspaceMethod, 0);
            return new StackTraceElement(ste.getClassName(), ste.getMethodName(), ste.getFileName(), -1);
        }
        return runtime().getCompilerToVM().getStackTraceElement(metaspaceMethod, bci);
    }

    public ResolvedJavaMethod uniqueConcreteMethod(HotSpotResolvedObjectType receiver) {
        if (receiver.isInterface()) {
            // Cannot trust interfaces. Because of:
            // interface I { void foo(); }
            // class A { public void foo() {} }
            // class B extends A implements I { }
            // class C extends B { public void foo() { } }
            // class D extends B { }
            // Would lead to identify C.foo() as the unique concrete method for I.foo() without
            // seeing A.foo().
            return null;
        }
        long metaspaceKlass = ((HotSpotResolvedObjectTypeImpl) receiver).getMetaspaceKlass();
        final long uniqueConcreteMethod = runtime().getCompilerToVM().findUniqueConcreteMethod(metaspaceKlass, metaspaceMethod);
        if (uniqueConcreteMethod == 0) {
            return null;
        }
        return fromMetaspace(uniqueConcreteMethod);
    }

    @Override
    public HotSpotSignature getSignature() {
        return signature;
    }

    /**
     * Gets the value of {@code Method::_code}.
     *
     * @return the value of {@code Method::_code}
     */
    private long getCompiledCode() {
        HotSpotVMConfig config = runtime().getConfig();
        return unsafe.getAddress(metaspaceMethod + config.methodCodeOffset);
    }

    /**
     * Returns whether this method has compiled code.
     *
     * @return true if this method has compiled code, false otherwise
     */
    public boolean hasCompiledCode() {
        return getCompiledCode() != 0L;
    }

    /**
     * @param level
     * @return true if the currently installed code was generated at {@code level}.
     */
    public boolean hasCompiledCodeAtLevel(int level) {
        long compiledCode = getCompiledCode();
        if (compiledCode != 0) {
            return unsafe.getInt(compiledCode + runtime().getConfig().nmethodCompLevelOffset) == level;
        }
        return false;
    }

    private static final String TraceMethodDataFilter = System.getProperty("graal.traceMethodDataFilter");

    @Override
    public ProfilingInfo getProfilingInfo() {
        return getProfilingInfo(true, true);
    }

    public ProfilingInfo getCompilationProfilingInfo(boolean isOSR) {
        return getProfilingInfo(!isOSR, isOSR);
    }

    private ProfilingInfo getProfilingInfo(boolean includeNormal, boolean includeOSR) {
        ProfilingInfo info;

        if (UseProfilingInformation.getValue() && methodData == null) {
            long metaspaceMethodData = unsafe.getAddress(metaspaceMethod + runtime().getConfig().methodDataOffset);
            if (metaspaceMethodData != 0) {
                methodData = new HotSpotMethodData(metaspaceMethodData);
                if (TraceMethodDataFilter != null && this.format("%H.%n").contains(TraceMethodDataFilter)) {
                    TTY.println("Raw method data for " + this.format("%H.%n(%p)") + ":");
                    TTY.println(methodData.toString());
                }
            }
        }

        if (methodData == null || (!methodData.hasNormalData() && !methodData.hasExtraData())) {
            // Be optimistic and return false for exceptionSeen. A methodDataOop is allocated in
            // case of a deoptimization.
            info = DefaultProfilingInfo.get(TriState.FALSE);
        } else {
            info = new HotSpotProfilingInfo(methodData, this, includeNormal, includeOSR);
        }
        return info;
    }

    @Override
    public void reprofile() {
        runtime().getCompilerToVM().reprofile(metaspaceMethod);
    }

    @Override
    public ConstantPool getConstantPool() {
        return constantPool;
    }

    @Override
    public Annotation[][] getParameterAnnotations() {
        if (isConstructor()) {
            Constructor<?> javaConstructor = toJavaConstructor();
            return javaConstructor == null ? null : javaConstructor.getParameterAnnotations();
        }
        Method javaMethod = toJava();
        return javaMethod == null ? null : javaMethod.getParameterAnnotations();
    }

    @Override
    public Annotation[] getAnnotations() {
        if (isConstructor()) {
            Constructor<?> javaConstructor = toJavaConstructor();
            return javaConstructor == null ? new Annotation[0] : javaConstructor.getAnnotations();
        }
        Method javaMethod = toJava();
        return javaMethod == null ? new Annotation[0] : javaMethod.getAnnotations();
    }

    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        if (isConstructor()) {
            Constructor<?> javaConstructor = toJavaConstructor();
            return javaConstructor == null ? null : javaConstructor.getAnnotation(annotationClass);
        }
        Method javaMethod = toJava();
        return javaMethod == null ? null : javaMethod.getAnnotation(annotationClass);
    }

    @Override
    public boolean isSynthetic() {
        int modifiers = getAllModifiers();
        return (runtime().getConfig().syntheticFlag & modifiers) != 0;
    }

    public boolean isDefault() {
        if (isConstructor()) {
            return false;
        }
        // Copied from java.lang.Method.isDefault()
        int mask = Modifier.ABSTRACT | Modifier.PUBLIC | Modifier.STATIC;
        return ((getModifiers() & mask) == Modifier.PUBLIC) && getDeclaringClass().isInterface();
    }

    @Override
    public Type[] getGenericParameterTypes() {
        if (isConstructor()) {
            Constructor<?> javaConstructor = toJavaConstructor();
            return javaConstructor == null ? null : javaConstructor.getGenericParameterTypes();
        }
        Method javaMethod = toJava();
        return javaMethod == null ? null : javaMethod.getGenericParameterTypes();
    }

    public Class<?>[] signatureToTypes() {
        Signature sig = getSignature();
        int count = sig.getParameterCount(false);
        Class<?>[] result = new Class<?>[count];
        for (int i = 0; i < result.length; ++i) {
            JavaType parameterType = sig.getParameterType(i, holder);
            HotSpotResolvedJavaType resolvedParameterType = (HotSpotResolvedJavaType) parameterType.resolve(holder);
            result[i] = resolvedParameterType.mirror();
        }
        return result;
    }

    private Method toJava() {
        if (toJavaCache != null) {
            return (Method) toJavaCache;
        }
        try {
            Method result = holder.mirror().getDeclaredMethod(name, signatureToTypes());
            toJavaCache = result;
            return result;
        } catch (NoSuchMethodException | NoClassDefFoundError e) {
            return null;
        }
    }

    private Constructor<?> toJavaConstructor() {
        if (toJavaCache != null) {
            return (Constructor<?>) toJavaCache;
        }
        try {
            Constructor<?> result = holder.mirror().getDeclaredConstructor(signatureToTypes());
            toJavaCache = result;
            return result;
        } catch (NoSuchMethodException | NoClassDefFoundError e) {
            return null;
        }
    }

    @Override
    public boolean canBeInlined() {
        if (isDontInline()) {
            return false;
        }
        return runtime().getCompilerToVM().canInlineMethod(metaspaceMethod);
    }

    @Override
    public boolean shouldBeInlined() {
        if (isForceInline()) {
            return true;
        }
        return runtime().getCompilerToVM().shouldInlineMethod(metaspaceMethod);
    }

    @Override
    public LineNumberTable getLineNumberTable() {
        final boolean hasLineNumberTable = (getConstMethodFlags() & runtime().getConfig().constMethodHasLineNumberTable) != 0;
        if (!hasLineNumberTable) {
            return null;
        }

        long[] values = runtime().getCompilerToVM().getLineNumberTable(metaspaceMethod);
        if (values.length == 0) {
            // Empty table so treat is as non-existent
            return null;
        }
        assert values.length % 2 == 0;
        int[] bci = new int[values.length / 2];
        int[] line = new int[values.length / 2];

        for (int i = 0; i < values.length / 2; i++) {
            bci[i] = (int) values[i * 2];
            line[i] = (int) values[i * 2 + 1];
        }

        return new LineNumberTableImpl(line, bci);
    }

    @Override
    public LocalVariableTable getLocalVariableTable() {
        final boolean hasLocalVariableTable = (getConstMethodFlags() & runtime().getConfig().constMethodHasLocalVariableTable) != 0;
        if (!hasLocalVariableTable) {
            return null;
        }

        HotSpotVMConfig config = runtime().getConfig();
        long localVariableTableElement = runtime().getCompilerToVM().getLocalVariableTableStart(metaspaceMethod);
        final int localVariableTableLength = runtime().getCompilerToVM().getLocalVariableTableLength(metaspaceMethod);
        Local[] locals = new Local[localVariableTableLength];

        for (int i = 0; i < localVariableTableLength; i++) {
            final int startBci = unsafe.getChar(localVariableTableElement + config.localVariableTableElementStartBciOffset);
            final int endBci = startBci + unsafe.getChar(localVariableTableElement + config.localVariableTableElementLengthOffset);
            final int nameCpIndex = unsafe.getChar(localVariableTableElement + config.localVariableTableElementNameCpIndexOffset);
            final int typeCpIndex = unsafe.getChar(localVariableTableElement + config.localVariableTableElementDescriptorCpIndexOffset);
            final int slot = unsafe.getChar(localVariableTableElement + config.localVariableTableElementSlotOffset);

            String localName = getConstantPool().lookupUtf8(nameCpIndex);
            String localType = getConstantPool().lookupUtf8(typeCpIndex);

            locals[i] = new LocalImpl(localName, runtime().lookupType(localType, holder, false), startBci, endBci, slot);

            // Go to the next LocalVariableTableElement
            localVariableTableElement += config.localVariableTableElementSize;
        }

        return new LocalVariableTableImpl(locals);
    }

    /**
     * Returns the offset of this method into the v-table. The method must have a v-table entry as
     * indicated by {@link #isInVirtualMethodTable(ResolvedJavaType)}, otherwise an exception is
     * thrown.
     *
     * @return the offset of this method into the v-table
     */
    public int vtableEntryOffset(ResolvedJavaType resolved) {
        if (!isInVirtualMethodTable(resolved)) {
            throw new JVMCIError("%s does not have a vtable entry", this);
        }
        HotSpotVMConfig config = runtime().getConfig();
        final int vtableIndex = getVtableIndex((HotSpotResolvedObjectTypeImpl) resolved);
        return config.instanceKlassVtableStartOffset + vtableIndex * config.vtableEntrySize + config.vtableEntryMethodOffset;
    }

    @Override
    public boolean isInVirtualMethodTable(ResolvedJavaType resolved) {
        if (resolved instanceof HotSpotResolvedObjectTypeImpl) {
            HotSpotResolvedObjectTypeImpl hotspotResolved = (HotSpotResolvedObjectTypeImpl) resolved;
            int vtableIndex = getVtableIndex(hotspotResolved);
            return vtableIndex >= 0 && vtableIndex < hotspotResolved.getVtableLength();
        }
        return false;
    }

    private int getVtableIndex(HotSpotResolvedObjectTypeImpl resolved) {
        if (!holder.isLinked()) {
            return runtime().getConfig().invalidVtableIndex;
        }
        if (holder.isInterface()) {
            if (resolved.isInterface()) {
                return runtime().getConfig().invalidVtableIndex;
            }
            return getVtableIndexForInterface(resolved);
        }
        return getVtableIndex();
    }

    /**
     * Returns this method's virtual table index.
     *
     * @return virtual table index
     */
    private int getVtableIndex() {
        assert !holder.isInterface();
        HotSpotVMConfig config = runtime().getConfig();
        int result = unsafe.getInt(metaspaceMethod + config.methodVtableIndexOffset);
        assert result >= config.nonvirtualVtableIndex : "must be linked";
        return result;
    }

    private int getVtableIndexForInterface(ResolvedJavaType resolved) {
        HotSpotResolvedObjectTypeImpl hotspotType = (HotSpotResolvedObjectTypeImpl) resolved;
        return runtime().getCompilerToVM().getVtableIndexForInterface(hotspotType.getMetaspaceKlass(), getMetaspaceMethod());
    }

    /**
     * The {@link SpeculationLog} for methods compiled by Graal hang off this per-declaring-type
     * {@link ClassValue}. The raw Method* value is safe to use as a key in the map as a) it is
     * never moves and b) we never read from it.
     * <p>
     * One implication is that we will preserve {@link SpeculationLog}s for methods that have been
     * redefined via class redefinition. It's tempting to periodically flush such logs but we cannot
     * read the JVM_ACC_IS_OBSOLETE bit (or anything else) via the raw pointer as obsoleted methods
     * are subject to clean up and deletion (see InstanceKlass::purge_previous_versions_internal).
     */
    private static final ClassValue<Map<Long, SpeculationLog>> SpeculationLogs = new ClassValue<Map<Long, SpeculationLog>>() {
        @Override
        protected Map<Long, SpeculationLog> computeValue(java.lang.Class<?> type) {
            return new HashMap<>(4);
        }
    };

    public SpeculationLog getSpeculationLog() {
        Map<Long, SpeculationLog> map = SpeculationLogs.get(holder.mirror());
        synchronized (map) {
            SpeculationLog log = map.get(this.metaspaceMethod);
            if (log == null) {
                log = new HotSpotSpeculationLog();
                map.put(metaspaceMethod, log);
            }
            return log;
        }
    }

    public int intrinsicId() {
        HotSpotVMConfig config = runtime().getConfig();
        return unsafe.getByte(metaspaceMethod + config.methodIntrinsicIdOffset) & 0xff;
    }

    @Override
    public JavaConstant invoke(JavaConstant receiver, JavaConstant[] arguments) {
        assert !isConstructor();
        Method javaMethod = toJava();
        javaMethod.setAccessible(true);

        Object[] objArguments = new Object[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            objArguments[i] = HotSpotObjectConstantImpl.asBoxedValue(arguments[i]);
        }
        Object objReceiver = receiver != null && !receiver.isNull() ? ((HotSpotObjectConstantImpl) receiver).object() : null;

        try {
            Object objResult = javaMethod.invoke(objReceiver, objArguments);
            return javaMethod.getReturnType() == void.class ? null : HotSpotObjectConstantImpl.forBoxedValue(getSignature().getReturnKind(), objResult);

        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    /**
     * Allocates a compile id for this method by asking the VM for one.
     *
     * @param entryBCI entry bci
     * @return compile id
     */
    public int allocateCompileId(int entryBCI) {
        return runtime().getCompilerToVM().allocateCompileId(metaspaceMethod, entryBCI);
    }

    public boolean hasCodeAtLevel(int entryBCI, int level) {
        if (entryBCI == runtime().getConfig().invocationEntryBci) {
            return hasCompiledCodeAtLevel(level);
        }
        return runtime().getCompilerToVM().hasCompiledCodeForOSR(metaspaceMethod, entryBCI, level);
    }

    private int methodId;

    public void setMethodId(int id) {
        assert methodId == 0;
        methodId = id;
    }

    public int getMethodId() {
        return methodId;
    }
}
