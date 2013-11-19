/*
 * Copyright (c) 2011, 2013, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.graph.UnsafeAccess.*;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.bridge.*;

/**
 * Used to access native configuration details.
 * 
 * All non-static, public fields in this class are so that they can be compiled as constants.
 */
public class HotSpotVMConfig extends CompilerObject {

    private static final long serialVersionUID = -4744897993263044184L;

    HotSpotVMConfig(CompilerToVM compilerToVm) {
        compilerToVm.initializeConfiguration(this);

        // Fill the VM fields hash map.
        HashMap<String, VMFields.Field> vmFields = new HashMap<>();
        for (VMFields.Field e : new VMFields(gHotSpotVMStructs)) {
            vmFields.put(e.getName(), e);
        }

        // Fill the VM types hash map.
        HashMap<String, VMTypes.Type> vmTypes = new HashMap<>();
        for (VMTypes.Type e : new VMTypes(gHotSpotVMTypes)) {
            vmTypes.put(e.getTypeName(), e);
        }

        // Fill the VM constants hash map.
        HashMap<String, AbstractConstant> vmConstants = new HashMap<>();
        for (AbstractConstant e : new VMIntConstants(gHotSpotVMIntConstants)) {
            vmConstants.put(e.getName(), e);
        }
        for (AbstractConstant e : new VMLongConstants(gHotSpotVMLongConstants)) {
            vmConstants.put(e.getName(), e);
        }

        // Fill the flags hash map.
        HashMap<String, Flags.Flag> flags = new HashMap<>();
        for (Flags.Flag e : new Flags(vmFields, vmTypes)) {
            flags.put(e.getName(), e);
        }

        for (Field f : HotSpotVMConfig.class.getDeclaredFields()) {
            if (f.isAnnotationPresent(HotSpotVMField.class)) {
                HotSpotVMField annotation = f.getAnnotation(HotSpotVMField.class);
                String name = annotation.name();
                VMFields.Field entry = vmFields.get(name);
                if (entry == null) {
                    if (annotation.optional()) {
                        continue;
                    } else {
                        throw new IllegalArgumentException("field not found: " + name);
                    }
                }
                switch (annotation.get()) {
                    case OFFSET:
                        setField(f, entry.getOffset());
                        break;
                    case ADDRESS:
                        setField(f, entry.getAddress());
                        break;
                    case VALUE:
                        setField(f, entry.getValue());
                        break;
                    default:
                        throw GraalInternalError.shouldNotReachHere("unknown kind " + annotation.get());
                }
            } else if (f.isAnnotationPresent(HotSpotVMType.class)) {
                HotSpotVMType annotation = f.getAnnotation(HotSpotVMType.class);
                String name = annotation.name();
                VMTypes.Type entry = vmTypes.get(name);
                if (entry == null) {
                    throw new IllegalArgumentException("type not found: " + name);
                }
                switch (annotation.get()) {
                    case SIZE:
                        setField(f, entry.getSize());
                        break;
                    default:
                        throw GraalInternalError.shouldNotReachHere("unknown kind " + annotation.get());
                }
            } else if (f.isAnnotationPresent(HotSpotVMConstant.class)) {
                HotSpotVMConstant annotation = f.getAnnotation(HotSpotVMConstant.class);
                String name = annotation.name();
                AbstractConstant entry = vmConstants.get(name);
                if (entry == null) {
                    if (annotation.optional()) {
                        continue;
                    } else {
                        throw new IllegalArgumentException("constant not found: " + name);
                    }
                }
                setField(f, entry.getValue());
            } else if (f.isAnnotationPresent(HotSpotVMFlag.class)) {
                HotSpotVMFlag annotation = f.getAnnotation(HotSpotVMFlag.class);
                String name = annotation.name();
                Flags.Flag entry = flags.get(name);
                if (entry == null) {
                    if (annotation.optional()) {
                        continue;
                    } else {
                        throw new IllegalArgumentException("flag not found: " + name);
                    }
                }
                setField(f, entry.getValue());
            }
        }

        assert check();
    }

    private void setField(Field field, Object value) {
        try {
            Class<?> fieldType = field.getType();
            if (fieldType == boolean.class) {
                if (value instanceof String) {
                    field.setBoolean(this, Boolean.valueOf((String) value));
                } else if (value instanceof Boolean) {
                    field.setBoolean(this, (boolean) value);
                } else if (value instanceof Long) {
                    field.setBoolean(this, ((long) value) != 0);
                } else {
                    GraalInternalError.shouldNotReachHere(value.getClass().getSimpleName());
                }
            } else if (fieldType == int.class) {
                if (value instanceof Integer) {
                    field.setInt(this, (int) value);
                } else if (value instanceof Long) {
                    field.setInt(this, (int) (long) value);
                } else {
                    GraalInternalError.shouldNotReachHere(value.getClass().getSimpleName());
                }
            } else if (fieldType == long.class) {
                field.setLong(this, (long) value);
            } else {
                GraalInternalError.shouldNotReachHere(field.toString());
            }
        } catch (IllegalAccessException e) {
            throw GraalInternalError.shouldNotReachHere(field.toString() + ": " + e);
        }
    }

    /**
     * VMStructEntry (see vmStructs.hpp).
     */
    private long gHotSpotVMStructs;
    private long gHotSpotVMStructEntryTypeNameOffset;
    private long gHotSpotVMStructEntryFieldNameOffset;
    private long gHotSpotVMStructEntryTypeStringOffset;
    private long gHotSpotVMStructEntryIsStaticOffset;
    private long gHotSpotVMStructEntryOffsetOffset;
    private long gHotSpotVMStructEntryAddressOffset;
    private long gHotSpotVMStructEntryArrayStride;

    class VMFields implements Iterable<VMFields.Field> {

        private long address;

        public VMFields(long address) {
            this.address = address;
        }

        public Iterator<VMFields.Field> iterator() {
            return new Iterator<VMFields.Field>() {

                private int index = 0;

                private Field current() {
                    return new Field(address + gHotSpotVMStructEntryArrayStride * index);
                }

                /**
                 * The last entry is identified by a NULL fieldName.
                 */
                public boolean hasNext() {
                    Field entry = current();
                    return entry.getFieldName() != null;
                }

                public Field next() {
                    Field entry = current();
                    index++;
                    return entry;
                }

                @Override
                public void remove() {
                    throw GraalInternalError.unimplemented();
                }
            };
        }

        class Field {

            private long entryAddress;

            Field(long address) {
                this.entryAddress = address;
            }

            public String getTypeName() {
                long typeNameAddress = unsafe.getAddress(entryAddress + gHotSpotVMStructEntryTypeNameOffset);
                return readCStringAsString(typeNameAddress);
            }

            public String getFieldName() {
                long fieldNameAddress = unsafe.getAddress(entryAddress + gHotSpotVMStructEntryFieldNameOffset);
                return readCStringAsString(fieldNameAddress);
            }

            public String getTypeString() {
                long typeStringAddress = unsafe.getAddress(entryAddress + gHotSpotVMStructEntryTypeStringOffset);
                return readCStringAsString(typeStringAddress);
            }

