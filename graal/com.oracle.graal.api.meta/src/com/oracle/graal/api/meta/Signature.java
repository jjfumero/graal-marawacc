/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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

/**
 * Represents a method signature provided by the runtime.
 *
 * @see <a href="http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.3.3">Method
 *      Descriptors</a>
 */
public interface Signature extends Remote {

    /**
     * Returns the number of parameters in this signature, adding 1 for a receiver if requested.
     *
     * @param receiver true if 1 is to be added to the result for a receiver
     * @return the number of parameters; + 1 iff {@code receiver == true}
     */
    int getParameterCount(boolean receiver);

    /**
     * Gets the parameter type at the specified position.
     *
     * @param index the index into the parameters, with {@code 0} indicating the first parameter
     * @param accessingClass the context of the type lookup. If non-null, its class loader is used
     *            for resolving the type. If {@code null}, then the type returned is either
     *            unresolved or a resolved type whose resolution is context free (e.g., a primitive
     *            type or a type in a java.* package).
     * @return the {@code index}'th parameter type
     * @throws LinkageError if {@code accessingClass != null} and resolution fails
     *
     */
    JavaType getParameterType(int index, ResolvedJavaType accessingClass);

    /**
     * Gets the parameter kind at the specified position. This is the same as calling
     * {@link #getParameterType}. {@link JavaType#getKind getKind}.
     *
     * @param index the index into the parameters, with {@code 0} indicating the first parameter
     * @return the kind of the parameter at the specified position
     */
    default Kind getParameterKind(int index) {
        return getParameterType(index, null).getKind();
    }

    /**
     * Gets the return type of this signature.
     *
     * @param accessingClass the context of the type lookup. If non-null, its class loader is used
     *            for resolving the type. If {@code null}, then the type returned is either
     *            unresolved or a resolved type whose resolution is context free (e.g., a primitive
     *            type or a type in a java.* package).
     * @return the return type
     * @throws LinkageError if {@code accessingClass != null} and resolution fails
     */
    JavaType getReturnType(ResolvedJavaType accessingClass);

    /**
     * Gets the return kind of this signature. This is the same as calling {@link #getReturnType}.
     * {@link JavaType#getKind getKind}.
     */
    default Kind getReturnKind() {
        return getReturnType(null).getKind();
    }

    /**
     * Gets the size, in Java slots, of the parameters to this signature.
     *
     * @param withReceiver {@code true} if to add a slot for a receiver object; {@code false} not to
     *            include the receiver
     * @return the size of the parameters in slots
     */
    int getParameterSlots(boolean withReceiver);

    /**
     * Gets the <a
     * href="http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.3.3">method
     * descriptor</a> corresponding to this signature. For example:
     *
     * <pre>
     * (ILjava/lang/String;D)V
     * </pre>
     *
     * @return the signature as a string
     */
    default String toMethodDescriptor() {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < getParameterCount(false); ++i) {
            sb.append(getParameterType(i, null).getName());
        }
        sb.append(')').append(getReturnType(null).getName());
        return sb.toString();
    }

    default JavaType[] toParameterTypes(JavaType receiverType) {
        int args = getParameterCount(false);
        JavaType[] result;
        int i = 0;
        if (receiverType != null) {
            result = new JavaType[args + 1];
            result[0] = receiverType;
            i = 1;
        } else {
            result = new JavaType[args];
        }
        for (int j = 0; j < args; j++) {
            result[i + j] = getParameterType(j, null);
        }
        return result;
    }
}
