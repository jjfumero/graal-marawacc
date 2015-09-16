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

import java.util.Iterator;
import java.util.NoSuchElementException;

import com.oracle.graal.compiler.common.type.Stamp;
import com.oracle.graal.compiler.common.type.StampFactory;
import com.oracle.graal.graph.IterableNodeType;
import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.graph.iterators.NodeIterable;
import com.oracle.graal.graph.spi.Simplifiable;
import com.oracle.graal.graph.spi.SimplifierTool;
import com.oracle.graal.nodeinfo.InputType;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.extended.AnchoringNode;
import com.oracle.graal.nodes.extended.GuardingNode;
import com.oracle.graal.nodes.spi.LIRLowerable;
import com.oracle.graal.nodes.spi.NodeLIRBuilderTool;

@NodeInfo(allowedUsageTypes = {InputType.Guard, InputType.Anchor})
public abstract class AbstractBeginNode extends FixedWithNextNode implements LIRLowerable, Simplifiable, GuardingNode, AnchoringNode, IterableNodeType {

    public static final NodeClass<AbstractBeginNode> TYPE = NodeClass.create(AbstractBeginNode.class);

    protected AbstractBeginNode(NodeClass<? extends AbstractBeginNode> c) {
        this(c, StampFactory.forVoid());
    }

    protected AbstractBeginNode(NodeClass<? extends AbstractBeginNode> c, Stamp stamp) {
        super(c, stamp);
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
            prepareDelete();
            tool.addToWorkList(next());
            graph().removeFixed(this);
        }
    }

    public static AbstractBeginNode prevBegin(FixedNode from) {
        Node next = from;
        while (next != null) {
            if (next instanceof AbstractBeginNode) {
                AbstractBeginNode begin = (AbstractBeginNode) next;
                return begin;
            }
            next = next.predecessor();
        }
        return null;
    }

    private void evacuateGuards(FixedNode evacuateFrom) {
        if (!hasNoUsages()) {
            AbstractBeginNode prevBegin = prevBegin(evacuateFrom);
            assert prevBegin != null;
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
        if (this.hasUsages()) {
            outer: while (true) {
                for (ProxyNode vpn : proxies().snapshot()) {
                    ValueNode value = vpn.value();
                    vpn.replaceAtUsages(value);
                    vpn.safeDelete();
                    if (value == this) {
                        // Guard proxy could have this input as value.
                        continue outer;
                    }
                }
                break;
            }
        }
    }

    @Override
    public boolean verify() {
        assertTrue(predecessor() != null || this == graph().start() || this instanceof AbstractMergeNode, "begin nodes must be connected");
        return super.verify();
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        // nop
    }

    public NodeIterable<GuardNode> guards() {
        return usages().filter(GuardNode.class);
    }

    public NodeIterable<Node> anchored() {
        return usages().filter(n -> {
            if (n instanceof ProxyNode) {
                ProxyNode proxyNode = (ProxyNode) n;
                return proxyNode.proxyPoint() != this;
            }
            return true;
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public NodeIterable<ProxyNode> proxies() {
        return (NodeIterable) usages().filter(n -> {
            if (n instanceof ProxyNode) {
                ProxyNode proxyNode = (ProxyNode) n;
                return proxyNode.proxyPoint() == this;
            }
            return false;
        });
    }

    public NodeIterable<FixedNode> getBlockNodes() {
        return new NodeIterable<FixedNode>() {

            @Override
            public Iterator<FixedNode> iterator() {
                return new BlockNodeIterator(AbstractBeginNode.this);
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
            if (!(current instanceof FixedWithNextNode) || (current instanceof AbstractBeginNode && current != AbstractBeginNode.this)) {
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
