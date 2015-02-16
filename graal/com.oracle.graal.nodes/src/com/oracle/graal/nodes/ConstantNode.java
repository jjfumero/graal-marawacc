/*
 * Copyright (c) 2009, 2014, Oracle and/or its affiliates. All rights reserved.
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

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.iterators.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.spi.*;

/**
 * The {@code ConstantNode} represents a {@link Constant constant}.
 */
@NodeInfo(shortName = "Const", nameTemplate = "Const({p#rawvalue})")
public final class ConstantNode extends FloatingNode implements LIRLowerable {

    public static final NodeClass<ConstantNode> TYPE = NodeClass.get(ConstantNode.class);
    private static final DebugMetric ConstantNodes = Debug.metric("ConstantNodes");

    protected final Constant value;

    private static ConstantNode createPrimitive(JavaConstant value) {
        assert value.getKind() != Kind.Object;
        return new ConstantNode(value, StampFactory.forConstant(value));
    }

    /**
     * Constructs a new node representing the specified constant.
     *
     * @param value the constant
     */
    public ConstantNode(Constant value, Stamp stamp) {
        super(TYPE, stamp);
        assert stamp != null && isCompatible(value, stamp);
        this.value = value;
        ConstantNodes.increment();
    }

    private static boolean isCompatible(Constant value, Stamp stamp) {
        if (value instanceof VMConstant) {
            assert stamp instanceof AbstractPointerStamp;
        } else if (value instanceof PrimitiveConstant) {
            if (((PrimitiveConstant) value).getKind() == Kind.Illegal) {
                assert stamp instanceof IllegalStamp;
            } else {
                assert stamp instanceof PrimitiveStamp;
            }
        }
        return true;
    }

    /**
     * @return the constant value represented by this node
     */
    public Constant getValue() {
        return value;
    }

    /**
     * Gathers all the {@link ConstantNode}s that are inputs to the
     * {@linkplain StructuredGraph#getNodes() live nodes} in a given graph.
     */
    public static NodeIterable<ConstantNode> getConstantNodes(StructuredGraph graph) {
        return graph.getNodes().filter(ConstantNode.class);
    }

