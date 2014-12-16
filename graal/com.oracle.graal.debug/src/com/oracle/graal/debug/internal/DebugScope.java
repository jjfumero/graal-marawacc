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
package com.oracle.graal.debug.internal;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import com.oracle.graal.debug.*;

public final class DebugScope implements Debug.Scope {

    private final class IndentImpl implements Indent {

        private static final String INDENTATION_INCREMENT = "  ";

        final String indent;
        final IndentImpl parentIndent;

        IndentImpl(IndentImpl parentIndent) {
            this.parentIndent = parentIndent;
            this.indent = (parentIndent == null ? "" : parentIndent.indent + INDENTATION_INCREMENT);
        }

        private void printScopeName(StringBuilder str) {
            if (logScopeName) {
                if (parentIndent != null) {
                    parentIndent.printScopeName(str);
                }
                str.append(indent).append("[thread:").append(Thread.currentThread().getId()).append("] scope: ").append(qualifiedName).append(System.lineSeparator());
                logScopeName = false;
            }
        }

        public void log(int logLevel, String msg, Object... args) {
            if (isLogEnabled(logLevel)) {
                StringBuilder str = new StringBuilder();
                printScopeName(str);
                str.append(indent);
                if (args.length == 0) {
                    str.append(msg);
                } else {
                    str.append(String.format(msg, args));
                }
                str.append(System.lineSeparator());
                output.append(str);
                lastUsedIndent = this;
            }
        }

        IndentImpl indent() {
            lastUsedIndent = new IndentImpl(this);
            return lastUsedIndent;
        }

        @Override
        public void close() {
            if (parentIndent != null) {
                lastUsedIndent = parentIndent;
            }
        }
    }

    private static final ThreadLocal<DebugScope> instanceTL = new ThreadLocal<>();
    private static final ThreadLocal<DebugScope> lastClosedTL = new ThreadLocal<>();
    private static final ThreadLocal<DebugConfig> configTL = new ThreadLocal<>();
    private static final ThreadLocal<Throwable> lastExceptionThrownTL = new ThreadLocal<>();

    private final DebugScope parent;
    private final DebugConfig parentConfig;
    private final boolean sandbox;
    private IndentImpl lastUsedIndent;
    private boolean logScopeName;

    private final Object[] context;

    private final DebugValueMap valueMap;
    private final String qualifiedName;

    private static final char SCOPE_SEP = '.';

    private boolean meterEnabled;
    private boolean timeEnabled;
    private boolean memUseTrackingEnabled;
    private boolean verifyEnabled;

    private int currentDumpLevel;
    private int currentLogLevel;

    private PrintStream output;

    public static DebugScope getInstance() {
        DebugScope result = instanceTL.get();
        if (result == null) {
            DebugScope topLevelDebugScope = new DebugScope(Thread.currentThread().getName(), "", null, false);
            instanceTL.set(topLevelDebugScope);
            DebugValueMap.registerTopLevel(topLevelDebugScope.getValueMap());
            return topLevelDebugScope;
        } else {
            return result;
        }
    }

    public static DebugConfig getConfig() {
        return configTL.get();
    }

    private DebugScope(String name, String qualifiedName, DebugScope parent, boolean sandbox, Object... context) {
        this.parent = parent;
        this.sandbox = sandbox;
        this.parentConfig = getConfig();
        this.context = context;
        this.qualifiedName = qualifiedName;
        if (parent != null) {
            lastUsedIndent = new IndentImpl(parent.lastUsedIndent);
            logScopeName = !parent.qualifiedName.equals(qualifiedName);
        } else {
            lastUsedIndent = new IndentImpl(null);
            logScopeName = true;
        }

        // Be pragmatic: provide a default log stream to prevent a crash if the stream is not
        // set while logging
        this.output = TTY.cachedOut;
        assert context != null;

        if (parent != null) {
            for (DebugValueMap child : parent.getValueMap().getChildren()) {
                if (child.getName().equals(name)) {
                    this.valueMap = child;
                    return;
                }
            }
            this.valueMap = new DebugValueMap(name);
            parent.getValueMap().addChild(this.valueMap);
        } else {
            this.valueMap = new DebugValueMap(name);
        }
    }

