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

import java.util.*;
import java.util.regex.*;

import com.oracle.graal.api.meta.*;

/**
 * This class implements a method filter that can filter based on class name, method name and parameters.
 * The syntax for the source pattern that is passed to the constructor is as follows:
 *
 * <pre>
 * SourcePattern = [ Class "." ] method [ "(" [ Parameter { ";" Parameter } ] ")" ] .
 * Parameter = Class | "int" | "long" | "float" | "double" | "short" | "char" | "boolean" .
 * Class = { package "." } class .
 * </pre>
 *
 *
 * Glob pattern matching (*, ?) is allowed in all parts of the source pattern. Examples for valid filters are:
 *
 * <ul>
 * <li><pre>visit(Argument;BlockScope)</pre>
 * Matches all methods named "visit", with the first parameter of type "Argument", and the second parameter of type "BlockScope".
 * The packages of the parameter types are irrelevant.</li>
 * <li><pre>arraycopy(Object;;;;)</pre>
 * Matches all methods named "arraycopy", with the first parameter of type "Object", and four more parameters of any type.
 * The packages of the parameter types are irrelevant.</li>
 * <li><pre>com.oracle.graal.compiler.graph.PostOrderNodeIterator.*</pre>
 * Matches all methods in the class "com.oracle.graal.compiler.graph.PostOrderNodeIterator".</li>
 * <li><pre>*</pre>
 * Matches all methods in all classes</li>
 * <li><pre>com.oracle.graal.compiler.graph.*.visit</pre>
 * Matches all methods named "visit" in classes in the package "com.oracle.graal.compiler.graph".</pre>
 * </ul>
 */
public class MethodFilter {

    private final Pattern clazz;
    private final Pattern methodName;
    private final Pattern[] signature;

    public MethodFilter(String sourcePattern) {
        String pattern = sourcePattern.trim();

        // extract parameter part
        int pos = pattern.indexOf('(');
        if (pos != -1) {
            if (pattern.charAt(pattern.length() - 1) != ')') {
                throw new IllegalArgumentException("missing ')' at end of method filter pattern: " + pattern);
            }
            String[] signatureClasses = pattern.substring(pos + 1, pattern.length() - 1).split(";", -1);
            signature = new Pattern[signatureClasses.length];
            for (int i = 0; i < signatureClasses.length; i++) {
                signature[i] = createClassGlobPattern(signatureClasses[i].trim());
            }
            pattern = pattern.substring(0, pos);
        } else {
            signature = null;
        }

        // If there is at least one "." then everything before the last "." is the class name.
        // Otherwise, the pattern contains only the method name.
        pos = pattern.lastIndexOf('.');
        if (pos != -1) {
            clazz = createClassGlobPattern(pattern.substring(0, pos));
            methodName = Pattern.compile(createGlobString(pattern.substring(pos + 1)));
        } else {
            clazz = null;
            methodName = Pattern.compile(createGlobString(pattern));
        }
    }

    static String createGlobString(String pattern) {
        return Pattern.quote(pattern).replace("?", "\\E.\\Q").replace("*", "\\E.*\\Q");
    }

    private static Pattern createClassGlobPattern(String pattern) {
        if (pattern.length() == 0) {
            return null;
        } else if (pattern.contains(".")) {
            return Pattern.compile(createGlobString(pattern));
        } else {
            return Pattern.compile("([^\\.]*\\.)*" + createGlobString(pattern));
        }
    }

    public boolean matches(JavaMethod o) {
        // check method name first, since MetaUtil.toJavaName is expensive
        if (methodName != null && !methodName.matcher(o.name()).matches()) {
            return false;
        }
        if (clazz != null && !clazz.matcher(MetaUtil.toJavaName(o.holder())).matches()) {
            return false;
        }
        if (signature != null) {
            Signature sig = o.signature();
            if (sig.argumentCount(false) != signature.length) {
                return false;
            }
            for (int i = 0; i < signature.length; i++) {
                JavaType type = sig.argumentTypeAt(i, null);
                String javaName = MetaUtil.toJavaName(type);
                if (signature[i] != null && !signature[i].matcher(javaName).matches()) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder("MethodFilter[");
        String sep = "";
        if (clazz != null) {
            buf.append(sep).append("clazz=").append(clazz);
            sep = ", ";
        }
        if (methodName != null) {
            buf.append(sep).append("methodName=").append(methodName);
            sep = ", ";
        }
        if (signature != null) {
            buf.append(sep).append("signature=").append(Arrays.toString(signature));
            sep = ", ";
        }
        return buf.append("]").toString();
    }
}
