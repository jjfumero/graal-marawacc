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
 *
 */
package com.sun.hotspot.igv.graal.filters;

import com.sun.hotspot.igv.data.Properties;
import com.sun.hotspot.igv.filter.AbstractFilter;
import com.sun.hotspot.igv.graph.Connection;
import com.sun.hotspot.igv.graph.Diagram;
import com.sun.hotspot.igv.graph.Figure;
import com.sun.hotspot.igv.graph.InputSlot;
import java.awt.Color;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GraalCFGFilter extends AbstractFilter {
    
    public String getName() {
        return "Graal CFG Filter";
    }

    public void apply(Diagram d) {
        Set<Figure> figuresToRemove = new HashSet<Figure>();
        Set<Connection> connectionsToRemove = new HashSet<Connection>();
        for (Figure f : d.getFigures()) {
            final String prop = f.getProperties().get("probability");
            
            if (prop == null) {
                figuresToRemove.add(f);
            }
        }
        d.removeAllFigures(figuresToRemove);
        
        for (Figure f : d.getFigures()) {
            Properties p = f.getProperties();
            int predCount = Integer.parseInt(p.get("predecessorCount"));
            for (InputSlot is : f.getInputSlots()) {
                if (is.getPosition() >= predCount && !"EndNode".equals(is.getProperties().get("class"))) {
                    for (Connection c : is.getConnections()) {
                        if (!"EndNode".equals(c.getOutputSlot().getFigure().getProperties().get("class"))) {
                            connectionsToRemove.add(c);
                        }
                    }
                }
            }
        }
        
        for (Connection c : connectionsToRemove) {
            c.remove();
            System.out.println("rm " + c);
        }
    }
}
