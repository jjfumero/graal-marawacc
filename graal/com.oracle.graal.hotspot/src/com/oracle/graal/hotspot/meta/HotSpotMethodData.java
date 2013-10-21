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
package com.oracle.graal.hotspot.meta;

import static com.oracle.graal.graph.UnsafeAccess.*;
import static com.oracle.graal.hotspot.HotSpotGraalRuntime.*;

import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.meta.JavaMethodProfile.ProfiledMethod;
import com.oracle.graal.api.meta.JavaTypeProfile.ProfiledType;
import com.oracle.graal.api.meta.ProfilingInfo.TriState;
import com.oracle.graal.hotspot.*;

/**
 * Access to a HotSpot MethodData structure (defined in methodData.hpp).
 */
public final class HotSpotMethodData extends CompilerObject {

    private static final long serialVersionUID = -8873133496591225071L;

    private static final HotSpotVMConfig config = runtime().getConfig();
    private static final HotSpotMethodDataAccessor NO_DATA_NO_EXCEPTION_ACCESSOR = new NoMethodData(TriState.FALSE);
    private static final HotSpotMethodDataAccessor NO_DATA_EXCEPTION_POSSIBLY_NOT_RECORDED_ACCESSOR = new NoMethodData(TriState.UNKNOWN);

    // sorted by tag
    private static final HotSpotMethodDataAccessor[] PROFILE_DATA_ACCESSORS = {null, new BitData(), new CounterData(), new JumpData(), new TypeCheckData(), new VirtualCallData(), new RetData(),
                    new BranchData(), new MultiBranchData(), new ArgInfoData()};

    /**
     * Reference to the C++ MethodData object.
     */
    private final long metaspaceMethodData;

    private int normalDataSize;
    private int extraDataSize;

    HotSpotMethodData(long metaspaceMethodData) {
        this.metaspaceMethodData = metaspaceMethodData;
        runtime().getCompilerToVM().initializeMethodData(metaspaceMethodData, this);
    }

    public boolean hasNormalData() {
        return normalDataSize > 0;
    }

    public boolean hasExtraData() {
        return extraDataSize > 0;
    }

    public int getExtraDataBeginOffset() {
        return normalDataSize;
    }

    public boolean isWithin(int position) {
        return position >= 0 && position < normalDataSize + extraDataSize;
    }

    public int getDeoptimizationCount(DeoptimizationReason reason) {
        int reasonIndex = runtime().getHostProviders().getMetaAccess().convertDeoptReason(reason);
        return unsafe.getByte(metaspaceMethodData + config.methodDataOopTrapHistoryOffset + reasonIndex) & 0xFF;
    }

    public HotSpotMethodDataAccessor getNormalData(int position) {
        if (position >= normalDataSize) {
            return null;
        }

        HotSpotMethodDataAccessor result = getData(position);
        assert result != null : "NO_DATA tag is not allowed";
        return result;
    }

    public HotSpotMethodDataAccessor getExtraData(int position) {
        if (position >= normalDataSize + extraDataSize) {
            return null;
        }
        return getData(position);
    }

    public static HotSpotMethodDataAccessor getNoDataAccessor(boolean exceptionPossiblyNotRecorded) {
        if (exceptionPossiblyNotRecorded) {
            return NO_DATA_EXCEPTION_POSSIBLY_NOT_RECORDED_ACCESSOR;
        } else {
            return NO_DATA_NO_EXCEPTION_ACCESSOR;
        }
    }

    private HotSpotMethodDataAccessor getData(int position) {
        assert position >= 0 : "out of bounds";
        int tag = AbstractMethodData.readTag(this, position);
        assert tag >= 0 && tag < PROFILE_DATA_ACCESSORS.length : "illegal tag";
        return PROFILE_DATA_ACCESSORS[tag];
    }

    private int readUnsignedByte(int position, int offsetInBytes) {
        long fullOffsetInBytes = computeFullOffset(position, offsetInBytes);
        return unsafe.getByte(metaspaceMethodData + fullOffsetInBytes) & 0xFF;
    }

