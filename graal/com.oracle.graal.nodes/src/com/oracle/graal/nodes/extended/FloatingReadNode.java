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

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.PhiNode.PhiType;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

/**
 * A floating read of a value from memory specified in terms of an object base and an object
 * relative location. This node does not null check the object.
 */
public final class FloatingReadNode extends FloatingAccessNode implements IterableNodeType, LIRLowerable, Canonicalizable {

    @Input private Node lastLocationAccess;

    public FloatingReadNode(ValueNode object, LocationNode location, Node lastLocationAccess, Stamp stamp) {
        this(object, location, lastLocationAccess, stamp, null, BarrierType.NONE, false);
    }

    public FloatingReadNode(ValueNode object, LocationNode location, Node lastLocationAccess, Stamp stamp, GuardingNode guard) {
        this(object, location, lastLocationAccess, stamp, guard, BarrierType.NONE, false);
    }

    public FloatingReadNode(ValueNode object, LocationNode location, Node lastLocationAccess, Stamp stamp, GuardingNode guard, BarrierType barrierType, boolean compressible) {
        super(object, location, stamp, guard, barrierType, compressible);
        this.lastLocationAccess = lastLocationAccess;
    }

    public Node getLastLocationAccess() {
        return lastLocationAccess;
    }

    public void setLastLocationAccess(Node newlla) {
        updateUsages(lastLocationAccess, newlla);
        lastLocationAccess = newlla;
    }

    @Override
    public void generate(LIRGeneratorTool gen) {
        Value address = location().generateAddress(gen, gen.operand(object()));
        gen.setResult(this, gen.emitLoad(location().getValueKind(), address, this));
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        return ReadNode.canonicalizeRead(this, location(), object(), tool, isCompressible());
    }

    @Override
    public Access asFixedNode() {
        return graph().add(new ReadNode(object(), nullCheckLocation(), stamp(), getGuard(), getBarrierType(), isCompressible()));
    }

    private static boolean isMemoryCheckPoint(Node n) {
        return n instanceof MemoryCheckpoint.Single || n instanceof MemoryCheckpoint.Multi;
    }

    private static boolean isMemoryPhi(Node n) {
        return n instanceof PhiNode && ((PhiNode) n).type() == PhiType.Memory;
    }

    private static boolean isMemoryProxy(Node n) {
        return n instanceof ProxyNode && ((ProxyNode) n).type() == PhiType.Memory;
    }

    @Override
    public boolean verify() {
        Node lla = getLastLocationAccess();
        assert lla == null || isMemoryCheckPoint(lla) || isMemoryPhi(lla) || isMemoryProxy(lla) : "lastLocationAccess of " + this + " should be a MemoryCheckpoint, but is " + lla;
        return super.verify();
    }
}
