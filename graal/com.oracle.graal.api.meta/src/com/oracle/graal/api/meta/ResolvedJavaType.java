/*
 * Copyright (c) 2009, 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.api.meta;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.net.*;

/**
 * Represents a resolved Java type. Types include primitives, objects, {@code void}, and arrays
 * thereof. Types, like fields and methods, are resolved through {@link ConstantPool constant pools}
 * .
 */
public interface ResolvedJavaType extends JavaType {

    /**
     * Represents each of the several different parts of the runtime representation of a type which
     * compiled code may need to reference individually. These may or may not be different objects
     * or data structures, depending on the runtime system.
     */
    public enum Representation {
        /**
         * The runtime representation of the Java class object of this type.
         */
        JavaClass,

        /**
         * The runtime representation of the "hub" of this type--that is, the closest part of the
         * type representation which is typically stored in the object header.
         */
        ObjectHub
    }

    /**
     * Gets the encoding of (that is, a constant representing the value of) the specified part of
     * this type.
     * 
     * @param r the part of this type
     * @return a constant representing a reference to the specified part of this type
     */
    Constant getEncoding(Representation r);

    /**
     * Checks whether this type has a finalizer method.
     * 
     * @return {@code true} if this class has a finalizer
     */
    boolean hasFinalizer();

    /**
     * Checks whether this type has any finalizable subclasses so far. Any decisions based on this
     * information require the registration of a dependency, since this information may change.
     * 
     * @return {@code true} if this class has any subclasses with finalizers
     */
    boolean hasFinalizableSubclass();

    /**
     * Checks whether this type is an interface.
     * 
     * @return {@code true} if this type is an interface
     */
    boolean isInterface();

    /**
     * Checks whether this type is an instance class.
     * 
     * @return {@code true} if this type is an instance class
     */
    boolean isInstanceClass();

    /**
     * Checks whether this type is an array class.
     * 
     * @return {@code true} if this type is an array class
     */
    boolean isArray();

    /**
     * Checks whether this type is primitive.
     * 
     * @return {@code true} if this type is primitive
     */
    boolean isPrimitive();

    /**
     * Returns the Java language modifiers for this type, as an integer. The {@link Modifier} class
     * should be used to decode the modifiers. Only the flags specified in the JVM specification
     * will be included in the returned mask. This method is identical to
     * {@link Class#getModifiers()} in terms of the value return for this type.
     */
    int getModifiers();

    /**
     * Checks whether this type is initialized.
     * 
     * @return {@code true} if this type is initialized
     */
    boolean isInitialized();

    /**
     * Initializes this type.
     */
    void initialize();

    /**
     * Determines if this type is either the same as, or is a superclass or superinterface of, the
     * type represented by the specified parameter. This method is identical to
     * {@link Class#isAssignableFrom(Class)} in terms of the value return for this type.
     */
    boolean isAssignableFrom(ResolvedJavaType other);

    /**
     * Checks whether the specified object is an instance of this type.
     * 
     * @param obj the object to test
     * @return {@code true} if the object is an instance of this type
     */
    boolean isInstance(Constant obj);

    /**
     * Returns this type if it is an exact type otherwise returns null. This type is exact if it is
     * void, primitive, final, or an array of a final or primitive type.
     * 
     * @return this type if it is exact; {@code null} otherwise
     */
    ResolvedJavaType asExactType();

    /**
     * Gets the super class of this type. If this type represents either the {@code Object} class, a
     * primitive type, or void, then null is returned. If this object represents an array class or
     * an interface then the type object representing the {@code Object} class is returned.
     */
    ResolvedJavaType getSuperclass();

    /**
     * Gets the interfaces implemented or extended by this type. This method is analogous to
     * {@link Class#getInterfaces()} and as such, only returns the interfaces directly implemented
     * or extended by this type.
     */
    ResolvedJavaType[] getInterfaces();

    /**
     * Walks the class hierarchy upwards and returns the least common class that is a superclass of
     * both the current and the given type.
     * 
     * @return the least common type that is a super type of both the current and the given type, or
     *         {@code null} if primitive types are involved.
     */
    ResolvedJavaType findLeastCommonAncestor(ResolvedJavaType otherType);