    public void close() {
        instanceTL.set(parent);
        configTL.set(parentConfig);
        lastClosedTL.set(this);
    }

    public boolean isDumpEnabled(int dumpLevel) {
        assert dumpLevel > 0;
        return currentDumpLevel >= dumpLevel;
    }

    /**
     * Enable dumping at the new {@code dumpLevel} for remainder of compile. Requires a
     * TopLevelDebugConfig
     *
     * @param dumpLevel
     */
    public static void setDumpLevel(int dumpLevel) {
        TopLevelDebugConfig config = fetchTopLevelDebugConfig("setLogLevel");
        if (config != null) {
            config.override(DelegatingDebugConfig.Level.DUMP, dumpLevel);
            recursiveUpdateFlags();
        }
    }

    /**
     * Enable logging at the new {@code logLevel} for remainder of compile. Requires a
     * TopLevelDebugConfig
     *
     * @param logLevel
     */
    public static void setLogLevel(int logLevel) {
        TopLevelDebugConfig config = fetchTopLevelDebugConfig("setLogLevel");
        if (config != null) {
            config.override(DelegatingDebugConfig.Level.LOG, logLevel);
            config.delegate(DelegatingDebugConfig.Feature.LOG_METHOD);
            recursiveUpdateFlags();
        }
    }

    private static void recursiveUpdateFlags() {
        DebugScope c = DebugScope.getInstance();
        while (c != null) {
            c.updateFlags();
            c = c.parent;
        }
    }

    private static TopLevelDebugConfig fetchTopLevelDebugConfig(String msg) {
        DebugConfig config = getConfig();
        if (config instanceof TopLevelDebugConfig) {
            return (TopLevelDebugConfig) config;
        } else {
            PrintStream out = System.out;
            if (config == null) {
                out.printf("DebugScope.%s ignored because debugging is disabled%n", msg);
            } else {
                out.printf("DebugScope.%s ignored because top level delegate config missing%n", msg);
            }
            return null;
        }
    }

    public boolean isVerifyEnabled() {
        return verifyEnabled;
    }

    public boolean isLogEnabled(int logLevel) {
        assert logLevel > 0;
        return currentLogLevel >= logLevel;
    }

    public boolean isMeterEnabled() {
        return meterEnabled;
    }

    public boolean isTimeEnabled() {
        return timeEnabled;
    }

    public boolean isMemUseTrackingEnabled() {
        return memUseTrackingEnabled;
    }

    public void log(int logLevel, String msg, Object... args) {
        lastUsedIndent.log(logLevel, msg, args);
    }

    public void dump(int dumpLevel, Object object, String formatString, Object... args) {
        if (isDumpEnabled(dumpLevel)) {
            DebugConfig config = getConfig();
            if (config != null) {
                String message = String.format(formatString, args);
                for (DebugDumpHandler dumpHandler : config.dumpHandlers()) {
                    dumpHandler.dump(object, message);
                }
            }
        }
    }

    /**
     * This method exists mainly to allow a debugger (e.g., Eclipse) to force dump a graph.
     */
    public static void forceDump(Object object, String message) {
        DebugConfig config = getConfig();
        if (config != null) {
            for (DebugDumpHandler dumpHandler : config.dumpHandlers()) {
                dumpHandler.dump(object, message);
            }
        } else {
            PrintStream out = System.out;
            out.println("Forced dump ignored because debugging is disabled - use -G:Dump=xxx option");
        }
    }

    /**
     * @see Debug#verify(Object, String)
     */
    public void verify(Object object, String formatString, Object... args) {
        if (isVerifyEnabled()) {
            DebugConfig config = getConfig();
            if (config != null) {
                String message = String.format(formatString, args);
                for (DebugVerifyHandler handler : config.verifyHandlers()) {
                    handler.verify(object, message);
                }
            }
        }
    }

    /**
     * Creates and enters a new debug scope which is either a child of the current scope or a
     * disjoint top level scope.
     *
     * @param name the name of the new scope
     * @param sandboxConfig the configuration to use for a new top level scope, or null if the new
     *            scope should be a child scope
     * @param newContextObjects objects to be appended to the debug context
     * @return the new scope which will be exited when its {@link #close()} method is called
     */
    public DebugScope scope(CharSequence name, DebugConfig sandboxConfig, Object... newContextObjects) {
        DebugScope newScope = null;
        if (sandboxConfig != null) {
            newScope = new DebugScope(name.toString(), name.toString(), this, true, newContextObjects);
            configTL.set(sandboxConfig);
        } else {
            newScope = this.createChild(name.toString(), newContextObjects);
        }
        instanceTL.set(newScope);
        newScope.updateFlags();
        return newScope;
    }

