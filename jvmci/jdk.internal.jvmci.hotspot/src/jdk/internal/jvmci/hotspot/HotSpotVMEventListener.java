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
package jdk.internal.jvmci.hotspot;

public interface HotSpotVMEventListener {

    /**
     * Notifies this client that HotSpot is running in CompileTheWorld mode and the JVMCI compiler
     * should now perform its version of CompileTheWorld.
     *
     * @param metaspaceMethod
     * @param entryBCI
     * @param jvmciEnv
     * @param id
     */
    default void compileMetaspaceMethod(long metaspaceMethod, int entryBCI, long jvmciEnv, int id) {
    }

    /**
     * Notifies this client that HotSpot is running in CompileTheWorld mode and the JVMCI compiler
     * should now perform its version of CompileTheWorld.
     */
    default void notifyCompileTheWorld() throws Throwable {
    }

    /**
     * Notifies this client that the VM is shutting down.
     */
    default void notifyShutdown() {
    }
}
