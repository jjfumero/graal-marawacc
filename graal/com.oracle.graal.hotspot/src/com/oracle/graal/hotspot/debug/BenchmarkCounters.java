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
package com.oracle.graal.hotspot.debug;

import java.io.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import sun.misc.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.bridge.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.HeapAccess.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.debug.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.options.*;
import com.oracle.graal.replacements.nodes.*;

/**
 * This class contains infrastructure to maintain counters based on {@link DynamicCounterNode}s. The
 * infrastructure is enabled by specifying either the GenericDynamicCounters or
 * BenchmarkDynamicCounters option.<br/>
 * 
 * The counters are kept in a special area in the native JavaThread object, and the number of
 * counters is configured in {@code thread.hpp (GRAAL_COUNTERS_SIZE)}. This file also contains an
 * option to exclude compiler threads ({@code GRAAL_COUNTERS_EXCLUDE_COMPILER_THREADS}, which
 * defaults to true).
 * 
 * The subsystems that use the logging need to have their own options to turn on the counters, and
 * insert DynamicCounterNodes when they're enabled.
 */
public class BenchmarkCounters {

    static class Options {

        //@formatter:off
        @Option(help = "Turn on the benchmark counters, and displays the results on VM shutdown")
        private static final OptionValue<Boolean> GenericDynamicCounters = new OptionValue<>(false);

        @Option(help = "Turn on the benchmark counters, and listen for specific patterns on System.out/System.err:%n" +
                       "Format: (err|out),start pattern,end pattern (~ matches multiple digits)%n" +
                       "Examples:%n" +
                       "  dacapo = 'err, starting =====, PASSED in'%n" +
                       "  specjvm2008 = 'out,Iteration ~ (~s) begins:,Iteration ~ (~s) ends:'")
        private static final OptionValue<String> BenchmarkDynamicCounters = new OptionValue<>(null);
        //@formatter:on
    }

    private static final boolean DUMP_STATIC = false;

    public static String excludedClassPrefix = null;
    public static boolean enabled = false;

    public static final ConcurrentHashMap<String, Integer> indexes = new ConcurrentHashMap<>();
    public static final ArrayList<String> groups = new ArrayList<>();
    public static long[] delta;
    public static final ArrayList<AtomicLong> staticCounters = new ArrayList<>();

    public static int getIndex(DynamicCounterNode counter) {
        if (!enabled) {
            throw new GraalInternalError("counter nodes shouldn't exist when counters are not enabled");
        }
        String name = counter.getName();
        String group = counter.getGroup();
        name = counter.isWithContext() ? name + " @ " + counter.graph().graphId() + ":" + MetaUtil.format("%h.%n", counter.graph().method()) + "#" + group : name + "#" + group;
        Integer index = indexes.get(name);
        if (index == null) {
            synchronized (BenchmarkCounters.class) {
                index = indexes.get(name);
                if (index == null) {
                    index = indexes.size();
                    indexes.put(name, index);
                    groups.add(group);
                    staticCounters.add(new AtomicLong());
                }
            }
        }
        assert groups.get(index).equals(group) : "mismatching groups: " + groups.get(index) + " vs. " + group;
        if (counter.getIncrement().isConstant()) {
            staticCounters.get(index).addAndGet(counter.getIncrement().asConstant().asLong());
        }
        return index;
    }

    public static synchronized void dump(PrintStream out, double seconds, long[] counters) {
        if (!staticCounters.isEmpty()) {
            out.println("====== dynamic counters (" + staticCounters.size() + " in total) ======");
            for (String group : new TreeSet<>(groups)) {
                if (group != null) {
                    if (DUMP_STATIC) {
                        dumpCounters(out, seconds, counters, true, group);
                    }
                    dumpCounters(out, seconds, counters, false, group);
                }
            }
            out.println("============================");

            clear(counters);
        }
    }

    public static synchronized void clear(long[] counters) {
        delta = counters;
    }

