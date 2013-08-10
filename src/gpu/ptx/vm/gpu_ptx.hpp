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

#ifndef GPU_PTX_HPP
#define GPU_PTX_HPP

/* 
 * Some useful macro definitions from publicly available cuda.h.
 * These definitions are for convenience.
 */
#define GRAAL_CUDA_SUCCESS                                   0
/**< Device shares a unified address space with the host */
#define GRAAL_CU_DEVICE_ATTRIBUTE_UNIFIED_ADDRESSING        41
#define GRAAL_CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR  75
#define GRAAL_CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MINOR  76
#define GRAAL_CU_JIT_MAX_REGISTERS                           0
#define GRAAL_CU_JIT_THREADS_PER_BLOCK                       1
#define GRAAL_CU_JIT_INFO_LOG_BUFFER                         3
#define GRAAL_CU_JIT_INFO_LOG_BUFFER_SIZE_BYTES              4
#define GRAAL_CUDA_ERROR_NO_BINARY_FOR_GPU                 209

/**
 * End of array terminator for the extra parameter to
 * ::cuLaunchKernel
 */
#define GRAAL_CU_LAUNCH_PARAM_END            ((void*)0x00)

/**
 * Indicator that the next value in the  extra parameter to
 * ::cuLaunchKernel will be a pointer to a buffer containing all kernel
 * parameters used for launching kernel f.  This buffer needs to
 * honor all alignment/padding requirements of the individual parameters.
 * If ::GRAAL_CU_LAUNCH_PARAM_BUFFER_SIZE is not also specified in the
 *  extra array, then ::GRAAL_CU_LAUNCH_PARAM_BUFFER_POINTER will have no
 * effect.
 */
#define GRAAL_CU_LAUNCH_PARAM_BUFFER_POINTER ((void*)0x01)

/**
 * Indicator that the next value in the  extra parameter to
 * ::cuLaunchKernel will be a pointer to a size_t which contains the
 * size of the buffer specified with ::GRAAL_CU_LAUNCH_PARAM_BUFFER_POINTER.
 * It is required that ::GRAAL_CU_LAUNCH_PARAM_BUFFER_POINTER also be specified
 * in the extra array if the value associated with
 * ::GRAAL_CU_LAUNCH_PARAM_BUFFER_SIZE is not zero.
 */
#define GRAAL_CU_LAUNCH_PARAM_BUFFER_SIZE    ((void*)0x02)

class Ptx {
  friend class gpu;

 protected:
  static bool probe_linkage();
  static bool initialize_gpu();
  static void * generate_kernel(unsigned char *code, int code_len, const char *name);
  static bool execute_kernel(address kernel, JavaCallArguments *);
  
private:
  typedef int (*cuda_cu_init_func_t)(unsigned int);
  typedef int (*cuda_cu_ctx_create_func_t)(void *, int, int);
  typedef int (*cuda_cu_ctx_detach_func_t)(int *);
  typedef int (*cuda_cu_ctx_synchronize_func_t)(int *);
  typedef int (*cuda_cu_device_get_count_func_t)(int *);
  typedef int (*cuda_cu_device_get_name_func_t)(char *, int, int);
  typedef int (*cuda_cu_device_get_func_t)(int *, int);
  typedef int (*cuda_cu_device_compute_capability_func_t)(int *, int *, int);
  typedef int (*cuda_cu_device_get_attribute_func_t)(int *, int, int);
  typedef int (*cuda_cu_launch_kernel_func_t)(void *,
                                              unsigned int, unsigned int, unsigned int,
                                              unsigned int, unsigned int, unsigned int,
                                              unsigned int, void *, void **, void **);
  typedef int (*cuda_cu_module_get_function_func_t)(void *, void *, const char *);
  typedef int (*cuda_cu_module_load_data_ex_func_t)(void *, void *, unsigned int, void *, void **);

  static cuda_cu_init_func_t                      _cuda_cu_init;
  static cuda_cu_ctx_create_func_t                _cuda_cu_ctx_create;
  static cuda_cu_ctx_detach_func_t                _cuda_cu_ctx_detach;
  static cuda_cu_ctx_synchronize_func_t           _cuda_cu_ctx_synchronize;
  static cuda_cu_device_get_count_func_t          _cuda_cu_device_get_count;
  static cuda_cu_device_get_name_func_t           _cuda_cu_device_get_name;
  static cuda_cu_device_get_func_t                _cuda_cu_device_get;
  static cuda_cu_device_compute_capability_func_t _cuda_cu_device_compute_capability; /* Deprecated as of CUDA 5.0 */
  static cuda_cu_device_get_attribute_func_t      _cuda_cu_device_get_attribute;
  static cuda_cu_launch_kernel_func_t             _cuda_cu_launch_kernel;
  static cuda_cu_module_get_function_func_t       _cuda_cu_module_get_function;
  static cuda_cu_module_load_data_ex_func_t       _cuda_cu_module_load_data_ex;

protected:
  static void * _device_context;
};
#endif // GPU_PTX_HPP
