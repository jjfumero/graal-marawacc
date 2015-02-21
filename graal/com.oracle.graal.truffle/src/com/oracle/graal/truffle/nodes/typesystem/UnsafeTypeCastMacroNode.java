/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.truffle.nodes.typesystem;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.calc.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.replacements.nodes.*;

/**
 * Macro node for method CompilerDirectives#unsafeCast.
 */
@NodeInfo
public final class UnsafeTypeCastMacroNode extends MacroStateSplitNode implements Simplifiable {

    public static final NodeClass<UnsafeTypeCastMacroNode> TYPE = NodeClass.create(UnsafeTypeCastMacroNode.class);
    private static final int OBJECT_ARGUMENT_INDEX = 0;
    private static final int CLASS_ARGUMENT_INDEX = 1;
    private static final int CONDITION_ARGUMENT_INDEX = 2;
    private static final int NONNULL_ARGUMENT_INDEX = 3;
    private static final int ARGUMENT_COUNT = 4;

    public UnsafeTypeCastMacroNode(Invoke invoke) {
        super(TYPE, invoke);
        assert arguments.size() == ARGUMENT_COUNT;
    }

    @Override
    public void simplify(SimplifierTool tool) {
        ValueNode classArgument = arguments.get(CLASS_ARGUMENT_INDEX);
        ValueNode nonNullArgument = arguments.get(NONNULL_ARGUMENT_INDEX);
        if (classArgument.isConstant() && nonNullArgument.isConstant()) {
            ValueNode objectArgument = arguments.get(OBJECT_ARGUMENT_INDEX);
            ValueNode conditionArgument = arguments.get(CONDITION_ARGUMENT_INDEX);
            ResolvedJavaType lookupJavaType = tool.getConstantReflection().asJavaType(classArgument.asConstant());
            tool.addToWorkList(usages());
            if (lookupJavaType == null) {
                replaceAtUsages(objectArgument);
                GraphUtil.removeFixedWithUnusedInputs(this);
            } else {
                Stamp piStamp = StampFactory.declaredTrusted(lookupJavaType, nonNullArgument.asJavaConstant().asInt() != 0);
                ConditionAnchorNode valueAnchorNode = graph().add(
                                new ConditionAnchorNode(CompareNode.createCompareNode(graph(), Condition.EQ, conditionArgument, ConstantNode.forBoolean(true, graph()), tool.getConstantReflection())));
                PiNode piCast = graph().unique(new PiNode(objectArgument, piStamp, valueAnchorNode));
                replaceAtUsages(piCast);
                graph().replaceFixedWithFixed(this, valueAnchorNode);
            }
        }
    }
}
