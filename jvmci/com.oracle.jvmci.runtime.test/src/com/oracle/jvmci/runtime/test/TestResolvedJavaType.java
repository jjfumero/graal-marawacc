/*
 * Copyright (c) 2012, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.jvmci.runtime.test;

import static java.lang.reflect.Modifier.*;
import static org.junit.Assert.*;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

import org.junit.*;

import sun.reflect.ConstantPool;

import com.oracle.jvmci.common.*;
import com.oracle.jvmci.meta.Assumptions.AssumptionResult;
import com.oracle.jvmci.meta.*;

/**
 * Tests for {@link ResolvedJavaType}.
 */
public class TestResolvedJavaType extends TypeUniverse {

    public TestResolvedJavaType() {
    }

    @Test
    public void findInstanceFieldWithOffsetTest() {
        for (Class<?> c : classes) {
            ResolvedJavaType type = metaAccess.lookupJavaType(c);
            Set<Field> reflectionFields = getInstanceFields(c, true);
            for (Field f : reflectionFields) {
                ResolvedJavaField rf = lookupField(type.getInstanceFields(true), f);
                assertNotNull(rf);
                long offset = isStatic(f.getModifiers()) ? unsafe.staticFieldOffset(f) : unsafe.objectFieldOffset(f);
                ResolvedJavaField result = type.findInstanceFieldWithOffset(offset, rf.getKind());
                assertNotNull(result);
                assertTrue(fieldsEqual(f, result));
            }
        }
    }

    @Test
    public void isInterfaceTest() {
        for (Class<?> c : classes) {
            ResolvedJavaType type = metaAccess.lookupJavaType(c);
            boolean expected = c.isInterface();
            boolean actual = type.isInterface();
            assertEquals(expected, actual);
        }
    }

    @Test
    public void isInstanceClassTest() {
        for (Class<?> c : classes) {
            ResolvedJavaType type = metaAccess.lookupJavaType(c);
            boolean expected = !c.isArray() && !c.isPrimitive() && !c.isInterface();
            boolean actual = type.isInstanceClass();
            assertEquals(expected, actual);
        }
    }

    @Test
    public void isArrayTest() {
        for (Class<?> c : classes) {
            ResolvedJavaType type = metaAccess.lookupJavaType(c);
            boolean expected = c.isArray();
            boolean actual = type.isArray();
            assertEquals(expected, actual);
        }
    }

    @Test
    public void getModifiersTest() {
        for (Class<?> c : classes) {
            ResolvedJavaType type = metaAccess.lookupJavaType(c);
            int expected = c.getModifiers() & ModifiersProvider.jvmClassModifiers();
            int actual = type.getModifiers() & ModifiersProvider.jvmClassModifiers();
            Class<?> elementalType = c;
            while (elementalType.isArray()) {
                elementalType = elementalType.getComponentType();
            }
            if (elementalType.isMemberClass()) {
                // member class get their modifiers from the inner-class attribute in the JVM and
                // from the classfile header in jvmci
                expected &= ~(Modifier.PUBLIC | Modifier.PRIVATE | Modifier.PROTECTED);
                actual &= ~(Modifier.PUBLIC | Modifier.PRIVATE | Modifier.PROTECTED);
            }
            assertEquals(String.format("%s: 0x%x != 0x%x", type, expected, actual), expected, actual);
        }
    }

    @Test
    public void isAssignableFromTest() {
        Class<?>[] all = classes.toArray(new Class[classes.size()]);
        for (int i = 0; i < all.length; i++) {
            Class<?> c1 = all[i];
            for (int j = i; j < all.length; j++) {
                Class<?> c2 = all[j];
                ResolvedJavaType t1 = metaAccess.lookupJavaType(c1);
                ResolvedJavaType t2 = metaAccess.lookupJavaType(c2);
                boolean expected = c1.isAssignableFrom(c2);
                boolean actual = t1.isAssignableFrom(t2);
                assertEquals(expected, actual);
                if (expected && t1 != t2) {
                    assertFalse(t2.isAssignableFrom(t1));
                }
            }
        }
    }

    @Test
    public void isInstanceTest() {
        for (ConstantValue cv : constants()) {
            JavaConstant c = cv.value;
            if (c.getKind() == Kind.Object && !c.isNull()) {
                ResolvedJavaType cType = metaAccess.lookupJavaType(c);
                for (ResolvedJavaType t : javaTypes) {
                    if (t.isAssignableFrom(cType)) {
                        assertTrue(t.isInstance(c));
                    } else {
                        assertFalse(t.isInstance(c));
                    }
                }
            }
        }
    }

