/*
 * Copyright (c) 2013, 2017, The University of Edinburgh. All rights reserved.
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
 */
package uk.ac.ed.marawacc.compilation;

import java.util.HashMap;

import com.oracle.graal.nodes.StructuredGraph;

public final class MarawaccGraalIRCache {

    private static MarawaccGraalIRCache _instance;

    private static int counter = 0;

    // Graph ID -> CallTargetID
    private HashMap<Long, Long> graphsTable;

    // CallTargetID -> StructuredGraph
    private HashMap<Long, StructuredGraph> compilationTable;

    public static MarawaccGraalIRCache getInstance() {
        if (_instance == null) {
            _instance = new MarawaccGraalIRCache();
        }
        return _instance;
    }

    private MarawaccGraalIRCache() {
        compilationTable = new HashMap<>();
        graphsTable = new HashMap<>();
    }

    public static int getCounter() {
        return counter;
    }

    public synchronized void insertCallTargetID(long idGraph, long idCallTarget) {
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

    public synchronized boolean updateGraph(StructuredGraph graph) {
        Long idCallTarget = graphsTable.get(graph.graphId());
        if (idCallTarget != null && !compilationTable.containsKey(idCallTarget)) {
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

    /**
     * It removes the entry from the Compilation and Graphs Table.
     *
     * @param idGraph
     */
    public void deoptimize(long idGraph) {
        compilationTable.remove(idGraph);
        graphsTable.remove(idGraph);
    }

}
