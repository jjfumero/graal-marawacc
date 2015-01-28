/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.test.tools;

import static com.oracle.truffle.api.test.tools.TestNodes.*;
import static org.junit.Assert.*;

import org.junit.*;

import com.oracle.truffle.api.instrument.*;
import com.oracle.truffle.api.nodes.*;
import com.oracle.truffle.api.source.*;
import com.oracle.truffle.api.tools.*;

public class LineToProbesMapTest {

    @Test
    public void testToolCreatedTooLate() {
        final RootNode expr13rootNode = createExpr13TestRootNode();
        final Node addNode = expr13rootNode.getChildren().iterator().next();
        final Probe probe = addNode.probe();
        final LineLocation lineLocation = probe.getProbedSourceSection().getLineLocation();
        assertEquals(lineLocation, expr13Line2);

        final LineToProbesMap tool = new LineToProbesMap();
        tool.install();

        assertNull(tool.findFirstProbe(expr13Line1));
        assertNull(tool.findFirstProbe(expr13Line2));
        tool.dispose();
    }

    @Test
    public void testToolInstalledTooLate() {
        final LineToProbesMap tool = new LineToProbesMap();

        final RootNode expr13rootNode = createExpr13TestRootNode();
        final Node addNode = expr13rootNode.getChildren().iterator().next();
        final Probe probe = addNode.probe();
        final LineLocation lineLocation = probe.getProbedSourceSection().getLineLocation();
        assertEquals(lineLocation, expr13Line2);

        tool.install();

        assertNull(tool.findFirstProbe(expr13Line1));
        assertNull(tool.findFirstProbe(expr13Line2));
        tool.dispose();
    }

    @Test
    public void testMapping() {
        final LineToProbesMap tool = new LineToProbesMap();
        tool.install();

        final RootNode expr13rootNode = createExpr13TestRootNode();
        final Node addNode = expr13rootNode.getChildren().iterator().next();
        final Probe probe = addNode.probe();
        final LineLocation lineLocation = probe.getProbedSourceSection().getLineLocation();
        assertEquals(lineLocation, expr13Line2);

        assertNull(tool.findFirstProbe(expr13Line1));
        assertEquals(tool.findFirstProbe(expr13Line2), probe);
        tool.dispose();
    }

}
