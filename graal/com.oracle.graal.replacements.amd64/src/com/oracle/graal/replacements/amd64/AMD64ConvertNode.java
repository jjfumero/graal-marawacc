/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.replacements.amd64;

import com.oracle.graal.api.code.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.ConvertNode.Op;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

/**
 * This node has the semantics of the AMD64 conversions. It is used in the lowering of the
 * ConvertNode which, on AMD64 needs a AMD64ConvertNode plus some fixup code that handles the corner
 * cases that differ between AMD64 and Java.
 * 
 */
public class AMD64ConvertNode extends FloatingNode implements LIRLowerable, ArithmeticOperation {

    @Input private ValueNode value;
    public final Op opcode;

    public AMD64ConvertNode(Op opcode, ValueNode value) {
        super(StampFactory.forKind(opcode.to.getStackKind()));
        this.opcode = opcode;
        this.value = value;
    }

    public void generate(LIRGeneratorTool gen) {
        gen.setResult(this, gen.emitConvert(opcode, gen.operand(value)));
    }
}
