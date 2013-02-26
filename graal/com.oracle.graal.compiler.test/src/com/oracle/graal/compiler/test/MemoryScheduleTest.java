/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.compiler.test;

import java.util.concurrent.*;

import org.junit.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.phases.schedule.*;

/**
 * In these test the FrameStates are explicitly cleared out, so that the scheduling of
 * FloatingReadNodes depends solely on the scheduling algorithm. The FrameStates normally keep the
 * FloatingReadNodes above a certain point, so that they (most of the time...) magically do the
 * right thing.
 * 
 * The scheduling shouldn't depend on FrameStates, which is tested by this class.
 */
public class MemoryScheduleTest extends GraphScheduleTest {

    private static enum TestMode {
        WITH_FRAMESTATES, WITHOUT_FRAMESTATES
    }

    public static class Container {

        public int a;
        public int b;
        public int c;
    }

    private static final Container container = new Container();

    public static int testSimpleSnippet() {
        // in this test the read should be scheduled before the write
        try {
            return container.a;
        } finally {
            container.a = 15;
        }
    }

    @Test
    public void testSimple() {
        for (TestMode mode : TestMode.values()) {
            SchedulePhase schedule = getFinalSchedule("testSimpleSnippet", mode);
            assertReadAfterWrite(schedule, false);
        }
    }

    /**
     * In this case the read should be scheduled in the first block.
     */
    public static int testSplitSnippet1(int a) {
        try {
            return container.a;
        } finally {
            if (a < 0) {
                container.a = 15;
            } else {
                container.b = 15;
            }
        }
    }

    @Test
    public void testSplit1() {
        for (TestMode mode : TestMode.values()) {
            SchedulePhase schedule = getFinalSchedule("testSplitSnippet1", mode);
            assertReadWithinStartBlock(schedule, true);
        }
    }

    /**
     * Here the read should float to the end.
     */
    public static int testSplit2Snippet(int a) {
        try {
            return container.a;
        } finally {
            if (a < 0) {
                container.c = 15;
            } else {
                container.b = 15;
            }
        }
    }

    @Test
    public void testSplit2() {
        SchedulePhase schedule = getFinalSchedule("testSplit2Snippet", TestMode.WITHOUT_FRAMESTATES);
        assertReadWithinStartBlock(schedule, false);
    }

    /**
     * Here the read should not float to the end.
     */
    public static int testLoop1Snippet(int a, int b) {
        try {
            return container.a;
        } finally {
            for (int i = 0; i < a; i++) {
                if (b < 0) {
                    container.b = 10;
                } else {
                    container.a = 15;
                }
            }
        }
    }

    @Test
    public void testLoop1() {
        SchedulePhase schedule = getFinalSchedule("testLoop1Snippet", TestMode.WITHOUT_FRAMESTATES);
        assertEquals(7, schedule.getCFG().getBlocks().length);
        assertReadWithinStartBlock(schedule, true);
    }

    /**
     * Here the read should float to the end.
     */
    public static int testLoop2Snippet(int a, int b) {
        try {
            return container.a;
        } finally {
            for (int i = 0; i < a; i++) {
                if (b < 0) {
                    container.b = 10;
                } else {
                    container.c = 15;
                }
            }
        }
    }

    @Test
    public void testLoop2() {
        SchedulePhase schedule = getFinalSchedule("testLoop2Snippet", TestMode.WITHOUT_FRAMESTATES);
        assertEquals(7, schedule.getCFG().getBlocks().length);
        assertReadWithinStartBlock(schedule, false);
    }

    private void assertReadAfterWrite(SchedulePhase schedule, boolean readAfterWrite) {
        boolean writeEncountered = false;
        assertEquals(1, schedule.getCFG().getBlocks().length);
        for (Node node : schedule.getBlockToNodesMap().get(schedule.getCFG().getStartBlock())) {
            if (node instanceof WriteNode) {
                writeEncountered = true;
            } else if (node instanceof FloatingReadNode) {
                assertEquals(readAfterWrite, writeEncountered);
            }
        }
    }

    private void assertReadWithinStartBlock(SchedulePhase schedule, boolean withinStartBlock) {
        boolean readEncountered = false;
        for (Node node : schedule.getBlockToNodesMap().get(schedule.getCFG().getStartBlock())) {
            if (node instanceof FloatingReadNode) {
                readEncountered = true;
            }
        }
        assertEquals(withinStartBlock, readEncountered);
    }

    private SchedulePhase getFinalSchedule(final String snippet, final TestMode mode) {
        return Debug.scope("FloatingReadTest", new DebugDumpScope(snippet), new Callable<SchedulePhase>() {

            @Override
            public SchedulePhase call() throws Exception {
                StructuredGraph graph = parse(snippet);
                if (mode == TestMode.WITHOUT_FRAMESTATES) {
                    for (Node node : graph.getNodes()) {
                        if (node instanceof StateSplit) {
                            FrameState stateAfter = ((StateSplit) node).stateAfter();
                            if (stateAfter != null) {
                                ((StateSplit) node).setStateAfter(null);
                                GraphUtil.killWithUnusedFloatingInputs(stateAfter);
                            }
                        }
                    }
                }
                new LoweringPhase(null, runtime(), new Assumptions(false)).apply(graph);
                new FloatingReadPhase().apply(graph);

                SchedulePhase schedule = new SchedulePhase();
                schedule.apply(graph);
                return schedule;
            }
        });
    }
}
