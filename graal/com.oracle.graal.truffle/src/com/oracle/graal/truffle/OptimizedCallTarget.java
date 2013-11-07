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

import static com.oracle.graal.truffle.TruffleCompilerOptions.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.debug.*;
import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.impl.*;
import com.oracle.truffle.api.nodes.*;

/**
 * Call target that is optimized by Graal upon surpassing a specific invocation threshold.
 */
public final class OptimizedCallTarget extends DefaultCallTarget implements FrameFactory, LoopCountReceiver, ReplaceObserver {

    private static final PrintStream OUT = TTY.out().out();

    private InstalledCode installedCode;
    private Future<InstalledCode> installedCodeTask;
    private final TruffleCompiler compiler;
    private final CompilationProfile compilationProfile;
    private final CompilationPolicy compilationPolicy;
    private final TruffleInlining inlining;
    private boolean disableCompilation;
    private int callCount;

    protected OptimizedCallTarget(RootNode rootNode, FrameDescriptor descriptor, TruffleCompiler compiler, int invokeCounter, int compilationThreshold) {
        super(rootNode, descriptor);
        this.compiler = compiler;
        this.compilationProfile = new CompilationProfile(compilationThreshold, invokeCounter, rootNode.toString());
        this.inlining = new TruffleInliningImpl();
        this.rootNode.setCallTarget(this);

        if (TruffleUseTimeForCompilationDecision.getValue()) {
            compilationPolicy = new TimedCompilationPolicy();
        } else {
            compilationPolicy = new DefaultCompilationPolicy();
        }

        if (TruffleCallTargetProfiling.getValue()) {
            registerCallTarget(this);
        }
    }

    @Override
    public Object call(PackedFrame caller, Arguments args) {
        return callHelper(caller, args);
    }

    public CompilationProfile getCompilationProfile() {
        return compilationProfile;
    }

    private Object callHelper(PackedFrame caller, Arguments args) {
        if (installedCode != null && installedCode.isValid()) {
            TruffleRuntime runtime = Truffle.getRuntime();
            if (runtime instanceof GraalTruffleRuntime) {
                OUT.printf("[truffle] reinstall OptimizedCallTarget.call code with frame prolog shortcut.");
                OUT.println();
                GraalTruffleRuntime.installOptimizedCallTargetCallMethod();
            }
        }
        if (TruffleCallTargetProfiling.getValue()) {
            callCount++;
        }
        if (CompilerDirectives.injectBranchProbability(CompilerDirectives.FASTPATH_PROBABILITY, installedCode != null)) {
            try {
                return installedCode.execute(this, caller, args);
            } catch (InvalidInstalledCodeException ex) {
                return compiledCodeInvalidated(caller, args);
            }
        } else {
            return interpreterCall(caller, args);
        }
    }

    private Object compiledCodeInvalidated(PackedFrame caller, Arguments args) {
        invalidate();
        return call(caller, args);
    }

    private void invalidate() {
        InstalledCode m = this.installedCode;
        if (m != null) {
            CompilerAsserts.neverPartOfCompilation();
            installedCode = null;
            compilationProfile.reportInvalidated();
            if (TraceTruffleCompilation.getValue()) {
                OUT.printf("[truffle] invalidated %-48s |Inv# %d                                     |Replace# %d\n", rootNode, compilationProfile.getInvalidationCount(),
                                compilationProfile.getNodeReplaceCount());
            }
        }

        Future<InstalledCode> task = this.installedCodeTask;
        if (task != null) {
            task.cancel(true);
            this.installedCodeTask = null;
            compilationProfile.reportInvalidated();
        }
    }

    private Object interpreterCall(PackedFrame caller, Arguments args) {
        CompilerAsserts.neverPartOfCompilation();
        compilationProfile.reportInterpreterCall();
        if (disableCompilation || !compilationPolicy.shouldCompile(compilationProfile)) {
            return executeHelper(caller, args);
        } else {
            return compileOrInline(caller, args);
        }
    }

    private Object compileOrInline(PackedFrame caller, Arguments args) {
        if (installedCodeTask != null) {
            // There is already a compilation running.
            if (installedCodeTask.isCancelled()) {
                installedCodeTask = null;
            } else {
                if (installedCodeTask.isDone()) {
                    receiveInstalledCode();
                }
                return executeHelper(caller, args);
            }
        }

        if (TruffleFunctionInlining.getValue() && inline()) {
            compilationProfile.reportInliningPerformed();
            return call(caller, args);
        } else {
            compile();
            return executeHelper(caller, args);
        }
    }

