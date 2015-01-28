/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.source;

import java.util.*;

/**
 * A specification for a location in guest language source, expressed as a line number in a specific
 * instance of {@link Source}, suitable for hash table keys with equality defined in terms of
 * content.
 */
public interface LineLocation {

    Source getSource();

    /**
     * Gets the 1-based number of a line in the source.
     */
    int getLineNumber();

    String getShortDescription();

    /**
     * Default comparator by (1) textual path name, (2) line number.
     */
    Comparator<LineLocation> COMPARATOR = new Comparator<LineLocation>() {

        public int compare(LineLocation l1, LineLocation l2) {
            final int sourceResult = l1.getSource().getPath().compareTo(l2.getSource().getPath());
            if (sourceResult != 0) {
                return sourceResult;
            }
            return Integer.compare(l1.getLineNumber(), l2.getLineNumber());
        }

    };

}
