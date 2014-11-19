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
package com.oracle.graal.hotspot;

import java.lang.reflect.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.replacements.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.replacements.*;
import com.oracle.graal.hotspot.word.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.phases.util.*;
import com.oracle.graal.replacements.*;
import com.oracle.graal.replacements.IntegerSubstitutions;
import com.oracle.graal.replacements.LongSubstitutions;
import com.oracle.graal.word.phases.*;

/**
 * Filters certain method substitutions based on whether there is underlying hardware support for
 * them.
 */
public class HotSpotReplacementsImpl extends ReplacementsImpl {

    private final HotSpotVMConfig config;

    public HotSpotReplacementsImpl(Providers providers, SnippetReflectionProvider snippetReflection, HotSpotVMConfig config, Assumptions assumptions, TargetDescription target) {
        super(providers, snippetReflection, assumptions, target);
        this.config = config;
    }

    @Override
    protected ResolvedJavaMethod registerMethodSubstitution(ClassReplacements cr, Executable originalMethod, Method substituteMethod) {
        final Class<?> substituteClass = substituteMethod.getDeclaringClass();
        if (substituteClass.getDeclaringClass() == BoxingSubstitutions.class) {
            if (config.useHeapProfiler) {
                return null;
            }
        } else if (substituteClass == IntegerSubstitutions.class || substituteClass == LongSubstitutions.class) {
            if (substituteMethod.getName().equals("bitCount")) {
                if (!config.usePopCountInstruction) {
                    return null;
                }
            } else if (substituteMethod.getName().equals("numberOfLeadingZeros")) {
                if (config.useCountLeadingZerosInstruction) {
                    return null;
                }
            } else if (substituteMethod.getName().equals("numberOfTrailingZeros")) {
                if (config.useCountTrailingZerosInstruction) {
                    return null;
                }
            }
        } else if (substituteClass == CRC32Substitutions.class) {
            if (!config.useCRC32Intrinsics) {
                return null;
            }
        } else if (substituteClass == StringSubstitutions.class) {
            /*
             * AMD64's String.equals substitution needs about 8 registers so we better disable the
             * substitution if there is some register pressure.
             */
            if (GraalOptions.RegisterPressure.getValue() != null) {
                return null;
            }
        }
        return super.registerMethodSubstitution(cr, originalMethod, substituteMethod);
    }

    @Override
    public Class<? extends FixedWithNextNode> getMacroSubstitution(ResolvedJavaMethod method) {
        HotSpotResolvedJavaMethod hsMethod = (HotSpotResolvedJavaMethod) method;
        int intrinsicId = hsMethod.intrinsicId();
        if (intrinsicId != 0) {
            /*
             * The methods of MethodHandle that need substitution are signature-polymorphic, i.e.,
             * the VM replicates them for every signature that they are actually used for.
             * Therefore, we cannot use the usual annotation-driven mechanism to define the
             */
            if (MethodHandleNode.lookupMethodHandleIntrinsic(method, providers.getConstantReflection().getMethodHandleAccess()) != null) {
                return MethodHandleNode.class;
            }
        }
        return super.getMacroSubstitution(method);
    }

    @Override
    protected GraphMaker createGraphMaker(ResolvedJavaMethod substitute, ResolvedJavaMethod original, FrameStateProcessing frameStateProcessing) {
        return new HotSpotGraphMaker(this, substitute, original, frameStateProcessing);
    }

    public static class HotSpotGraphMaker extends ReplacementsImpl.GraphMaker {

        public HotSpotGraphMaker(ReplacementsImpl replacements, ResolvedJavaMethod substitute, ResolvedJavaMethod substitutedMethod, FrameStateProcessing frameStateProcessing) {
            super(replacements, substitute, substitutedMethod, frameStateProcessing);
        }

        @Override
        protected void afterParsing(StructuredGraph graph) {
            MetaAccessProvider metaAccess = replacements.providers.getMetaAccess();
            new WordTypeVerificationPhase(metaAccess, replacements.snippetReflection, replacements.providers.getConstantReflection(), replacements.target.wordKind).apply(graph);
            new HotSpotWordTypeRewriterPhase(metaAccess, replacements.snippetReflection, replacements.providers.getConstantReflection(), replacements.target.wordKind).apply(graph);
        }
    }
}
