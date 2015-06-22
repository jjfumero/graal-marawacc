/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
package jdk.internal.jvmci.service;

import java.util.*;

import sun.reflect.*;

/**
 * An mechanism for accessing service providers via JVMCI. These providers are loaded via a JVMCI
 * class loader that is hidden from application code. Hence the {@link SecurityManager} checks in
 * {@link #load(Class)} and {@link #loadSingle(Class, boolean)}.
 */
public final class Services {

    private Services() {
    }

    private static final String SUPPRESS_PROPERTY_NAME = "jvmci.service.suppressNoClassDefFoundError";

    /**
     * Determines whether to suppress the {@link NoClassDefFoundError} raised if a service provider
     * class specified in a {@code <jre>/jvmci/services/*} file is missing.
     */
    private static final boolean SuppressNoClassDefFoundError = Boolean.getBoolean(SUPPRESS_PROPERTY_NAME);

    private static final ClassValue<List<?>> cache = new ClassValue<List<?>>() {
        @Override
        protected List<?> computeValue(Class<?> type) {
            try {
                return Arrays.asList(getServiceImpls(type));
            } catch (NoClassDefFoundError e) {
                if (SuppressNoClassDefFoundError) {
                    return Collections.emptyList();
                } else {
                    NoClassDefFoundError newEx = new NoClassDefFoundError(e.getMessage() + "  (suppress with -D" + SUPPRESS_PROPERTY_NAME + "=true)");
                    if (e.getCause() != null) {
                        newEx.initCause(e.getCause());
                    }
                    throw newEx;
                }
            }
        }
    };

    /**
     * Gets an {@link Iterable} of the JVMCI providers available for a given service.
     *
     * @throws SecurityException if a security manager is present and it denies
     *             <tt>{@link RuntimePermission}("jvmciServices")</tt>
     */
    @SuppressWarnings("unchecked")
    @CallerSensitive
    public static <S> Iterable<S> load(Class<S> service) {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new RuntimePermission("jvmciServices"));
        }
        try {
            return (Iterable<S>) cache.get(service);
        } catch (UnsatisfiedLinkError e) {
            return Collections.emptyList();
        }
    }

    /**
     * Gets the JVMCI provider for a given service for which at most one provider must be available.
     *
     * @param service the service whose provider is being requested
     * @param required specifies if an {@link InternalError} should be thrown if no provider of
     *            {@code service} is available
     * @throws SecurityException if a security manager is present and it denies
     *             <tt>{@link RuntimePermission}("jvmciServices")</tt>
     */
    @SuppressWarnings({"unchecked"})
    @CallerSensitive
    public static <S> S loadSingle(Class<S> service, boolean required) {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new RuntimePermission("jvmciServices"));
        }
        Iterable<S> providers;
        try {
            providers = (Iterable<S>) cache.get(service);
        } catch (UnsatisfiedLinkError e) {
            providers = Collections.emptyList();
        }

        S singleProvider = null;
        for (S provider : providers) {
            if (singleProvider != null) {
                throw new InternalError(String.format("Multiple %s providers found: %s, %s", service.getName(), singleProvider.getClass().getName(), provider.getClass().getName()));
            }
            singleProvider = provider;
        }
        if (singleProvider == null && required) {
            String javaHome = System.getProperty("java.home");
            String vmName = System.getProperty("java.vm.name");
            Formatter errorMessage = new Formatter();
            errorMessage.format("The VM does not expose required service %s.%n", service.getName());
            errorMessage.format("Currently used Java home directory is %s.%n", javaHome);
            errorMessage.format("Currently used VM configuration is: %s", vmName);
            throw new UnsupportedOperationException(errorMessage.toString());
        }
        return singleProvider;
    }

    static {
        Reflection.registerMethodsToFilter(Services.class, "getServiceImpls");
        Reflection.registerFieldsToFilter(Services.class, "cache");
    }

    private static native <S> S[] getServiceImpls(Class<?> service);
}
