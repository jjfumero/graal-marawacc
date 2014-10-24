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
package com.oracle.graal.truffle.test;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;

import org.junit.*;
import org.junit.experimental.theories.*;
import org.junit.runner.*;

import com.oracle.graal.truffle.*;
import com.oracle.truffle.api.*;

@RunWith(Theories.class)
public class TruffleStampTest {

    private static final Object TYPE1 = new Object();
    private static final Object TYPE2 = new Object();

    // @formatter:off
    @DataPoints public static Object[] data = new Object[]{1, 2, 1.0d, 2.0d, "1", "2", null,
                    new TestTypedObject(TYPE1), new TestTypedObject(TYPE1), new TestTypedObject(TYPE2), //
                    new Object[]{1, "a", new TestTypedObject(TYPE1)}, new Object[0]};
    // @formatter:on

    private TruffleStamp stamp;

    @Before
    public void setUp() {
        this.stamp = DefaultTruffleStamp.getInstance();
    }

    @Theory
    public void testOne1(Object value) {
        assertThat(stamp.joinValue(value).isCompatible(value), is(true));
    }

    @Theory
    public void testOne2(Object value) {
        assertThat(stamp.join(stamp.joinValue(value)).isCompatible(value), is(true));
    }

    @Theory
    public void testOne3(Object value) {
        assertThat(stamp.joinValue(value).equals(stamp.joinValue(value)), is(true));
    }

    @Theory
    public void testOne4(Object value) {
        assertThat(stamp.isCompatible(value), is(false));
    }

    @Theory
    public void testOne5(Object value) {
        TruffleStamp stamp1 = stamp.joinValue(value);
        assertThat(stamp1.joinValue(value), sameInstance(stamp1));
    }

    @Theory
    public void testOne6(Object value) {
        TruffleStamp stamp1 = stamp.joinValue(value);
        TruffleStamp stamp2 = stamp.joinValue(value);
        assertThat(stamp1.join(stamp2), sameInstance(stamp1));
    }

    @Theory
    public void testOne7(Object value1, Object value2) {
        assertThat(stamp.joinValue(value1).joinValue(value2).toStringShort(), is(notNullValue()));
        assertThat(stamp.joinValue(value1).joinValue(value2).toString(), is(notNullValue()));
        assertThat(stamp.joinValue(value1).joinValue(value2).hashCode(), is(stamp.joinValue(value1).joinValue(value2).hashCode()));
    }

    @Theory
    public void testTwo1(Object value1, Object value2) {
        TruffleStamp stamp1 = stamp.joinValue(value1).joinValue(value2);
        assertThat(stamp1.isCompatible(value1), is(true));
        assertThat(stamp1.isCompatible(value2), is(true));
    }

    @Theory
    public void testTwo2(Object value1, Object value2) {
        TruffleStamp stamp1 = stamp.join(stamp.joinValue(value1)).join(stamp.joinValue(value2));
        assertThat(stamp1.isCompatible(value1), is(true));
        assertThat(stamp1.isCompatible(value2), is(true));
    }

    @Theory
    public void testTwo3(Object value1, Object value2) {
        TruffleStamp stamp1 = stamp.joinValue(value1).joinValue(value2);
        TruffleStamp stamp2 = stamp.joinValue(value1).joinValue(value2);
        assertThat(stamp1.equals(stamp2), is(true));
    }

    @Theory
    public void testThree1(Object value1, Object value2, Object value3) {
        TruffleStamp stamp1 = stamp.joinValue(value1).joinValue(value2).joinValue(value3);
        assertThat(stamp1.isCompatible(value1), is(true));
        assertThat(stamp1.isCompatible(value2), is(true));
        assertThat(stamp1.isCompatible(value3), is(true));
    }

    @Theory
    public void testThree2(Object value1, Object value2, Object value3) {
        TruffleStamp stamp1 = stamp.join(stamp.joinValue(value1)).join(stamp.joinValue(value2)).join(stamp.joinValue(value3));
        assertThat(stamp1.isCompatible(value1), is(true));
        assertThat(stamp1.isCompatible(value2), is(true));
    }

    @Theory
    public void testThree3(Object value1, Object value2, Object value3) {
        TruffleStamp stamp1 = stamp.joinValue(value1).joinValue(value2).joinValue(value3);
        TruffleStamp stamp2 = stamp.joinValue(value1).joinValue(value2).joinValue(value3);
        assertThat(stamp1.equals(stamp2), is(true));
    }

    @Theory
    public void testThree4(Object value1, Object value2, Object value3) {
        TruffleStamp stamp1 = stamp.joinValue(value1).join(stamp.joinValue(value2).joinValue(value3));
        assertThat(stamp1.isCompatible(value1), is(true));
        assertThat(stamp1.isCompatible(value2), is(true));
    }

    @Theory
    public void testArray1(Object value1, Object value2, Object value3) {
        Object[] values = new Object[]{value1, value2, value3};
        stamp = stamp.joinValue(values);
        assertThat(stamp.isCompatible(values), is(true));
    }

    @Theory
    public void testArray2(Object value1, Object value2, Object value3) {
        Object[] values = new Object[]{value1, value2, value3};
        assertThat(stamp.joinValue(values).equals(stamp.joinValue(values)), is(true));
    }

    private static final class TestTypedObject implements TypedObject {

        private final Object type;

        public TestTypedObject(Object type) {
            this.type = type;
        }

        public Object getTypeIdentifier() {
            return type;
        }

    }

}
