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
package com.oracle.graal.truffle;

import com.oracle.graal.options.*;

/**
 * Options for the Truffle compiler.
 */
public class TruffleCompilerOptions {

    // @formatter:off
    // configuration
    /**
     * Instructs the Truffle Compiler to compile call targets only if their name contains at least one element of a comma-separated list of includes.
     * Excludes are prefixed with a tilde (~).
     * 
     * The format in EBNF:
     * <pre>
     * CompileOnly = Element, { ',', Element } ;
     * Element = Include | '~' Exclude ;
     * </pre>
     */
    @Option(help = "")
    public static final OptionValue<String> TruffleCompileOnly = new OptionValue<>(null);
    @Option(help = "")
    public static final OptionValue<Integer> TruffleCompilationThreshold = new OptionValue<>(1000);
    @Option(help = "")
    public static final OptionValue<Integer> TruffleInvalidationReprofileCount = new OptionValue<>(3);
    @Option(help = "")
    public static final OptionValue<Integer> TruffleInliningReprofileCount = new OptionValue<>(100);
    @Option(help = "")
    public static final OptionValue<Boolean> TruffleFunctionInlining = new OptionValue<>(true);
    @Option(help = "")
    public static final OptionValue<Integer> TruffleConstantUnrollLimit = new OptionValue<>(32);
    @Option(help = "")
    public static final OptionValue<Integer> TruffleOperationCacheMaxNodes = new OptionValue<>(200);
    @Option(help = "")
    public static final OptionValue<Integer> TruffleGraphMaxNodes = new OptionValue<>(12000);
    @Option(help = "")
    public static final OptionValue<Integer> TruffleInliningMaxCallerSize = new OptionValue<>(300);
    @Option(help = "")
    public static final OptionValue<Integer> TruffleInliningMaxCalleeSize = new OptionValue<>(62);

    // tracing
    @Option(help = "")
    public static final OptionValue<Boolean> TraceTruffleCompilation = new OptionValue<>(true);
    @Option(help = "")
    public static final OptionValue<Boolean> TraceTruffleCompilationDetails = new OptionValue<>(false);
    @Option(help = "")
    public static final OptionValue<Boolean> TraceTruffleCacheDetails = new OptionValue<>(false);
    @Option(help = "")
    public static final OptionValue<Boolean> TraceTrufflePerformanceWarnings = new OptionValue<>(false);
    @Option(help = "")
    public static final OptionValue<Boolean> TruffleInlinePrinter = new OptionValue<>(false);
    @Option(help = "")
    public static final OptionValue<Boolean> TraceTruffleCompilationExceptions = new OptionValue<>(true);
    @Option(help = "")
    public static final OptionValue<Boolean> TruffleCompilationExceptionsAreFatal = new OptionValue<>(true);
    @Option(help = "")
    public static final OptionValue<Boolean> TraceTruffleInlining = new OptionValue<>(true);
    @Option(help = "")
    public static final OptionValue<Boolean> TraceTruffleInliningDetails = new OptionValue<>(false);
    @Option(help = "")
    public static final OptionValue<Boolean> TruffleProfiling = new StableOptionValue<>(false);
    // @formatter:on
}