    private int readUnsignedShort(int position, int offsetInBytes) {
        long fullOffsetInBytes = computeFullOffset(position, offsetInBytes);
        return unsafe.getShort(metaspaceMethodData + fullOffsetInBytes) & 0xFFFF;
    }

    /**
     * Since the values are stored in cells (platform words) this method uses
     * {@link HotSpotGraalRuntime#unsafeReadWord} to read the right value on both little and big
     * endian machines.
     */
    private long readUnsignedInt(int position, int offsetInBytes) {
        long fullOffsetInBytes = computeFullOffset(position, offsetInBytes);
        return unsafeReadWord(metaspaceMethodData + fullOffsetInBytes) & 0xFFFFFFFFL;
    }

    private int readUnsignedIntAsSignedInt(int position, int offsetInBytes) {
        long value = readUnsignedInt(position, offsetInBytes);
        return truncateLongToInt(value);
    }

    /**
     * Since the values are stored in cells (platform words) this method uses
     * {@link HotSpotGraalRuntime#unsafeReadWord} to read the right value on both little and big
     * endian machines.
     */
    private int readInt(int position, int offsetInBytes) {
        long fullOffsetInBytes = computeFullOffset(position, offsetInBytes);
        return (int) unsafeReadWord(metaspaceMethodData + fullOffsetInBytes);
    }

    private long readWord(int position, int offsetInBytes) {
        long fullOffsetInBytes = computeFullOffset(position, offsetInBytes);
        return unsafeReadWord(metaspaceMethodData + fullOffsetInBytes);
    }

    private static int truncateLongToInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static int computeFullOffset(int position, int offsetInBytes) {
        return config.methodDataOopDataOffset + position + offsetInBytes;
    }

    private static int cellIndexToOffset(int cells) {
        return config.dataLayoutHeaderSize + cellsToBytes(cells);
    }

    private static int cellsToBytes(int cells) {
        return cells * config.dataLayoutCellSize;
    }

    private abstract static class AbstractMethodData implements HotSpotMethodDataAccessor {

        /**
         * Corresponds to DS_RECOMPILE_BIT defined in deoptimization.cpp.
         */
        private static final int EXCEPTIONS_MASK = 0x2;

        private final int tag;
        private final int staticSize;

        protected AbstractMethodData(int tag, int staticSize) {
            this.tag = tag;
            this.staticSize = staticSize;
        }

        public int getTag() {
            return tag;
        }

        public static int readTag(HotSpotMethodData data, int position) {
            return data.readUnsignedByte(position, config.dataLayoutTagOffset);
        }

        @Override
        public int getBCI(HotSpotMethodData data, int position) {
            return data.readUnsignedShort(position, config.dataLayoutBCIOffset);
        }

        @Override
        public int getSize(HotSpotMethodData data, int position) {
            return staticSize + getDynamicSize(data, position);
        }

        @Override
        public TriState getExceptionSeen(HotSpotMethodData data, int position) {
            return TriState.get((getFlags(data, position) & EXCEPTIONS_MASK) != 0);
        }

        @Override
        public JavaTypeProfile getTypeProfile(HotSpotMethodData data, int position) {
            return null;
        }

        @Override
        public JavaMethodProfile getMethodProfile(HotSpotMethodData data, int position) {
            return null;
        }

        @Override
        public double getBranchTakenProbability(HotSpotMethodData data, int position) {
            return -1;
        }

        @Override
        public double[] getSwitchProbabilities(HotSpotMethodData data, int position) {
            return null;
        }

        @Override
        public int getExecutionCount(HotSpotMethodData data, int position) {
            return -1;
        }

        @Override
        public TriState getNullSeen(HotSpotMethodData data, int position) {
            return TriState.UNKNOWN;
        }

        protected int getFlags(HotSpotMethodData data, int position) {
            return data.readUnsignedByte(position, config.dataLayoutFlagsOffset);
        }

        protected int getDynamicSize(@SuppressWarnings("unused") HotSpotMethodData data, @SuppressWarnings("unused") int position) {
            return 0;
        }
    }

    private static class NoMethodData extends AbstractMethodData {

        private static final int NO_DATA_TAG = 0;
        private static final int NO_DATA_SIZE = cellIndexToOffset(0);

