/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.hsail;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.hsail.replacements.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.spi.*;

public class HSAILHotSpotLoweringProvider extends DefaultHotSpotLoweringProvider {

    private HSAILNewObjectSnippets.Templates hsailNewObjectSnippets;

    abstract class LoweringStrategy {
        abstract void lower(Node n, LoweringTool tool);
    }

    LoweringStrategy PassThruStrategy = new LoweringStrategy() {
        @Override
        void lower(Node n, LoweringTool tool) {
            return;
        }
    };

    LoweringStrategy RejectStrategy = new LoweringStrategy() {
        @Override
        void lower(Node n, LoweringTool tool) {
            throw new GraalInternalError("Node implementing Lowerable not handled in HSAIL Backend: " + n);
        }
    };

    LoweringStrategy NewObjectStrategy = new LoweringStrategy() {
        @Override
        void lower(Node n, LoweringTool tool) {
            StructuredGraph graph = (StructuredGraph) n.graph();
            if (graph.getGuardsStage() == StructuredGraph.GuardsStage.AFTER_FSA) {
                if (n instanceof NewInstanceNode) {
                    hsailNewObjectSnippets.lower((NewInstanceNode) n, tool);
                } else if (n instanceof NewArrayNode) {
                    hsailNewObjectSnippets.lower((NewArrayNode) n, tool);
                }
            }
        }
    };

    // strategy to replace an UnwindNode with a DeoptNode
    LoweringStrategy UnwindNodeStrategy = new LoweringStrategy() {
        @Override
        void lower(Node n, LoweringTool tool) {
            StructuredGraph graph = (StructuredGraph) n.graph();
            UnwindNode unwind = (UnwindNode) n;
            ValueNode exception = unwind.exception();
            if (exception instanceof ForeignCallNode) {
                // build up action and reason
                String callName = ((ForeignCallNode) exception).getDescriptor().getName();
                DeoptimizationReason reason;
                switch (callName) {
                    case "createOutOfBoundsException":
                        reason = DeoptimizationReason.BoundsCheckException;
                        break;
                    case "createNullPointerException":
                        reason = DeoptimizationReason.NullCheckException;
                        break;
                    default:
                        reason = DeoptimizationReason.None;
                }
                unwind.replaceAtPredecessor(graph.add(DeoptimizeNode.create(DeoptimizationAction.InvalidateReprofile, reason)));
                unwind.safeDelete();
            } else {
                // unwind whose exception is not an instance of ForeignCallNode
                throw new GraalInternalError("UnwindNode seen without ForeignCallNode: " + exception);
            }
        }
    };

    private HashMap<NodeClass, LoweringStrategy> strategyMap = new HashMap<>();

    void initStrategyMap() {
        strategyMap.put(NodeClass.get(ConvertNode.class), PassThruStrategy);
        strategyMap.put(NodeClass.get(FloatConvertNode.class), PassThruStrategy);
        strategyMap.put(NodeClass.get(NewInstanceNode.class), NewObjectStrategy);
        strategyMap.put(NodeClass.get(NewArrayNode.class), NewObjectStrategy);
        strategyMap.put(NodeClass.get(NewMultiArrayNode.class), RejectStrategy);
        strategyMap.put(NodeClass.get(DynamicNewArrayNode.class), RejectStrategy);
        strategyMap.put(NodeClass.get(MonitorEnterNode.class), RejectStrategy);
        strategyMap.put(NodeClass.get(MonitorExitNode.class), RejectStrategy);
        strategyMap.put(NodeClass.get(UnwindNode.class), UnwindNodeStrategy);
    }

    private LoweringStrategy getStrategy(Node n) {
        return strategyMap.get(n.getNodeClass());
    }

    public HSAILHotSpotLoweringProvider(HotSpotGraalRuntime runtime, MetaAccessProvider metaAccess, ForeignCallsProvider foreignCalls, HotSpotRegistersProvider registers, TargetDescription target) {
        super(runtime, metaAccess, foreignCalls, registers, target);
        initStrategyMap();
    }

    @Override
    public void initialize(HotSpotProviders providers, HotSpotVMConfig config) {
        super.initialize(providers, config);
        hsailNewObjectSnippets = new HSAILNewObjectSnippets.Templates(providers, target);
    }

    @Override
    public void lower(Node n, LoweringTool tool) {
        LoweringStrategy strategy = getStrategy(n);
        // if not in map, let superclass handle it
        if (strategy == null) {
            super.lower(n, tool);
        } else {
            strategy.lower(n, tool);
        }
    }

}
