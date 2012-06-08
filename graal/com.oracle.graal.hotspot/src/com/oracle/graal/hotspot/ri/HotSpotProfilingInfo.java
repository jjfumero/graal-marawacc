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
package com.oracle.graal.hotspot.ri;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.hotspot.*;


public final class HotSpotProfilingInfo extends CompilerObject implements RiProfilingInfo {

    private static final long serialVersionUID = -8307682725047864875L;
    private static final DebugMetric metricInsufficentSpace = Debug.metric("InsufficientSpaceForProfilingData");

    private int position;
    private int hintPosition;
    private int hintBCI;
    private HotSpotMethodDataAccessor dataAccessor;
    private HotSpotMethodData methodData;
    private final int codeSize;

    public HotSpotProfilingInfo(HotSpotCompilerImpl compiler, HotSpotMethodData methodData, int codeSize) {
        super(compiler);
        this.methodData = methodData;
        this.codeSize = codeSize;
        hintPosition = 0;
        hintBCI = -1;
    }

    @Override
    public int codeSize() {
        return codeSize;
    }

    @Override
    public RiTypeProfile getTypeProfile(int bci) {
        findBCI(bci, false);
        return dataAccessor.getTypeProfile(methodData, position);
    }

    @Override
    public double getBranchTakenProbability(int bci) {
        findBCI(bci, false);
        return dataAccessor.getBranchTakenProbability(methodData, position);
    }

    @Override
    public double[] getSwitchProbabilities(int bci) {
        findBCI(bci, false);
        return dataAccessor.getSwitchProbabilities(methodData, position);
    }

    @Override
    public RiExceptionSeen getExceptionSeen(int bci) {
        findBCI(bci, true);
        return dataAccessor.getExceptionSeen(methodData, position);
    }

    @Override
    public int getExecutionCount(int bci) {
        findBCI(bci, false);
        return dataAccessor.getExecutionCount(methodData, position);
    }

    @Override
    public int getDeoptimizationCount(RiDeoptReason reason) {
        return methodData.getDeoptimizationCount(reason);
    }

    private void findBCI(int targetBCI, boolean searchExtraData) {
        assert targetBCI >= 0 : "invalid BCI";

        if (methodData.hasNormalData()) {
            int currentPosition = targetBCI < hintBCI ? 0 : hintPosition;
            HotSpotMethodDataAccessor currentAccessor;
            while ((currentAccessor = methodData.getNormalData(currentPosition)) != null) {
                int currentBCI = currentAccessor.getBCI(methodData, currentPosition);
                if (currentBCI == targetBCI) {
                    normalDataFound(currentAccessor, currentPosition, currentBCI);
                    return;
                } else if (currentBCI > targetBCI) {
                    break;
                }
                currentPosition = currentPosition + currentAccessor.getSize(methodData, currentPosition);
            }
        }

        boolean exceptionPossiblyNotRecorded = false;
        if (searchExtraData && methodData.hasExtraData()) {
            int currentPosition = methodData.getExtraDataBeginOffset();
            HotSpotMethodDataAccessor currentAccessor;
            while ((currentAccessor = methodData.getExtraData(currentPosition)) != null) {
                int currentBCI = currentAccessor.getBCI(methodData, currentPosition);
                if (currentBCI == targetBCI) {
                    extraDataFound(currentAccessor, currentPosition);
                    return;
                }
                currentPosition = currentPosition + currentAccessor.getSize(methodData, currentPosition);
            }

            if (!methodData.isWithin(currentPosition)) {
                exceptionPossiblyNotRecorded = true;
                metricInsufficentSpace.increment();
            }
        }

        noDataFound(exceptionPossiblyNotRecorded);
    }

    private void normalDataFound(HotSpotMethodDataAccessor data, int pos, int bci) {
        setCurrentData(data, pos);
        this.hintPosition = position;
        this.hintBCI = bci;
    }

    private void extraDataFound(HotSpotMethodDataAccessor data, int pos) {
        setCurrentData(data, pos);
    }

    private void noDataFound(boolean exceptionPossiblyNotRecorded) {
        HotSpotMethodDataAccessor accessor = HotSpotMethodData.getNoDataAccessor(exceptionPossiblyNotRecorded);
        setCurrentData(accessor, -1);
    }

    private void setCurrentData(HotSpotMethodDataAccessor dataAccessor, int position) {
        this.dataAccessor = dataAccessor;
        this.position = position;
    }

    @Override
    public String toString() {
        return "HotSpotProfilingInfo<" + CiUtil.profileToString(this, null, "; ") + ">";
    }
}
