/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.graal.compiler.phases;

import com.oracle.max.graal.debug.*;
import com.oracle.max.graal.nodes.*;

public abstract class Phase {

    private String name;

    protected Phase() {
        this.name = this.getClass().getSimpleName();
        if (name.endsWith("Phase")) {
            name = name.substring(0, name.length() - "Phase".length());
        }
    }

    protected Phase(String name) {
        this.name = name;
    }

    protected String getDetailedName() {
        return getName();
    }

    public final void apply(final StructuredGraph graph) {
        apply(graph, true);
    }

    public final void apply(final StructuredGraph graph, final boolean dumpGraph) {
        Debug.scope(name, this, new Runnable() {
            public void run() {
                Phase.this.run(graph);
                if (dumpGraph) {
                    Debug.dump(graph, "After phase %s", name);
                }
                assert graph.verify();
            }
        });
    }

    public final String getName() {
        return name;
    }

    protected abstract void run(StructuredGraph graph);
}
