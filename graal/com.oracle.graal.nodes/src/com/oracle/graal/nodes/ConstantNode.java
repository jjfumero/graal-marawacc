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
package com.oracle.graal.nodes;

import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.iterators.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

/**
 * The {@code ConstantNode} represents a {@link Constant constant}.
 */
@NodeInfo(shortName = "Const", nameTemplate = "Const({p#rawvalue})")
public final class ConstantNode extends FloatingNode implements LIRLowerable {

    private static final DebugMetric ConstantNodes = Debug.metric("ConstantNodes");

    private final Constant value;

    private static ConstantNode createPrimitive(Constant value) {
        assert value.getKind() != Kind.Object;
        return new ConstantNode(value, StampFactory.forConstant(value));
    }

    /**
     * Constructs a new node representing the specified constant.
     *
     * @param value the constant
     */
    protected ConstantNode(Constant value, Stamp stamp) {
        super(stamp);
        assert stamp != null;
        this.value = value;
        ConstantNodes.increment();
    }

    /**
     * @return the constant value represented by this node
     */
    public Constant getValue() {
        return value;
    }

    /**
     * Used to measure the impact of ConstantNodes not recording their usages. This and all code
     * predicated on this value being true will be removed at some point.
     */
    public static final boolean ConstantNodeRecordsUsages = Boolean.parseBoolean(System.getProperty("graal.constantNodeRecordsUsages", "true"));

    @Override
    public boolean recordsUsages() {
        return ConstantNodeRecordsUsages;
    }

    /**
     * Computes the usages of this node by iterating over all the nodes in the graph, searching for
     * those that have this node as an input.
     */
    public List<Node> gatherUsages(StructuredGraph graph) {
        assert !ConstantNodeRecordsUsages;
        List<Node> usages = new ArrayList<>();
        for (Node node : graph.getNodes()) {
            for (Node input : node.inputs()) {
                if (input == this) {
                    usages.add(node);
                }
            }
        }
        return usages;
    }

    /**
     * Gathers all the {@link ConstantNode}s that are inputs to the
     * {@linkplain StructuredGraph#getNodes() live nodes} in a given graph.
     */
    public static NodeIterable<ConstantNode> getConstantNodes(StructuredGraph graph) {
        return graph.getNodes().filter(ConstantNode.class);
    }

    /**
     * Replaces this node at its usages with another node. If {@link #ConstantNodeRecordsUsages} is
     * false, this is an expensive operation that should only be used in test/verification/AOT code.
     */
    public void replace(StructuredGraph graph, Node replacement) {
        if (!recordsUsages()) {
            List<Node> usages = gatherUsages(graph);
            for (Node usage : usages) {
                usage.replaceFirstInput(this, replacement);
            }
            graph.removeFloating(this);
        } else {
            assert graph == graph();
            graph().replaceFloating(this, replacement);
        }
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        assert ConstantNodeRecordsUsages : "LIR generator should generate constants per-usage";
        if (gen.getLIRGeneratorTool().canInlineConstant(value) || onlyUsedInVirtualState()) {
            gen.setResult(this, value);
        } else {
            gen.setResult(this, gen.getLIRGeneratorTool().emitMove(value));
        }
    }

    private boolean onlyUsedInVirtualState() {
        for (Node n : this.usages()) {
            if (n instanceof VirtualState) {
                // Only virtual usage.
            } else {
                return false;
            }
        }
        return true;
    }

    public static ConstantNode forConstant(Constant constant, MetaAccessProvider metaAccess, StructuredGraph graph) {
        if (constant.getKind().getStackKind() == Kind.Int && constant.getKind() != Kind.Int) {
            return forInt(constant.asInt(), graph);
        }
        if (constant.getKind() == Kind.Object) {
            return unique(graph, new ConstantNode(constant, StampFactory.forConstant(constant, metaAccess)));
        } else {
            return unique(graph, createPrimitive(constant));
        }
    }

    public static ConstantNode forConstant(Stamp stamp, Constant constant, MetaAccessProvider metaAccess, StructuredGraph graph) {
        if (stamp instanceof PrimitiveStamp) {
            return forPrimitive(stamp, constant, graph);
        } else {
            ConstantNode ret = forConstant(constant, metaAccess, graph);
            assert ret.stamp().isCompatible(stamp);
            return ret;
        }
    }

    /**
     * Returns a node for a Java primitive.
     */
    public static ConstantNode forPrimitive(Constant constant, StructuredGraph graph) {
        assert constant.getKind() != Kind.Object;
        return forConstant(constant, null, graph);
    }

    /**
     * Returns a node for a primitive of a given type.
     */
    public static ConstantNode forPrimitive(Stamp stamp, Constant constant, StructuredGraph graph) {
        if (stamp instanceof IntegerStamp) {
            assert constant.getKind().isNumericInteger() && stamp.getStackKind() == constant.getKind().getStackKind();
            IntegerStamp istamp = (IntegerStamp) stamp;
            return forIntegerBits(istamp.getBits(), istamp.isUnsigned(), constant, graph);
        } else {
            assert constant.getKind().isNumericFloat() && stamp.getStackKind() == constant.getKind();
            return forPrimitive(constant, graph);
        }
    }

    /**
     * Returns a node for a double constant.
     *
     * @param d the double value for which to create the instruction
     * @return a node for a double constant
     */
    public static ConstantNode forDouble(double d, StructuredGraph graph) {
        return unique(graph, createPrimitive(Constant.forDouble(d)));
    }

