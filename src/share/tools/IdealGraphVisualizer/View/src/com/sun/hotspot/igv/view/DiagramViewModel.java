/*
 * Copyright (c) 1998, 2008, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.hotspot.igv.view;

import com.sun.hotspot.igv.data.Group;
import com.sun.hotspot.igv.data.InputGraph;
import com.sun.hotspot.igv.data.InputNode;
import com.sun.hotspot.igv.difference.Difference;
import com.sun.hotspot.igv.filter.FilterChain;
import com.sun.hotspot.igv.graph.Diagram;
import com.sun.hotspot.igv.data.ChangedEvent;
import com.sun.hotspot.igv.util.RangeSliderModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.sun.hotspot.igv.data.ChangedListener;
import com.sun.hotspot.igv.filter.CustomFilter;
import com.sun.hotspot.igv.graph.Figure;
import com.sun.hotspot.igv.settings.Settings;
import java.awt.Color;
import java.util.Collection;

/**
 *
 * @author Thomas Wuerthinger
 */
public class DiagramViewModel extends RangeSliderModel implements ChangedListener<RangeSliderModel> {

    // Warning: Update setData method if fields are added
    private Group group;
    private Set<Integer> hiddenNodes;
    private Set<Integer> onScreenNodes;
    private Set<Integer> selectedNodes;
    private FilterChain filterChain;
    private FilterChain sequenceFilterChain;
    private Diagram diagram;
    private InputGraph inputGraph;
    private ChangedEvent<DiagramViewModel> groupChangedEvent;
    private ChangedEvent<DiagramViewModel> diagramChangedEvent;
    private ChangedEvent<DiagramViewModel> viewChangedEvent;
    private ChangedEvent<DiagramViewModel> hiddenNodesChangedEvent;
    private ChangedEvent<DiagramViewModel> viewPropertiesChangedEvent;
    private boolean showBlocks;
    private boolean showNodeHull;
    private ChangedListener<FilterChain> filterChainChangedListener = new ChangedListener<FilterChain>() {

        @Override
        public void changed(FilterChain source) {
            diagramChanged();
        }
    };

    @Override
    public DiagramViewModel copy() {
        DiagramViewModel result = new DiagramViewModel(group, filterChain, sequenceFilterChain);
        result.setData(this);
        return result;
    }

    public void setData(DiagramViewModel newModel) {
        super.setData(newModel);
        boolean diagramChanged = false;
        boolean viewChanged = false;
        boolean viewPropertiesChanged = false;

        boolean groupChanged = (group == newModel.group);
        this.group = newModel.group;
        diagramChanged |= (filterChain != newModel.filterChain);
        this.filterChain = newModel.filterChain;
        diagramChanged |= (sequenceFilterChain != newModel.sequenceFilterChain);
        this.sequenceFilterChain = newModel.sequenceFilterChain;
        diagramChanged |= (diagram != newModel.diagram);
        this.diagram = newModel.diagram;
        viewChanged |= (hiddenNodes != newModel.hiddenNodes);
        this.hiddenNodes = newModel.hiddenNodes;
        viewChanged |= (onScreenNodes != newModel.onScreenNodes);
        this.onScreenNodes = newModel.onScreenNodes;
        viewChanged |= (selectedNodes != newModel.selectedNodes);
        this.selectedNodes = newModel.selectedNodes;
        viewPropertiesChanged |= (showBlocks != newModel.showBlocks);
        this.showBlocks = newModel.showBlocks;
        viewPropertiesChanged |= (showNodeHull != newModel.showNodeHull);
        this.showNodeHull = newModel.showNodeHull;

        if (groupChanged) {
            groupChangedEvent.fire();
        }

        if (diagramChanged) {
            diagramChangedEvent.fire();
        }
        if (viewPropertiesChanged) {
            viewPropertiesChangedEvent.fire();
        }
        if (viewChanged) {
            viewChangedEvent.fire();
        }
    }

    public boolean getShowBlocks() {
        return showBlocks;
    }

    public void setShowBlocks(boolean b) {
        showBlocks = b;
        viewPropertiesChangedEvent.fire();
    }

    public boolean getShowNodeHull() {
        return showNodeHull;
    }

