/*
 * Copyright 2000-2010 Sun Microsystems, Inc.  All Rights Reserved.
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
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 *
 */

# include "incls/_precompiled.incl"
# include "incls/_c1x_CodeInstaller.cpp.incl"


// TODO this should be handled in a more robust way - not hard coded...
Register CPU_REGS[] = { rax, rcx, rdx, rbx, rsp, rbp, rsi, rdi, r8, r9, r10, r11, r12, r13, r14, r15 };
bool OOP_ALLOWED[] = {true, true, true, true, false, false, true, true, true, true, false, true, true, true, true, true};
const static int NUM_CPU_REGS = sizeof(CPU_REGS) / sizeof(Register);
XMMRegister XMM_REGS[] = { xmm0, xmm1, xmm2, xmm3, xmm4, xmm5, xmm6, xmm7, xmm8, xmm9, xmm10, xmm11, xmm12, xmm13, xmm14, xmm15 };
const static int NUM_XMM_REGS = sizeof(XMM_REGS) / sizeof(XMMRegister);
const static int NUM_REGS = NUM_CPU_REGS + NUM_XMM_REGS;
const static jlong NO_REF_MAP = 0x8000000000000000L;

// convert c1x register indices (as used in oop maps) to hotspot registers
VMReg get_hotspot_reg(jint c1x_reg) {

  assert(c1x_reg >= 0 && c1x_reg < NUM_REGS, "invalid register number");
  if (c1x_reg < NUM_CPU_REGS) {
    return CPU_REGS[c1x_reg]->as_VMReg();
  } else {
    return XMM_REGS[c1x_reg - NUM_CPU_REGS]->as_VMReg();
  }
}

static bool is_bit_set(oop bit_map, int i) {
  const int MapWordBits = 64;
  if (i < MapWordBits) {
    jlong low = CiBitMap::low(bit_map);
    return (low & (1L << i)) != 0;
  } else {
    jint extra_idx = (i - MapWordBits) / MapWordBits;
    arrayOop extra = (arrayOop) CiBitMap::extra(bit_map);
    assert(extra_idx >= 0 && extra_idx < extra->length(), "unexpected index");
    jlong word = ((jlong*) extra->base(T_LONG))[extra_idx];
    return (word & (1L << (i % MapWordBits))) != 0;
  }
}

// creates a hotspot oop map out of the byte arrays provided by CiDebugInfo
static OopMap* create_oop_map(jint frame_size, jint parameter_count, oop debug_info) {
  OopMap* map = new OopMap(frame_size, parameter_count);
  oop register_map = (oop) CiDebugInfo::registerRefMap(debug_info);
  oop frame_map = (oop) CiDebugInfo::frameRefMap(debug_info);

  if (register_map != NULL) {
    assert(CiBitMap::size(register_map) == (unsigned) NUM_CPU_REGS, "unexpected register_map length");
    for (jint i = 0; i < NUM_CPU_REGS; i++) {
      bool is_oop = is_bit_set(register_map, i);
      VMReg reg = get_hotspot_reg(i);
      if (is_oop) {
        assert(OOP_ALLOWED[i], "this register may never be an oop, register map misaligned?");
        map->set_oop(reg);
      } else {
        map->set_value(reg);
      }
    }
  }

  if (frame_size > 0) {
    assert(CiBitMap::size(frame_map) == frame_size / HeapWordSize, "unexpected frame_map length");

    for (jint i = 0; i < frame_size / HeapWordSize; i++) {
      bool is_oop = is_bit_set(frame_map, i);
      // hotspot stack slots are 4 bytes
      VMReg reg = VMRegImpl::stack2reg(i * 2);
      if (is_oop) {
        map->set_oop(reg);
      } else {
        map->set_value(reg);
      }
    }
  } else {
    assert(frame_map == NULL || CiBitMap::size(frame_map) == 0, "cannot have frame_map for frames with size 0");
  }

  return map;
}

