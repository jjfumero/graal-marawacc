/*
 * Copyright (c) 2002, 2010, Oracle and/or its affiliates. All rights reserved.
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

#include "precompiled.hpp"
#ifndef SERIALGC
#include "gc_implementation/concurrentMarkSweep/freeChunk.hpp"
#endif // SERIALGC
#include "memory/freeBlockDictionary.hpp"
#ifdef TARGET_OS_FAMILY_linux
# include "thread_linux.inline.hpp"
#endif
#ifdef TARGET_OS_FAMILY_solaris
# include "thread_solaris.inline.hpp"
#endif
#ifdef TARGET_OS_FAMILY_windows
# include "thread_windows.inline.hpp"
#endif
#ifdef TARGET_OS_FAMILY_bsd
# include "thread_bsd.inline.hpp"
#endif

#ifndef PRODUCT
template <class Chunk> Mutex* FreeBlockDictionary<Chunk>::par_lock() const {
  return _lock;
}

template <class Chunk> void FreeBlockDictionary<Chunk>::set_par_lock(Mutex* lock) {
  _lock = lock;
}

template <class Chunk> void FreeBlockDictionary<Chunk>::verify_par_locked() const {
#ifdef ASSERT
  if (ParallelGCThreads > 0) {
    Thread* my_thread = Thread::current();
    if (my_thread->is_GC_task_thread()) {
      assert(par_lock() != NULL, "Should be using locking?");
      assert_lock_strong(par_lock());
    }
  }
#endif // ASSERT
}
#endif

#ifndef SERIALGC
// Explicitly instantiate for FreeChunk
template class FreeBlockDictionary<FreeChunk>;
#endif // SERIALGC
