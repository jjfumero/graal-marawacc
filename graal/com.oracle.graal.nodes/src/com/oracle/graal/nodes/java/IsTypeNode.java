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

import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.max.cri.ci.*;
import com.oracle.max.cri.ri.*;

public final class IsTypeNode extends BooleanNode implements Canonicalizable, LIRLowerable {

    @Input private ValueNode objectClass;
    private final RiResolvedType type;

    public ValueNode objectClass() {
        return objectClass;
    }

    /**
     * Constructs a new IsTypeNode.
     *
     * @param object the instruction producing the object to check against the given type
     * @param type the type for this check
     */
    public IsTypeNode(ValueNode objectClass, RiResolvedType type) {
        super(StampFactory.illegal());
        assert objectClass == null || objectClass.kind() == CiKind.Object;
        this.type = type;
        this.objectClass = objectClass;
    }

    public RiResolvedType type() {
        return type;
    }

    @Override
    public void generate(LIRGeneratorTool gen) {
        // nothing to do
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        RiResolvedType exactType = objectClass() instanceof ReadHubNode ? ((ReadHubNode) objectClass()).object().exactType() : null;
        if (exactType != null) {
            return ConstantNode.forBoolean(exactType == type(), graph());
        }
        // constants return the correct exactType, so they are handled by the code above
        return this;
    }

    @Override
    public BooleanNode negate() {
        throw new Error("unimplemented");
    }
}
