package at.ssw.visualizer.cfg.graph;

import at.ssw.visualizer.cfg.model.CfgEdge;
import at.ssw.visualizer.cfg.CfgEditorContext;
import at.ssw.visualizer.cfg.action.HideEdgesAction;
import at.ssw.visualizer.cfg.action.ShowEdgesAction;
import at.ssw.visualizer.cfg.graph.layout.HierarchicalCompoundLayout;
import at.ssw.visualizer.cfg.graph.layout.HierarchicalNodeLayout;
import at.ssw.visualizer.cfg.model.CfgEnv;
import at.ssw.visualizer.cfg.model.CfgNode;
import at.ssw.visualizer.cfg.model.LoopInfo;
import at.ssw.visualizer.cfg.preferences.CfgPreferences;
import at.ssw.visualizer.cfg.visual.PolylineRouter;
import at.ssw.visualizer.cfg.visual.PolylineRouterV2;
import at.ssw.visualizer.cfg.visual.WidgetCollisionCollector;
import at.ssw.visualizer.model.cfg.BasicBlock;
import at.ssw.visualizer.model.cfg.ControlFlowGraph;
import com.oracle.graal.visualizer.sharedactions.ZoomCookie;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.prefs.PreferenceChangeEvent;
import java.util.prefs.PreferenceChangeListener;
import java.util.prefs.Preferences;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.EventListenerList;
import org.netbeans.api.visual.action.ActionFactory;
import org.netbeans.api.visual.action.MoveProvider;
import org.netbeans.api.visual.action.PopupMenuProvider;
import org.netbeans.api.visual.action.RectangularSelectDecorator;
import org.netbeans.api.visual.action.RectangularSelectProvider;
import org.netbeans.api.visual.action.WidgetAction;
import org.netbeans.api.visual.anchor.Anchor;
import org.netbeans.api.visual.anchor.AnchorFactory;
import org.netbeans.api.visual.graph.GraphScene;
import org.netbeans.api.visual.graph.layout.GraphLayout;
import org.netbeans.api.visual.layout.LayoutFactory;
import org.netbeans.api.visual.layout.SceneLayout;
import org.netbeans.api.visual.router.Router;
import org.netbeans.api.visual.router.Router.*;
import org.netbeans.api.visual.router.RouterFactory;
import org.netbeans.api.visual.widget.ConnectionWidget;
import org.netbeans.api.visual.widget.LayerWidget;
import org.netbeans.api.visual.widget.Widget;
import org.openide.util.NbPreferences;
import org.openide.util.actions.SystemAction;

public final class CfgScene extends GraphScene<CfgNode, CfgEdge> implements ChangeListener, ZoomCookie {

    private LayerWidget mainLayer = new LayerWidget(this);
    private LayerWidget connectionLayer = new LayerWidget(this);
    private LayerWidget interractionLayer = new LayerWidget(this);
    private LayerWidget clusterLayer = new LayerWidget(this);
    private Set<CfgNode> selectedNodes = Collections.<CfgNode>emptySet();
    private Map<Integer, LoopClusterWidget> loopidx2clusterwidget = new HashMap<>();
    private Map<CfgNode, EdgeSwitchWidget> inputSwitches = new HashMap<>();
    private Map<CfgNode, EdgeSwitchWidget> outputSwitches = new HashMap<>();
    private WidgetAction moveAction = ActionFactory.createMoveAction(ActionFactory.createFreeMoveStrategy(), this.createMoveProvider());
    private SceneLayout sceneLayout;
    private CfgEnv env;
    private int currentLayout = -1;
    private JScrollPane scrollPane;
    private WidgetAction contextPopupAction = this.createContextMenuAction(this);
    private List<NodeWidget> nodeWidgets = null;
    private boolean loopClustersVisible = true;
    private static String PREFERENCE_LOOP_CLUSTER = "loopClusters";
    private static String PREFERENCE_ROUTER = "router";
    private static String PREFERENCE_LAYOUT = "layout";

