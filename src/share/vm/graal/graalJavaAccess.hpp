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

#ifndef SHARE_VM_GRAAL_GRAAL_JAVA_ACCESS_HPP
#define SHARE_VM_GRAAL_GRAAL_JAVA_ACCESS_HPP

void graal_compute_offsets();

#include "classfile/systemDictionary.hpp"
#include "oops/instanceMirrorKlass.hpp"

/* This macro defines the structure of the InstalledCode - classes.
 * It will generate classes with accessors similar to javaClasses.hpp, but with specializations for oops, Handles and jni handles.
 *
 * The public interface of these classes will look like this:

 * class StackSlot : AllStatic {
 * public:
 *   static klassOop klass();
 *   static jint  index(oop obj);
 *   static jint  index(Handle obj);
 *   static jint  index(jobject obj);
 *   static void set_index(oop obj, jint x);
 *   static void set_index(Handle obj, jint x);
 *   static void set_index(jobject obj, jint x);
 * };
 *
 */

#define COMPILER_CLASSES_DO(start_class, end_class, char_field, int_field, boolean_field, long_field, float_field, oop_field, static_oop_field)   \
  start_class(HotSpotResolvedJavaType)                                                      \
    oop_field(HotSpotResolvedJavaType, javaMirror, "Ljava/lang/Class;")                     \
    oop_field(HotSpotResolvedJavaType, simpleName, "Ljava/lang/String;")                    \
    int_field(HotSpotResolvedJavaType, accessFlags)                                         \
    long_field(HotSpotResolvedJavaType, initialMarkWord)                                    \
    boolean_field(HotSpotResolvedJavaType, hasFinalizer)                                    \
    boolean_field(HotSpotResolvedJavaType, hasFinalizableSubclass)                          \
    int_field(HotSpotResolvedJavaType, superCheckOffset)                                    \
    boolean_field(HotSpotResolvedJavaType, isArrayClass)                                    \
    boolean_field(HotSpotResolvedJavaType, isInstanceClass)                                 \
    boolean_field(HotSpotResolvedJavaType, isInterface)                                     \
    int_field(HotSpotResolvedJavaType, instanceSize)                                        \
  end_class                                                                                 \
  start_class(HotSpotKlassOop)                                                              \
    oop_field(HotSpotKlassOop, type, "Lcom/oracle/graal/api/meta/ResolvedJavaType;")        \
    end_class                                                                               \
  start_class(HotSpotResolvedJavaMethod)                                                    \
    oop_field(HotSpotResolvedJavaMethod, name, "Ljava/lang/String;")                        \
    oop_field(HotSpotResolvedJavaMethod, holder, "Lcom/oracle/graal/api/meta/ResolvedJavaType;")  \
    oop_field(HotSpotResolvedJavaMethod, javaMirror, "Ljava/lang/Object;")                  \
    int_field(HotSpotResolvedJavaMethod, codeSize)                                          \
    int_field(HotSpotResolvedJavaMethod, accessFlags)                                       \
    int_field(HotSpotResolvedJavaMethod, maxLocals)                                         \
    int_field(HotSpotResolvedJavaMethod, maxStackSize)                                      \
    boolean_field(HotSpotResolvedJavaMethod, canBeInlined)                                  \
  end_class                                                                             \
  start_class(HotSpotMethodData)                                                        \
    oop_field(HotSpotMethodData, hotspotMirror, "Ljava/lang/Object;")                   \
    int_field(HotSpotMethodData, normalDataSize)                                        \
    int_field(HotSpotMethodData, extraDataSize)                                         \
  end_class                                                                             \
  start_class(HotSpotJavaType)                                                              \
    oop_field(HotSpotJavaType, name, "Ljava/lang/String;")                                  \
  end_class                                                                             \
  start_class(HotSpotResolvedJavaField)                                                             \
    oop_field(HotSpotResolvedJavaField, constant, "Lcom/oracle/graal/api/meta/Constant;")             \
    int_field(HotSpotResolvedJavaField, offset)                                                     \
    int_field(HotSpotResolvedJavaField, accessFlags)                                                \
  end_class                                                                             \
  start_class(HotSpotCompiledMethod)                                                    \
    long_field(HotSpotCompiledMethod, nmethod)                                          \
    oop_field(HotSpotCompiledMethod, method, "Lcom/oracle/graal/api/meta/ResolvedJavaMethod;")\
  end_class                                                                             \
  start_class(HotSpotCodeInfo)                                                          \
    long_field(HotSpotCodeInfo, start)                                                  \
    oop_field(HotSpotCodeInfo, code, "[B")                                              \
  end_class                                                                             \
  start_class(HotSpotProxy)                                                             \
    static_oop_field(HotSpotProxy, DUMMY_CONSTANT_OBJ, "Ljava/lang/Long;")              \
  end_class                                                                             \
  start_class(HotSpotTargetMethod)                                                      \
    oop_field(HotSpotTargetMethod, targetMethod, "Lcom/oracle/graal/api/code/CompilationResult;") \
    oop_field(HotSpotTargetMethod, method, "Lcom/oracle/graal/hotspot/meta/HotSpotResolvedJavaMethod;") \
    oop_field(HotSpotTargetMethod, name, "Ljava/lang/String;")                          \
    oop_field(HotSpotTargetMethod, sites, "[Lcom/oracle/graal/api/code/CompilationResult$Site;") \
    oop_field(HotSpotTargetMethod, exceptionHandlers, "[Lcom/oracle/graal/api/code/CompilationResult$ExceptionHandler;") \
  end_class                                                                             \
  start_class(ExceptionHandler)                                                  \
    int_field(ExceptionHandler, startBCI)                                        \
    int_field(ExceptionHandler, endBCI)                                          \
    int_field(ExceptionHandler, handlerBCI)                                      \
    int_field(ExceptionHandler, catchTypeCPI)                                 \
    oop_field(ExceptionHandler, catchType, "Lcom/oracle/graal/api/meta/JavaType;")    \
  end_class                                                                             \
  start_class(InstalledCode)                                                           \
    int_field(InstalledCode, frameSize)                                                \
    int_field(InstalledCode, customStackAreaOffset)                                    \
    oop_field(InstalledCode, targetCode, "[B")                                         \
    oop_field(InstalledCode, assumptions, "Lcom/oracle/graal/api/code/Assumptions;")     \
    int_field(InstalledCode, targetCodeSize)                                           \
  end_class                                                                             \
  start_class(Assumptions)                                                            \
    oop_field(Assumptions, list, "[Lcom/oracle/graal/api/code/Assumptions$Assumption;") \
  end_class                                                                             \
  start_class(Assumptions_MethodContents)                                             \
    oop_field(Assumptions_MethodContents, method, "Lcom/oracle/graal/api/meta/ResolvedJavaMethod;") \
  end_class                                                                             \
  start_class(Assumptions_ConcreteSubtype)                                            \
    oop_field(Assumptions_ConcreteSubtype, context, "Lcom/oracle/graal/api/meta/ResolvedJavaType;") \
    oop_field(Assumptions_ConcreteSubtype, subtype, "Lcom/oracle/graal/api/meta/ResolvedJavaType;") \
  end_class                                                                             \
  start_class(Assumptions_ConcreteMethod)                                             \
    oop_field(Assumptions_ConcreteMethod, method, "Lcom/oracle/graal/api/meta/ResolvedJavaMethod;") \
    oop_field(Assumptions_ConcreteMethod, context, "Lcom/oracle/graal/api/meta/ResolvedJavaType;") \
    oop_field(Assumptions_ConcreteMethod, impl, "Lcom/oracle/graal/api/meta/ResolvedJavaMethod;") \
  end_class                                                                             \
  start_class(InstalledCode_Site)                                                      \
    int_field(InstalledCode_Site, pcOffset)                                            \
  end_class                                                                             \
  start_class(InstalledCode_Call)                                                      \
    oop_field(InstalledCode_Call, target, "Ljava/lang/Object;")                        \
    oop_field(InstalledCode_Call, debugInfo, "Lcom/oracle/graal/api/code/DebugInfo;")    \
  end_class                                                                             \
  start_class(InstalledCode_DataPatch)                                                 \
    oop_field(InstalledCode_DataPatch, constant, "Lcom/oracle/graal/api/meta/Constant;") \
    int_field(InstalledCode_DataPatch, alignment)                                      \
  end_class                                                                             \
  start_class(InstalledCode_Safepoint)                                                 \
    oop_field(InstalledCode_Safepoint, debugInfo, "Lcom/oracle/graal/api/code/DebugInfo;") \
  end_class                                                                             \
  start_class(InstalledCode_ExceptionHandler)                                          \
    int_field(InstalledCode_ExceptionHandler, handlerPos)                              \
  end_class                                                                             \
  start_class(InstalledCode_Mark)                                                      \
    oop_field(InstalledCode_Mark, id, "Ljava/lang/Object;")                            \
    oop_field(InstalledCode_Mark, references, "[Lcom/oracle/graal/api/code/CompilationResult$Mark;") \
  end_class                                                                             \
  start_class(DebugInfo)                                                              \
    oop_field(DebugInfo, bytecodePosition, "Lcom/oracle/graal/api/code/BytecodePosition;")                \
    oop_field(DebugInfo, registerRefMap, "Ljava/util/BitSet;")          \
    oop_field(DebugInfo, frameRefMap, "Ljava/util/BitSet;")             \
  end_class                                                                             \
  start_class(GraalBitMap)                                                              \
    oop_field(GraalBitMap, words, "[J")                                                 \
  end_class                                                                             \
  start_class(BytecodeFrame)                                                                  \
    oop_field(BytecodeFrame, values, "[Lcom/oracle/graal/api/meta/Value;")                      \
    int_field(BytecodeFrame, numLocals)                                                       \
    int_field(BytecodeFrame, numStack)                                                        \
    int_field(BytecodeFrame, numLocks)                                                        \
    long_field(BytecodeFrame, leafGraphId)                                                    \
    boolean_field(BytecodeFrame, rethrowException)                                            \
    boolean_field(BytecodeFrame, duringCall)                                                  \
  end_class                                                                             \
  start_class(BytecodePosition)                                                                \
    oop_field(BytecodePosition, caller, "Lcom/oracle/graal/api/code/BytecodePosition;")                   \
    oop_field(BytecodePosition, method, "Lcom/oracle/graal/api/meta/ResolvedJavaMethod;")            \
    int_field(BytecodePosition, bci)                                                           \
  end_class                                                                             \
  start_class(Constant)                                                               \
    oop_field(Constant, kind, "Lcom/oracle/graal/api/meta/Kind;")                       \
    oop_field(Constant, object, "Ljava/lang/Object;")                                 \
    long_field(Constant, primitive)                                                   \
  end_class                                                                             \
  start_class(Kind)                                                                   \
    char_field(Kind, typeChar)                                                        \
    static_oop_field(Kind, Boolean, "Lcom/oracle/graal/api/meta/Kind;");                \
    static_oop_field(Kind, Byte, "Lcom/oracle/graal/api/meta/Kind;");                   \
    static_oop_field(Kind, Char, "Lcom/oracle/graal/api/meta/Kind;");                   \
    static_oop_field(Kind, Short, "Lcom/oracle/graal/api/meta/Kind;");                  \
    static_oop_field(Kind, Int, "Lcom/oracle/graal/api/meta/Kind;");                    \
    static_oop_field(Kind, Long, "Lcom/oracle/graal/api/meta/Kind;");                   \
  end_class                                                                             \
  start_class(RuntimeCall)                                                            \
    static_oop_field(RuntimeCall, UnwindException, "Lcom/oracle/graal/api/code/RuntimeCall;"); \
    static_oop_field(RuntimeCall, RegisterFinalizer, "Lcom/oracle/graal/api/code/RuntimeCall;"); \
    static_oop_field(RuntimeCall, SetDeoptInfo, "Lcom/oracle/graal/api/code/RuntimeCall;");    \
    static_oop_field(RuntimeCall, CreateNullPointerException, "Lcom/oracle/graal/api/code/RuntimeCall;"); \
    static_oop_field(RuntimeCall, CreateOutOfBoundsException, "Lcom/oracle/graal/api/code/RuntimeCall;"); \
    static_oop_field(RuntimeCall, JavaTimeMillis, "Lcom/oracle/graal/api/code/RuntimeCall;");  \
    static_oop_field(RuntimeCall, JavaTimeNanos, "Lcom/oracle/graal/api/code/RuntimeCall;");   \
    static_oop_field(RuntimeCall, Debug, "Lcom/oracle/graal/api/code/RuntimeCall;");           \
    static_oop_field(RuntimeCall, ArithmeticFrem, "Lcom/oracle/graal/api/code/RuntimeCall;");  \
    static_oop_field(RuntimeCall, ArithmeticDrem, "Lcom/oracle/graal/api/code/RuntimeCall;");  \
    static_oop_field(RuntimeCall, ArithmeticCos, "Lcom/oracle/graal/api/code/RuntimeCall;");   \
    static_oop_field(RuntimeCall, ArithmeticTan, "Lcom/oracle/graal/api/code/RuntimeCall;");   \
    static_oop_field(RuntimeCall, ArithmeticSin, "Lcom/oracle/graal/api/code/RuntimeCall;");   \
    static_oop_field(RuntimeCall, Deoptimize, "Lcom/oracle/graal/api/code/RuntimeCall;");      \
    static_oop_field(RuntimeCall, GenericCallback, "Lcom/oracle/graal/api/code/RuntimeCall;"); \
    static_oop_field(RuntimeCall, LogPrimitive, "Lcom/oracle/graal/api/code/RuntimeCall;");    \
    static_oop_field(RuntimeCall, LogObject, "Lcom/oracle/graal/api/code/RuntimeCall;");       \
  end_class                                                                             \
  start_class(JavaMethod)                                                                 \
  end_class                                                                             \
  start_class(Value)                                                                  \
    oop_field(Value, kind, "Lcom/oracle/graal/api/meta/Kind;")                          \
    static_oop_field(Value, IllegalValue, "Lcom/oracle/graal/api/meta/Value;");         \
  end_class                                                                             \
  start_class(RegisterValue)                                                          \
    oop_field(RegisterValue, reg, "Lcom/oracle/graal/api/code/Register;")               \
  end_class                                                                             \
  start_class(code_Register)                                                               \
    int_field(code_Register, number)                                                       \
  end_class                                                                             \
  start_class(StackSlot)                                                              \
    int_field(StackSlot, offset)                                                      \
    boolean_field(StackSlot, addFrameSize)                                            \
  end_class                                                                             \
  start_class(VirtualObject)                                                          \
    int_field(VirtualObject, id)                                                      \
    oop_field(VirtualObject, type, "Lcom/oracle/graal/api/meta/JavaType;")                  \
    oop_field(VirtualObject, values, "[Lcom/oracle/graal/api/meta/Value;")              \
  end_class                                                                             \
  start_class(code_MonitorValue)                                                           \
    oop_field(code_MonitorValue, owner, "Lcom/oracle/graal/api/meta/Value;")                 \
    oop_field(code_MonitorValue, lockData, "Lcom/oracle/graal/api/meta/Value;")              \
    boolean_field(code_MonitorValue, eliminated)                                           \
  end_class                                                                             \
  /* end*/

