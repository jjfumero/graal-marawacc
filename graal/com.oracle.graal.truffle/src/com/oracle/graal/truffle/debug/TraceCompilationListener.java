package com.oracle.graal.truffle.debug;

import static com.oracle.graal.truffle.TruffleCompilerOptions.*;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.truffle.*;
import com.oracle.truffle.api.source.*;

public class TraceCompilationListener extends AbstractDebugCompilationListener {

    private final ThreadLocal<LocalCompilation> currentCompilation = new ThreadLocal<>();

    private TraceCompilationListener() {
    }

    public static void install(GraalTruffleRuntime runtime) {
        if (TraceTruffleCompilation.getValue() || TraceTruffleCompilationDetails.getValue()) {
            runtime.addCompilationListener(new TraceCompilationListener());
        }
    }

    @Override
    public void notifyCompilationQueued(OptimizedCallTarget target) {
        if (TraceTruffleCompilationDetails.getValue()) {
            log(0, "opt queued", target.toString(), target.getDebugProperties());
        }
    }

    @Override
    public void notifyCompilationDequeued(OptimizedCallTarget target, Object source, CharSequence reason) {
        if (TraceTruffleCompilationDetails.getValue()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            addSourceInfo(properties, source);
            properties.put("Reason", reason);
            log(0, "opt unqueued", target.toString(), properties);
        }
    }

    @Override
    public void notifyCompilationFailed(OptimizedCallTarget target, StructuredGraph graph, Throwable t) {
        super.notifyCompilationFailed(target, graph, t);
        if (!TraceCompilationFailureListener.isPermanentBailout(t)) {
            notifyCompilationDequeued(target, null, "Non permanent bailout: " + t.toString());
        }
        currentCompilation.set(null);
    }

    @Override
    public void notifyCompilationStarted(OptimizedCallTarget target) {
        if (TraceTruffleCompilationDetails.getValue()) {
            log(0, "opt start", target.toString(), target.getDebugProperties());
        }
        LocalCompilation compilation = new LocalCompilation();
        compilation.timeCompilationStarted = System.nanoTime();
        currentCompilation.set(compilation);
    }

    @Override
    public void notifyCompilationTruffleTierFinished(OptimizedCallTarget target, StructuredGraph graph) {
        super.notifyCompilationTruffleTierFinished(target, graph);
        LocalCompilation compilation = currentCompilation.get();
        compilation.timePartialEvaluationFinished = System.nanoTime();
        compilation.nodeCountPartialEval = graph.getNodeCount();
    }

    @Override
    public void notifyCompilationSuccess(OptimizedCallTarget target, StructuredGraph graph, CompilationResult result) {
        long timeCompilationFinished = System.nanoTime();
        int nodeCountLowered = graph.getNodeCount();
        LocalCompilation compilation = currentCompilation.get();
        TruffleInlining inlining = target.getInlining();

        int calls;
        int inlinedCalls;
        if (inlining == null) {
            calls = (int) target.nodeStream(false).filter(node -> (node instanceof OptimizedDirectCallNode)).count();
            inlinedCalls = 0;
        } else {
            calls = inlining.countCalls();
            inlinedCalls = inlining.countInlinedCalls();
        }

        int dispatchedCalls = calls - inlinedCalls;
        Map<String, Object> properties = new LinkedHashMap<>();
        OptimizedCallTargetLog.addASTSizeProperty(target, properties);
        properties.put("Time", String.format("%5.0f(%4.0f+%-4.0f)ms", //
                        (timeCompilationFinished - compilation.timeCompilationStarted) / 1e6, //
                        (compilation.timePartialEvaluationFinished - compilation.timeCompilationStarted) / 1e6, //
                        (timeCompilationFinished - compilation.timePartialEvaluationFinished) / 1e6));
        properties.put("DirectCallNodes", String.format("I %4d/D %4d", inlinedCalls, dispatchedCalls));
        properties.put("GraalNodes", String.format("%5d/%5d", compilation.nodeCountPartialEval, nodeCountLowered));
        properties.put("CodeSize", result.getTargetCodeSize());
        properties.put("Source", formatSourceSection(target.getRootNode().getSourceSection()));

        log(0, "opt done", target.toString(), properties);
        super.notifyCompilationSuccess(target, graph, result);
        currentCompilation.set(null);
    }

    private static String formatSourceSection(SourceSection sourceSection) {
        return sourceSection != null ? sourceSection.getShortDescription() : "n/a";
    }

    @Override
    public void notifyCompilationInvalidated(OptimizedCallTarget target, Object source, CharSequence reason) {
        Map<String, Object> properties = new LinkedHashMap<>();
        addSourceInfo(properties, source);
        properties.put("Reason", reason);
        log(0, "opt invalidated", target.toString(), properties);
    }

    private static void addSourceInfo(Map<String, Object> properties, Object source) {
        if (source != null) {
            properties.put("SourceClass", source.getClass().getSimpleName());
            properties.put("Source", source);
        }
    }

    private static final class LocalCompilation {
        long timeCompilationStarted;
        long timePartialEvaluationFinished;
        long nodeCountPartialEval;
    }

}
