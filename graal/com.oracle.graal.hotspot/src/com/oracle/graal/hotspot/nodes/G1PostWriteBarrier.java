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
package com.oracle.graal.hotspot.nodes;

import com.oracle.graal.graph.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;

@NodeInfo
public class G1PostWriteBarrier extends WriteBarrier {

    public static final NodeClass<G1PostWriteBarrier> TYPE = NodeClass.create(G1PostWriteBarrier.class);
    protected final boolean alwaysNull;

    public G1PostWriteBarrier(ValueNode object, ValueNode value, LocationNode location, boolean precise, boolean alwaysNull) {
        this(TYPE, object, value, location, precise, alwaysNull);
    }

    protected G1PostWriteBarrier(NodeClass<? extends G1PostWriteBarrier> c, ValueNode object, ValueNode value, LocationNode location, boolean precise, boolean alwaysNull) {
        super(c, object, value, location, precise);
        this.alwaysNull = alwaysNull;
    }

    public boolean alwaysNull() {
        return alwaysNull;
    }
}