    public static Preferences getPreferences() {
        return NbPreferences.forModule(CfgScene.class);
    }
    private final PreferenceChangeListener preferenceChangeListener = new PreferenceChangeListener() {
        @Override
        public void preferenceChange(PreferenceChangeEvent evt) {
            if (evt.getKey().equals(PREFERENCE_ROUTER)) {
                setUseBezierRouter(Boolean.parseBoolean(evt.getNewValue()));
            } else if (evt.getKey().equals(PREFERENCE_LOOP_CLUSTER)) {
                setLoopWidgets(Boolean.parseBoolean(evt.getNewValue()));
            }
        }
    };

    public CfgScene(final JScrollPane scrollPane, final ControlFlowGraph cfg) {
        addChild(clusterLayer);
        addChild(mainLayer);
        addChild(interractionLayer);
        addChild(connectionLayer);
        this.scrollPane = scrollPane;
        this.loadModel(new CfgEnv(cfg));
        this.getInputBindings().setZoomActionModifiers(0);
        this.getActions().addAction(ActionFactory.createMouseCenteredZoomAction(1.1));
        this.getActions().addAction(ActionFactory.createPanAction());
        this.getActions().addAction(ActionFactory.createRectangularSelectAction(
                this.createSelectDecorator(this),
                interractionLayer,
                this.createRectangularSelectProvider()));
        this.getActions().addAction(this.contextPopupAction);
        this.addSceneListener(createSceneListener(this));
        this.validate();

        getPreferences().addPreferenceChangeListener(preferenceChangeListener);
        this.loadDefaults();
    }

    private void loadModel(CfgEnv cfgenv) {
        this.env = cfgenv;
        for (CfgNode n : env.getNodes()) {
            addNode(n);
        }
        for (CfgEdge e : env.getEdges()) {
            addEdge(e);
            setEdgeSource(e, e.getSourceNode());
            setEdgeTarget(e, e.getTargetNode());
        }
        this.stackLoops(cfgenv.getLoopMap());
        this.autoHideEdges();
    }

    public void loadDefaults() {
        CfgPreferences prefs = CfgPreferences.getInstance();
        this.setBackground(prefs.getBackgroundColor());
        setUseBezierRouter(getPreferences().getBoolean(PREFERENCE_ROUTER, true));
        setLoopWidgets(getPreferences().getBoolean(PREFERENCE_LOOP_CLUSTER, true));
        setSceneLayout(getPreferences().getInt(PREFERENCE_LAYOUT, CfgEditorContext.LAYOUT_HIERARCHICALNODELAYOUT));
    }

    //sets the parent Widget of all LoopClusterWidgets
    private void stackLoops(Map<CfgNode, LoopInfo> map) {
        this.clusterLayer.removeChildren();

        Set<LoopInfo> cache = new HashSet<>();
        for (LoopInfo info : map.values()) {
            if (cache.contains(info)) {
                continue;
            }
            LoopClusterWidget widget = this.loopidx2clusterwidget.get(info.getLoopIndex());
            LoopInfo parent = info.getParent();
            while (parent != null) {
                LoopClusterWidget parentWidget = this.loopidx2clusterwidget.get(parent.getLoopIndex());
                assert parentWidget != null;
                if (widget.getParentWidget() != null) {
                    widget.removeFromParent();
                }
                parentWidget.addChild(widget);
                widget = parentWidget;
                parent = parent.getParent();
            }
            widget.removeFromParent();
            this.clusterLayer.addChild(widget);//parent == null => parent is clusterlayer
        }
    }

    //hide in|output edges 
    private void autoHideEdges() {
        for (CfgNode n : this.getNodes()) {
            int fanin = n.getInputEdges().length;
            int fanout = n.getOutputEdges().length;
            if (fanin > CfgEditorContext.MAX_AUTOEDGESVISIBLE) {
                assert (inputSwitches.containsKey(n));
                if (this.inputSwitches.containsKey(n)) {
                    EdgeSwitchWidget esw = this.inputSwitches.get(n);
                    esw.changeEdgeVisibility(false);
                }
            }
            if (fanout > CfgEditorContext.MAX_AUTOEDGESVISIBLE) {
                if (this.outputSwitches.containsKey(n)) {
                    EdgeSwitchWidget esw = this.outputSwitches.get(n);
                    esw.changeEdgeVisibility(false);
                }
            }
        }

    }

    //apply current cfggraphscene layout  
    public void applyLayout() {
        this.sceneLayout.invokeLayoutImmediately();
    }

