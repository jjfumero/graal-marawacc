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
package com.oracle.graal.graph.test;

import static org.junit.Assert.*;

import org.junit.*;

import com.oracle.graal.graph.*;
import com.oracle.graal.nodeinfo.*;

public class TypedNodeIteratorTest2 {

    @NodeInfo
    static class NodeA extends Node implements TestNodeInterface {

        protected final String name;

        public static NodeA create(String name) {
            return new NodeA(name);
        }

        protected NodeA(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    @NodeInfo
    static class NodeB extends NodeA implements IterableNodeType {

        public static NodeB create(String name) {
            return new NodeB(name);
        }

        protected NodeB(String name) {
            super(name);
        }
    }

    @NodeInfo
    static class NodeC extends NodeB {
        public static NodeC create(String name) {
            return new NodeC(name);
        }

        protected NodeC(String name) {
            super(name);
        }
    }

    @NodeInfo
    static class NodeD extends NodeC {
        public static NodeD create(String name) {
            return new NodeD(name);
        }

        protected NodeD(String name) {
            super(name);
        }
    }

    @Test
    public void simpleSubclassTest() {
        Graph graph = new Graph();
        graph.add(NodeB.create("b"));
        graph.add(NodeD.create("d"));

        Assert.assertEquals("bd", TypedNodeIteratorTest.toString(graph.getNodes(NodeB.class)));
        Assert.assertEquals("d", TypedNodeIteratorTest.toString(graph.getNodes(NodeD.class)));
    }

    @Test
    public void addingNodeDuringIterationTest() {
        Graph graph = new Graph();
        graph.add(NodeB.create("b1"));
        NodeD d1 = graph.add(NodeD.create("d1"));
        StringBuilder sb = new StringBuilder();
        for (NodeB tn : graph.getNodes(NodeB.class)) {
            if (tn == d1) {
                graph.add(NodeB.create("b2"));
            }
            sb.append(tn.getName());
        }
        assertEquals("b1d1b2", sb.toString());
        for (NodeB tn : graph.getNodes(NodeB.class)) {
            if (tn == d1) {
                graph.add(NodeB.create("b3"));
            }
            assertNotNull(tn);
        }
        assertEquals(4, graph.getNodes(NodeB.class).count());
        assertEquals(1, graph.getNodes(NodeD.class).count());
    }

}
