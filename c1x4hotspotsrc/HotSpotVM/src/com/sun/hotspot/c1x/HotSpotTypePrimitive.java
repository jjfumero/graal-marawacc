/*
 * Copyright (c) 2010 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.hotspot.c1x;

import com.sun.c1x.util.*;
import com.sun.cri.ci.*;
import com.sun.cri.ri.*;

/**
 * Implementation of RiType for primitive HotSpot types.
 *
 * @author Thomas Wuerthinger, Lukas Stadler
 */
public final class HotSpotTypePrimitive extends HotSpotType {

    private CiKind kind;

    public static final HotSpotTypePrimitive Boolean = new HotSpotTypePrimitive(CiKind.Boolean);
    public static final HotSpotTypePrimitive Char = new HotSpotTypePrimitive(CiKind.Char);
    public static final HotSpotTypePrimitive Float = new HotSpotTypePrimitive(CiKind.Float);
    public static final HotSpotTypePrimitive Double = new HotSpotTypePrimitive(CiKind.Double);
    public static final HotSpotTypePrimitive Byte = new HotSpotTypePrimitive(CiKind.Byte);
    public static final HotSpotTypePrimitive Short = new HotSpotTypePrimitive(CiKind.Short);
    public static final HotSpotTypePrimitive Int = new HotSpotTypePrimitive(CiKind.Int);
    public static final HotSpotTypePrimitive Long = new HotSpotTypePrimitive(CiKind.Long);
    public static final HotSpotTypePrimitive Void = new HotSpotTypePrimitive(CiKind.Void);

    private HotSpotTypePrimitive(CiKind kind) {
        this.kind = kind;
        this.name = kind.toString();
    }

    @Override
    public int accessFlags() {
        return javaClass().getModifiers();
    }

    @Override
    public RiType arrayOf() {
        return Compiler.getVMEntries().getPrimitiveArrayType(kind);
    }

    @Override
    public RiType componentType() {
        return null;
    }

    @Override
    public RiType exactType() {
        return this;
    }

    @Override
    public CiConstant getEncoding(Representation r) {
        throw Util.unimplemented("HotSpotTypePrimitive.getEncoding");
    }

    @Override
    public CiKind getRepresentationKind(Representation r) {
        return kind;
    }

    @Override
    public boolean hasFinalizableSubclass() {
        return false;
    }

    @Override
    public boolean hasFinalizer() {
        return false;
    }

    @Override
    public boolean hasSubclass() {
        return false;
    }

    @Override
    public boolean isArrayClass() {
        return false;
    }

    @Override
    public boolean isInitialized() {
        return true;
    }

    @Override
    public boolean isInstance(Object obj) {
        return false;
    }

    @Override
    public boolean isInstanceClass() {
        return false;
    }

    @Override
    public boolean isInterface() {
        return false;
    }

    @Override
    public boolean isResolved() {
        return true;
    }

    @Override
    public boolean isSubtypeOf(RiType other) {
        return false;
    }

    @Override
    public Class<?> javaClass() {
        return kind.toJavaClass();
    }

    @Override
    public CiKind kind() {
        return kind;
    }

    @Override
    public RiMethod resolveMethodImpl(RiMethod method) {
        return null;
    }

    @Override
    public String toString() {
        return "HotSpotTypePrimitive<" + kind + ">";
    }

    @Override
    public RiType uniqueConcreteSubtype() {
        return this;
    }

}
