/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.salver.dumper;

import static com.oracle.graal.compiler.common.GraalOptions.PrintGraphProbabilities;
import static com.oracle.graal.compiler.common.GraalOptions.PrintIdealGraphSchedule;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.oracle.graal.compiler.common.Fields;
import com.oracle.graal.compiler.common.cfg.BlockMap;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.graph.Edges;
import com.oracle.graal.graph.Edges.Type;
import com.oracle.graal.graph.Graph;
import com.oracle.graal.graph.InputEdges;
import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.graph.NodeList;
import com.oracle.graal.graph.NodeMap;
import com.oracle.graal.graph.iterators.NodeIterable;
import com.oracle.graal.nodes.AbstractBeginNode;
import com.oracle.graal.nodes.AbstractEndNode;
import com.oracle.graal.nodes.AbstractMergeNode;
import com.oracle.graal.nodes.ControlSinkNode;
import com.oracle.graal.nodes.ControlSplitNode;
import com.oracle.graal.nodes.FixedNode;
import com.oracle.graal.nodes.PhiNode;
import com.oracle.graal.nodes.ProxyNode;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.VirtualState;
import com.oracle.graal.nodes.cfg.Block;
import com.oracle.graal.nodes.cfg.ControlFlowGraph;
import com.oracle.graal.phases.schedule.SchedulePhase;
import com.oracle.graal.salver.data.DataDict;
import com.oracle.graal.salver.data.DataList;

public class GraphDumper extends AbstractMethodScopeDumper {

    public static final String EVENT_NAMESPACE = "graal/graph";

    private static final Map<Class<?>, String> nodeClassCategoryMap;

    static {
        nodeClassCategoryMap = new LinkedHashMap<>();
        nodeClassCategoryMap.put(ControlSinkNode.class, "ControlSink");
        nodeClassCategoryMap.put(ControlSplitNode.class, "ControlSplit");
        nodeClassCategoryMap.put(AbstractMergeNode.class, "Merge");
        nodeClassCategoryMap.put(AbstractBeginNode.class, "Begin");
        nodeClassCategoryMap.put(AbstractEndNode.class, "End");
        nodeClassCategoryMap.put(FixedNode.class, "Fixed");
        nodeClassCategoryMap.put(VirtualState.class, "State");
        nodeClassCategoryMap.put(PhiNode.class, "Phi");
        nodeClassCategoryMap.put(ProxyNode.class, "Proxy");
        // nodeClassCategoryMap.put(Object.class, "Floating");
    }

    @Override
    public void beginDump() throws IOException {
        beginDump(EVENT_NAMESPACE);
    }