        private final TriState exceptionSeen;

        protected NoMethodData(TriState exceptionSeen) {
            super(NO_DATA_TAG, NO_DATA_SIZE);
            this.exceptionSeen = exceptionSeen;
        }

        @Override
        public int getBCI(HotSpotMethodData data, int position) {
            return -1;
        }

        @Override
        public TriState getExceptionSeen(HotSpotMethodData data, int position) {
            return exceptionSeen;
        }
    }

    private static class BitData extends AbstractMethodData {

        private static final int BIT_DATA_TAG = 1;
        private static final int BIT_DATA_SIZE = cellIndexToOffset(0);
        private static final int BIT_DATA_NULL_SEEN_FLAG = 0x01;

        private BitData() {
            super(BIT_DATA_TAG, BIT_DATA_SIZE);
        }

        protected BitData(int tag, int staticSize) {
            super(tag, staticSize);
        }

        @Override
        public TriState getNullSeen(HotSpotMethodData data, int position) {
            return TriState.get((getFlags(data, position) & BIT_DATA_NULL_SEEN_FLAG) != 0);
        }
    }

    private static class CounterData extends BitData {

        private static final int COUNTER_DATA_TAG = 2;
        private static final int COUNTER_DATA_SIZE = cellIndexToOffset(1);
        private static final int COUNTER_DATA_COUNT_OFFSET = cellIndexToOffset(0);

        public CounterData() {
            super(COUNTER_DATA_TAG, COUNTER_DATA_SIZE);
        }

        protected CounterData(int tag, int staticSize) {
            super(tag, staticSize);
        }

        @Override
        public int getExecutionCount(HotSpotMethodData data, int position) {
            return getCounterValue(data, position);
        }

        protected int getCounterValue(HotSpotMethodData data, int position) {
            return data.readUnsignedIntAsSignedInt(position, COUNTER_DATA_COUNT_OFFSET);
        }
    }

    private static class JumpData extends AbstractMethodData {

        private static final int JUMP_DATA_TAG = 3;
        private static final int JUMP_DATA_SIZE = cellIndexToOffset(2);
        protected static final int TAKEN_COUNT_OFFSET = cellIndexToOffset(0);
        protected static final int TAKEN_DISPLACEMENT_OFFSET = cellIndexToOffset(1);

        public JumpData() {
            super(JUMP_DATA_TAG, JUMP_DATA_SIZE);
        }

        protected JumpData(int tag, int staticSize) {
            super(tag, staticSize);
        }

        @Override
        public double getBranchTakenProbability(HotSpotMethodData data, int position) {
            return getExecutionCount(data, position) != 0 ? 1 : 0;
        }

        @Override
        public int getExecutionCount(HotSpotMethodData data, int position) {
            return data.readUnsignedIntAsSignedInt(position, TAKEN_COUNT_OFFSET);
        }

        @SuppressWarnings("unused")
        public int getTakenDisplacement(HotSpotMethodData data, int position) {
            return data.readInt(position, TAKEN_DISPLACEMENT_OFFSET);
        }
    }

    private abstract static class AbstractTypeData extends CounterData {

        protected static final int TYPE_DATA_ROW_SIZE = cellsToBytes(2);

        protected static final int NONPROFILED_COUNT_OFFSET = cellIndexToOffset(1);
        protected static final int TYPE_DATA_FIRST_TYPE_OFFSET = cellIndexToOffset(2);
        protected static final int TYPE_DATA_FIRST_TYPE_COUNT_OFFSET = cellIndexToOffset(3);

        protected AbstractTypeData(int tag, int staticSize) {
            super(tag, staticSize);
        }

