/*
 * Copyright (c) 2008, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.hotspot.igv.view.scene;

import com.oracle.graal.visualizer.editor.DiagramViewModel;
import com.sun.hotspot.igv.data.ChangedListener;
import com.sun.hotspot.igv.data.ControllableChangedListener;
import com.sun.hotspot.igv.data.Pair;
import com.sun.hotspot.igv.data.Properties;
import com.sun.hotspot.igv.graph.*;
import com.sun.hotspot.igv.hierarchicallayout.HierarchicalLayoutManager;
import com.sun.hotspot.igv.layout.LayoutGraph;
import com.sun.hotspot.igv.util.ColorIcon;
import com.sun.hotspot.igv.util.DoubleClickAction;
import com.sun.hotspot.igv.util.PropertiesSheet;
import com.sun.hotspot.igv.view.actions.CustomizablePanAction;
import com.sun.hotspot.igv.view.widgets.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.*;
import javax.swing.*;
import org.netbeans.api.visual.action.*;
import org.netbeans.api.visual.animator.SceneAnimator;
import org.netbeans.api.visual.layout.LayoutFactory;
import org.netbeans.api.visual.model.*;
import org.netbeans.api.visual.widget.LayerWidget;
import org.netbeans.api.visual.widget.Widget;
import org.openide.awt.UndoRedo;
import org.openide.nodes.AbstractNode;
import org.openide.nodes.Children;
import org.openide.nodes.Sheet;
import org.openide.util.Lookup;
import org.openide.util.lookup.AbstractLookup;
import org.openide.util.lookup.InstanceContent;

/**
 *
 * @author Thomas Wuerthinger
 */
public class DiagramScene extends ObjectScene {

    private CustomizablePanAction panAction;
    private WidgetAction hoverAction;
    private WidgetAction selectAction;
    private Lookup lookup;
    private InstanceContent content;
    private Action[] actions;
    private LayerWidget connectionLayer;
    private JScrollPane scrollPane;
    private LayerWidget mainLayer;
    private LayerWidget blockLayer;
    private Widget topLeft;
    private Widget bottomRight;
    private DiagramViewModel model;
    private WidgetAction zoomAction;
    private boolean rebuilding;
    /**
     * The alpha level of partially visible figures.
     */
    public static final float ALPHA = 0.4f;
    /**
     * The offset of the graph to the border of the window showing it.
     */
    public static final int BORDER_SIZE = 20;
    public static final int UNDOREDO_LIMIT = 100;
    public static final int SCROLL_UNIT_INCREMENT = 80;
    public static final int SCROLL_BLOCK_INCREMENT = 400;
    public static final float ZOOM_MAX_FACTOR = 3.0f;
    public static final float ZOOM_MIN_FACTOR = 0.0f;//0.15f;
    public static final float ZOOM_INCREMENT = 1.5f;
    public static final int SLOT_OFFSET = 6;
    public static final int ANIMATION_LIMIT = 40;
    private PopupMenuProvider popupMenuProvider = new PopupMenuProvider() {

        @Override
        public JPopupMenu getPopupMenu(Widget widget, Point localLocation) {
            return DiagramScene.this.createPopupMenu();
        }
    };
    private RectangularSelectDecorator rectangularSelectDecorator = new RectangularSelectDecorator() {

        @Override
        public Widget createSelectionWidget() {
            Widget widget = new Widget(DiagramScene.this);
            widget.setBorder(BorderFactory.createLineBorder(Color.black, 2));
            widget.setForeground(Color.red);
            return widget;
        }
    };

    @SuppressWarnings("unchecked")
    public <T> T getWidget(Object o) {
        Widget w = this.findWidget(o);
        return (T) w;
    }

    @SuppressWarnings("unchecked")
    public <T> T getWidget(Object o, Class<T> klass) {
        Widget w = this.findWidget(o);
        return (T) w;
    }

    private static boolean intersects(Set<? extends Object> s1, Set<? extends Object> s2) {
        for (Object o : s1) {
            if (s2.contains(o)) {
                return true;
            }
        }
        return false;
    }

