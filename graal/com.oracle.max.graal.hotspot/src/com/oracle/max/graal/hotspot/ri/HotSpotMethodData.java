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
package com.oracle.max.graal.hotspot.ri;

import sun.misc.*;

import com.oracle.max.cri.ri.*;
import com.oracle.max.graal.hotspot.*;
import com.oracle.max.graal.hotspot.Compiler;


public final class HotSpotMethodData extends CompilerObject {

    /**
     *
     */
    private static final long serialVersionUID = -8873133496591225071L;

    static {
        config = CompilerImpl.getInstance().getConfig();
    }

    // TODO (ch) use same logic as in NodeClass?
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final HotSpotMethodDataAccessor NO_DATA_ACCESSOR = new NoDataAccessor();
    private static final HotSpotVMConfig config;
    private static final HotSpotMethodDataAccessor[] PROFILE_DATA_ACCESSORS = {
        null, new BitData(), new CounterData(), new JumpData(),
        new TypeCheckData(), new VirtualCallData(), new RetData(),
        new BranchData(), new MultiBranchData(), new ArgInfoData()
    };

    private Object hotspotMirror;
    private int normalDataSize;
    private int extraDataSize;
    private boolean mature;

    // TODO (ch) how are we going to handle methodData->is_mature()

    private HotSpotMethodData(Compiler compiler) {
        super(compiler);
        throw new IllegalStateException("this constructor is never actually called, because the objects are allocated from within the VM");
    }

    public boolean hasNormalData() {
        return normalDataSize > 0;
    }

    public boolean hasExtraData() {
        return extraDataSize > 0;
    }

    public boolean isMature() {
        if (!mature) {
            mature = compiler.getVMEntries().HotSpotMethodData_isMature(this);
        }
        return mature;
    }

    public boolean isWithin(int position) {
        return position >= 0 && position < normalDataSize + extraDataSize;
    }

    public HotSpotMethodDataAccessor getNormalData(int position) {
        if (position >= normalDataSize) {
            return null;
        }

        HotSpotMethodDataAccessor result = getData(position, 0);
        assert result != null : "NO_DATA tag is not allowed";
        return result;
    }

    public HotSpotMethodDataAccessor getExtraData(int position) {
        if (position >= extraDataSize) {
            return null;
        }
        return getData(position, normalDataSize);
    }

    public static HotSpotMethodDataAccessor getNoDataAccessor() {
        return NO_DATA_ACCESSOR;
    }

    private HotSpotMethodDataAccessor getData(int position, int displacement) {
        assert position >= 0 : "out of bounds";
        int tag = AbstractMethodDataAccessor.readTag(this, displacement + position);
        assert tag >= 0 && tag < PROFILE_DATA_ACCESSORS.length : "illegal tag";
        return PROFILE_DATA_ACCESSORS[tag];
    }

    private int readUnsignedByte(int position, int offsetInBytes) {
        long fullOffsetInBytes = computeFullOffset(position, offsetInBytes);
        return unsafe.getByte(hotspotMirror, fullOffsetInBytes) & 0xFF;
    }

    private int readUnsignedShort(int position, int offsetInBytes) {
        long fullOffsetInBytes = computeFullOffset(position, offsetInBytes);
        return unsafe.getShort(hotspotMirror, fullOffsetInBytes) & 0xFFFF;
    }

    private long readUnsignedInt(int position, int offsetInBytes) {
        long fullOffsetInBytes = computeFullOffset(position, offsetInBytes);
        return unsafe.getInt(hotspotMirror, fullOffsetInBytes) & 0xFFFFFFFFL;
    }

    private int readUnsignedIntAsSignedInt(int position, int offsetInBytes) {
        long value = readUnsignedInt(position, offsetInBytes);
        return truncateLongToInt(value);
    }

    private int readInt(int position, int offsetInBytes) {
        long fullOffsetInBytes = computeFullOffset(position, offsetInBytes);
        return unsafe.getInt(hotspotMirror, fullOffsetInBytes);
    }