// TODO: finish this - c1x doesn't provide any scope values at the moment
static ScopeValue* get_hotspot_value(oop value, int frame_size) {
  if (value == CiValue::IllegalValue()) {
    return new LocationValue(Location::new_stk_loc(Location::invalid, 0));
  }

  BasicType type = C1XCompiler::kindToBasicType(CiKind::typeChar(CiValue::kind(value)));
  Location::Type locationType = Location::normal;
  if (type == T_OBJECT || type == T_ARRAY) locationType = Location::oop;
  if (value->is_a(CiRegisterValue::klass())) {
    jint number = CiRegister::number(CiRegisterValue::reg(value));
    if (number < 16) {
      return new LocationValue(Location::new_reg_loc(locationType, as_Register(number)->as_VMReg()));
    } else {
      return new LocationValue(Location::new_reg_loc(locationType, as_XMMRegister(number - 16)->as_VMReg()));
    }
  } else if (value->is_a(CiStackSlot::klass())) {
    jint index = CiStackSlot::index(value);
    if (index >= 0) {
      return new LocationValue(Location::new_stk_loc(locationType, index * HeapWordSize));
    } else {
      int frame_size_bytes = frame_size + 2 * HeapWordSize;
      return new LocationValue(Location::new_stk_loc(locationType, -(index * HeapWordSize) + frame_size_bytes));
    }
  } else if (value->is_a(CiConstant::klass())){
    oop obj = CiConstant::object(value);
    jlong prim = CiConstant::primitive(value);
    if (type == T_INT || type == T_FLOAT || type == T_SHORT || type == T_CHAR || type == T_BOOLEAN || type == T_BYTE) {
      return new ConstantIntValue(*(jint*)&prim);
    } else if (type == T_LONG || type == T_DOUBLE) {
      return new ConstantLongValue(prim);
    } else if (type == T_OBJECT) {
      oop obj = CiConstant::object(value);
      if (obj == NULL) {
        return new ConstantOopWriteValue(NULL);
      } else {
        obj->print();
        ShouldNotReachHere();
      }
    } else if (type == T_ADDRESS) {
      return new ConstantLongValue(prim);
    }
    tty->print("%i", type);
    ShouldNotReachHere();
  } else {
    value->klass()->print();
    value->print();
    ShouldNotReachHere();
  }
}

// constructor used to create a method
CodeInstaller::CodeInstaller(oop target_method) {
  ciMethod *ciMethodObject = NULL;
  {
    No_Safepoint_Verifier no_safepoint;
    _env = CURRENT_ENV;

    initialize_fields(target_method);
    assert(_hotspot_method != NULL && _name == NULL, "installMethod needs NON-NULL method and NULL name");
    assert(_hotspot_method->is_a(HotSpotMethodResolved::klass()), "installMethod needs a HotSpotMethodResolved");

    methodOop method = VmIds::get<methodOop>(HotSpotMethodResolved::vmId(_hotspot_method));
    ciMethodObject = (ciMethod *) _env->get_object(method);
    _parameter_count = method->size_of_parameters();
  }

  // (very) conservative estimate: each site needs a relocation
  //CodeBuffer buffer("temp c1x method", _total_size, _sites->length() * relocInfo::length_limit);
  CodeBuffer buffer(CompilerThread::current()->get_buffer_blob());
  initialize_buffer(buffer);
  process_exception_handlers();

  int stack_slots = (_frame_size / HeapWordSize) + 2; // conversion to words, need to add two slots for ret address and frame pointer
  ThreadToNativeFromVM t((JavaThread*) Thread::current());
  _env->register_method(ciMethodObject, -1, &_offsets, 0, &buffer, stack_slots, _debug_recorder->_oopmaps, &_exception_handler_table,
      &_implicit_exception_table, C1XCompiler::instance(), _env->comp_level(), false, false);

}

