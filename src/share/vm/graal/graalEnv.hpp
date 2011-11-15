/*
 * Copyright (c) 1999, 2011, Oracle and/or its affiliates. All rights reserved.
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
 *
 */

#ifndef SHARE_VM_GRAAL_GRAALENV_HPP
#define SHARE_VM_GRAAL_GRAALENV_HPP

#include "classfile/systemDictionary.hpp"
#include "code/debugInfoRec.hpp"
#include "code/dependencies.hpp"
#include "code/exceptionHandlerTable.hpp"
#include "compiler/oopMap.hpp"
#include "runtime/thread.hpp"

class CompileTask;

// ciEnv
//
// This class is the top level broker for requests from the compiler
// to the VM.
class GraalEnv : AllStatic {
  CI_PACKAGE_ACCESS_TO

  friend class CompileBroker;
  friend class Dependencies;  // for get_object, during logging

public:

  // Look up a klass by name from a particular class loader (the accessor's).
  // If require_local, result must be defined in that class loader, or NULL.
  // If !require_local, a result from remote class loader may be reported,
  // if sufficient class loader constraints exist such that initiating
  // a class loading request from the given loader is bound to return
  // the class defined in the remote loader (or throw an error).
  //
  // Return an unloaded klass if !require_local and no class at all is found.
  //
  // The CI treats a klass as loaded if it is consistently defined in
  // another loader, even if it hasn't yet been loaded in all loaders
  // that could potentially see it via delegation.
  static KlassHandle get_klass_by_name(KlassHandle accessing_klass,
                             Symbol* klass_name,
                             bool require_local);

  // Constant pool access.
  static KlassHandle   get_klass_by_index(constantPoolHandle cpool,
                                int klass_index,
                                bool& is_accessible,
                                KlassHandle loading_klass);
  static void   get_field_by_index(instanceKlassHandle loading_klass, fieldDescriptor& fd,
                                int field_index);
  static methodHandle  get_method_by_index(constantPoolHandle cpool,
                                 int method_index, Bytecodes::Code bc,
                                 instanceKlass* loading_klass);

private:

  // Implementation methods for loading and constant pool access.
  static klassOop get_klass_by_name_impl(KlassHandle accessing_klass,
                                  constantPoolHandle cpool,
                                  Symbol* klass_name,
                                  bool require_local);
  static klassOop   get_klass_by_index_impl(constantPoolHandle cpool,
                                     int klass_index,
                                     bool& is_accessible,
                                     KlassHandle loading_klass);
  static void   get_field_by_index_impl(instanceKlassHandle loading_klass, fieldDescriptor& fd,
                                     int field_index);
  static methodHandle  get_method_by_index_impl(constantPoolHandle cpool,
                                      int method_index, Bytecodes::Code bc,
                                      instanceKlass* loading_klass);

  // Helper methods
  static bool       check_klass_accessibility(klassOop accessing_klass, klassOop resolved_klassOop);
  static methodOop  lookup_method(instanceKlass*  accessor,
                           instanceKlass*  holder,
                           Symbol*         name,
                           Symbol*         sig,
                           Bytecodes::Code bc);

  private:

  // Is this thread currently in the VM state?
  static bool is_in_vm();

  // Helper routine for determining the validity of a compilation
  // with respect to concurrent class loading.
  static bool check_for_system_dictionary_modification(Dependencies* target);

public:
  // Register the result of a compilation.
  static nmethod* register_method(methodHandle             target,
                       int                       entry_bci,
                       CodeOffsets*              offsets,
                       int                       orig_pc_offset,
                       CodeBuffer*               code_buffer,
                       int                       frame_words,
                       OopMapSet*                oop_map_set,
                       ExceptionHandlerTable*    handler_table,
                       ImplicitExceptionTable*   inc_table,
                       AbstractCompiler*         compiler,
                       DebugInformationRecorder* debug_info,
                       Dependencies*             dependencies,
                       CompileTask*              task,
                       int                       compile_id,
                       bool                      has_debug_info = true,
                       bool                      has_unsafe_access = false,
                       bool                      install_code = true);

  static ciKlass*  find_system_klass(ciSymbol* klass_name);
  // Note:  To find a class from its name string, use ciSymbol::make,
  // but consider adding to vmSymbols.hpp instead.

  // converts the ciKlass* representing the holder of a method into a
  // ciInstanceKlass*.  This is needed since the holder of a method in
  // the bytecodes could be an array type.  Basically this converts
  // array types into java/lang/Object and other types stay as they are.
  static instanceKlass* get_instance_klass_for_declared_method_holder(KlassHandle klass);
};

#endif // SHARE_VM_GRAAL_GRAALENV_HPP