    private static synchronized void dumpCounters(PrintStream out, double seconds, long[] counters, boolean staticCounter, String group) {
        TreeMap<Long, String> sorted = new TreeMap<>();

        long[] array;
        if (staticCounter) {
            array = new long[indexes.size()];
            for (int i = 0; i < array.length; i++) {
                array[i] = staticCounters.get(i).get();
            }
        } else {
            array = counters.clone();
            for (int i = 0; i < array.length; i++) {
                array[i] -= delta[i];
            }
        }
        long sum = 0;
        for (Map.Entry<String, Integer> entry : indexes.entrySet()) {
            int index = entry.getValue();
            if (groups.get(index).equals(group)) {
                sum += array[index];
                sorted.put(array[index] * array.length + index, entry.getKey().substring(0, entry.getKey().length() - group.length() - 1));
            }
        }

        if (sum > 0) {
            NumberFormat format = NumberFormat.getInstance(Locale.US);
            long cutoff = sorted.size() < 10 ? 1 : Math.max(1, sum / 100);
            if (staticCounter) {
                out.println("=========== " + group + " static counters: ");
                for (Map.Entry<Long, String> entry : sorted.entrySet()) {
                    long counter = entry.getKey() / array.length;
                    if (counter >= cutoff) {
                        out.println(format.format(counter) + " \t" + ((counter * 200 + 1) / sum / 2) + "% \t" + entry.getValue());
                    }
                }
                out.println(sum + ": total");
            } else {
                if (group.startsWith("~")) {
                    out.println("=========== " + group + " dynamic counters");
                    for (Map.Entry<Long, String> entry : sorted.entrySet()) {
                        long counter = entry.getKey() / array.length;
                        if (counter >= cutoff) {
                            out.println(format.format(counter) + " \t" + ((counter * 200 + 1) / sum / 2) + "% \t" + entry.getValue());
                        }
                    }
                    out.println(format.format(sum) + ": total");
                } else {
                    out.println("=========== " + group + " dynamic counters, time = " + seconds + " s");
                    for (Map.Entry<Long, String> entry : sorted.entrySet()) {
                        long counter = entry.getKey() / array.length;
                        if (counter >= cutoff) {
                            out.println(format.format((long) (counter / seconds)) + "/s \t" + ((counter * 200 + 1) / sum / 2) + "% \t" + entry.getValue());
                        }
                    }
                    out.println(format.format((long) (sum / seconds)) + "/s: total");
                }
            }
        }
    }

    public abstract static class CallbackOutputStream extends OutputStream {

        protected final PrintStream delegate;
        private final byte[][] patterns;
        private final int[] positions;

        public CallbackOutputStream(PrintStream delegate, String... patterns) {
            this.delegate = delegate;
            this.positions = new int[patterns.length];
            this.patterns = new byte[patterns.length][];
            for (int i = 0; i < patterns.length; i++) {
                this.patterns[i] = patterns[i].getBytes();
            }
        }

        protected abstract void patternFound(int index);

        @Override
        public void write(int b) throws IOException {
            try {
                delegate.write(b);
                for (int i = 0; i < patterns.length; i++) {
                    int j = positions[i];
                    byte[] cs = patterns[i];
                    byte patternChar = cs[j];
                    if (patternChar == '~' && Character.isDigit(b)) {
                        // nothing to do...
                    } else {
                        if (patternChar == '~') {
                            patternChar = cs[++positions[i]];
                        }
                        if (b == patternChar) {
                            positions[i]++;
                        } else {
                            positions[i] = 0;
                        }
                    }
                    if (positions[i] == patterns[i].length) {
                        positions[i] = 0;
                        patternFound(i);
                    }
                }
            } catch (RuntimeException e) {
                e.printStackTrace(delegate);
                throw e;
            }
        }
    }

