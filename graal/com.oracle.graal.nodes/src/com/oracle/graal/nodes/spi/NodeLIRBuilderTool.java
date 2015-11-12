/*
 * Copyright (c) 2011, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.spi;

import java.util.Collection;
import java.util.List;

import jdk.vm.ci.code.BytecodePosition;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.InfopointReason;
import jdk.vm.ci.meta.Value;

import com.oracle.graal.compiler.common.cfg.BlockMap;
import com.oracle.graal.compiler.common.type.Stamp;
import com.oracle.graal.graph.Node;
import com.oracle.graal.lir.LIRFrameState;
import com.oracle.graal.lir.gen.LIRGeneratorTool;
import com.oracle.graal.nodes.AbstractBeginNode;
import com.oracle.graal.nodes.AbstractEndNode;
import com.oracle.graal.nodes.AbstractMergeNode;
import com.oracle.graal.nodes.BreakpointNode;
import com.oracle.graal.nodes.DeoptimizingNode;
import com.oracle.graal.nodes.FullInfopointNode;
import com.oracle.graal.nodes.IfNode;
import com.oracle.graal.nodes.Invoke;
import com.oracle.graal.nodes.LoopEndNode;
import com.oracle.graal.nodes.SafepointNode;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.calc.ConditionalNode;
import com.oracle.graal.nodes.cfg.Block;
import com.oracle.graal.nodes.extended.SwitchNode;

public interface NodeLIRBuilderTool extends NodeValueMap {

    // TODO (je) remove and move into the Node
    LIRFrameState state(DeoptimizingNode deopt);

    void emitIf(IfNode i);

    void emitConditional(ConditionalNode i);

    void emitSwitch(SwitchNode i);

    void emitInvoke(Invoke i);

    // Handling of block-end nodes still needs to be unified in the LIRGenerator.
    void visitMerge(AbstractMergeNode i);

    void visitEndNode(AbstractEndNode i);

    void visitLoopEnd(LoopEndNode i);

    // These methods define the contract a runtime specific backend must provide.

    void visitSafepointNode(SafepointNode i);

    void visitBreakpointNode(BreakpointNode i);

    void visitFullInfopointNode(FullInfopointNode i);

    void recordSimpleInfopoint(InfopointReason reason, BytecodePosition position);

    LIRGeneratorTool getLIRGeneratorTool();

    void emitOverflowCheckBranch(AbstractBeginNode overflowSuccessor, AbstractBeginNode next, Stamp compareStamp, double probability);

    Value[] visitInvokeArguments(CallingConvention cc, Collection<ValueNode> arguments);

    void doBlock(Block block, StructuredGraph graph, BlockMap<List<Node>> blockMap);
}
