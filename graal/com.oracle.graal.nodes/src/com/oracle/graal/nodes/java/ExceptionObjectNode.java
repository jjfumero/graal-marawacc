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

import com.oracle.graal.api.meta.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.extended.LocationNode.LocationIdentity;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

/**
 * The entry to an exception handler with the exception coming from a call (as opposed to a local
 * throw instruction or implicit exception).
 */
public class ExceptionObjectNode extends DispatchBeginNode implements Lowerable, MemoryCheckpoint {

    public ExceptionObjectNode(MetaAccessProvider runtime) {
        super(StampFactory.declaredNonNull(runtime.lookupJavaType(Throwable.class)));
    }

    @Override
    public LocationIdentity[] getLocationIdentities() {
        return new LocationIdentity[]{LocationNode.ANY_LOCATION};
    }

    @Override
    public void simplify(SimplifierTool tool) {
        //
    }

    private boolean isLowered() {
        return (stamp() == StampFactory.forVoid());
    }

    @Override
    public void lower(LoweringTool tool, LoweringType loweringType) {
        if (isLowered()) {
            return;
        }
        StructuredGraph graph = (StructuredGraph) graph();
        LoadExceptionObjectNode loadException = graph.add(new LoadExceptionObjectNode(stamp()));
        loadException.setStateAfter(stateAfter());
        replaceAtUsages(loadException);
        graph.addAfterFixed(this, loadException);
        tool.setLastFixedNode(loadException);
        setStateAfter(null);
        setStamp(StampFactory.forVoid());
    }

    @Override
    public boolean verify() {
        if (isLowered()) {
            return true;
        }
        assertTrue(stateAfter() != null || stamp() == StampFactory.forVoid(), "an exception handler needs a frame state");
        return super.verify();
    }
}
