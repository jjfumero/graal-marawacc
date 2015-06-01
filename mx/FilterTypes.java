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

public class FilterTypes {

    /**
     * Prints to {@link System#out} the values in {@code args[1 .. N]} that denote classes that are
     * {@link Class#isAssignableFrom(Class) assignable} to the type denoted in {@code args[0]}. The
     * values are separated by {@code "|"}.
     */
    public static void main(String... args) throws Exception {
        Class<?> jvmciServiceInterface = Class.forName(args[0]);
        boolean needSep = false;
        StringBuilder buf = new StringBuilder();
        for (int i = 1; i < args.length; ++i) {
            String serviceName = args[i];
            Class<?> service = lookupService(serviceName);
            if (jvmciServiceInterface.isAssignableFrom(service)) {
                if (buf.length() != 0) {
                    buf.append('|');
                }
                buf.append(serviceName);
                needSep = true;
            }
        }
        System.out.print(buf);
    }
    
    private static Class<?> lookupService(String serviceName) {
    	try {
    		// This can fail in the case of running against a JDK
    		// with out of date JVMCI jars. In that case, just print
    		// a warning sinc the expectation is that the jars will be
    		// updated later on.
    	    return Class.forName(serviceName, false, FilterTypes.class.getClassLoader());
    	} catch (ClassNotFoundException e) {
    		// Must be stderr to avoid polluting the result being
    		// written to stdout.
    		System.err.println(e);
    	}
    }
}
