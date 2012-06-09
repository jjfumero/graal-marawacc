/*
 * Copyright (c) 2011, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.printer;

import java.io.*;
import java.util.*;

import com.oracle.max.criutils.*;
import com.oracle.graal.alloc.util.*;
import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.*;
import com.oracle.graal.compiler.alloc.*;
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.java.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.cfg.*;
import com.oracle.graal.nodes.*;

/**
 * Observes compilation events and uses {@link CFGPrinter} to produce a control flow graph for the <a
 * href="http://java.net/projects/c1visualizer/">C1 Visualizer</a>.
 */
public class CFGPrinterObserver implements DebugDumpHandler {

    private CFGPrinter cfgPrinter;
    private ResolvedJavaMethod curMethod;
    private List<String> curDecorators = Collections.emptyList();

    @Override
    public void dump(Object object, String message) {
        try {
            dumpSandboxed(object, message);
        } catch (Throwable ex) {
            TTY.println("CFGPrinter: Exception during output of " + message + ": " + ex);
        }
    }

    /**
     * Looks for the outer most method and its {@link DebugDumpScope#decorator}s
     * in the current debug scope and opens a new compilation scope if this pair
     * does not match the current method and decorator pair.
     */
    private void checkMethodScope() {
        ResolvedJavaMethod method = null;
        ArrayList<String> decorators = new ArrayList<>();
        for (Object o : Debug.context()) {
            if (o instanceof ResolvedJavaMethod) {
                method = (ResolvedJavaMethod) o;
                decorators.clear();
            } else if (o instanceof StructuredGraph) {
                StructuredGraph graph = (StructuredGraph) o;
                assert graph != null && graph.method() != null : "cannot find method context for CFG dump";
                method = graph.method();
                decorators.clear();
            } else if (o instanceof DebugDumpScope) {
                DebugDumpScope debugDumpScope = (DebugDumpScope) o;
                if (debugDumpScope.decorator) {
                    decorators.add(debugDumpScope.name);
                }
            }
        }

        if (method != curMethod || !curDecorators.equals(decorators)) {
            cfgPrinter.printCompilation(method);
            TTY.println("CFGPrinter: Dumping method %s", method);
            curMethod = method;
            curDecorators = decorators;
        }
    }

    public void dumpSandboxed(Object object, String message) {
        GraalCompiler compiler = Debug.contextLookup(GraalCompiler.class);
        if (compiler == null) {
            return;
        }

        if (cfgPrinter == null) {
            File file = new File("compilations-" + System.currentTimeMillis() + ".cfg");
            try {
                OutputStream out = new BufferedOutputStream(new FileOutputStream(file));
                cfgPrinter = new CFGPrinter(out);
            } catch (FileNotFoundException e) {
                throw new GraalInternalError("Could not open " + file.getAbsolutePath());
            }
            TTY.println("CFGPrinter: Output to file %s", file);
        }

        checkMethodScope();

        cfgPrinter.target = compiler.target;
        if (object instanceof LIR) {
            cfgPrinter.lir = (LIR) object;
        } else {
            cfgPrinter.lir = Debug.contextLookup(LIR.class);
        }
        cfgPrinter.lirGenerator = Debug.contextLookup(LIRGenerator.class);
        if (cfgPrinter.lir != null) {
            cfgPrinter.cfg = cfgPrinter.lir.cfg;
        }

        CodeCacheProvider runtime = compiler.runtime;

        if (object instanceof BciBlockMapping) {
            BciBlockMapping blockMap = (BciBlockMapping) object;
            cfgPrinter.printCFG(message, blockMap);
            cfgPrinter.printBytecodes(runtime.disassemble(blockMap.method));

        } else if (object instanceof LIR) {
            cfgPrinter.printCFG(message, cfgPrinter.lir.codeEmittingOrder());

        } else if (object instanceof StructuredGraph) {
            if (cfgPrinter.cfg == null) {
                cfgPrinter.cfg = ControlFlowGraph.compute((StructuredGraph) object, true, true, true, false);
            }
            cfgPrinter.printCFG(message, Arrays.asList(cfgPrinter.cfg.getBlocks()));

        } else if (object instanceof CompilationResult) {
            final CompilationResult tm = (CompilationResult) object;
            final byte[] code = Arrays.copyOf(tm.targetCode(), tm.targetCodeSize());
            CodeInfo info = new CodeInfo() {
                public ResolvedJavaMethod method() {
                    return null;
                }
                public long start() {
                    return 0L;
                }
                public byte[] code() {
                    return code;
                }
            };
            cfgPrinter.printMachineCode(runtime.disassemble(info, tm), message);
        } else if (object instanceof CodeInfo) {
            cfgPrinter.printMachineCode(runtime.disassemble((CodeInfo) object, null), message);
        } else if (object instanceof Interval[]) {
            cfgPrinter.printIntervals(message, (Interval[]) object);

        } else if (object instanceof IntervalPrinter.Interval[]) {
            cfgPrinter.printIntervals(message, (IntervalPrinter.Interval[]) object);
        }

        cfgPrinter.target = null;
        cfgPrinter.lir = null;
        cfgPrinter.lirGenerator = null;
        cfgPrinter.cfg = null;
        cfgPrinter.flush();
    }
}
