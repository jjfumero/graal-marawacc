/*
 * Copyright (c) 2012, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.graal.compiler.cfg;

public class CFGVerifier {
    public static boolean verify(ControlFlowGraph cfg) {
        for (Block block : cfg.getBlocks()) {
            assert cfg.getBlocks()[block.getId()] == block;

            for (Block pred : block.getPredecessors()) {
                assert pred.getSuccessors().contains(block);
                assert pred.getId() < block.getId() || pred.isLoopEnd();
            }

            for (Block sux : block.getSuccessors()) {
                assert sux.getPredecessors().contains(block);
                assert sux.getId() > block.getId() || sux.isLoopHeader();
            }

            if (block.getDominator() != null) {
                assert block.getDominator().getId() < block.getId();
                assert block.getDominator().getDominated().contains(block);
            }
            for (Block dominated : block.getDominated()) {
                assert dominated.getId() > block.getId();
                assert dominated.getDominator() == block;
            }

            assert cfg.getLoops() == null || !block.isLoopHeader() || block.getLoop().header == block;
        }

        if (cfg.getLoops() != null) {
            for (Loop loop : cfg.getLoops()) {
                assert loop.header.isLoopHeader();

                for (Block block : loop.blocks) {
                    assert block.getId() >= loop.header.getId();

                    Loop blockLoop = block.getLoop();
                    while (blockLoop != loop) {
                        blockLoop = blockLoop.parent;
                        assert blockLoop != null;
                    }
                }

                for (Block block : loop.exits) {
                    assert block.getId() >= loop.header.getId();

                    Loop blockLoop = block.getLoop();
                    while (blockLoop != null) {
                        blockLoop = blockLoop.parent;
                        assert blockLoop != loop;
                    }
                }
            }
        }

        return true;
    }
}
