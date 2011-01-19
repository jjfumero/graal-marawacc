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

import java.lang.reflect.*;
import java.util.*;

import com.sun.cri.ci.*;
import com.sun.cri.ri.*;

/**
 * Implementation of RiType for resolved non-primitive HotSpot classes.
 *
 * @author Thomas Wuerthinger, Lukas Stadler
 */
public class HotSpotTypeResolved extends HotSpotType {

    private Class javaMirror;
    private String simpleName;
    private int accessFlags;
    private boolean hasFinalizer;
    private boolean hasSubclass;
    private boolean hasFinalizableSubclass;
    private boolean isInitialized;
    private boolean isArrayClass;
    private boolean isInstanceClass;
    private boolean isInterface;
    private int instanceSize;
    private RiType componentType;
    private HashMap<Integer, RiField> fieldCache;

    @Override
    public int accessFlags() {
        return accessFlags;
    }

    @Override
    public RiType arrayOf() {
        return Compiler.getVMEntries().RiType_arrayOf(this);
    }

    @Override
    public RiType componentType() {
        return Compiler.getVMEntries().RiType_componentType(this);
    }

    @Override
    public RiType uniqueConcreteSubtype() {
        return Compiler.getVMEntries().RiType_uniqueConcreteSubtype(this);
    }

    @Override
    public RiType exactType() {
        if (Modifier.isFinal(accessFlags)) {
            return this;
        }
        return null;
    }

    @Override
    public CiConstant getEncoding(Representation r) {
        switch (r) {
            case JavaClass:
                return CiConstant.forObject(javaClass());
            case ObjectHub:
                return CiConstant.forObject(this);
            case StaticFields:
                return CiConstant.forObject(this);
            case TypeInfo:
                return CiConstant.forObject(this);
            default:
                return null;
        }
    }

    @Override
    public CiKind getRepresentationKind(Representation r) {
        return CiKind.Object;
    }

    @Override
    public boolean hasFinalizableSubclass() {
        return hasFinalizableSubclass;
    }

    @Override
    public boolean hasFinalizer() {
        return hasFinalizer;
    }

    @Override
    public boolean hasSubclass() {
        return hasSubclass;
    }

    @Override
    public boolean isArrayClass() {
        return isArrayClass;
    }

    @Override
    public boolean isInitialized() {
        return isInitialized;
    }

    @Override
    public boolean isInstance(Object obj) {
        return javaMirror.isInstance(obj);
    }

    @Override
    public boolean isInstanceClass() {
        return isInstanceClass;
    }

    @Override
    public boolean isInterface() {
        return isInterface;
    }

    @Override
    public boolean isResolved() {
        return true;
    }

    @Override
    public boolean isSubtypeOf(RiType other) {
        if (other instanceof HotSpotTypeResolved) {
            return Compiler.getVMEntries().RiType_isSubtypeOf(this, other);
        }
        // No resolved type is a subtype of an unresolved type.
        return false;
    }

    @Override
    public Class<?> javaClass() {
        return javaMirror;
    }

    @Override
    public CiKind kind() {
        return CiKind.Object;
    }

    @Override
    public RiMethod resolveMethodImpl(RiMethod method) {
        assert method instanceof HotSpotMethod;
        return Compiler.getVMEntries().RiType_resolveMethodImpl(this, method.name(), method.signature().asString());
    }

    @Override
    public String toString() {
        return "HotSpotType<" + simpleName + ", resolved>";
    }

    public RiConstantPool constantPool() {
        return Compiler.getVMEntries().RiType_constantPool(this);
    }

    public int instanceSize() {
        return instanceSize;
    }

    public RiField createRiField(String name, RiType type, int offset) {
        RiField result = null;

        // (tw) Must cache the fields, because the local load elimination only works if the objects from two field lookups are equal.
        if (fieldCache == null) {
            fieldCache = new HashMap<Integer, RiField>(8);
        } else {
            result = fieldCache.get(offset);
        }

        if (result == null) {
            result = new HotSpotField(this, name, type, offset);
            fieldCache.put(offset, result);
        }

        return result;
    }

}
