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
 *
 */
package com.sun.hotspot.igv.coordinator.actions;

import com.sun.hotspot.igv.data.GraphDocument;
import com.sun.hotspot.igv.data.InputGraph;
import org.openide.DialogDescriptor;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;

public class GraphRemoveCookie implements RemoveCookie {
    private final GraphDocument document;
    private final InputGraph graph;

    public GraphRemoveCookie(GraphDocument document, InputGraph graph) {
        this.document = document;
        this.graph = graph;
    }

    public void remove() {
        if (!graph.getGroup().isComplete()) {
            String msg = "This graph or the group it belongs to is still being loaded. Removing this graph now can cause problems. Do you want to continue and remove the graph?";
            NotifyDescriptor desc = new NotifyDescriptor(msg, "Incomplete data", NotifyDescriptor.YES_NO_OPTION, NotifyDescriptor.QUESTION_MESSAGE, null, NotifyDescriptor.NO_OPTION);

            if (DialogDisplayer.getDefault().notify(desc) == DialogDescriptor.NO_OPTION) {
                return;
            }
        }

        if (graph.getGroup().getGraphsCount() > 1) {
            graph.getGroup().removeGraph(graph);
        } else {
            // Last graph, remove the entire group
            document.removeGroup(graph.getGroup());
        }
    }
}
