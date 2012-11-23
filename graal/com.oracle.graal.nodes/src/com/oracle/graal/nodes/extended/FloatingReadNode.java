/*
 * Copyright (c) 2011, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.extended;

import java.util.*;

import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

/**
 * A floating read of a value from memory specified in terms of an object base and an object relative location.
 * This node does not null check the object.
 */
public final class FloatingReadNode extends FloatingAccessNode implements Node.IterableNodeType, LIRLowerable, Canonicalizable {

    @Input private Node lastLocationAccess;

    public FloatingReadNode(ValueNode object, LocationNode location, Node lastLocationAccess, Stamp stamp, ValueNode... dependencies) {
        super(object, location, stamp, dependencies);
        this.lastLocationAccess = lastLocationAccess;
    }

    public FloatingReadNode(ValueNode object, LocationNode location, Node lastLocationAccess, Stamp stamp, List<ValueNode> dependencies) {
        super(object, location, stamp, dependencies);
        this.lastLocationAccess = lastLocationAccess;
    }

    public Node lastLocationAccess() {
        return lastLocationAccess;
    }

    @Override
    public void generate(LIRGeneratorTool gen) {
        gen.setResult(this, gen.emitLoad(gen.makeAddress(location(), object()), getNullCheck()));
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        return ReadNode.canonicalizeRead(this, tool);
    }
}