    private void receiveInstalledCode() {
        try {
            this.installedCode = installedCodeTask.get();
            if (TruffleCallTargetProfiling.getValue()) {
                resetProfiling();
            }
        } catch (InterruptedException | ExecutionException e) {
            disableCompilation = true;
            OUT.printf("[truffle] opt failed %-48s  %s\n", rootNode, e.getMessage());
            if (e.getCause() instanceof BailoutException) {
                // Bailout => move on.
            } else {
                if (TraceTruffleCompilationExceptions.getValue()) {
                    e.printStackTrace(OUT);
                }
                if (TruffleCompilationExceptionsAreFatal.getValue()) {
                    System.exit(-1);
                }
            }
        }
        installedCodeTask = null;
    }

    public boolean inline() {
        CompilerAsserts.neverPartOfCompilation();
        return inlining.performInlining(this);
    }

    public void compile() {
        CompilerAsserts.neverPartOfCompilation();
        this.installedCodeTask = compiler.compile(this);
        if (!TruffleBackgroundCompilation.getValue()) {
            receiveInstalledCode();
        }
    }

    public Object executeHelper(PackedFrame caller, Arguments args) {
        VirtualFrame frame = createFrame(frameDescriptor, caller, args);
        return rootNode.execute(frame);
    }

    protected static FrameWithoutBoxing createFrame(FrameDescriptor descriptor, PackedFrame caller, Arguments args) {
        return new FrameWithoutBoxing(descriptor, caller, args);
    }

    @Override
    public VirtualFrame create(FrameDescriptor descriptor, PackedFrame caller, Arguments args) {
        return createFrame(descriptor, caller, args);
    }

    @Override
    public void reportLoopCount(int count) {
        compilationProfile.reportLoopCount(count);
    }

    @Override
    public void nodeReplaced() {
        compilationProfile.reportNodeReplaced();
        invalidate();
    }

    private static void resetProfiling() {
        for (OptimizedCallTarget callTarget : OptimizedCallTarget.callTargets.keySet()) {
            callTarget.callCount = 0;
        }
    }

    private static void printProfiling() {
        List<OptimizedCallTarget> sortedCallTargets = new ArrayList<>(OptimizedCallTarget.callTargets.keySet());
        Collections.sort(sortedCallTargets, new Comparator<OptimizedCallTarget>() {

            @Override
            public int compare(OptimizedCallTarget o1, OptimizedCallTarget o2) {
                return o2.callCount - o1.callCount;
            }
        });

        int totalCallCount = 0;
        int totalInlinedCallSiteCount = 0;
        int totalNotInlinedCallSiteCount = 0;
        int totalNodeCount = 0;
        int totalInvalidationCount = 0;

        OUT.println();
        OUT.printf("%-50s | %-10s | %s / %s | %s | %s\n", "Call Target", "Call Count", "Calls Sites Inlined", "Not Inlined", "Node Count", "Inv");
        for (OptimizedCallTarget callTarget : sortedCallTargets) {
            if (callTarget.callCount == 0) {
                continue;
            }

            int notInlinedCallSiteCount = TruffleInliningImpl.getInlinableCallSites(callTarget).size();
            int nodeCount = NodeUtil.countNodes(callTarget.rootNode);
            int inlinedCallSiteCount = NodeUtil.countNodes(callTarget.rootNode, InlinedCallSite.class);
            String comment = callTarget.installedCode == null ? " int" : "";
            comment += callTarget.disableCompilation ? " fail" : "";
            OUT.printf("%-50s | %10d | %15d | %15d | %10d | %3d%s\n", callTarget.getRootNode(), callTarget.callCount, inlinedCallSiteCount, notInlinedCallSiteCount, nodeCount,
                            callTarget.getCompilationProfile().getInvalidationCount(), comment);

            totalCallCount += callTarget.callCount;
            totalInlinedCallSiteCount += inlinedCallSiteCount;
            totalNotInlinedCallSiteCount += notInlinedCallSiteCount;
            totalNodeCount += nodeCount;
            totalInvalidationCount += callTarget.getCompilationProfile().getInvalidationCount();
        }
        OUT.printf("%-50s | %10d | %15d | %15d | %10d | %3d\n", "Total", totalCallCount, totalInlinedCallSiteCount, totalNotInlinedCallSiteCount, totalNodeCount, totalInvalidationCount);
    }

    private static void registerCallTarget(OptimizedCallTarget callTarget) {
        callTargets.put(callTarget, 0);
    }

    private static Map<OptimizedCallTarget, Integer> callTargets;
    static {
        if (TruffleCallTargetProfiling.getValue()) {
            callTargets = new WeakHashMap<>();

            Runtime.getRuntime().addShutdownHook(new Thread() {

                @Override
                public void run() {
                    printProfiling();
                }
            });
        }
    }
}
