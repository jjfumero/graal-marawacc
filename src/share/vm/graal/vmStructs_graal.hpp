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
 *
 */

#ifndef SHARE_VM_GRAAL_VMSTRUCTS_GRAAL_HPP
#define SHARE_VM_GRAAL_VMSTRUCTS_GRAAL_HPP

#include "compiler/abstractCompiler.hpp"

#define VM_STRUCTS_GRAAL(nonstatic_field, static_field)                       \
                                                                              \
  static_field(java_lang_Class, _graal_mirror_offset, int)                    \
                                                                              \
  nonstatic_field(CompilerStatistics, _standard,           CompilerStatistics::Data) \
  nonstatic_field(CompilerStatistics, _osr,                CompilerStatistics::Data) \
  nonstatic_field(CompilerStatistics, _nmethods_size,      int)                      \
  nonstatic_field(CompilerStatistics, _nmethods_code_size, int)                      \
  nonstatic_field(CompilerStatistics::Data, _bytes,        int)                      \
  nonstatic_field(CompilerStatistics::Data, _count,        int)                      \
  nonstatic_field(CompilerStatistics::Data, _time,         elapsedTimer)             \


#define VM_TYPES_GRAAL(declare_type, declare_toplevel_type)                   \
                                                                              \
  declare_toplevel_type(CompilerStatistics)                                   \
  declare_toplevel_type(CompilerStatistics::Data)                             \


#endif // SHARE_VM_GRAAL_VMSTRUCTS_GRAAL_HPP
