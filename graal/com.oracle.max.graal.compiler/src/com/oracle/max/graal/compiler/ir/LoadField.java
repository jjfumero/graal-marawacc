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

import com.oracle.max.graal.compiler.debug.*;
import com.oracle.max.graal.compiler.graph.*;
import com.oracle.max.graal.compiler.phases.CanonicalizerPhase.CanonicalizerOp;
import com.oracle.max.graal.compiler.phases.LoweringPhase.LoweringOp;
import com.oracle.max.graal.compiler.phases.LoweringPhase.LoweringTool;
import com.oracle.max.graal.graph.*;
import com.sun.cri.ci.*;
import com.sun.cri.ri.*;

/**
 * The {@code LoadField} instruction represents a read of a static or instance field.
 */
public final class LoadField extends AccessField {
    private static final LoadFieldCanonicalizerOp CANONICALIZER = new LoadFieldCanonicalizerOp();

    private static final int INPUT_COUNT = 0;
    private static final int SUCCESSOR_COUNT = 0;

    /**
     * Creates a new LoadField instance.
     * @param object the receiver object
     * @param field the compiler interface field
     * @param isStatic indicates if the field is static
     * @param stateAfter the state after the field access
     * @param graph
     * @param isLoaded indicates if the class is loaded
     */
    public LoadField(Value object, RiField field, Graph graph) {
        super(field.kind().stackKind(), object, field, INPUT_COUNT, SUCCESSOR_COUNT, graph);
    }

    /**
     * Gets the declared type of the field being accessed.
     * @return the declared type of the field being accessed.
     */
    @Override
    public RiType declaredType() {
        return field().type();
    }

    /**
     * Gets the exact type of the field being accessed. If the field type is
     * a primitive array or an instance class and the class is loaded and final,
     * then the exact type is the same as the declared type. Otherwise it is {@code null}
     * @return the exact type of the field if known; {@code null} otherwise
     */
    @Override
    public RiType exactType() {
        RiType declared = declaredType();
        return declared != null && declared.isResolved() ? declared.exactType() : null;
    }

    @Override
    public void accept(ValueVisitor v) {
        v.visitLoadField(this);
    }

    @Override
    public void print(LogStream out) {
        out.print(object()).
        print(".").
        print(field.name()).
        print(" [field: ").
        print(CiUtil.format("%h.%n:%t", field, false)).
        print("]");
    }

    @Override
    public boolean needsStateAfter() {
        return false;
    }

    /**
     * Gets a constant value to which this load can be reduced.
     *
     * @return {@code null} if this load cannot be reduced to a constant
     */
    private CiConstant constantValue() {
        if (isStatic()) {
            return field.constantValue(null);
        } else if (object().isConstant()) {
            return field.constantValue(object().asConstant());
        }
        return null;
    }

    @Override
    public Node copy(Graph into) {
        return new LoadField(null, field, into);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends Op> T lookup(Class<T> clazz) {
        if (clazz == CanonicalizerOp.class) {
            return (T) CANONICALIZER;
        }
        return super.lookup(clazz);
    }

    private static class LoadFieldLoweringOp implements LoweringOp {

        @Override
        public Node lower(Node n, LoweringTool tool) {
            LoadField field = (LoadField) n;
            return null;//field.field().createLoad(tool);
        }

    }

    private static class LoadFieldCanonicalizerOp implements CanonicalizerOp {
        @Override
        public Node canonical(Node node) {
            LoadField loadField = (LoadField) node;
            Graph graph = node.graph();
            CiConstant constant = null;
            if (graph instanceof CompilerGraph) {
                RiMethod method = ((CompilerGraph) graph).getCompilation().method;
                if (loadField.isStatic() && !method.isClassInitializer()) {
                    constant = loadField.field().constantValue(null);
                }
            }
            if (!loadField.isStatic()) {
                Value object = loadField.object();
                if (object.isConstant()) {
                    constant = loadField.field().constantValue(object.asConstant());
                }
            }
            if (constant != null) {
                return new Constant(constant, graph);
            }
            return loadField;
        }
    }
}