        @Override
        public JavaTypeProfile getTypeProfile(HotSpotMethodData data, int position) {
            int typeProfileWidth = config.typeProfileWidth;

            ResolvedJavaType[] types = new ResolvedJavaType[typeProfileWidth];
            long[] counts = new long[typeProfileWidth];
            long totalCount = 0;
            int entries = 0;

            for (int i = 0; i < typeProfileWidth; i++) {
                long receiverKlass = data.readWord(position, getTypeOffset(i));
                if (receiverKlass != 0) {
                    types[entries] = HotSpotResolvedObjectType.fromMetaspaceKlass(receiverKlass);
                    long count = data.readUnsignedInt(position, getTypeCountOffset(i));
                    totalCount += count;
                    counts[entries] = count;

                    entries++;
                }
            }

            totalCount += getTypesNotRecordedExecutionCount(data, position);
            return createTypeProfile(getNullSeen(data, position), types, counts, totalCount, entries);
        }

        protected abstract long getTypesNotRecordedExecutionCount(HotSpotMethodData data, int position);

        private static JavaTypeProfile createTypeProfile(TriState nullSeen, ResolvedJavaType[] types, long[] counts, long totalCount, int entries) {
            if (entries <= 0 || totalCount <= 0) {
                return null;
            }

            ProfiledType[] ptypes = new ProfiledType[entries];
            double totalProbability = 0.0;
            for (int i = 0; i < entries; i++) {
                double p = counts[i];
                p = p / totalCount;
                totalProbability += p;
                ptypes[i] = new ProfiledType(types[i], p);
            }

            Arrays.sort(ptypes);

            double notRecordedTypeProbability = entries < config.typeProfileWidth ? 0.0 : Math.min(1.0, Math.max(0.0, 1.0 - totalProbability));
            assert notRecordedTypeProbability == 0 || entries == config.typeProfileWidth;
            return new JavaTypeProfile(nullSeen, notRecordedTypeProbability, ptypes);
        }

        private static int getTypeOffset(int row) {
            return TYPE_DATA_FIRST_TYPE_OFFSET + row * TYPE_DATA_ROW_SIZE;
        }

        protected static int getTypeCountOffset(int row) {
            return TYPE_DATA_FIRST_TYPE_COUNT_OFFSET + row * TYPE_DATA_ROW_SIZE;
        }
    }

    private static class TypeCheckData extends AbstractTypeData {

        private static final int TYPE_CHECK_DATA_TAG = 4;
        private static final int TYPE_CHECK_DATA_SIZE = cellIndexToOffset(2) + TYPE_DATA_ROW_SIZE * config.typeProfileWidth;

        public TypeCheckData() {
            super(TYPE_CHECK_DATA_TAG, TYPE_CHECK_DATA_SIZE);
        }

        @Override
        public int getExecutionCount(HotSpotMethodData data, int position) {
            return -1;
        }

        @Override
        protected long getTypesNotRecordedExecutionCount(HotSpotMethodData data, int position) {
            return data.readUnsignedIntAsSignedInt(position, NONPROFILED_COUNT_OFFSET);
        }
    }

    private static class VirtualCallData extends AbstractTypeData {

        private static final int VIRTUAL_CALL_DATA_TAG = 5;
        private static final int VIRTUAL_CALL_DATA_SIZE = cellIndexToOffset(2) + TYPE_DATA_ROW_SIZE * (config.typeProfileWidth + config.methodProfileWidth);
        private static final int VIRTUAL_CALL_DATA_FIRST_METHOD_OFFSET = TYPE_DATA_FIRST_TYPE_OFFSET + TYPE_DATA_ROW_SIZE * config.typeProfileWidth;
        private static final int VIRTUAL_CALL_DATA_FIRST_METHOD_COUNT_OFFSET = TYPE_DATA_FIRST_TYPE_COUNT_OFFSET + TYPE_DATA_ROW_SIZE * config.typeProfileWidth;

        public VirtualCallData() {
            super(VIRTUAL_CALL_DATA_TAG, VIRTUAL_CALL_DATA_SIZE);
        }

        @Override
        public int getExecutionCount(HotSpotMethodData data, int position) {
            int typeProfileWidth = config.typeProfileWidth;

            long total = 0;
            for (int i = 0; i < typeProfileWidth; i++) {
                total += data.readUnsignedInt(position, getTypeCountOffset(i));
            }

            total += getCounterValue(data, position);
            return truncateLongToInt(total);
        }

        @Override
        protected long getTypesNotRecordedExecutionCount(HotSpotMethodData data, int position) {
            return getCounterValue(data, position);
        }

