/*
 * Copyright 2000-2010 Sun Microsystems, Inc.  All Rights Reserved.
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
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 *
 */

void c1x_compute_offsets();

/* This macro defines the structure of the CiTargetMethod - classes.
 * It will generate classes with accessors similar to javaClasses.hpp, but with specializations for oops, Handles and jni handles.
 *
 * The public interface of these classes will look like this:

 * class CiStackSlot : AllStatic {
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

#define COMPILER_CLASSES_DO(start_class, end_class, char_field, int_field, boolean_field, long_field, oop_field, static_oop_field)   \
  start_class(HotSpotTypeResolved)                                                      \
    long_field(HotSpotTypeResolved, vmId)                                               \
    long_field(HotSpotTypeResolved, javaMirrorVmId)                                     \
    oop_field(HotSpotTypeResolved, name, "Ljava/lang/String;")                          \
    int_field(HotSpotTypeResolved, accessFlags)                                         \
    boolean_field(HotSpotTypeResolved, hasFinalizer)                                    \
    boolean_field(HotSpotTypeResolved, hasSubclass)                                     \
    boolean_field(HotSpotTypeResolved, hasFinalizableSubclass)                          \
    boolean_field(HotSpotTypeResolved, isInitialized)                                   \
    boolean_field(HotSpotTypeResolved, isArrayClass)                                    \
    boolean_field(HotSpotTypeResolved, isInstanceClass)                                 \
    boolean_field(HotSpotTypeResolved, isInterface)                                     \
    int_field(HotSpotTypeResolved, instanceSize)                                        \
    oop_field(HotSpotTypeResolved, componentType, "Lcom/sun/cri/ri/RiType;")            \
  end_class                                                                             \
  start_class(HotSpotMethodResolved)                                                    \
    long_field(HotSpotMethodResolved, vmId)                                             \
  end_class                                                                             \
  start_class(HotSpotTargetMethod)                                                      \
    oop_field(HotSpotTargetMethod, targetMethod, "Lcom/sun/cri/ci/CiTargetMethod;")     \
    oop_field(HotSpotTargetMethod, method, "Lcom/sun/hotspot/c1x/HotSpotMethodResolved;")\
    oop_field(HotSpotTargetMethod, name, "Ljava/lang/String;")                          \
    oop_field(HotSpotTargetMethod, sites, "[Lcom/sun/cri/ci/CiTargetMethod$Site;")      \
    oop_field(HotSpotTargetMethod, exceptionHandlers, "[Lcom/sun/cri/ci/CiTargetMethod$ExceptionHandler;") \
  end_class                                                                             \
  start_class(HotSpotExceptionHandler)                                                  \
    int_field(HotSpotExceptionHandler, startBci)                                        \
    int_field(HotSpotExceptionHandler, endBci)                                          \
    int_field(HotSpotExceptionHandler, handlerBci)                                      \
    int_field(HotSpotExceptionHandler, catchClassIndex)                                 \
    oop_field(HotSpotExceptionHandler, catchClass, "Lcom/sun/cri/ri/RiType;")           \
  end_class                                                                             \
  start_class(CiTargetMethod)                                                           \
    int_field(CiTargetMethod, frameSize)                                                \
    oop_field(CiTargetMethod, targetCode, "[B")                                         \
    int_field(CiTargetMethod, targetCodeSize)                                           \
  end_class                                                                             \
  start_class(CiTargetMethod_Site)                                                      \
    int_field(CiTargetMethod_Site, pcOffset)                                            \
  end_class                                                                             \
  start_class(CiTargetMethod_Call)                                                      \
    oop_field(CiTargetMethod_Call, runtimeCall, "Lcom/sun/cri/ci/CiRuntimeCall;")       \
    oop_field(CiTargetMethod_Call, method, "Lcom/sun/cri/ri/RiMethod;")                 \
    oop_field(CiTargetMethod_Call, symbol, "Ljava/lang/String;")                        \
    oop_field(CiTargetMethod_Call, globalStubID, "Ljava/lang/Object;")                  \
    oop_field(CiTargetMethod_Call, debugInfo, "Lcom/sun/cri/ci/CiDebugInfo;")           \
  end_class                                                                             \
  start_class(CiTargetMethod_DataPatch)                                                 \
    oop_field(CiTargetMethod_DataPatch, constant, "Lcom/sun/cri/ci/CiConstant;")        \
  end_class                                                                             \
  start_class(CiTargetMethod_Safepoint)                                                 \
    oop_field(CiTargetMethod_Safepoint, debugInfo, "Lcom/sun/cri/ci/CiDebugInfo;")      \
  end_class                                                                             \
  start_class(CiTargetMethod_ExceptionHandler)                                          \
    int_field(CiTargetMethod_ExceptionHandler, handlerPos)                              \
    int_field(CiTargetMethod_ExceptionHandler, handlerBci)                              \
    int_field(CiTargetMethod_ExceptionHandler, bci)                                     \
    int_field(CiTargetMethod_ExceptionHandler, scopeLevel)                              \
    oop_field(CiTargetMethod_ExceptionHandler, exceptionType, "Lcom/sun/cri/ri/RiType;")\
  end_class                                                                             \
  start_class(CiTargetMethod_Mark)                                                      \
    oop_field(CiTargetMethod_Mark, id, "Ljava/lang/Object;")                            \
    oop_field(CiTargetMethod_Mark, references, "[Lcom/sun/cri/ci/CiTargetMethod$Mark;") \
  end_class                                                                             \
  start_class(CiDebugInfo)                                                              \
    oop_field(CiDebugInfo, codePos, "Lcom/sun/cri/ci/CiCodePos;")                       \
    oop_field(CiDebugInfo, registerRefMap, "[B")                                        \
    oop_field(CiDebugInfo, frameRefMap, "[B")                                           \
  end_class                                                                             \
  start_class(CiDebugInfo_Frame)                                                        \
    oop_field(CiDebugInfo_Frame, values, "[Lcom/sun/cri/ci/CiValue;")                   \
    int_field(CiDebugInfo_Frame, numLocals)                                             \
    int_field(CiDebugInfo_Frame, numStack)                                              \
    int_field(CiDebugInfo_Frame, numLocks)                                              \
  end_class                                                                             \
  start_class(CiCodePos)                                                                \
    oop_field(CiCodePos, caller, "Lcom/sun/cri/ci/CiCodePos;")                          \
    oop_field(CiCodePos, method, "Lcom/sun/cri/ri/RiMethod;")                           \
    int_field(CiCodePos, bci)                                                           \
  end_class                                                                             \
  start_class(CiConstant)                                                               \
    oop_field(CiConstant, kind, "Lcom/sun/cri/ci/CiKind;")                              \
    oop_field(CiConstant, object, "Ljava/lang/Object;")                                 \
    long_field(CiConstant, primitive)                                                   \
  end_class                                                                             \
  start_class(CiKind)                                                                   \
    char_field(CiKind, typeChar)                                                        \
  end_class                                                                             \
  start_class(CiRuntimeCall)                                                            \
    static_oop_field(CiRuntimeCall, UnwindException, "Lcom/sun/cri/ci/CiRuntimeCall;"); \
    static_oop_field(CiRuntimeCall, RegisterFinalizer, "Lcom/sun/cri/ci/CiRuntimeCall;"); \
    static_oop_field(CiRuntimeCall, HandleException, "Lcom/sun/cri/ci/CiRuntimeCall;"); \
    static_oop_field(CiRuntimeCall, OSRMigrationEnd, "Lcom/sun/cri/ci/CiRuntimeCall;"); \
    static_oop_field(CiRuntimeCall, JavaTimeMillis, "Lcom/sun/cri/ci/CiRuntimeCall;");  \
    static_oop_field(CiRuntimeCall, JavaTimeNanos, "Lcom/sun/cri/ci/CiRuntimeCall;");   \
    static_oop_field(CiRuntimeCall, Debug, "Lcom/sun/cri/ci/CiRuntimeCall;");           \
    static_oop_field(CiRuntimeCall, ArithmethicLrem, "Lcom/sun/cri/ci/CiRuntimeCall;"); \
    static_oop_field(CiRuntimeCall, ArithmeticLdiv, "Lcom/sun/cri/ci/CiRuntimeCall;");  \
    static_oop_field(CiRuntimeCall, ArithmeticFrem, "Lcom/sun/cri/ci/CiRuntimeCall;");  \
    static_oop_field(CiRuntimeCall, ArithmeticDrem, "Lcom/sun/cri/ci/CiRuntimeCall;");  \
    static_oop_field(CiRuntimeCall, ArithmeticCos, "Lcom/sun/cri/ci/CiRuntimeCall;");   \
    static_oop_field(CiRuntimeCall, ArithmeticTan, "Lcom/sun/cri/ci/CiRuntimeCall;");   \
    static_oop_field(CiRuntimeCall, ArithmeticLog, "Lcom/sun/cri/ci/CiRuntimeCall;");   \
    static_oop_field(CiRuntimeCall, ArithmeticLog10, "Lcom/sun/cri/ci/CiRuntimeCall;"); \
    static_oop_field(CiRuntimeCall, ArithmeticSin, "Lcom/sun/cri/ci/CiRuntimeCall;");   \
  end_class                                                                             \
  start_class(RiMethod)                                                                 \
  end_class                                                                             \
  start_class(CiValue)                                                                  \
    oop_field(CiValue, kind, "Lcom/sun/cri/ci/CiKind;")                                 \
    static_oop_field(CiValue, IllegalValue, "Lcom/sun/cri/ci/CiValue;");                \
  end_class                                                                             \
  start_class(CiRegisterValue)                                                          \
    oop_field(CiRegisterValue, reg, "Lcom/sun/cri/ci/CiRegister;")                      \
  end_class                                                                             \
  start_class(CiRegister)                                                               \
    int_field(CiRegister, number)                                                       \
  end_class                                                                             \
  start_class(CiStackSlot)                                                              \
    int_field(CiStackSlot, index)                                                       \
  end_class                                                                             \
  /* end*/




