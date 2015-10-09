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
package com.oracle.graal.hotspot.meta;

import static jdk.vm.ci.hotspot.HotSpotVMConfig.config;
import jdk.vm.ci.hotspot.HotSpotConstantReflectionProvider;
import jdk.vm.ci.hotspot.HotSpotObjectConstant;
import jdk.vm.ci.hotspot.HotSpotVMConfig;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaType;

import com.oracle.graal.api.replacements.SnippetReflectionProvider;
import com.oracle.graal.hotspot.HotSpotGraalRuntimeProvider;

public class HotSpotSnippetReflectionProvider implements SnippetReflectionProvider {

    private final HotSpotGraalRuntimeProvider runtime;
    private final HotSpotConstantReflectionProvider constantReflection;

    public HotSpotSnippetReflectionProvider(HotSpotGraalRuntimeProvider runtime, HotSpotConstantReflectionProvider constantReflection) {
        this.runtime = runtime;
        this.constantReflection = constantReflection;
    }

    @Override
    public JavaConstant forObject(Object object) {
        return constantReflection.forObject(object);
    }

    @Override
    public Object asObject(ResolvedJavaType type, JavaConstant constant) {
        if (constant.isNull()) {
            return null;
        }
        HotSpotObjectConstant hsConstant = (HotSpotObjectConstant) constant;
        return hsConstant.asObject(type);
    }

    @Override
    public <T> T asObject(Class<T> type, JavaConstant constant) {
        if (constant.isNull()) {
            return null;
        }
        HotSpotObjectConstant hsConstant = (HotSpotObjectConstant) constant;
        return hsConstant.asObject(type);
    }

    @Override
    public JavaConstant forBoxed(JavaKind kind, Object value) {
        if (kind == JavaKind.Object) {
            return forObject(value);
        } else {
            return JavaConstant.forBoxedPrimitive(value);
        }
    }

    public Object getSubstitutionGuardParameter(Class<?> type) {
        if (type.isInstance(runtime)) {
            return runtime;
        }
        HotSpotVMConfig config = config();
        if (type.isInstance(config)) {
            return config;
        }
        return null;
    }

    // Lazily initialized
    private ResolvedJavaType wordTypesType;
    private ResolvedJavaType runtimeType;
    private ResolvedJavaType configType;

    public Object getInjectedNodeIntrinsicParameter(ResolvedJavaType type) {
        // Need to test all fields since there no guarantee under the JMM
        // about the order in which these fields are written.
        HotSpotVMConfig config = config();
        if (configType == null || wordTypesType == null || configType == null) {
            MetaAccessProvider metaAccess = runtime.getHostProviders().getMetaAccess();
            wordTypesType = metaAccess.lookupJavaType(runtime.getHostProviders().getWordTypes().getClass());
            runtimeType = metaAccess.lookupJavaType(runtime.getClass());
            configType = metaAccess.lookupJavaType(config.getClass());
        }

        if (type.isAssignableFrom(wordTypesType)) {
            return runtime.getHostProviders().getWordTypes();
        }
        if (type.isAssignableFrom(runtimeType)) {
            return runtime;
        }
        if (type.isAssignableFrom(configType)) {
            return config;
        }
        return null;
    }
}
