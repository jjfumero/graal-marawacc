/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.compiler.GraalDebugConfig.*;
import static com.oracle.graal.options.OptionsLoader.*;

import java.lang.reflect.*;

import com.oracle.graal.api.runtime.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.options.*;
import com.oracle.graal.phases.common.inlining.*;

//JaCoCo Exclude

/**
 * Sets Graal options from the HotSpot command line. Such options are distinguished by the
 * {@link #GRAAL_OPTION_PREFIX} prefix.
 */
public class HotSpotOptions {

    private static final String GRAAL_OPTION_PREFIX = "-G:";

    private static native boolean isCITimingEnabled();

    static {
        if (isCITimingEnabled()) {
            unconditionallyEnableTimerOrMetric(InliningUtil.class, "InlinedBytecodes");
            unconditionallyEnableTimerOrMetric(CompilationTask.class, "CompilationTime");
        }
        assert !Debug.Initialization.isDebugInitialized() : "The class " + Debug.class.getName() + " must not be initialized before the Graal runtime has been initialized. " +
                        "This can be fixed by placing a call to " + Graal.class.getName() + ".runtime() on the path that triggers initialization of " + Debug.class.getName();
        if (areDebugScopePatternsEnabled()) {
            System.setProperty(Debug.Initialization.INITIALIZER_PROPERTY_NAME, "true");
        }
        if ("".equals(Meter.getValue())) {
            System.setProperty(Debug.ENABLE_UNSCOPED_METRICS_PROPERTY_NAME, "true");
        }
        if ("".equals(Time.getValue())) {
            System.setProperty(Debug.ENABLE_UNSCOPED_TIMERS_PROPERTY_NAME, "true");
        }
        if ("".equals(TrackMemUse.getValue())) {
            System.setProperty(Debug.ENABLE_UNSCOPED_MEM_USE_TRACKERS_PROPERTY_NAME, "true");
        }
    }

    static void printFlags() {
        OptionUtils.printFlags(options, GRAAL_OPTION_PREFIX);
    }

    /**
     * Sets the relevant system property such that a {@link DebugTimer} or {@link DebugMetric}
     * associated with a field in a class will be unconditionally enabled when it is created.
     * <p>
     * This method verifies that the named field exists and is of an expected type. However, it does
     * not verify that the timer or metric created has the same name of the field.
     *
     * @param c the class in which the field is declared
     * @param name the name of the field
     */
    private static void unconditionallyEnableTimerOrMetric(Class<?> c, String name) {
        try {
            Field field = c.getDeclaredField(name);
            String propertyName;
            if (DebugTimer.class.isAssignableFrom(field.getType())) {
                propertyName = Debug.ENABLE_TIMER_PROPERTY_NAME_PREFIX + name;
            } else {
                assert DebugMetric.class.isAssignableFrom(field.getType());
                propertyName = Debug.ENABLE_METRIC_PROPERTY_NAME_PREFIX + name;
            }
            String previous = System.setProperty(propertyName, "true");
            if (previous != null) {
                System.err.println("Overrode value \"" + previous + "\" of system property \"" + propertyName + "\" with \"true\"");
            }
        } catch (Exception e) {
            throw new GraalInternalError(e);
        }
    }

    public native Object getOptionValue(String optionName);
}
