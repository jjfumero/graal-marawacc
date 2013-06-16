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
package com.oracle.graal.replacements;

import static com.oracle.graal.phases.GraalOptions.*;
import static com.oracle.graal.replacements.SnippetTemplate.*;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.Node.NodeIntrinsic;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.replacements.Snippet.Fold;
import com.oracle.graal.replacements.Snippet.SnippetInliningPolicy;
import com.oracle.graal.replacements.SnippetTemplate.AbstractTemplates;
import com.oracle.graal.replacements.SnippetTemplate.Arguments;
import com.oracle.graal.replacements.SnippetTemplate.SnippetInfo;
import com.oracle.graal.word.*;

public class BoxingSnippets implements Snippets {

    /**
     * This snippet inlining policy differs from the default one in that it does normal inlining of
     * boxing methods like {@link Integer#valueOf(int)} (as opposed to method substitution).
     */
    public static class BoxingSnippetInliningPolicy implements SnippetInliningPolicy {

        @Override
        public boolean shouldInline(ResolvedJavaMethod method, ResolvedJavaMethod caller) {
            if (Modifier.isNative(method.getModifiers())) {
                return false;
            }
            if (method.getAnnotation(Fold.class) != null) {
                return false;
            }
            if (method.getAnnotation(NodeIntrinsic.class) != null) {
                return false;
            }
            if (method.getAnnotation(Word.Operation.class) != null) {
                return false;
            }
            return true;
        }

        @Override
        public boolean shouldUseReplacement(ResolvedJavaMethod callee, ResolvedJavaMethod methodToParse) {
            return false;
        }
    }

    @Snippet(inlining = BoxingSnippetInliningPolicy.class)
    public static Boolean booleanValueOf(boolean value) {
        valueOfCounter.inc();
        return UnsafeCastNode.unsafeCast(Boolean.valueOf(value), StampFactory.forNodeIntrinsic());
    }

    @Snippet(inlining = BoxingSnippetInliningPolicy.class)
    public static Byte byteValueOf(byte value) {
        valueOfCounter.inc();
        return UnsafeCastNode.unsafeCast(Byte.valueOf(value), StampFactory.forNodeIntrinsic());
    }

    @Snippet(inlining = BoxingSnippetInliningPolicy.class)
    public static Character charValueOf(char value) {
        valueOfCounter.inc();
        return UnsafeCastNode.unsafeCast(Character.valueOf(value), StampFactory.forNodeIntrinsic());
    }

    @Snippet(inlining = BoxingSnippetInliningPolicy.class)
    public static Double doubleValueOf(double value) {
        valueOfCounter.inc();
        return UnsafeCastNode.unsafeCast(Double.valueOf(value), StampFactory.forNodeIntrinsic());
    }

    @Snippet(inlining = BoxingSnippetInliningPolicy.class)
    public static Float floatValueOf(float value) {
        valueOfCounter.inc();
        return UnsafeCastNode.unsafeCast(Float.valueOf(value), StampFactory.forNodeIntrinsic());
    }

    @Snippet(inlining = BoxingSnippetInliningPolicy.class)
    public static Integer intValueOf(int value) {
        valueOfCounter.inc();
        return UnsafeCastNode.unsafeCast(Integer.valueOf(value), StampFactory.forNodeIntrinsic());
    }

    @Snippet(inlining = BoxingSnippetInliningPolicy.class)
    public static Long longValueOf(long value) {
        valueOfCounter.inc();
        return UnsafeCastNode.unsafeCast(Long.valueOf(value), StampFactory.forNodeIntrinsic());
    }

    @Snippet(inlining = BoxingSnippetInliningPolicy.class)
    public static Short shortValueOf(short value) {
        valueOfCounter.inc();
        return UnsafeCastNode.unsafeCast(Short.valueOf(value), StampFactory.forNodeIntrinsic());
    }

    @Snippet(inlining = BoxingSnippetInliningPolicy.class)
    public static boolean booleanValue(Boolean value) {
        valueOfCounter.inc();
        return value.booleanValue();
    }

    @Snippet(inlining = BoxingSnippetInliningPolicy.class)
    public static byte byteValue(Byte value) {
        valueOfCounter.inc();
        return value.byteValue();
    }

    @Snippet(inlining = BoxingSnippetInliningPolicy.class)
    public static char charValue(Character value) {
        valueOfCounter.inc();
        return value.charValue();
    }

