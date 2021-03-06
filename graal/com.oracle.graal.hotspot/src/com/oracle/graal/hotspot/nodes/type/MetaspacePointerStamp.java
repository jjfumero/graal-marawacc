/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.nodes.type;

import jdk.vm.ci.common.JVMCIError;
import jdk.vm.ci.meta.LIRKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaType;

import com.oracle.graal.compiler.common.spi.LIRKindTool;
import com.oracle.graal.compiler.common.type.AbstractPointerStamp;
import com.oracle.graal.compiler.common.type.Stamp;

public abstract class MetaspacePointerStamp extends AbstractPointerStamp {

    protected MetaspacePointerStamp(boolean nonNull, boolean alwaysNull) {
        super(nonNull, alwaysNull);
    }

    @Override
    public LIRKind getLIRKind(LIRKindTool tool) {
        return tool.getWordKind();
    }

    @Override
    public Stamp empty() {
        // there is no empty pointer stamp
        return this;
    }

    @Override
    public boolean hasValues() {
        return true;
    }

    @Override
    public ResolvedJavaType javaType(MetaAccessProvider metaAccess) {
        throw JVMCIError.shouldNotReachHere("metaspace pointer has no Java type");
    }

    protected void appendString(StringBuilder str) {
        str.append(nonNull() ? "!" : "").append(alwaysNull() ? " NULL" : "");
    }

}