    @SuppressWarnings("try")
    public void dump(Graph graph, String msg) throws IOException {
        resolveMethodContext();

        try (Scope s = Debug.sandbox(getClass().getSimpleName(), null)) {
            SchedulePhase predefinedSchedule = null;
            for (Object obj : Debug.context()) {
                if (obj instanceof SchedulePhase) {
                    predefinedSchedule = (SchedulePhase) obj;
                }
            }
            processGraph(graph, msg, predefinedSchedule);
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
    }

    private void processGraph(Graph graph, String name, SchedulePhase predefinedSchedule) throws IOException {
        SchedulePhase schedule = predefinedSchedule;
        if (schedule == null) {
            // Also provide a schedule when an error occurs
            if (PrintIdealGraphSchedule.getValue() || Debug.contextLookup(Throwable.class) != null) {
                if (graph instanceof StructuredGraph) {
                    schedule = new SchedulePhase();
                    schedule.apply((StructuredGraph) graph);
                }
            }
        }

        ControlFlowGraph cfg = null;
        List<Block> blocks = null;
        NodeMap<Block> nodeToBlock = null;
        BlockMap<List<Node>> blockToNodes = null;

        if (schedule != null) {
            cfg = schedule.getCFG();
            if (cfg != null) {
                blocks = cfg.getBlocks();
                nodeToBlock = schedule.getNodeToBlockMap();
                blockToNodes = schedule.getBlockToNodesMap();
            }
        }

        DataDict dataDict = new DataDict();
        dataDict.put("id", nextItemId());
        dataDict.put("name", name);

        DataDict graphDict = new DataDict();
        dataDict.put("graph", graphDict);

        processNodes(graphDict, graph.getNodes(), nodeToBlock, cfg);

        if (blocks != null && blockToNodes != null) {
            processBlocks(graphDict, blocks, blockToNodes);
        }
        serializeAndFlush(createEventDictWithId("graph", dataDict));
    }

    private static void processNodes(DataDict graphDict, NodeIterable<Node> nodes, NodeMap<Block> nodeToBlock, ControlFlowGraph cfg) {
        Map<NodeClass<?>, Integer> classMap = new HashMap<>();

        DataList classList = new DataList();
        graphDict.put("classes", classList);

        DataList nodeList = new DataList();
        graphDict.put("nodes", nodeList);

        DataList edgeList = new DataList();
        graphDict.put("edges", edgeList);

        for (Node node : nodes) {
            NodeClass<?> nodeClass = node.getNodeClass();

            DataDict nodeDict = new DataDict();
            nodeDict.put("id", getNodeId(node));
            nodeDict.put("class", getNodeClassId(classMap, classList, nodeClass));

            if (nodeToBlock != null) {
                if (nodeToBlock.isNew(node)) {
                    nodeDict.put("block", -1);
                } else {
                    Block block = nodeToBlock.get(node);
                    if (block != null) {
                        nodeDict.put("block", block.getId());
                    }
                }
            }

            if (cfg != null && PrintGraphProbabilities.getValue() && node instanceof FixedNode) {
                try {
                    nodeDict.put("probability", cfg.blockFor(node).probability());
                } catch (Throwable t) {
                    nodeDict.put("probability", t);
                }
            }

            Map<Object, Object> debugProperties = node.getDebugProperties();
            if (!debugProperties.isEmpty()) {
                DataDict propertyDict = new DataDict();
                nodeDict.put("properties", propertyDict);
                for (Map.Entry<Object, Object> entry : debugProperties.entrySet()) {
                    propertyDict.put(entry.getKey().toString(), entry.getValue());
                }
            }

            nodeList.add(nodeDict);
            appendEdges(edgeList, node, Type.Inputs);
            appendEdges(edgeList, node, Type.Successors);
        }
    }

    private static void processBlocks(DataDict graphDict, List<Block> blocks, BlockMap<List<Node>> blockToNodes) {
        DataList blockList = new DataList();
        graphDict.put("blocks", blockList);

        for (Block block : blocks) {
            List<Node> nodes = blockToNodes.get(block);
            if (nodes != null) {
                DataDict blockDict = new DataDict();
                blockDict.put("id", block.getId());

                DataList nodeList = new DataList();
                blockDict.put("nodes", nodeList);

                for (Node node : nodes) {
                    nodeList.add(getNodeId(node));
                }

                DataList successorList = new DataList();
                blockDict.put("successors", successorList);
                for (Block successor : block.getSuccessors()) {
                    successorList.add(successor.getId());
                }

                blockList.add(blockDict);
            }
        }
    }

    private static void appendEdges(DataList edgeList, Node node, Edges.Type type) {
        NodeClass<?> nodeClass = node.getNodeClass();

        Edges edges = nodeClass.getEdges(type);
        final long[] curOffsets = edges.getOffsets();

        for (int i = 0; i < edges.getDirectCount(); i++) {
            Node other = Edges.getNode(node, curOffsets, i);
            if (other != null) {
                DataDict edgeDict = new DataDict();

                DataDict nodeDict = new DataDict();
                nodeDict.put("node", getNodeId(node));
                nodeDict.put("field", edges.getName(i));

                edgeDict.put("from", type == Type.Inputs ? getNodeId(other) : nodeDict);
                edgeDict.put("to", type == Type.Inputs ? nodeDict : getNodeId(other));
                edgeList.add(edgeDict);
            }
        }
        for (int i = edges.getDirectCount(); i < edges.getCount(); i++) {
            NodeList<Node> list = Edges.getNodeList(node, curOffsets, i);
            if (list != null) {
                for (int index = 0; index < list.size(); index++) {
                    Node other = list.get(index);
                    if (other != null) {
                        DataDict edgeDict = new DataDict();

                        DataDict nodeDict = new DataDict();
                        nodeDict.put("node", getNodeId(node));
                        nodeDict.put("field", edges.getName(i));
                        nodeDict.put("index", index);

                        edgeDict.put("from", type == Type.Inputs ? getNodeId(other) : nodeDict);
                        edgeDict.put("to", type == Type.Inputs ? nodeDict : getNodeId(other));
                        edgeList.add(edgeDict);
                    }
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static int getNodeId(Node node) {
        return node != null ? node.getId() : -1;
    }

    private static int getNodeClassId(Map<NodeClass<?>, Integer> classMap, DataList classList, NodeClass<?> nodeClass) {
        if (classMap.containsKey(nodeClass)) {
            return classMap.get(nodeClass);
        }
        int classId = classMap.size();
        classMap.put(nodeClass, classId);

        Class<?> javaClass = nodeClass.getJavaClass();

        DataDict classDict = new DataDict();
        classList.add(classDict);

        classDict.put("id", classId);
        classDict.put("name", nodeClass.getNameTemplate());
        classDict.put("jtype", javaClass.getName());

        String category = getNodeClassCategory(javaClass);
        if (category != null) {
            classDict.put("category", category);
        }

        Object propertyInfo = getPropertyInfo(nodeClass);
        if (propertyInfo != null) {
            classDict.put("properties", propertyInfo);
        }

        Object inputInfo = getEdgeInfo(nodeClass, Type.Inputs);
        if (inputInfo != null) {
            classDict.put("inputs", inputInfo);
        }
        Object successorInfo = getEdgeInfo(nodeClass, Type.Successors);
        if (successorInfo != null) {
            classDict.put("successors", successorInfo);
        }
        return classId;
    }

    private static DataDict getPropertyInfo(NodeClass<?> nodeClass) {
        Fields properties = nodeClass.getData();
        if (properties.getCount() > 0) {
            DataDict propertyInfoDict = new DataDict();
            for (int i = 0; i < properties.getCount(); i++) {
                DataDict propertyDict = new DataDict();
                String name = properties.getName(i);
                propertyDict.put("name", name);
                propertyDict.put("jtype", properties.getType(i).getName());
                propertyInfoDict.put(name, propertyDict);
            }
            return propertyInfoDict;
        }
        return null;
    }

    private static DataDict getEdgeInfo(NodeClass<?> nodeClass, Edges.Type type) {
        DataDict edgeInfoDict = new DataDict();
        Edges edges = nodeClass.getEdges(type);
        for (int i = 0; i < edges.getCount(); i++) {
            DataDict edgeDict = new DataDict();
            String name = edges.getName(i);
            edgeDict.put("name", name);
            edgeDict.put("jtype", edges.getType(i).getName());
            if (type == Type.Inputs) {
                edgeDict.put("type", ((InputEdges) edges).getInputType(i));
            }
            edgeInfoDict.put(name, edgeDict);
        }
        return edgeInfoDict.isEmpty() ? null : edgeInfoDict;
    }

    private static String getNodeClassCategory(Class<?> clazz) {
        for (Map.Entry<Class<?>, String> entry : nodeClassCategoryMap.entrySet()) {
            if (entry.getKey().isAssignableFrom(clazz)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
