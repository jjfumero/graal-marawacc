/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.graal.compiler.hsail.test.infra;

/**
 * This class extends KernelTester and provides a base class
 * for which the HSAIL code comes from the Graal compiler.
 */
import com.oracle.graal.compiler.hsail.HSAILCompilationResult;
import java.lang.reflect.Method;
import java.io.*;

public abstract class GraalKernelTester extends KernelTester {

    HSAILCompilationResult hsailCompResult;
    private boolean showHsailSource = false;
    private boolean saveInFile = false;

    @Override
    public String getCompiledHSAILSource(Method testMethod) {
        if (hsailCompResult == null) {
            hsailCompResult = HSAILCompilationResult.getHSAILCompilationResult(testMethod);
        }
        String hsailSource = hsailCompResult.getHSAILCode();
        if (showHsailSource) {
            logger.severe(hsailSource);
        }
        if (saveInFile) {
            try {
                File fout = File.createTempFile("tmp", ".hsail");
                logger.fine("creating " + fout.getCanonicalPath());
                FileWriter fw = new FileWriter(fout);
                BufferedWriter bw = new BufferedWriter(fw);
                bw.write(hsailSource);
                bw.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return hsailSource;
    }

}
