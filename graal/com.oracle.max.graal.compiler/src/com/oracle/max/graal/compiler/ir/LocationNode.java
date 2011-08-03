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
package com.oracle.max.graal.compiler.ir;

import com.oracle.max.graal.compiler.debug.*;
import com.oracle.max.graal.compiler.gen.*;
import com.oracle.max.graal.graph.*;
import com.sun.cri.ci.*;
import com.sun.cri.ci.CiAddress.*;


public final class LocationNode extends FloatingNode {

    @NodeInput
    private Value index;

    public Value index() {
        return index;
    }

    public void setIndex(Value x) {
        updateUsages(index, x);
        index = x;
    }

    public static final Object FINAL_LOCATION = new Object();

    public static Object getArrayLocation(CiKind elementKind) {
        return elementKind;
    }

    private int displacement;
    private CiKind valueKind;
    private Object locationIdentity;

    public int displacement() {
        return displacement;
    }

    public static LocationNode create(Object identity, CiKind kind, int displacement, Graph graph) {
        LocationNode result = new LocationNode(identity, kind, displacement, graph);
        return graph.ideal(result);
    }

    private LocationNode(Object identity, CiKind kind, int displacement, Graph graph) {
        super(CiKind.Illegal, graph);
        this.displacement = displacement;
        this.valueKind = kind;
        this.locationIdentity = identity;
    }

    @Override
    public <T extends Op> T lookup(Class<T> clazz) {
        if (clazz == LIRGenerator.LIRGeneratorOp.class) {
            return null;
        }
        return super.lookup(clazz);
    }

    @Override
    public void print(LogStream out) {
        out.print("mem location disp is ").print(displacement);
    }

    public CiKind getValueKind() {
        return valueKind;
    }

    @Override
    public Node copy(Graph into) {
        return new LocationNode(locationIdentity, valueKind, displacement, into);
    }

    public CiValue createAddress(LIRGenerator lirGenerator, Value object) {
        CiValue indexValue = CiValue.IllegalValue;
        Scale indexScale = Scale.Times1;
        if (this.index() != null) {
            indexValue = lirGenerator.load(this.index());
            indexScale = Scale.fromInt(valueKind.sizeInBytes(lirGenerator.target().wordSize));
        }
        return new CiAddress(valueKind, lirGenerator.load(object), indexValue, indexScale, displacement);
    }

    public Object locationIdentity() {
        return locationIdentity;
    }

    @Override
    public boolean valueEqual(Node i) {
        if (i instanceof LocationNode) {
            LocationNode locationNode = (LocationNode) i;
            return locationNode.locationIdentity == locationIdentity && locationNode.displacement == displacement;
        }
        return false;
    }

    @Override
    public int valueNumber() {
        return locationIdentity.hashCode() + displacement;
    }
}