#define START_CLASS(name)                       \
class name : AllStatic {                      \
  private:                                      \
    friend class GraalCompiler;                   \
    static void check(oop obj) { assert(obj != NULL, "NULL field access of class " #name); assert(obj->is_a(SystemDictionary::name##_klass()), "wrong class, " #name " expected"); } \
    static void compute_offsets();              \
  public:                                       \
    static klassOop klass() { return SystemDictionary::name##_klass(); }

#define END_CLASS };

#define FIELD(name, type, accessor)             \
    static int _##name##_offset;                \
    static type name(oop obj)                   { check(obj); return obj->accessor(_##name##_offset); } \
    static type name(Handle& obj)                { check(obj()); return obj->accessor(_##name##_offset); } \
    static type name(jobject obj)               { check(JNIHandles::resolve(obj)); return JNIHandles::resolve(obj)->accessor(_##name##_offset); } \
    static void set_##name(oop obj, type x)     { check(obj); obj->accessor##_put(_##name##_offset, x); } \
    static void set_##name(Handle& obj, type x)  { check(obj()); obj->accessor##_put(_##name##_offset, x); } \
    static void set_##name(jobject obj, type x) { check(JNIHandles::resolve(obj)); JNIHandles::resolve(obj)->accessor##_put(_##name##_offset, x); }

#define CHAR_FIELD(klass, name) FIELD(name, jchar, char_field)
#define INT_FIELD(klass, name) FIELD(name, jint, int_field)
#define BOOLEAN_FIELD(klass, name) FIELD(name, jboolean, bool_field)
#define LONG_FIELD(klass, name) FIELD(name, jlong, long_field)
#define FLOAT_FIELD(klass, name) FIELD(name, jfloat, float_field)
#define OOP_FIELD(klass, name, signature) FIELD(name, oop, obj_field)
#define STATIC_OOP_FIELD(klassName, name, signature)                \
    static int _##name##_offset;                                    \
    static oop name() {                                             \
      instanceKlass* ik = instanceKlass::cast(klassName::klass());  \
      address addr = ik->static_field_addr(_##name##_offset - instanceMirrorKlass::offset_of_static_fields());       \
      if (UseCompressedOops) {                                      \
        return oopDesc::load_decode_heap_oop((narrowOop *)addr);    \
      } else {                                                      \
        return oopDesc::load_decode_heap_oop((oop*)addr);           \
      }                                                             \
    }                                                               \
    static void set_##name(oop x) {                                 \
      instanceKlass* ik = instanceKlass::cast(klassName::klass());  \
      address addr = ik->static_field_addr(_##name##_offset - instanceMirrorKlass::offset_of_static_fields());       \
      if (UseCompressedOops) {                                      \
        oop_store((narrowOop *)addr, x);       \
      } else {                                                      \
        oop_store((oop*)addr, x);              \
      }                                                             \
    }
COMPILER_CLASSES_DO(START_CLASS, END_CLASS, CHAR_FIELD, INT_FIELD, BOOLEAN_FIELD, LONG_FIELD, FLOAT_FIELD, OOP_FIELD, STATIC_OOP_FIELD)
#undef START_CLASS
#undef END_CLASS
#undef FIELD
#undef CHAR_FIELD
#undef INT_FIELD
#undef BOOLEAN_FIELD
#undef LONG_FIELD
#undef FLOAT_FIELD
#undef OOP_FIELD
#undef STATIC_OOP_FIELD

void compute_offset(int &dest_offset, klassOop klass_oop, const char* name, const char* signature, bool static_field);

#endif // SHARE_VM_GRAAL_GRAAL_JAVA_ACCESS_HPP
