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
package com.oracle.graal.compiler;

import java.io.*;
import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.options.*;

public class GraalDebugConfig implements DebugConfig {

    // @formatter:off
    @Option(help = "Pattern for scope(s) in which dumping is enabled (see DebugFilter and Debug.dump)", type = OptionType.Debug)
    public static final OptionValue<String> Dump = new OptionValue<>(null);
    @Option(help = "Pattern for scope(s) in which metering is enabled (see DebugFilter and Debug.metric)", type = OptionType.Debug)
    public static final OptionValue<String> Meter = new OptionValue<>(null);
    @Option(help = "Pattern for scope(s) in which verification is enabled (see DebugFilter and Debug.verify)", type = OptionType.Debug)
    public static final OptionValue<String> Verify = new OptionValue<>(null);
    @Option(help = "Pattern for scope(s) in which memory use tracking is enabled (see DebugFilter and Debug.metric)", type = OptionType.Debug)
    public static final OptionValue<String> TrackMemUse = new OptionValue<>(null);
    @Option(help = "Pattern for scope(s) in which timing is enabled (see DebugFilter and Debug.timer)", type = OptionType.Debug)
    public static final OptionValue<String> Time = new OptionValue<>(null);
    @Option(help = "Pattern for scope(s) in which logging is enabled (see DebugFilter and Debug.log)", type = OptionType.Debug)
    public static final OptionValue<String> Log = new OptionValue<>(null);
    @Option(help = "Pattern for filtering debug scope output based on method context (see MethodFilter)", type = OptionType.Debug)
    public static final OptionValue<String> MethodFilter = new OptionValue<>(null);
    @Option(help = "Only check MethodFilter against the root method in the context if true, otherwise check all methods", type = OptionType.Debug)
    public static final OptionValue<Boolean> MethodFilterRootOnly = new OptionValue<>(false);

    @Option(help = "How to print metric and timing values:%n" +
                   "Name - aggregate by unqualified name%n" +
                   "Partial - aggregate by partially qualified name (e.g., A.B.C.D.Counter and X.Y.Z.D.Counter will be merged to D.Counter)%n" +
                   "Complete - aggregate by qualified name%n" +
                   "Thread - aggregate by qualified name and thread", type = OptionType.Debug)
    public static final OptionValue<String> DebugValueSummary = new OptionValue<>("Name");
    @Option(help = "Omit reporting 0-value metrics", type = OptionType.Debug)
    public static final OptionValue<Boolean> SuppressZeroDebugValues = new OptionValue<>(true);
    @Option(help = "Send Graal IR to dump handlers on error", type = OptionType.Debug)
    public static final OptionValue<Boolean> DumpOnError = new OptionValue<>(false);
    @Option(help = "Intercept also bailout exceptions", type = OptionType.Debug)
    public static final OptionValue<Boolean> InterceptBailout = new OptionValue<>(false);
    @Option(help = "Enable more verbose log output when available", type = OptionType.Debug)
    public static final OptionValue<Boolean> LogVerbose = new OptionValue<>(false);
    // @formatter:on

    public static boolean areDebugScopePatternsEnabled() {
        return DumpOnError.getValue() || Dump.getValue() != null || Log.getValue() != null || areMetricsOrTimersEnabled();
    }

    public static boolean areMetricsOrTimersEnabled() {
        return Meter.getValue() != null || Time.getValue() != null || TrackMemUse.getValue() != null;
    }

    private final DebugFilter logFilter;
    private final DebugFilter meterFilter;
    private final DebugFilter trackMemUseFilter;
    private final DebugFilter timerFilter;
    private final DebugFilter dumpFilter;
    private final DebugFilter verifyFilter;
    private final MethodFilter[] methodFilter;
    private final List<DebugDumpHandler> dumpHandlers;
    private final List<DebugVerifyHandler> verifyHandlers;
    private final PrintStream output;
    private final Set<Object> extraFilters = new HashSet<>();

    public GraalDebugConfig(String logFilter, String meterFilter, String trackMemUseFilter, String timerFilter, String dumpFilter, String verifyFilter, String methodFilter, PrintStream output,
                    List<DebugDumpHandler> dumpHandlers, List<DebugVerifyHandler> verifyHandlers) {
        this.logFilter = DebugFilter.parse(logFilter);
        this.meterFilter = DebugFilter.parse(meterFilter);
        this.trackMemUseFilter = DebugFilter.parse(trackMemUseFilter);
        this.timerFilter = DebugFilter.parse(timerFilter);
        this.dumpFilter = DebugFilter.parse(dumpFilter);
        this.verifyFilter = DebugFilter.parse(verifyFilter);
        if (methodFilter == null || methodFilter.isEmpty()) {
            this.methodFilter = null;
        } else {
            this.methodFilter = com.oracle.graal.compiler.MethodFilter.parse(methodFilter);
        }

        // Report the filters that have been configured so the user can verify it's what they expect
        if (logFilter != null || meterFilter != null || timerFilter != null || dumpFilter != null || methodFilter != null) {
            // TTY.println(Thread.currentThread().getName() + ": " + toString());
        }
        this.dumpHandlers = dumpHandlers;
        this.verifyHandlers = verifyHandlers;
        this.output = output;
    }

    public int getLogLevel() {
        return getLevel(logFilter);
    }

