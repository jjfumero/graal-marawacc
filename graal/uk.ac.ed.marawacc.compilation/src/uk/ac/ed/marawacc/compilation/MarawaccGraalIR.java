package uk.ac.ed.marawacc.compilation;

import java.util.HashMap;

import com.oracle.graal.nodes.StructuredGraph;

public class MarawaccGraalIR {

    private HashMap<Long, StructuredGraph> compilationTable;
    private HashMap<Long, Long> callTargetsTable;

    public static final MarawaccGraalIR INSTANCE = new MarawaccGraalIR();

    private MarawaccGraalIR() {
        compilationTable = new HashMap<>();
        callTargetsTable = new HashMap<>();
    }

    public void insertCallTargetID(StructuredGraph graph, long idCallTarget) {
        callTargetsTable.put(graph.graphId(), idCallTarget);
    }

    public boolean isCompiledGraph(long graphID) {
        return callTargetsTable.containsKey(graphID);
    }

    public void updateGraph(StructuredGraph graph) {
        Long idCallTarget = callTargetsTable.get(graph.graphId());
        if (idCallTarget != null) {
            compilationTable.put(idCallTarget, graph);
        } else {
            throw new RuntimeException("Update graph not valid");
        }
    }

    public StructuredGraph getCompiledGraph(StructuredGraph graph) {
        Long idCallTarget = callTargetsTable.get(graph.graphId());
        return compilationTable.get(idCallTarget);
    }
}
