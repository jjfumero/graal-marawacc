/*
 * Copyright (c) 2013, 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.phases.graph;

import java.util.*;

import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.phases.schedule.*;

/**
 * Iterates over a list of nodes, which usually comes from
 * {@link SchedulePhase#getBlockToNodesMap()}.
 * 
 * While iterating, it is possible to {@link #insert(FixedNode, FixedWithNextNode) insert} and
 * {@link #replaceCurrent(FixedWithNextNode) replace} nodes.
 */
public abstract class ScheduledNodeIterator {

    private FixedWithNextNode lastFixed;
    private FixedWithNextNode reconnect;
    private ListIterator<ScheduledNode> iterator;

    public void processNodes(List<ScheduledNode> nodes, FixedWithNextNode begin) {
        assert begin != null;
        lastFixed = begin;
        reconnect = null;
        iterator = nodes.listIterator();
        while (iterator.hasNext()) {
            Node node = iterator.next();
            if (!node.isAlive()) {
                continue;
            }
            if (reconnect != null && node instanceof FixedNode) {
                reconnect.setNext((FixedNode) node);
                reconnect = null;
            }
            if (node instanceof FixedWithNextNode) {
                lastFixed = (FixedWithNextNode) node;
            }
            processNode(node);
        }
    }

    protected void insert(FixedNode start, FixedWithNextNode end) {
        this.lastFixed.setNext(start);
        this.lastFixed = end;
        this.reconnect = end;
    }

    protected void replaceCurrent(FixedWithNextNode newNode) {
        Node current = iterator.previous();
        iterator.next(); // needed because of the previous() call
        current.replaceAndDelete(newNode);
        insert(newNode, newNode);
        iterator.set(newNode);
    }

    protected abstract void processNode(Node node);
}
