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
package com.oracle.graal.hotspot.loader;

import java.io.*;
import java.net.*;

/**
 * Utility to create and register a separate class loader for loading Graal classes (i.e., those in
 * {@code graal.jar} and {@code graal-truffle.jar}).
 */
public class Factory {

    /**
     * Copy of the {@code UseGraalClassLoader} VM option. Set by the VM before the static
     * initializer is called.
     */
    private static boolean useGraalClassLoader;

    /**
     * Registers the Graal class loader in the VM.
     */
    private static native void init(ClassLoader loader);

    static {
        init(useGraalClassLoader ? newClassLoader() : null);
    }

    /**
     * Creates a new class loader for loading classes in {@code graal.jar} and
     * {@code graal-truffle.jar}.
     */
    private static ClassLoader newClassLoader() {
        URL[] urls = {getGraalJarUrl("graal"), getGraalJarUrl("graal-truffle")};
        ClassLoader parent = null;
        return URLClassLoader.newInstance(urls, parent);
    }

    /**
     * Gets the URL for {@code base.jar}.
     */
    private static URL getGraalJarUrl(String base) {
        File file = new File(System.getProperty("java.home"));
        for (String name : new String[]{"lib", base + ".jar"}) {
            file = new File(file, name);
        }

        if (!file.exists()) {
            throw new InternalError(file + " does not exist");
        }

        try {
            return file.toURI().toURL();
        } catch (MalformedURLException e) {
            throw new InternalError(e);
        }
    }
}
