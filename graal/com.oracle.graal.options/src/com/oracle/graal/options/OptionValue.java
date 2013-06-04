/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.options;

import java.util.*;

/**
 * A settable option value.
 * <p>
 * To access {@link OptionProvider} instances via a {@link ServiceLoader} for working with options,
 * instances of this class should be assigned to static final fields that are annotated with
 * {@link Option}.
 */
public class OptionValue<T> {

    /**
     * The raw option value.
     */
    protected T value;

    /**
     * Creates an option value.
     * 
     * @param value the initial/default value of the option
     */
    public OptionValue(T value) {
        this.value = value;
    }

    private static final Object UNINITIALIZED = "UNINITIALIZED";

    /**
     * Creates an uninitialized option value for a subclass that initializes itself
     * {@link #initialValue() lazily}.
     */
    @SuppressWarnings("unchecked")
    protected OptionValue() {
        this.value = (T) UNINITIALIZED;
    }

    /**
     * Lazy initialization of value.
     */
    protected T initialValue() {
        throw new InternalError("Uninitialized option value must override initialValue()");
    }

    /**
     * Gets the value of this option.
     */
    public final T getValue() {
        if (value == UNINITIALIZED) {
            value = initialValue();
        }
        return value;
    }

    /**
     * Sets the value of this option.
     */
    @SuppressWarnings("unchecked")
    public final void setValue(Object v) {
        this.value = (T) v;
    }
}