    public void setShowNodeHull(boolean b) {
        showNodeHull = b;
        viewPropertiesChangedEvent.fire();
    }

    public DiagramViewModel(Group g, FilterChain filterChain, FilterChain sequenceFilterChain) {
        super(calculateStringList(g));

        this.showNodeHull = true;
        this.showBlocks = true;
        this.group = g;
        assert filterChain != null;
        this.filterChain = filterChain;
        assert sequenceFilterChain != null;
        this.sequenceFilterChain = sequenceFilterChain;
        hiddenNodes = new HashSet<>();
        onScreenNodes = new HashSet<>();
        selectedNodes = new HashSet<>();
        super.getChangedEvent().addListener(this);
        diagramChangedEvent = new ChangedEvent<>(this);
        viewChangedEvent = new ChangedEvent<>(this);
        hiddenNodesChangedEvent = new ChangedEvent<>(this);
        viewPropertiesChangedEvent = new ChangedEvent<>(this);

        groupChangedEvent = new ChangedEvent<>(this);
        groupChangedEvent.addListener(groupChangedListener);
        groupChangedEvent.fire();

        filterChain.getChangedEvent().addListener(filterChainChangedListener);
        sequenceFilterChain.getChangedEvent().addListener(filterChainChangedListener);
    }
    private final ChangedListener<DiagramViewModel> groupChangedListener = new ChangedListener<DiagramViewModel>() {

        private Group oldGroup;

        @Override
        public void changed(DiagramViewModel source) {
            if (oldGroup != null) {
                oldGroup.getChangedEvent().removeListener(groupContentChangedListener);
            }
            group.getChangedEvent().addListener(groupContentChangedListener);
            oldGroup = group;
        }
    };
    private final ChangedListener<Group> groupContentChangedListener = new ChangedListener<Group>() {

        @Override
        public void changed(Group source) {
            assert source == group;
            setPositions(calculateStringList(source));
            setSelectedNodes(selectedNodes);
        }
    };

    public ChangedEvent<DiagramViewModel> getDiagramChangedEvent() {
        return diagramChangedEvent;
    }

    public ChangedEvent<DiagramViewModel> getViewChangedEvent() {
        return viewChangedEvent;
    }

    public ChangedEvent<DiagramViewModel> getHiddenNodesChangedEvent() {
        return hiddenNodesChangedEvent;
    }

    public ChangedEvent<DiagramViewModel> getViewPropertiesChangedEvent() {
        return viewPropertiesChangedEvent;
    }

    public Set<Integer> getSelectedNodes() {
        return selectedNodes;
    }

    public Set<Integer> getHiddenNodes() {
        return hiddenNodes;
    }

    public Set<Integer> getOnScreenNodes() {
        return onScreenNodes;
    }

    public void setSelectedNodes(Set<Integer> nodes) {
        this.selectedNodes = nodes;
        List<Color> colors = new ArrayList<>();
        for (String s : getPositions()) {
            colors.add(Color.black);
        }
        if (nodes.size() >= 1) {
            for (Integer id : nodes) {
                if (id < 0) {
                    id = -id;
                }
                InputNode last = null;
                int index = 0;
                for (InputGraph g : group.getGraphs()) {
                    Color curColor = colors.get(index);
                    InputNode cur = g.getNode(id);
                    if (cur != null) {
                        if (last == null) {
                            curColor = Color.green;
                        } else {
                            if (last.equals(cur)) {
                                if (curColor == Color.black) {
                                    curColor = Color.white;
                                }
                            } else {
                                if (curColor != Color.green) {
                                    curColor = Color.orange;
                                }
                            }
                        }
                    }
                    last = cur;
                    colors.set(index, curColor);
                    index++;
                }
            }
        }
        setColors(colors);
        viewChangedEvent.fire();
    }

    public void showNot(final Set<Integer> nodes) {
        System.out.println("Shownot called with " + nodes);
        setHiddenNodes(nodes);
    }

    public void showFigures(Collection<Figure> f) {
        HashSet<Integer> newHiddenNodes = new HashSet<>(getHiddenNodes());
        for (Figure fig : f) {
            newHiddenNodes.removeAll(fig.getSource().getSourceNodesAsSet());
        }
        setHiddenNodes(newHiddenNodes);
    }


