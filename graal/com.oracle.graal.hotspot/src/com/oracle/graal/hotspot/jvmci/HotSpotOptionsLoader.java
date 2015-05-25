/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.jvmci;

import java.util.*;

import com.oracle.graal.options.*;
import com.oracle.jvmci.runtime.*;

/**
 * Helper class for separating loading of options from option initialization at runtime.
 */
class HotSpotOptionsLoader {
    static final SortedMap<String, OptionDescriptor> options = new TreeMap<>();

    /**
     * Initializes {@link #options} from {@link Options} services.
     */
    static {
        for (Options opts : Services.load(Options.class)) {
            for (OptionDescriptor desc : opts) {
                if (isHotSpotOption(desc)) {
                    String name = desc.getName();
                    OptionDescriptor existing = options.put(name, desc);
                    assert existing == null : "Option named \"" + name + "\" has multiple definitions: " + existing.getLocation() + " and " + desc.getLocation();
                }
            }
        }
    }

    /**
     * Determines if a given option is a HotSpot command line option.
     */
    private static boolean isHotSpotOption(OptionDescriptor desc) {
        return desc.getClass().getName().startsWith("com.oracle.graal");
    }
}
