/*
 * Copyright (c) 1998, 2007, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package com.sun.hotspot.igv.graal;

import com.sun.hotspot.igv.data.InputBlock;
import com.sun.hotspot.igv.data.InputEdge;
import com.sun.hotspot.igv.data.InputGraph;
import com.sun.hotspot.igv.data.InputNode;
import com.sun.hotspot.igv.data.Properties;
import com.sun.hotspot.igv.data.Properties.PropertyMatcher;
import com.sun.hotspot.igv.graph.Diagram;
import com.sun.hotspot.igv.graph.Figure;
import com.sun.hotspot.igv.graphtotext.services.GraphToTextConverter;
import com.sun.hotspot.igv.structuredtext.Element;
import com.sun.hotspot.igv.structuredtext.MultiElement;
import com.sun.hotspot.igv.structuredtext.SimpleElement;
import com.sun.hotspot.igv.structuredtext.StructuredText;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;

/**
 * @author Peter Hofer
 * @author Thomas Wuerthinger
 */
public class GraalGraphToTextConverter implements GraphToTextConverter {

    private Map<InputNode, Set<Figure>> map;
    private Map<InputNode, List<InputEdge>> incomingEdges;
    private Map<InputNode, List<InputEdge>> outgoingEdges;
    private InputGraph graph;

    private Collection<InputNode> sortNodes(Collection<InputNode> nodes) {
        List<InputNode> result = new ArrayList<InputNode>(nodes);
        Collections.sort(result, InputNode.getPropertyComparator("idx"));
        return result;
    }

    public StructuredText convert(InputGraph graph, Diagram diagram) {

        this.graph = graph;
        map = diagram.calcSourceToFigureRelation();

        incomingEdges = graph.findAllIngoingEdges();
        outgoingEdges = graph.findAllOutgoingEdges();

        final StructuredText result = new StructuredText(graph.getName());

        for (InputBlock b : graph.getBlocks()) {
            result.addChild(new SimpleElement("Block " + b.getName() + "\n"));
            for (InputNode n : sortNodes(b.getNodes())) {
                result.addChild(getNodeElement(n));
            }
        }

        boolean first = true;
        for (InputNode n : sortNodes(graph.getNodes())) {
            if (graph.getBlock(n) == null) {
                if (first) {
                    first = false;
                    result.addChild(new SimpleElement("No block: \n"));
                }
                result.addChild(getNodeElement(n));
            }
        }

        return result;
    }

    private Element getNodeNameElement(InputNode n) {

        final SimpleElement name = new SimpleElement(n.getProperties().get("idx") + " " + n.getProperties().get("name"), calcStyle(n));
        name.addSource(n.getId());
        return name;
    }

    private Element getNodeShortElement(InputNode n) {
        final SimpleElement id = new SimpleElement(n.getProperties().get("idx"), calcStyle(n));
        id.addSource(n.getId());
        return id;
    }

    private Element getNodeElement(InputNode n) {

        final MultiElement result = new MultiElement();

        result.print("\t");
        result.addChild(getNodeNameElement(n));

        result.print(" :::");

        // NOTE: lists in ingoingEdges/outgoingEdges are sorted by from/to slot
        //       and for slots that are connected with multiple edges, by
        //       from/to node

        InputEdge[] outgoing = outgoingEdges.get(n).toArray(new InputEdge[0]);
        int succCount = outgoing.length - Integer.parseInt(n.getProperties().get("usageCount"));

        int i = 0;
        if (outgoing.length > 0 && outgoing[0].getFromIndex() < succCount) {
            // Node has successors (each connected to a different slot)
            result.print("  Succ = [");
            while (i < outgoing.length && outgoing[i].getFromIndex() < succCount) {
                result.print(" ");
                result.addChild(getNodeShortElement(graph.getNode(outgoing[i].getTo())));
                result.print(" ");
                i++;
            }
            result.print("]");
        }
        if (i < outgoing.length) {
            // Node has usages (all connected to a single slot)
            result.print("  Usages = [");
            while (i < outgoing.length) {
                result.print(" ");
                result.addChild(getNodeShortElement(graph.getNode(outgoing[i].getTo())));
                result.print(" ");
                i++;
            }
            result.print("]");
        }

        int predCount = Integer.parseInt(n.getProperties().get("predecessorCount"));
        InputEdge[] incoming = incomingEdges.get(n).toArray(new InputEdge[0]);

        int j = 0;
        if (incoming.length > 0 && incoming[0].getToIndex() < predCount) {
            // Node has predecessors (each connected to a different slot)
            result.print("  Pred = [");
            while (j < incoming.length && incoming[j].getToIndex() < predCount) {
                result.print(" ");
                result.addChild(getNodeShortElement(graph.getNode(incoming[j].getFrom())));
                result.print(" ");
                j++;
            }
            result.print("]");
        }
        if (j < incoming.length) {
            // Node has inputs (each connected to a different slot)
            result.print("  Inputs = [");
            while (j < incoming.length) {
                result.print(" ");
                result.addChild(getNodeShortElement(graph.getNode(incoming[j].getFrom())));
                result.print(" ");
                j++;
            }
            result.print("]");
        }

        result.print("\n");
        return result;
    }
    private static final PropertyMatcher MATCHER = new Properties.StringPropertyMatcher("origin", "Graal");

    public boolean canConvert(InputGraph graph) {
        return graph.getGroup().getProperties().selectSingle(MATCHER) != null;
    }

    private Color calcColor(InputNode node) {
        Set<Figure> figureSet = this.map.get(node);
        if (figureSet != null && figureSet.size() == 1) {
            return figureSet.iterator().next().getColor();
        } else {
            return Color.WHITE;
        }
    }

    private Color lessColor(Color c) {
        return new Color(255 - (255 - c.getRed()) / 4, 255 - (255 - c.getGreen()) / 4, 255 - (255 - c.getBlue()) / 4);
    }

    private Style calcStyle(InputNode node) {
        Color c = calcColor(node);
        Style defaultStyle = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
        Style newStyle = StyleContext.getDefaultStyleContext().addStyle(null, defaultStyle);

        StyleConstants.setBackground(newStyle, lessColor(c));
        return newStyle;
    }
}
