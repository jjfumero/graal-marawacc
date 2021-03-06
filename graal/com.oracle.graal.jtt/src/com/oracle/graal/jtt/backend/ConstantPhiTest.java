/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.jtt.backend;

import static com.oracle.graal.api.directives.GraalDirectives.LIKELY_PROBABILITY;
import static com.oracle.graal.api.directives.GraalDirectives.injectBranchProbability;

import java.lang.reflect.Method;

import jdk.vm.ci.options.OptionValue;
import jdk.vm.ci.options.OptionValue.OverrideScope;

import org.junit.Test;

import com.oracle.graal.compiler.common.GraalOptions;
import com.oracle.graal.jtt.JTTTest;

public class ConstantPhiTest extends JTTTest {

    public static int test(int i, int x) throws Throwable {
        int r;
        if (injectBranchProbability(LIKELY_PROBABILITY, i < 0)) {
            r = 42;
        } else {
            r = x;
        }
        destroyCallerSavedValues();
        return r;
    }

    protected static void destroyCallerSavedValues() throws Throwable {
        Class<ConstantPhiTest> c = ConstantPhiTest.class;
        Method m = c.getMethod("destroyCallerSavedValues0");
        m.invoke(null);
    }

    public static void destroyCallerSavedValues0() {
    }

    @Test
    @SuppressWarnings("try")
    public void run0() {
        try (OverrideScope os = OptionValue.override(GraalOptions.MaximumInliningSize, -1)) {
            runTest("test", 0, 0xDEADDEAD);
        }
    }

    @Test
    @SuppressWarnings("try")
    public void run1() {
        try (OverrideScope os = OptionValue.override(GraalOptions.MaximumInliningSize, -1)) {
            runTest("test", -1, 0xDEADDEAD);
        }
    }

    @Test
    @SuppressWarnings("try")
    public void run2() {
        try (OverrideScope os = OptionValue.override(GraalOptions.MaximumInliningSize, -1)) {
            runTest("test", 1, 0xDEADDEAD);
        }
    }
}
