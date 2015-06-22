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
package jdk.internal.jvmci.hotspotvmconfig;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface HotSpotVMValue {

    /**
     * A C++ expression to be evaluated and assigned to the field.
     */
    String expression();

    enum Type {
        /**
         * A C++ address which might require extra casts to be safely assigned to a Java field.
         */
        ADDRESS,

        /**
         * A simple value which can be assigned to a regular Java field.
         */
        VALUE
    }

    /**
     * If {@link #expression} is a C++ function name, {@link #signature} represents the signature of
     * the function.
     *
     */
    String signature() default "";

    Type get() default Type.VALUE;

    /**
     * List of preprocessor symbols that should guard initialization of this value.
     */
    String[] defines() default {};

}
