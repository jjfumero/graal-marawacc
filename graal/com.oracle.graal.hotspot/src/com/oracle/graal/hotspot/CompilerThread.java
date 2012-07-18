/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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

import java.io.*;
import java.util.concurrent.*;

import com.oracle.graal.compiler.*;
import com.oracle.graal.debug.*;


public final class CompilerThread extends Thread {

    public static final ThreadFactory FACTORY = new ThreadFactory() {

        @Override
        public Thread newThread(Runnable r) {
            return new CompilerThread(r);
        }
    };
    public static final ThreadFactory LOW_PRIORITY_FACTORY = new ThreadFactory() {

        @Override
        public Thread newThread(Runnable r) {
            CompilerThread thread = new CompilerThread(r);
            thread.setPriority(MIN_PRIORITY);
            return thread;
        }
    };

    private CompilerThread(Runnable r) {
        super(r);
        this.setName("GraalCompilerThread-" + this.getId());
        this.setDaemon(true);
    }

    @Override
    public void run() {
        HotSpotDebugConfig hotspotDebugConfig = null;
        if (GraalOptions.Debug) {
            Debug.enable();
            PrintStream log = HotSpotGraalRuntime.getInstance().getVMToCompiler().log();
            hotspotDebugConfig = new HotSpotDebugConfig(GraalOptions.Log, GraalOptions.Meter, GraalOptions.Time, GraalOptions.Dump, GraalOptions.MethodFilter, log);
            Debug.setConfig(hotspotDebugConfig);
        }
        try {
            super.run();
        } finally {
            if (hotspotDebugConfig != null) {
                for (DebugDumpHandler dumpHandler : hotspotDebugConfig.dumpHandlers()) {
                    try {
                        dumpHandler.close();
                    } catch (Throwable t) {

                    }
                }
            }
        }
    }
}
