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

import com.sun.cri.ci.*;
import com.sun.cri.ri.*;

/**
 * Implementation of RiMethod for resolved HotSpot methods.
 *
 * @author Thomas Wuerthinger, Lukas Stadler
 */
public class HotSpotMethodResolved implements HotSpotMethod {

    private final long vmId;
    private final String name;

    // cached values
    private byte[] code;
    private int accessFlags = -1;
    private int maxLocals = -1;
    private int maxStackSize = -1;
    private RiExceptionHandler[] exceptionHandlers;
    private RiSignature signature;
    private RiType holder;
    private Boolean hasBalancedMonitors;

    public HotSpotMethodResolved(long vmId, String name) {
        this.vmId = vmId;
        this.name = name;
    }

    @Override
    public int accessFlags() {
        if (accessFlags == -1) {
            accessFlags = Compiler.getVMEntries().RiMethod_accessFlags(vmId);
        }
        return accessFlags;
    }

    @Override
    public boolean canBeStaticallyBound() {
        return isLeafMethod() || Modifier.isStatic(accessFlags());
    }

    @Override
    public byte[] code() {
        if (code == null) {
            code = Compiler.getVMEntries().RiMethod_code(vmId);
        }
        return code;
    }

    @Override
    public RiExceptionHandler[] exceptionHandlers() {
        if (exceptionHandlers == null) {
            exceptionHandlers = Compiler.getVMEntries().RiMethod_exceptionHandlers(vmId);
        }
        return exceptionHandlers;
    }

    @Override
    public boolean hasBalancedMonitors() {
        if (hasBalancedMonitors == null) {
            hasBalancedMonitors = Compiler.getVMEntries().RiMethod_hasBalancedMonitors(vmId);
        }
        return hasBalancedMonitors;
    }

    @Override
    public RiType holder() {
        if (holder == null) {
            holder = Compiler.getVMEntries().RiMethod_holder(vmId);
        }
        return holder;
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
    public boolean isLeafMethod() {
        return Modifier.isFinal(accessFlags()) || Modifier.isPrivate(accessFlags());
    }

    @Override
    public boolean isOverridden() {
        throw new UnsupportedOperationException("isOverridden");
    }

    @Override
    public boolean isResolved() {
        return true;
    }

    @Override
    public String jniSymbol() {
        throw new UnsupportedOperationException("jniSymbol");
    }

    public CiBitMap[] livenessMap() {
        return null;
    }

    @Override
    public int maxLocals() {
        if (maxLocals == -1) {
            maxLocals = Compiler.getVMEntries().RiMethod_maxLocals(vmId);
        }
        return maxLocals;
    }

    @Override
    public int maxStackSize() {
        if (maxStackSize == -1) {
            maxStackSize = Compiler.getVMEntries().RiMethod_maxStackSize(vmId);
        }
        return maxStackSize;
    }

    @Override
    public RiMethodProfile methodData() {
        return null;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public StackTraceElement toStackTraceElement(int bci) {
        return CiUtil.toStackTraceElement(this, bci);
    }

    @Override
    public RiMethod uniqueConcreteMethod() {
        return Compiler.getVMEntries().RiMethod_uniqueConcreteMethod(vmId);
    }

    @Override
    public RiSignature signature() {
        if (signature == null) {
            signature = new HotSpotSignature(Compiler.getVMEntries().RiMethod_signature(vmId));
        }
        return signature;
    }

    @Override
    public String toString() {
        return "HotSpotMethod<" + holder().name() + ". " + name + ">";
    }

    public boolean hasCompiledCode() {
        // TODO: needs a VMEntries to go cache the result of that method.
        // This isn't used by GRAAL for now, so this is enough.
        return false;
    }

    @Override
    public Class<?> accessor() {
        return null;
    }

    @Override
    public int intrinsic() {
        return 0;
    }
}