    //returns a Set with the currently selected Nodes    
    public Set<CfgNode> getSelectedNodes() {
        return Collections.<CfgNode>unmodifiableSet(selectedNodes);
    }

    public Map<Integer, LoopClusterWidget> getLoopidx2clusterwidget() {
        return loopidx2clusterwidget;
    }

    /**
     * Sets the color of the currently selected Nodes If the supplied color is null the default color will be used
     */
    public void setSelectedNodesColor(Color color) {
        if (color == null) { //set default color
            CfgPreferences prefs = CfgPreferences.getInstance();
            boolean customized = false;
            for (CfgNode n : this.selectedNodes) {
                color = null;
                color = prefs.getFlagsSetting().getColor(n.getBasicBlock().getFlags());
                customized = (color != null);
                NodeWidget nw = (NodeWidget) this.findWidget(n);
                nw.setNodeColor((customized) ? color : prefs.getNodeColor(), customized);
            }
        } else {
            for (CfgNode n : this.selectedNodes) {
                NodeWidget nw = (NodeWidget) this.findWidget(n);
                nw.setNodeColor(color, true);
            }
        }
        this.validate();
    }

    public void setSelectedEdgesVisibility(boolean visible) {
        for (CfgNode n : this.selectedNodes) {
            EdgeSwitchWidget in = this.inputSwitches.get(n);
            EdgeSwitchWidget out = this.outputSwitches.get(n);
            if (in != null) {
                in.changeEdgeVisibility(visible);
            }
            if (out != null) {
                out.changeEdgeVisibility(visible);
            }
        }
        this.validate();
    }

    public EdgeSwitchWidget getInputSwitch(CfgNode n) {
        return this.inputSwitches.get(n);
    }

    public EdgeSwitchWidget getOutputSwitch(CfgNode n) {
        return this.outputSwitches.get(n);
    }

    public CfgEnv getCfgEnv() {
        return env;
    }

    public boolean isLoopClusterVisible() {
        return loopClustersVisible;
    }

    public void setLoopWidgets(boolean visible) {
        for (Widget w : this.loopidx2clusterwidget.values()) {
            w.setVisible(visible);
            w.revalidate();
        }
        this.loopClustersVisible = visible;
        this.validate();
        getPreferences().putBoolean(PREFERENCE_LOOP_CLUSTER, visible);
    }
    
    private void setUseBezierRouter(boolean value) {
        Router router;
        if (value) {
            router = new PolylineRouterV2(new WidgetCollisionCollector() {
                @Override
                public void collectCollisions(List<Widget> collisions) {
                    collisions.addAll(getNodeWidgets());
                }
            });
        } else {
            router = RouterFactory.createDirectRouter();
        }

        for (CfgEdge e : this.getEdges()) {
            EdgeWidget ew = (EdgeWidget) this.findWidget(e);
            ew.setRouter(router);
        }
        this.validate();
        getPreferences().putBoolean(PREFERENCE_ROUTER, value);
    }

    public Collection<NodeWidget> getNodeWidgets() {
        if (nodeWidgets != null && nodeWidgets.size() == this.getNodes().size()) {
            return nodeWidgets;
        }

        List<NodeWidget> widgets = new ArrayList<>();
        for (CfgNode n : this.getNodes()) {
            NodeWidget w = (NodeWidget) this.findWidget(n);
            widgets.add(w);
        }

        nodeWidgets = Collections.unmodifiableList(widgets);
        return widgets;
    }

    public void setSceneLayout(int newLayout) {

        if (currentLayout == newLayout) {
            return;
        }

        GraphLayout<CfgNode, CfgEdge> graphLayout = null;

        switch (newLayout) {
            case CfgEditorContext.LAYOUT_HIERARCHICALNODELAYOUT:
                graphLayout = new HierarchicalNodeLayout(this);
                break;

            case CfgEditorContext.LAYOUT_HIERARCHICALCOMPOUNDLAYOUT:
                graphLayout = new HierarchicalCompoundLayout(this);
                break;
        }

        this.currentLayout = newLayout;
        if (graphLayout != null) {
            this.sceneLayout = LayoutFactory.createSceneGraphLayout(this, graphLayout);
        }
        
        getPreferences().putInt(PREFERENCE_LAYOUT, newLayout);
        sceneLayout.invokeLayoutImmediately();
    }

