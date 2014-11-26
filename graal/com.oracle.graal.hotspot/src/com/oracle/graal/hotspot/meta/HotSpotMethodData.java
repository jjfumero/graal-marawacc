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

import static com.oracle.graal.compiler.common.UnsafeAccess.*;
import static com.oracle.graal.hotspot.HotSpotGraalRuntime.*;
import static java.lang.String.*;

import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.meta.JavaMethodProfile.ProfiledMethod;
import com.oracle.graal.api.meta.JavaTypeProfile.ProfiledType;
import com.oracle.graal.api.meta.ProfilingInfo.TriState;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.meta.HotSpotMethodDataAccessor.Tag;

/**
 * Access to a HotSpot MethodData structure (defined in methodData.hpp).
 */
public final class HotSpotMethodData extends CompilerObject {

    private static final long serialVersionUID = -8873133496591225071L;

    private static final HotSpotVMConfig config = runtime().getConfig();
    private static final HotSpotMethodDataAccessor NO_DATA_NO_EXCEPTION_ACCESSOR = new NoMethodData(TriState.FALSE);
    private static final HotSpotMethodDataAccessor NO_DATA_EXCEPTION_POSSIBLY_NOT_RECORDED_ACCESSOR = new NoMethodData(TriState.UNKNOWN);

    // sorted by tag
    // @formatter:off
    private static final HotSpotMethodDataAccessor[] PROFILE_DATA_ACCESSORS = {
        null,
        new BitData(),
        new CounterData(),
        new JumpData(),
        new TypeCheckData(),
        new VirtualCallData(),
        new RetData(),
        new BranchData(),
        new MultiBranchData(),
        new ArgInfoData(),
        null, // call_type_data_tag
        null, // virtual_call_type_data_tag
        null, // parameters_type_data_tag
        null, // speculative_trap_data_tag
    };
    // @formatter:on

    /**
     * Reference to the C++ MethodData object.
     */
    private final long metaspaceMethodData;

    public HotSpotMethodData(long metaspaceMethodData) {
        this.metaspaceMethodData = metaspaceMethodData;
    }

    /**
     * @return value of the MethodData::_data_size field
     */
    private int normalDataSize() {
        return unsafe.getInt(metaspaceMethodData + config.methodDataDataSize);
    }

    /**
     * Returns the size of the extra data records. This method does the same calculation as
     * MethodData::extra_data_size().
     *
     * @return size of extra data records
     */
    private int extraDataSize() {
        final int extraDataBase = config.methodDataOopDataOffset + normalDataSize();
        final int extraDataLimit = unsafe.getInt(metaspaceMethodData + config.methodDataSize);
        return extraDataLimit - extraDataBase;
    }

    public boolean hasNormalData() {
        return normalDataSize() > 0;
    }

    public boolean hasExtraData() {
        return extraDataSize() > 0;
    }

    public int getExtraDataBeginOffset() {
        return normalDataSize();
    }

    public boolean isWithin(int position) {
        return position >= 0 && position < normalDataSize() + extraDataSize();
    }

    public int getDeoptimizationCount(DeoptimizationReason reason) {
        HotSpotMetaAccessProvider metaAccess = (HotSpotMetaAccessProvider) runtime().getHostProviders().getMetaAccess();
        int reasonIndex = metaAccess.convertDeoptReason(reason);
        return unsafe.getByte(metaspaceMethodData + config.methodDataOopTrapHistoryOffset + reasonIndex) & 0xFF;
    }

    public int getOSRDeoptimizationCount(DeoptimizationReason reason) {
        HotSpotMetaAccessProvider metaAccess = (HotSpotMetaAccessProvider) runtime().getHostProviders().getMetaAccess();
        int reasonIndex = metaAccess.convertDeoptReason(reason);
        return unsafe.getByte(metaspaceMethodData + config.methodDataOopTrapHistoryOffset + config.deoptReasonOSROffset + reasonIndex) & 0xFF;
    }