    private Object readObject(int position, int offsetInBytes) {
        long fullOffsetInBytes = computeFullOffset(position, offsetInBytes);
        return unsafe.getObject(hotspotMirror, fullOffsetInBytes);
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

    private abstract static class AbstractMethodDataAccessor implements HotSpotMethodDataAccessor {
        private static final int IMPLICIT_EXCEPTIONS_MASK = 0x0E;

        private final int tag;
        private final int staticSize;

        protected AbstractMethodDataAccessor(int tag, int staticSize) {
            this.tag = tag;
            this.staticSize = staticSize;
        }

        @Override
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
        public boolean getImplicitExceptionSeen(HotSpotMethodData data, int position) {
            // TODO (ch) might return true too often because flags are also used for deoptimization reasons
            return (getFlags(data, position) & IMPLICIT_EXCEPTIONS_MASK) != 0;
        }

        @Override
        public RiTypeProfile getTypeProfile(HotSpotMethodData data, int position) {
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

        protected int getFlags(HotSpotMethodData data, int position) {
            return data.readUnsignedByte(position, config.dataLayoutFlagsOffset);
        }

        protected int getDynamicSize(@SuppressWarnings("unused") HotSpotMethodData data, @SuppressWarnings("unused") int position) {
            return 0;
        }

        protected static int addressData(int offset) {
            return config.dataLayoutHeaderSize + offset;
        }
    }

    private static class NoDataAccessor extends AbstractMethodDataAccessor {
        private static final int NO_DATA_TAG = 0;
        private static final int NO_DATA_SIZE = cellIndexToOffset(0);

        protected NoDataAccessor() {
            super(NO_DATA_TAG, NO_DATA_SIZE);
        }

        @Override
        public int getBCI(HotSpotMethodData data, int position) {
            return -1;
        }


        @Override
        public boolean getImplicitExceptionSeen(HotSpotMethodData data, int position) {
            return false;
        }
    }

    private static class BitData extends AbstractMethodDataAccessor {
        private static final int BIT_DATA_TAG = 1;
        private static final int BIT_DATA_SIZE = cellIndexToOffset(0);
        private static final int BIT_DATA_NULL_SEEN_FLAG = 0x01;

        private BitData() {
            super(BIT_DATA_TAG, BIT_DATA_SIZE);
        }

        protected BitData(int tag, int staticSize) {
            super(tag, staticSize);
        }

        public boolean getNullSeen(HotSpotMethodData data, int position) {
            return (getFlags(data, position) & BIT_DATA_NULL_SEEN_FLAG) != 0;
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

    private static class JumpData extends AbstractMethodDataAccessor {
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
            return 1;
        }

        @Override
        public int getExecutionCount(HotSpotMethodData data, int position) {
            return data.readUnsignedIntAsSignedInt(position, TAKEN_COUNT_OFFSET);
        }

        public int getDisplacement(HotSpotMethodData data, int position) {
            return data.readInt(position, TAKEN_DISPLACEMENT_OFFSET);
        }
    }

    private static class AbstractTypeData extends CounterData {
        private static final int RECEIVER_TYPE_DATA_ROW_SIZE = cellsToBytes(2);
        private static final int RECEIVER_TYPE_DATA_SIZE = cellIndexToOffset(1) + RECEIVER_TYPE_DATA_ROW_SIZE * config.typeProfileWidth;
        private static final int RECEIVER_TYPE_DATA_FIRST_RECEIVER_OFFSET = cellIndexToOffset(1);
        private static final int RECEIVER_TYPE_DATA_FIRST_COUNT_OFFSET = cellIndexToOffset(2);

        protected AbstractTypeData(int tag) {
            super(tag, RECEIVER_TYPE_DATA_SIZE);
        }

        @Override
        public RiTypeProfile getTypeProfile(HotSpotMethodData data, int position) {
            int typeProfileWidth = config.typeProfileWidth;

            RiResolvedType[] sparseTypes = new RiResolvedType[typeProfileWidth];
            double[] counts = new double[typeProfileWidth];
            long totalCount = 0;
            int entries = 0;

            for (int i = 0; i < typeProfileWidth; i++) {
                Object receiverKlassOop = data.readObject(position, getReceiverOffset(i));
                if (receiverKlassOop != null) {
                    Object graalMirror = unsafe.getObject(receiverKlassOop, (long) config.graalMirrorKlassOffset);
                    if (graalMirror == null) {
                        Class<?> javaClass = (Class<?>) unsafe.getObject(receiverKlassOop, (long) config.classMirrorOffset);
                        graalMirror = CompilerImpl.getInstance().getVMEntries().getType(javaClass);
                        assert graalMirror != null : "must not return null";
                    }

                    long count = data.readUnsignedInt(position, getCountOffset(i));
                    if (count > 0) {
                        totalCount += count;
                        counts[entries] = count;
                        entries++;
                    }

                    sparseTypes[i] = (RiResolvedType) graalMirror;
                }
            }

            RiResolvedType[] types;
            double[] probabilities;

            if (entries <= 0) {
                return null;
            } else if (entries < typeProfileWidth) {
                RiResolvedType[] compactedTypes = new RiResolvedType[entries];
                System.arraycopy(sparseTypes, 0, compactedTypes, 0, entries);
                types = compactedTypes;
                probabilities = new double[entries];
            } else {
                types = sparseTypes;
                probabilities = counts;
            }

            for (int i = 0; i < entries; i++) {
                probabilities[i] = counts[i] / totalCount;
            }
            return new RiTypeProfile(types, probabilities);
        }

        private static int getReceiverOffset(int row) {
            return RECEIVER_TYPE_DATA_FIRST_RECEIVER_OFFSET + row * RECEIVER_TYPE_DATA_ROW_SIZE;
        }

        protected static int getCountOffset(int row) {
            return RECEIVER_TYPE_DATA_FIRST_COUNT_OFFSET + row * RECEIVER_TYPE_DATA_ROW_SIZE;
        }
    }

    private static class TypeCheckData extends AbstractTypeData {
        private static final int RECEIVER_TYPE_DATA_TAG = 4;

        public TypeCheckData() {
            super(RECEIVER_TYPE_DATA_TAG);
        }

        @Override
        public int getExecutionCount(HotSpotMethodData data, int position) {
            return -1;
        }
    }

    private static class VirtualCallData extends AbstractTypeData {
        private static final int VIRTUAL_CALL_DATA_TAG = 5;

        public VirtualCallData() {
            super(VIRTUAL_CALL_DATA_TAG);
        }

        @Override
        public int getExecutionCount(HotSpotMethodData data, int position) {
            int typeProfileWidth = config.typeProfileWidth;

            long total = 0;
            for (int i = 0; i < typeProfileWidth; i++) {
                total += data.readUnsignedInt(position, getCountOffset(i));
            }

            total += getCounterValue(data, position);
            return truncateLongToInt(total);
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
        private static final int BRANCH_DATA_MATURE_COUNT = 40;

        public BranchData() {
            super(BRANCH_DATA_TAG, BRANCH_DATA_SIZE);
        }

        @Override
        public double getBranchTakenProbability(HotSpotMethodData data, int position) {
            long takenCount = data.readUnsignedInt(position, TAKEN_COUNT_OFFSET);
            long notTakenCount = data.readUnsignedInt(position, NOT_TAKEN_COUNT_OFFSET);
            long total = takenCount + notTakenCount;

            if (total < BRANCH_DATA_MATURE_COUNT) {
                return -1;
            } else {
                return takenCount / (double) total;
            }
        }

        @Override
        public int getExecutionCount(HotSpotMethodData data, int position) {
            long count = data.readUnsignedInt(position, TAKEN_COUNT_OFFSET) + data.readUnsignedInt(position, NOT_TAKEN_COUNT_OFFSET);
            return truncateLongToInt(count);
        }
    }

    private static class ArrayData extends AbstractMethodDataAccessor {
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

            // default case is expected as last entry
            int offset = getCountOffset(0);
            long count = data.readUnsignedInt(position, offset);
            totalCount += count;
            result[length - 1] = count;

            for (int i = 1; i < length; i++) {
                offset = getCountOffset(i);
                count = data.readUnsignedInt(position, offset);
                totalCount += count;
                result[i - 1] = count;
            }

            if (totalCount < 10 * (length + 2)) {
                return null;
            } else {
                for (int i = 0; i < length; i++) {
                    result[i] = result[i] / totalCount;
                }
                return result;
            }
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
    }

    private static class ArgInfoData extends ArrayData {
        private static final int ARG_INFO_DATA_TAG = 9;
        private static final int ARG_INFO_DATA_SIZE = cellIndexToOffset(1);

        public ArgInfoData() {
            super(ARG_INFO_DATA_TAG, ARG_INFO_DATA_SIZE);
        }
    }
}