    @Override
    protected void attachEdgeSourceAnchor(CfgEdge edge, CfgNode oldSourceNode, CfgNode sourceNode) {
        Anchor sourceAnchor;
        EdgeWidget edgeWidget = (EdgeWidget) findWidget(edge);
        Widget sourceWidget = findWidget(sourceNode);

        if (edge.isSymmetric()) {
            sourceAnchor = new SymmetricAnchor(sourceWidget, true, true);
        } else {
            sourceAnchor = AnchorFactory.createRectangularAnchor(sourceWidget);
        }
        edgeWidget.setSourceAnchor(sourceAnchor);
    }

    @Override
    protected void attachEdgeTargetAnchor(CfgEdge edge, CfgNode oldtarget, CfgNode targetNode) {
        Anchor targetAnchor;
        ConnectionWidget edgeWidget = (ConnectionWidget) findWidget(edge);
        Widget targetWidget = findWidget(targetNode);

        if (edge.isSymmetric()) {
            targetAnchor = new SymmetricAnchor(targetWidget, true, false);
        } else {
            targetAnchor = AnchorFactory.createRectangularAnchor(targetWidget);
        }
        edgeWidget.setTargetAnchor(targetAnchor);
    }

    @Override
    protected Widget attachEdgeWidget(CfgEdge edge) {
        EdgeWidget widget = new EdgeWidget(this, edge);
        connectionLayer.addChild(widget);
        attachSourceSwitchWidget(edge);
        attachTargetSwitchWidget(edge);
        return widget;
    }

    @Override
    protected Widget attachNodeWidget(CfgNode node) {
        this.nodeWidgets = null;

        NodeWidget nw = new NodeWidget(this, node);
        WidgetAction.Chain actions = nw.getActions();
        actions.addAction(this.contextPopupAction);
        actions.addAction(this.moveAction);
        actions.addAction(this.createObjectHoverAction());

        if (node.isLoopMember()) {
            LoopClusterWidget loopWidget = this.attachLoopMember(node);
            loopWidget.addMember(nw);
        }
        mainLayer.addChild(nw);
        return nw;
    }

    private LoopClusterWidget attachLoopMember(CfgNode node) {
        LoopClusterWidget lw = this.loopidx2clusterwidget.get(node.getLoopIndex());
        if (lw == null) {
            lw = new LoopClusterWidget(this, node.getLoopDepth(), node.getLoopIndex());
            this.loopidx2clusterwidget.put(node.getLoopIndex(), lw);
            this.clusterLayer.addChild(lw);
        }
        return lw;
    }

    private boolean detachLoopMember(CfgNode node, NodeWidget nodeWidget) {
        LoopClusterWidget rm = this.loopidx2clusterwidget.get(node.getLoopIndex());
        if (rm == null) {
            return false;//not added
        }
        if (rm.removeMember(nodeWidget)) {
            if (rm.getMembers().isEmpty()) {
                this.loopidx2clusterwidget.remove(rm.getLoopIndex());
                List<Widget> childs = new ArrayList<>(rm.getChildren());
                for (Widget w : childs) {//append stacked loopwidgets
                    w.removeFromParent();
                    rm.getParentWidget().addChild(w);
                }
                rm.removeFromParent();
            }
            return true;
        }
        return false;
    }

    //this function is not invoked by any class  of the module
    //however to ensure that the edge switches are treatet corretly
    //when a future version removes nodes it was implemented too.
    @Override
    protected void detachNodeWidget(CfgNode node, Widget nodeWidget) {
        if (node.isLoopMember() && nodeWidget instanceof NodeWidget) {
            this.detachLoopMember(node, (NodeWidget) nodeWidget);
        }
        super.detachNodeWidget(node, nodeWidget);
        assert nodeWidget.getParentWidget() == null;
        if (this.inputSwitches.containsKey(node)) {
            EdgeSwitchWidget esw = this.inputSwitches.remove(node);
            this.connectionLayer.removeChild(esw);
        }
        if (this.outputSwitches.containsKey(node)) {
            EdgeSwitchWidget esw = this.outputSwitches.remove(node);
            this.connectionLayer.removeChild(esw);
        }
    }

