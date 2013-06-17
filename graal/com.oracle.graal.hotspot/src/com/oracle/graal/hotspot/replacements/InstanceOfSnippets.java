/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.replacements;

import static com.oracle.graal.api.code.DeoptimizationAction.*;
import static com.oracle.graal.api.meta.DeoptimizationReason.*;
import static com.oracle.graal.hotspot.HotSpotGraalRuntime.*;
import static com.oracle.graal.hotspot.replacements.HotSpotReplacementsUtil.*;
import static com.oracle.graal.hotspot.replacements.TypeCheckSnippetUtils.*;
import static com.oracle.graal.phases.GraalOptions.*;
import static com.oracle.graal.replacements.nodes.BranchProbabilityNode.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.meta.ProfilingInfo.TriState;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.replacements.TypeCheckSnippetUtils.Hints;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.replacements.*;
import com.oracle.graal.replacements.Snippet.ConstantParameter;
import com.oracle.graal.replacements.Snippet.VarargsParameter;
import com.oracle.graal.replacements.SnippetTemplate.Arguments;
import com.oracle.graal.replacements.SnippetTemplate.SnippetInfo;
import com.oracle.graal.replacements.nodes.*;
import com.oracle.graal.word.*;

/**
 * Snippets used for implementing the type test of an instanceof instruction. Since instanceof is a
 * floating node, it is lowered separately for each of its usages.
 * 
 * The type tests implemented are described in the paper <a
 * href="http://dl.acm.org/citation.cfm?id=583821"> Fast subtype checking in the HotSpot JVM</a> by
 * Cliff Click and John Rose.
 */
public class InstanceOfSnippets implements Snippets {

    /**
     * Gets the minimum required probability of a profiled instanceof hitting one the profiled types
     * for use of the {@linkplain #instanceofWithProfile deoptimizing} snippet. The value is
     * computed to be an order of magnitude greater than the configured compilation threshold. For
     * example, if a method is compiled after being interpreted 10000 times, the deoptimizing
     * snippet will only be used for an instanceof if its profile indicates that less than 1 in
     * 100000 executions are for an object whose type is not one of the top N profiled types (where
     * {@code N == } {@link GraalOptions#InstanceOfMaxHints}).
     */
    public static double hintHitProbabilityThresholdForDeoptimizingSnippet() {
        return 1.0D - (1.0D / (graalRuntime().getConfig().compileThreshold * 10));
    }

    /**
     * A test against a set of hints derived from a profile with very close to 100% precise coverage
     * of seen types. This snippet deoptimizes on hint miss paths.
     * 
     * @see #hintHitProbabilityThresholdForDeoptimizingSnippet()
     */
    @Snippet
    public static Object instanceofWithProfile(Object object, @VarargsParameter Word[] hints, @VarargsParameter boolean[] hintIsPositive, Object trueValue, Object falseValue,
                    @ConstantParameter boolean checkNull, @ConstantParameter boolean nullSeen) {
        if (probability(NOT_FREQUENT_PROBABILITY, checkNull && object == null)) {
            isNull.inc();
            if (!nullSeen) {
                // In this case, the execution is contradicting the profile
                // so invalidating and re-profiling is justified.
                DeoptimizeNode.deopt(InvalidateReprofile, OptimizedTypeCheckViolated);
            }
            return falseValue;
        }
        Word objectHub = loadHub(object);
        // if we get an exact match: succeed immediately
        ExplodeLoopNode.explodeLoop();
        for (int i = 0; i < hints.length; i++) {
            Word hintHub = hints[i];
            boolean positive = hintIsPositive[i];
            if (probability(NOT_FREQUENT_PROBABILITY, hintHub.equal(objectHub))) {
                hintsHit.inc();
                return positive ? trueValue : falseValue;
            }
        }
        // Don't throw away the code as we assume this is a rare event
        // that will periodically occur.
        DeoptimizeNode.deopt(DeoptimizationAction.None, OptimizedTypeCheckViolated);
        return falseValue;
    }

    /**
     * A test against a final type.
     */
    @Snippet
    public static Object instanceofExact(Object object, Word exactHub, Object trueValue, Object falseValue, @ConstantParameter boolean checkNull) {
        if (checkNull && probability(NOT_FREQUENT_PROBABILITY, object == null)) {
            isNull.inc();
            return falseValue;
        }
        Word objectHub = loadHub(object);
        if (probability(LIKELY_PROBABILITY, objectHub.notEqual(exactHub))) {
            exactMiss.inc();
            return falseValue;
        }
        exactHit.inc();
        return trueValue;
    }

    /**
     * A test against a primary type.
     */
    @Snippet
    public static Object instanceofPrimary(Word hub, Object object, @ConstantParameter int superCheckOffset, Object trueValue, Object falseValue, @ConstantParameter boolean checkNull) {
        if (checkNull && probability(NOT_FREQUENT_PROBABILITY, object == null)) {
            isNull.inc();
            return falseValue;
        }
        Word objectHub = loadHub(object);
        if (probability(NOT_LIKELY_PROBABILITY, objectHub.readWord(superCheckOffset, LocationIdentity.FINAL_LOCATION).notEqual(hub))) {
            displayMiss.inc();
            return falseValue;
        }
        displayHit.inc();
        return trueValue;
    }