// constructor used to create a stub
CodeInstaller::CodeInstaller(oop target_method, jlong& id) {
  No_Safepoint_Verifier no_safepoint;
  _env = CURRENT_ENV;

  initialize_fields(target_method);
  assert(_hotspot_method == NULL && _name != NULL, "installMethod needs NON-NULL name and NULL method");

  // (very) conservative estimate: each site needs a relocation
  CodeBuffer buffer(CompilerThread::current()->get_buffer_blob());
  initialize_buffer(buffer);

  const char* cname = java_lang_String::as_utf8_string(_name);
  BufferBlob* blob = BufferBlob::create(strdup(cname), &buffer); // this is leaking strings... but only a limited number of stubs will be created
  IF_TRACE_C1X_3 Disassembler::decode((CodeBlob*) blob);
  id = VmIds::addStub(blob->code_begin());
}

void CodeInstaller::initialize_fields(oop target_method) {
  _citarget_method = HotSpotTargetMethod::targetMethod(target_method);
  _hotspot_method = HotSpotTargetMethod::method(target_method);
  _name = HotSpotTargetMethod::name(target_method);
  _sites = (arrayOop) HotSpotTargetMethod::sites(target_method);
  _exception_handlers = (arrayOop) HotSpotTargetMethod::exceptionHandlers(target_method);

  _code = (arrayOop) CiTargetMethod::targetCode(_citarget_method);
  _code_size = CiTargetMethod::targetCodeSize(_citarget_method);
  _frame_size = CiTargetMethod::frameSize(_citarget_method);

  // (very) conservative estimate: each site needs a constant section entry
  _constants_size = _sites->length() * BytesPerLong;
  _total_size = align_size_up(_code_size, HeapWordSize) + _constants_size;

  _next_call_type = MARK_INVOKE_INVALID;
}

// perform data and call relocation on the CodeBuffer
void CodeInstaller::initialize_buffer(CodeBuffer& buffer) {
  int locs_buffer_size = _sites->length() * (relocInfo::length_limit + sizeof(relocInfo));
  char* locs_buffer = NEW_RESOURCE_ARRAY(char, locs_buffer_size);
  buffer.insts()->initialize_shared_locs((relocInfo*)locs_buffer, locs_buffer_size / sizeof(relocInfo));
  buffer.initialize_stubs_size(256);
  buffer.initialize_consts_size(_constants_size);

  _oop_recorder = new OopRecorder(_env->arena());
  _env->set_oop_recorder(_oop_recorder);
  _debug_recorder = new DebugInformationRecorder(_env->oop_recorder());
  _debug_recorder->set_oopmaps(new OopMapSet());
  _dependencies = new Dependencies(_env);

  _env->set_oop_recorder(_oop_recorder);
  _env->set_debug_info(_debug_recorder);
  _env->set_dependencies(_dependencies);
  buffer.initialize_oop_recorder(_oop_recorder);

  _instructions = buffer.insts();
  _constants = buffer.consts();

  // copy the code into the newly created CodeBuffer
  memcpy(_instructions->start(), _code->base(T_BYTE), _code_size);
  _instructions->set_end(_instructions->start() + _code_size);

  oop* sites = (oop*) _sites->base(T_OBJECT);
  for (int i = 0; i < _sites->length(); i++) {
    oop site = sites[i];
    jint pc_offset = CiTargetMethod_Site::pcOffset(site);

    if (site->is_a(CiTargetMethod_Safepoint::klass())) {
      TRACE_C1X_4("safepoint at %i", pc_offset);
      site_Safepoint(buffer, pc_offset, site);
    } else if (site->is_a(CiTargetMethod_Call::klass())) {
      TRACE_C1X_4("call at %i", pc_offset);
      site_Call(buffer, pc_offset, site);
    } else if (site->is_a(CiTargetMethod_DataPatch::klass())) {
      TRACE_C1X_4("datapatch at %i", pc_offset);
      site_DataPatch(buffer, pc_offset, site);
    } else if (site->is_a(CiTargetMethod_Mark::klass())) {
      TRACE_C1X_4("mark at %i", pc_offset);
      site_Mark(buffer, pc_offset, site);
    } else {
      fatal("unexpected Site subclass");
    }
  }
}