            public boolean isStatic() {
                return unsafe.getInt(entryAddress + gHotSpotVMStructEntryIsStaticOffset) != 0;
            }

            public long getOffset() {
                return unsafe.getLong(entryAddress + gHotSpotVMStructEntryOffsetOffset);
            }

            public long getAddress() {
                return unsafe.getAddress(entryAddress + gHotSpotVMStructEntryAddressOffset);
            }

            public String getName() {
                String typeName = getTypeName();
                String fieldName = getFieldName();
                return typeName + "::" + fieldName;
            }

            public long getValue() {
                String type = getTypeString();
                switch (type) {
                    case "int":
                        return unsafe.getInt(getAddress());
                    case "address":
                    case "intptr_t":
                        return unsafe.getAddress(getAddress());
                    default:
                        // All foo* types are addresses.
                        if (type.endsWith("*")) {
                            return unsafe.getAddress(getAddress());
                        }
                        throw GraalInternalError.shouldNotReachHere(type);
                }
            }

            @Override
            public String toString() {
                return String.format("Field[typeName=%s, fieldName=%s, typeString=%s, isStatic=%b, offset=%d, address=0x%x]", getTypeName(), getFieldName(), getTypeString(), isStatic(), getOffset(),
                                getAddress());
            }
        }
    }

    /**
     * VMTypeEntry (see vmStructs.hpp).
     */
    private long gHotSpotVMTypes;
    private long gHotSpotVMTypeEntryTypeNameOffset;
    private long gHotSpotVMTypeEntrySuperclassNameOffset;
    private long gHotSpotVMTypeEntryIsOopTypeOffset;
    private long gHotSpotVMTypeEntryIsIntegerTypeOffset;
    private long gHotSpotVMTypeEntryIsUnsignedOffset;
    private long gHotSpotVMTypeEntrySizeOffset;
    private long gHotSpotVMTypeEntryArrayStride;

    class VMTypes implements Iterable<VMTypes.Type> {

        private long address;

        public VMTypes(long address) {
            this.address = address;
        }

        public Iterator<VMTypes.Type> iterator() {
            return new Iterator<VMTypes.Type>() {

                private int index = 0;

                private Type current() {
                    return new Type(address + gHotSpotVMTypeEntryArrayStride * index);
                }

                /**
                 * The last entry is identified by a NULL type name.
                 */
                public boolean hasNext() {
                    Type entry = current();
                    return entry.getTypeName() != null;
                }

                public Type next() {
                    Type entry = current();
                    index++;
                    return entry;
                }

                @Override
                public void remove() {
                    throw GraalInternalError.unimplemented();
                }
            };
        }

        class Type {

            private long entryAddress;

            Type(long address) {
                this.entryAddress = address;
            }

            public String getTypeName() {
                long typeNameAddress = unsafe.getAddress(entryAddress + gHotSpotVMTypeEntryTypeNameOffset);
                return readCStringAsString(typeNameAddress);
            }

            public String getSuperclassName() {
                long superclassNameAddress = unsafe.getAddress(entryAddress + gHotSpotVMTypeEntrySuperclassNameOffset);
                return readCStringAsString(superclassNameAddress);
            }

            public boolean isOopType() {
                return unsafe.getInt(entryAddress + gHotSpotVMTypeEntryIsOopTypeOffset) != 0;
            }

            public boolean isIntegerType() {
                return unsafe.getInt(entryAddress + gHotSpotVMTypeEntryIsIntegerTypeOffset) != 0;
            }

            public boolean isUnsigned() {
                return unsafe.getInt(entryAddress + gHotSpotVMTypeEntryIsUnsignedOffset) != 0;
            }

            public long getSize() {
                return unsafe.getLong(entryAddress + gHotSpotVMTypeEntrySizeOffset);
            }

            @Override
            public String toString() {
                return String.format("Type[typeName=%s, superclassName=%s, isOopType=%b, isIntegerType=%b, isUnsigned=%b, size=%d]", getTypeName(), getSuperclassName(), isOopType(), isIntegerType(),
                                isUnsigned(), getSize());
            }
        }
    }

    public abstract class AbstractConstant {

        protected long address;
        protected long nameOffset;
        protected long valueOffset;

        AbstractConstant(long address, long nameOffset, long valueOffset) {
            this.address = address;
            this.nameOffset = nameOffset;
            this.valueOffset = valueOffset;
        }

        public String getName() {
            long nameAddress = unsafe.getAddress(address + nameOffset);
            return readCStringAsString(nameAddress);
        }

        public abstract long getValue();
    }

    /**
     * VMIntConstantEntry (see vmStructs.hpp).
     */
    private long gHotSpotVMIntConstants;
    private long gHotSpotVMIntConstantEntryNameOffset;
    private long gHotSpotVMIntConstantEntryValueOffset;
    private long gHotSpotVMIntConstantEntryArrayStride;

    class VMIntConstants implements Iterable<VMIntConstants.Constant> {

        private long address;

        public VMIntConstants(long address) {
            this.address = address;
        }

        public Iterator<VMIntConstants.Constant> iterator() {
            return new Iterator<VMIntConstants.Constant>() {

                private int index = 0;

                private Constant current() {
                    return new Constant(address + gHotSpotVMIntConstantEntryArrayStride * index);
                }

                /**
                 * The last entry is identified by a NULL name.
                 */
                public boolean hasNext() {
                    Constant entry = current();
                    return entry.getName() != null;
                }

                public Constant next() {
                    Constant entry = current();
                    index++;
                    return entry;
                }

                @Override
                public void remove() {
                    throw GraalInternalError.unimplemented();
                }
            };
        }

        class Constant extends AbstractConstant {

            Constant(long address) {
                super(address, gHotSpotVMIntConstantEntryNameOffset, gHotSpotVMIntConstantEntryValueOffset);
            }

            @Override
            public long getValue() {
                return unsafe.getInt(address + valueOffset);
            }

            @Override
            public String toString() {
                return String.format("IntConstant[name=%s, value=%d (0x%x)]", getName(), getValue(), getValue());
            }
        }
    }

    /**
     * VMLongConstantEntry (see vmStructs.hpp).
     */
    private long gHotSpotVMLongConstants;
    private long gHotSpotVMLongConstantEntryNameOffset;
    private long gHotSpotVMLongConstantEntryValueOffset;
    private long gHotSpotVMLongConstantEntryArrayStride;

    class VMLongConstants implements Iterable<VMLongConstants.Constant> {

        private long address;

        public VMLongConstants(long address) {
            this.address = address;
        }

        public Iterator<VMLongConstants.Constant> iterator() {
            return new Iterator<VMLongConstants.Constant>() {

                private int index = 0;

                private Constant currentEntry() {
                    return new Constant(address + gHotSpotVMLongConstantEntryArrayStride * index);
                }

                /**
                 * The last entry is identified by a NULL name.
                 */
                public boolean hasNext() {
                    Constant entry = currentEntry();
                    return entry.getName() != null;
                }

                public Constant next() {
                    Constant entry = currentEntry();
                    index++;
                    return entry;
                }

                @Override
                public void remove() {
                    throw GraalInternalError.unimplemented();
                }
            };
        }

        class Constant extends AbstractConstant {

