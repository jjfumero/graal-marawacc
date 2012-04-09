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

import static com.oracle.graal.graph.iterators.NodePredicates.*;

import java.util.*;

import com.oracle.graal.graph.*;
import com.oracle.graal.graph.iterators.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

public class BeginNode extends AbstractStateSplit implements LIRLowerable, Simplifiable, Node.IterableNodeType {
    public BeginNode() {
        super(StampFactory.illegal());
    }

    public static BeginNode begin(FixedNode with) {
        if (with instanceof BeginNode) {
            return (BeginNode) with;
        }
        BeginNode begin =  with.graph().add(new BeginNode());
        begin.setNext(with);
        return begin;
    }

    @Override
    public Map<Object, Object> getDebugProperties() {
        Map<Object, Object> debugProperties = super.getDebugProperties();
        debugProperties.put("shortName", "B");
        return debugProperties;
    }

    @Override
    public void simplify(SimplifierTool tool) {
        FixedNode prev = (FixedNode) this.predecessor();
        if (prev == null) {
            // This is the start node.
        } else if (prev instanceof ControlSplitNode) {
            // This begin node is necessary.
        } else {
            // This begin node can be removed and all guards moved up to the preceding begin node.
            if (!usages().isEmpty()) {
                Node prevBegin = prev;
                while (!(prevBegin instanceof BeginNode)) {
                    prevBegin = prevBegin.predecessor();
                }
                for (Node usage : usages()) {
                    tool.addToWorkList(usage);
                }
                replaceAtUsages(prevBegin);
            }
            ((StructuredGraph) graph()).removeFixed(this);
        }
    }

    public void evacuateGuards(FixedNode evacuateFrom) {
        if (!usages().isEmpty()) {
            Node prevBegin = evacuateFrom;
            assert prevBegin != null;
            while (!(prevBegin instanceof BeginNode)) {
                prevBegin = prevBegin.predecessor();
            }
            for (Node anchored : anchored().snapshot()) {
                anchored.replaceFirstInput(this, prevBegin);
            }
        }
    }

    public void prepareDelete() {
        prepareDelete((FixedNode) predecessor());
    }

    public void prepareDelete(FixedNode evacuateFrom) {
        removeProxies();
        evacuateGuards(evacuateFrom);
    }

    public void removeProxies() {
        StructuredGraph graph = (StructuredGraph) graph();
        for (ValueProxyNode vpn : proxies().snapshot()) {
            graph.replaceFloating(vpn, vpn.value());
        }
    }

    @Override
    public boolean verify() {
        assertTrue(predecessor() != null || this == ((StructuredGraph) graph()).start() || this instanceof MergeNode, "begin nodes must be connected");
        return super.verify();
    }

    @Override
    public void generate(LIRGeneratorTool gen) {
        // nop
    }

    public NodeIterable<GuardNode> guards() {
        return usages().filter(GuardNode.class);
    }

    public NodeIterable<Node> anchored() {
        return usages().filter(isNotA(ValueProxyNode.class));
    }

    public NodeIterable<ValueProxyNode> proxies() {
        return usages().filter(ValueProxyNode.class);
    }

    public NodeIterable<FixedNode> getBlockNodes() {
        return new NodeIterable<FixedNode>() {
            @Override
            public Iterator<FixedNode> iterator() {
                return new BlockNodeIterator(BeginNode.this);
            }
        };
    }

    private class BlockNodeIterator implements Iterator<FixedNode> {
        private FixedNode current;

        public BlockNodeIterator(FixedNode next) {
            this.current = next;
        }

        @Override
        public boolean hasNext() {
            return current != null;
        }

        @Override
        public FixedNode next() {
            FixedNode ret = current;
            if (ret == null) {
                throw new NoSuchElementException();
            }
            if (!(current instanceof FixedWithNextNode) || (current instanceof BeginNode && current != BeginNode.this)) {
                current = null;
            } else {
                current = ((FixedWithNextNode) current).next();
            }
            return ret;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}
