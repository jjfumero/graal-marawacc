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
 *
 */
package com.oracle.graal.visualizer.editor;

import com.oracle.graal.visualizer.util.LookupUtils;
import com.sun.hotspot.igv.data.ChangedListener;
import com.sun.hotspot.igv.data.Group;
import com.sun.hotspot.igv.data.InputGraph;
import com.sun.hotspot.igv.util.RangeSliderModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.*;
import javax.swing.*;
import org.openide.awt.Toolbar;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;
import org.openide.util.Utilities;
import org.openide.util.actions.Presenter;
import org.openide.util.lookup.AbstractLookup;
import org.openide.util.lookup.InstanceContent;
import org.openide.util.lookup.Lookups;
import org.openide.util.lookup.ProxyLookup;
import org.openide.windows.Mode;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

public final class EditorTopComponent extends TopComponent {

    private InstanceContent content;
    private static final String PREFERRED_ID = "EditorTopComponent";
    private RangeSliderModel rangeSliderModel;
    private CompilationViewer activeViewer;
    private CompilationViewerFactory activeFactory;
    private Group group;
    private final JToolBar viewerToolBar;
    private final JPanel viewerPanel;
    private final CardLayout viewerPanelCardLayout;
    private final Map<String, CompilationViewer> createdComponents = new HashMap<>();
    private final Lookup proxyLookup;
    private Lookup currentLookup = Lookups.fixed();
    private final Lookup.Provider currentViewLookupProvider = new Lookup.Provider() {

        @Override
        public Lookup getLookup() {
            return currentLookup;
        }
    };

    private InputGraph getFirstGraph() {
        return group.getGraphs().get(getModel().getFirstPosition());
    }

    private InputGraph getSecondGraph() {
        return group.getGraphs().get(getModel().getSecondPosition());
    }

    private void updateDisplayName() {
        int first = getModel().getFirstPosition();
        int second = getModel().getSecondPosition();
        if (first == second) {
            setDisplayName(getModel().getPositions().get(first));
        } else {
            setDisplayName(String.format("%s: %s - %s", activeFactory.getName(), getModel().getPositions().get(first), getModel().getPositions().get(second)));
        }
    }

    private void activateFactory(CompilationViewerFactory factory) {
        this.activeFactory = factory;
        updateView();
    }

    public EditorTopComponent(InputGraph graph) {
        setName(NbBundle.getMessage(EditorTopComponent.class, "CTL_EditorTopComponent"));
        setToolTipText(NbBundle.getMessage(EditorTopComponent.class, "HINT_EditorTopComponent"));

        initComponents();

        //ToolbarPool.getDefault().setPreferredIconSize(16);
        Toolbar toolBar = new Toolbar();
        //Border b = (Border) UIManager.get("Nb.Editor.Toolbar.border"); //NOI18N
        //toolBar.setBorder(b);
        this.add(BorderLayout.NORTH, toolBar);

        this.group = graph.getGroup();
        rangeSliderModel = new RangeSliderModel(calculateStringList(group));
        content = new InstanceContent();
        content.add(rangeSliderModel);
        int graphPos = group.getGraphs().indexOf(graph);
        rangeSliderModel.setPositions(graphPos, graphPos);

        Collection<? extends CompilationViewerFactory> factories = Lookup.getDefault().lookupAll(CompilationViewerFactory.class);
        proxyLookup = Lookups.proxy(currentViewLookupProvider);
        this.associateLookup(new ProxyLookup(new Lookup[]{proxyLookup, new AbstractLookup(content)}));

        rangeSliderModel.getChangedEvent().addListener(rangeSliderListener);

        ButtonGroup factoryButtonGroup = new ButtonGroup();
        for (CompilationViewerFactory factory : factories) {
            AbstractButton button = createFactoryChangeButton(factory);
            factoryButtonGroup.add(button);
            toolBar.add(button);
        }
        toolBar.addSeparator();

        viewerToolBar = new JToolBar();
        toolBar.add(viewerToolBar);
        toolBar.add(Box.createHorizontalGlue());        

        Action action = Utilities.actionsForPath("QuickSearchShadow").get(0);
        Component quicksearch = ((Presenter.Toolbar) action).getToolbarPresenter();
        quicksearch.setMinimumSize(quicksearch.getPreferredSize()); // necessary for GTK LAF
        toolBar.add(quicksearch);

        viewerPanel = new JPanel();
        viewerPanelCardLayout = new CardLayout();
        viewerPanel.setLayout(viewerPanelCardLayout);
        this.add(viewerPanel, BorderLayout.CENTER);

        ((JToggleButton) factoryButtonGroup.getElements().nextElement()).setSelected(true);
        activeFactory = factories.iterator().next();
        updateView();
    }

