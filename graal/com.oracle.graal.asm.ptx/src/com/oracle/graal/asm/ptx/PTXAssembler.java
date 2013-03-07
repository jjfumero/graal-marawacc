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
package com.oracle.graal.asm.ptx;

import static com.oracle.graal.api.code.ValueUtil.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;

public class PTXAssembler extends AbstractPTXAssembler {

    @SuppressWarnings("unused")
    public PTXAssembler(TargetDescription target, RegisterConfig registerConfig) {
        super(target);
    }

    public final void at() {
        emitString("@%p" + " " + "");
    }

    public final void add_s16(Register d, Register a, Register b) {
        emitString("add.s16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void add_s32(Register d, Register a, Register b) {
        emitString("add.s32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void add_s64(Register d, Register a, Register b) {
        emitString("add.s64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void add_s16(Register d, Register a, short s16) {
        emitString("add.s16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + s16 + ";" + "");
    }

    public final void add_s32(Register d, Register a, int s32) {
        emitString("add.s32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + s32 + ";" + "");
    }

    public final void add_s64(Register d, Register a, long s64) {
        emitString("add.s64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + s64 + ";" + "");
    }

    public final void add_u16(Register d, Register a, Register b) {
        emitString("add.u16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void add_u32(Register d, Register a, Register b) {
        emitString("add.u32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void add_u64(Register d, Register a, Register b) {
        emitString("add.u64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void add_u16(Register d, Register a, short u16) {
        emitString("add.u16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + u16 + ";" + "");
    }

    public final void add_u32(Register d, Register a, int u32) {
        emitString("add.u32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + u32 + ";" + "");
    }

    public final void add_u64(Register d, Register a, long u64) {
        emitString("add.u64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + u64 + ";" + "");
    }

    public final void add_sat_s32(Register d, Register a, Register b) {
        emitString("add.sat.s32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void add_sat_s32(Register d, Register a, int s32) {
        emitString("add.sat.s32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + s32 + ";" + "");
    }

    public final void and_b16(Register d, Register a, Register b) {
        emitString("and.b16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void and_b32(Register d, Register a, Register b) {
        emitString("and.b32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void and_b64(Register d, Register a, Register b) {
        emitString("and.b64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void and_b16(Register d, Register a, short b16) {
        emitString("and.b16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + b16 + ";" + "");
    }

    public final void and_b32(Register d, Register a, int b32) {
        emitString("and.b32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + b32 + ";" + "");
    }

    public final void and_b64(Register d, Register a, long b64) {
        emitString("and.b64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + b64 + ";" + "");
    }

    public final void bra(String tgt) {
        emitString("bra" + " " + tgt + ";" + "");
    }

    public final void bra_uni(String tgt) {
        emitString("bra.uni" + " " + tgt + ";" + "");
    }

    public final void div_s16(Register d, Register a, Register b) {
        emitString("div.s16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void div_s32(Register d, Register a, Register b) {
        emitString("div.s32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void div_s64(Register d, Register a, Register b) {
        emitString("div.s64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void div_s16(Register d, Register a, short s16) {
        emitString("div.s16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + s16 + ";" + "");
    }

    public final void div_s32(Register d, Register a, int s32) {
        emitString("div.s32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + s32 + ";" + "");
    }

    public final void div_s64(Register d, Register a, long s64) {
        emitString("div.s64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + s64 + ";" + "");
    }

    public final void div_u16(Register d, Register a, Register b) {
        emitString("div.u16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void div_u32(Register d, Register a, Register b) {
        emitString("div.u32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void div_u64(Register d, Register a, Register b) {
        emitString("div.u64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void div_u16(Register d, Register a, short u16) {
        emitString("div.u16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + u16 + ";" + "");
    }

    public final void div_u32(Register d, Register a, int u32) {
        emitString("div.u32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + u32 + ";" + "");
    }

    public final void div_u64(Register d, Register a, long u64) {
        emitString("div.u64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + u64 + ";" + "");
    }

    public final void exit() {
        emitString("exit;" + " " + "");
    }

    public final void ld_global_b8(Register d, Register a, long immOff) {
        emitString("ld.global.b8" + " " + "%r" + d.encoding() + ", [%r" + a.encoding() + " + " + immOff + "]" + ";" + "");
    }

    public final void ld_global_b16(Register d, Register a, long immOff) {
        emitString("ld.global.b16" + " " + "%r" + d.encoding() + ", [%r" + a.encoding() + " + " + immOff + "]" + ";" + "");
    }

    public final void ld_global_b32(Register d, Register a, long immOff) {
        emitString("ld.global.b32" + " " + "%r" + d.encoding() + ", [%r" + a.encoding() + " + " + immOff + "]" + ";" + "");
    }

    public final void ld_global_b64(Register d, Register a, long immOff) {
        emitString("ld.global.b64" + " " + "%r" + d.encoding() + ", [%r" + a.encoding() + " + " + immOff + "]" + ";" + "");
    }

    public final void ld_global_u8(Register d, Register a, long immOff) {
        emitString("ld.global.u8" + " " + "%r" + d.encoding() + ", [%r" + a.encoding() + " + " + immOff + "]" + ";" + "");
    }

    public final void ld_global_u16(Register d, Register a, long immOff) {
        emitString("ld.global.u16" + " " + "%r" + d.encoding() + ", [%r" + a.encoding() + " + " + immOff + "]" + ";" + "");
    }

    public final void ld_global_u32(Register d, Register a, long immOff) {
        emitString("ld.global.u32" + " " + "%r" + d.encoding() + ", [%r" + a.encoding() + " + " + immOff + "]" + ";" + "");
    }

    public final void ld_global_u64(Register d, Register a, long immOff) {
        emitString("ld.global.u64" + " " + "%r" + d.encoding() + ", [%r" + a.encoding() + " + " + immOff + "]" + ";" + "");
    }

    public final void ld_global_s8(Register d, Register a, long immOff) {
        emitString("ld.global.s8" + " " + "%r" + d.encoding() + ", [%r" + a.encoding() + " + " + immOff + "]" + ";" + "");
    }

    public final void ld_global_s16(Register d, Register a, long immOff) {
        emitString("ld.global.s16" + " " + "%r" + d.encoding() + ", [%r" + a.encoding() + " + " + immOff + "]" + ";" + "");
    }

    public final void ld_global_s32(Register d, Register a, long immOff) {
        emitString("ld.global.s32" + " " + "%r" + d.encoding() + ", [%r" + a.encoding() + " + " + immOff + "]" + ";" + "");
    }

    public final void ld_global_s64(Register d, Register a, long immOff) {
        emitString("ld.global.s64" + " " + "%r" + d.encoding() + ", [%r" + a.encoding() + " + " + immOff + "]" + ";" + "");
    }

    public final void ld_global_f32(Register d, Register a, long immOff) {
        emitString("ld.global.f32" + " " + "%r" + d.encoding() + ", [%r" + a.encoding() + " + " + immOff + "]" + ";" + "");
    }

    public final void ld_global_f64(Register d, Register a, long immOff) {
        emitString("ld.global.f64" + " " + "%r" + d.encoding() + ", [%r" + a.encoding() + " + " + immOff + "]" + ";" + "");
    }

    public final void mov_b16(Register d, Register a) {
        emitString("mov.b16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ";" + "");
    }

    public final void mov_b32(Register d, Register a) {
        emitString("mov.b32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ";" + "");
    }

    public final void mov_b64(Register d, Register a) {
        emitString("mov.b64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ";" + "");
    }

    public final void mov_u16(Register d, Register a) {
        emitString("mov.u16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ";" + "");
    }

    public final void mov_u32(Register d, Register a) {
        emitString("mov.u32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ";" + "");
    }

    public final void mov_u64(Register d, Register a) {
        emitString("mov.u64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ";" + "");
    }

    public final void mov_s16(Register d, Register a) {
        emitString("mov.s16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ";" + "");
    }

    public final void mov_s32(Register d, Register a) {
        emitString("mov.s32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ";" + "");
    }

    public final void mov_s64(Register d, Register a) {
        emitString("mov.s64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ";" + "");
    }

    public final void mov_f32(Register d, Register a) {
        emitString("mov.f32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ";" + "");
    }

    public final void mov_f64(Register d, Register a) {
        emitString("mov.f64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ";" + "");
    }

    public final void mov_b16(Register d, short b16) {
        emitString("mov.b16" + " " + "%r" + d.encoding() + ", " + b16 + ";" + "");
    }

    public final void mov_b32(Register d, int b32) {
        emitString("mov.b32" + " " + "%r" + d.encoding() + ", " + b32 + ";" + "");
    }

    public final void mov_b64(Register d, long b64) {
        emitString("mov.b64" + " " + "%r" + d.encoding() + ", " + b64 + ";" + "");
    }

    public final void mov_u16(Register d, short u16) {
        emitString("mov.u16" + " " + "%r" + d.encoding() + ", " + u16 + ";" + "");
    }

    public final void mov_u32(Register d, int u32) {
        emitString("mov.u32" + " " + "%r" + d.encoding() + ", " + u32 + ";" + "");
    }

    public final void mov_u64(Register d, long u64) {
        emitString("mov.u64" + " " + "%r" + d.encoding() + ", " + u64 + ";" + "");
    }

    public final void mov_s16(Register d, short s16) {
        emitString("mov.s16" + " " + "%r" + d.encoding() + ", " + s16 + ";" + "");
    }

    public final void mov_s32(Register d, int s32) {
        emitString("mov.s32" + " " + "%r" + d.encoding() + ", " + s32 + ";" + "");
    }

    public final void mov_s64(Register d, long s64) {
        emitString("mov.s64" + " " + "%r" + d.encoding() + ", " + s64 + ";" + "");
    }

    public final void mov_f32(Register d, float f32) {
        emitString("mov.f32" + " " + "%r" + d.encoding() + ", " + f32 + ";" + "");
    }

    public final void mov_f64(Register d, double f64) {
        emitString("mov.f64" + " " + "%r" + d.encoding() + ", " + f64 + ";" + "");
    }

    public final void mul_s16(Register d, Register a, Register b) {
        emitString("mul.s16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void mul_s32(Register d, Register a, Register b) {
        emitString("mul.s32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void mul_s64(Register d, Register a, Register b) {
        emitString("mul.s64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void mul_s16(Register d, Register a, short s16) {
        emitString("mul.s16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + s16 + ";" + "");
    }

    public final void mul_s32(Register d, Register a, int s32) {
        emitString("mul.s32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + s32 + ";" + "");
    }

    public final void mul_s64(Register d, Register a, long s64) {
        emitString("mul.s64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + s64 + ";" + "");
    }

    public final void mul_u16(Register d, Register a, Register b) {
        emitString("mul.u16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void mul_u32(Register d, Register a, Register b) {
        emitString("mul.u32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void mul_u64(Register d, Register a, Register b) {
        emitString("mul.u64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void mul_u16(Register d, Register a, short u16) {
        emitString("mul.u16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + u16 + ";" + "");
    }

    public final void mul_u32(Register d, Register a, int u32) {
        emitString("mul.u32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + u32 + ";" + "");
    }

    public final void mul_u64(Register d, Register a, long u64) {
        emitString("mul.u64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + u64 + ";" + "");
    }

    public final void neg_s16(Register d, Register a) {
        emitString("neg.s16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ";" + "");
    }

    public final void neg_s32(Register d, Register a) {
        emitString("neg.s32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ";" + "");
    }

    public final void neg_s64(Register d, Register a) {
        emitString("neg.s64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ";" + "");
    }

    public final void popc_b32(Register d, Register a) {
        emitString("popc.b32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ";" + "");
    }

    public final void popc_b64(Register d, Register a) {
        emitString("popc.b64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ";" + "");
    }

    public final void rem_s16(Register d, Register a, Register b) {
        emitString("rem.s16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void rem_s32(Register d, Register a, Register b) {
        emitString("rem.s32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void rem_s64(Register d, Register a, Register b) {
        emitString("rem.s64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void rem_s16(Register d, Register a, short s16) {
        emitString("rem.s16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + s16 + ";" + "");
    }

    public final void rem_s32(Register d, Register a, int s32) {
        emitString("rem.s32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + s32 + ";" + "");
    }

    public final void rem_s64(Register d, Register a, long s64) {
        emitString("rem.s64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + s64 + ";" + "");
    }

    public final void rem_u16(Register d, Register a, Register b) {
        emitString("rem.u16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void rem_u32(Register d, Register a, Register b) {
        emitString("rem.u32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void rem_u64(Register d, Register a, Register b) {
        emitString("rem.u64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void rem_u16(Register d, Register a, short u16) {
        emitString("rem.u16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + u16 + ";" + "");
    }

    public final void rem_u32(Register d, Register a, int u32) {
        emitString("rem.u32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + u32 + ";" + "");
    }

    public final void rem_u64(Register d, Register a, long u64) {
        emitString("rem.u64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + u64 + ";" + "");
    }

    public final void ret() {
        emitString("ret;" + " " + "");
    }

    public final void ret_uni() {
        emitString("ret.uni;" + " " + "");
    }

    public final void setp_eq_s32(Register a, Register b) {
        emitString("setp.eq.s32" + " " + "%p" + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_ne_s32(Register a, Register b) {
        emitString("setp.ne.s32" + " " + "%p" + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_lt_s32(Register a, Register b) {
        emitString("setp.lt.s32" + " " + "%p" + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_le_s32(Register a, Register b) {
        emitString("setp.le.s32" + " " + "%p" + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_gt_s32(Register a, Register b) {
        emitString("setp.gt.s32" + " " + "%p" + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_ge_s32(Register a, Register b) {
        emitString("setp.ge.s32" + " " + "%p" + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_eq_s32(Register a, int s32) {
        emitString("setp.eq.s32" + " " + "%p" + ", %r" + a.encoding() + ", " + s32 + ";" + "");
    }

    public final void setp_ne_s32(Register a, int s32) {
        emitString("setp.ne.s32" + " " + "%p" + ", %r" + a.encoding() + ", " + s32 + ";" + "");
    }

    public final void setp_lt_s32(Register a, int s32) {
        emitString("setp.lt.s32" + " " + "%p" + ", %r" + a.encoding() + ", " + s32 + ";" + "");
    }

    public final void setp_le_s32(Register a, int s32) {
        emitString("setp.le.s32" + " " + "%p" + ", %r" + a.encoding() + ", " + s32 + ";" + "");
    }

    public final void setp_gt_s32(Register a, int s32) {
        emitString("setp.gt.s32" + " " + "%p" + ", %r" + a.encoding() + ", " + s32 + ";" + "");
    }

    public final void setp_ge_s32(Register a, int s32) {
        emitString("setp.ge.s32" + " " + "%p" + ", %r" + a.encoding() + ", " + s32 + ";" + "");
    }

    public final void setp_eq_s32(int s32, Register b) {
        emitString("setp.eq.s32" + " " + "%p" + ", " + s32 + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_ne_s32(int s32, Register b) {
        emitString("setp.ne.s32" + " " + "%p" + ", " + s32 + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_lt_s32(int s32, Register b) {
        emitString("setp.lt.s32" + " " + "%p" + ", " + s32 + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_le_s32(int s32, Register b) {
        emitString("setp.le.s32" + " " + "%p" + ", " + s32 + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_gt_s32(int s32, Register b) {
        emitString("setp.gt.s32" + " " + "%p" + ", " + s32 + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_ge_s32(int s32, Register b) {
        emitString("setp.ge.s32" + " " + "%p" + ", " + s32 + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_eq_u32(Register a, Register b) {
        emitString("setp.eq.u32" + " " + "%p" + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_ne_u32(Register a, Register b) {
        emitString("setp.ne.u32" + " " + "%p" + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_lt_u32(Register a, Register b) {
        emitString("setp.lt.u32" + " " + "%p" + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_le_u32(Register a, Register b) {
        emitString("setp.le.u32" + " " + "%p" + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_gt_u32(Register a, Register b) {
        emitString("setp.gt.u32" + " " + "%p" + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_ge_u32(Register a, Register b) {
        emitString("setp.ge.u32" + " " + "%p" + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_eq_u32(Register a, int u32) {
        emitString("setp.eq.u32" + " " + "%p" + ", %r" + a.encoding() + ", " + u32 + ";" + "");
    }

    public final void setp_ne_u32(Register a, int u32) {
        emitString("setp.ne.u32" + " " + "%p" + ", %r" + a.encoding() + ", " + u32 + ";" + "");
    }

    public final void setp_lt_u32(Register a, int u32) {
        emitString("setp.lt.u32" + " " + "%p" + ", %r" + a.encoding() + ", " + u32 + ";" + "");
    }

    public final void setp_le_u32(Register a, int u32) {
        emitString("setp.le.u32" + " " + "%p" + ", %r" + a.encoding() + ", " + u32 + ";" + "");
    }

    public final void setp_gt_u32(Register a, int u32) {
        emitString("setp.gt.u32" + " " + "%p" + ", %r" + a.encoding() + ", " + u32 + ";" + "");
    }

    public final void setp_ge_u32(Register a, int u32) {
        emitString("setp.ge.u32" + " " + "%p" + ", %r" + a.encoding() + ", " + u32 + ";" + "");
    }

    public final void setp_eq_u32(int u32, Register b) {
        emitString("setp.eq.u32" + " " + "%p" + ", " + u32 + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_ne_u32(int u32, Register b) {
        emitString("setp.ne.u32" + " " + "%p" + ", " + u32 + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_lt_u32(int u32, Register b) {
        emitString("setp.lt.u32" + " " + "%p" + ", " + u32 + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_le_u32(int u32, Register b) {
        emitString("setp.le.u32" + " " + "%p" + ", " + u32 + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_gt_u32(int u32, Register b) {
        emitString("setp.gt.u32" + " " + "%p" + ", " + u32 + ", %r" + b.encoding() + ";" + "");
    }

    public final void setp_ge_u32(int u32, Register b) {
        emitString("setp.ge.u32" + " " + "%p" + ", " + u32 + ", %r" + b.encoding() + ";" + "");
    }

    public final void shr_s16(Register d, Register a, Register b) {
        emitString("shr.s16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void shr_s32(Register d, Register a, Register b) {
        emitString("shr.s32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void shr_s64(Register d, Register a, Register b) {
        emitString("shr.s64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void shr_s16(Register d, Register a, int u32) {
        emitString("shr.s16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + u32 + ";" + "");
    }

    public final void shr_s32(Register d, Register a, int u32) {
        emitString("shr.s32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + u32 + ";" + "");
    }

    public final void shr_s64(Register d, Register a, int u32) {
        emitString("shr.s64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + u32 + ";" + "");
    }

    public final void shr_u16(Register d, Register a, Register b) {
        emitString("shr.u16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void shr_u32(Register d, Register a, Register b) {
        emitString("shr.u32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void shr_u64(Register d, Register a, Register b) {
        emitString("shr.u64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void shr_u16(Register d, Register a, int u32) {
        emitString("shr.u16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + u32 + ";" + "");
    }

    public final void shr_u32(Register d, Register a, int u32) {
        emitString("shr.u32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + u32 + ";" + "");
    }

    public final void shr_u64(Register d, Register a, int u32) {
        emitString("shr.u64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + u32 + ";" + "");
    }

    public final void st_global_b8(Register a, long immOff, Register b) {
        emitString("st.global.b8" + " " + "[%r" + a.encoding() + " + " + immOff + "], %r" + b.encoding() + ";" + "");
    }

    public final void st_global_b16(Register a, long immOff, Register b) {
        emitString("st.global.b16" + " " + "[%r" + a.encoding() + " + " + immOff + "], %r" + b.encoding() + ";" + "");
    }

    public final void st_global_b32(Register a, long immOff, Register b) {
        emitString("st.global.b32" + " " + "[%r" + a.encoding() + " + " + immOff + "], %r" + b.encoding() + ";" + "");
    }

    public final void st_global_b64(Register a, long immOff, Register b) {
        emitString("st.global.b64" + " " + "[%r" + a.encoding() + " + " + immOff + "], %r" + b.encoding() + ";" + "");
    }

    public final void st_global_u8(Register a, long immOff, Register b) {
        emitString("st.global.u8" + " " + "[%r" + a.encoding() + " + " + immOff + "], %r" + b.encoding() + ";" + "");
    }

    public final void st_global_u16(Register a, long immOff, Register b) {
        emitString("st.global.u16" + " " + "[%r" + a.encoding() + " + " + immOff + "], %r" + b.encoding() + ";" + "");
    }

    public final void st_global_u32(Register a, long immOff, Register b) {
        emitString("st.global.u32" + " " + "[%r" + a.encoding() + " + " + immOff + "], %r" + b.encoding() + ";" + "");
    }

    public final void st_global_u64(Register a, long immOff, Register b) {
        emitString("st.global.u64" + " " + "[%r" + a.encoding() + " + " + immOff + "], %r" + b.encoding() + ";" + "");
    }

    public final void st_global_s8(Register a, long immOff, Register b) {
        emitString("st.global.s8" + " " + "[%r" + a.encoding() + " + " + immOff + "], %r" + b.encoding() + ";" + "");
    }

    public final void st_global_s16(Register a, long immOff, Register b) {
        emitString("st.global.s16" + " " + "[%r" + a.encoding() + " + " + immOff + "], %r" + b.encoding() + ";" + "");
    }

    public final void st_global_s32(Register a, long immOff, Register b) {
        emitString("st.global.s32" + " " + "[%r" + a.encoding() + " + " + immOff + "], %r" + b.encoding() + ";" + "");
    }

    public final void st_global_s64(Register a, long immOff, Register b) {
        emitString("st.global.s64" + " " + "[%r" + a.encoding() + " + " + immOff + "], %r" + b.encoding() + ";" + "");
    }

    public final void st_global_f32(Register a, long immOff, Register b) {
        emitString("st.global.f32" + " " + "[%r" + a.encoding() + " + " + immOff + "], %r" + b.encoding() + ";" + "");
    }

    public final void st_global_f64(Register a, long immOff, Register b) {
        emitString("st.global.f64" + " " + "[%r" + a.encoding() + " + " + immOff + "], %r" + b.encoding() + ";" + "");
    }

    public final void sub_s16(Register d, Register a, Register b) {
        emitString("sub.s16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void sub_s32(Register d, Register a, Register b) {
        emitString("sub.s32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void sub_s64(Register d, Register a, Register b) {
        emitString("sub.s64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void sub_s16(Register d, Register a, short s16) {
        emitString("sub.s16" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + s16 + ";" + "");
    }

    public final void sub_s32(Register d, Register a, int s32) {
        emitString("sub.s32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + s32 + ";" + "");
    }

    public final void sub_s64(Register d, Register a, long s64) {
        emitString("sub.s64" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + s64 + ";" + "");
    }

    public final void sub_s16(Register d, short s16, Register b) {
        emitString("sub.s16" + " " + "%r" + d.encoding() + ", " + s16 + ", %r" + b.encoding() + ";" + "");
    }

    public final void sub_s32(Register d, int s32, Register b) {
        emitString("sub.s32" + " " + "%r" + d.encoding() + ", " + s32 + ", %r" + b.encoding() + ";" + "");
    }

    public final void sub_s64(Register d, long s64, Register b) {
        emitString("sub.s64" + " " + "%r" + d.encoding() + ", " + s64 + ", %r" + b.encoding() + ";" + "");
    }

    public final void sub_sat_s32(Register d, Register a, Register b) {
        emitString("sub.sat.s32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", %r" + b.encoding() + ";" + "");
    }

    public final void sub_sat_s32(Register d, Register a, int s32) {
        emitString("sub.sat.s32" + " " + "%r" + d.encoding() + ", %r" + a.encoding() + ", " + s32 + ";" + "");
    }

    public final void sub_sat_s32(Register d, int s32, Register b) {
        emitString("sub.sat.s32" + " " + "%r" + d.encoding() + ", " + s32 + ", %r" + b.encoding() + ";" + "");
    }

    @Override
    public PTXAddress makeAddress(Kind kind, Value base, int displacement) {
        assert isRegister(base);
        return new PTXAddress(asRegister(base), displacement);
    }

    @Override
    public PTXAddress getPlaceholder() {
        // TODO Auto-generated method stub
        return null;
    }
}
