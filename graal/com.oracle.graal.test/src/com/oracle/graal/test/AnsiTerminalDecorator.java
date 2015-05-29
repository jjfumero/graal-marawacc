/*
 * Copyright (c) 2014, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.test;

import static com.oracle.jvmci.debug.AnsiColor.*;

import org.junit.runner.*;
import org.junit.runner.notification.*;

/**
 * Color support for JUnit test output using ANSI escapes codes.
 */
public class AnsiTerminalDecorator extends GraalJUnitRunListenerDecorator {

    public AnsiTerminalDecorator(GraalJUnitRunListener l) {
        super(l);
    }

    @Override
    public void testSucceeded(Description description) {
        getWriter().print(GREEN);
        super.testSucceeded(description);
        getWriter().print(RESET);
    }

    @Override
    public void testAssumptionFailure(Failure failure) {
        getWriter().print(BLUE);
        super.testAssumptionFailure(failure);
        getWriter().print(RESET);
    }

    @Override
    public void testFailed(Failure failure) {
        getWriter().print(RED);
        super.testFailed(failure);
        getWriter().print(RESET);
    }

    @Override
    public void testIgnored(Description description) {
        getWriter().print(MAGENTA);
        super.testIgnored(description);
        getWriter().print(RESET);
    }
}