void CodeInstaller::process_exception_handlers() {
  // allocate some arrays for use by the collection code.
  const int num_handlers = 5;
  GrowableArray<intptr_t>* bcis = new GrowableArray<intptr_t> (num_handlers);
  GrowableArray<intptr_t>* scope_depths = new GrowableArray<intptr_t> (num_handlers);
  GrowableArray<intptr_t>* pcos = new GrowableArray<intptr_t> (num_handlers);

  if (_exception_handlers != NULL) {
    oop* exception_handlers = (oop*) _exception_handlers->base(T_OBJECT);
    for (int i = 0; i < _exception_handlers->length(); i++) {
      jint pc_offset = CiTargetMethod_Site::pcOffset(exception_handlers[i]);
      int start = i;
      while ((i + 1) < _exception_handlers->length() && CiTargetMethod_Site::pcOffset(exception_handlers[i + 1]) == pc_offset)
        i++;

      // empty the arrays
      bcis->trunc_to(0);
      scope_depths->trunc_to(0);
      pcos->trunc_to(0);

      for (int j = start; j <= i; j++) {
        oop exc = exception_handlers[j];
        jint handler_offset = CiTargetMethod_ExceptionHandler::handlerPos(exc);
        jint handler_bci = CiTargetMethod_ExceptionHandler::handlerBci(exc);
        jint bci = CiTargetMethod_ExceptionHandler::bci(exc);
        jint scope_level = CiTargetMethod_ExceptionHandler::scopeLevel(exc);
        Handle handler_type = CiTargetMethod_ExceptionHandler::exceptionType(exc);

        assert(handler_offset != -1, "must have been generated");

        int e = bcis->find(handler_bci);
        if (e >= 0 && scope_depths->at(e) == scope_level) {
          // two different handlers are declared to dispatch to the same
          // catch bci.  During parsing we created edges for each
          // handler but we really only need one.  The exception handler
          // table will also get unhappy if we try to declare both since
          // it's nonsensical.  Just skip this handler.
          continue;
        }

        bcis->append(handler_bci);
        if (handler_bci == -1) {
          // insert a wildcard handler at scope depth 0 so that the
          // exception lookup logic with find it.
          scope_depths->append(0);
        } else {
          scope_depths->append(scope_level);
        }
        pcos->append(handler_offset);

        // stop processing once we hit a catch any
        //        if (handler->is_catch_all()) {
        //          assert(i == handlers->length() - 1, "catch all must be last handler");
        //        }

      }
      _exception_handler_table.add_subtable(pc_offset, bcis, scope_depths, pcos);
    }
  }

}