            Constant(long address) {
                super(address, gHotSpotVMLongConstantEntryNameOffset, gHotSpotVMLongConstantEntryValueOffset);
            }

            @Override
            public long getValue() {
                return unsafe.getLong(address + valueOffset);
            }

            @Override
            public String toString() {
                return String.format("LongConstant[name=%s, value=%d (0x%x)]", getName(), getValue(), getValue());
            }
        }
    }

    class Flags implements Iterable<Flags.Flag> {

        private long address;
        private long entrySize;
        private long typeOffset;
        private long nameOffset;
        private long addrOffset;

        public Flags(HashMap<String, VMFields.Field> vmStructs, HashMap<String, VMTypes.Type> vmTypes) {
            address = vmStructs.get("Flag::flags").getValue();
            entrySize = vmTypes.get("Flag").getSize();
            typeOffset = vmStructs.get("Flag::_type").getOffset();
            nameOffset = vmStructs.get("Flag::_name").getOffset();
            addrOffset = vmStructs.get("Flag::_addr").getOffset();

            // TODO use the following after we switched to JDK 8
            assert vmTypes.get("bool").getSize() == Byte.SIZE / Byte.SIZE; // TODO Byte.BYTES;
            assert vmTypes.get("intx").getSize() == Long.SIZE / Byte.SIZE; // TODO Long.BYTES;
            assert vmTypes.get("uintx").getSize() == Long.SIZE / Byte.SIZE; // TODO Long.BYTES;
        }

        public Iterator<Flags.Flag> iterator() {
            return new Iterator<Flags.Flag>() {

                private int index = 0;

                private Flag current() {
                    return new Flag(address + entrySize * index);
                }

                /**
                 * The last entry is identified by a NULL name.
                 */
                public boolean hasNext() {
                    Flag entry = current();
                    return entry.getName() != null;
                }

                public Flag next() {
                    Flag entry = current();
                    index++;
                    return entry;
                }

                @Override
                public void remove() {
                    throw GraalInternalError.unimplemented();
                }
            };
        }

        class Flag {

            private long entryAddress;

            Flag(long address) {
                this.entryAddress = address;
            }

            public String getType() {
                long typeAddress = unsafe.getAddress(entryAddress + typeOffset);
                return readCStringAsString(typeAddress);
            }

            public String getName() {
                long nameAddress = unsafe.getAddress(entryAddress + nameOffset);
                return readCStringAsString(nameAddress);
            }

            public long getAddr() {
                return unsafe.getAddress(entryAddress + addrOffset);
            }

            public Object getValue() {
                switch (getType()) {
                    case "bool":
                        return Boolean.valueOf(unsafe.getByte(getAddr()) != 0);
                    case "intx":
                    case "uintx":
                    case "uint64_t":
                        return Long.valueOf(unsafe.getLong(getAddr()));
                    case "double":
                        return Double.valueOf(unsafe.getDouble(getAddr()));
                    case "ccstr":
                    case "ccstrlist":
                        return readCStringAsString(getAddr());
                    default:
                        throw GraalInternalError.shouldNotReachHere(getType());
                }
            }

            @Override
            public String toString() {
                return String.format("Flag[type=%s, name=%s, value=%s]", getType(), getName(), getValue());
            }
        }
    }

