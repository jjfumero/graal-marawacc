package uk.ac.ed.marawacc.compilation;

import java.util.HashMap;

import com.oracle.graal.nodes.StructuredGraph;

public class MarawaccGraalIR {

    public static final MarawaccGraalIR INSTANCE = new MarawaccGraalIR();

    // CallTargetID -> StructuredGraph
    private HashMap<Long, StructuredGraph> compilationTable;

    // Graph ID -> CallTargetID
    private HashMap<Long, Long> graphsTable;

    private MarawaccGraalIR() {
        compilationTable = new HashMap<>();
        graphsTable = new HashMap<>();
    }

    public void insertCallTargetID(StructuredGraph graph, long idCallTarget) {
        graphsTable.put(graph.graphId(), idCallTarget);
    }

    public boolean isCompiledGraph(long graphID) {
        return graphsTable.containsKey(graphID);
    }

    public void updateGraph(StructuredGraph graph) {
        Long idCallTarget = graphsTable.get(graph.graphId());
        if (idCallTarget != null) {
            compilationTable.put(idCallTarget, graph);
        } else {
            throw new RuntimeException("Update graph not valid");
        }
    }

    public StructuredGraph getCompiledGraph(StructuredGraph graph) {
        Long idCallTarget = graphsTable.get(graph.graphId());
        return compilationTable.get(idCallTarget);
    }

    public StructuredGraph getCompiledGraph(long callTargetID) {
        return compilationTable.get(callTargetID);
    }

    public void clean() {
        compilationTable.clear();
        graphsTable.clear();
    }
}
