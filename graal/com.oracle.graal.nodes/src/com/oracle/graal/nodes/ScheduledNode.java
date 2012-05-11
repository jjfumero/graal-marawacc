/*
 * Copyright (c) 2012, 2012, Oracle and/or its affiliates. All rights reserved.
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

import com.oracle.graal.graph.*;

public class ScheduledNode extends Node {

    @Successor private ScheduledNode scheduledNext; // the immediate successor of the current node

    public ScheduledNode scheduledNext() {
        return scheduledNext;
    }

    public void setScheduledNext(ScheduledNode x) {
        updatePredecessors(scheduledNext, x);
        scheduledNext = x;
    }

    @Override
    public Map<Object, Object> getDebugProperties() {
        Map<Object, Object> debugProperties = super.getDebugProperties();
        if (this instanceof StateSplit) {
            StateSplit stateSplit = (StateSplit) this;
            if (stateSplit.stateAfter() != null) {
                debugProperties.put("stateAfter", stateSplit.stateAfter().toString(Verbosity.Debugger));
            }
        }
        return debugProperties;
    }

}