        private static long getMethodsNotRecordedExecutionCount(HotSpotMethodData data, int position) {
            return data.readUnsignedIntAsSignedInt(position, NONPROFILED_COUNT_OFFSET);
        }

        @Override
        public JavaMethodProfile getMethodProfile(HotSpotMethodData data, int position) {
            int profileWidth = config.methodProfileWidth;

            ResolvedJavaMethod[] methods = new ResolvedJavaMethod[profileWidth];
            long[] counts = new long[profileWidth];
            long totalCount = 0;
            int entries = 0;

            for (int i = 0; i < profileWidth; i++) {
                long method = data.readWord(position, getMethodOffset(i));
                if (method != 0) {
                    methods[entries] = HotSpotResolvedJavaMethod.fromMetaspace(method);
                    long count = data.readUnsignedInt(position, getMethodCountOffset(i));
                    totalCount += count;
                    counts[entries] = count;

                    entries++;
                }
            }

            totalCount += getMethodsNotRecordedExecutionCount(data, position);
            return createMethodProfile(methods, counts, totalCount, entries);
        }

        private static JavaMethodProfile createMethodProfile(ResolvedJavaMethod[] methods, long[] counts, long totalCount, int entries) {
            if (entries <= 0 || totalCount <= 0) {
                return null;
            }

            ProfiledMethod[] pmethods = new ProfiledMethod[entries];
            double totalProbability = 0.0;
            for (int i = 0; i < entries; i++) {
                double p = counts[i];
                p = p / totalCount;
                totalProbability += p;
                pmethods[i] = new ProfiledMethod(methods[i], p);
            }

            Arrays.sort(pmethods);

            double notRecordedMethodProbability = entries < config.methodProfileWidth ? 0.0 : Math.min(1.0, Math.max(0.0, 1.0 - totalProbability));
            assert notRecordedMethodProbability == 0 || entries == config.methodProfileWidth;
            return new JavaMethodProfile(notRecordedMethodProbability, pmethods);
        }

        private static int getMethodOffset(int row) {
            return VIRTUAL_CALL_DATA_FIRST_METHOD_OFFSET + row * TYPE_DATA_ROW_SIZE;
        }

        private static int getMethodCountOffset(int row) {
            return VIRTUAL_CALL_DATA_FIRST_METHOD_COUNT_OFFSET + row * TYPE_DATA_ROW_SIZE;
        }
    }

    private static class RetData extends CounterData {

        private static final int RET_DATA_TAG = 6;
        private static final int RET_DATA_ROW_SIZE = cellsToBytes(3);
        private static final int RET_DATA_SIZE = cellIndexToOffset(1) + RET_DATA_ROW_SIZE * config.bciProfileWidth;

        public RetData() {
            super(RET_DATA_TAG, RET_DATA_SIZE);
        }
    }

    private static class BranchData extends JumpData {

        private static final int BRANCH_DATA_TAG = 7;
        private static final int BRANCH_DATA_SIZE = cellIndexToOffset(3);
        private static final int NOT_TAKEN_COUNT_OFFSET = cellIndexToOffset(2);

        public BranchData() {
            super(BRANCH_DATA_TAG, BRANCH_DATA_SIZE);
        }

        @Override
        public double getBranchTakenProbability(HotSpotMethodData data, int position) {
            long takenCount = data.readUnsignedInt(position, TAKEN_COUNT_OFFSET);
            long notTakenCount = data.readUnsignedInt(position, NOT_TAKEN_COUNT_OFFSET);
            long total = takenCount + notTakenCount;

            return total <= 0 ? -1 : takenCount / (double) total;
        }

        @Override
        public int getExecutionCount(HotSpotMethodData data, int position) {
            long count = data.readUnsignedInt(position, TAKEN_COUNT_OFFSET) + data.readUnsignedInt(position, NOT_TAKEN_COUNT_OFFSET);
            return truncateLongToInt(count);
        }
    }

    private static class ArrayData extends AbstractMethodData {

