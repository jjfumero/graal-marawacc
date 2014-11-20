/*
 * Copyright (c) 2014, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.nodes.type;

import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.spi.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.hotspot.HotSpotVMConfig.CompressEncoding;
import com.oracle.graal.hotspot.meta.*;

public final class KlassPointerStamp extends MetaspacePointerStamp {

    private final CompressEncoding encoding;

    public static KlassPointerStamp klass() {
        return new KlassPointerStamp(false, false, null);
    }

    public static KlassPointerStamp klassNonNull() {
        return new KlassPointerStamp(true, false, null);
    }

    private KlassPointerStamp(boolean nonNull, boolean alwaysNull) {
        this(nonNull, alwaysNull, null);
    }

    private KlassPointerStamp(boolean nonNull, boolean alwaysNull, CompressEncoding encoding) {
        super(nonNull, alwaysNull);
        this.encoding = encoding;
    }

    @Override
    public boolean isCompatible(Stamp otherStamp) {
        if (this == otherStamp) {
            return true;
        }
        if (otherStamp instanceof KlassPointerStamp) {
            KlassPointerStamp other = (KlassPointerStamp) otherStamp;
            return Objects.equals(this.encoding, other.encoding);
        }
        return false;
    }

    @Override
    public LIRKind getLIRKind(LIRKindTool tool) {
        if (isCompressed()) {
            return LIRKind.value(Kind.Int);
        } else {
            return super.getLIRKind(tool);
        }
    }

    public boolean isCompressed() {
        return encoding != null;
    }

    public CompressEncoding getEncoding() {
        return encoding;
    }

    public KlassPointerStamp compressed(CompressEncoding newEncoding) {
        assert !isCompressed();
        return new KlassPointerStamp(nonNull(), alwaysNull(), newEncoding);
    }

    public KlassPointerStamp uncompressed() {
        assert isCompressed();
        return new KlassPointerStamp(nonNull(), alwaysNull());
    }

    @Override
    public Constant readConstant(MemoryAccessProvider provider, Constant base, long displacement) {
        if (isCompressed()) {
            return ((HotSpotMemoryAccessProviderImpl) provider).readNarrowPointerConstant(PointerType.Type, base, displacement);
        } else {
            return provider.readPointerConstant(PointerType.Type, base, displacement);
        }
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((encoding == null) ? 0 : encoding.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!super.equals(obj)) {
            return false;
        }
        if (!(obj instanceof KlassPointerStamp)) {
            return false;
        }
        KlassPointerStamp other = (KlassPointerStamp) obj;
        return Objects.equals(this.encoding, other.encoding);
    }

    @Override
    public String toString() {
        StringBuilder ret = new StringBuilder("Klass*");
        appendString(ret);
        if (isCompressed()) {
            ret.append("(compressed ").append(encoding).append(")");
        }
        return ret.toString();
    }
}