    public HotSpotMethodDataAccessor getNormalData(int position) {
        if (position >= normalDataSize()) {
            return null;
        }

        HotSpotMethodDataAccessor result = getData(position);
        assert result != null : "NO_DATA tag is not allowed";
        return result;
    }

    public HotSpotMethodDataAccessor getExtraData(int position) {
        if (position >= normalDataSize() + extraDataSize()) {
            return null;
        }
        HotSpotMethodDataAccessor data = getData(position);
        if (data != null) {
            return data;
        }
        return data;
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
        final Tag tag = AbstractMethodData.readTag(this, position);
        HotSpotMethodDataAccessor accessor = PROFILE_DATA_ACCESSORS[tag.getValue()];
        assert accessor == null || accessor.getTag() == tag : "wrong data accessor " + accessor + " for tag " + tag;
        return accessor;
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
        return unsafe.getAddress(metaspaceMethodData + fullOffsetInBytes) & 0xFFFFFFFFL;
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
        return (int) unsafe.getAddress(metaspaceMethodData + fullOffsetInBytes);
    }

    private long readWord(int position, int offsetInBytes) {
        long fullOffsetInBytes = computeFullOffset(position, offsetInBytes);
        return unsafe.getAddress(metaspaceMethodData + fullOffsetInBytes);
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

    /**
     * Returns whether profiling ran long enough that the profile information is mature. Other
     * informational data will still be valid even if the profile isn't mature.
     */
    public boolean isProfileMature() {
        return runtime().getCompilerToVM().isMature(metaspaceMethodData);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String nl = String.format("%n");
        String nlIndent = String.format("%n%38s", "");
        if (hasNormalData()) {
            int pos = 0;
            HotSpotMethodDataAccessor data;
            while ((data = getNormalData(pos)) != null) {
                if (pos != 0) {
                    sb.append(nl);
                }
                int bci = data.getBCI(this, pos);
                sb.append(String.format("%-6d bci: %-6d%-20s", pos, bci, data.getClass().getSimpleName()));
                sb.append(data.appendTo(new StringBuilder(), this, pos).toString().replace(nl, nlIndent));
                pos = pos + data.getSize(this, pos);
            }
        }

        if (hasExtraData()) {
            int pos = getExtraDataBeginOffset();
            HotSpotMethodDataAccessor data;
            while ((data = getExtraData(pos)) != null) {
                if (pos == getExtraDataBeginOffset()) {
                    sb.append(nl).append("--- Extra data:");
                }
                int bci = data.getBCI(this, pos);
                sb.append(String.format("%n%-6d bci: %-6d%-20s", pos, bci, data.getClass().getSimpleName()));
                sb.append(data.appendTo(new StringBuilder(), this, pos).toString().replace(nl, nlIndent));
                pos = pos + data.getSize(this, pos);
            }

        }
        return sb.toString();
    }

    private abstract static class AbstractMethodData implements HotSpotMethodDataAccessor {

        /**
         * Corresponds to {@code exception_seen_flag}.
         */
        private static final int EXCEPTIONS_MASK = 0x2;

        private final Tag tag;
        private final int staticSize;

        protected AbstractMethodData(Tag tag, int staticSize) {
            this.tag = tag;
            this.staticSize = staticSize;
        }

        public Tag getTag() {
            return tag;
        }

        public static Tag readTag(HotSpotMethodData data, int position) {
            final int tag = data.readUnsignedByte(position, config.dataLayoutTagOffset);
            return Tag.getEnum(tag);
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

        /**
         * @param data
         * @param position
         */
        protected int getDynamicSize(HotSpotMethodData data, int position) {
            return 0;
        }

        public abstract StringBuilder appendTo(StringBuilder sb, HotSpotMethodData data, int pos);
    }

    private static class NoMethodData extends AbstractMethodData {

        private static final int NO_DATA_SIZE = cellIndexToOffset(0);

        private final TriState exceptionSeen;

        protected NoMethodData(TriState exceptionSeen) {
            super(Tag.No, NO_DATA_SIZE);
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

        @Override
        public StringBuilder appendTo(StringBuilder sb, HotSpotMethodData data, int pos) {
            return sb;
        }
    }

    private static class BitData extends AbstractMethodData {

        private static final int BIT_DATA_SIZE = cellIndexToOffset(0);
        private static final int BIT_DATA_NULL_SEEN_FLAG = 0x01;

        private BitData() {
            super(Tag.BitData, BIT_DATA_SIZE);
        }

        protected BitData(Tag tag, int staticSize) {
            super(tag, staticSize);
        }

        @Override
        public TriState getNullSeen(HotSpotMethodData data, int position) {
            return TriState.get((getFlags(data, position) & BIT_DATA_NULL_SEEN_FLAG) != 0);
        }

        @Override
        public StringBuilder appendTo(StringBuilder sb, HotSpotMethodData data, int pos) {
            return sb.append(format("exception_seen(%s)", getExceptionSeen(data, pos)));
        }
    }

    private static class CounterData extends BitData {

        private static final int COUNTER_DATA_SIZE = cellIndexToOffset(1);
        private static final int COUNTER_DATA_COUNT_OFFSET = cellIndexToOffset(0);

        public CounterData() {
            super(Tag.CounterData, COUNTER_DATA_SIZE);
        }

        protected CounterData(Tag tag, int staticSize) {
            super(tag, staticSize);
        }

        @Override
        public int getExecutionCount(HotSpotMethodData data, int position) {
            return getCounterValue(data, position);
        }

        protected int getCounterValue(HotSpotMethodData data, int position) {
            return data.readUnsignedIntAsSignedInt(position, COUNTER_DATA_COUNT_OFFSET);
        }

        @Override
        public StringBuilder appendTo(StringBuilder sb, HotSpotMethodData data, int pos) {
            return sb.append(format("count(%d) null_seen(%s) exception_seen(%s)", getCounterValue(data, pos), getNullSeen(data, pos), getExceptionSeen(data, pos)));
        }
    }

    private static class JumpData extends AbstractMethodData {

        private static final int JUMP_DATA_SIZE = cellIndexToOffset(2);
        protected static final int TAKEN_COUNT_OFFSET = cellIndexToOffset(0);
        protected static final int TAKEN_DISPLACEMENT_OFFSET = cellIndexToOffset(1);

        public JumpData() {
            super(Tag.JumpData, JUMP_DATA_SIZE);
        }

        protected JumpData(Tag tag, int staticSize) {
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

        public int getTakenDisplacement(HotSpotMethodData data, int position) {
            return data.readInt(position, TAKEN_DISPLACEMENT_OFFSET);
        }

        @Override
        public StringBuilder appendTo(StringBuilder sb, HotSpotMethodData data, int pos) {
            return sb.append(format("taken(%d) displacement(%d)", getExecutionCount(data, pos), getTakenDisplacement(data, pos)));
        }
    }

    static class RawItemProfile<T> {
        final int entries;
        final T[] items;
        final long[] counts;
        final long totalCount;

        public RawItemProfile(int entries, T[] items, long[] counts, long totalCount) {
            this.entries = entries;
            this.items = items;
            this.counts = counts;
            this.totalCount = totalCount;
        }
    }

    private abstract static class AbstractTypeData extends CounterData {

        protected static final int TYPE_DATA_ROW_SIZE = cellsToBytes(2);

        protected static final int NONPROFILED_COUNT_OFFSET = cellIndexToOffset(1);
        protected static final int TYPE_DATA_FIRST_TYPE_OFFSET = cellIndexToOffset(2);
        protected static final int TYPE_DATA_FIRST_TYPE_COUNT_OFFSET = cellIndexToOffset(3);

        protected AbstractTypeData(Tag tag, int staticSize) {
            super(tag, staticSize);
        }

        @Override
        public JavaTypeProfile getTypeProfile(HotSpotMethodData data, int position) {
            return createTypeProfile(getNullSeen(data, position), getRawTypeProfile(data, position));
        }

        private RawItemProfile<ResolvedJavaType> getRawTypeProfile(HotSpotMethodData data, int position) {
            int typeProfileWidth = config.typeProfileWidth;

            ResolvedJavaType[] types = new ResolvedJavaType[typeProfileWidth];
            long[] counts = new long[typeProfileWidth];
            long totalCount = 0;
            int entries = 0;

            outer: for (int i = 0; i < typeProfileWidth; i++) {
                long receiverKlass = data.readWord(position, getTypeOffset(i));
                if (receiverKlass != 0) {
                    HotSpotResolvedObjectTypeImpl klass = HotSpotResolvedObjectTypeImpl.fromMetaspaceKlass(receiverKlass);
                    long count = data.readUnsignedInt(position, getTypeCountOffset(i));
                    /*
                     * Because of races in the profile collection machinery it's possible for a
                     * class to appear multiple times so merge them to make the profile look
                     * rational.
                     */
                    for (int j = 0; j < entries; j++) {
                        if (types[j].equals(klass)) {
                            totalCount += count;
                            counts[j] += count;
                            continue outer;
                        }
                    }
                    types[entries] = klass;
                    totalCount += count;
                    counts[entries] = count;
                    entries++;
                }
            }

            totalCount += getTypesNotRecordedExecutionCount(data, position);
            return new RawItemProfile<>(entries, types, counts, totalCount);
        }

        protected abstract long getTypesNotRecordedExecutionCount(HotSpotMethodData data, int position);

        private static JavaTypeProfile createTypeProfile(TriState nullSeen, RawItemProfile<ResolvedJavaType> profile) {
            if (profile.entries <= 0 || profile.totalCount <= 0) {
                return null;
            }

            ProfiledType[] ptypes = new ProfiledType[profile.entries];
            double totalProbability = 0.0;
            for (int i = 0; i < profile.entries; i++) {
                double p = profile.counts[i];
                p = p / profile.totalCount;
                totalProbability += p;
                ptypes[i] = new ProfiledType(profile.items[i], p);
            }

            Arrays.sort(ptypes);

            double notRecordedTypeProbability = profile.entries < config.typeProfileWidth ? 0.0 : Math.min(1.0, Math.max(0.0, 1.0 - totalProbability));
            assert notRecordedTypeProbability == 0 || profile.entries == config.typeProfileWidth;
            return new JavaTypeProfile(nullSeen, notRecordedTypeProbability, ptypes);
        }

        private static int getTypeOffset(int row) {
            return TYPE_DATA_FIRST_TYPE_OFFSET + row * TYPE_DATA_ROW_SIZE;
        }

        protected static int getTypeCountOffset(int row) {
            return TYPE_DATA_FIRST_TYPE_COUNT_OFFSET + row * TYPE_DATA_ROW_SIZE;
        }

        @Override
        public StringBuilder appendTo(StringBuilder sb, HotSpotMethodData data, int pos) {
            RawItemProfile<ResolvedJavaType> profile = getRawTypeProfile(data, pos);
            TriState nullSeen = getNullSeen(data, pos);
            TriState exceptionSeen = getExceptionSeen(data, pos);
            sb.append(format("count(%d) null_seen(%s) exception_seen(%s) nonprofiled_count(%d) entries(%d)", getCounterValue(data, pos), nullSeen, exceptionSeen,
                            getTypesNotRecordedExecutionCount(data, pos), profile.entries));
            for (int i = 0; i < profile.entries; i++) {
                long count = profile.counts[i];
                sb.append(format("%n  %s (%d, %4.2f)", profile.items[i].toJavaName(), count, (double) count / profile.totalCount));
            }
            return sb;
        }
    }

    private static class TypeCheckData extends AbstractTypeData {

        private static final int TYPE_CHECK_DATA_SIZE = cellIndexToOffset(2) + TYPE_DATA_ROW_SIZE * config.typeProfileWidth;

        public TypeCheckData() {
            super(Tag.ReceiverTypeData, TYPE_CHECK_DATA_SIZE);
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

        private static final int VIRTUAL_CALL_DATA_SIZE = cellIndexToOffset(2) + TYPE_DATA_ROW_SIZE * (config.typeProfileWidth + config.methodProfileWidth);
        private static final int VIRTUAL_CALL_DATA_FIRST_METHOD_OFFSET = TYPE_DATA_FIRST_TYPE_OFFSET + TYPE_DATA_ROW_SIZE * config.typeProfileWidth;
        private static final int VIRTUAL_CALL_DATA_FIRST_METHOD_COUNT_OFFSET = TYPE_DATA_FIRST_TYPE_COUNT_OFFSET + TYPE_DATA_ROW_SIZE * config.typeProfileWidth;

        public VirtualCallData() {
            super(Tag.VirtualCallData, VIRTUAL_CALL_DATA_SIZE);
        }

        @Override
        public int getExecutionCount(HotSpotMethodData data, int position) {
            final int typeProfileWidth = config.typeProfileWidth;

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
            return createMethodProfile(getRawMethodProfile(data, position));
        }

        private static RawItemProfile<ResolvedJavaMethod> getRawMethodProfile(HotSpotMethodData data, int position) {
            int profileWidth = config.methodProfileWidth;

            ResolvedJavaMethod[] methods = new ResolvedJavaMethod[profileWidth];
            long[] counts = new long[profileWidth];
            long totalCount = 0;
            int entries = 0;

            for (int i = 0; i < profileWidth; i++) {
                long method = data.readWord(position, getMethodOffset(i));
                if (method != 0) {
                    methods[entries] = HotSpotResolvedJavaMethodImpl.fromMetaspace(method);
                    long count = data.readUnsignedInt(position, getMethodCountOffset(i));
                    totalCount += count;
                    counts[entries] = count;

                    entries++;
                }
            }

            totalCount += getMethodsNotRecordedExecutionCount(data, position);
            return new RawItemProfile<>(entries, methods, counts, totalCount);
        }

        private static JavaMethodProfile createMethodProfile(RawItemProfile<ResolvedJavaMethod> profile) {
            if (profile.entries <= 0 || profile.totalCount <= 0) {
                return null;
            }

            ProfiledMethod[] pmethods = new ProfiledMethod[profile.entries];
            double totalProbability = 0.0;
            for (int i = 0; i < profile.entries; i++) {
                double p = profile.counts[i];
                p = p / profile.totalCount;
                totalProbability += p;
                pmethods[i] = new ProfiledMethod(profile.items[i], p);
            }

            Arrays.sort(pmethods);

            double notRecordedMethodProbability = profile.entries < config.methodProfileWidth ? 0.0 : Math.min(1.0, Math.max(0.0, 1.0 - totalProbability));
            assert notRecordedMethodProbability == 0 || profile.entries == config.methodProfileWidth;
            return new JavaMethodProfile(notRecordedMethodProbability, pmethods);
        }

        private static int getMethodOffset(int row) {
            return VIRTUAL_CALL_DATA_FIRST_METHOD_OFFSET + row * TYPE_DATA_ROW_SIZE;
        }

        private static int getMethodCountOffset(int row) {
            return VIRTUAL_CALL_DATA_FIRST_METHOD_COUNT_OFFSET + row * TYPE_DATA_ROW_SIZE;
        }

        @Override
        public StringBuilder appendTo(StringBuilder sb, HotSpotMethodData data, int pos) {
            RawItemProfile<ResolvedJavaMethod> profile = getRawMethodProfile(data, pos);
            super.appendTo(sb.append(format("exception_seen(%s) ", getExceptionSeen(data, pos))), data, pos).append(format("%nmethod_entries(%d)", profile.entries));
            for (int i = 0; i < profile.entries; i++) {
                long count = profile.counts[i];
                sb.append(format("%n  %s (%d, %4.2f)", profile.items[i].format("%H.%n(%p)"), count, (double) count / profile.totalCount));
            }
            return sb;
        }
    }

    private static class RetData extends CounterData {

        private static final int RET_DATA_ROW_SIZE = cellsToBytes(3);
        private static final int RET_DATA_SIZE = cellIndexToOffset(1) + RET_DATA_ROW_SIZE * config.bciProfileWidth;

        public RetData() {
            super(Tag.RetData, RET_DATA_SIZE);
        }
    }

    private static class BranchData extends JumpData {

        private static final int BRANCH_DATA_SIZE = cellIndexToOffset(3);
        private static final int NOT_TAKEN_COUNT_OFFSET = cellIndexToOffset(2);

        public BranchData() {
            super(Tag.BranchData, BRANCH_DATA_SIZE);
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

        @Override
        public StringBuilder appendTo(StringBuilder sb, HotSpotMethodData data, int pos) {
            long taken = data.readUnsignedInt(pos, TAKEN_COUNT_OFFSET);
            long notTaken = data.readUnsignedInt(pos, NOT_TAKEN_COUNT_OFFSET);
            double takenProbability = getBranchTakenProbability(data, pos);
            return sb.append(format("taken(%d, %4.2f) not_taken(%d, %4.2f) displacement(%d)", taken, takenProbability, notTaken, 1.0D - takenProbability, getTakenDisplacement(data, pos)));
        }
    }

    private static class ArrayData extends AbstractMethodData {

        private static final int ARRAY_DATA_LENGTH_OFFSET = cellIndexToOffset(0);
        protected static final int ARRAY_DATA_START_OFFSET = cellIndexToOffset(1);

        public ArrayData(Tag tag, int staticSize) {
            super(tag, staticSize);
        }

        @Override
        protected int getDynamicSize(HotSpotMethodData data, int position) {
            return cellsToBytes(getLength(data, position));
        }

        protected static int getLength(HotSpotMethodData data, int position) {
            return data.readInt(position, ARRAY_DATA_LENGTH_OFFSET);
        }

        @Override
        public StringBuilder appendTo(StringBuilder sb, HotSpotMethodData data, int pos) {
            return sb.append(format("length(%d)", getLength(data, pos)));
        }
    }

    private static class MultiBranchData extends ArrayData {

        private static final int MULTI_BRANCH_DATA_SIZE = cellIndexToOffset(1);
        private static final int MULTI_BRANCH_DATA_ROW_SIZE_IN_CELLS = 2;
        private static final int MULTI_BRANCH_DATA_ROW_SIZE = cellsToBytes(MULTI_BRANCH_DATA_ROW_SIZE_IN_CELLS);
        private static final int MULTI_BRANCH_DATA_FIRST_COUNT_OFFSET = ARRAY_DATA_START_OFFSET + cellsToBytes(0);
        private static final int MULTI_BRANCH_DATA_FIRST_DISPLACEMENT_OFFSET = ARRAY_DATA_START_OFFSET + cellsToBytes(1);

        public MultiBranchData() {
            super(Tag.MultiBranchData, MULTI_BRANCH_DATA_SIZE);
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

        private static int getDisplacementOffset(int index) {
            return MULTI_BRANCH_DATA_FIRST_DISPLACEMENT_OFFSET + index * MULTI_BRANCH_DATA_ROW_SIZE;
        }

        @Override
        public StringBuilder appendTo(StringBuilder sb, HotSpotMethodData data, int pos) {
            int entries = getLength(data, pos) / MULTI_BRANCH_DATA_ROW_SIZE_IN_CELLS;
            sb.append(format("entries(%d)", entries));
            for (int i = 0; i < entries; i++) {
                sb.append(format("%n  %d: count(%d) displacement(%d)", i, data.readUnsignedInt(pos, getCountOffset(i)), data.readUnsignedInt(pos, getDisplacementOffset(i))));
            }
            return sb;
        }
    }

    private static class ArgInfoData extends ArrayData {

        private static final int ARG_INFO_DATA_SIZE = cellIndexToOffset(1);

        public ArgInfoData() {
            super(Tag.ArgInfoData, ARG_INFO_DATA_SIZE);
        }
    }

    public void setCompiledGraphSize(int nodeCount) {
        unsafe.putInt(metaspaceMethodData + config.methodDataGraalNodeCountOffset, nodeCount);
    }

    public int getCompiledGraphSize() {
        return unsafe.getInt(metaspaceMethodData + config.methodDataGraalNodeCountOffset);
    }
}