    @Snippet(inlining = BoxingSnippetInliningPolicy.class)
    public static double doubleValue(Double value) {
        valueOfCounter.inc();
        return value.doubleValue();
    }

    @Snippet(inlining = BoxingSnippetInliningPolicy.class)
    public static float floatValue(Float value) {
        valueOfCounter.inc();
        return value.floatValue();
    }

    @Snippet(inlining = BoxingSnippetInliningPolicy.class)
    public static int intValue(Integer value) {
        valueOfCounter.inc();
        return value.intValue();
    }

    @Snippet(inlining = BoxingSnippetInliningPolicy.class)
    public static long longValue(Long value) {
        valueOfCounter.inc();
        return value.longValue();
    }

    @Snippet(inlining = BoxingSnippetInliningPolicy.class)
    public static short shortValue(Short value) {
        valueOfCounter.inc();
        return value.shortValue();
    }

    public static FloatingNode canonicalizeBoxing(BoxNode box, MetaAccessProvider runtime) {
        ValueNode value = box.getValue();
        if (value.isConstant()) {
            Constant sourceConstant = value.asConstant();
            switch (box.getBoxingKind()) {
                case Boolean:
                    return ConstantNode.forObject(Boolean.valueOf(sourceConstant.asInt() != 0), runtime, box.graph());
                case Byte:
                    return ConstantNode.forObject(Byte.valueOf((byte) sourceConstant.asInt()), runtime, box.graph());
                case Char:
                    return ConstantNode.forObject(Character.valueOf((char) sourceConstant.asInt()), runtime, box.graph());
                case Short:
                    return ConstantNode.forObject(Short.valueOf((short) sourceConstant.asInt()), runtime, box.graph());
                case Int:
                    return ConstantNode.forObject(Integer.valueOf(sourceConstant.asInt()), runtime, box.graph());
                case Long:
                    return ConstantNode.forObject(Long.valueOf(sourceConstant.asLong()), runtime, box.graph());
                case Float:
                    return ConstantNode.forObject(Float.valueOf(sourceConstant.asFloat()), runtime, box.graph());
                case Double:
                    return ConstantNode.forObject(Double.valueOf(sourceConstant.asDouble()), runtime, box.graph());
                default:
                    assert false : "Unexpected source kind for boxing";
                    break;
            }
        }
        return null;
    }

    public static class Templates extends AbstractTemplates {

        private final EnumMap<Kind, SnippetInfo> boxSnippets = new EnumMap<>(Kind.class);
        private final EnumMap<Kind, SnippetInfo> unboxSnippets = new EnumMap<>(Kind.class);

        public Templates(MetaAccessProvider runtime, Replacements replacements, TargetDescription target) {
            super(runtime, replacements, target);
            for (Kind kind : new Kind[]{Kind.Boolean, Kind.Byte, Kind.Char, Kind.Double, Kind.Float, Kind.Int, Kind.Long, Kind.Short}) {
                boxSnippets.put(kind, snippet(BoxingSnippets.class, kind.getJavaName() + "ValueOf"));
                unboxSnippets.put(kind, snippet(BoxingSnippets.class, kind.getJavaName() + "Value"));
            }
        }

        public void lower(BoxNode box) {
            FloatingNode canonical = canonicalizeBoxing(box, runtime);
            if (!AOTCompilation.getValue() && canonical != null) {
                box.graph().replaceFixedWithFloating(box, canonical);
            } else {
                Arguments args = new Arguments(boxSnippets.get(box.getBoxingKind()));
                args.add("value", box.getValue());

                SnippetTemplate template = template(args);
                Debug.log("Lowering integerValueOf in %s: node=%s, template=%s, arguments=%s", box.graph(), box, template, args);
                template.instantiate(runtime, box, DEFAULT_REPLACER, args);
            }
        }

        public void lower(UnboxNode unbox) {
            Arguments args = new Arguments(unboxSnippets.get(unbox.getBoxingKind()));
            args.add("value", unbox.getValue());

            SnippetTemplate template = template(args);
            Debug.log("Lowering integerValueOf in %s: node=%s, template=%s, arguments=%s", unbox.graph(), unbox, template, args);
            template.instantiate(runtime, unbox, DEFAULT_REPLACER, args);
        }
    }

    private static final SnippetCounter.Group integerCounters = SnippetCounters.getValue() ? new SnippetCounter.Group("Integer intrinsifications") : null;
    private static final SnippetCounter valueOfCounter = new SnippetCounter(integerCounters, "valueOf", "valueOf intrinsification");

}
