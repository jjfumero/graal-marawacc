/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.graphbuilderconf;

import java.lang.invoke.*;
import java.lang.reflect.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.graphbuilderconf.InvocationPlugins.Receiver;
import com.oracle.graal.nodes.*;

/**
 * Plugin for handling a specific method invocation.
 */
public interface InvocationPlugin extends GraphBuilderPlugin {

    /**
     * Determines if this plugin is for a method with a polymorphic signature (e.g.
     * {@link MethodHandle#invokeExact(Object...)}).
     */
    default boolean isSignaturePolymorphic() {
        return false;
    }

    /**
     * Handles invocation of a signature polymorphic method.
     *
     * @param receiver access to the receiver, {@code null} if {@code targetMethod} is static
     * @param argsIncludingReceiver all arguments to the invocation include the raw receiver in
     *            position 0 if {@code targetMethod} is not static
     * @see #execute
     */
    default boolean applyPolymorphic(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode... argsIncludingReceiver) {
        return defaultHandler(b, targetMethod, receiver, argsIncludingReceiver);
    }

    /**
     * @see #execute
     */
    default boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
        return defaultHandler(b, targetMethod, receiver);
    }

    /**
     * @see #execute
     */
    default boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode arg) {
        return defaultHandler(b, targetMethod, receiver, arg);
    }

    /**
     * @see #execute
     */
    default boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode arg1, ValueNode arg2) {
        return defaultHandler(b, targetMethod, receiver, arg1, arg2);
    }

    /**
     * @see #execute
     */
    default boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode arg1, ValueNode arg2, ValueNode arg3) {
        return defaultHandler(b, targetMethod, receiver, arg1, arg2, arg3);
    }

    /**
     * @see #execute
     */
    default boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode arg1, ValueNode arg2, ValueNode arg3, ValueNode arg4) {
        return defaultHandler(b, targetMethod, receiver, arg1, arg2, arg3, arg4);
    }

    /**
     * @see #execute
     */
    default boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode arg1, ValueNode arg2, ValueNode arg3, ValueNode arg4, ValueNode arg5) {
        return defaultHandler(b, targetMethod, receiver, arg1, arg2, arg3, arg4, arg5);
    }

    default ResolvedJavaMethod getSubstitute() {
        return null;
    }

    /**
     * Executes a given plugin against a set of invocation arguments by dispatching to the
     * {@code apply(...)} method that matches the number of arguments or to
     * {@link #applyPolymorphic} if {@code plugin} is {@linkplain #isSignaturePolymorphic()
     * signature polymorphic}.
     *
     * @param targetMethod the method for which plugin is being applied
     * @param receiver access to the receiver, {@code null} if {@code targetMethod} is static
     * @param argsIncludingReceiver all arguments to the invocation include the receiver in position
     *            0 if {@code targetMethod} is not static
     * @return {@code true} if the plugin handled the invocation of {@code targetMethod}
     *         {@code false} if the graph builder should process the invoke further (e.g., by
     *         inlining it or creating an {@link Invoke} node). A plugin that does not handle an
     *         invocation must not modify the graph being constructed.
     */
    static boolean execute(GraphBuilderContext b, ResolvedJavaMethod targetMethod, InvocationPlugin plugin, Receiver receiver, ValueNode[] argsIncludingReceiver) {
        if (plugin.isSignaturePolymorphic()) {
            return plugin.applyPolymorphic(b, targetMethod, receiver, argsIncludingReceiver);
        } else if (receiver != null) {
            assert !targetMethod.isStatic();
            assert argsIncludingReceiver.length > 0;
            if (argsIncludingReceiver.length == 1) {
                return plugin.apply(b, targetMethod, receiver);
            } else if (argsIncludingReceiver.length == 2) {
                return plugin.apply(b, targetMethod, receiver, argsIncludingReceiver[1]);
            } else if (argsIncludingReceiver.length == 3) {
                return plugin.apply(b, targetMethod, receiver, argsIncludingReceiver[1], argsIncludingReceiver[2]);
            } else if (argsIncludingReceiver.length == 4) {
                return plugin.apply(b, targetMethod, receiver, argsIncludingReceiver[1], argsIncludingReceiver[2], argsIncludingReceiver[3]);
            } else if (argsIncludingReceiver.length == 5) {
                return plugin.apply(b, targetMethod, receiver, argsIncludingReceiver[1], argsIncludingReceiver[2], argsIncludingReceiver[3], argsIncludingReceiver[4]);
            } else {
                return plugin.defaultHandler(b, targetMethod, receiver, argsIncludingReceiver);
            }
        } else {
            assert targetMethod.isStatic();
            if (argsIncludingReceiver.length == 0) {
                return plugin.apply(b, targetMethod, null);
            } else if (argsIncludingReceiver.length == 1) {
                return plugin.apply(b, targetMethod, null, argsIncludingReceiver[0]);
            } else if (argsIncludingReceiver.length == 2) {
                return plugin.apply(b, targetMethod, null, argsIncludingReceiver[0], argsIncludingReceiver[1]);
            } else if (argsIncludingReceiver.length == 3) {
                return plugin.apply(b, targetMethod, null, argsIncludingReceiver[0], argsIncludingReceiver[1], argsIncludingReceiver[2]);
            } else if (argsIncludingReceiver.length == 4) {
                return plugin.apply(b, targetMethod, null, argsIncludingReceiver[0], argsIncludingReceiver[1], argsIncludingReceiver[2], argsIncludingReceiver[3]);
            } else if (argsIncludingReceiver.length == 5) {
                return plugin.apply(b, targetMethod, null, argsIncludingReceiver[0], argsIncludingReceiver[1], argsIncludingReceiver[2], argsIncludingReceiver[3], argsIncludingReceiver[4]);
            } else {
                return plugin.defaultHandler(b, targetMethod, receiver, argsIncludingReceiver);
            }

        }
    }

    /**
     * Handles an invocation when a specific {@code apply} method is not available.
     */
    default boolean defaultHandler(@SuppressWarnings("unused") GraphBuilderContext b, ResolvedJavaMethod targetMethod, @SuppressWarnings("unused") Receiver receiver, ValueNode... args) {
        throw new GraalInternalError("Invocation plugin for %s does not handle invocations with %d arguments", targetMethod.format("%H.%n(%p)"), args.length);
    }

    default StackTraceElement getApplySourceLocation(MetaAccessProvider metaAccess) {
        Class<?> c = getClass();
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals("apply")) {
                return metaAccess.lookupJavaMethod(m).asStackTraceElement(0);
            } else if (m.getName().equals("defaultHandler")) {
                return metaAccess.lookupJavaMethod(m).asStackTraceElement(0);
            }
        }
        throw new GraalInternalError("could not find method named \"apply\" or \"defaultHandler\" in " + c.getName());
    }
}
