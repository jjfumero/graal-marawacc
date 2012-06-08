/*
 * Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.java;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

/**
 * The {@code LoadFieldNode} represents a read of a static or instance field.
 */
public final class LoadFieldNode extends AccessFieldNode implements Canonicalizable, Node.IterableNodeType {

    /**
     * Creates a new LoadFieldNode instance.
     *
     * @param object the receiver object
     * @param field the compiler interface field
     */
    public LoadFieldNode(ValueNode object, RiResolvedField field, long leafGraphId) {
        super(createStamp(field), object, field, leafGraphId);
    }

    private static Stamp createStamp(RiResolvedField field) {
        Kind kind = field.kind();
        if (kind == Kind.Object && field.type() instanceof RiResolvedType) {
            return StampFactory.declared((RiResolvedType) field.type());
        } else {
            return StampFactory.forKind(kind);
        }
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        RiRuntime runtime = tool.runtime();
        if (runtime != null) {
            Constant constant = null;
            if (isStatic()) {
                constant = field().constantValue(null);
            } else if (object().isConstant() && !object().isNullConstant()) {
                constant = field().constantValue(object().asConstant());
            }
            if (constant != null) {
                return ConstantNode.forCiConstant(constant, runtime, graph());
            }
        }
        return this;
    }
}
