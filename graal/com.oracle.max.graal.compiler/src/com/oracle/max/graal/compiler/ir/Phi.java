/*
 * Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.graal.compiler.ir;

import java.util.*;

import com.oracle.max.graal.compiler.*;
import com.oracle.max.graal.compiler.debug.*;
import com.oracle.max.graal.compiler.ir.StateSplit.FilteringIterator;
import com.oracle.max.graal.compiler.phases.CanonicalizerPhase.CanonicalizerOp;
import com.oracle.max.graal.compiler.phases.CanonicalizerPhase.NotifyReProcess;
import com.oracle.max.graal.graph.*;
import com.sun.cri.ci.*;

/**
 * The {@code Phi} instruction represents the merging of dataflow
 * in the instruction graph. It refers to a join block and a variable.
 */
public final class Phi extends FloatingNode {

    @Input    private Merge merge;

    @Input    private final NodeInputList<Value> values = new NodeInputList<Value>(this);

    public Merge merge() {
        return merge;
    }

    public void setMerge(Merge x) {
        updateUsages(merge, x);
        merge = x;
    }

    public static enum PhiType {
        Value,          // normal value phis
        Memory,         // memory phis
        Virtual         // phis used for VirtualObjectField merges
    }

    private final PhiType type;

    public Phi(CiKind kind, Merge merge, PhiType type, Graph graph) {
        super(kind, graph);
        this.type = type;
        setMerge(merge);
    }

    private Phi(CiKind kind, PhiType type, Graph graph) {
        super(kind, graph);
        this.type = type;
    }

    public PhiType type() {
        return type;
    }

    @Override
    public boolean verify() {
        assertTrue(merge() != null);
        assertTrue(merge().phiPredecessorCount() == valueCount(), merge().phiPredecessorCount() + "==" + valueCount());
        return true;
    }

    /**
     * Get the instruction that produces the value associated with the i'th predecessor
     * of the join block.
     * @param i the index of the predecessor
     * @return the instruction that produced the value in the i'th predecessor
     */
    public Value valueAt(int i) {
        return values.get(i);
    }

    public void setValueAt(int i, Value x) {
        values.set(i, x);
    }

    /**
     * Get the number of inputs to this phi (i.e. the number of predecessors to the join block).
     * @return the number of inputs in this phi
     */
    public int valueCount() {
        return values.size();
    }

    @Override
    public void accept(ValueVisitor v) {
        v.visitPhi(this);
    }

    @Override
    public void print(LogStream out) {
        out.print("phi function (");
        for (int i = 0; i < valueCount(); ++i) {
            if (i != 0) {
                out.print(' ');
            }
            out.print(valueAt(i));
        }
        out.print(')');
    }

    @Override
    public String shortName() {
        StringBuilder str = new StringBuilder();
        for (int i = 0; i < valueCount(); ++i) {
            if (i != 0) {
                str.append(' ');
            }
            str.append(valueAt(i) == null ? "-" : valueAt(i).id());
        }
        if (type == PhiType.Value) {
            return "Phi: (" + str + ")";
        } else {
            return type + "Phi: (" + str + ")";
        }
    }

    public void addInput(Value x) {
        values.add(x);
    }

    public void removeInput(int index) {
        values.remove(index);
    }

    @Override
    public Node copy(Graph into) {
        Phi x = new Phi(kind, type, into);
        return x;
    }

    @Override
    public Iterable<? extends Node> dataInputs() {
        final Iterator< ? extends Node> input = super.dataInputs().iterator();
        return new Iterable<Node>() {
            @Override
            public Iterator<Node> iterator() {
                return new FilteringIterator(input, Merge.class);
            }
        };
    }


    @SuppressWarnings("unchecked")
    @Override
    public <T extends Op> T lookup(Class<T> clazz) {
        if (clazz == CanonicalizerOp.class) {
            return (T) CANONICALIZER;
        }
        return super.lookup(clazz);
    }

    private static CanonicalizerOp CANONICALIZER = new CanonicalizerOp() {

        @Override
        public Node canonical(Node node, NotifyReProcess reProcess) {
            Phi phiNode = (Phi) node;
            if (phiNode.valueCount() != 2 || phiNode.merge().endCount() != 2) {
                return phiNode;
            }
            Merge merge = phiNode.merge();
            if (merge.phis().size() > 1) { // XXX (gd) disable canonicalization of multiple conditional while we are not able to fuse them and the potentially leftover If in the backend
                return phiNode;
            }
            Node end0 = merge.endAt(0);
            Node end1 = merge.endAt(1);
            Node endPred0 = end0.predecessor();
            Node endPred1 = end1.predecessor();
            if (endPred0 != endPred1 || !(endPred0 instanceof If)) {
                return phiNode;
            }
            If ifNode = (If) endPred0;
            boolean inverted = ifNode.trueSuccessor() == end1;
            Value trueValue = phiNode.valueAt(inverted ? 1 : 0);
            Value falseValue = phiNode.valueAt(inverted ? 0 : 1);
            if ((trueValue.kind != CiKind.Int && trueValue.kind != CiKind.Long) || (falseValue.kind != CiKind.Int && falseValue.kind != CiKind.Long)) {
                return phiNode;
            }
            if ((!(trueValue instanceof Constant) && trueValue.usages().size() == 1) || (!(falseValue instanceof Constant) && falseValue.usages().size() == 1)) {
                return phiNode;
            }
            BooleanNode compare = ifNode.compare();
            while (compare instanceof NegateBooleanNode) {
                compare = ((NegateBooleanNode) compare).value();
            }
            if (!(compare instanceof Compare || compare instanceof IsNonNull || compare instanceof NegateBooleanNode || compare instanceof Constant)) {
                return phiNode;
            }
            if (GraalOptions.TraceCanonicalizer) {
                TTY.println("> Phi canon'ed to Conditional");
            }
            reProcess.reProccess(ifNode);
            Conditional conditional = new Conditional(ifNode.compare(), trueValue, falseValue, node.graph());
            return conditional;
        }
    };
}