    public Set<Figure> getSelectedFigures() {
        Set<Figure> result = new HashSet<>();
        for (Figure f : diagram.getFigures()) {
            for (InputNode node : f.getSource().getSourceNodes()) {
                if (getSelectedNodes().contains(node.getId())) {
                    result.add(f);
                }
            }
        }
        return result;
    }

    public void showAll(final Collection<Figure> f) {
        showFigures(f);
    }

    public void showOnly(final Set<Integer> nodes) {
        final HashSet<Integer> allNodes = new HashSet<>(getGraphToView().getGroup().getAllNodes());
        allNodes.removeAll(nodes);
        setHiddenNodes(allNodes);
    }

    public void setHiddenNodes(Set<Integer> nodes) {
        this.hiddenNodes = nodes;
        hiddenNodesChangedEvent.fire();
    }

    public void setOnScreenNodes(Set<Integer> onScreenNodes) {
        this.onScreenNodes = onScreenNodes;
        viewChangedEvent.fire();
    }

    public FilterChain getSequenceFilterChain() {
        return filterChain;
    }

    public void setSequenceFilterChain(FilterChain chain) {
        assert chain != null : "sequenceFilterChain must never be null";
        sequenceFilterChain.getChangedEvent().removeListener(filterChainChangedListener);
        sequenceFilterChain = chain;
        sequenceFilterChain.getChangedEvent().addListener(filterChainChangedListener);
        diagramChanged();
    }

    private void diagramChanged() {
        // clear diagram
        diagram = null;
        getDiagramChangedEvent().fire();

    }

    public FilterChain getFilterChain() {
        return filterChain;
    }

    public void setFilterChain(FilterChain chain) {
        assert chain != null : "filterChain must never be null";
        filterChain.getChangedEvent().removeListener(filterChainChangedListener);
        filterChain = chain;
        filterChain.getChangedEvent().addListener(filterChainChangedListener);
        diagramChanged();
    }

    private static List<String> calculateStringList(Group g) {
        List<String> result = new ArrayList<>();
        for (InputGraph graph : g.getGraphs()) {
            result.add(graph.getName());
        }
        return result;
    }

    public InputGraph getFirstGraph() {
        List<InputGraph> graphs = group.getGraphs();
        if (getFirstPosition() < graphs.size()) {
            return graphs.get(getFirstPosition());
        }
        return graphs.get(graphs.size() - 1);
    }

    public InputGraph getSecondGraph() {
        List<InputGraph> graphs = group.getGraphs();
        if (getSecondPosition() < graphs.size()) {
            return graphs.get(getSecondPosition());
        }
        return getFirstGraph();
    }

    public void selectGraph(InputGraph g) {
        int index = group.getGraphs().indexOf(g);
        assert index != -1;
        setPositions(index, index);
    }

    public Diagram getDiagramToView() {

        if (diagram == null) {
            diagram = Diagram.createDiagram(getGraphToView(), Settings.get().get(Settings.NODE_TEXT, Settings.NODE_TEXT_DEFAULT));
            getFilterChain().apply(diagram, getSequenceFilterChain());
            if (getFirstPosition() != getSecondPosition()) {
                CustomFilter f = new CustomFilter(
                        "difference", "colorize('state', 'same', white);"
                        + "colorize('state', 'changed', orange);"
                        + "colorize('state', 'new', green);"
                        + "colorize('state', 'deleted', red);");
                f.apply(diagram);
           }
        }

        return diagram;
    }

    public InputGraph getGraphToView() {
        if (inputGraph == null) {
            if (getFirstGraph() != getSecondGraph()) {
                inputGraph = Difference.createDiffGraph(getFirstGraph(), getSecondGraph());
            } else {
                inputGraph = getFirstGraph();
            }
        }

        return inputGraph;
    }

    @Override
    public void changed(RangeSliderModel source) {
        inputGraph = null;
        diagramChanged();
    }

    void setSelectedFigures(List<Figure> list) {
        Set<Integer> newSelectedNodes = new HashSet<>();
        for (Figure f : list) {
            newSelectedNodes.addAll(f.getSource().getSourceNodesAsSet());
        }
        this.setSelectedNodes(newSelectedNodes);
    }
}
