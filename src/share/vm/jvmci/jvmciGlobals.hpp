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

#ifndef SHARE_VM_JVMCI_JVMCIGLOBALS_HPP
#define SHARE_VM_JVMCI_JVMCIGLOBALS_HPP

#include "runtime/globals.hpp"
#ifdef TARGET_ARCH_x86
# include "jvmciGlobals_x86.hpp"
#endif
#ifdef TARGET_ARCH_sparc
# include "jvmciGlobals_sparc.hpp"
#endif
#ifdef TARGET_ARCH_arm
# include "jvmciGlobals_arm.hpp"
#endif
#ifdef TARGET_ARCH_ppc
# include "jvmciGlobals_ppc.hpp"
#endif

//
// Defines all global flags used by the JVMCI compiler. Only flags that need
// to be accessible to the JVMCI C++ code should be defined here. All other
// JVMCI flags should be defined in JVMCIOptions.java.
//
#define JVMCI_FLAGS(develop, develop_pd, product, product_pd, notproduct)   \
                                                                            \
  product(bool, DebugJVMCI, true,                                           \
          "Enable JVMTI for the compiler thread")                           \
                                                                            \
  product(bool, UseJVMCIClassLoader, true,                                  \
          "Load JVMCI classes with separate class loader")                  \
                                                                            \
  COMPILERJVMCI_PRESENT(product(bool, BootstrapJVMCI, true,                 \
          "Bootstrap JVMCI before running Java main method"))               \
                                                                            \
  COMPILERJVMCI_PRESENT(product(bool, PrintBootstrap, true,                 \
          "Print JVMCI bootstrap progress and summary"))                    \
                                                                            \
  COMPILERJVMCI_PRESENT(product(intx, JVMCIThreads, 1,                      \
          "Force number of JVMCI compiler threads to use"))                 \
                                                                            \
  COMPILERJVMCI_PRESENT(product(intx, JVMCIHostThreads, 1,                  \
          "Force number of compiler threads for JVMCI host compiler"))      \
                                                                            \
  JVMCI_ONLY(product(bool, CodeInstallSafepointChecks, true,                \
          "Perform explicit safepoint checks while installing code"))       \
                                                                            \
  NOT_COMPILER2(product_pd(intx, MaxVectorSize,                                \
          "Max vector size in bytes, "                                      \
          "actual size could be less depending on elements type"))          \
                                                                            \
  product(intx, TraceJVMCI, 0,                                              \
          "Trace level for JVMCI")                                          \
                                                                            \
  product(intx, JVMCICounterSize, 0,                                        \
          "Reserved size for benchmark counters")                           \
                                                                            \
  product(bool, JVMCICountersExcludeCompiler, true,                         \
          "Exclude JVMCI compiler threads from benchmark counters")         \
                                                                            \
  product(bool, JVMCIDeferredInitBarriers, true,                            \
          "Defer write barriers of young objects")                          \
                                                                            \
  product(bool, JVMCIHProfEnabled, false,                                   \
          "Is Heap  Profiler enabled")                                      \
                                                                            \
  product(bool, JVMCICompileWithC1Only, true,                               \
          "Only compile JVMCI classes with C1")                             \
                                                                            \
  product(bool, JVMCICompileAppFirst, false,                                \
          "Prioritize application compilations over JVMCI compilations")    \
                                                                            \
  develop(bool, JVMCIUseFastLocking, true,                                  \
          "Use fast inlined locking code")                                  \
                                                                            \
  develop(bool, JVMCIUseFastNewTypeArray, true,                             \
          "Use fast inlined type array allocation")                         \
                                                                            \
  develop(bool, JVMCIUseFastNewObjectArray, true,                           \
          "Use fast inlined object array allocation")                       \
                                                                            \
  product(intx, JVMCINMethodSizeLimit, (80*K)*wordSize,                     \
          "Maximum size of a compiled method.")                             \
                                                                            \
  notproduct(bool, JVMCIPrintSimpleStubs, false,                            \
          "Print simple JVMCI stubs")                                       \
                                                                            \
  develop(bool, TraceUncollectedSpeculations, false,                        \
          "Print message when a failed speculation was not collected")      \


// Read default values for JVMCI globals

JVMCI_FLAGS(DECLARE_DEVELOPER_FLAG, DECLARE_PD_DEVELOPER_FLAG, DECLARE_PRODUCT_FLAG, DECLARE_PD_PRODUCT_FLAG, DECLARE_NOTPRODUCT_FLAG)

#endif // SHARE_VM_JVMCI_JVMCIGLOBALS_HPP