    private static List<String> calculateStringList(Group g) {
        List<String> result = new ArrayList<>();
        for (InputGraph graph : g.getGraphs()) {
            result.add(graph.getName());
        }
        return result;
    }

    private RangeSliderModel getModel() {
        return rangeSliderModel;
    }

    public static EditorTopComponent getActive() {
        Set<? extends Mode> modes = WindowManager.getDefault().getModes();
        for (Mode m : modes) {
            TopComponent tc = m.getSelectedTopComponent();
            if (tc instanceof EditorTopComponent) {
                return (EditorTopComponent) tc;
            }
        }
        return null;
    }

    /**
     * This method is called from within the constructor to initialize the form. WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jCheckBox1 = new javax.swing.JCheckBox();

        org.openide.awt.Mnemonics.setLocalizedText(jCheckBox1, "jCheckBox1");
        jCheckBox1.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));
        jCheckBox1.setMargin(new java.awt.Insets(0, 0, 0, 0));

        setLayout(new java.awt.BorderLayout());
    }// </editor-fold>//GEN-END:initComponents
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JCheckBox jCheckBox1;
    // End of variables declaration//GEN-END:variables

    @Override
    public int getPersistenceType() {
        return TopComponent.PERSISTENCE_NEVER;
    }

    @Override
    public void componentOpened() {
    }

    @Override
    public void componentClosed() {
    }

    @Override
    protected String preferredID() {
        return PREFERRED_ID;
    }
    private ChangedListener<RangeSliderModel> rangeSliderListener = new ChangedListener<RangeSliderModel>() {

        @Override
        public void changed(RangeSliderModel source) {
            updateView();
        }
    };

    private void updateView() {
        updateDisplayName();
        String id = getViewStringIdentifier();
        if (!createdComponents.containsKey(id)) {
            CompilationViewer newViewer = createViewer(activeFactory);
            createdComponents.put(id, newViewer);
            viewerPanel.add(newViewer.getComponent(), id);
        }

        CompilationViewer newViewer = createdComponents.get(id);
        if (newViewer != activeViewer) {
            activeViewer = newViewer;
            viewerPanelCardLayout.show(viewerPanel, id);
            initializeToolBar(id);

            // Make sure that lookup is updated.
            currentLookup = new ProxyLookup(activeViewer.getLookup(), Lookups.fixed(getFirstGraph(), getSecondGraph()));
            proxyLookup.lookup(Object.class);
        }
    }

    private String getViewStringIdentifier() {
        return String.format("%s/%d/%d", activeFactory.getName(), getModel().getFirstPosition(), getModel().getSecondPosition());
    }

    private AbstractButton createFactoryChangeButton(final CompilationViewerFactory factory) {
        JToggleButton toggleButton = new JToggleButton(factory.getName());
        toggleButton.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                activateFactory(factory);
            }
        });
        return toggleButton;
    }

    private CompilationViewer createViewer(CompilationViewerFactory activeFactory) {
        InputGraph firstSnapshot = getFirstGraph();
        InputGraph secondSnapshot = getSecondGraph();
        return activeFactory.createViewer(firstSnapshot, secondSnapshot);
    }

    private void initializeToolBar(String id) {
        viewerToolBar.removeAll();
        for (Action a : LookupUtils.lookupActions(String.format("CompilationViewer/%s/Actions", id))) {
            viewerToolBar.add(a);
        }
    }
}