    protected EdgeSwitchWidget attachSourceSwitchWidget(CfgEdge e) {
        CfgNode sourceNode = e.getSourceNode();
        NodeWidget sourceWidget = (NodeWidget) this.findWidget(sourceNode);
        EdgeSwitchWidget out = outputSwitches.get(sourceNode);
        if (out == null) {
            out = new EdgeSwitchWidget(this, sourceWidget, true);
            this.connectionLayer.addChild(out);
            outputSwitches.put(sourceNode, out);
        }
        return out;
    }

    protected EdgeSwitchWidget attachTargetSwitchWidget(CfgEdge e) {
        CfgNode targetNode = e.getTargetNode();
        NodeWidget targetWidget = (NodeWidget) this.findWidget(targetNode);
        EdgeSwitchWidget in = inputSwitches.get(targetNode);
        if (in == null) {
            in = new EdgeSwitchWidget(this, targetWidget, false);
            this.connectionLayer.addChild(in);
            inputSwitches.put(targetNode, in);
        }
        return in;
    }

    //resets the selection state of all NodeWidgets
    private void cleanNodeSelection() {
        if (!this.selectedNodes.isEmpty()) {
            this.userSelectionSuggested(Collections.<CfgNode>emptySet(), false);
            this.selectedNodes = Collections.<CfgNode>emptySet();
            this.validate();
        }
    }

    //sets the scene & global node selection
    public void setNodeSelection(Set<CfgNode> newSelection) {
        this.setSceneSelection(newSelection);
        this.updateGlobalSelection();
    }

    //sets the scene selection
    private void setSceneSelection(Set<CfgNode> newSelection) {
        if (newSelection.equals(selectedNodes)) {
            return;
        }

        this.selectedNodes = newSelection;

        Set<Object> selectedObjects = new HashSet<>();

        for (CfgNode n : newSelection) {
            selectedObjects.addAll(this.findNodeEdges(n, true, true));
        }
        selectedObjects.addAll(newSelection);

        //if the selection gets updated from a change in the block view
        //the scene will be centered
        if (selectionUpdating) {
            this.centerSelection();
        }

        this.userSelectionSuggested(selectedObjects, false);
        this.validate();
    }

    //updates selection of Block View
    public void updateGlobalSelection() {
        // TODO(tw): Add selection management.
//        Selection selection = SelectionManager.getDefault().getCurSelection();
//        ArrayList<BasicBlock> newBlocks = new ArrayList<BasicBlock>();
//        for (CfgNode n : this.selectedNodes) {
//            newBlocks.add(n.getBasicBlock());
//        }
//        BasicBlock[] curBlocks = newBlocks.toArray(new BasicBlock[newBlocks.size()]);     
//        selection.put(curBlocks);
    }
    private boolean selectionUpdating = false;

    //change of blockview selection   
    public void stateChanged(ChangeEvent event) {
        if (selectionUpdating) {
            return;
        }

        selectionUpdating = true;

        Object source = event.getSource();
//        if(source instanceof Selection){
//            Selection selection=(Selection) source;
//            Set<CfgNode> newSelection = new HashSet<CfgNode>();
//            BasicBlock[] newBlocks = selection.get(BasicBlock[].class);                  
//            if (newBlocks != null) {
//                for(BasicBlock b : newBlocks){
//                    for(CfgNode n : this.getNodes()){
//                        if(n.getBasicBlock() == b) newSelection.add(n);
//                    }
//                }
//                this.setSceneSelection(newSelection);                               
//            } 
//        }       
        selectionUpdating = false;
        // TODO(tw): Add selection management.
    }

    //centers the viewport on the currently selected nodewidgets
    private void centerSelection() {
        Point sceneCenter = null;
        Collection<CfgNode> nodes = this.selectedNodes;
        if (nodes.size() == 0) {
            nodes = this.getNodes();
        }

        for (CfgNode n : nodes) {
            if (sceneCenter == null) {
                sceneCenter = this.findWidget(n).getLocation();
                continue;
            }
            Point location = this.findWidget(n).getLocation();
            sceneCenter.x = (location.x + sceneCenter.x) / 2;
            sceneCenter.y = (location.y + sceneCenter.y) / 2;
        }

        JComponent view = this.getView();
        if (view != null) {
            Rectangle viewBounds = view.getVisibleRect();

            Point viewCenter = this.convertSceneToView(sceneCenter);

            view.scrollRectToVisible(new Rectangle(
                    viewCenter.x - viewBounds.width / 2,
                    viewCenter.y - viewBounds.height / 2,
                    viewBounds.width,
                    viewBounds.height));
        }
    }

