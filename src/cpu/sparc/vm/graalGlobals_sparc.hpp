/*
 * Copyright (c) 2000, 2012, Oracle and/or its affiliates. All rights reserved.
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

#ifndef CPU_SPARC_VM_GRAALGLOBALS_SPARC_HPP
#define CPU_SPARC_VM_GRAALGLOBALS_SPARC_HPP

#include "utilities/globalDefinitions.hpp"
#include "utilities/macros.hpp"

// Sets the default values for platform dependent flags used by the Graal compiler.
// (see graalGlobals.hpp)

#if !defined(COMPILER1) && !defined(COMPILER2)
define_pd_global(bool, BackgroundCompilation,        true );
define_pd_global(bool, UseTLAB,                      true );
define_pd_global(bool, ResizeTLAB,                   true );
define_pd_global(bool, InlineIntrinsics,             true );
define_pd_global(bool, PreferInterpreterNativeStubs, false);
define_pd_global(bool, TieredCompilation,            false);
define_pd_global(intx, BackEdgeThreshold,            100000);

define_pd_global(intx, OnStackReplacePercentage,     933  );
define_pd_global(intx, FreqInlineSize,               325  );
define_pd_global(intx, NewSizeThreadIncrease,        4*K  );
define_pd_global(uintx,MetaspaceSize,                12*M );
define_pd_global(bool, NeverActAsServerClassMachine, false);
define_pd_global(uint64_t,MaxRAM,                    1ULL*G);
define_pd_global(bool, CICompileOSR,                 true );
define_pd_global(bool, ProfileTraps,                 true );
define_pd_global(bool, UseOnStackReplacement,        true );
define_pd_global(intx, CompileThreshold,             10000);
define_pd_global(intx, InitialCodeCacheSize,         16*M );
define_pd_global(intx, ReservedCodeCacheSize,        64*M );
define_pd_global(bool, ProfileInterpreter,           true );
define_pd_global(intx, CodeCacheExpansionSize,       64*K );
define_pd_global(uintx,CodeCacheMinBlockLength,      4);
define_pd_global(intx, TypeProfileWidth,             8);
define_pd_global(intx, MethodProfileWidth,           4);
#endif

#endif // CPU_SPARC_VM_GRAALGLOBALS_SPARC_HPP