        private static final int ARRAY_DATA_LENGTH_OFFSET = cellIndexToOffset(0);
        protected static final int ARRAY_DATA_START_OFFSET = cellIndexToOffset(1);

        public ArrayData(int tag, int staticSize) {
            super(tag, staticSize);
        }

        @Override
        protected int getDynamicSize(HotSpotMethodData data, int position) {
            return cellsToBytes(getLength(data, position));
        }

        protected static int getLength(HotSpotMethodData data, int position) {
            return data.readInt(position, ARRAY_DATA_LENGTH_OFFSET);
        }
    }

    private static class MultiBranchData extends ArrayData {

        private static final int MULTI_BRANCH_DATA_TAG = 8;
        private static final int MULTI_BRANCH_DATA_SIZE = cellIndexToOffset(1);
        private static final int MULTI_BRANCH_DATA_ROW_SIZE_IN_CELLS = 2;
        private static final int MULTI_BRANCH_DATA_ROW_SIZE = cellsToBytes(MULTI_BRANCH_DATA_ROW_SIZE_IN_CELLS);
        private static final int MULTI_BRANCH_DATA_FIRST_COUNT_OFFSET = ARRAY_DATA_START_OFFSET + cellsToBytes(0);
        private static final int MULTI_BRANCH_DATA_FIRST_DISPLACEMENT_OFFSET = ARRAY_DATA_START_OFFSET + cellsToBytes(1);

        public MultiBranchData() {
            super(MULTI_BRANCH_DATA_TAG, MULTI_BRANCH_DATA_SIZE);
        }

        @Override
        public double[] getSwitchProbabilities(HotSpotMethodData data, int position) {
            int arrayLength = getLength(data, position);
            assert arrayLength > 0 : "switch must have at least the default case";
            assert arrayLength % MULTI_BRANCH_DATA_ROW_SIZE_IN_CELLS == 0 : "array must have full rows";

            int length = arrayLength / MULTI_BRANCH_DATA_ROW_SIZE_IN_CELLS;
            long totalCount = 0;
            double[] result = new double[length];

            // default case is first in HotSpot but last for the compiler
            long count = readCount(data, position, 0);
            totalCount += count;
            result[length - 1] = count;

            for (int i = 1; i < length; i++) {
                count = readCount(data, position, i);
                totalCount += count;
                result[i - 1] = count;
            }

            if (totalCount <= 0) {
                return null;
            } else {
                for (int i = 0; i < length; i++) {
                    result[i] = result[i] / totalCount;
                }
                return result;
            }
        }

        private static long readCount(HotSpotMethodData data, int position, int i) {
            int offset;
            long count;
            offset = getCountOffset(i);
            count = data.readUnsignedInt(position, offset);
            return count;
        }

        @Override
        public int getExecutionCount(HotSpotMethodData data, int position) {
            int arrayLength = getLength(data, position);
            assert arrayLength > 0 : "switch must have at least the default case";
            assert arrayLength % MULTI_BRANCH_DATA_ROW_SIZE_IN_CELLS == 0 : "array must have full rows";

            int length = arrayLength / MULTI_BRANCH_DATA_ROW_SIZE_IN_CELLS;
            long totalCount = 0;
            for (int i = 0; i < length; i++) {
                int offset = getCountOffset(i);
                totalCount += data.readUnsignedInt(position, offset);
            }

            return truncateLongToInt(totalCount);
        }

        private static int getCountOffset(int index) {
            return MULTI_BRANCH_DATA_FIRST_COUNT_OFFSET + index * MULTI_BRANCH_DATA_ROW_SIZE;
        }

        @SuppressWarnings("unused")
        private static int getDisplacementOffset(int index) {
            return MULTI_BRANCH_DATA_FIRST_DISPLACEMENT_OFFSET + index * MULTI_BRANCH_DATA_ROW_SIZE;
        }
    }

    private static class ArgInfoData extends ArrayData {

        private static final int ARG_INFO_DATA_TAG = 9;
        private static final int ARG_INFO_DATA_SIZE = cellIndexToOffset(1);

        public ArgInfoData() {
            super(ARG_INFO_DATA_TAG, ARG_INFO_DATA_SIZE);
        }
    }
}