    public boolean isLogEnabledForMethod() {
        return isEnabledForMethod(logFilter);
    }

    public boolean isMeterEnabled() {
        return isEnabled(meterFilter);
    }

    public boolean isMemUseTrackingEnabled() {
        return isEnabled(trackMemUseFilter);
    }

    public int getDumpLevel() {
        return getLevel(dumpFilter);
    }

    public boolean isDumpEnabledForMethod() {
        return isEnabledForMethod(dumpFilter);
    }

    public boolean isVerifyEnabled() {
        return isEnabled(verifyFilter);
    }

    public boolean isVerifyEnabledForMethod() {
        return isEnabledForMethod(verifyFilter);
    }

    public boolean isTimeEnabled() {
        return isEnabled(timerFilter);
    }

    public PrintStream output() {
        return output;
    }

    private boolean isEnabled(DebugFilter filter) {
        return getLevel(filter) > 0;
    }

    private int getLevel(DebugFilter filter) {
        int level = checkDebugFilter(Debug.currentScope(), filter);
        if (level > 0 && !checkMethodFilter()) {
            level = 0;
        }
        return level;
    }

    private boolean isEnabledForMethod(DebugFilter filter) {
        return filter != null && checkMethodFilter();
    }

    private static int checkDebugFilter(String currentScope, DebugFilter filter) {
        if (filter == null) {
            return 0;
        } else {
            return filter.matchLevel(currentScope);
        }
    }

    /**
     * Extracts a {@link JavaMethod} from an opaque debug context.
     *
     * @return the {@link JavaMethod} represented by {@code context} or null
     */
    public static JavaMethod asJavaMethod(Object context) {
        if (context instanceof JavaMethod) {
            return (JavaMethod) context;
        }
        if (context instanceof StructuredGraph) {
            ResolvedJavaMethod method = ((StructuredGraph) context).method();
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    private boolean checkMethodFilter() {
        if (methodFilter == null && extraFilters.isEmpty()) {
            return true;
        } else {
            JavaMethod lastMethod = null;
            for (Object o : Debug.context()) {
                if (extraFilters.contains(o)) {
                    return true;
                } else if (methodFilter != null) {
                    JavaMethod method = asJavaMethod(o);
                    if (method != null) {
                        if (!MethodFilterRootOnly.getValue()) {
                            if (com.oracle.graal.compiler.MethodFilter.matches(methodFilter, method)) {
                                return true;
                            }
                        } else {
                            /*
                             * The context values operate as a stack so if we want MethodFilter to
                             * only apply to the root method we have to check only the last method
                             * seen.
                             */
                            lastMethod = method;
                        }
                    }
                }
            }
            if (lastMethod != null && com.oracle.graal.compiler.MethodFilter.matches(methodFilter, lastMethod)) {
                return true;
            }
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Debug config:");
        add(sb, "Log", logFilter);
        add(sb, "Meter", meterFilter);
        add(sb, "Time", timerFilter);
        add(sb, "Dump", dumpFilter);
        add(sb, "MethodFilter", methodFilter);
        return sb.toString();
    }

    private static void add(StringBuilder sb, String name, Object filter) {
        if (filter != null) {
            sb.append(' ');
            sb.append(name);
            sb.append('=');
            if (filter instanceof Object[]) {
                sb.append(Arrays.toString((Object[]) filter));
            } else {
                sb.append(String.valueOf(filter));
            }
        }
    }

    @Override
    public RuntimeException interceptException(Throwable e) {
        if (e instanceof BailoutException && !InterceptBailout.getValue()) {
            return null;
        }
        Debug.setConfig(Debug.fixedConfig(Debug.DEFAULT_LOG_LEVEL, Debug.DEFAULT_LOG_LEVEL, false, false, false, false, dumpHandlers, verifyHandlers, output));
        Debug.log(String.format("Exception occurred in scope: %s", Debug.currentScope()));
        for (Object o : Debug.context()) {
            if (o instanceof Graph) {
                Debug.log("Context obj %s", o);
                if (DumpOnError.getValue()) {
                    Debug.dump(o, "Exception graph: " + e);
                } else {
                    Debug.log("Use -G:+DumpOnError to enable dumping of graphs on this error");
                }
            } else if (o instanceof LIR) {
                Debug.log("Context obj %s", o);
                if (DumpOnError.getValue()) {
                    Debug.dump(o, "Exception LIR: " + e);
                } else {
                    Debug.log("Use -G:+DumpOnError to enable dumping of graphs on this error");
                }
            } else if (o instanceof Node) {
                String location = GraphUtil.approxSourceLocation((Node) o);
                String node = ((Node) o).toString(Verbosity.Debugger);
                if (location != null) {
                    Debug.log("Context obj %s (approx. location: %s)", node, location);
                } else {
                    Debug.log("Context obj %s", node);
                }
            } else {
                Debug.log("Context obj %s", o);
            }
        }
        return null;
    }

    @Override
    public Collection<DebugDumpHandler> dumpHandlers() {
        return dumpHandlers;
    }

    @Override
    public Collection<DebugVerifyHandler> verifyHandlers() {
        return verifyHandlers;
    }

    @Override
    public void addToContext(Object o) {
        extraFilters.add(o);
    }

    @Override
    public void removeFromContext(Object o) {
        extraFilters.remove(o);
    }
}