    /**
     * Read a null-terminated C string from memory and convert it to a Java String.
     */
    private static String readCStringAsString(long address) {
        if (address == 0) {
            return null;
        }
        StringBuffer sb = new StringBuffer();
        for (int i = 0;; i++) {
            char c = (char) unsafe.getByte(address + i);
            if (c == 0) {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // os information, register layout, code generation, ...
    @HotSpotVMConstant(name = "ASSERT") @Stable public boolean cAssertions;
    public final boolean windowsOs = System.getProperty("os.name", "").startsWith("Windows");

    @HotSpotVMFlag(name = "CodeEntryAlignment") @Stable public int codeEntryAlignment;
    @HotSpotVMFlag(name = "VerifyOops") @Stable public boolean verifyOops;
    @HotSpotVMFlag(name = "CITime") @Stable public boolean ciTime;
    @HotSpotVMFlag(name = "CITimeEach", optional = true) @Stable public boolean ciTimeEach;
    @HotSpotVMFlag(name = "CompileThreshold") @Stable public long compileThreshold;
    @HotSpotVMFlag(name = "CompileTheWorld") @Stable public boolean compileTheWorld;
    @HotSpotVMFlag(name = "CompileTheWorldStartAt") @Stable public int compileTheWorldStartAt;
    @HotSpotVMFlag(name = "CompileTheWorldStopAt") @Stable public int compileTheWorldStopAt;
    @HotSpotVMFlag(name = "PrintCompilation") @Stable public boolean printCompilation;
    @HotSpotVMFlag(name = "PrintInlining") @Stable public boolean printInlining;
    @HotSpotVMFlag(name = "GraalUseFastLocking") @Stable public boolean useFastLocking;

    @HotSpotVMFlag(name = "UseTLAB") @Stable public boolean useTLAB;
    @HotSpotVMFlag(name = "UseBiasedLocking") @Stable public boolean useBiasedLocking;
    @HotSpotVMFlag(name = "UsePopCountInstruction") @Stable public boolean usePopCountInstruction;
    @HotSpotVMFlag(name = "UseAESIntrinsics") @Stable public boolean useAESIntrinsics;
    @HotSpotVMFlag(name = "UseCRC32Intrinsics") @Stable public boolean useCRC32Intrinsics;
    @HotSpotVMFlag(name = "UseG1GC") @Stable public boolean useG1GC;

    @HotSpotVMField(name = "Universe::_collectedHeap", get = HotSpotVMField.Type.VALUE) @Stable private long universeCollectedHeap;
    @HotSpotVMField(name = "CollectedHeap::_total_collections", get = HotSpotVMField.Type.OFFSET) @Stable private int collectedHeapTotalCollectionsOffset;

    public long gcTotalCollectionsAddress() {
        return universeCollectedHeap + collectedHeapTotalCollectionsOffset;
    }

    @HotSpotVMFlag(name = "GraalDeferredInitBarriers") @Stable public boolean useDeferredInitBarriers;
    @HotSpotVMFlag(name = "GraalHProfEnabled") @Stable public boolean useHeapProfiler;

    // Compressed Oops related values.
    @HotSpotVMFlag(name = "UseCompressedOops") @Stable public boolean useCompressedOops;
    @HotSpotVMFlag(name = "UseCompressedClassPointers") @Stable public boolean useCompressedClassPointers;

    @HotSpotVMField(name = "Universe::_narrow_oop._base", get = HotSpotVMField.Type.VALUE) @Stable public long narrowOopBase;
    @HotSpotVMField(name = "Universe::_narrow_oop._shift", get = HotSpotVMField.Type.VALUE) @Stable public int narrowOopShift;
    @HotSpotVMFlag(name = "ObjectAlignmentInBytes") @Stable public int objectAlignment;

    public int logMinObjAlignment() {
        return (int) (Math.log(objectAlignment) / Math.log(2));
    }

    @HotSpotVMField(name = "Universe::_narrow_klass._base", get = HotSpotVMField.Type.VALUE) @Stable public long narrowKlassBase;
    @HotSpotVMField(name = "Universe::_narrow_klass._shift", get = HotSpotVMField.Type.VALUE) @Stable public int narrowKlassShift;
    @HotSpotVMConstant(name = "LogKlassAlignmentInBytes") @Stable public int logKlassAlignment;

    // CPU capabilities
    @HotSpotVMFlag(name = "UseSSE") @Stable public int useSSE;
    @HotSpotVMFlag(name = "UseAVX", optional = true) @Stable public int useAVX;

    // offsets, ...
    @HotSpotVMFlag(name = "StackShadowPages") @Stable public int stackShadowPages;

    @HotSpotVMField(name = "oopDesc::_mark", get = HotSpotVMField.Type.OFFSET) @Stable public int markOffset;
    @HotSpotVMField(name = "oopDesc::_metadata._klass", get = HotSpotVMField.Type.OFFSET) @Stable public int hubOffset;

    @HotSpotVMField(name = "Klass::_prototype_header", get = HotSpotVMField.Type.OFFSET) @Stable public int prototypeMarkWordOffset;
    @HotSpotVMField(name = "Klass::_subklass", get = HotSpotVMField.Type.OFFSET) @Stable public int subklassOffset;
    @HotSpotVMField(name = "Klass::_next_sibling", get = HotSpotVMField.Type.OFFSET) @Stable public int nextSiblingOffset;
    @HotSpotVMField(name = "Klass::_super_check_offset", get = HotSpotVMField.Type.OFFSET) @Stable public int superCheckOffsetOffset;
    @HotSpotVMField(name = "Klass::_secondary_super_cache", get = HotSpotVMField.Type.OFFSET) @Stable public int secondarySuperCacheOffset;
    @HotSpotVMField(name = "Klass::_secondary_supers", get = HotSpotVMField.Type.OFFSET) @Stable public int secondarySupersOffset;

    @HotSpotVMType(name = "vtableEntry", get = HotSpotVMType.Type.SIZE) @Stable public int vtableEntrySize;
    @HotSpotVMField(name = "vtableEntry::_method", get = HotSpotVMField.Type.OFFSET) @Stable public int vtableEntryMethodOffset;
    @Stable public int instanceKlassVtableStartOffset;

    /**
     * The offset of the array length word in an array object's header.
     */
    @Stable public int arrayLengthOffset;

    @HotSpotVMField(name = "Array<Klass*>::_length", get = HotSpotVMField.Type.OFFSET) @Stable public int metaspaceArrayLengthOffset;
    @HotSpotVMField(name = "Array<Klass*>::_data", get = HotSpotVMField.Type.OFFSET) @Stable public int metaspaceArrayBaseOffset;

    @HotSpotVMField(name = "InstanceKlass::_init_state", get = HotSpotVMField.Type.OFFSET) @Stable public int klassStateOffset;
    @HotSpotVMConstant(name = "InstanceKlass::fully_initialized") @Stable public int klassStateFullyInitialized;
    @HotSpotVMField(name = "InstanceKlass::_constants", get = HotSpotVMField.Type.OFFSET) @Stable public int instanceKlassConstantsOffset;

    @HotSpotVMField(name = "ObjArrayKlass::_element_klass", get = HotSpotVMField.Type.OFFSET) @Stable public int arrayClassElementOffset;

    @HotSpotVMField(name = "Thread::_tlab", get = HotSpotVMField.Type.OFFSET) @Stable public int threadTlabOffset;

    @HotSpotVMField(name = "JavaThread::_anchor", get = HotSpotVMField.Type.OFFSET) @Stable public int javaThreadAnchorOffset;
    @HotSpotVMField(name = "JavaThread::_threadObj", get = HotSpotVMField.Type.OFFSET) @Stable public int threadObjectOffset;
    @HotSpotVMField(name = "JavaThread::_osthread", get = HotSpotVMField.Type.OFFSET) @Stable public int osThreadOffset;
    @HotSpotVMField(name = "JavaThread::_dirty_card_queue", get = HotSpotVMField.Type.OFFSET) @Stable public int javaThreadDirtyCardQueueOffset;
    @HotSpotVMField(name = "JavaThread::_is_method_handle_return", get = HotSpotVMField.Type.OFFSET) @Stable public int threadIsMethodHandleReturnOffset;
    @HotSpotVMField(name = "JavaThread::_satb_mark_queue", get = HotSpotVMField.Type.OFFSET) @Stable public int javaThreadSatbMarkQueueOffset;
    @HotSpotVMField(name = "JavaThread::_vm_result", get = HotSpotVMField.Type.OFFSET) @Stable public int threadObjectResultOffset;
    @HotSpotVMField(name = "JavaThread::_graal_counters[0]", get = HotSpotVMField.Type.OFFSET, optional = true) @Stable public int graalCountersThreadOffset;

    @HotSpotVMConstant(name = "GRAAL_COUNTERS_SIZE", optional = true) @Stable public int graalCountersSize;

    @HotSpotVMField(name = "CompilerStatistics::_standard", get = HotSpotVMField.Type.OFFSET) @Stable public long compilerStatisticsStandardOffset;
    @HotSpotVMField(name = "CompilerStatistics::_osr", get = HotSpotVMField.Type.OFFSET) @Stable public long compilerStatisticsOsrOffset;
    @HotSpotVMField(name = "CompilerStatistics::_nmethods_size", get = HotSpotVMField.Type.OFFSET) @Stable public long compilerStatisticsNmethodsSizeOffset;
    @HotSpotVMField(name = "CompilerStatistics::_nmethods_code_size", get = HotSpotVMField.Type.OFFSET) @Stable public long compilerStatisticsNmethodsCodeSizeOffset;
    @HotSpotVMField(name = "CompilerStatistics::Data::_bytes", get = HotSpotVMField.Type.OFFSET) @Stable public long compilerStatisticsDataBytesOffset;
    @HotSpotVMField(name = "CompilerStatistics::Data::_time", get = HotSpotVMField.Type.OFFSET) @Stable public long compilerStatisticsDataTimeOffset;
    @HotSpotVMField(name = "CompilerStatistics::Data::_count", get = HotSpotVMField.Type.OFFSET) @Stable public long compilerStatisticsDataCountOffset;
    @HotSpotVMField(name = "elapsedTimer::_counter", get = HotSpotVMField.Type.OFFSET) @Stable public long elapsedTimerCounterOffset;
    @Stable public long elapsedTimerFrequency;

    /**
     * This field is used to pass exception objects into and out of the runtime system during
     * exception handling for compiled code.
     * <p>
     * <b>NOTE: This is not the same as {@link #pendingExceptionOffset}.</b>
     */
    @HotSpotVMField(name = "JavaThread::_exception_oop", get = HotSpotVMField.Type.OFFSET) @Stable public int threadExceptionOopOffset;
    @HotSpotVMField(name = "JavaThread::_exception_pc", get = HotSpotVMField.Type.OFFSET) @Stable public int threadExceptionPcOffset;

    @HotSpotVMField(name = "JavaFrameAnchor::_last_Java_sp", get = HotSpotVMField.Type.OFFSET) @Stable private int javaFrameAnchorLastJavaSpOffset;
    @HotSpotVMField(name = "JavaFrameAnchor::_last_Java_pc", get = HotSpotVMField.Type.OFFSET) @Stable private int javaFrameAnchorLastJavaPcOffset;
    @HotSpotVMField(name = "JavaFrameAnchor::_last_Java_fp", get = HotSpotVMField.Type.OFFSET, optional = true) @Stable private int javaFrameAnchorLastJavaFpOffset;
    @HotSpotVMField(name = "JavaFrameAnchor::_flags", get = HotSpotVMField.Type.OFFSET, optional = true) @Stable private int javaFrameAnchorFlagsOffset;

    public int threadLastJavaSpOffset() {
        return javaThreadAnchorOffset + javaFrameAnchorLastJavaSpOffset;
    }

    public int threadLastJavaPcOffset() {
        return javaThreadAnchorOffset + javaFrameAnchorLastJavaPcOffset;
    }

    /**
     * This value is only valid on AMD64.
     */
    public int threadLastJavaFpOffset() {
        // TODO add an assert for AMD64
        return javaThreadAnchorOffset + javaFrameAnchorLastJavaFpOffset;
    }

    /**
     * This value is only valid on SPARC.
     */
    public int threadJavaFrameAnchorFlagsOffset() {
        // TODO add an assert for SPARC
        return javaThreadAnchorOffset + javaFrameAnchorFlagsOffset;
    }

    @HotSpotVMField(name = "PtrQueue::_active", get = HotSpotVMField.Type.OFFSET) @Stable public int ptrQueueActiveOffset;
    @HotSpotVMField(name = "PtrQueue::_buf", get = HotSpotVMField.Type.OFFSET) @Stable public int ptrQueueBufferOffset;
    @HotSpotVMField(name = "PtrQueue::_index", get = HotSpotVMField.Type.OFFSET) @Stable public int ptrQueueIndexOffset;

    @HotSpotVMField(name = "OSThread::_interrupted", get = HotSpotVMField.Type.OFFSET) @Stable public int osThreadInterruptedOffset;

    @HotSpotVMConstant(name = "markOopDesc::unlocked_value") @Stable public int unlockedMask;
    @HotSpotVMConstant(name = "markOopDesc::biased_lock_mask_in_place") @Stable public int biasedLockMaskInPlace;
    @HotSpotVMConstant(name = "markOopDesc::age_mask_in_place") @Stable public int ageMaskInPlace;
    @HotSpotVMConstant(name = "markOopDesc::epoch_mask_in_place") @Stable public int epochMaskInPlace;

    @HotSpotVMConstant(name = "markOopDesc::hash_shift") @Stable public long markOopDescHashShift;
    @HotSpotVMConstant(name = "markOopDesc::hash_mask") @Stable public long markOopDescHashMask;
    @HotSpotVMConstant(name = "markOopDesc::hash_mask_in_place") @Stable public long markOopDescHashMaskInPlace;

    @HotSpotVMConstant(name = "markOopDesc::biased_lock_pattern") @Stable public int biasedLockPattern;
    @HotSpotVMConstant(name = "markOopDesc::no_hash_in_place") @Stable public int markWordNoHashInPlace;
    @HotSpotVMConstant(name = "markOopDesc::no_lock_in_place") @Stable public int markWordNoLockInPlace;

    /**
     * See markOopDesc::prototype().
     */
    public long arrayPrototypeMarkWord() {
        return markWordNoHashInPlace | markWordNoLockInPlace;
    }

    /**
     * See markOopDesc::copy_set_hash().
     */
    public long tlabIntArrayMarkWord() {
        long tmp = arrayPrototypeMarkWord() & (~markOopDescHashMaskInPlace);
        tmp |= ((0x2 & markOopDescHashMask) << markOopDescHashShift);
        return tmp;
    }

    /**
     * Offset of the _pending_exception field in ThreadShadow (defined in exceptions.hpp). This
     * field is used to propagate exceptions through C/C++ calls.
     * <p>
     * <b>NOTE: This is not the same as {@link #threadExceptionOopOffset}.</b>
     */
    @HotSpotVMField(name = "ThreadShadow::_pending_exception", get = HotSpotVMField.Type.OFFSET) @Stable public int pendingExceptionOffset;
    @HotSpotVMField(name = "ThreadShadow::_pending_deoptimization", get = HotSpotVMField.Type.OFFSET) @Stable public int pendingDeoptimizationOffset;

    /**
     * Mark word right shift to get identity hash code.
     */
    @HotSpotVMConstant(name = "markOopDesc::hash_shift") @Stable public int identityHashCodeShift;

    /**
     * Identity hash code value when uninitialized.
     */
    @HotSpotVMConstant(name = "markOopDesc::no_hash") @Stable public int uninitializedIdentityHashCodeValue;

    /**
     * Used for marking a Method object as queued for compilation.
     */
    @HotSpotVMConstant(name = "JVM_ACC_QUEUED") @Stable public int methodQueuedForCompilationBit;

    @HotSpotVMField(name = "Method::_access_flags", get = HotSpotVMField.Type.OFFSET) @Stable public int methodAccessFlagsOffset;
    @HotSpotVMField(name = "Method::_constMethod", get = HotSpotVMField.Type.OFFSET) @Stable public int methodConstMethodOffset;
    @HotSpotVMField(name = "Method::_intrinsic_id", get = HotSpotVMField.Type.OFFSET) @Stable public int methodIntrinsicIdOffset;
    @HotSpotVMField(name = "Method::_vtable_index", get = HotSpotVMField.Type.OFFSET) @Stable public int methodVtableIndexOffset;

    /**
     * Value of Method::extra_stack_entries().
     */
    @Stable public int extraStackEntries;

    @HotSpotVMField(name = "ConstMethod::_max_stack", get = HotSpotVMField.Type.OFFSET) @Stable public int constMethodMaxStackOffset;
    @HotSpotVMField(name = "ConstMethod::_max_locals", get = HotSpotVMField.Type.OFFSET) @Stable public int methodMaxLocalsOffset;
    @HotSpotVMField(name = "ConstMethod::_constants", get = HotSpotVMField.Type.OFFSET) @Stable public int constMethodConstantsOffset;

    @HotSpotVMField(name = "ConstantPool::_pool_holder", get = HotSpotVMField.Type.OFFSET) @Stable public int constantPoolHolderOffset;
    @HotSpotVMField(name = "ConstantPool::_length", get = HotSpotVMField.Type.OFFSET) @Stable public int constantPoolLengthOffset;

    @HotSpotVMConstant(name = "JVM_ACC_HAS_FINALIZER") @Stable public int klassHasFinalizerFlag;

    /**
     * Bit pattern that represents a non-oop. Neither the high bits nor the low bits of this value
     * are allowed to look like (respectively) the high or low bits of a real oop.
     */
    @HotSpotVMField(name = "Universe::_non_oop_bits", get = HotSpotVMField.Type.VALUE) @Stable public long nonOopBits;

    @HotSpotVMField(name = "StubRoutines::_verify_oop_count", get = HotSpotVMField.Type.ADDRESS) @Stable public long verifyOopCounterAddress;
    @Stable public long verifyOopMask;
    @Stable public long verifyOopBits;

    @HotSpotVMField(name = "CollectedHeap::_barrier_set", get = HotSpotVMField.Type.OFFSET) @Stable public int collectedHeapBarrierSetOffset;

    @HotSpotVMField(name = "HeapRegion::LogOfHRGrainBytes", get = HotSpotVMField.Type.VALUE) @Stable public int logOfHRGrainBytes;

    @HotSpotVMField(name = "BarrierSet::_kind", get = HotSpotVMField.Type.OFFSET) @Stable public int barrierSetKindOffset;
    @HotSpotVMConstant(name = "BarrierSet::CardTableModRef") @Stable public int barrierSetCardTableModRef;
    @HotSpotVMConstant(name = "BarrierSet::CardTableExtension") @Stable public int barrierSetCardTableExtension;
    @HotSpotVMConstant(name = "BarrierSet::G1SATBCT") @Stable public int barrierSetG1SATBCT;
    @HotSpotVMConstant(name = "BarrierSet::G1SATBCTLogging") @Stable public int barrierSetG1SATBCTLogging;
    @HotSpotVMConstant(name = "BarrierSet::ModRef") @Stable public int barrierSetModRef;
    @HotSpotVMConstant(name = "BarrierSet::Other") @Stable public int barrierSetOther;

    @HotSpotVMField(name = "CardTableModRefBS::byte_map_base", get = HotSpotVMField.Type.OFFSET) @Stable public int cardTableModRefBSByteMapBaseOffset;
    @HotSpotVMConstant(name = "CardTableModRefBS::card_shift") @Stable public int cardTableModRefBSCardShift;

    public long cardtableStartAddress() {
        final long barrierSetAddress = unsafe.getAddress(universeCollectedHeap + collectedHeapBarrierSetOffset);
        final int kind = unsafe.getInt(barrierSetAddress + barrierSetKindOffset);
        if ((kind == barrierSetCardTableModRef) || (kind == barrierSetCardTableExtension) || (kind == barrierSetG1SATBCT) || (kind == barrierSetG1SATBCTLogging)) {
            final long base = unsafe.getAddress(barrierSetAddress + cardTableModRefBSByteMapBaseOffset);
            assert base != 0 : "unexpected byte_map_base: " + base;
            return base;
        }
        if ((kind == barrierSetModRef) || (kind == barrierSetOther)) {
            // No post barriers
            return 0;
        }
        throw GraalInternalError.shouldNotReachHere("kind: " + kind);
    }

    public int cardtableShift() {
        final long barrierSetAddress = unsafe.getAddress(universeCollectedHeap + collectedHeapBarrierSetOffset);
        final int kind = unsafe.getInt(barrierSetAddress + barrierSetKindOffset);
        if ((kind == barrierSetCardTableModRef) || (kind == barrierSetCardTableExtension) || (kind == barrierSetG1SATBCT) || (kind == barrierSetG1SATBCTLogging)) {
            return cardTableModRefBSCardShift;
        }
        if ((kind == barrierSetModRef) || (kind == barrierSetOther)) {
            // No post barriers
            return 0;
        }
        throw GraalInternalError.shouldNotReachHere("kind: " + kind);
    }

    @HotSpotVMField(name = "os::_polling_page", get = HotSpotVMField.Type.VALUE) @Stable public long safepointPollingAddress;

    // G1 Collector Related Values.

    public int g1CardQueueIndexOffset() {
        return javaThreadDirtyCardQueueOffset + ptrQueueIndexOffset;
    }

    public int g1CardQueueBufferOffset() {
        return javaThreadDirtyCardQueueOffset + ptrQueueBufferOffset;
    }

    public int g1SATBQueueMarkingOffset() {
        return javaThreadSatbMarkQueueOffset + ptrQueueActiveOffset;
    }

    public int g1SATBQueueIndexOffset() {
        return javaThreadSatbMarkQueueOffset + ptrQueueIndexOffset;
    }

    public int g1SATBQueueBufferOffset() {
        return javaThreadSatbMarkQueueOffset + ptrQueueBufferOffset;
    }

    /**
     * The offset of the _java_mirror field (of type {@link Class}) in a Klass.
     */
    @HotSpotVMField(name = "Klass::_java_mirror", get = HotSpotVMField.Type.OFFSET) @Stable public int classMirrorOffset;

    @HotSpotVMConstant(name = "frame::arg_reg_save_area_bytes", optional = true) @Stable public int runtimeCallStackSize;

    @HotSpotVMField(name = "Klass::_super", get = HotSpotVMField.Type.OFFSET) @Stable public int klassSuperKlassOffset;
    @HotSpotVMField(name = "Klass::_modifier_flags", get = HotSpotVMField.Type.OFFSET) @Stable public int klassModifierFlagsOffset;
    @HotSpotVMField(name = "Klass::_access_flags", get = HotSpotVMField.Type.OFFSET) @Stable public int klassAccessFlagsOffset;
    @HotSpotVMField(name = "Klass::_layout_helper", get = HotSpotVMField.Type.OFFSET) @Stable public int klassLayoutHelperOffset;
    @HotSpotVMField(name = "Klass::_layout_helper", get = HotSpotVMField.Type.OFFSET) @Stable public int klassInstanceSizeOffset;

    /**
     * Bit pattern in the klass layout helper that can be used to identify arrays.
     */
    public final int arrayKlassLayoutHelperIdentifier = 0x80000000;

    @HotSpotVMField(name = "ArrayKlass::_component_mirror", get = HotSpotVMField.Type.OFFSET) @Stable public int arrayKlassComponentMirrorOffset;

    @HotSpotVMField(name = "java_lang_Class::_klass_offset", get = HotSpotVMField.Type.VALUE) @Stable public int klassOffset;
    @HotSpotVMField(name = "java_lang_Class::_array_klass_offset", get = HotSpotVMField.Type.VALUE) @Stable public int arrayKlassOffset;
    @HotSpotVMField(name = "java_lang_Class::_graal_mirror_offset", get = HotSpotVMField.Type.VALUE) @Stable public int graalMirrorInClassOffset;

    @HotSpotVMField(name = "Method::_method_data", get = HotSpotVMField.Type.OFFSET) @Stable public int methodDataOffset;
    @HotSpotVMField(name = "Method::_from_compiled_entry", get = HotSpotVMField.Type.OFFSET) @Stable public int methodCompiledEntryOffset;

    @HotSpotVMField(name = "nmethod::_verified_entry_point", get = HotSpotVMField.Type.OFFSET) @Stable public int nmethodEntryOffset;

    @HotSpotVMType(name = "BasicLock", get = HotSpotVMType.Type.SIZE) @Stable public int basicLockSize;
    @HotSpotVMField(name = "BasicLock::_displaced_header", get = HotSpotVMField.Type.OFFSET) @Stable public int basicLockDisplacedHeaderOffset;

    @Stable public long heapEndAddress;
    @Stable public long heapTopAddress;

    @HotSpotVMField(name = "Thread::_allocated_bytes", get = HotSpotVMField.Type.OFFSET) @Stable public int threadAllocatedBytesOffset;

    @HotSpotVMFlag(name = "TLABWasteIncrement") @Stable public int tlabRefillWasteIncrement;
    @Stable public int tlabAlignmentReserve;

    @HotSpotVMField(name = "ThreadLocalAllocBuffer::_start", get = HotSpotVMField.Type.OFFSET) @Stable public int threadLocalAllocBufferStartOffset;
    @HotSpotVMField(name = "ThreadLocalAllocBuffer::_end", get = HotSpotVMField.Type.OFFSET) @Stable public int threadLocalAllocBufferEndOffset;
    @HotSpotVMField(name = "ThreadLocalAllocBuffer::_top", get = HotSpotVMField.Type.OFFSET) @Stable public int threadLocalAllocBufferTopOffset;
    @HotSpotVMField(name = "ThreadLocalAllocBuffer::_slow_allocations", get = HotSpotVMField.Type.OFFSET) @Stable public int threadLocalAllocBufferSlowAllocationsOffset;
    @HotSpotVMField(name = "ThreadLocalAllocBuffer::_fast_refill_waste", get = HotSpotVMField.Type.OFFSET) @Stable public int threadLocalAllocBufferFastRefillWasteOffset;
    @HotSpotVMField(name = "ThreadLocalAllocBuffer::_number_of_refills", get = HotSpotVMField.Type.OFFSET) @Stable public int threadLocalAllocBufferNumberOfRefillsOffset;
    @HotSpotVMField(name = "ThreadLocalAllocBuffer::_refill_waste_limit", get = HotSpotVMField.Type.OFFSET) @Stable public int threadLocalAllocBufferRefillWasteLimitOffset;
    @HotSpotVMField(name = "ThreadLocalAllocBuffer::_desired_size", get = HotSpotVMField.Type.OFFSET) @Stable public int threadLocalAllocBufferDesiredSizeOffset;

    public int tlabSlowAllocationsOffset() {
        return threadTlabOffset + threadLocalAllocBufferSlowAllocationsOffset;
    }

    public int tlabFastRefillWasteOffset() {
        return threadTlabOffset + threadLocalAllocBufferFastRefillWasteOffset;
    }

    public int tlabNumberOfRefillsOffset() {
        return threadTlabOffset + threadLocalAllocBufferNumberOfRefillsOffset;
    }

    public int tlabRefillWasteLimitOffset() {
        return threadTlabOffset + threadLocalAllocBufferRefillWasteLimitOffset;
    }

    public int threadTlabSizeOffset() {
        return threadTlabOffset + threadLocalAllocBufferDesiredSizeOffset;
    }

    public int threadTlabStartOffset() {
        return threadTlabOffset + threadLocalAllocBufferStartOffset;
    }

    public int threadTlabEndOffset() {
        return threadTlabOffset + threadLocalAllocBufferEndOffset;
    }

    public int threadTlabTopOffset() {
        return threadTlabOffset + threadLocalAllocBufferTopOffset;
    }

    @HotSpotVMFlag(name = "TLABStats") @Stable public boolean tlabStats;
    @Stable public boolean inlineContiguousAllocationSupported;

    @HotSpotVMField(name = "Klass::_layout_helper", get = HotSpotVMField.Type.OFFSET) @Stable public int layoutHelperOffset;
    @HotSpotVMConstant(name = "Klass::_lh_log2_element_size_shift") @Stable public int layoutHelperLog2ElementSizeShift;
    @HotSpotVMConstant(name = "Klass::_lh_log2_element_size_mask") @Stable public int layoutHelperLog2ElementSizeMask;
    @HotSpotVMConstant(name = "Klass::_lh_element_type_shift") @Stable public int layoutHelperElementTypeShift;
    @HotSpotVMConstant(name = "Klass::_lh_element_type_mask") @Stable public int layoutHelperElementTypeMask;
    @HotSpotVMConstant(name = "Klass::_lh_header_size_shift") @Stable public int layoutHelperHeaderSizeShift;
    @HotSpotVMConstant(name = "Klass::_lh_header_size_mask") @Stable public int layoutHelperHeaderSizeMask;
    @HotSpotVMConstant(name = "Klass::_lh_array_tag_shift") @Stable public int layoutHelperArrayTagShift;
    @HotSpotVMConstant(name = "Klass::_lh_array_tag_type_value") @Stable public int layoutHelperArrayTagTypeValue;
    @HotSpotVMConstant(name = "Klass::_lh_array_tag_obj_value") @Stable public int layoutHelperArrayTagObjectValue;

    /**
     * This filters out the bit that differentiates a type array from an object array.
     */
    public int layoutHelperElementTypePrimitiveInPlace() {
        return (layoutHelperArrayTagTypeValue & ~layoutHelperArrayTagObjectValue) << layoutHelperArrayTagShift;
    }

    @HotSpotVMField(name = "MethodData::_data[0]", get = HotSpotVMField.Type.OFFSET) @Stable public int methodDataOopDataOffset;
    @HotSpotVMField(name = "MethodData::_trap_hist._array[0]", get = HotSpotVMField.Type.OFFSET) @Stable public int methodDataOopTrapHistoryOffset;

    /**
     * The DataLayout header size is the same as the cell size.
     */
    @HotSpotVMConstant(name = "DataLayout::cell_size") @Stable public int dataLayoutHeaderSize;
    @HotSpotVMField(name = "DataLayout::_header._struct._tag", get = HotSpotVMField.Type.OFFSET) @Stable public int dataLayoutTagOffset;
    @HotSpotVMField(name = "DataLayout::_header._struct._flags", get = HotSpotVMField.Type.OFFSET) @Stable public int dataLayoutFlagsOffset;
    @HotSpotVMField(name = "DataLayout::_header._struct._bci", get = HotSpotVMField.Type.OFFSET) @Stable public int dataLayoutBCIOffset;
    @HotSpotVMField(name = "DataLayout::_cells[0]", get = HotSpotVMField.Type.OFFSET) @Stable public int dataLayoutCellsOffset;
    @HotSpotVMConstant(name = "DataLayout::cell_size") @Stable public int dataLayoutCellSize;

    @HotSpotVMFlag(name = "BciProfileWidth") @Stable public int bciProfileWidth;
    @HotSpotVMFlag(name = "TypeProfileWidth") @Stable public int typeProfileWidth;
    @HotSpotVMFlag(name = "MethodProfileWidth") @Stable public int methodProfileWidth;

    @HotSpotVMField(name = "CodeBlob::_code_offset", get = HotSpotVMField.Type.OFFSET) @Stable private int codeBlobCodeOffsetOffset;
    @HotSpotVMField(name = "SharedRuntime::_ic_miss_blob", get = HotSpotVMField.Type.VALUE) @Stable private long inlineCacheMissBlob;

    public long inlineCacheMissStub() {
        return inlineCacheMissBlob + unsafe.getInt(inlineCacheMissBlob + codeBlobCodeOffsetOffset);
    }

    @HotSpotVMField(name = "CodeCache::_heap", get = HotSpotVMField.Type.VALUE) @Stable private long codeCacheHeap;
    @HotSpotVMField(name = "CodeHeap::_memory", get = HotSpotVMField.Type.OFFSET) @Stable private int codeHeapMemoryOffset;
    @HotSpotVMField(name = "VirtualSpace::_low_boundary", get = HotSpotVMField.Type.OFFSET) @Stable private int virtualSpaceLowBoundaryOffset;
    @HotSpotVMField(name = "VirtualSpace::_high_boundary", get = HotSpotVMField.Type.OFFSET) @Stable private int virtualSpaceHighBoundaryOffset;

    /**
     * @return CodeCache::_heap->_memory._low_boundary
     */
    public long codeCacheLowBoundary() {
        return unsafe.getAddress(codeCacheHeap + codeHeapMemoryOffset + virtualSpaceLowBoundaryOffset);
    }

    /**
     * @return CodeCache::_heap->_memory._high_boundary
     */
    public long codeCacheHighBoundary() {
        return unsafe.getAddress(codeCacheHeap + codeHeapMemoryOffset + virtualSpaceHighBoundaryOffset);
    }

    @Stable public long handleDeoptStub;
    @Stable public long uncommonTrapStub;

    @HotSpotVMField(name = "StubRoutines::_aescrypt_encryptBlock", get = HotSpotVMField.Type.VALUE) @Stable public long aescryptEncryptBlockStub;
    @HotSpotVMField(name = "StubRoutines::_aescrypt_decryptBlock", get = HotSpotVMField.Type.VALUE) @Stable public long aescryptDecryptBlockStub;
    @HotSpotVMField(name = "StubRoutines::_cipherBlockChaining_encryptAESCrypt", get = HotSpotVMField.Type.VALUE) @Stable public long cipherBlockChainingEncryptAESCryptStub;
    @HotSpotVMField(name = "StubRoutines::_cipherBlockChaining_decryptAESCrypt", get = HotSpotVMField.Type.VALUE) @Stable public long cipherBlockChainingDecryptAESCryptStub;
    @HotSpotVMField(name = "StubRoutines::_updateBytesCRC32", get = HotSpotVMField.Type.VALUE) @Stable public long updateBytesCRC32Stub;
    @HotSpotVMField(name = "StubRoutines::_crc_table_adr", get = HotSpotVMField.Type.VALUE) @Stable public long crcTableAddress;

    @Stable public long newInstanceAddress;
    @Stable public long newArrayAddress;
    @Stable public long newMultiArrayAddress;
    @Stable public long dynamicNewArrayAddress;
    @Stable public long registerFinalizerAddress;
    @Stable public long threadIsInterruptedAddress;
    @Stable public long vmMessageAddress;
    @Stable public long identityHashCodeAddress;
    @Stable public long exceptionHandlerForPcAddress;
    @Stable public long exceptionHandlerForReturnAddressAddress;
    @Stable public long osrMigrationEndAddress;
    @Stable public long monitorenterAddress;
    @Stable public long monitorexitAddress;
    @Stable public long createNullPointerExceptionAddress;
    @Stable public long createOutOfBoundsExceptionAddress;
    @Stable public long logPrimitiveAddress;
    @Stable public long logObjectAddress;
    @Stable public long logPrintfAddress;
    @Stable public long vmErrorAddress;
    @Stable public long writeBarrierPreAddress;
    @Stable public long writeBarrierPostAddress;
    @Stable public long validateObject;
    @Stable public long javaTimeMillisAddress;
    @Stable public long javaTimeNanosAddress;
    @Stable public long arithmeticSinAddress;
    @Stable public long arithmeticCosAddress;
    @Stable public long arithmeticTanAddress;
    @Stable public long loadAndClearExceptionAddress;

    @HotSpotVMConstant(name = "Deoptimization::Reason_none") @Stable public int deoptReasonNone;
    @HotSpotVMConstant(name = "Deoptimization::Reason_null_check") @Stable public int deoptReasonNullCheck;
    @HotSpotVMConstant(name = "Deoptimization::Reason_range_check") @Stable public int deoptReasonRangeCheck;
    @HotSpotVMConstant(name = "Deoptimization::Reason_class_check") @Stable public int deoptReasonClassCheck;
    @HotSpotVMConstant(name = "Deoptimization::Reason_array_check") @Stable public int deoptReasonArrayCheck;
    @HotSpotVMConstant(name = "Deoptimization::Reason_null_assert") @Stable public int deoptReasonUnreached0;
    @HotSpotVMConstant(name = "Deoptimization::Reason_intrinsic") @Stable public int deoptReasonTypeCheckInlining;
    @HotSpotVMConstant(name = "Deoptimization::Reason_bimorphic") @Stable public int deoptReasonOptimizedTypeCheck;
    @HotSpotVMConstant(name = "Deoptimization::Reason_unhandled") @Stable public int deoptReasonNotCompiledExceptionHandler;
    @HotSpotVMConstant(name = "Deoptimization::Reason_uninitialized") @Stable public int deoptReasonUnresolved;
    @HotSpotVMConstant(name = "Deoptimization::Reason_age") @Stable public int deoptReasonJsrMismatch;
    @HotSpotVMConstant(name = "Deoptimization::Reason_div0_check") @Stable public int deoptReasonDiv0Check;
    @HotSpotVMConstant(name = "Deoptimization::Reason_constraint") @Stable public int deoptReasonConstraint;
    @HotSpotVMConstant(name = "Deoptimization::Reason_loop_limit_check") @Stable public int deoptReasonLoopLimitCheck;

    @HotSpotVMConstant(name = "Deoptimization::Action_none") @Stable public int deoptActionNone;
    @HotSpotVMConstant(name = "Deoptimization::Action_maybe_recompile") @Stable public int deoptActionMaybeRecompile;
    @HotSpotVMConstant(name = "Deoptimization::Action_reinterpret") @Stable public int deoptActionReinterpret;
    @HotSpotVMConstant(name = "Deoptimization::Action_make_not_entrant") @Stable public int deoptActionMakeNotEntrant;
    @HotSpotVMConstant(name = "Deoptimization::Action_make_not_compilable") @Stable public int deoptActionMakeNotCompilable;

    @HotSpotVMConstant(name = "vmIntrinsics::_invokeBasic") @Stable public int vmIntrinsicInvokeBasic;
    @HotSpotVMConstant(name = "vmIntrinsics::_linkToVirtual") @Stable public int vmIntrinsicLinkToVirtual;
    @HotSpotVMConstant(name = "vmIntrinsics::_linkToStatic") @Stable public int vmIntrinsicLinkToStatic;
    @HotSpotVMConstant(name = "vmIntrinsics::_linkToSpecial") @Stable public int vmIntrinsicLinkToSpecial;
    @HotSpotVMConstant(name = "vmIntrinsics::_linkToInterface") @Stable public int vmIntrinsicLinkToInterface;

    public boolean check() {
        for (Field f : getClass().getDeclaredFields()) {
            int modifiers = f.getModifiers();
            if (Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers)) {
                assert Modifier.isFinal(modifiers) || f.getAnnotation(Stable.class) != null : "field should either be final or @Stable: " + f;
            }
        }

        assert codeEntryAlignment > 0 : codeEntryAlignment;
        assert stackShadowPages > 0 : stackShadowPages;

        assert (layoutHelperArrayTagObjectValue & layoutHelperArrayTagTypeValue & arrayKlassLayoutHelperIdentifier) != 0 : "object array and type array must have first bit set";

        return true;
    }
}
