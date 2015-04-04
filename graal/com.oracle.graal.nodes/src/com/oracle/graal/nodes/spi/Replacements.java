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
package com.oracle.graal.nodes.spi;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.replacements.*;
import com.oracle.graal.nodes.*;

/**
 * Interface for managing replacements.
 */
public interface Replacements {

    /**
     * Gets the snippet graph derived from a given method.
     *
     * @param args arguments to the snippet if available, otherwise {@code null}
     * @return the snippet graph, if any, that is derived from {@code method}
     */
    StructuredGraph getSnippet(ResolvedJavaMethod method, Object[] args);

    /**
     * Gets the snippet graph derived from a given method.
     *
     * @param recursiveEntry if the snippet contains a call to this method, it's considered as
     *            recursive call and won't be processed for {@linkplain MethodSubstitution
     *            substitutions}.
     * @param args arguments to the snippet if available, otherwise {@code null}
     * @return the snippet graph, if any, that is derived from {@code method}
     */
    StructuredGraph getSnippet(ResolvedJavaMethod method, ResolvedJavaMethod recursiveEntry, Object[] args);

    /**
     * Registers a method as snippet.
     */
    void registerSnippet(ResolvedJavaMethod method);

    /**
     * Notifies this object during snippet specialization once the specialized snippet's constant
     * parameters have been replaced with constant values.
     *
     * @param specializedSnippet the snippet in the process of being specialized. This is a copy of
     *            the unspecialized snippet graph created during snippet preparation.
     */
    void notifyAfterConstantsBound(StructuredGraph specializedSnippet);

    /**
     * Gets a graph that is a substitution for a given method.
     *
     * @return the graph, if any, that is a substitution for {@code method}
     */
    default StructuredGraph getMethodSubstitution(ResolvedJavaMethod method) {
        return getMethodSubstitution(method, false);
    }

    /**
     * Gets a graph that is a substitution for a given method.
     *
     * @param fromBytecodeOnly only return a graph created by parsing the bytecode of another method
     * @return the graph, if any, that is a substitution for {@code method}
     */
    StructuredGraph getMethodSubstitution(ResolvedJavaMethod method, boolean fromBytecodeOnly);

    /**
     * Determines if there is a {@linkplain #getMethodSubstitution(ResolvedJavaMethod) substitution
     * graph} for a given method.
     *
     * @return true iff there is a substitution graph available for {@code method}
     */
    boolean hasMethodSubstitution(ResolvedJavaMethod method);

    /**
     * Gets the method that is a substitution for a given method.
     *
     * @return the method, if any, that is a substitution for {@code method}
     */
    ResolvedJavaMethod getMethodSubstitutionMethod(ResolvedJavaMethod method);

    /**
     * Registers all the {@linkplain MethodSubstitution method} substitutions defined by a given
     * class.
     *
     * @param original the original class for which substitutions are being registered. This must be
     *            the same type denoted by the {@link ClassSubstitution} annotation on
     *            {@code substitutions}. It is required here so that an implementation is not forced
     *            to read annotations during registration.
     * @param substitutions the class defining substitutions for {@code original}. This class must
     *            be annotated with {@link ClassSubstitution}.
     */
    void registerSubstitutions(Type original, Class<?> substitutions);

    /**
     * Returns all methods that are currently registered as method/macro substitution or as a
     * snippet.
     */
    Collection<ResolvedJavaMethod> getAllReplacements();

    /**
     * Determines whether the replacement of this method is flagged as being inlined always.
     */
    boolean isForcedSubstitution(ResolvedJavaMethod methodAt);

    /**
     * Register snippet templates.
     */
    void registerSnippetTemplateCache(SnippetTemplateCache snippetTemplates);

    /**
     * Get snippet templates that were registered with
     * {@link Replacements#registerSnippetTemplateCache(SnippetTemplateCache)}.
     */
    <T extends SnippetTemplateCache> T getSnippetTemplateCache(Class<T> templatesClass);
}
