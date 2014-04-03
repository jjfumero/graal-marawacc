/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package com.oracle.truffle.api.impl;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;

public class DefaultCallNode extends CallNode {

    public DefaultCallNode(CallTarget target) {
        super(target);
    }

    @Override
    public Object call(PackedFrame caller, Arguments arguments) {
        return getCurrentCallTarget().call(caller, arguments);
    }

    @Override
    public void inline() {
    }

    @Override
    public CallTarget getSplitCallTarget() {
        return null;
    }

    @Override
    public boolean split() {
        return false;
    }

    @Override
    public boolean isSplittable() {
        return false;
    }

    @Override
    public boolean isInlined() {
        return false;
    }

    @Override
    public boolean isInlinable() {
        return false;
    }

    @Override
    public String toString() {
        return (getParent() != null ? getParent().toString() : super.toString()) + " call " + getCurrentCallTarget().toString();
    }
}