    private static Class<?> asExactClass(Class<?> c) {
        if (c.isArray()) {
            if (asExactClass(c.getComponentType()) != null) {
                return c;
            }
        } else {
            if (c.isPrimitive() || Modifier.isFinal(c.getModifiers())) {
                return c;
            }
        }
        return null;
    }

    @Test
    public void asExactTypeTest() {
        for (Class<?> c : classes) {
            ResolvedJavaType type = metaAccess.lookupJavaType(c);
            ResolvedJavaType exactType = type.asExactType();
            Class<?> expected = asExactClass(c);
            if (expected == null) {
                assertTrue("exact(" + c.getName() + ") != null", exactType == null);
            } else {
                assertNotNull(exactType);
                assertTrue(exactType.equals(metaAccess.lookupJavaType(expected)));
            }
        }
    }

    @Test
    public void getSuperclassTest() {
        for (Class<?> c : classes) {
            ResolvedJavaType type = metaAccess.lookupJavaType(c);
            Class<?> expected = c.getSuperclass();
            ResolvedJavaType actual = type.getSuperclass();
            if (expected == null) {
                assertTrue(actual == null);
            } else {
                assertNotNull(actual);
                assertTrue(actual.equals(metaAccess.lookupJavaType(expected)));
            }
        }
    }

    @Test
    public void getInterfacesTest() {
        for (Class<?> c : classes) {
            ResolvedJavaType type = metaAccess.lookupJavaType(c);
            Class<?>[] expected = c.getInterfaces();
            ResolvedJavaType[] actual = type.getInterfaces();
            assertEquals(expected.length, actual.length);
            for (int i = 0; i < expected.length; i++) {
                assertTrue(actual[i].equals(metaAccess.lookupJavaType(expected[i])));
            }
        }
    }

    public Class<?> getSupertype(Class<?> c) {
        assert !c.isPrimitive();
        if (c.isArray()) {
            Class<?> componentType = c.getComponentType();
            if (componentType.isPrimitive() || componentType == Object.class) {
                return Object.class;
            }
            return getArrayClass(getSupertype(componentType));
        }
        if (c.isInterface()) {
            return Object.class;
        }
        return c.getSuperclass();
    }

    public Class<?> findLeastCommonAncestor(Class<?> c1Initial, Class<?> c2Initial) {
        if (c1Initial.isPrimitive() || c2Initial.isPrimitive()) {
            return null;
        } else {
            Class<?> c1 = c1Initial;
            Class<?> c2 = c2Initial;
            while (true) {
                if (c1.isAssignableFrom(c2)) {
                    return c1;
                }
                if (c2.isAssignableFrom(c1)) {
                    return c2;
                }
                c1 = getSupertype(c1);
                c2 = getSupertype(c2);
            }
        }
    }

    @Test
    public void findLeastCommonAncestorTest() {
        Class<?>[] all = classes.toArray(new Class[classes.size()]);
        for (int i = 0; i < all.length; i++) {
            Class<?> c1 = all[i];
            for (int j = i; j < all.length; j++) {
                Class<?> c2 = all[j];
                ResolvedJavaType t1 = metaAccess.lookupJavaType(c1);
                ResolvedJavaType t2 = metaAccess.lookupJavaType(c2);
                Class<?> expected = findLeastCommonAncestor(c1, c2);
                ResolvedJavaType actual = t1.findLeastCommonAncestor(t2);
                if (expected == null) {
                    assertTrue(actual == null);
                } else {
                    assertNotNull(actual);
                    assertTrue(actual.equals(metaAccess.lookupJavaType(expected)));
                }
            }
        }
    }

    private static class Base {
    }

    abstract static class Abstract1 extends Base {
    }

    interface Interface1 {
    }

    static class Concrete1 extends Abstract1 {
    }

    static class Concrete2 extends Abstract1 implements Interface1 {
    }

    static class Concrete3 extends Concrete2 {
    }

    static final class Final1 extends Abstract1 {
    }

    abstract static class Abstract4 extends Concrete3 {
    }

