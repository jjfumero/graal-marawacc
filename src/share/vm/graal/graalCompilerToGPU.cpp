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

#include "precompiled.hpp"

#include "memory/oopFactory.hpp"
#include "graal/graalCompiler.hpp"
#include "graal/graalEnv.hpp"
#include "graal/graalJavaAccess.hpp"
#include "runtime/gpu.hpp"
#include "runtime/javaCalls.hpp"
# include "ptx/vm/ptxKernelArguments.hpp"

// Entry to native method implementation that transitions current thread to '_thread_in_vm'.
#define C2V_VMENTRY(result_type, name, signature) \
  JNIEXPORT result_type JNICALL c2v_ ## name signature { \
  TRACE_graal_3("CompilerToGPU::" #name); \
  GRAAL_VM_ENTRY_MARK; \

// Entry to native method implementation that calls a JNI function
// and hence cannot transition current thread to '_thread_in_vm'.
#define C2V_ENTRY(result_type, name, signature) \
  JNIEXPORT result_type JNICALL c2v_ ## name signature { \
  TRACE_graal_3("CompilerToGPU::" #name); \

#define C2V_END }


C2V_ENTRY(jlong, generateKernel, (JNIEnv *env, jobject, jbyteArray code, jstring name))
  if (gpu::is_available() == false || gpu::has_gpu_linkage() == false && gpu::is_initialized()) {
    tty->print_cr("generateKernel - not available / no linkage / not initialized");
    return 0;
  }
  jboolean is_copy;
  jbyte *bytes = env->GetByteArrayElements(code, &is_copy);
  jint len = env->GetArrayLength(code);
  const char *namestr = env->GetStringUTFChars(name, &is_copy);
  void *kernel = gpu::generate_kernel((unsigned char *)bytes, len, namestr);
  if (kernel == NULL) {
    tty->print_cr("[CUDA] *** Error: Failed to compile kernel");
  }
  else if (TraceGPUInteraction) {
    tty->print_cr("[CUDA] Generated kernel");
  }
  env->ReleaseByteArrayElements(code, bytes, 0);
  env->ReleaseStringUTFChars(name, namestr);

  return (jlong)kernel;
C2V_END

C2V_VMENTRY(jobject, executeExternalMethodVarargs, (JNIEnv *env, jobject, jobject args, jobject hotspotInstalledCode))
  ResourceMark rm;
  HandleMark hm;

  if (gpu::is_available() == false || gpu::has_gpu_linkage() == false && gpu::is_initialized()) {
    tty->print_cr("executeExternalMethodVarargs - not available / no linkage / not initialized");
    return NULL;
  }
  jlong nmethodValue = HotSpotInstalledCode::codeBlob(hotspotInstalledCode);
  nmethod* nm = (nmethod*) (address) nmethodValue;
  methodHandle mh = nm->method();
  Symbol* signature = mh->signature();

  // start value is the kernel
  jlong startValue = HotSpotInstalledCode::codeStart(hotspotInstalledCode);

  PTXKernelArguments ptxka(signature, (arrayOop) JNIHandles::resolve(args), mh->is_static());
  JavaValue result(ptxka.get_ret_type());
  if (!gpu::execute_kernel((address)startValue, ptxka, result)) {
    return NULL;
  }

  if (ptxka.get_ret_type() == T_VOID) {
    return NULL;
  } else if (ptxka.get_ret_type() == T_OBJECT || ptxka.get_ret_type() == T_ARRAY) {
    return JNIHandles::make_local((oop) result.get_jobject());
  } else {
    oop o = java_lang_boxing_object::create(ptxka.get_ret_type(), (jvalue *) result.get_value_addr(), CHECK_NULL);
    if (TraceGPUInteraction) {
      switch (ptxka.get_ret_type()) {
        case T_INT:
          tty->print_cr("GPU execution returned %d", result.get_jint());
          break;
        case T_FLOAT:
          tty->print_cr("GPU execution returned %f", result.get_jfloat());
          break;
        case T_DOUBLE:
          tty->print_cr("GPU execution returned %f", result.get_jdouble());
          break;
        default:
          tty->print_cr("GPU returned unhandled");
          break;
        }
    }
    return JNIHandles::make_local(o);
  }

C2V_END

C2V_VMENTRY(jobject, executeParallelMethodVarargs, (JNIEnv *env,
                                                          jobject,
                                                          jint dimX, jint dimY, jint dimZ,
                                                          jobject args, jobject hotspotInstalledCode))
  ResourceMark rm;
  HandleMark hm;

  if (gpu::is_available() == false || gpu::has_gpu_linkage() == false && gpu::is_initialized()) {
    tty->print_cr("executeParallelMethodVarargs - not available / no linkage / not initialized");
    return NULL;
  }
  jlong nmethodValue = HotSpotInstalledCode::codeBlob(hotspotInstalledCode);
  nmethod* nm = (nmethod*) (address) nmethodValue;
  methodHandle mh = nm->method();
  Symbol* signature = mh->signature();

  // start value is the kernel
  jlong startValue = HotSpotInstalledCode::codeStart(hotspotInstalledCode);

  PTXKernelArguments ptxka(signature, (arrayOop) JNIHandles::resolve(args), mh->is_static());
  JavaValue result(ptxka.get_ret_type());
if (!gpu::execute_warp(dimX, dimY, dimZ, (address)startValue, ptxka, result)) {
    return NULL;
  }

  if (ptxka.get_ret_type() == T_VOID) {
    return NULL;
  } else if (ptxka.get_ret_type() == T_OBJECT || ptxka.get_ret_type() == T_ARRAY) {
    return JNIHandles::make_local((oop) result.get_jobject());
  } else {
    oop o = java_lang_boxing_object::create(ptxka.get_ret_type(), (jvalue *) result.get_value_addr(), CHECK_NULL);
    if (TraceGPUInteraction) {
      switch (ptxka.get_ret_type()) {
        case T_INT:
          tty->print_cr("GPU execution returned %d", result.get_jint());
          break;
        case T_FLOAT:
          tty->print_cr("GPU execution returned %f", result.get_jfloat());
          break;
        case T_DOUBLE:
          tty->print_cr("GPU execution returned %g", result.get_jdouble());
          break;
        default:
          tty->print_cr("GPU returned unhandled");
          break;
      }
    }
    return JNIHandles::make_local(o);
  }

C2V_END

C2V_VMENTRY(jboolean, deviceInit, (JNIEnv *env, jobject))
  if (gpu::is_available() == false || gpu::has_gpu_linkage() == false) {
    tty->print_cr("deviceInit - not available / no linkage");
    return false;
  }
  if (gpu::is_initialized()) {
    tty->print_cr("deviceInit - already initialized");
    return true;
  }
  gpu::initialize_gpu();
  return gpu::is_initialized();
C2V_END

C2V_VMENTRY(jint, availableProcessors, (JNIEnv *env, jobject))
  if (gpu::is_available() == false || gpu::has_gpu_linkage() == false) {
    tty->print_cr("deviceInit - not available / no linkage");
    return false;
  }
  return gpu::available_processors();
C2V_END

static objArrayHandle newSingletonStringArray(const char* value, TRAPS) {
  objArrayHandle nullRes;
  objArrayOop res = oopFactory::new_objArray(SystemDictionary::String_klass(), 1, CHECK_(nullRes));
  objArrayHandle res_h = objArrayHandle(THREAD, res);
  Handle valueString = java_lang_String::create_from_str(value, CHECK_(nullRes));
  res_h->obj_at_put(0, valueString());
  return res_h;
}

C2V_VMENTRY(jobject, getAvailableGPUArchitectures, (JNIEnv *env, jobject))
  if (UseGPU) {
    if (gpu::is_available() && gpu::has_gpu_linkage()) {
      if (gpu::get_target_il_type() == gpu::PTX) {
        return JNIHandles::make_local(newSingletonStringArray("PTX", THREAD)());
      }
      if (gpu::get_target_il_type() == gpu::HSAIL) {
        return JNIHandles::make_local(newSingletonStringArray("HSAIL", THREAD)());
      }
    }
  }
  return JNIHandles::make_local(oopFactory::new_objArray(SystemDictionary::String_klass(), 0, THREAD));
C2V_END

C2V_VMENTRY(jboolean, deviceDetach, (JNIEnv *env, jobject))
return true;
C2V_END


#define CC (char*)  /*cast a literal from (const char*)*/
#define FN_PTR(f) CAST_FROM_FN_PTR(void*, &(c2v_ ## f))

#define RESOLVED_TYPE         "Lcom/oracle/graal/api/meta/ResolvedJavaType;"
#define TYPE                  "Lcom/oracle/graal/api/meta/JavaType;"
#define METHOD                "Lcom/oracle/graal/api/meta/JavaMethod;"
#define FIELD                 "Lcom/oracle/graal/api/meta/JavaField;"
#define SIGNATURE             "Lcom/oracle/graal/api/meta/Signature;"
#define CONSTANT_POOL         "Lcom/oracle/graal/api/meta/ConstantPool;"
#define CONSTANT              "Lcom/oracle/graal/api/meta/Constant;"
#define KIND                  "Lcom/oracle/graal/api/meta/Kind;"
#define LOCAL                 "Lcom/oracle/graal/api/meta/Local;"
#define RUNTIME_CALL          "Lcom/oracle/graal/api/code/RuntimeCall;"
#define EXCEPTION_HANDLERS    "[Lcom/oracle/graal/api/meta/ExceptionHandler;"
#define REFLECT_METHOD        "Ljava/lang/reflect/Method;"
#define REFLECT_CONSTRUCTOR   "Ljava/lang/reflect/Constructor;"
#define REFLECT_FIELD         "Ljava/lang/reflect/Field;"
#define STRING                "Ljava/lang/String;"
#define OBJECT                "Ljava/lang/Object;"
#define CLASS                 "Ljava/lang/Class;"
#define STACK_TRACE_ELEMENT   "Ljava/lang/StackTraceElement;"
#define HS_RESOLVED_TYPE      "Lcom/oracle/graal/hotspot/meta/HotSpotResolvedObjectType;"
#define HS_RESOLVED_JAVA_TYPE "Lcom/oracle/graal/hotspot/meta/HotSpotResolvedJavaType;"
#define HS_RESOLVED_METHOD    "Lcom/oracle/graal/hotspot/meta/HotSpotResolvedJavaMethod;"
#define HS_RESOLVED_FIELD     "Lcom/oracle/graal/hotspot/meta/HotSpotResolvedJavaField;"
#define HS_COMPILED_CODE      "Lcom/oracle/graal/hotspot/HotSpotCompiledCode;"
#define HS_CONFIG             "Lcom/oracle/graal/hotspot/HotSpotVMConfig;"
#define HS_METHOD             "Lcom/oracle/graal/hotspot/meta/HotSpotMethod;"
#define HS_INSTALLED_CODE     "Lcom/oracle/graal/hotspot/meta/HotSpotInstalledCode;"
#define METHOD_DATA           "Lcom/oracle/graal/hotspot/meta/HotSpotMethodData;"
#define METASPACE_METHOD      "J"
#define METASPACE_METHOD_DATA "J"
#define NMETHOD               "J"
#define GPUSPACE_METHOD       "J"

JNINativeMethod CompilerToGPU_methods[] = {
  {CC"generateKernel",                CC"([B" STRING ")"GPUSPACE_METHOD,          FN_PTR(generateKernel)},
  {CC"deviceInit",                    CC"()Z",                                    FN_PTR(deviceInit)},
  {CC"deviceDetach",                  CC"()Z",                                    FN_PTR(deviceDetach)},
  {CC"availableProcessors",           CC"()I",                                    FN_PTR(availableProcessors)},
  {CC"getAvailableGPUArchitectures",  CC"()["STRING,                              FN_PTR(getAvailableGPUArchitectures)},
  {CC"executeExternalMethodVarargs",  CC"(["OBJECT HS_INSTALLED_CODE")"OBJECT,    FN_PTR(executeExternalMethodVarargs)},
  {CC"executeParallelMethodVarargs",  CC"(III["OBJECT HS_INSTALLED_CODE")"OBJECT, FN_PTR(executeParallelMethodVarargs)},
};

int CompilerToGPU_methods_count() {
  return sizeof(CompilerToGPU_methods) / sizeof(JNINativeMethod);
}

