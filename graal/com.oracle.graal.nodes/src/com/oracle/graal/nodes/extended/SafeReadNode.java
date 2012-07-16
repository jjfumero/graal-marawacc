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
package com.oracle.graal.nodes.extended;

import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

/**
 * An analog to {@link ReadNode} with the additional semantics of null-checking
 * the receiver object before reading from it.
 */
public class SafeReadNode extends SafeAccessNode implements Lowerable {

    public SafeReadNode(ValueNode object, LocationNode location, Stamp stamp, long leafGraphId) {
        super(object, location, stamp, leafGraphId);
        assert object != null && location != null;
    }

    @Override
    public void lower(LoweringTool tool) {
        StructuredGraph graph = (StructuredGraph) graph();
        ValueNode guard = tool.createNullCheckGuard(object(), leafGraphId());
        ReadNode read = graph.add(new ReadNode(object(), location(), stamp()));
        read.dependencies().add(guard);

        graph.replaceFixedWithFixed(this, read);
    }
}
