/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

public abstract class CallTargetNode extends ValueNode implements LIRLowerable {

    @Input protected final NodeInputList<ValueNode> arguments;

    @Input protected ValueNode address;

    public ValueNode address() {
        return address;
    }

    public void setAddress(ValueNode address) {
        updateUsages(this.address, address);
        this.address = address;
    }

    public CallTargetNode(ValueNode[] arguments) {
        super(StampFactory.extension());
        this.arguments = new NodeInputList<>(this, arguments);
    }

    public NodeInputList<ValueNode> arguments() {
        return arguments;
    }

    public abstract JavaType returnType();

    public abstract Kind returnKind();

    @Override
    public void generate(LIRGeneratorTool gen) {
        // nop
    }
}
