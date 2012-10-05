/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.compiler.phases;

import com.oracle.graal.api.code.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

public class InsertStateAfterPlaceholderPhase extends Phase {

    private static class PlaceholderNode extends AbstractStateSplit implements StateSplit, Node.IterableNodeType, LIRLowerable, Canonicalizable {
        public PlaceholderNode() {
            super(StampFactory.forVoid());
        }

        @Override
        public void generate(LIRGeneratorTool gen) {
            // nothing to do
        }

        @Override
        public boolean hasSideEffect(CodeCacheProvider runtime) {
            return false;
        }

        @Override
        public ValueNode canonical(CanonicalizerTool tool) {
            if (stateAfter() == null) {
                return null;
            }
            return this;
        }
    }

    @Override
    protected void run(StructuredGraph graph) {
        for (ReturnNode ret : graph.getNodes(ReturnNode.class)) {
            PlaceholderNode p = graph.add(new PlaceholderNode());
            p.setStateAfter(graph.add(new FrameState(FrameState.AFTER_BCI)));
            graph.addBeforeFixed(ret, p);
        }
    }

}
