/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.word.nodes;

import jdk.internal.jvmci.meta.AllocatableValue;
import jdk.internal.jvmci.meta.JavaConstant;
import jdk.internal.jvmci.meta.JavaKind;
import jdk.internal.jvmci.meta.LIRKind;
import jdk.internal.jvmci.meta.Value;

import com.oracle.graal.compiler.common.type.AbstractPointerStamp;
import com.oracle.graal.compiler.common.type.ObjectStamp;
import com.oracle.graal.compiler.common.type.Stamp;
import com.oracle.graal.compiler.common.type.StampFactory;
import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.graph.spi.Canonicalizable;
import com.oracle.graal.graph.spi.CanonicalizerTool;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.ConstantNode;
import com.oracle.graal.nodes.FixedWithNextNode;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.spi.LIRLowerable;
import com.oracle.graal.nodes.spi.NodeLIRBuilderTool;
import com.oracle.graal.word.Word.Opcode;

/**
 * Casts between Word and Object exposed by the {@link Opcode#FROM_ADDRESS},
 * {@link Opcode#OBJECT_TO_TRACKED}, {@link Opcode#OBJECT_TO_UNTRACKED} and {@link Opcode#TO_OBJECT}
 * operations. It has an impact on the pointer maps for the GC, so it must not be scheduled or
 * optimized away.
 */
@NodeInfo
public final class WordCastNode extends FixedWithNextNode implements LIRLowerable, Canonicalizable {

    public static final NodeClass<WordCastNode> TYPE = NodeClass.create(WordCastNode.class);

    @Input ValueNode input;
    public final boolean trackedPointer;

    public static WordCastNode wordToObject(ValueNode input, JavaKind wordKind) {
        assert input.getStackKind() == wordKind;
        return new WordCastNode(StampFactory.object(), input);
    }

    public static WordCastNode addressToWord(ValueNode input, JavaKind wordKind) {
        assert input.stamp() instanceof AbstractPointerStamp;
        return new WordCastNode(StampFactory.forKind(wordKind), input);
    }

    public static WordCastNode objectToTrackedPointer(ValueNode input, JavaKind wordKind) {
        assert input.stamp() instanceof ObjectStamp;
        return new WordCastNode(StampFactory.forKind(wordKind), input, true);
    }

    public static WordCastNode objectToUntrackedPointer(ValueNode input, JavaKind wordKind) {
        assert input.stamp() instanceof ObjectStamp;
        return new WordCastNode(StampFactory.forKind(wordKind), input, false);
    }

    protected WordCastNode(Stamp stamp, ValueNode input) {
        this(stamp, input, true);
    }

    protected WordCastNode(Stamp stamp, ValueNode input, boolean trackedPointer) {
        super(TYPE, stamp);
        this.input = input;
        this.trackedPointer = trackedPointer;
    }

    public ValueNode getInput() {
        return input;
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (tool.allUsagesAvailable() && hasNoUsages()) {
            /* If the cast is unused, it can be eliminated. */
            return input;
        }

        assert !stamp().isCompatible(input.stamp());
        if (input.isConstant()) {
            /* Null pointers are uncritical for GC, so they can be constant folded. */
            if (input.asJavaConstant().isNull()) {
                return ConstantNode.forIntegerStamp(stamp(), 0);
            } else if (input.asJavaConstant().getJavaKind().isNumericInteger() && input.asJavaConstant().asLong() == 0) {
                return ConstantNode.forConstant(stamp(), JavaConstant.NULL_POINTER, tool.getMetaAccess());
            }
        }

        return this;
    }

    @Override
    public void generate(NodeLIRBuilderTool generator) {
        Value value = generator.operand(input);
        LIRKind kind = generator.getLIRGeneratorTool().getLIRKind(stamp());
        assert kind.getPlatformKind().getSizeInBytes() == value.getPlatformKind().getSizeInBytes();

        if (trackedPointer && kind.isValue() && !value.getLIRKind().isValue()) {
            // just change the PlatformKind, but don't drop reference information
            kind = value.getLIRKind().changeType(kind.getPlatformKind());
        }

        if (kind.equals(value.getLIRKind())) {
            generator.setResult(this, value);
        } else {
            AllocatableValue result = generator.getLIRGeneratorTool().newVariable(kind);
            generator.getLIRGeneratorTool().emitMove(result, value);
            generator.setResult(this, result);
        }
    }
}