    /**
     * Replaces this node at its usages with another node.
     */
    public void replace(StructuredGraph graph, Node replacement) {
        assert graph == graph();
        graph().replaceFloating(this, replacement);
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        if (onlyUsedInVirtualState()) {
            gen.setResult(this, (JavaConstant) value);
        } else {
            LIRKind kind = gen.getLIRGeneratorTool().getLIRKind(stamp());
            gen.setResult(this, gen.getLIRGeneratorTool().emitLoadConstant(kind, value));
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

    public static ConstantNode forConstant(JavaConstant constant, MetaAccessProvider metaAccess, StructuredGraph graph) {
        if (constant.getKind().getStackKind() == Kind.Int && constant.getKind() != Kind.Int) {
            return forInt(constant.asInt(), graph);
        }
        if (constant.getKind() == Kind.Object) {
            return unique(graph, new ConstantNode(constant, StampFactory.forConstant(constant, metaAccess)));
        } else {
            return unique(graph, createPrimitive(constant));
        }
    }

    public static ConstantNode forConstant(JavaConstant constant, MetaAccessProvider metaAccess) {
        if (constant.getKind().getStackKind() == Kind.Int && constant.getKind() != Kind.Int) {
            return forInt(constant.asInt());
        }
        if (constant.getKind() == Kind.Object) {
            return new ConstantNode(constant, StampFactory.forConstant(constant, metaAccess));
        } else {
            return createPrimitive(constant);
        }
    }

    public static ConstantNode forConstant(Stamp stamp, Constant constant, MetaAccessProvider metaAccess, StructuredGraph graph) {
        return graph.unique(new ConstantNode(constant, stamp.constant(constant, metaAccess)));
    }

    public static ConstantNode forConstant(Stamp stamp, Constant constant, MetaAccessProvider metaAccess) {
        return new ConstantNode(constant, stamp.constant(constant, metaAccess));
    }

    /**
     * Returns a node for a Java primitive.
     */
    public static ConstantNode forPrimitive(JavaConstant constant, StructuredGraph graph) {
        assert constant.getKind() != Kind.Object;
        return forConstant(constant, null, graph);
    }

    /**
     * Returns a node for a Java primitive.
     */
    public static ConstantNode forPrimitive(JavaConstant constant) {
        assert constant.getKind() != Kind.Object;
        return forConstant(constant, null);
    }

    /**
     * Returns a node for a primitive of a given type.
     */
    public static ConstantNode forPrimitive(Stamp stamp, JavaConstant constant, StructuredGraph graph) {
        if (stamp instanceof IntegerStamp) {
            assert constant.getKind().isNumericInteger() && stamp.getStackKind() == constant.getKind().getStackKind();
            IntegerStamp istamp = (IntegerStamp) stamp;
            return forIntegerBits(istamp.getBits(), constant, graph);
        } else {
            assert constant.getKind().isNumericFloat() && stamp.getStackKind() == constant.getKind();
            return forPrimitive(constant, graph);
        }
    }

    /**
     * Returns a node for a primitive of a given type.
     */
    public static ConstantNode forPrimitive(Stamp stamp, Constant constant) {
        if (stamp instanceof IntegerStamp) {
            PrimitiveConstant primitive = (PrimitiveConstant) constant;
            assert primitive.getKind().isNumericInteger() && stamp.getStackKind() == primitive.getKind().getStackKind();
            IntegerStamp istamp = (IntegerStamp) stamp;
            return forIntegerBits(istamp.getBits(), primitive);
        } else if (stamp instanceof FloatStamp) {
            PrimitiveConstant primitive = (PrimitiveConstant) constant;
            assert primitive.getKind().isNumericFloat() && stamp.getStackKind() == primitive.getKind();
            return forConstant(primitive, null);
        } else {
            assert !(stamp instanceof AbstractObjectStamp);
            return new ConstantNode(constant, stamp.constant(constant, null));
        }
    }

    /**
     * Returns a node for a double constant.
     *
     * @param d the double value for which to create the instruction
     * @return a node for a double constant
     */
    public static ConstantNode forDouble(double d, StructuredGraph graph) {
        return unique(graph, createPrimitive(JavaConstant.forDouble(d)));
    }

    /**
     * Returns a node for a double constant.
     *
     * @param d the double value for which to create the instruction
     * @return a node for a double constant
     */
    public static ConstantNode forDouble(double d) {
        return createPrimitive(JavaConstant.forDouble(d));
    }

    /**
     * Returns a node for a float constant.
     *
     * @param f the float value for which to create the instruction
     * @return a node for a float constant
     */
    public static ConstantNode forFloat(float f, StructuredGraph graph) {
        return unique(graph, createPrimitive(JavaConstant.forFloat(f)));
    }

    /**
     * Returns a node for a float constant.
     *
     * @param f the float value for which to create the instruction
     * @return a node for a float constant
     */
    public static ConstantNode forFloat(float f) {
        return createPrimitive(JavaConstant.forFloat(f));
    }

    /**
     * Returns a node for an long constant.
     *
     * @param i the long value for which to create the instruction
     * @return a node for an long constant
     */
    public static ConstantNode forLong(long i, StructuredGraph graph) {
        return unique(graph, createPrimitive(JavaConstant.forLong(i)));
    }

    /**
     * Returns a node for an long constant.
     *
     * @param i the long value for which to create the instruction
     * @return a node for an long constant
     */
    public static ConstantNode forLong(long i) {
        return createPrimitive(JavaConstant.forLong(i));
    }

    /**
     * Returns a node for an integer constant.
     *
     * @param i the integer value for which to create the instruction
     * @return a node for an integer constant
     */
    public static ConstantNode forInt(int i, StructuredGraph graph) {
        return unique(graph, createPrimitive(JavaConstant.forInt(i)));
    }

    /**
     * Returns a node for an integer constant.
     *
     * @param i the integer value for which to create the instruction
     * @return a node for an integer constant
     */
    public static ConstantNode forInt(int i) {
        return createPrimitive(JavaConstant.forInt(i));
    }

    /**
     * Returns a node for a boolean constant.
     *
     * @param i the boolean value for which to create the instruction
     * @return a node representing the boolean
     */
    public static ConstantNode forBoolean(boolean i, StructuredGraph graph) {
        return unique(graph, createPrimitive(JavaConstant.forInt(i ? 1 : 0)));
    }

    /**
     * Returns a node for a boolean constant.
     *
     * @param i the boolean value for which to create the instruction
     * @return a node representing the boolean
     */
    public static ConstantNode forBoolean(boolean i) {
        return createPrimitive(JavaConstant.forInt(i ? 1 : 0));
    }

    /**
     * Returns a node for a byte constant.
     *
     * @param i the byte value for which to create the instruction
     * @return a node representing the byte
     */
    public static ConstantNode forByte(byte i, StructuredGraph graph) {
        return unique(graph, createPrimitive(JavaConstant.forInt(i)));
    }

    /**
     * Returns a node for a char constant.
     *
     * @param i the char value for which to create the instruction
     * @return a node representing the char
     */
    public static ConstantNode forChar(char i, StructuredGraph graph) {
        return unique(graph, createPrimitive(JavaConstant.forInt(i)));
    }

    /**
     * Returns a node for a short constant.
     *
     * @param i the short value for which to create the instruction
     * @return a node representing the short
     */
    public static ConstantNode forShort(short i, StructuredGraph graph) {
        return unique(graph, createPrimitive(JavaConstant.forInt(i)));
    }

    private static ConstantNode unique(StructuredGraph graph, ConstantNode node) {
        return graph.unique(node);
    }

    private static ConstantNode forIntegerBits(int bits, JavaConstant constant, StructuredGraph graph) {
        long value = constant.asLong();
        long bounds = CodeUtil.signExtend(value, bits);
        return unique(graph, new ConstantNode(constant, StampFactory.forInteger(bits, bounds, bounds)));
    }

    /**
     * Returns a node for a constant integer that's not directly representable as Java primitive
     * (e.g. short).
     */
    public static ConstantNode forIntegerBits(int bits, long value, StructuredGraph graph) {
        return forIntegerBits(bits, JavaConstant.forPrimitiveInt(bits, value), graph);
    }

    private static ConstantNode forIntegerBits(int bits, JavaConstant constant) {
        long value = constant.asLong();
        long bounds = CodeUtil.signExtend(value, bits);
        return new ConstantNode(constant, StampFactory.forInteger(bits, bounds, bounds));
    }

    /**
     * Returns a node for a constant integer that's not directly representable as Java primitive
     * (e.g. short).
     */
    public static ConstantNode forIntegerBits(int bits, long value) {
        return forIntegerBits(bits, JavaConstant.forPrimitiveInt(bits, value));
    }

    /**
     * Returns a node for a constant integer that's compatible to a given stamp.
     */
    public static ConstantNode forIntegerStamp(Stamp stamp, long value, StructuredGraph graph) {
        if (stamp instanceof IntegerStamp) {
            IntegerStamp intStamp = (IntegerStamp) stamp;
            return forIntegerBits(intStamp.getBits(), value, graph);
        } else {
            return forIntegerKind(stamp.getStackKind(), value, graph);
        }
    }

    /**
     * Returns a node for a constant integer that's compatible to a given stamp.
     */
    public static ConstantNode forIntegerStamp(Stamp stamp, long value) {
        if (stamp instanceof IntegerStamp) {
            IntegerStamp intStamp = (IntegerStamp) stamp;
            return forIntegerBits(intStamp.getBits(), value);
        } else {
            return forIntegerKind(stamp.getStackKind(), value);
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

    public static ConstantNode forIntegerKind(Kind kind, long value) {
        switch (kind) {
            case Byte:
            case Short:
            case Int:
                return createPrimitive(JavaConstant.forInt((int) value));
            case Long:
                return createPrimitive(JavaConstant.forLong(value));
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

    public static ConstantNode forFloatingKind(Kind kind, double value) {
        switch (kind) {
            case Float:
                return ConstantNode.forFloat((float) value);
            case Double:
                return ConstantNode.forDouble(value);
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

    /**
     * Returns a node for a constant double that's compatible to a given stamp.
     */
    public static ConstantNode forFloatingStamp(Stamp stamp, double value) {
        return forFloatingKind(stamp.getStackKind(), value);
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
                return ConstantNode.forConstant(JavaConstant.NULL_POINTER, null, graph);
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
