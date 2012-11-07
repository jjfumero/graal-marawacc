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

import java.util.concurrent.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.phases.*;


public final class CompilationTask implements Runnable, Comparable<CompilationTask> {


    public static final ThreadLocal<Boolean> withinEnqueue = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.valueOf(Thread.currentThread() instanceof CompilerThread);
        }
    };

    public static final ThreadLocal<Long> withinCompilation = new ThreadLocal<Long>() {
        @Override
        protected Long initialValue() {
            return 0L;
        }
    };


    private volatile boolean cancelled;

    private final HotSpotGraalRuntime graalRuntime;
    private final PhasePlan plan;
    private final OptimisticOptimizations optimisticOpts;
    private final HotSpotResolvedJavaMethod method;
    private final int entryBCI;
    private final int id;
    private final int priority;
    private final Runnable callback;

    public static CompilationTask create(HotSpotGraalRuntime graalRuntime, PhasePlan plan, OptimisticOptimizations optimisticOpts, HotSpotResolvedJavaMethod method, int entryBCI, int id, int priority, Runnable callback) {
        return new CompilationTask(graalRuntime, plan, optimisticOpts, method, entryBCI, id, priority, callback);
    }

    private CompilationTask(HotSpotGraalRuntime graalRuntime, PhasePlan plan, OptimisticOptimizations optimisticOpts, HotSpotResolvedJavaMethod method, int entryBCI, int id, int priority, Runnable callback) {
        this.graalRuntime = graalRuntime;
        this.plan = plan;
        this.method = method;
        this.optimisticOpts = optimisticOpts;
        this.entryBCI = entryBCI;
        this.id = id;
        this.priority = priority;
        this.callback = callback;
    }

    public ResolvedJavaMethod method() {
        return method;
    }

    public int priority() {
        return priority;
    }

    public void cancel() {
        cancelled = true;
    }

    public void run() {
        withinEnqueue.set(Boolean.FALSE);
        try {
            if (cancelled) {
                return;
            }
            if (GraalOptions.DynamicCompilePriority) {
                int threadPriority = priority < GraalOptions.SlowQueueCutoff ? Thread.NORM_PRIORITY : Thread.MIN_PRIORITY;
                if (Thread.currentThread().getPriority() != threadPriority) {
                    Thread.currentThread().setPriority(threadPriority);
                }
            }
            runCompilation();
            if (method.currentTask() == this && entryBCI == StructuredGraph.INVOCATION_ENTRY_BCI) {
                method.setCurrentTask(null);
            }
        } finally {
            withinEnqueue.set(Boolean.TRUE);
        }
    }

    public void runCompilation() {
        withinCompilation.set(withinCompilation.get() + 1);
        CompilationStatistics stats = CompilationStatistics.create(method);
        try {
            final boolean printCompilation = GraalOptions.PrintCompilation && !TTY.isSuppressed();
            if (printCompilation) {
                TTY.println(String.format("%-6d Graal %-70s %-45s %-50s ...", id, method.getDeclaringClass().getName(), method.getName(), method.getSignature()));
            }

            CompilationResult result = null;
            TTY.Filter filter = new TTY.Filter(GraalOptions.PrintFilter, method);
            try {
                result = Debug.scope("Compiling", new DebugDumpScope(String.valueOf(id), true), new Callable<CompilationResult>() {

                    @Override
                    public CompilationResult call() throws Exception {
                        graalRuntime.evictDeoptedGraphs();
                        StructuredGraph graph = new StructuredGraph(method, -1);
                        return graalRuntime.getCompiler().compileMethod(method, graph, graalRuntime.getCache(), plan, optimisticOpts);
                    }
                });
            } finally {
                filter.remove();
                if (printCompilation) {
                    TTY.println(String.format("%-6d Graal %-70s %-45s %-50s | %4dnodes %5dB", id, "", "", "", 0, (result != null ? result.getTargetCodeSize() : -1)));
                }
            }

            installMethod(result);
        } catch (BailoutException bailout) {
            Debug.metric("Bailouts").increment();
            if (GraalOptions.ExitVMOnBailout) {
                TTY.cachedOut.println(MetaUtil.format("Bailout in %H.%n(%p)", method));
                bailout.printStackTrace(TTY.cachedOut);
                System.exit(-1);
            } else if (GraalOptions.PrintBailout) {
                TTY.cachedOut.println(MetaUtil.format("Bailout in %H.%n(%p)", method));
                bailout.printStackTrace(TTY.cachedOut);
            }
        } catch (Throwable t) {
            if (GraalOptions.ExitVMOnException) {
                t.printStackTrace(TTY.cachedOut);
                System.exit(-1);
            }
        }
        stats.finish(method);
        if (callback != null) {
            callback.run();
        }
        withinCompilation.set(withinCompilation.get() - 1);
    }

    private void installMethod(final CompilationResult tm) {
        Debug.scope("CodeInstall", new Object[] {new DebugDumpScope(String.valueOf(id), true), graalRuntime.getCompiler(), method}, new Runnable() {
            @Override
            public void run() {
                final CodeInfo[] info = Debug.isDumpEnabled() ? new CodeInfo[1] : null;
                graalRuntime.getRuntime().installMethod(method, entryBCI, tm, info);
                if (info != null) {
                    Debug.dump(new Object[] {tm, info[0]}, "After code installation");
                }
            }

        });
    }

    @Override
    public int compareTo(CompilationTask o) {
        if (priority < o.priority) {
            return -1;
        }
        if (priority > o.priority) {
            return 1;
        }
        return id < o.id ? -1 : (id > o.id ? 1 : 0);
    }

}
