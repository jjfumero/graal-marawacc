/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.lir.dfa;

import java.util.List;

import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.Value;

import com.oracle.graal.compiler.common.alloc.RegisterAllocationConfig;
import com.oracle.graal.compiler.common.cfg.AbstractBlockBase;
import com.oracle.graal.lir.LIR;
import com.oracle.graal.lir.LIRFrameState;
import com.oracle.graal.lir.LIRInstruction;
import com.oracle.graal.lir.Variable;
import com.oracle.graal.lir.framemap.FrameMap;
import com.oracle.graal.lir.gen.LIRGenerationResult;
import com.oracle.graal.lir.gen.LIRGeneratorTool.MoveFactory;
import com.oracle.graal.lir.phases.AllocationPhase;
import com.oracle.graal.lir.util.IndexedValueMap;
import com.oracle.graal.lir.util.ValueSet;

/**
 * Record all derived reference base pointers in a frame state.
 */
public final class MarkBasePointersPhase extends AllocationPhase {

    @Override
    protected <B extends AbstractBlockBase<B>> void run(TargetDescription target, LIRGenerationResult lirGenRes, List<B> codeEmittingOrder, List<B> linearScanOrder, MoveFactory spillMoveFactory,
                    RegisterAllocationConfig registerAllocationConfig) {
        new Marker<B>(lirGenRes.getLIR(), null).build();
    }

    private static final class Marker<T extends AbstractBlockBase<T>> extends LocationMarker<T, Marker<T>.BasePointersSet> {

        private final class BasePointersSet extends ValueSet<Marker<T>.BasePointersSet> {

            private final IndexedValueMap variables;

            public BasePointersSet() {
                variables = new IndexedValueMap();
            }

            private BasePointersSet(BasePointersSet s) {
                variables = new IndexedValueMap(s.variables);
            }

            @Override
            public Marker<T>.BasePointersSet copy() {
                return new BasePointersSet(this);
            }

            @Override
            public void put(Value v) {
                Variable base = (Variable) v.getLIRKind().getDerivedReferenceBase();
                assert !base.getLIRKind().isValue();
                variables.put(base.index, base);
            }

            @Override
            public void putAll(BasePointersSet v) {
                variables.putAll(v.variables);
            }

            @Override
            public void remove(Value v) {
                Variable base = (Variable) v.getLIRKind().getDerivedReferenceBase();
                assert !base.getLIRKind().isValue();
                variables.put(base.index, null);
            }

            @SuppressWarnings("unchecked")
            @Override
            public boolean equals(Object obj) {
                if (obj instanceof Marker.BasePointersSet) {
                    BasePointersSet other = (BasePointersSet) obj;
                    return variables.equals(other.variables);
                } else {
                    return false;
                }
            }

            @Override
            public int hashCode() {
                throw new UnsupportedOperationException();
            }
        }

        private Marker(LIR lir, FrameMap frameMap) {
            super(lir, frameMap);
        }

        @Override
        protected Marker<T>.BasePointersSet newLiveValueSet() {
            return new BasePointersSet();
        }

        @Override
        protected boolean shouldProcessValue(Value operand) {
            return operand.getLIRKind().isDerivedReference();
        }

        @Override
        protected void processState(LIRInstruction op, LIRFrameState info, BasePointersSet values) {
            info.setLiveBasePointers(new IndexedValueMap(values.variables));
        }
    }
}