    /**
     * A test against a restricted secondary type type.
     */
    @Snippet
    public static Object instanceofSecondary(Word hub, Object object, @VarargsParameter Word[] hints, @VarargsParameter boolean[] hintIsPositive, Object trueValue, Object falseValue,
                    @ConstantParameter boolean checkNull) {
        if (checkNull && probability(NOT_FREQUENT_PROBABILITY, object == null)) {
            isNull.inc();
            return falseValue;
        }
        Word objectHub = loadHub(object);
        // if we get an exact match: succeed immediately
        ExplodeLoopNode.explodeLoop();
        for (int i = 0; i < hints.length; i++) {
            Word hintHub = hints[i];
            boolean positive = hintIsPositive[i];
            if (probability(NOT_FREQUENT_PROBABILITY, hintHub.equal(objectHub))) {
                hintsHit.inc();
                return positive ? trueValue : falseValue;
            }
        }
        if (!checkSecondarySubType(hub, objectHub)) {
            return falseValue;
        }
        return trueValue;
    }

    /**
     * Type test used when the type being tested against is not known at compile time.
     */
    @Snippet
    public static Object instanceofDynamic(Class mirror, Object object, Object trueValue, Object falseValue, @ConstantParameter boolean checkNull) {
        if (checkNull && probability(NOT_FREQUENT_PROBABILITY, object == null)) {
            isNull.inc();
            return falseValue;
        }

        Word hub = loadWordFromObject(mirror, klassOffset());
        Word objectHub = loadHub(object);
        if (!checkUnknownSubType(hub, objectHub)) {
            return falseValue;
        }
        return trueValue;
    }

    public static class Templates extends InstanceOfSnippetsTemplates {

        private final SnippetInfo instanceofWithProfile = snippet(InstanceOfSnippets.class, "instanceofWithProfile");
        private final SnippetInfo instanceofExact = snippet(InstanceOfSnippets.class, "instanceofExact");
        private final SnippetInfo instanceofPrimary = snippet(InstanceOfSnippets.class, "instanceofPrimary");
        private final SnippetInfo instanceofSecondary = snippet(InstanceOfSnippets.class, "instanceofSecondary");
        private final SnippetInfo instanceofDynamic = snippet(InstanceOfSnippets.class, "instanceofDynamic");

        public Templates(CodeCacheProvider runtime, Replacements replacements, TargetDescription target) {
            super(runtime, replacements, target);
        }

        @Override
        protected Arguments makeArguments(InstanceOfUsageReplacer replacer, LoweringTool tool) {
            if (replacer.instanceOf instanceof InstanceOfNode) {
                InstanceOfNode instanceOf = (InstanceOfNode) replacer.instanceOf;
                ValueNode object = instanceOf.object();
                TypeCheckHints hintInfo = new TypeCheckHints(instanceOf.type(), instanceOf.profile(), tool.assumptions(), InstanceOfMinHintHitProbability.getValue(), InstanceOfMaxHints.getValue());
                final HotSpotResolvedObjectType type = (HotSpotResolvedObjectType) instanceOf.type();
                ConstantNode hub = ConstantNode.forConstant(type.klass(), runtime, instanceOf.graph());

                Arguments args;

                if (hintInfo.hintHitProbability >= hintHitProbabilityThresholdForDeoptimizingSnippet()) {
                    Hints hints = createHints(hintInfo, runtime, false, hub.graph());
                    args = new Arguments(instanceofWithProfile);
                    args.add("object", object);
                    args.addVarargs("hints", Word.class, StampFactory.forKind(wordKind()), hints.hubs);
                    args.addVarargs("hintIsPositive", boolean.class, StampFactory.forKind(Kind.Boolean), hints.isPositive);
                } else if (hintInfo.exact != null) {
                    args = new Arguments(instanceofExact);
                    args.add("object", object);
                    args.add("exactHub", ConstantNode.forConstant(((HotSpotResolvedObjectType) hintInfo.exact).klass(), runtime, hub.graph()));
                } else if (type.isPrimaryType()) {
                    args = new Arguments(instanceofPrimary);
                    args.add("hub", hub);
                    args.add("object", object);
                    args.addConst("superCheckOffset", type.superCheckOffset());
                } else {
                    Hints hints = createHints(hintInfo, runtime, false, hub.graph());
                    args = new Arguments(instanceofSecondary);
                    args.add("hub", hub);
                    args.add("object", object);
                    args.addVarargs("hints", Word.class, StampFactory.forKind(getWordKind()), hints.hubs);
                    args.addVarargs("hintIsPositive", boolean.class, StampFactory.forKind(Kind.Boolean), hints.isPositive);
                }
                args.add("trueValue", replacer.trueValue);
                args.add("falseValue", replacer.falseValue);
                args.addConst("checkNull", !object.stamp().nonNull());
                if (hintInfo.hintHitProbability >= hintHitProbabilityThresholdForDeoptimizingSnippet()) {
                    args.addConst("nullSeen", hintInfo.profile.getNullSeen() != TriState.FALSE);
                }
                return args;

            } else {
                assert replacer.instanceOf instanceof InstanceOfDynamicNode;
                InstanceOfDynamicNode instanceOf = (InstanceOfDynamicNode) replacer.instanceOf;
                ValueNode object = instanceOf.object();

                Arguments args = new Arguments(instanceofDynamic);
                args.add("mirror", instanceOf.mirror());
                args.add("object", object);
                args.add("trueValue", replacer.trueValue);
                args.add("falseValue", replacer.falseValue);
                args.addConst("checkNull", !object.stamp().nonNull());
                return args;
            }
        }
    }
}
