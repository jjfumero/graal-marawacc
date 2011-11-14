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

#include "precompiled.hpp"
#include "graal/graalVmIds.hpp"
#include "ci/ciUtilities.hpp"

// VmIds implementation

GrowableArray<address>* VmIds::_stubs = NULL;


void VmIds::initializeObjects() {
  if (_stubs == NULL) {
    assert(_localHandles == NULL, "inconsistent state");
    _stubs = new (ResourceObj::C_HEAP) GrowableArray<address> (64, true);
  }
  assert(_localHandles->length() == 0, "invalid state");
}

jlong VmIds::addStub(address stub) {
  assert(!_stubs->contains(stub), "duplicate stub");
  return _stubs->append(stub) | STUB;
}

address VmIds::getStub(jlong id) {
  assert((id & TYPE_MASK) == STUB, "wrong id type, STUB expected");
  assert((id & ~TYPE_MASK) >= 0 && (id & ~TYPE_MASK) < _stubs->length(), "STUB index out of bounds");
  return _stubs->at(id & ~TYPE_MASK);
}

