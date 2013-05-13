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
package com.oracle.graal.phases.verify;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.phases.*;

public class VerifyValueUsage extends VerifyPhase {

    private MetaAccessProvider runtime;

    public VerifyValueUsage(MetaAccessProvider runtime) {
        this.runtime = runtime;
    }

    private boolean checkType(ValueNode node) {
        if (node.stamp() instanceof ObjectStamp) {
            ResolvedJavaType valueType = runtime.lookupJavaType(Value.class);
            ResolvedJavaType nodeType = node.objectStamp().type();

            if (valueType.isAssignableFrom(nodeType)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean verify(StructuredGraph graph) {
        for (ObjectEqualsNode cn : graph.getNodes().filter(ObjectEqualsNode.class)) {
            if (!graph.method().toString().endsWith("equals(Object)>")) {
                assert !((checkType(cn.x()) && !(cn.y() instanceof ConstantNode)) || (checkType(cn.y()) && !(cn.x() instanceof ConstantNode))) : "VerifyValueUsage: " + cn.x() + " or " + cn.y() +
                                " in " + graph.method() + " uses object identity. Should use equals() instead.";
            }
        }
        return true;
    }
}