    void checkConcreteSubtype(ResolvedJavaType type, ResolvedJavaType expected) {
        AssumptionResult<ResolvedJavaType> leafConcreteSubtype = type.findLeafConcreteSubtype();
        if (leafConcreteSubtype == null) {
            // findLeafConcreteSubtype() is conservative
        } else {
            if (expected == null) {
                assertNull(leafConcreteSubtype);
            } else {
                assertTrue(leafConcreteSubtype.getResult().equals(expected));
            }
        }

        if (!type.isArray()) {
            ResolvedJavaType arrayType = type.getArrayClass();
            AssumptionResult<ResolvedJavaType> arraySubtype = arrayType.findLeafConcreteSubtype();
            if (arraySubtype != null) {
                assertEquals(arraySubtype.getResult(), arrayType);
            } else {
                // findLeafConcreteSubtype() method is conservative
            }
        }
    }

    @Test
    public void findLeafConcreteSubtypeTest() {
        ResolvedJavaType base = metaAccess.lookupJavaType(Base.class);
        checkConcreteSubtype(base, base);

        ResolvedJavaType a1 = metaAccess.lookupJavaType(Abstract1.class);
        ResolvedJavaType c1 = metaAccess.lookupJavaType(Concrete1.class);

        checkConcreteSubtype(base, null);
        checkConcreteSubtype(a1, c1);
        checkConcreteSubtype(c1, c1);

        ResolvedJavaType i1 = metaAccess.lookupJavaType(Interface1.class);
        ResolvedJavaType c2 = metaAccess.lookupJavaType(Concrete2.class);

        checkConcreteSubtype(base, null);
        checkConcreteSubtype(a1, null);
        checkConcreteSubtype(c1, c1);
        checkConcreteSubtype(i1, c2);
        checkConcreteSubtype(c2, c2);

        ResolvedJavaType c3 = metaAccess.lookupJavaType(Concrete3.class);
        checkConcreteSubtype(c2, null);
        checkConcreteSubtype(c3, c3);

        ResolvedJavaType a4 = metaAccess.lookupJavaType(Abstract4.class);
        checkConcreteSubtype(c3, null);
        checkConcreteSubtype(a4, null);

        ResolvedJavaType a1a = metaAccess.lookupJavaType(Abstract1[].class);
        checkConcreteSubtype(a1a, null);
        ResolvedJavaType c1a = metaAccess.lookupJavaType(Concrete1[].class);
        checkConcreteSubtype(c1a, null);
        ResolvedJavaType f1a = metaAccess.lookupJavaType(Final1[].class);
        checkConcreteSubtype(f1a, f1a);

        ResolvedJavaType obja = metaAccess.lookupJavaType(Object[].class);
        checkConcreteSubtype(obja, null);

        ResolvedJavaType inta = metaAccess.lookupJavaType(int[].class);
        checkConcreteSubtype(inta, inta);
    }

    interface NoImplementor {
    }

    interface SingleImplementorInterface {
    }

    static class SingleConcreteImplementor implements SingleImplementorInterface {
    }

    interface SingleAbstractImplementorInterface {
    }

    abstract static class SingleAbstractImplementor implements SingleAbstractImplementorInterface {
    }

    interface MultiImplementorInterface {
    }

    static class ConcreteImplementor1 implements MultiImplementorInterface {
    }

    static class ConcreteImplementor2 implements MultiImplementorInterface {
    }

    interface MultipleAbstractImplementorInterface {
    }

    abstract static class MultiAbstractImplementor1 implements MultipleAbstractImplementorInterface {
    }

    abstract static class MultiAbstractImplementor2 implements MultipleAbstractImplementorInterface {
    }

    interface SingleAbstractImplementorInterface2 {
    }

    interface ExtendedSingleImplementorInterface {
    }

    abstract static class SingleAbstractImplementor2 implements SingleAbstractImplementorInterface2 {
    }

    static class ConcreteTransitiveImplementor1 extends SingleAbstractImplementor2 implements ExtendedSingleImplementorInterface {
    }

    static class ConcreteTransitiveImplementor2 extends SingleAbstractImplementor2 implements ExtendedSingleImplementorInterface {
    }

