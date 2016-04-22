package uk.ac.ed.marawacc.compilation;

import java.util.concurrent.ConcurrentHashMap;

import com.oracle.graal.nodes.StructuredGraph;

public class MarawaccGraalIR {

    private static MarawaccGraalIR INSTANCE;

    // CallTargetID -> StructuredGraph
    private ConcurrentHashMap<Long, StructuredGraph> compilationTable;

    // Graph ID -> CallTargetID
    private ConcurrentHashMap<Long, Long> graphsTable;

    public static MarawaccGraalIR getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new MarawaccGraalIR();
        }
        return INSTANCE;
    }

    private MarawaccGraalIR() {
        compilationTable = new ConcurrentHashMap<>();
        graphsTable = new ConcurrentHashMap<>();
    }

    public synchronized void insertCallTargetID(StructuredGraph graph, long idCallTarget) {
        graphsTable.put(graph.graphId(), idCallTarget);
    }

    public boolean isCompiledGraph(long graphID) {
        return graphsTable.containsKey(graphID);
    }

    public synchronized void updateGraph(StructuredGraph graph) {
        System.out.println("INSERTING GRAPH INTO THE COMPILATION TABLE: " + graph);
        Long idCallTarget = graphsTable.get(graph.graphId());
        if (idCallTarget != null) {
            System.out.println("\n\t >> INSERTING: " + idCallTarget + " -- " + graph.graphId());
            compilationTable.put(idCallTarget, graph);
            System.out.println(compilationTable);
        } else {
            throw new RuntimeException("Update graph not valid");
        }
    }

    public StructuredGraph getCompiledGraph(StructuredGraph graph) {
        Long idCallTarget = graphsTable.get(graph.graphId());
        if (idCallTarget != null) {
            return getCompiledGraph(idCallTarget);
        }
        return null;
    }

    public StructuredGraph getCompiledGraph(long idCallTarget) {
        if (compilationTable.containsKey(idCallTarget)) {
            return compilationTable.get(idCallTarget);
        } else {
            return null;
        }
    }

    public void clean() {
        compilationTable.clear();
        graphsTable.clear();
    }
}
