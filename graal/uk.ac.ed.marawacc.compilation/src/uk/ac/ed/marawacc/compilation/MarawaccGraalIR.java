package uk.ac.ed.marawacc.compilation;

import java.util.HashMap;

import com.oracle.graal.nodes.StructuredGraph;

public class MarawaccGraalIR {

    private static MarawaccGraalIR _instance;

    // Graph ID -> CallTargetID
    private HashMap<Long, Long> graphsTable;

    // CallTargetID -> StructuredGraph
    private HashMap<Long, StructuredGraph> compilationTable;

    public static MarawaccGraalIR getInstance() {
        if (_instance == null) {
            _instance = new MarawaccGraalIR();
        }
        return _instance;
    }

    private MarawaccGraalIR() {
        compilationTable = new HashMap<>();
        graphsTable = new HashMap<>();
    }

    public void insertCallTargetID(long idGraph, long idCallTarget) {
        graphsTable.put(idGraph, idCallTarget);
    }

    public boolean isCompiledGraph(long graphID) {
        return graphsTable.containsKey(graphID);
    }

    public boolean isInCompilationTable(long graphID) {
        Long idCallTarget = graphsTable.get(graphID);
        if (idCallTarget != null && !compilationTable.containsKey(idCallTarget)) {
            return false;
        }
        return true;
    }

    public boolean updateGraph(StructuredGraph graph) {
        Long idCallTarget = graphsTable.get(graph.graphId());
        if (idCallTarget != null && !compilationTable.containsKey(idCallTarget)) {
            compilationTable.put(idCallTarget, (StructuredGraph) graph.copy());
            return true;
        }
        return false;
    }

    public StructuredGraph getCompiledGraph(long idCallTarget) {
        if (compilationTable.containsKey(idCallTarget)) {
            return compilationTable.get(idCallTarget);
        } else {
            return null;
        }
    }

    public void printInfo() {
        System.out.println(graphsTable);
        System.out.println(compilationTable);
    }

}
