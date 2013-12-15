/*
 * Copyright (c) 2012, 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.sl.nodes;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.utilities.*;
import com.oracle.truffle.sl.runtime.*;

public final class ReadFunctionNode extends TypedNode {

    private final SLFunctionRegistry registry;
    private final String name;
    private final BranchProfile invalidFunction = new BranchProfile();

    public ReadFunctionNode(SLFunctionRegistry registry, String name) {
        this.registry = registry;
        this.name = name;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return executeCallTarget(frame);
    }

    @Override
    public CallTarget executeCallTarget(VirtualFrame frame) {
        CallTarget target = registry.lookup(name);
        if (target != null) {
            return target;
        }
        invalidFunction.enter();
        throw new RuntimeException("Function with name '" + name + "' not found.");
    }

}