    public void zoomScene() {
        JScrollPane pane = scrollPane;
        Rectangle prefBounds = this.getPreferredBounds();
        Dimension viewDim = pane.getViewportBorderBounds().getSize();

        double realwidth = (double) prefBounds.width * this.getZoomFactor();
        double realheight = (double) prefBounds.height * this.getZoomFactor();

        double zoomX = (double) viewDim.width / realwidth;
        double zoomY = (double) viewDim.height / realheight;
        double zoomFactor = Math.min(zoomX, zoomY);
        this.setZoomFactor(zoomFactor * 0.9);
    }

    //Enables Antialiasing
    @Override
    public void paintChildren() {
        Object anti = getGraphics().getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        Object textAnti = getGraphics().getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);

        getGraphics().setRenderingHint(
                RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        getGraphics().setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        super.paintChildren();

        getGraphics().setRenderingHint(RenderingHints.KEY_ANTIALIASING, anti);
        getGraphics().setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, textAnti);
    }

    //select provider for node selection
    private RectangularSelectProvider createRectangularSelectProvider() {
        return new RectangularSelectProvider() {

            public void performSelection(Rectangle rectangle) {
                HashSet<CfgNode> set = new HashSet<>();

                //change to a rectangle with (x,offsetY) at the top left
                if (rectangle.width < 0) {
                    rectangle.x = rectangle.x + rectangle.width;
                    rectangle.width *= -1;
                }
                if (rectangle.height < 0) {
                    rectangle.y = rectangle.y + rectangle.height;
                    rectangle.height *= -1;
                }

                for (NodeWidget n : getNodeWidgets()) {
                    Point p = n.getLocation();
                    if (p != null && rectangle.contains(p)) {
                        set.add(n.getNodeModel());
                    }
                }
                setNodeSelection(set);
            }
        };
    }

    //select decorator for node selection
    private RectangularSelectDecorator createSelectDecorator(final CfgScene scene) {
        return new RectangularSelectDecorator() {

            public Widget createSelectionWidget() {
                scene.cleanNodeSelection();//unselected all nodes
                scene.revalidate();
                return new SelectionWidget(getScene());
            }
        };
    }

    private MoveProvider createMoveProvider() {
        return new MoveProvider() {

            private HashMap<Widget, Point> originals = new HashMap<>();
            private Point original = null;

            public void movementStarted(Widget widget) {
                originals.clear();
                NodeWidget nw = (NodeWidget) widget;
                if (selectedNodes.contains(nw.getNodeModel())) {//move current selection
                    for (CfgNode n : selectedNodes) {
                        Widget w = findWidget(n);
                        originals.put(w, w.getLocation());
                    }
                } else {//a newly-selected node will be moved               
                    CfgNode n = nw.getNodeModel();
                    HashSet<CfgNode> selectedNode = new HashSet<>(1);
                    selectedNode.add(n);
                    setNodeSelection(selectedNode);
                    originals.put(widget, widget.getPreferredLocation());
                    widget.revalidate();
                    validate();

                }
            }

            public void movementFinished(Widget widget) {
                NodeWidget nw = (NodeWidget) widget;
                if (selectedNodes.contains(nw.getNodeModel())) {
                    return;//to be able to move the current selection
                }

                HashSet<CfgNode> selectedNode = new HashSet<>(1);
                selectedNode.add(nw.getNodeModel());
                setNodeSelection(selectedNode);
                originals.clear();
                original = null;

            }

            public Point getOriginalLocation(Widget widget) {
                if (original == null) {
                    original = widget.getLocation();
                }

                return original;
            }
            //todo : find a cache algorithm which only routes edges
            //which are intersected by bounds of the moved rectangle

            public void setNewLocation(Widget widget, Point location) {
                Point org = getOriginalLocation(widget);
                int dx = location.x - org.x;
                int dy = location.y - org.y;
                for (Map.Entry<Widget, Point> entry : originals.entrySet()) {
                    Point point = entry.getValue();
                    entry.getKey().setPreferredLocation(new Point(point.x + dx, point.y + dy));
                }
                for (CfgEdge e : getEdges()) {
                    EdgeWidget ew = (EdgeWidget) findWidget(e);
                    if (ew.isVisible()) {
                        ew.reroute();
                    }
                }
            }
        };
    }

    private WidgetAction createContextMenuAction(final CfgScene scene) {
        return ActionFactory.createPopupMenuAction(new PopupMenuProvider() {

            public JPopupMenu getPopupMenu(Widget widget, Point point) {
                JPopupMenu menu = new JPopupMenu();
                NodeWidget nw = null;
                if (widget instanceof NodeWidget) {
                    nw = (NodeWidget) widget;
                    if (!selectedNodes.contains(nw.getNodeModel())) {
                        HashSet<CfgNode> selectedNode = new HashSet<>(1);
                        selectedNode.add(nw.getNodeModel());
                        setNodeSelection(selectedNode);
                    }
                } else if (scene.getSelectedNodes().size() == 1) {
                    nw = (NodeWidget) scene.findWidget(scene.getSelectedNodes().iterator().next());
                }

                if (nw != null) {
                    CfgNode node = nw.getNodeModel();
                    ArrayList<CfgNode> successors = new ArrayList<>();
                    ArrayList<CfgNode> predecessors = new ArrayList<>();
                    for (CfgEdge e : node.getOutputEdges()) {
                        successors.add(e.getTargetNode());
                    }
                    for (CfgEdge e : node.getInputEdges()) {
                        predecessors.add(e.getSourceNode());
                    }

                    if (predecessors.size() > 0) {
                        Collections.sort(predecessors, new NodeNameComparator());
                        JMenu predmenu = new JMenu("Go to predecessor");
                        for (CfgNode n : predecessors) {
                            GotoNodeAction action = new GotoNodeAction(n);
                            predmenu.add(action);
                        }
                        menu.add(predmenu);
                    }
                    if (successors.size() > 0) {
                        Collections.sort(successors, new NodeNameComparator());
                        JMenu succmenu = new JMenu("Go to successor");
                        for (CfgNode n : successors) {
                            GotoNodeAction action = new GotoNodeAction(n);
                            succmenu.add(action);
                        }
                        menu.add(succmenu);
                    }
                    if (successors.size() > 0 || predecessors.size() > 0) {
                        menu.addSeparator();
                    }
                }

                return menu;
            }
        });
    }

    @Override
    public void zoomIn() {
        this.setZoomFactor(1.1);
        this.validate();
    }

    @Override
    public void zoomOut() {
        this.setZoomFactor(0.9);
        this.validate();
    }

    @Override
    public void showAll() {
        this.zoomScene();
        this.validate();
    }

    private class NodeNameComparator implements Comparator<CfgNode> {

        public int compare(CfgNode node1, CfgNode node2) {
            String name1 = node1.getBasicBlock().getName().substring(1);
            String name2 = node2.getBasicBlock().getName().substring(1);
            Integer blocknum1 = Integer.parseInt(name1);
            Integer blocknum2 = Integer.parseInt(name2);
            return blocknum1.compareTo(blocknum2);
        }
    }

    private class GotoNodeAction extends AbstractAction {

        CfgNode node;

        GotoNodeAction(CfgNode node) {
            super(node.getBasicBlock().getName());
            this.node = node;
        }

        public void actionPerformed(ActionEvent e) {
            Set<CfgNode> nodes = new HashSet<>(1);
            nodes.add(node);
            setNodeSelection(nodes);
            centerSelection();
        }
    }

    private SceneListener createSceneListener(final CfgScene scene) {
        return new SceneListener() {

            public void sceneRepaint() {
            }

            public void sceneValidating() {
            }

            public void sceneValidated() {
                if (scene.isLoopClusterVisible()) { //update only if visible
                    for (LoopClusterWidget cw : getLoopidx2clusterwidget().values()) {
                        cw.updateClusterBounds();
                    }
                }
                for (EdgeSwitchWidget esw : inputSwitches.values()) {
                    esw.updatePosition();
                }

                for (EdgeSwitchWidget esw : outputSwitches.values()) {
                    esw.updatePosition();
                }
            }
        };
    }
}
