/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.lir.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import jdk.internal.jvmci.code.Register;
import jdk.internal.jvmci.code.Register.RegisterCategory;
import jdk.internal.jvmci.code.RegisterValue;
import jdk.internal.jvmci.meta.JavaConstant;
import jdk.internal.jvmci.meta.LIRKind;
import jdk.internal.jvmci.meta.PlatformKind;

import org.junit.Test;

import com.oracle.graal.lir.Variable;
import com.oracle.graal.lir.util.GenericValueMap;

public class GenericValueMapTest {

    private static enum DummyKind implements PlatformKind {
        Long;

        private EnumKey<DummyKind> key = new EnumKey<>(this);

        public Key getKey() {
            return key;
        }

        public int getSizeInBytes() {
            return 8;
        }

        public int getVectorLength() {
            return 1;
        }

        public char getTypeChar() {
            return 'l';
        }

        public JavaConstant getDefaultValue() {
            return null;
        }
    }

    @Test
    public void run0() {
        RegisterCategory cat = new RegisterCategory("regs");

        RegisterValue reg = new Register(0, 0, "reg0", cat).asValue();
        Variable var = new Variable(LIRKind.value(DummyKind.Long), 0);
        Object obj0 = new Object();
        Object obj1 = new Object();

        GenericValueMap<Object> map = new GenericValueMap<>();

        assertNull(map.get(reg));
        assertNull(map.get(var));

        map.put(reg, obj0);
        map.put(var, obj1);

        assertEquals(obj0, map.get(reg));
        assertEquals(obj1, map.get(var));

        map.remove(reg);
        map.remove(var);

        assertNull(map.get(reg));
        assertNull(map.get(var));

        map.put(reg, obj0);
        map.put(var, obj1);

        map.put(var, obj0);
        map.put(reg, obj1);

        assertEquals(obj1, map.get(reg));
        assertEquals(obj0, map.get(var));

        map.put(reg, null);
        map.put(var, null);

        assertNull(map.get(reg));
        assertNull(map.get(var));
    }
}
