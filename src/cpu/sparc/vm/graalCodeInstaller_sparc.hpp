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
 */

#ifndef CPU_SPARC_VM_CODEINSTALLER_SPARC_HPP
#define CPU_SPARC_VM_CODEINSTALLER_SPARC_HPP

#include "graal/graalCompiler.hpp"
#include "graal/graalCompilerToVM.hpp"
#include "graal/graalJavaAccess.hpp"

inline jint CodeInstaller::pd_next_offset(NativeInstruction* inst, jint pc_offset, oop method) {
  if (inst->is_call() || inst->is_jump()) {
    return pc_offset + NativeCall::instruction_size;
  } else if (inst->is_sethi()) {
    return pc_offset + NativeFarCall::instruction_size;
  } else {
    fatal("unsupported type of instruction for call site");
    return 0;
  }
}

inline void CodeInstaller::pd_site_DataPatch(int pc_offset, oop site) {
  oop constant = CompilationResult_DataPatch::constant(site);
  int alignment = CompilationResult_DataPatch::alignment(site);
  bool inlined = CompilationResult_DataPatch::inlined(site) == JNI_TRUE;

  oop kind = Constant::kind(constant);
  char typeChar = Kind::typeChar(kind);

  address pc = _instructions->start() + pc_offset;

  switch (typeChar) {
    case 'z':
    case 'b':
    case 's':
    case 'c':
    case 'i':
      fatal("int-sized values not expected in DataPatch");
      break;
    case 'f':
    case 'j':
    case 'd': {
      if (inlined) {
        NativeMovConstReg* move = nativeMovConstReg_at(pc);
        uint64_t value = Constant::primitive(constant);
        move->set_data(value);
      } else {
        int size = _constants->size();
        if (alignment > 0) {
          guarantee(alignment <= _constants->alignment(), "Alignment inside constants section is restricted by alignment of section begin");
          size = align_size_up(size, alignment);
        }
        // we don't care if this is a long/double/etc., the primitive field contains the right bits
        address dest = _constants->start() + size;
        _constants->set_end(dest);
        uint64_t value = Constant::primitive(constant);
        _constants->emit_int64(value);

        NativeMovRegMem* load = nativeMovRegMem_at(pc);
        int disp = _constants_size + pc_offset - size - BytesPerInstWord;
        load->set_offset(-disp);
      }
      break;
    }
    case 'a': {
      int size = _constants->size();
      if (alignment > 0) {
        guarantee(alignment <= _constants->alignment(), "Alignment inside constants section is restricted by alignment of section begin");
        size = align_size_up(size, alignment);
      }
      address dest = _constants->start() + size;
      _constants->set_end(dest);
      Handle obj = Constant::object(constant);
      jobject value = JNIHandles::make_local(obj());
      _constants->emit_address((address) value);

      NativeMovRegMem* load = nativeMovRegMem_at(pc);
      int disp = _constants_size + pc_offset - size - BytesPerInstWord;
      load->set_offset(-disp);

      int oop_index = _oop_recorder->find_index(value);
      _constants->relocate(dest, oop_Relocation::spec(oop_index));
      break;
    }
    default:
      fatal(err_msg("unexpected Kind (%d) in DataPatch", typeChar));
      break;
  }
}

inline void CodeInstaller::pd_relocate_CodeBlob(CodeBlob* cb, NativeInstruction* inst) {
  fatal("CodeInstaller::pd_relocate_CodeBlob - sparc unimp");
}

inline void CodeInstaller::pd_relocate_ForeignCall(NativeInstruction* inst, jlong foreign_call_destination) {
  address pc = (address) inst;
  if (inst->is_call()) {
    NativeCall* call = nativeCall_at(pc);
    call->set_destination((address) foreign_call_destination);
    _instructions->relocate(call->instruction_address(), runtime_call_Relocation::spec());
  } else if (inst->is_sethi()) {
    NativeJump* jump = nativeJump_at(pc);
    jump->set_jump_destination((address) foreign_call_destination);
    _instructions->relocate(jump->instruction_address(), runtime_call_Relocation::spec());
  } else {
    fatal(err_msg("unknown call or jump instruction at %p", pc));
  }
  TRACE_graal_3("relocating (foreign call) at %p", inst);
}

inline void CodeInstaller::pd_relocate_JavaMethod(oop hotspot_method, jint pc_offset) {
#ifdef ASSERT
  Method* method = NULL;
  // we need to check, this might also be an unresolved method
  if (hotspot_method->is_a(HotSpotResolvedJavaMethod::klass())) {
    method = getMethodFromHotSpotMethod(hotspot_method);
  }
#endif
  switch (_next_call_type) {
    case MARK_INLINE_INVOKE:
      break;
    case MARK_INVOKEVIRTUAL:
    case MARK_INVOKEINTERFACE: {
      assert(method == NULL || !method->is_static(), "cannot call static method with invokeinterface");
      NativeCall* call = nativeCall_at(_instructions->start() + pc_offset);
      call->set_destination(SharedRuntime::get_resolve_virtual_call_stub());
      _instructions->relocate(call->instruction_address(), virtual_call_Relocation::spec(_invoke_mark_pc));
      break;
    }
    case MARK_INVOKESTATIC: {
      assert(method == NULL || method->is_static(), "cannot call non-static method with invokestatic");
      NativeCall* call = nativeCall_at(_instructions->start() + pc_offset);
      call->set_destination(SharedRuntime::get_resolve_static_call_stub());
      _instructions->relocate(call->instruction_address(), relocInfo::static_call_type);
      break;
    }
    case MARK_INVOKESPECIAL: {
      assert(method == NULL || !method->is_static(), "cannot call static method with invokespecial");
      NativeCall* call = nativeCall_at(_instructions->start() + pc_offset);
      call->set_destination(SharedRuntime::get_resolve_opt_virtual_call_stub());
      _instructions->relocate(call->instruction_address(), relocInfo::opt_virtual_call_type);
      break;
    }
    default:
      fatal("invalid _next_call_type value");
      break;
  }
}

inline int32_t* CodeInstaller::pd_locate_operand(address instruction) {
  fatal("CodeInstaller::pd_locate_operand - sparc unimp");
  return (int32_t*)0;
}

#endif // CPU_SPARC_VM_CODEINSTALLER_SPARC_HPP
