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
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.iterators.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.type.GenericStamp.*;
import com.oracle.graal.nodes.util.*;

/**
 * This class represents a value within the graph, including local variables, phis, and
 * all other instructions.
 */
public abstract class ValueNode extends ScheduledNode implements StampProvider {

    /**
     * The kind of this value. This is {@link Kind#Void} for instructions that produce no value.
     * This kind is guaranteed to be a {@linkplain Kind#stackKind() stack kind}.
     */
    private Stamp stamp;

    @Input(notDataflow = true) private NodeInputList<ValueNode> dependencies;

    /**
     * This collection keeps dependencies that should be observed while scheduling (guards, etc.).
     */
    public NodeInputList<ValueNode> dependencies() {
        return dependencies;
    }

    public ValueNode(Stamp stamp) {
        this.stamp = stamp;
        this.dependencies = new NodeInputList<>(this);
        assert kind() != null && kind() == kind().stackKind() : kind() + " != " + kind().stackKind();
    }

    public ValueNode(Stamp stamp, ValueNode... dependencies) {
        this.stamp = stamp;
        this.dependencies = new NodeInputList<>(this, dependencies);
        assert kind() != null && kind() == kind().stackKind() : kind() + " != " + kind().stackKind();
    }

    public ValueNode(Stamp stamp, List<ValueNode> dependencies) {
        this.stamp = stamp;
        this.dependencies = new NodeInputList<>(this, dependencies);
        assert kind() != null && kind() == kind().stackKind() : kind() + " != " + kind().stackKind();
    }

    public Stamp stamp() {
        return stamp;
    }

    public void setStamp(Stamp stamp) {
        this.stamp = stamp;
    }

    /**
     * Checks if the given stamp is different than the current one ({@code newStamp.equals(oldStamp) == false}). If it
     * is different then the new stamp will become the current stamp for this node.
     *
     * @return true if the stamp has changed, false otherwise.
     */
    protected final boolean updateStamp(Stamp newStamp) {
        if (newStamp.equals(stamp)) {
            return false;
        } else {
            stamp = newStamp;
            return true;
        }
    }

    /**
     * This method can be overridden by subclasses of {@link ValueNode} if they need to recompute their stamp if their
     * inputs change. A typical implementation will compute the stamp and pass it to {@link #updateStamp(Stamp)}, whose
     * return value can be used as the result of this method.
     *
     * @return true if the stamp has changed, false otherwise.
     */
    public boolean inferStamp() {
        return false;
    }

    public Kind kind() {
        return stamp.kind();
    }

    /**
     * Checks whether this value is a constant (i.e. it is of type {@link ConstantNode}.
     *
     * @return {@code true} if this value is a constant
     */
    public final boolean isConstant() {
        return this instanceof ConstantNode;
    }

    private static final NodePredicate IS_CONSTANT = new NodePredicate() {
        @Override
        public boolean apply(Node n) {
            return n instanceof ValueNode && ((ValueNode) n).isConstant();
        }
    };

    public static NodePredicate isConstantPredicate() {
        return IS_CONSTANT;
    }

    /**
     * Checks whether this value represents the null constant.
     *
     * @return {@code true} if this value represents the null constant
     */
    public final boolean isNullConstant() {
        return this instanceof ConstantNode && ((ConstantNode) this).value.isNull();
    }

    /**
     * Convert this value to a constant if it is a constant, otherwise return null.
     *
     * @return the {@link Constant} represented by this value if it is a constant; {@code null} otherwise
     */
    public final Constant asConstant() {
        if (this instanceof ConstantNode) {
            return ((ConstantNode) this).value;
        }
        return null;
    }

    public <T extends Stamp> boolean verifyStamp(Class<T> stampClass) {
        assert stamp != null;
        assert stampClass.isInstance(stamp) : this + " (" + GraphUtil.approxSourceLocation(this) + ") has unexpected stamp type: expected " + stampClass.getName() +
            ", got " + stamp.getClass().getName();
        return true;
    }

    public final ObjectStamp objectStamp() {
        assert verifyStamp(ObjectStamp.class);
        return (ObjectStamp) stamp();
    }

    public final IntegerStamp integerStamp() {
        assert verifyStamp(IntegerStamp.class);
        return (IntegerStamp) stamp();
    }

    public final FloatStamp floatStamp() {
        assert verifyStamp(FloatStamp.class);
        return (FloatStamp) stamp();
    }

    @Override
    public boolean verify() {
        for (ValueNode v : dependencies().nonNull()) {
            assertTrue(!(v.stamp() instanceof GenericStamp) || ((GenericStamp) v.stamp()).type() == GenericStampType.Dependency, "cannot depend on node with stamp %s", v.stamp());
        }
        return super.verify();
    }

    @Override
    public Map<Object, Object> getDebugProperties() {
        Map<Object, Object> properties = super.getDebugProperties();
        if (!dependencies.isEmpty()) {
            StringBuilder str = new StringBuilder();
            for (int i = 0; i < dependencies.size(); i++) {
                str.append(i == 0 ? "" : ", ").append(dependencies.get(i) == null ? "null" : dependencies.get(i).toString(Verbosity.Id));
            }
            properties.put("dependencies", str.toString());
        }
        return properties;
    }
}