    public RuntimeException handle(Throwable e) {
        DebugScope lastClosed = lastClosedTL.get();
        assert lastClosed.parent == this : "Debug.handle() used with no matching Debug.scope(...) or Debug.sandbox(...)";
        if (e != lastExceptionThrownTL.get()) {
            RuntimeException newException = null;
            instanceTL.set(lastClosed);
            try (DebugScope s = lastClosed) {
                newException = s.interceptException(e);
            }
            assert instanceTL.get() == this;
            assert lastClosed == lastClosedTL.get();
            if (newException == null) {
                lastExceptionThrownTL.set(e);
            } else {
                lastExceptionThrownTL.set(newException);
                throw newException;
            }
        }
        if (e instanceof Error) {
            throw (Error) e;
        }
        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        }
        throw new RuntimeException(e);
    }

    private void updateFlags() {
        DebugConfig config = getConfig();
        if (config == null) {
            meterEnabled = false;
            memUseTrackingEnabled = false;
            timeEnabled = false;
            verifyEnabled = false;

            currentDumpLevel = 0;

            // Be pragmatic: provide a default log stream to prevent a crash if the stream is not
            // set while logging
            output = TTY.cachedOut;
        } else {
            meterEnabled = config.isMeterEnabled();
            memUseTrackingEnabled = config.isMemUseTrackingEnabled();
            timeEnabled = config.isTimeEnabled();
            verifyEnabled = config.isVerifyEnabled();
            output = config.output();
            currentDumpLevel = config.getDumpLevel();
            currentLogLevel = config.getLogLevel();
        }
    }

    private RuntimeException interceptException(final Throwable e) {
        final DebugConfig config = getConfig();
        if (config != null) {
            try (DebugScope s = scope("InterceptException", null, e)) {
                return config.interceptException(e);
            } catch (Throwable t) {
                return new RuntimeException("Exception while intercepting exception", t);
            }
        }
        return null;
    }

    private DebugValueMap getValueMap() {
        return valueMap;
    }

    long getCurrentValue(int index) {
        return getValueMap().getCurrentValue(index);
    }

    void setCurrentValue(int index, long l) {
        getValueMap().setCurrentValue(index, l);
    }

    private DebugScope createChild(String newName, Object[] newContext) {
        String newQualifiedName = newName;
        if (this.qualifiedName.length() > 0) {
            newQualifiedName = this.qualifiedName + SCOPE_SEP + newName;
        }
        DebugScope result = new DebugScope(newName, newQualifiedName, this, false, newContext);
        return result;
    }

    public Iterable<Object> getCurrentContext() {
        final DebugScope scope = this;
        return new Iterable<Object>() {

            @Override
            public Iterator<Object> iterator() {
                return new Iterator<Object>() {

                    DebugScope currentScope = scope;
                    int objectIndex;

                    @Override
                    public boolean hasNext() {
                        selectScope();
                        return currentScope != null;
                    }

                    private void selectScope() {
                        while (currentScope != null && currentScope.context.length <= objectIndex) {
                            currentScope = currentScope.sandbox ? null : currentScope.parent;
                            objectIndex = 0;
                        }
                    }

                    @Override
                    public Object next() {
                        selectScope();
                        if (currentScope != null) {
                            return currentScope.context[objectIndex++];
                        }
                        throw new IllegalStateException("May only be called if there is a next element.");
                    }

                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException("This iterator is read only.");
                    }
                };
            }
        };
    }

    public static <T> T call(Callable<T> callable) {
        try {
            return callable.call();
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            } else {
                throw new RuntimeException(e);
            }
        }
    }

    public void setConfig(DebugConfig newConfig) {
        configTL.set(newConfig);
        updateFlags();
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public Indent pushIndentLogger() {
        lastUsedIndent = lastUsedIndent.indent();
        return lastUsedIndent;
    }
}