void CodeInstaller::record_scope(jint pc_offset, oop code_pos) {
  oop caller_pos = CiCodePos::caller(code_pos);
  if (caller_pos != NULL) {
    record_scope(pc_offset, caller_pos);
  }
  oop frame = NULL;
  if (code_pos->klass()->klass_part()->name() == vmSymbols::com_sun_cri_ci_CiDebugInfo_Frame()) {
    frame = code_pos;
  }

  oop hotspot_method = CiCodePos::method(code_pos);
  assert(hotspot_method != NULL && hotspot_method->is_a(HotSpotMethodResolved::klass()), "unexpected hotspot method");
  methodOop method = VmIds::get<methodOop>(HotSpotMethodResolved::vmId(hotspot_method));
  ciMethod *cimethod = (ciMethod *) _env->get_object(method);
  jint bci = CiCodePos::bci(code_pos);
  bool reexecute;
  if (bci == -1) {
     reexecute = false;
  } else {
    Bytecodes::Code code = Bytecodes::java_code_at(method->bcp_from(bci));
    reexecute = Interpreter::bytecode_should_reexecute(code);
  }

  if (frame != NULL) {
    jint local_count = CiDebugInfo_Frame::numLocals(frame);
    jint expression_count = CiDebugInfo_Frame::numStack(frame);
    jint monitor_count = CiDebugInfo_Frame::numLocks(frame);
    arrayOop values = (arrayOop) CiDebugInfo_Frame::values(frame);

    assert(local_count + expression_count + monitor_count == values->length(), "unexpected values length");

    GrowableArray<ScopeValue*>* locals = new GrowableArray<ScopeValue*> ();
    GrowableArray<ScopeValue*>* expressions = new GrowableArray<ScopeValue*> ();
    GrowableArray<MonitorValue*>* monitors = new GrowableArray<MonitorValue*> ();

    if (TraceC1X >= 2) {
      tty->print_cr("Scope at bci %d with %d values", bci, values->length());
      tty->print_cr("%d locals %d expressions, %d monitors", local_count, expression_count, monitor_count);
    }

    for (jint i = 0; i < values->length(); i++) {
      ScopeValue* value = get_hotspot_value(((oop*) values->base(T_OBJECT))[i], _frame_size);

      if (i < local_count) {
        locals->append(value);
      } else if (i < local_count + expression_count) {
        expressions->append(value);
      } else {
        assert(value->is_location(), "invalid monitor location");
        LocationValue* loc = (LocationValue*)value;
        int monitor_offset = loc->location().stack_offset();
        LocationValue* obj = new LocationValue(Location::new_stk_loc(Location::oop, monitor_offset + BasicObjectLock::obj_offset_in_bytes()));
        monitors->append(new MonitorValue(obj, Location::new_stk_loc(Location::normal, monitor_offset  + BasicObjectLock::lock_offset_in_bytes())));
      }
    }
    DebugToken* locals_token = _debug_recorder->create_scope_values(locals);
    DebugToken* expressions_token = _debug_recorder->create_scope_values(expressions);
    DebugToken* monitors_token = _debug_recorder->create_monitor_values(monitors);

    _debug_recorder->describe_scope(pc_offset, cimethod, bci, reexecute, false, false, locals_token, expressions_token, monitors_token);
  } else {
    _debug_recorder->describe_scope(pc_offset, cimethod, bci, reexecute, false, false, NULL, NULL, NULL);
  }
}

void CodeInstaller::site_Safepoint(CodeBuffer& buffer, jint pc_offset, oop site) {
  oop debug_info = CiTargetMethod_Safepoint::debugInfo(site);
  assert(debug_info != NULL, "debug info expected");

  // address instruction = _instructions->start() + pc_offset;
  // jint next_pc_offset = Assembler::locate_next_instruction(instruction) - _instructions->start();
  _debug_recorder->add_safepoint(pc_offset, create_oop_map(_frame_size, _parameter_count, debug_info));

  oop code_pos = CiDebugInfo::codePos(debug_info);
  record_scope(pc_offset, code_pos);

  _debug_recorder->end_safepoint(pc_offset);
}

