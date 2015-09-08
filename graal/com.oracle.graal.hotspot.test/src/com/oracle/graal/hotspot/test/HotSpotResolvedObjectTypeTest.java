/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.test;

import jdk.internal.jvmci.hotspot.*;
import jdk.internal.jvmci.meta.*;

import org.junit.*;

import com.oracle.graal.compiler.common.type.*;

/**
 * Tests {@link HotSpotResolvedJavaMethod} functionality.
 */
public class HotSpotResolvedObjectTypeTest extends HotSpotGraalCompilerTest {

    @Test
    public void testGetSourceFileName() throws Throwable {
        Assert.assertEquals("Object.java", HotSpotResolvedObjectTypeImpl.fromObjectClass(Object.class).getSourceFileName());
        Assert.assertEquals("HotSpotResolvedObjectTypeTest.java", HotSpotResolvedObjectTypeImpl.fromObjectClass(this.getClass()).getSourceFileName());
    }

    @Test
    public void testKlassLayoutHelper() {
        JavaConstant klass = HotSpotResolvedObjectTypeImpl.fromObjectClass(this.getClass()).klass();
        MemoryAccessProvider memoryAccess = getProviders().getConstantReflection().getMemoryAccessProvider();
        HotSpotVMConfig config = runtime().getConfig();
        Constant c = StampFactory.forKind(JavaKind.Int).readConstant(memoryAccess, klass, config.klassLayoutHelperOffset);
        assertTrue(c.toString(), c.getClass() == PrimitiveConstant.class);
        PrimitiveConstant pc = (PrimitiveConstant) c;
        assertTrue(pc.toString(), pc.getJavaKind() == JavaKind.Int);
    }
}