#define START_CLASS(name)                       \
  class name : AllStatic {                      \
  private:                                      \
    friend class C1XCompiler;                   \
    static void check(oop obj) { assert(obj != NULL, "NULL field access"); assert(obj->is_a(SystemDictionary::name##_klass()), "wrong class, " #name " expected"); } \
    static void compute_offsets();              \
  public:                                       \
    static klassOop klass() { return SystemDictionary::name##_klass(); }

#define END_CLASS };

#define FIELD(name, type, accessor)             \
    static int _##name##_offset;                \
    static type name(oop obj)                   { check(obj); return obj->accessor(_##name##_offset); } \
    static type name(Handle obj)                { check(obj()); return obj->accessor(_##name##_offset); } \
    static type name(jobject obj)               { check(JNIHandles::resolve(obj)); return JNIHandles::resolve(obj)->accessor(_##name##_offset); } \
    static void set_##name(oop obj, type x)     { check(obj); obj->accessor##_put(_##name##_offset, x); } \
    static void set_##name(Handle obj, type x)  { check(obj()); obj->accessor##_put(_##name##_offset, x); } \
    static void set_##name(jobject obj, type x) { check(JNIHandles::resolve(obj)); JNIHandles::resolve(obj)->accessor##_put(_##name##_offset, x); }

#define CHAR_FIELD(klass, name) FIELD(name, jchar, char_field)
#define INT_FIELD(klass, name) FIELD(name, jint, int_field)
#define BOOLEAN_FIELD(klass, name) FIELD(name, jboolean, bool_field)
#define LONG_FIELD(klass, name) FIELD(name, jlong, long_field)
#define OOP_FIELD(klass, name, signature) FIELD(name, oop, obj_field)
#define STATIC_OOP_FIELD(klassName, name, signature) \
    static int _##name##_offset;                \
    static oop name()             { return klassName::klass()->obj_field(_##name##_offset); } \
    static void set_##name(oop x) { klassName::klass()->obj_field_put(_##name##_offset, x); }

COMPILER_CLASSES_DO(START_CLASS, END_CLASS, CHAR_FIELD, INT_FIELD, BOOLEAN_FIELD, LONG_FIELD, OOP_FIELD, STATIC_OOP_FIELD)
#undef START_CLASS
#undef END_CLASS
#undef FIELD
#undef CHAR_FIELD
#undef INT_FIELD
#undef BOOLEAN_FIELD
#undef LONG_FIELD
#undef OOP_FIELD
#undef STATIC_OOP_FIELD