void CodeInstaller::site_Call(CodeBuffer& buffer, jint pc_offset, oop site) {
  oop runtime_call = CiTargetMethod_Call::runtimeCall(site);
  oop hotspot_method = CiTargetMethod_Call::method(site);
  oop symbol = CiTargetMethod_Call::symbol(site);
  oop global_stub = CiTargetMethod_Call::globalStubID(site);

  oop debug_info = CiTargetMethod_Call::debugInfo(site);

  assert((runtime_call ? 1 : 0) + (hotspot_method ? 1 : 0) + (symbol ? 1 : 0) + (global_stub ? 1 : 0) == 1, "Call site needs exactly one type");

  assert(NativeCall::instruction_size == (int)NativeJump::instruction_size, "unexpected size)");
  jint next_pc_offset = pc_offset + NativeCall::instruction_size;

  if (debug_info != NULL) {
    _debug_recorder->add_safepoint(next_pc_offset, create_oop_map(_frame_size, _parameter_count, debug_info));
    oop code_pos = CiDebugInfo::codePos(debug_info);
    record_scope(next_pc_offset, code_pos);
  }

  if (runtime_call != NULL) {
    NativeCall* call = nativeCall_at(_instructions->start() + pc_offset);
    if (runtime_call == CiRuntimeCall::Debug()) {
      TRACE_C1X_3("CiRuntimeCall::Debug()");
    } else if (runtime_call == CiRuntimeCall::UnwindException()) {
      call->set_destination(Runtime1::entry_for(Runtime1::c1x_unwind_exception_call_id));
      _instructions->relocate(call->instruction_address(), runtime_call_Relocation::spec(), Assembler::call32_operand);
      TRACE_C1X_3("CiRuntimeCall::UnwindException()");
    } else if (runtime_call == CiRuntimeCall::HandleException()) {
      call->set_destination(Runtime1::entry_for(Runtime1::c1x_handle_exception_id));
      _instructions->relocate(call->instruction_address(), runtime_call_Relocation::spec(), Assembler::call32_operand);
      TRACE_C1X_3("CiRuntimeCall::HandleException()");
    } else if (runtime_call == CiRuntimeCall::JavaTimeMillis()) {
      call->set_destination((address)os::javaTimeMillis);
      _instructions->relocate(call->instruction_address(), runtime_call_Relocation::spec(), Assembler::call32_operand);
      TRACE_C1X_3("CiRuntimeCall::JavaTimeMillis()");
    } else if (runtime_call == CiRuntimeCall::JavaTimeNanos()) {
      call->set_destination((address)os::javaTimeNanos);
      _instructions->relocate(call->instruction_address(), runtime_call_Relocation::spec(), Assembler::call32_operand);
      TRACE_C1X_3("CiRuntimeCall::JavaTimeNanos()");
    } else if (runtime_call == CiRuntimeCall::ArithmeticFrem()) {
      call->set_destination(Runtime1::entry_for(Runtime1::c1x_arithmetic_frem_id));
      _instructions->relocate(call->instruction_address(), runtime_call_Relocation::spec(), Assembler::call32_operand);
      TRACE_C1X_3("CiRuntimeCall::ArithmeticFrem()");
    } else if (runtime_call == CiRuntimeCall::ArithmeticDrem()) {
      call->set_destination(Runtime1::entry_for(Runtime1::c1x_arithmetic_drem_id));
      _instructions->relocate(call->instruction_address(), runtime_call_Relocation::spec(), Assembler::call32_operand);
      TRACE_C1X_3("CiRuntimeCall::ArithmeticDrem()");
    } else {
      TRACE_C1X_1("runtime_call not implemented: ");
      IF_TRACE_C1X_1 runtime_call->print();
    }
  } else if (global_stub != NULL) {
    NativeInstruction* inst = nativeInstruction_at(_instructions->start() + pc_offset);
    assert(java_lang_boxing_object::is_instance(global_stub, T_LONG), "global_stub needs to be of type Long");

    if (inst->is_call()) {
      nativeCall_at((address)inst)->set_destination(VmIds::getStub(global_stub));
    } else {
      nativeJump_at((address)inst)->set_jump_destination(VmIds::getStub(global_stub));
    }
    _instructions->relocate((address)inst, runtime_call_Relocation::spec(), Assembler::call32_operand);
    TRACE_C1X_3("relocating (stub)  at %016x", inst);
  } else if (symbol != NULL) {
    fatal("symbol");
  } else { // method != NULL
    NativeCall* call = nativeCall_at(_instructions->start() + pc_offset);
    assert(hotspot_method != NULL, "unexpected RiMethod");
    assert(debug_info != NULL, "debug info expected");

    methodOop method = NULL;
    if (hotspot_method->is_a(HotSpotMethodResolved::klass())) method = VmIds::get<methodOop>(HotSpotMethodResolved::vmId(hotspot_method));

    assert(debug_info != NULL, "debug info expected");

    TRACE_C1X_3("method call");
    switch (_next_call_type) {
      case MARK_INVOKEVIRTUAL:
      case MARK_INVOKEINTERFACE: {
        assert(method == NULL || !method->is_static(), "cannot call static method with invokeinterface");

        call->set_destination(SharedRuntime::get_resolve_virtual_call_stub());
        _instructions->relocate(call->instruction_address(), virtual_call_Relocation::spec(_invoke_mark_pc), Assembler::call32_operand);
        break;
      }
      case MARK_INVOKESTATIC: {
        assert(method == NULL || method->is_static(), "cannot call non-static method with invokestatic");
        call->set_destination(SharedRuntime::get_resolve_static_call_stub());
        _instructions->relocate(call->instruction_address(), relocInfo::static_call_type, Assembler::call32_operand);
        break;
      }
      case MARK_INVOKESPECIAL: {
        assert(method == NULL || !method->is_static(), "cannot call static method with invokespecial");

        call->set_destination(SharedRuntime::get_resolve_opt_virtual_call_stub());
        _instructions->relocate(call->instruction_address(), relocInfo::opt_virtual_call_type, Assembler::call32_operand);
        break;
      }
      case MARK_INVOKE_INVALID:
      default:
        fatal("invalid _next_call_type value");
        break;
    }
  }
  _next_call_type = MARK_INVOKE_INVALID;
  if (debug_info != NULL) {
    _debug_recorder->end_safepoint(next_pc_offset);
  }
}

