/*
 * Copyright (c) 2010 Sun Microsystems, Inc. All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product that is
 * described in this document. In particular, and without limitation, these intellectual property rights may include one
 * or more of the U.S. patents listed at http://www.sun.com/patents and one or more additional patents or pending patent
 * applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun Microsystems, Inc. standard
 * license agreement and applicable provisions of the FAR and its supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or registered
 * trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks are used under license and
 * are trademarks or registered trademarks of SPARC International, Inc. in the U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open Company, Ltd.
 */
package com.sun.hotspot.c1x;

import com.sun.cri.ci.*;
import com.sun.cri.ri.*;

/**
 * Implementation of RiMethod for unresolved HotSpot methods.
 *
 * @author Lukas Stadler
 */
public class HotSpotMethodUnresolved implements HotSpotMethod {
    private final String name;
    private final RiType holder;
    private final RiSignature signature;

    public HotSpotMethodUnresolved(String name, String signature, RiType holder) {
        this.name = name;
        this.holder = holder;
        this.signature = new HotSpotSignature(signature);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RiType holder() {
        return holder;
    }

    @Override
    public RiSignature signature() {
        return signature;
    }

    @Override
    public boolean isResolved() {
        return false;
    }

    @Override
    public byte[] code() {
        throw unresolved("code");
    }

    @Override
    public RiMethodProfile methodData() {
        throw unresolved("methodData");
    }

    @Override
    public String jniSymbol() {
        throw unresolved("jniSymbol");
    }

    @Override
    public int maxLocals() {
        throw unresolved("maxLocals");
    }

    @Override
    public int maxStackSize() {
        throw unresolved("maxStackSize");
    }

    @Override
    public boolean hasBalancedMonitors() {
        throw unresolved("hasBalancedMonitors");
    }

    @Override
    public int accessFlags() {
        throw unresolved("accessFlags");
    }

    @Override
    public boolean isLeafMethod() {
        throw unresolved("isLeafMethod");
    }

    @Override
    public boolean isClassInitializer() {
        return "<clinit>".equals(name);
    }

    @Override
    public boolean isConstructor() {
        return "<init>".equals(name);
    }

    @Override
    public boolean isOverridden() {
        throw unresolved("isOverridden");
    }

    @Override
    public Object liveness(int bci) {
        throw unresolved("liveness");
    }

    @Override
    public boolean canBeStaticallyBound() {
        throw unresolved("canBeStaticallyBound");
    }

    @Override
    public RiExceptionHandler[] exceptionHandlers() {
        throw unresolved("exceptionHandlers");
    }

    private CiUnresolvedException unresolved(String operation) {
        return new CiUnresolvedException(operation + " not defined for unresolved method " + name);
    }

    @Override
    public String toString() {
        return "HotSpotMethod<" + name + ", unresolved>";
    }

    @Override
    public Class<?> accessor() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int intrinsic() {
        // TODO Auto-generated method stub
        return 0;
    }

}
