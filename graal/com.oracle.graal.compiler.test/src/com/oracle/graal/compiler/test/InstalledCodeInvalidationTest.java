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
package com.oracle.graal.compiler.test;

import jdk.internal.jvmci.code.InstalledCode;
import jdk.internal.jvmci.code.InvalidInstalledCodeException;

import org.junit.Test;

import com.oracle.graal.api.directives.GraalDirectives;

public class InstalledCodeInvalidationTest extends GraalCompilerTest {

    public void recurse(InstalledCode code, int depth) throws InvalidInstalledCodeException {
        if (depth > 1) {
            /*
             * Recurse a few times to ensure there are multiple activations.
             */
            code.executeVarargs(this, code, depth - 1);
        } else {
            /*
             * Deoptimize this activation and make the compiled code no longer usable.
             */

            GraalDirectives.deoptimizeAndInvalidate();
            assert code.isAlive() && !code.isValid();
            code.invalidate();
            assert !code.isAlive();
        }
        if (GraalDirectives.inCompiledCode()) {
            /*
             * If this still in compiled code then the deoptimizeAndInvalidate call above didn't
             * remove all existing activations.
             */
            throw new InternalError();
        }
    }

    /**
     * Test that after uncommon trapping in an installed code it's still possible to invalidate all
     * existing activations of that installed code.
     *
     * @throws InvalidInstalledCodeException
     */
    @Test
    public void testInstalledCodeInvalidation() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getMetaAccess().lookupJavaMethod(getMethod("recurse")));
        code.executeVarargs(this, code, 3);
    }
}
