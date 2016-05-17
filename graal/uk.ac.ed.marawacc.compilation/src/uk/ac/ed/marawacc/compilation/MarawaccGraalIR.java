package uk.ac.ed.marawacc.compilation;

import java.util.HashMap;

import com.oracle.graal.nodes.StructuredGraph;

public class MarawaccGraalIR {

    private static MarawaccGraalIR _instance;

    private static int counter = 0;

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

    public int getCounter() {
        return counter;
    }

    public void insertCallTargetID(long idGraph, long idCallTarget) {
        graphsTable.put(idGraph, idCallTarget);
    }

    public boolean isCompiledGraph(long graphID) {
        return graphsTable.containsKey(graphID);
    }

    public boolean isInCompilationTable(long graphID) {
        Long idCallTarget = graphsTable.get(graphID);
        if ((idCallTarget != null) && !compilationTable.containsKey(idCallTarget)) {
            return false;
        } else if (idCallTarget == null) {
            return false;
        }
        return true;
    }

    public boolean updateGraph(StructuredGraph graph) {
        Long idCallTarget = graphsTable.get(graph.graphId());
        if (idCallTarget != null && !compilationTable.containsKey(idCallTarget)) {
            System.out.println("[ASTX] UPDATING THE GRAPH");
            compilationTable.put(idCallTarget, (StructuredGraph) graph.copy());
            counter++;
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