    @Test
    public void getSingleImplementorTest() {
        ResolvedJavaType iNi = metaAccess.lookupJavaType(NoImplementor.class);
        assertNull(iNi.getSingleImplementor());

        ResolvedJavaType iSi = metaAccess.lookupJavaType(SingleImplementorInterface.class);
        ResolvedJavaType cSi = metaAccess.lookupJavaType(SingleConcreteImplementor.class);
        assertEquals(cSi, iSi.getSingleImplementor());

        ResolvedJavaType iSai = metaAccess.lookupJavaType(SingleAbstractImplementorInterface.class);
        ResolvedJavaType aSai = metaAccess.lookupJavaType(SingleAbstractImplementor.class);
        assertEquals(aSai, iSai.getSingleImplementor());

        ResolvedJavaType iMi = metaAccess.lookupJavaType(MultiImplementorInterface.class);
        metaAccess.lookupJavaType(ConcreteImplementor1.class);
        metaAccess.lookupJavaType(ConcreteImplementor2.class);
        assertEquals(iMi, iMi.getSingleImplementor());

        ResolvedJavaType iMai = metaAccess.lookupJavaType(MultipleAbstractImplementorInterface.class);
        metaAccess.lookupJavaType(MultiAbstractImplementor1.class);
        metaAccess.lookupJavaType(MultiAbstractImplementor2.class);
        assertEquals(iMai, iMai.getSingleImplementor());

        ResolvedJavaType iSai2 = metaAccess.lookupJavaType(SingleAbstractImplementorInterface2.class);
        ResolvedJavaType aSai2 = metaAccess.lookupJavaType(SingleAbstractImplementor2.class);
        metaAccess.lookupJavaType(ConcreteTransitiveImplementor1.class);
        metaAccess.lookupJavaType(ConcreteTransitiveImplementor2.class);
        assertEquals(aSai2, iSai2.getSingleImplementor());
    }

    @Test(expected = JVMCIError.class)
    public void getSingleImplementorTestClassReceiver() {
        ResolvedJavaType base = metaAccess.lookupJavaType(Base.class);
        base.getSingleImplementor();
    }

    @Test(expected = JVMCIError.class)
    public void getSingleImplementorTestPrimitiveReceiver() {
        ResolvedJavaType primitive = metaAccess.lookupJavaType(int.class);
        primitive.getSingleImplementor();
    }

    @Test
    public void getComponentTypeTest() {
        for (Class<?> c : classes) {
            ResolvedJavaType type = metaAccess.lookupJavaType(c);
            Class<?> expected = c.getComponentType();
            ResolvedJavaType actual = type.getComponentType();
            if (expected == null) {
                assertNull(actual);
            } else {
                assertTrue(actual.equals(metaAccess.lookupJavaType(expected)));
            }
        }
    }

    @Test
    public void getArrayClassTest() {
        for (Class<?> c : classes) {
            if (c != void.class) {
                ResolvedJavaType type = metaAccess.lookupJavaType(c);
                Class<?> expected = getArrayClass(c);
                ResolvedJavaType actual = type.getArrayClass();
                assertTrue(actual.equals(metaAccess.lookupJavaType(expected)));
            }
        }
    }

    static class Declarations {

        final Method implementation;
        final Set<Method> declarations;

        public Declarations(Method impl) {
            this.implementation = impl;
            declarations = new HashSet<>();
        }
    }

    /**
     * See <a href="http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-5.html#jvms-5.4.5">Method
     * overriding</a>.
     */
    static boolean isOverriderOf(Method impl, Method m) {
        if (!isPrivate(m.getModifiers()) && !isFinal(m.getModifiers())) {
            if (m.getName().equals(impl.getName())) {
                if (m.getReturnType() == impl.getReturnType()) {
                    if (Arrays.equals(m.getParameterTypes(), impl.getParameterTypes())) {
                        if (isPublic(m.getModifiers()) || isProtected(m.getModifiers())) {
                            // m is public or protected
                            return isPublic(impl.getModifiers()) || isProtected(impl.getModifiers());
                        } else {
                            // m is package-private
                            return impl.getDeclaringClass().getPackage() == m.getDeclaringClass().getPackage();
                        }
                    }
                }
            }
        }
        return false;
    }

    static final Map<Class<?>, VTable> vtables = new HashMap<>();

    static class VTable {

        final Map<NameAndSignature, Method> methods = new HashMap<>();
    }