    /**
     * Returns a node for a float constant.
     *
     * @param f the float value for which to create the instruction
     * @return a node for a float constant
     */
    public static ConstantNode forFloat(float f, StructuredGraph graph) {
        return unique(graph, createPrimitive(Constant.forFloat(f)));
    }

    /**
     * Returns a node for an long constant.
     *
     * @param i the long value for which to create the instruction
     * @return a node for an long constant
     */
    public static ConstantNode forLong(long i, StructuredGraph graph) {
        return unique(graph, createPrimitive(Constant.forLong(i)));
    }

    /**
     * Returns a node for an integer constant.
     *
     * @param i the integer value for which to create the instruction
     * @return a node for an integer constant
     */
    public static ConstantNode forInt(int i, StructuredGraph graph) {
        return unique(graph, createPrimitive(Constant.forInt(i)));
    }

    /**
     * Returns a node for a boolean constant.
     *
     * @param i the boolean value for which to create the instruction
     * @return a node representing the boolean
     */
    public static ConstantNode forBoolean(boolean i, StructuredGraph graph) {
        return unique(graph, createPrimitive(Constant.forInt(i ? 1 : 0)));
    }

    /**
     * Returns a node for a byte constant.
     *
     * @param i the byte value for which to create the instruction
     * @return a node representing the byte
     */
    public static ConstantNode forByte(byte i, StructuredGraph graph) {
        return unique(graph, createPrimitive(Constant.forInt(i)));
    }

    /**
     * Returns a node for a char constant.
     *
     * @param i the char value for which to create the instruction
     * @return a node representing the char
     */
    public static ConstantNode forChar(char i, StructuredGraph graph) {
        return unique(graph, createPrimitive(Constant.forInt(i)));
    }

    /**
     * Returns a node for a short constant.
     *
     * @param i the short value for which to create the instruction
     * @return a node representing the short
     */
    public static ConstantNode forShort(short i, StructuredGraph graph) {
        return unique(graph, createPrimitive(Constant.forInt(i)));
    }

    private static ConstantNode unique(StructuredGraph graph, ConstantNode node) {
        return graph.unique(node);
    }

    private static ConstantNode forIntegerBits(int bits, boolean unsigned, Constant constant, StructuredGraph graph) {
        long value = constant.asLong();
        long bounds;
        if (unsigned) {
            bounds = ZeroExtendNode.zeroExtend(value, bits);
        } else {
            bounds = SignExtendNode.signExtend(value, bits);
        }
        return unique(graph, new ConstantNode(constant, StampFactory.forInteger(bits, unsigned, bounds, bounds)));
    }

    /**
     * Returns a node for a constant integer that's not directly representable as Java primitive
     * (e.g. short).
     */
    public static ConstantNode forIntegerBits(int bits, boolean unsigned, long value, StructuredGraph graph) {
        return forIntegerBits(bits, unsigned, Constant.forPrimitiveInt(bits, value), graph);
    }

    /**
     * Returns a node for a constant integer that's compatible to a given stamp.
     */
    public static ConstantNode forIntegerStamp(Stamp stamp, long value, StructuredGraph graph) {
        if (stamp instanceof IntegerStamp) {
            IntegerStamp intStamp = (IntegerStamp) stamp;
            return forIntegerBits(intStamp.getBits(), intStamp.isUnsigned(), value, graph);
        } else {
            return forIntegerKind(stamp.getStackKind(), value, graph);
        }
    }

    public static ConstantNode forIntegerKind(Kind kind, long value, StructuredGraph graph) {
        switch (kind) {
            case Byte:
            case Short:
            case Int:
                return ConstantNode.forInt((int) value, graph);
            case Long:
                return ConstantNode.forLong(value, graph);
            default:
                throw GraalInternalError.shouldNotReachHere("unknown kind " + kind);
        }
    }

    public static ConstantNode forFloatingKind(Kind kind, double value, StructuredGraph graph) {
        switch (kind) {
            case Float:
                return ConstantNode.forFloat((float) value, graph);
            case Double:
                return ConstantNode.forDouble(value, graph);
            default:
                throw GraalInternalError.shouldNotReachHere("unknown kind " + kind);
        }
    }

    /**
     * Returns a node for a constant double that's compatible to a given stamp.
     */
    public static ConstantNode forFloatingStamp(Stamp stamp, double value, StructuredGraph graph) {
        return forFloatingKind(stamp.getStackKind(), value, graph);
    }

    public static ConstantNode defaultForKind(Kind kind, StructuredGraph graph) {
        switch (kind) {
            case Boolean:
            case Byte:
            case Char:
            case Short:
            case Int:
                return ConstantNode.forInt(0, graph);
            case Double:
                return ConstantNode.forDouble(0.0, graph);
            case Float:
                return ConstantNode.forFloat(0.0f, graph);
            case Long:
                return ConstantNode.forLong(0L, graph);
            case Object:
                return ConstantNode.forConstant(Constant.NULL_OBJECT, null, graph);
            default:
                return null;
        }
    }

    @Override
    public Map<Object, Object> getDebugProperties(Map<Object, Object> map) {
        Map<Object, Object> properties = super.getDebugProperties(map);
        properties.put("rawvalue", value.toValueString());
        return properties;
    }

    @Override
    public String toString(Verbosity verbosity) {
        if (verbosity == Verbosity.Name) {
            return super.toString(Verbosity.Name) + "(" + value.toValueString() + ")";
        } else {
            return super.toString(verbosity);
        }
    }
}
