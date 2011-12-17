/*
 * Copyright (c) 2001, 2011, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_VM_GC_IMPLEMENTATION_G1_G1OOPCLOSURES_INLINE_HPP
#define SHARE_VM_GC_IMPLEMENTATION_G1_G1OOPCLOSURES_INLINE_HPP

#include "gc_implementation/g1/concurrentMark.inline.hpp"
#include "gc_implementation/g1/g1CollectedHeap.hpp"
#include "gc_implementation/g1/g1OopClosures.hpp"
#include "gc_implementation/g1/g1RemSet.hpp"

/*
 * This really ought to be an inline function, but apparently the C++
 * compiler sometimes sees fit to ignore inline declarations.  Sigh.
 */

// This must a ifdef'ed because the counting it controls is in a
// perf-critical inner loop.
#define FILTERINTOCSCLOSURE_DOHISTOGRAMCOUNT 0

template <class T> inline void FilterIntoCSClosure::do_oop_nv(T* p) {
  T heap_oop = oopDesc::load_heap_oop(p);
  if (!oopDesc::is_null(heap_oop) &&
      _g1->obj_in_cs(oopDesc::decode_heap_oop_not_null(heap_oop))) {
    _oc->do_oop(p);
#if FILTERINTOCSCLOSURE_DOHISTOGRAMCOUNT
    if (_dcto_cl != NULL)
      _dcto_cl->incr_count();
#endif
  }
}

#define FILTEROUTOFREGIONCLOSURE_DOHISTOGRAMCOUNT 0

template <class T> inline void FilterOutOfRegionClosure::do_oop_nv(T* p) {
  T heap_oop = oopDesc::load_heap_oop(p);
  if (!oopDesc::is_null(heap_oop)) {
    HeapWord* obj_hw = (HeapWord*)oopDesc::decode_heap_oop_not_null(heap_oop);
    if (obj_hw < _r_bottom || obj_hw >= _r_end) {
      _oc->do_oop(p);
#if FILTEROUTOFREGIONCLOSURE_DOHISTOGRAMCOUNT
      _out_of_region++;
#endif
    }
  }
}

// This closure is applied to the fields of the objects that have just been copied.
template <class T> inline void G1ParScanClosure::do_oop_nv(T* p) {
  T heap_oop = oopDesc::load_heap_oop(p);

  if (!oopDesc::is_null(heap_oop)) {
    oop obj = oopDesc::decode_heap_oop_not_null(heap_oop);
    if (_g1->in_cset_fast_test(obj)) {
      // We're not going to even bother checking whether the object is
      // already forwarded or not, as this usually causes an immediate
      // stall. We'll try to prefetch the object (for write, given that
      // we might need to install the forwarding reference) and we'll
      // get back to it when pop it from the queue
      Prefetch::write(obj->mark_addr(), 0);
      Prefetch::read(obj->mark_addr(), (HeapWordSize*2));

      // slightly paranoid test; I'm trying to catch potential
      // problems before we go into push_on_queue to know where the
      // problem is coming from
      assert((obj == oopDesc::load_decode_heap_oop(p)) ||
             (obj->is_forwarded() &&
                 obj->forwardee() == oopDesc::load_decode_heap_oop(p)),
             "p should still be pointing to obj or to its forwardee");

      _par_scan_state->push_on_queue(p);
    } else {
      _par_scan_state->update_rs(_from, p, _par_scan_state->queue_num());
    }
  }
}

template <class T> inline void G1ParPushHeapRSClosure::do_oop_nv(T* p) {
  T heap_oop = oopDesc::load_heap_oop(p);

  if (!oopDesc::is_null(heap_oop)) {
    oop obj = oopDesc::decode_heap_oop_not_null(heap_oop);
    if (_g1->in_cset_fast_test(obj)) {
      Prefetch::write(obj->mark_addr(), 0);
      Prefetch::read(obj->mark_addr(), (HeapWordSize*2));

      // Place on the references queue
      _par_scan_state->push_on_queue(p);
    }
  }
}

template <class T> inline void G1CMOopClosure::do_oop_nv(T* p) {
  assert(_g1h->is_in_g1_reserved((HeapWord*) p), "invariant");
  assert(!_g1h->is_on_master_free_list(
                    _g1h->heap_region_containing((HeapWord*) p)), "invariant");

  oop obj = oopDesc::load_decode_heap_oop(p);
  if (_cm->verbose_high()) {
    gclog_or_tty->print_cr("[%d] we're looking at location "
                           "*"PTR_FORMAT" = "PTR_FORMAT,
                           _task->task_id(), p, (void*) obj);
  }
  _task->deal_with_reference(obj);
}

#endif // SHARE_VM_GC_IMPLEMENTATION_G1_G1OOPCLOSURES_INLINE_HPP