    public static void initialize(final CompilerToVM compilerToVM) {
        final class BenchmarkCountersOutputStream extends CallbackOutputStream {

            private long startTime;
            private boolean waitingForEnd;

            private BenchmarkCountersOutputStream(PrintStream delegate, String start, String end) {
                super(delegate, new String[]{start, end, "\n"});
            }

            @Override
            protected void patternFound(int index) {
                switch (index) {
                    case 0:
                        startTime = System.nanoTime();
                        BenchmarkCounters.clear(compilerToVM.collectCounters());
                        break;
                    case 1:
                        waitingForEnd = true;
                        break;
                    case 2:
                        if (waitingForEnd) {
                            waitingForEnd = false;
                            BenchmarkCounters.dump(delegate, (System.nanoTime() - startTime) / 1000000000d, compilerToVM.collectCounters());
                        }
                        break;
                }
            }
        }

        if (Options.BenchmarkDynamicCounters.getValue() != null) {
            String[] arguments = Options.BenchmarkDynamicCounters.getValue().split(",");
            if (arguments.length == 0 || (arguments.length % 3) != 0) {
                throw new GraalInternalError("invalid arguments to BenchmarkDynamicCounters: (err|out),start,end,(err|out),start,end,... (~ matches multiple digits)");
            }
            for (int i = 0; i < arguments.length; i += 3) {
                if (arguments[i].equals("err")) {
                    System.setErr(new PrintStream(new BenchmarkCountersOutputStream(System.err, arguments[i + 1], arguments[i + 2])));
                } else if (arguments[i].equals("out")) {
                    System.setOut(new PrintStream(new BenchmarkCountersOutputStream(System.out, arguments[i + 1], arguments[i + 2])));
                } else {
                    throw new GraalInternalError("invalid arguments to BenchmarkDynamicCounters: err|out");
                }
            }
            excludedClassPrefix = "Lcom/oracle/graal/";
            enabled = true;
        }
        if (Options.GenericDynamicCounters.getValue()) {
            enabled = true;
        }
        if (Options.GenericDynamicCounters.getValue() || Options.BenchmarkDynamicCounters.getValue() != null) {
            clear(compilerToVM.collectCounters());
        }
    }

    public static void shutdown(CompilerToVM compilerToVM, long compilerStartTime) {
        if (Options.GenericDynamicCounters.getValue()) {
            dump(System.out, (System.nanoTime() - compilerStartTime) / 1000000000d, compilerToVM.collectCounters());
        }
    }

    public static void lower(DynamicCounterNode counter, HotSpotRuntime runtime) {
        StructuredGraph graph = counter.graph();
        if (excludedClassPrefix == null || !counter.graph().method().getDeclaringClass().getName().startsWith(excludedClassPrefix)) {
            HotSpotVMConfig config = runtime.config;

            ReadRegisterNode thread = graph.add(new ReadRegisterNode(runtime.threadRegister(), runtime.getTarget().wordKind, true, false));

            int index = BenchmarkCounters.getIndex(counter);
            if (index >= config.graalCountersSize) {
                throw new GraalInternalError("too many counters, reduce number of counters or increase GRAAL_COUNTERS_SIZE (current value: " + config.graalCountersSize + ")");
            }
            ConstantLocationNode location = ConstantLocationNode.create(LocationIdentity.ANY_LOCATION, Kind.Long, config.graalCountersThreadOffset + Unsafe.ARRAY_LONG_INDEX_SCALE * index, graph);
            ReadNode read = graph.add(new ReadNode(thread, location, StampFactory.forKind(Kind.Long), BarrierType.NONE, false));
            IntegerAddNode add = graph.unique(new IntegerAddNode(Kind.Long, read, counter.getIncrement()));
            WriteNode write = graph.add(new WriteNode(thread, add, location, BarrierType.NONE, false));

            graph.addBeforeFixed(counter, thread);
            graph.addBeforeFixed(counter, read);
            graph.addBeforeFixed(counter, write);
        }
        graph.removeFixed(counter);
    }
}