    /**
     * Attempts to get a unique concrete subclass of this type.
     * <p>
     * For an {@linkplain #isArray() array} type A, the unique concrete subclass is A if the
     * {@linkplain MetaUtil#getElementalType(ResolvedJavaType) elemental} type of A is final (which
     * includes primitive types). Otherwise {@code null} is returned for A.
     * <p>
     * For a non-array type T, the result is the unique concrete type in the current hierarchy of T.
     * <p>
     * A runtime may decide not to manage or walk a large hierarchy and so the result is
     * conservative. That is, a non-null result is guaranteed to be the unique concrete class in T's
     * hierarchy <b>at the current point in time</b> but a null result does not necessarily imply
     * that there is no unique concrete class in T's hierarchy.
     * <p>
     * If the compiler uses the result of this method for its compilation, it must register an
     * assumption because dynamic class loading can invalidate the result of this method.
     * 
     * @return the unique concrete subclass for this type as described above
     */
    ResolvedJavaType findUniqueConcreteSubtype();

    ResolvedJavaType getComponentType();

    ResolvedJavaType getArrayClass();

    /**
     * Resolves the method implementation for virtual dispatches on objects of this dynamic type.
     * 
     * @param method the method to select the implementation of
     * @return the method implementation that would be selected at runtime, or {@code null} if the
     *         runtime cannot resolve the method at this point in time.
     */
    ResolvedJavaMethod resolveMethod(ResolvedJavaMethod method);

    /**
     * Given a {@link ResolvedJavaMethod} A, returns a concrete {@link ResolvedJavaMethod} B that is
     * the only possible unique target for a virtual call on A(). Returns {@code null} if either no
     * such concrete method or more than one such method exists. Returns the method A if A is a
     * concrete method that is not overridden.
     * <p>
     * If the compiler uses the result of this method for its compilation, it must register an
     * assumption because dynamic class loading can invalidate the result of this method.
     * 
     * @param method the method A for which a unique concrete target is searched
     * @return the unique concrete target or {@code null} if no such target exists or assumptions
     *         are not supported by this runtime
     */
    ResolvedJavaMethod findUniqueConcreteMethod(ResolvedJavaMethod method);

    /**
     * Returns the instance fields of this class, including
     * {@linkplain ResolvedJavaField#isInternal() internal} fields. A zero-length array is returned
     * for array and primitive types. The order of fields returned by this method is stable. That
     * is, for a single JVM execution the same order is returned each time this method is called. It
     * is also the "natural" order, which means that the JVM would expect the fields in this order
     * if no specific order is given.
     * 
     * @param includeSuperclasses if true, then instance fields for the complete hierarchy of this
     *            type are included in the result
     * @return an array of instance fields
     */
    ResolvedJavaField[] getInstanceFields(boolean includeSuperclasses);

    /**
     * Returns the annotation for the specified type of this class, if such an annotation is
     * present.
     * 
     * @param annotationClass the Class object corresponding to the annotation type
     * @return this element's annotation for the specified annotation type if present on this class,
     *         else {@code null}
     */
    <T extends Annotation> T getAnnotation(Class<T> annotationClass);

    /**
     * Returns the instance field of this class (or one of its super classes) at the given offset,
     * or {@code null} if there is no such field.
     * 
     * @param offset the offset of the field to look for
     * @return the field with the given offset, or {@code null} if there is no such field.
     */
    ResolvedJavaField findInstanceFieldWithOffset(long offset);

    /**
     * Returns name of source file of this type.
     */
    String getSourceFileName();

    /**
     * Returns the class file path - if available - of this type, or {@code null}.
     */
    URL getClassFilePath();

    /**
     * Returns {@code true} if the type is a local type.
     */
    boolean isLocal();

    /**
     * Returns {@code true} if the type is a member type.
     */
    boolean isMember();

    /**
     * Returns the enclosing type of this type, if it exists, or {@code null}.
     */
    ResolvedJavaType getEnclosingType();

    /**
     * Returns an array reflecting all the constructors declared by this type. This method is
     * similar to {@link Class#getDeclaredConstructors()} in terms of returned constructors.
     */
    ResolvedJavaMethod[] getDeclaredConstructors();

    /**
     * Returns an array reflecting all the methods declared by this type. This method is similar to
     * {@link Class#getDeclaredMethods()} in terms of returned methods.
     */
    ResolvedJavaMethod[] getDeclaredMethods();

    /**
     * Creates a new array with this type as the component type and the specified length. This
     * method is similar to {@link Array#newInstance(Class, int)}.
     */
    Constant newArray(int length);
}
