/*
 * Copyright (c) 2013, 2013, Oracle and/or its affiliates. All rights reserved.
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

import com.oracle.graal.api.meta.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.word.phases.*;

/**
 * Cast between Word and Object that is introduced by the {@link WordTypeRewriterPhase}. It has an
 * impact on the pointer maps for the GC, so it must not be scheduled or optimized away.
 */
public final class WordCastNode extends FixedWithNextNode implements LIRLowerable {

    public static WordCastNode wordToObject(ValueNode input, Kind wordKind) {
        assert input.kind() == wordKind;
        return new WordCastNode(StampFactory.object(), input);
    }

    public static WordCastNode objectToWord(ValueNode input, Kind wordKind) {
        assert input.kind() == Kind.Object;
        return new WordCastNode(StampFactory.forKind(wordKind), input);
    }

    @Input private ValueNode input;

    private WordCastNode(Stamp stamp, ValueNode input) {
        super(stamp);
        this.input = input;
    }

    public ValueNode getInput() {
        return input;
    }

    @Override
    public void generate(LIRGeneratorTool generator) {
        assert kind() != input.kind();
        assert generator.target().arch.getSizeInBytes(kind()) == generator.target().arch.getSizeInBytes(input.kind());

        AllocatableValue result = generator.newVariable(kind());
        generator.emitMove(result, generator.operand(input));
        generator.setResult(this, result);
    }
}