    static synchronized VTable getVTable(Class<?> c) {
        VTable vtable = vtables.get(c);
        if (vtable == null) {
            vtable = new VTable();
            if (c != Object.class) {
                VTable superVtable = getVTable(c.getSuperclass());
                vtable.methods.putAll(superVtable.methods);
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!isStatic(m.getModifiers()) && !isPrivate(m.getModifiers())) {
                    if (isAbstract(m.getModifiers())) {
                        // A subclass makes a concrete method in a superclass abstract
                        vtable.methods.remove(new NameAndSignature(m));
                    } else {
                        vtable.methods.put(new NameAndSignature(m), m);
                    }
                }
            }
            vtables.put(c, vtable);
        }
        return vtable;
    }

    static Set<Method> findDeclarations(Method impl, Class<?> c) {
        Set<Method> declarations = new HashSet<>();
        NameAndSignature implSig = new NameAndSignature(impl);
        if (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (new NameAndSignature(m).equals(implSig)) {
                    declarations.add(m);
                    break;
                }
            }
            if (!c.isInterface()) {
                declarations.addAll(findDeclarations(impl, c.getSuperclass()));
            }
            for (Class<?> i : c.getInterfaces()) {
                declarations.addAll(findDeclarations(impl, i));
            }
        }
        return declarations;
    }

    private static void checkResolveMethod(ResolvedJavaType type, ResolvedJavaType context, ResolvedJavaMethod decl, ResolvedJavaMethod expected) {
        ResolvedJavaMethod impl = type.resolveConcreteMethod(decl, context);
        assertEquals(expected, impl);
    }

    @Test
    public void resolveMethodTest() {
        ResolvedJavaType context = metaAccess.lookupJavaType(TestResolvedJavaType.class);
        for (Class<?> c : classes) {
            if (c.isInterface() || c.isPrimitive()) {
                ResolvedJavaType type = metaAccess.lookupJavaType(c);
                for (Method m : c.getDeclaredMethods()) {
                    if (JAVA_VERSION <= 1.7D || (!isStatic(m.getModifiers()) && !isPrivate(m.getModifiers()))) {
                        ResolvedJavaMethod resolved = metaAccess.lookupJavaMethod(m);
                        ResolvedJavaMethod impl = type.resolveMethod(resolved, context, true);
                        ResolvedJavaMethod expected = resolved.isDefault() || resolved.isAbstract() ? resolved : null;
                        assertEquals(m.toString(), expected, impl);
                    } else {
                        // As of JDK 8, interfaces can have static and private methods
                    }
                }
            } else {
                ResolvedJavaType type = metaAccess.lookupJavaType(c);
                VTable vtable = getVTable(c);
                for (Method impl : vtable.methods.values()) {
                    Set<Method> decls = findDeclarations(impl, c);
                    for (Method decl : decls) {
                        ResolvedJavaMethod m = metaAccess.lookupJavaMethod(decl);
                        if (m.isPublic()) {
                            ResolvedJavaMethod i = metaAccess.lookupJavaMethod(impl);
                            checkResolveMethod(type, context, m, i);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void resolveConcreteMethodTest() {
        ResolvedJavaType context = metaAccess.lookupJavaType(TestResolvedJavaType.class);
        for (Class<?> c : classes) {
            if (c.isInterface() || c.isPrimitive()) {
                ResolvedJavaType type = metaAccess.lookupJavaType(c);
                for (Method m : c.getDeclaredMethods()) {
                    if (JAVA_VERSION <= 1.7D || (!isStatic(m.getModifiers()) && !isPrivate(m.getModifiers()))) {
                        ResolvedJavaMethod resolved = metaAccess.lookupJavaMethod(m);
                        ResolvedJavaMethod impl = type.resolveConcreteMethod(resolved, context);
                        ResolvedJavaMethod expected = resolved.isDefault() ? resolved : null;
                        assertEquals(m.toString(), expected, impl);
                    } else {
                        // As of JDK 8, interfaces can have static and private methods
                    }
                }
            } else {
                ResolvedJavaType type = metaAccess.lookupJavaType(c);
                VTable vtable = getVTable(c);
                for (Method impl : vtable.methods.values()) {
                    Set<Method> decls = findDeclarations(impl, c);
                    for (Method decl : decls) {
                        ResolvedJavaMethod m = metaAccess.lookupJavaMethod(decl);
                        if (m.isPublic()) {
                            ResolvedJavaMethod i = metaAccess.lookupJavaMethod(impl);
                            checkResolveMethod(type, context, m, i);
                        }
                    }
                }
                for (Method m : c.getDeclaredMethods()) {
                    ResolvedJavaMethod impl = type.resolveConcreteMethod(metaAccess.lookupJavaMethod(m), context);
                    ResolvedJavaMethod expected = isAbstract(m.getModifiers()) ? null : impl;
                    assertEquals(type + " " + m.toString(), expected, impl);
                }
            }
        }
    }

    @Test
    public void findUniqueConcreteMethodTest() throws NoSuchMethodException {
        ResolvedJavaMethod thisMethod = metaAccess.lookupJavaMethod(getClass().getDeclaredMethod("findUniqueConcreteMethodTest"));
        ResolvedJavaMethod ucm = metaAccess.lookupJavaType(getClass()).findUniqueConcreteMethod(thisMethod).getResult();
        assertEquals(thisMethod, ucm);
    }

    public static Set<Field> getInstanceFields(Class<?> c, boolean includeSuperclasses) {
        if (c.isArray() || c.isPrimitive() || c.isInterface()) {
            return Collections.emptySet();
        }
        Set<Field> result = new HashSet<>();
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                result.add(f);
            }
        }
        if (includeSuperclasses && c != Object.class) {
            result.addAll(getInstanceFields(c.getSuperclass(), true));
        }
        return result;
    }

    public static Set<Field> getStaticFields(Class<?> c) {
        Set<Field> result = new HashSet<>();
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                result.add(f);
            }
        }
        return result;
    }

    public boolean fieldsEqual(Field f, ResolvedJavaField rjf) {
        return rjf.getDeclaringClass().equals(metaAccess.lookupJavaType(f.getDeclaringClass())) && rjf.getName().equals(f.getName()) &&
                        rjf.getType().resolve(rjf.getDeclaringClass()).equals(metaAccess.lookupJavaType(f.getType()));
    }

    public ResolvedJavaField lookupField(ResolvedJavaField[] fields, Field key) {
        for (ResolvedJavaField rf : fields) {
            if (fieldsEqual(key, rf)) {
                return rf;
            }
        }
        return null;
    }

    public Field lookupField(Set<Field> fields, ResolvedJavaField key) {
        for (Field f : fields) {
            if (fieldsEqual(f, key)) {
                return f;
            }
        }
        return null;
    }

    private static boolean isHiddenFromReflection(ResolvedJavaField f) {
        if (f.getDeclaringClass().equals(metaAccess.lookupJavaType(Throwable.class)) && f.getName().equals("backtrace")) {
            return true;
        }
        if (f.getDeclaringClass().equals(metaAccess.lookupJavaType(ConstantPool.class)) && f.getName().equals("constantPoolOop")) {
            return true;
        }
        if (f.getDeclaringClass().equals(metaAccess.lookupJavaType(Class.class)) && f.getName().equals("classLoader")) {
            return true;
        }
        return false;
    }

    @Test
    public void getInstanceFieldsTest() {
        for (Class<?> c : classes) {
            ResolvedJavaType type = metaAccess.lookupJavaType(c);
            for (boolean includeSuperclasses : new boolean[]{true, false}) {
                Set<Field> expected = getInstanceFields(c, includeSuperclasses);
                ResolvedJavaField[] actual = type.getInstanceFields(includeSuperclasses);
                for (Field f : expected) {
                    assertNotNull(lookupField(actual, f));
                }
                for (ResolvedJavaField rf : actual) {
                    if (!isHiddenFromReflection(rf)) {
                        assertEquals(rf.toString(), lookupField(expected, rf) != null, !rf.isInternal());
                    }
                }

                // Test stability of getInstanceFields
                ResolvedJavaField[] actual2 = type.getInstanceFields(includeSuperclasses);
                assertArrayEquals(actual, actual2);
            }
        }
    }

    @Test
    public void getStaticFieldsTest() {
        for (Class<?> c : classes) {
            ResolvedJavaType type = metaAccess.lookupJavaType(c);
            Set<Field> expected = getStaticFields(c);
            ResolvedJavaField[] actual = type.getStaticFields();
            for (Field f : expected) {
                assertNotNull(lookupField(actual, f));
            }
            for (ResolvedJavaField rf : actual) {
                if (!isHiddenFromReflection(rf)) {
                    assertEquals(lookupField(expected, rf) != null, !rf.isInternal());
                }
            }

            // Test stability of getStaticFields
            ResolvedJavaField[] actual2 = type.getStaticFields();
            assertArrayEquals(actual, actual2);
        }
    }

    @Test
    public void getDeclaredMethodsTest() {
        for (Class<?> c : classes) {
            ResolvedJavaType type = metaAccess.lookupJavaType(c);
            Method[] raw = c.getDeclaredMethods();
            Set<ResolvedJavaMethod> expected = new HashSet<>();
            for (Method m : raw) {
                ResolvedJavaMethod resolvedMethod = metaAccess.lookupJavaMethod(m);
                assertNotNull(resolvedMethod);
                expected.add(resolvedMethod);
            }
            Set<ResolvedJavaMethod> actual = new HashSet<>(Arrays.asList(type.getDeclaredMethods()));
            assertEquals(expected, actual);
        }
    }

    static class A {
        static String name = "foo";
    }

    static class B extends A {
    }

    static class C {
    }

    static class D {
        void foo() {
            // use of assertions causes the class to have a <clinit>
            assert getClass() != null;
        }
    }

    @Test
    public void getClassInitializerTest() {
        assertNotNull(metaAccess.lookupJavaType(A.class).getClassInitializer());
        assertNotNull(metaAccess.lookupJavaType(D.class).getClassInitializer());
        assertNull(metaAccess.lookupJavaType(B.class).getClassInitializer());
        assertNull(metaAccess.lookupJavaType(C.class).getClassInitializer());
        assertNull(metaAccess.lookupJavaType(int.class).getClassInitializer());
        assertNull(metaAccess.lookupJavaType(void.class).getClassInitializer());
    }

    @Test
    public void getAnnotationTest() {
        for (Class<?> c : classes) {
            ResolvedJavaType type = metaAccess.lookupJavaType(c);
            for (Annotation a : c.getAnnotations()) {
                assertEquals(a, type.getAnnotation(a.annotationType()));
            }
        }
    }

    @Test
    public void memberClassesTest() {
        for (Class<?> c : classes) {
            ResolvedJavaType type = metaAccess.lookupJavaType(c);
            assertEquals(c.isLocalClass(), type.isLocal());
            assertEquals(c.isMemberClass(), type.isMember());
            Class<?> enclc = c.getEnclosingClass();
            ResolvedJavaType enclt = type.getEnclosingType();
            assertFalse(enclc == null ^ enclt == null);
            if (enclc != null) {
                assertEquals(enclt, metaAccess.lookupJavaType(enclc));
            }
        }
    }

    @Test
    public void classFilePathTest() {
        for (Class<?> c : classes) {
            ResolvedJavaType type = metaAccess.lookupJavaType(c);
            URL path = type.getClassFilePath();
            if (type.isPrimitive() || type.isArray()) {
                assertEquals(null, path);
            } else {
                assertNotNull(path);
                String pathString = path.getPath();
                if (type.isLocal() || type.isMember()) {
                    assertTrue(pathString.indexOf('$') > 0);
                }
            }
        }
    }

    @Test
    public void isTrustedInterfaceTypeTest() {
        for (Class<?> c : classes) {
            ResolvedJavaType type = metaAccess.lookupJavaType(c);
            if (TrustedInterface.class.isAssignableFrom(c)) {
                assertTrue(type.isTrustedInterfaceType());
            }
        }
    }

    private Method findTestMethod(Method apiMethod) {
        String testName = apiMethod.getName() + "Test";
        for (Method m : getClass().getDeclaredMethods()) {
            if (m.getName().equals(testName) && m.getAnnotation(Test.class) != null) {
                return m;
            }
        }
        return null;
    }

    // @formatter:off
    private static final String[] untestedApiMethods = {
        "initialize",
        "isPrimitive",
        "newArray",
        "getDeclaredConstructors",
        "isInitialized",
        "isLinked",
        "getJavaClass",
        "getObjectHub",
        "hasFinalizableSubclass",
        "hasFinalizer",
        "getSourceFileName",
        "getClassFilePath",
        "isLocal",
        "isJavaLangObject",
        "isMember",
        "getElementalType",
        "getEnclosingType",
        "$jacocoInit"
    };
    // @formatter:on

    /**
     * Ensures that any new methods added to {@link ResolvedJavaMethod} either have a test written
     * for them or are added to {@link #untestedApiMethods}.
     */
    @Test
    public void testCoverage() {
        Set<String> known = new HashSet<>(Arrays.asList(untestedApiMethods));
        for (Method m : ResolvedJavaType.class.getDeclaredMethods()) {
            if (findTestMethod(m) == null) {
                assertTrue("test missing for " + m, known.contains(m.getName()));
            } else {
                assertFalse("test should be removed from untestedApiMethods" + m, known.contains(m.getName()));
            }
        }
    }
}