    public void zoomOut() {
        double zoom = getZoomFactor();
        Point viewPosition = getScrollPane().getViewport().getViewPosition();
        double newZoom = zoom / DiagramScene.ZOOM_INCREMENT;
        if (newZoom > DiagramScene.ZOOM_MIN_FACTOR) {
            setZoomFactor(newZoom);
            validate();
            getScrollPane().getViewport().setViewPosition(new Point((int) (viewPosition.x / DiagramScene.ZOOM_INCREMENT), (int) (viewPosition.y / DiagramScene.ZOOM_INCREMENT)));
        }
    }

    public void zoomIn() {

        double zoom = getZoomFactor();
        Point viewPosition = getScrollPane().getViewport().getViewPosition();
        double newZoom = zoom * DiagramScene.ZOOM_INCREMENT;
        if (newZoom < DiagramScene.ZOOM_MAX_FACTOR) {
            setZoomFactor(newZoom);
            validate();
            getScrollPane().getViewport().setViewPosition(new Point((int) (viewPosition.x * DiagramScene.ZOOM_INCREMENT), (int) (viewPosition.y * DiagramScene.ZOOM_INCREMENT)));
        }
    }

    private void centerFigures(Collection<Figure> list) {
        gotoFigures(list);
    }

    private Set<Object> getObjectsFromIdSet(Set<Object> set) {
        Set<Object> selectedObjects = new HashSet<>();
        for (Figure f : getModel().getDiagramToView().getFigures()) {
            if (intersects(f.getSource().getSourceNodesAsSet(), set)) {
                selectedObjects.add(f);
            }

            for (Slot s : f.getSlots()) {
                if (intersects(s.getSource().getSourceNodesAsSet(), set)) {
                    selectedObjects.add(s);
                }
            }
        }
        return selectedObjects;
    }
    private RectangularSelectProvider rectangularSelectProvider = new RectangularSelectProvider() {

        @Override
        public void performSelection(Rectangle rectangle) {
            if (rectangle.width < 0) {
                rectangle.x += rectangle.width;
                rectangle.width *= -1;
            }

            if (rectangle.height < 0) {
                rectangle.y += rectangle.height;
                rectangle.height *= -1;
            }

            Set<Object> selectedObjects = new HashSet<>();
            for (Figure f : getModel().getDiagramToView().getFigures()) {
                FigureWidget w = getWidget(f);
                if (w != null) {
                    Rectangle r = new Rectangle(w.getBounds());
                    r.setLocation(w.getLocation());

                    if (r.intersects(rectangle)) {
                        selectedObjects.add(f);
                    }

                    for (Slot s : f.getSlots()) {
                        SlotWidget sw = getWidget(s);
                        Rectangle r2 = new Rectangle(sw.getBounds());
                        r2.setLocation(sw.convertLocalToScene(new Point(0, 0)));

                        if (r2.intersects(rectangle)) {
                            selectedObjects.add(s);
                        }
                    }
                } else {
                    assert false : "w should not be null here!";
                }
            }

            setSelectedObjects(selectedObjects);
        }
    };
    private MouseWheelListener mouseWheelListener = new MouseWheelListener() {

        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            if (e.isControlDown()) {
                DiagramScene.this.relayoutWithoutLayout(null);
            }
        }
    };

    public Point getScrollPosition() {
        return getScrollPane().getViewport().getViewPosition();
    }

    public void setScrollPosition(Point p) {
        getScrollPane().getViewport().setViewPosition(p);
    }

    private JScrollPane createScrollPane() {
        JComponent comp = this.createView();
        comp.setDoubleBuffered(true);
        comp.setBackground(Color.WHITE);
        comp.setOpaque(true);
        this.setBackground(Color.WHITE);
        this.setOpaque(true);
        JScrollPane result = new JScrollPane(comp);
        result.setBackground(Color.WHITE);
        result.getVerticalScrollBar().setUnitIncrement(SCROLL_UNIT_INCREMENT);
        result.getVerticalScrollBar().setBlockIncrement(SCROLL_BLOCK_INCREMENT);
        result.getHorizontalScrollBar().setUnitIncrement(SCROLL_UNIT_INCREMENT);
        result.getHorizontalScrollBar().setBlockIncrement(SCROLL_BLOCK_INCREMENT);
        return result;
    }
    private ObjectSceneListener selectionChangedListener = new ObjectSceneListener() {

        @Override
        public void objectAdded(ObjectSceneEvent arg0, Object arg1) {
        }

        @Override
        public void objectRemoved(ObjectSceneEvent arg0, Object arg1) {
        }

        @Override
        public void objectStateChanged(ObjectSceneEvent e, Object o, ObjectState oldState, ObjectState newState) {
        }

        @Override
        public void selectionChanged(ObjectSceneEvent e, Set<Object> oldSet, Set<Object> newSet) {
            DiagramScene scene = (DiagramScene) e.getObjectScene();
            if (scene.isRebuilding()) {
                return;
            }

            content.set(newSet, null);

            Set<Integer> nodeSelection = new HashSet<>();
            for (Object o : newSet) {
                if (o instanceof Properties.Provider) {
                    final Properties.Provider provider = (Properties.Provider) o;
                    AbstractNode node = new AbstractNode(Children.LEAF) {

                        @Override
                        protected Sheet createSheet() {
                            Sheet s = super.createSheet();
                            PropertiesSheet.initializeSheet(provider.getProperties(), s);
                            return s;
                        }
                    };
                    node.setDisplayName(provider.getProperties().get("name"));
                    content.add(node);
                }


                if (o instanceof Figure) {
                    nodeSelection.addAll(((Figure) o).getSource().getSourceNodesAsSet());
                } else if (o instanceof Slot) {
                    nodeSelection.addAll(((Slot) o).getSource().getSourceNodesAsSet());
                }
            }
            getModel().setSelectedNodes(nodeSelection);
        }

        @Override
        public void highlightingChanged(ObjectSceneEvent e, Set<Object> oldSet, Set<Object> newSet) {
            Set<Integer> nodeHighlighting = new HashSet<>();
            for (Object o : newSet) {
                if (o instanceof Figure) {
                    nodeHighlighting.addAll(((Figure) o).getSource().getSourceNodesAsSet());
                } else if (o instanceof Slot) {
                    nodeHighlighting.addAll(((Slot) o).getSource().getSourceNodesAsSet());
                }
            }
//            boolean b = highlightedCoordinatorListener.isEnabled();
//            highlightedCoordinatorListener.setEnabled(false);
//            SelectionCoordinator.getInstance().setHighlightedObjects(nodeHighlighting);
//            highlightedCoordinatorListener.setEnabled(b);
            validate();
        }

        @Override
        public void hoverChanged(ObjectSceneEvent e, Object oldObject, Object newObject) {
            Set<Object> newHighlightedObjects = new HashSet<>(DiagramScene.this.getHighlightedObjects());
            if (oldObject != null) {
                newHighlightedObjects.remove(oldObject);
            }
            if (newObject != null) {
                newHighlightedObjects.add(newObject);
            }
            DiagramScene.this.setHighlightedObjects(newHighlightedObjects);
        }

        @Override
        public void focusChanged(ObjectSceneEvent arg0, Object arg1, Object arg2) {
        }
    };

    public void setActions(Action[] actions) {
        this.actions = actions;
    }
    
    

    public DiagramScene(DiagramViewModel model) {

        this.model = model;
        content = new InstanceContent();
        lookup = new AbstractLookup(content);

        this.setCheckClipping(true);

        scrollPane = createScrollPane();

        hoverAction = createObjectHoverAction();

        // This panAction handles the event only when the left mouse button is
        // pressed without any modifier keys, otherwise it will not consume it
        // and the selection action (below) will handle the event
        panAction = new CustomizablePanAction(~0, MouseEvent.BUTTON1_DOWN_MASK);
        this.getActions().addAction(panAction);

        selectAction = createSelectAction();
        this.getActions().addAction(selectAction);

        blockLayer = new LayerWidget(this);
        this.addChild(blockLayer);

        mainLayer = new LayerWidget(this);
        this.addChild(mainLayer);

        topLeft = new Widget(this);
        topLeft.setPreferredLocation(new Point(-BORDER_SIZE, -BORDER_SIZE));
        this.addChild(topLeft);

        bottomRight = new Widget(this);
        bottomRight.setPreferredLocation(new Point(-BORDER_SIZE, -BORDER_SIZE));
        this.addChild(bottomRight);

        connectionLayer = new LayerWidget(this);
        this.addChild(connectionLayer);

        LayerWidget selectionLayer = new LayerWidget(this);
        this.addChild(selectionLayer);

        this.setLayout(LayoutFactory.createAbsoluteLayout());

        this.getInputBindings().setZoomActionModifiers(KeyEvent.CTRL_MASK);
        zoomAction = ActionFactory.createMouseCenteredZoomAction(1.2);
        this.getActions().addAction(zoomAction);
        this.getView().addMouseWheelListener(mouseWheelListener);
        this.getActions().addAction(ActionFactory.createPopupMenuAction(popupMenuProvider));

        this.getActions().addAction(ActionFactory.createWheelPanAction());

        LayerWidget selectLayer = new LayerWidget(this);
        this.addChild(selectLayer);
        this.getActions().addAction(ActionFactory.createRectangularSelectAction(rectangularSelectDecorator, selectLayer, rectangularSelectProvider));

        this.addObjectSceneListener(selectionChangedListener, ObjectSceneEventType.OBJECT_SELECTION_CHANGED, ObjectSceneEventType.OBJECT_HIGHLIGHTING_CHANGED, ObjectSceneEventType.OBJECT_HOVER_CHANGED);
        
        update();
    }

    public DiagramViewModel getModel() {
        return model;
    }

    public JScrollPane getScrollPane() {
        return scrollPane;
    }

    Component getComponent() {
        return scrollPane;
    }

    public boolean isAllVisible() {
        return getModel().getHiddenNodes().isEmpty();
    }

    public Action createGotoAction(final Figure f) {
        final DiagramScene diagramScene = this;
        Action a = new AbstractAction() {

            @Override
            public void actionPerformed(ActionEvent e) {
                diagramScene.gotoFigure(f);
            }
        };

        a.setEnabled(true);
        a.putValue(Action.SMALL_ICON, new ColorIcon(f.getColor()));
        String name = f.getLines()[0];

        name += " (";

        if (!this.getWidget(f, FigureWidget.class).isVisible()) {
            name += "hidden";
        }
        name += ")";
        a.putValue(Action.NAME, name);
        return a;
    }

    private void update() {
        mainLayer.removeChildren();
        blockLayer.removeChildren();

        rebuilding = true;

        Collection<Object> objects = new ArrayList<>(this.getObjects());
        for (Object o : objects) {
            this.removeObject(o);
        }

        Diagram d = getModel().getDiagramToView();

        for (Figure f : d.getFigures()) {
            FigureWidget w = new FigureWidget(f, hoverAction, selectAction, this, mainLayer);
            w.getActions().addAction(ActionFactory.createPopupMenuAction(w));
            w.getActions().addAction(selectAction);
            w.getActions().addAction(hoverAction);
            w.setVisible(false);

            this.addObject(f, w);

            for (InputSlot s : f.getInputSlots()) {
                SlotWidget sw = new InputSlotWidget(s, this, w, w);
                addObject(s, sw);
                sw.getActions().addAction(new DoubleClickAction(sw));
                sw.getActions().addAction(hoverAction);
                sw.getActions().addAction(selectAction);
            }

            for (OutputSlot s : f.getOutputSlots()) {
                SlotWidget sw = new OutputSlotWidget(s, this, w, w);
                addObject(s, sw);
                sw.getActions().addAction(new DoubleClickAction(sw));
                sw.getActions().addAction(hoverAction);
                sw.getActions().addAction(selectAction);
            }
        }

        rebuilding = false;
        this.smallUpdate(true);
    }

    public boolean isRebuilding() {
        return rebuilding;
    }

    private void smallUpdate(boolean relayout) {

        this.updateHiddenNodes(model.getHiddenNodes(), relayout);
        this.validate();
    }

    private boolean isVisible(Connection c) {
        FigureWidget w1 = getWidget(c.getInputSlot().getFigure());
        FigureWidget w2 = getWidget(c.getOutputSlot().getFigure());

        if (w1.isVisible() && w2.isVisible()) {
            return true;
        }

        return false;
    }

    private void relayout(Set<Widget> oldVisibleWidgets) {
        System.out.println("relayout called with old visible widgets: " + oldVisibleWidgets);

        Diagram diagram = getModel().getDiagramToView();

        HashSet<Figure> figures = new HashSet<>();

        for (Figure f : diagram.getFigures()) {
            FigureWidget w = getWidget(f);
            if (w.isVisible()) {
                figures.add(f);
            }
        }

        HashSet<Connection> edges = new HashSet<>();

        for (Connection c : diagram.getConnections()) {
            if (isVisible(c)) {
                edges.add(c);
            }
        }

        HierarchicalLayoutManager manager = new HierarchicalLayoutManager(HierarchicalLayoutManager.Combine.SAME_OUTPUTS);
        manager.setMaxLayerLength(10);
        manager.doLayout(new LayoutGraph(edges, figures));
        relayoutWithoutLayout(oldVisibleWidgets);
    }
    private Set<Pair<Point, Point>> lineCache = new HashSet<>();

    private void relayoutWithoutLayout(Set<Widget> oldVisibleWidgets) {

        System.out.println("relayout without layout with visible widgets: " + oldVisibleWidgets);

        Diagram diagram = getModel().getDiagramToView();

        int maxX = -BORDER_SIZE;
        int maxY = -BORDER_SIZE;
        for (Figure f : diagram.getFigures()) {
            FigureWidget w = getWidget(f);
            if (w.isVisible()) {
                Point p = f.getPosition();
                Dimension d = f.getSize();
                maxX = Math.max(maxX, p.x + d.width);
                maxY = Math.max(maxY, p.y + d.height);
            }
        }

        for (Connection c : diagram.getConnections()) {
            List<Point> points = c.getControlPoints();
            FigureWidget w1 = getWidget((Figure) c.getTo().getVertex());
            FigureWidget w2 = getWidget((Figure) c.getFrom().getVertex());
            if (w1.isVisible() && w2.isVisible()) {
                for (Point p : points) {
                    if (p != null) {
                        maxX = Math.max(maxX, p.x);
                        maxY = Math.max(maxY, p.y);
                    }
                }
            }
        }

        bottomRight.setPreferredLocation(new Point(maxX + BORDER_SIZE, maxY + BORDER_SIZE));
        int offx = 0;
        int offy = 0;
        int curWidth = maxX + 2 * BORDER_SIZE;
        int curHeight = maxY + 2 * BORDER_SIZE;

        Rectangle bounds = this.getScrollPane().getBounds();
        bounds.width /= getZoomFactor();
        bounds.height /= getZoomFactor();
        if (curWidth < bounds.width) {
            offx = (bounds.width - curWidth) / 2;
        }

        if (curHeight < bounds.height) {
            offy = (bounds.height - curHeight) / 2;
        }

        final int offx2 = offx;
        final int offy2 = offy;

        SceneAnimator animator = this.getSceneAnimator();
        connectionLayer.removeChildren();
        int visibleFigureCount = 0;
        for (Figure f : diagram.getFigures()) {
            if (getWidget(f, FigureWidget.class).isVisible()) {
                visibleFigureCount++;
            }
        }


        Set<Pair<Point, Point>> lastLineCache = lineCache;
        lineCache = new HashSet<>();
        for (Figure f : diagram.getFigures()) {
            for (OutputSlot s : f.getOutputSlots()) {
                SceneAnimator anim = animator;
                if (visibleFigureCount > ANIMATION_LIMIT || oldVisibleWidgets == null) {
                    anim = null;
                }
                processOutputSlot(lastLineCache, s, s.getConnections(), 0, null, null, offx2, offy2, anim);
            }
        }

        for (Figure f : diagram.getFigures()) {
            FigureWidget w = getWidget(f);
            if (w.isVisible()) {
                Point p = f.getPosition();
                Point p2 = new Point(p.x + offx2, p.y + offy2);
                if ((visibleFigureCount <= ANIMATION_LIMIT && oldVisibleWidgets != null && oldVisibleWidgets.contains(w))) {
                    animator.animatePreferredLocation(w, p2);
                } else {
                    w.setPreferredLocation(p2);
                    animator.animatePreferredLocation(w, p2);
                }
            }
        }

        this.validate();
    }
    private final Point specialNullPoint = new Point(Integer.MAX_VALUE, Integer.MAX_VALUE);

    private void processOutputSlot(Set<Pair<Point, Point>> lastLineCache, OutputSlot s, List<Connection> connections, int controlPointIndex, Point lastPoint, LineWidget predecessor, int offx, int offy, SceneAnimator animator) {
        Map<Point, List<Connection>> pointMap = new HashMap<>(connections.size());

        for (Connection c : connections) {

            if (!isVisible(c)) {
                continue;
            }

            List<Point> controlPoints = c.getControlPoints();
            if (controlPointIndex >= controlPoints.size()) {
                continue;
            }

            Point cur = controlPoints.get(controlPointIndex);
            if (cur == null) {
                cur = specialNullPoint;
            } else if (controlPointIndex == 0 && !s.shouldShowName()) {
                cur = new Point(cur.x, cur.y - SLOT_OFFSET);
            } else if (controlPointIndex == controlPoints.size() - 1 && !c.getInputSlot().shouldShowName()) {
                cur = new Point(cur.x, cur.y + SLOT_OFFSET);
            }

            if (pointMap.containsKey(cur)) {
                pointMap.get(cur).add(c);
            } else {
                List<Connection> newList = new ArrayList<>(2);
                newList.add(c);
                pointMap.put(cur, newList);
            }

        }

        for (Point p : pointMap.keySet()) {
            List<Connection> connectionList = pointMap.get(p);

            boolean isBold = false;
            boolean isDashed = true;

            for (Connection c : connectionList) {

                if (c.getStyle() == Connection.ConnectionStyle.BOLD) {
                    isBold = true;
                }

                if (c.getStyle() != Connection.ConnectionStyle.DASHED) {
                    isDashed = false;
                }
            }

            LineWidget newPredecessor = predecessor;
            if (p == specialNullPoint) {
            } else if (lastPoint == specialNullPoint) {
            } else if (lastPoint != null) {
                Point p1 = new Point(lastPoint.x + offx, lastPoint.y + offy);
                Point p2 = new Point(p.x + offx, p.y + offy);

                Pair<Point, Point> curPair = new Pair<>(p1, p2);
                SceneAnimator curAnimator = animator;
                if (lastLineCache.contains(curPair)) {
                    curAnimator = null;
                }
                LineWidget w = new LineWidget(this, s, connectionList, p1, p2, predecessor, curAnimator, isBold, isDashed);
                lineCache.add(curPair);

                newPredecessor = w;
                connectionLayer.addChild(w);
                w.getActions().addAction(hoverAction);
            }

            processOutputSlot(lastLineCache, s, connectionList, controlPointIndex + 1, p, newPredecessor, offx, offy, animator);
        }
    }

    @Override
    public Lookup getLookup() {
        return lookup;
    }

    private void gotoFigures(final Collection<Figure> figures) {
        Rectangle overall = null;
        getModel().showFigures(figures);
        for (Figure f : figures) {

            FigureWidget fw = getWidget(f);
            if (fw != null) {
                Rectangle r = fw.getBounds();
                Point p = fw.getLocation();
                Rectangle r2 = new Rectangle(p.x, p.y, r.width, r.height);

                if (overall == null) {
                    overall = r2;
                } else {
                    overall = overall.union(r2);
                }
            }
        }
        if (overall != null) {
            centerRectangle(overall);
        }
    }

    private Set<Object> idSetToObjectSet(Set<Object> ids) {

        Set<Object> result = new HashSet<>();
        for (Figure f : getModel().getDiagramToView().getFigures()) {
            if (DiagramScene.doesIntersect(f.getSource().getSourceNodesAsSet(), ids)) {
                result.add(f);
            }

            for (Slot s : f.getSlots()) {
                if (DiagramScene.doesIntersect(s.getSource().getSourceNodesAsSet(), ids)) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    public void gotoSelection(Set<Object> ids) {

        Rectangle overall = null;
        Set<Integer> hiddenNodes = new HashSet<>(this.getModel().getHiddenNodes());
        hiddenNodes.removeAll(ids);
        this.getModel().showNot(hiddenNodes);

        Set<Object> objects = idSetToObjectSet(ids);
        for (Object o : objects) {

            Widget w = getWidget(o);
            if (w != null) {
                Rectangle r = w.getBounds();
                Point p = w.convertLocalToScene(new Point(0, 0));

                Rectangle r2 = new Rectangle(p.x, p.y, r.width, r.height);

                if (overall == null) {
                    overall = r2;
                } else {
                    overall = overall.union(r2);
                }
            }
        }
        if (overall != null) {
            centerRectangle(overall);
        }

        setSelectedObjects(objects);
    }

    private Point calcCenter(Rectangle r) {

        Point center = new Point((int) r.getCenterX(), (int) r.getCenterY());
        center.x -= getScrollPane().getViewport().getViewRect().width / 2;
        center.y -= getScrollPane().getViewport().getViewRect().height / 2;

        // Ensure to be within area
        center.x = Math.max(0, center.x);
        center.x = Math.min(getScrollPane().getViewport().getViewSize().width - getScrollPane().getViewport().getViewRect().width, center.x);
        center.y = Math.max(0, center.y);
        center.y = Math.min(getScrollPane().getViewport().getViewSize().height - getScrollPane().getViewport().getViewRect().height, center.y);

        return center;
    }

    private void centerRectangle(Rectangle r) {

        if (getScrollPane().getViewport().getViewRect().width == 0 || getScrollPane().getViewport().getViewRect().height == 0) {
            return;
        }

        Rectangle r2 = new Rectangle(r.x, r.y, r.width, r.height);
        r2 = convertSceneToView(r2);

        double factorX = (double) r2.width / (double) getScrollPane().getViewport().getViewRect().width;
        double factorY = (double) r2.height / (double) getScrollPane().getViewport().getViewRect().height;
        double factor = Math.max(factorX, factorY);
        if (factor >= 1.0) {
            Point p = getScrollPane().getViewport().getViewPosition();
            setZoomFactor(getZoomFactor() / factor);
            r2.x /= factor;
            r2.y /= factor;
            r2.width /= factor;
            r2.height /= factor;
            getScrollPane().getViewport().setViewPosition(calcCenter(r2));
        } else {
            getScrollPane().getViewport().setViewPosition(calcCenter(r2));
        }
    }

    void setSelection(Collection<Figure> list) {
        super.setSelectedObjects(new HashSet<>(list));
        centerFigures(list);
    }

    private boolean isVisible(Figure f) {
        for (Integer n : f.getSource().getSourceNodesAsSet()) {
            if (getModel().getHiddenNodes().contains(n)) {
                return false;
            }
        }
        return true;
    }

    public static boolean doesIntersect(Set<?> s1, Set<?> s2) {
        if (s1.size() > s2.size()) {
            Set<?> tmp = s1;
            s1 = s2;
            s2 = tmp;
        }

        for (Object o : s1) {
            if (s2.contains(o)) {
                return true;
            }
        }

        return false;
    }

    private void updateHiddenNodes(Set<Integer> newHiddenNodes, boolean doRelayout) {

        System.out.println("newHiddenNodes: " + newHiddenNodes);

        Diagram diagram = getModel().getDiagramToView();
        assert diagram != null;

        Set<Widget> oldVisibleWidgets = new HashSet<>();

        for (Figure f : diagram.getFigures()) {
            FigureWidget w = getWidget(f);
            if (w != null && w.isVisible()) {
                oldVisibleWidgets.add(w);
            }
        }

        for (Figure f : diagram.getFigures()) {
            boolean hiddenAfter = doesIntersect(f.getSource().getSourceNodesAsSet(), newHiddenNodes);

            FigureWidget w = getWidget(f);
            w.setBoundary(false);
            if (!hiddenAfter) {
                // Figure is shown
                w.setVisible(true);
            } else {
                // Figure is hidden
                w.setVisible(false);
            }
        }

        if (getModel().getShowNodeHull()) {
            List<FigureWidget> boundaries = new ArrayList<>();
            for (Figure f : diagram.getFigures()) {
                FigureWidget w = getWidget(f);
                if (!w.isVisible()) {
                    Set<Figure> set = new HashSet<>(f.getPredecessorSet());
                    set.addAll(f.getSuccessorSet());

                    boolean b = false;
                    for (Figure neighbor : set) {
                        FigureWidget neighborWidget = getWidget(neighbor);
                        if (neighborWidget.isVisible()) {
                            b = true;
                            break;
                        }
                    }

                    if (b) {
                        w.setBoundary(true);
                        boundaries.add(w);
                    }
                }
            }

            for (FigureWidget w : boundaries) {
                if (w.isBoundary()) {
                    w.setVisible(true);
                }
            }
        }

        if (doRelayout) {
            relayout(oldVisibleWidgets);
        }
        this.validate();
    }

    private void showFigure(Figure f) {
        HashSet<Integer> newHiddenNodes = new HashSet<>(getModel().getHiddenNodes());
        newHiddenNodes.removeAll(f.getSource().getSourceNodesAsSet());
        updateHiddenNodes(newHiddenNodes, true);
    }

    public void show(final Figure f) {
        showFigure(f);
    }

    public void setSelectedObjects(Object... args) {
        Set<Object> set = new HashSet<>();
        for (Object o : args) {
            set.add(o);
        }
        super.setSelectedObjects(set);
    }

    private void centerWidget(Widget w) {
        Rectangle r = w.getBounds();
        Point p = w.getLocation();
        centerRectangle(new Rectangle(p.x, p.y, r.width, r.height));
    }

    public void gotoFigure(final Figure f) {
        if (!isVisible(f)) {
            showFigure(f);
        }

        FigureWidget fw = getWidget(f);
        if (fw != null) {
            setSelection(new HashSet<>(Arrays.asList(f)));
        }
    }

    public JPopupMenu createPopupMenu() {
        JPopupMenu menu = new JPopupMenu();
        for (Action a : actions) {
            if (a == null) {
                menu.addSeparator();
            } else {
                menu.add(a);
            }
        }
        return menu;
    }
    
    private final ChangedListener<DiagramViewModel> fullChange = new ChangedListener<DiagramViewModel>() {

        @Override
        public void changed(DiagramViewModel source) {
            assert source == model : "Receive only changed event from current model!";
            assert source != null;
            update();
        }
    };
    private final ChangedListener<DiagramViewModel> hiddenNodesChange = new ChangedListener<DiagramViewModel>() {

        @Override
        public void changed(DiagramViewModel source) {
            assert source == model : "Receive only changed event from current model!";
            assert source != null;
            smallUpdate(true);
        }
    };
    private final ChangedListener<DiagramViewModel> selectionChange = new ChangedListener<DiagramViewModel>() {

        @Override
        public void changed(DiagramViewModel source) {
            assert source == model : "Receive only changed event from current model!";
            assert source != null;
            smallUpdate(false);
        }
    };
}
