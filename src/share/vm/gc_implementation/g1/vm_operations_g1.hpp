/*
 * Copyright (c) 2001, 2009, Oracle and/or its affiliates. All rights reserved.
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

// VM_operations for the G1 collector.
// VM_GC_Operation:
//   - VM_CGC_Operation
//   - VM_G1CollectFull
//   - VM_G1CollectForAllocation
//   - VM_G1IncCollectionPause
//   - VM_G1PopRegionCollectionPause

class VM_G1CollectFull: public VM_GC_Operation {
 public:
  VM_G1CollectFull(unsigned int gc_count_before,
                   unsigned int full_gc_count_before,
                   GCCause::Cause cause)
    : VM_GC_Operation(gc_count_before, full_gc_count_before) {
    _gc_cause = cause;
  }
  ~VM_G1CollectFull() {}
  virtual VMOp_Type type() const { return VMOp_G1CollectFull; }
  virtual void doit();
  virtual const char* name() const {
    return "full garbage-first collection";
  }
};

class VM_G1CollectForAllocation: public VM_GC_Operation {
 private:
  HeapWord*   _res;
  size_t      _size;                       // size of object to be allocated
 public:
  VM_G1CollectForAllocation(size_t size, int gc_count_before)
    : VM_GC_Operation(gc_count_before) {
    _size        = size;
    _res         = NULL;
  }
  ~VM_G1CollectForAllocation()        {}
  virtual VMOp_Type type() const { return VMOp_G1CollectForAllocation; }
  virtual void doit();
  virtual const char* name() const {
    return "garbage-first collection to satisfy allocation";
  }
  HeapWord* result() { return _res; }
};

class VM_G1IncCollectionPause: public VM_GC_Operation {
private:
  bool _should_initiate_conc_mark;
  double _target_pause_time_ms;
  unsigned int _full_collections_completed_before;
public:
  VM_G1IncCollectionPause(unsigned int   gc_count_before,
                          bool           should_initiate_conc_mark,
                          double         target_pause_time_ms,
                          GCCause::Cause cause)
    : VM_GC_Operation(gc_count_before),
      _full_collections_completed_before(0),
      _should_initiate_conc_mark(should_initiate_conc_mark),
      _target_pause_time_ms(target_pause_time_ms) {
    guarantee(target_pause_time_ms > 0.0,
              err_msg("target_pause_time_ms = %1.6lf should be positive",
                      target_pause_time_ms));

    _gc_cause = cause;
  }
  virtual VMOp_Type type() const { return VMOp_G1IncCollectionPause; }
  virtual void doit();
  virtual void doit_epilogue();
  virtual const char* name() const {
    return "garbage-first incremental collection pause";
  }
};

// Concurrent GC stop-the-world operations such as initial and final mark;
// consider sharing these with CMS's counterparts.
class VM_CGC_Operation: public VM_Operation {
  VoidClosure* _cl;
  const char* _printGCMessage;
 public:
  VM_CGC_Operation(VoidClosure* cl, const char *printGCMsg) :
    _cl(cl),
    _printGCMessage(printGCMsg)
    {}

  ~VM_CGC_Operation() {}

  virtual VMOp_Type type() const { return VMOp_CGC_Operation; }
  virtual void doit();
  virtual bool doit_prologue();
  virtual void doit_epilogue();
  virtual const char* name() const {
    return "concurrent gc";
  }
};
