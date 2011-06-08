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

#include "memory/allocation.hpp"
#include "oops/oop.hpp"
#include "runtime/handles.hpp"
#include "runtime/thread.hpp"
#include "classfile/javaClasses.hpp"
#include "runtime/jniHandles.hpp"
#include "runtime/javaCalls.hpp"

class VMExits : public AllStatic {

private:
  static jobject _compilerPermObject;
  static jobject _vmExitsPermObject;
  static jobject _vmExitsPermKlass;

  static KlassHandle vmExitsKlass();
  static Handle instance();

public:
  static void initializeCompiler();

  static Handle compilerInstance();

  // public static boolean HotSpotOptions.setOption(String option);
  static jboolean setOption(Handle option);

  // public static void HotSpotOptions.setDefaultOptions();
  static void setDefaultOptions();

  // public abstract void compileMethod(long vmId, String name, int entry_bci);
  static void compileMethod(jlong vmId, Handle name, int entry_bci);

  // public abstract RiMethod createRiMethodResolved(long vmId, String name);
  static oop createRiMethodResolved(jlong vmId, Handle name, TRAPS);

  // public abstract RiMethod createRiMethodUnresolved(String name, String signature, RiType holder);
  static oop createRiMethodUnresolved(Handle name, Handle signature, Handle holder, TRAPS);

  // public abstract RiField createRiField(RiType holder, String name, RiType type, int flags, int offset);
  static oop createRiField(Handle holder, Handle name, Handle type, int index, int flags, TRAPS);

  // public abstract RiType createRiType(long vmId, String name);
  static oop createRiType(jlong vmId, Handle name, TRAPS);

  // public abstract RiType createRiTypeUnresolved(String name);
  static oop createRiTypeUnresolved(Handle name, TRAPS);

  // public abstract RiConstantPool createRiConstantPool(long vmId);
  static oop createRiConstantPool(jlong vmId, TRAPS);

  // public abstract RiType createRiTypePrimitive(int basicType);
  static oop createRiTypePrimitive(int basicType, TRAPS);

  // public abstract RiSignature createRiSignature(String signature);
  static oop createRiSignature(Handle name, TRAPS);

  // public abstract CiConstant createCiConstant(CiKind kind, long value);
  static oop createCiConstant(Handle kind, jlong value, TRAPS);

  // public abstract CiConstant createCiConstantFloat(float value);
  static oop createCiConstantFloat(jfloat value, TRAPS);

  // public abstract CiConstant createCiConstantDouble(double value);
  static oop createCiConstantDouble(jdouble value, TRAPS);

  // public abstract CiConstant createCiConstantObject(long vmId);
  static oop createCiConstantObject(Handle object, TRAPS);
};

inline void check_pending_exception(const char* message, bool dump_core = false) {
  Thread* THREAD = Thread::current();
  if (THREAD->has_pending_exception()) {
    Handle exception = PENDING_EXCEPTION;
    CLEAR_PENDING_EXCEPTION;
    tty->print_cr("%s", message);
    java_lang_Throwable::print(exception, tty);
    tty->cr();
    java_lang_Throwable::print_stack_trace(exception(), tty);
    vm_abort(dump_core);
  }
}

inline void check_not_null(void* value, const char* message, bool dump_core = false) {
  if (value == NULL) {
    tty->print_cr("%s", message);
    vm_abort(dump_core);
  }
}
