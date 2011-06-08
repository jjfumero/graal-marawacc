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

#include "compiler/abstractCompiler.hpp"

class C1XCompiler : public AbstractCompiler {

private:

  bool                  _initialized;

  static C1XCompiler*   _instance;

public:

  C1XCompiler();

  static C1XCompiler* instance() { return _instance; }


  virtual const char* name() { return "C1X"; }

  // Native / OSR not supported
  virtual bool supports_native()                 { return false; }
  virtual bool supports_osr   ()                 { return false; }

  // Pretend to be C1
  bool is_c1   ()                                { return true; }
  bool is_c2   ()                                { return false; }

  // Initialization
  virtual void initialize();

  // Compilation entry point for methods
  virtual void compile_method(ciEnv* env, ciMethod* target, int entry_bci);

  // Print compilation timers and statistics
  virtual void print_timers();

  static oop get_RiType(ciType *klass, KlassHandle accessor, TRAPS);
  static oop get_RiField(ciField *ciField, ciInstanceKlass* accessor_klass, KlassHandle accessor, Bytecodes::Code byteCode, TRAPS);

  static oop createHotSpotTypeResolved(KlassHandle klass, Handle name, TRAPS);

  static BasicType kindToBasicType(jchar ch);

  static int to_cp_index_u2(int index) {
    // Swap.
    index = ((index & 0xFF) << 8) | (index >> 8);
    // Tag.
    index = index + constantPoolOopDesc::CPCACHE_INDEX_TAG;
    return index;
  }

private:

  void initialize_buffer_blob();
};

// Tracing macros

#define IF_TRACE_C1X_1 if (!(TraceC1X >= 1)) ; else
#define IF_TRACE_C1X_2 if (!(TraceC1X >= 2)) ; else
#define IF_TRACE_C1X_3 if (!(TraceC1X >= 3)) ; else
#define IF_TRACE_C1X_4 if (!(TraceC1X >= 4)) ; else
#define IF_TRACE_C1X_5 if (!(TraceC1X >= 5)) ; else

// using commas and else to keep one-instruction semantics

#define TRACE_C1X_1 if (!(TraceC1X >= 1 && (tty->print("TraceC1X-1: "), true))) ; else tty->print_cr
#define TRACE_C1X_2 if (!(TraceC1X >= 2 && (tty->print("   TraceC1X-2: "), true))) ; else tty->print_cr
#define TRACE_C1X_3 if (!(TraceC1X >= 3 && (tty->print("      TraceC1X-3: "), true))) ; else tty->print_cr
#define TRACE_C1X_4 if (!(TraceC1X >= 4 && (tty->print("         TraceC1X-4: "), true))) ; else tty->print_cr
#define TRACE_C1X_5 if (!(TraceC1X >= 5 && (tty->print("            TraceC1X-5: "), true))) ; else tty->print_cr