void CodeInstaller::site_DataPatch(CodeBuffer& buffer, jint pc_offset, oop site) {
  oop constant = CiTargetMethod_DataPatch::constant(site);
  oop kind = CiConstant::kind(constant);

  address instruction = _instructions->start() + pc_offset;

  switch (CiKind::typeChar(kind)) {
    case 'z':
    case 'b':
    case 's':
    case 'c':
    case 'i':
      fatal("int-sized values not expected in DataPatch")
      ;
      break;
    case 'f':
    case 'l':
    case 'd': {
      address operand = Assembler::locate_operand(instruction, Assembler::disp32_operand);
      address next_instruction = Assembler::locate_next_instruction(instruction);
      // we don't care if this is a long/double/etc., the primitive field contains the right bits
      address dest = _constants->end();
      *(jlong*) dest = CiConstant::primitive(constant);
      _constants->set_end(dest + BytesPerLong);

      long disp = dest - next_instruction;
      assert(disp == (jint) disp, "disp doesn't fit in 32 bits");
      *((jint*) operand) = (jint) disp;

      _instructions->relocate(instruction, section_word_Relocation::spec((address) dest, CodeBuffer::SECT_CONSTS), Assembler::disp32_operand);
      TRACE_C1X_3("relocating (Float/Long/Double) at %016x/%016x", instruction, operand);
      break;
    }
    case 'a': {
      address operand = Assembler::locate_operand(instruction, Assembler::imm_operand);
      Handle obj = CiConstant::object(constant);

      if (obj->is_a(HotSpotTypeResolved::klass())) {
        *((jobject*) operand) = JNIHandles::make_local(VmIds::get<klassOop>(HotSpotTypeResolved::vmId(obj)));
        _instructions->relocate(instruction, oop_Relocation::spec_for_immediate(), Assembler::imm_operand);
        TRACE_C1X_3("relocating (HotSpotType) at %016x/%016x", instruction, operand);
      } else {
        jobject value;
        if (obj() == HotSpotProxy::DUMMY_CONSTANT_OBJ()) {
          value = (jobject) Universe::non_oop_word();
        } else {
          value = JNIHandles::make_local(obj());
        }
        *((jobject*) operand) = value;
        _instructions->relocate(instruction, oop_Relocation::spec_for_immediate(), Assembler::imm_operand);
        TRACE_C1X_3("relocating (oop constant) at %016x/%016x", instruction, operand);
      }
      break;
    }
    default:
      fatal("unexpected CiKind in DataPatch");
      break;
  }
}

