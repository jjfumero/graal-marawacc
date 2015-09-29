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
package com.oracle.graal.hotspot;

import static jdk.internal.jvmci.hotspot.HotSpotJVMCIRuntime.runtime;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;

import jdk.internal.jvmci.hotspot.HotSpotJVMCIRuntimeProvider;
import jdk.internal.jvmci.options.OptionValue;

/**
 * An option that encapsulates and configures a print stream.
 */
public class PrintStreamOption extends OptionValue<String> {

    public PrintStreamOption() {
        super(null);
    }

    /**
     * The print stream to which output will be written.
     *
     * Declared {@code volatile} to enable safe use of double-checked locking in
     * {@link #getStream()} and {@link #setValue(Object)}.
     */
    private volatile PrintStream ps;

    /**
     * Replace any instance of %p with a an identifying name. Try to get it from the RuntimeMXBean
     * name.
     *
     * @return the name of the file to log to
     */
    private String getFilename() {
        String name = getValue();
        if (name.contains("%p")) {
            String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
            try {
                int index = runtimeName.indexOf('@');
                if (index != -1) {
                    long pid = Long.parseLong(runtimeName.substring(0, index));
                    runtimeName = Long.toString(pid);
                }
                name = name.replaceAll("%p", runtimeName);
            } catch (NumberFormatException e) {

            }
        }
        return name;
    }

    /**
     * Gets the print stream configured by this option. If no file is configured, the print stream
     * will output to HotSpot's {@link HotSpotJVMCIRuntimeProvider#getLogStream() log} stream.
     */
    public PrintStream getStream() {
        if (ps == null) {
            if (getValue() != null) {
                synchronized (this) {
                    if (ps == null) {
                        try {
                            final boolean enableAutoflush = true;
                            ps = new PrintStream(new FileOutputStream(getFilename()), enableAutoflush);
                            /* Add the JVM and Java arguments to the log file to help identity it. */
                            String inputArguments = String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments());
                            ps.println("VM Arguments: " + inputArguments);
                            String cmd = System.getProperty("sun.java.command");
                            if (cmd != null) {
                                ps.println("sun.java.command=" + cmd);
                            }
                        } catch (FileNotFoundException e) {
                            throw new RuntimeException("couldn't open file: " + getValue(), e);
                        }
                    }
                }
            } else {
                ps = new PrintStream(runtime().getLogStream());
            }
        }
        return ps;
    }

    @Override
    public void setValue(Object v) {
        if (ps != null) {
            synchronized (this) {
                if (ps != null) {
                    ps.close();
                    ps = null;
                }
            }
        }
        super.setValue(v);
    }
}
