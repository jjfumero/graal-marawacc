/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.graal.hotspot.phases;

import static com.oracle.graal.api.meta.MetaUtil.*;
import static com.oracle.graal.hotspot.HotSpotGraalRuntime.*;
import static java.lang.reflect.Modifier.*;

import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.java.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.options.*;
import com.oracle.graal.phases.*;

/**
 * Verifies that a class that declares one or more HotSpot {@linkplain OptionValue options} has a
 * class initializer that only initializes the option(s). This sanity check prevents an option being
 * read to initialize some global state before it is parsed on the command line. The latter occurs
 * if an option declaring class has a class initializer that reads options or triggers other class
 * initializers that read options.
 */
public class VerifyHotSpotOptionsPhase extends Phase {

    public static boolean checkOptions() {
        HotSpotRuntime runtime = graalRuntime().getRuntime();
        ServiceLoader<Options> sl = ServiceLoader.loadInstalled(Options.class);
        Set<HotSpotResolvedObjectType> checked = new HashSet<>();
        for (Options opts : sl) {
            for (OptionDescriptor desc : opts) {
                if (HotSpotOptions.isHotSpotOption(desc)) {
                    HotSpotResolvedObjectType holder = (HotSpotResolvedObjectType) runtime.lookupJavaType(desc.getDeclaringClass());
                    checkType(holder, desc, runtime, checked);
                }
            }
        }
        return true;
    }

    private static void checkType(HotSpotResolvedObjectType type, OptionDescriptor option, HotSpotRuntime runtime, Set<HotSpotResolvedObjectType> checked) {
        if (!checked.contains(type)) {
            checked.add(type);
            HotSpotResolvedObjectType superType = type.getSupertype();
            if (superType != null && !MetaUtil.isJavaLangObject(superType)) {
                checkType(superType, option, runtime, checked);
            }
            for (ResolvedJavaMethod method : type.getMethods()) {
                if (method.isClassInitializer()) {
                    StructuredGraph graph = new StructuredGraph(method);
                    new GraphBuilderPhase(runtime, GraphBuilderConfiguration.getEagerDefault(), OptimisticOptimizations.ALL).apply(graph);
                    new VerifyHotSpotOptionsPhase(type, runtime, option).apply(graph);
                }
            }
        }
    }

    private final HotSpotRuntime runtime;
    private final ResolvedJavaType declaringClass;
    private final ResolvedJavaType optionValueType;
    private final Set<ResolvedJavaType> boxingTypes;
    private final OptionDescriptor option;

    public VerifyHotSpotOptionsPhase(ResolvedJavaType declaringClass, HotSpotRuntime runtime, OptionDescriptor option) {
        this.runtime = runtime;
        this.declaringClass = declaringClass;
        this.optionValueType = runtime.lookupJavaType(OptionValue.class);
        this.option = option;
        this.boxingTypes = new HashSet<>();
        for (Class c : new Class[]{Boolean.class, Byte.class, Short.class, Character.class, Integer.class, Float.class, Long.class, Double.class}) {
            this.boxingTypes.add(runtime.lookupJavaType(c));
        }
    }

    /**
     * Checks whether a given method is allowed to be called.
     */
    private boolean checkInvokeTarget(ResolvedJavaMethod method) {
        ResolvedJavaType holder = method.getDeclaringClass();
        if (method.isConstructor()) {
            if (optionValueType.isAssignableFrom(holder)) {
                return true;
            }
        } else if (boxingTypes.contains(holder)) {
            return method.getName().equals("valueOf");
        } else if (method.getDeclaringClass() == runtime.lookupJavaType(Class.class)) {
            return method.getName().equals("desiredAssertionStatus");
        } else if (method.getDeclaringClass().equals(declaringClass)) {
            return (method.getName().equals("$jacocoInit"));
        }
        return false;
    }

    @Override
    protected void run(StructuredGraph graph) {
        for (ValueNode node : graph.getNodes().filter(ValueNode.class)) {
            if (node instanceof StoreFieldNode) {
                HotSpotResolvedJavaField field = (HotSpotResolvedJavaField) ((StoreFieldNode) node).field();
                verify(field.getDeclaringClass() == declaringClass, node, "store to field " + format("%H.%n", field));
                verify(isStatic(field.getModifiers()), node, "store to field " + format("%H.%n", field));
                if (optionValueType.isAssignableFrom((ResolvedJavaType) field.getType())) {
                    verify(isFinal(field.getModifiers()), node, "option field " + format("%H.%n", field) + " not final");
                } else {
                    verify((field.isSynthetic()), node, "store to non-synthetic field " + format("%H.%n", field));
                }
            } else if (node instanceof Invoke) {
                Invoke invoke = (Invoke) node;
                MethodCallTargetNode callTarget = (MethodCallTargetNode) invoke.callTarget();
                ResolvedJavaMethod targetMethod = callTarget.targetMethod();
                verify(checkInvokeTarget(targetMethod), node, "invocation of " + format("%H.%n(%p)", targetMethod));
            }
        }
    }

    private void verify(boolean condition, Node node, String message) {
        if (!condition) {
            error(node, message);
        }
    }

    private void error(Node node, String message) {
        String loc = GraphUtil.approxSourceLocation(node);
        throw new GraalInternalError(String.format("The " + option.getName() + " option is declared in " + option.getDeclaringClass() +
                        " whose class hierarchy contains a class initializer (in %s) with a code pattern at or near %s implying an action other than initialization of an option:%n%n    %s%n%n" +
                        "The recommended solution is to move " + option.getName() + " into a separate class (e.g., " + option.getDeclaringClass().getName() + ".Options).%n",
                        toJavaName(declaringClass), loc, message));
    }
}