void CodeInstaller::site_Mark(CodeBuffer& buffer, jint pc_offset, oop site) {
  oop id_obj = CiTargetMethod_Mark::id(site);
  arrayOop references = (arrayOop) CiTargetMethod_Mark::references(site);

  if (id_obj != NULL) {
    assert(java_lang_boxing_object::is_instance(id_obj, T_INT), "Integer id expected");
    jint id = id_obj->int_field(java_lang_boxing_object::value_offset_in_bytes(T_INT));

    address instruction = _instructions->start() + pc_offset;

    switch (id) {
      case MARK_UNVERIFIED_ENTRY:
        _offsets.set_value(CodeOffsets::Entry, pc_offset);
        break;
      case MARK_VERIFIED_ENTRY:
        _offsets.set_value(CodeOffsets::Verified_Entry, pc_offset);
        break;
      case MARK_OSR_ENTRY:
        _offsets.set_value(CodeOffsets::OSR_Entry, pc_offset);
        break;
      case MARK_UNWIND_ENTRY:
        _offsets.set_value(CodeOffsets::UnwindHandler, pc_offset);
        break;
      case MARK_EXCEPTION_HANDLER_ENTRY:
        _offsets.set_value(CodeOffsets::Exceptions, pc_offset);
        break;
      case MARK_DEOPT_HANDLER_ENTRY:
        _offsets.set_value(CodeOffsets::Deopt, pc_offset);
        break;
      case MARK_STATIC_CALL_STUB: {
        assert(references->length() == 1, "static call stub needs one reference");
        oop ref = ((oop*) references->base(T_OBJECT))[0];
        address call_pc = _instructions->start() + CiTargetMethod_Site::pcOffset(ref);
        _instructions->relocate(instruction, static_stub_Relocation::spec(call_pc));
        _instructions->relocate(instruction, oop_Relocation::spec_for_immediate(), Assembler::imm_operand);
        break;
      }
      case MARK_INVOKE_INVALID:
      case MARK_INVOKEINTERFACE:
      case MARK_INVOKESTATIC:
      case MARK_INVOKESPECIAL:
      case MARK_INVOKEVIRTUAL:
        _next_call_type = (MarkId) id;
        _invoke_mark_pc = instruction;
        break;
      case MARK_IMPLICIT_NULL:
        _implicit_exception_table.append(pc_offset, pc_offset);
        break;
      case MARK_KLASS_PATCHING:
      case MARK_ACCESS_FIELD_PATCHING: {
        unsigned char* byte_count = (unsigned char*) (instruction - 1);
        unsigned char* byte_skip = (unsigned char*) (instruction - 2);
        unsigned char* being_initialized_entry_offset = (unsigned char*) (instruction - 3);

        assert(*byte_skip == 5, "unexpected byte_skip");

        assert(references->length() == 2, "MARK_KLASS_PATCHING/MARK_ACCESS_FIELD_PATCHING needs 2 references");
        oop ref1 = ((oop*) references->base(T_OBJECT))[0];
        oop ref2 = ((oop*) references->base(T_OBJECT))[1];
        int i_byte_count = CiTargetMethod_Site::pcOffset(ref2) - CiTargetMethod_Site::pcOffset(ref1);
        assert(i_byte_count == (unsigned char)i_byte_count, "invalid offset");
        *byte_count = i_byte_count;
        *being_initialized_entry_offset = *byte_count + *byte_skip;

        // we need to correct the offset of a field access - it's created with MAX_INT to ensure the correct size, and hotspot expects 0
        if (id == MARK_ACCESS_FIELD_PATCHING) {
          NativeMovRegMem* inst = nativeMovRegMem_at(_instructions->start() + CiTargetMethod_Site::pcOffset(ref1));
          assert(inst->offset() == max_jint, "unexpected offset value");
          inst->set_offset(0);
        }
        break;
      }
      case MARK_DUMMY_OOP_RELOCATION: {
        _instructions->relocate(instruction, oop_Relocation::spec_for_immediate(), Assembler::imm_operand);

        RelocIterator iter(_instructions, (address) instruction, (address) (instruction + 1));
        relocInfo::change_reloc_info_for_address(&iter, (address) instruction, relocInfo::oop_type, relocInfo::none);
        break;
      }
      default:
        ShouldNotReachHere();
        break;
    }
  }
}

