/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.replacements;

import static com.oracle.graal.hotspot.replacements.HotSpotReplacementsUtil.*;
import sun.misc.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.replacements.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.Node.ConstantNodeParameter;
import com.oracle.graal.graph.Node.NodeIntrinsic;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.replacements.Snippet.Fold;
import com.oracle.graal.word.*;

/**
 * Substitutions for {@code com.sun.crypto.provider.CipherBlockChaining} methods.
 */
@ClassSubstitution(className = "com.sun.crypto.provider.CipherBlockChaining", optional = true)
public class CipherBlockChainingSubstitutions {

    private static final long embeddedCipherOffset;
    private static final long rOffset;
    static {
        try {
            // Need to use launcher class path as com.sun.crypto.provider.AESCrypt
            // is normally not on the boot class path
            ClassLoader cl = Launcher.getLauncher().getClassLoader();
            embeddedCipherOffset = UnsafeAccess.unsafe.objectFieldOffset(Class.forName("com.sun.crypto.provider.FeedbackCipher", true, cl).getDeclaredField("embeddedCipher"));
            rOffset = UnsafeAccess.unsafe.objectFieldOffset(Class.forName("com.sun.crypto.provider.CipherBlockChaining", true, cl).getDeclaredField("r"));
        } catch (Exception ex) {
            throw new GraalInternalError(ex);
        }
    }

    @Fold
    private static Class getAESCryptClass() {
        return AESCryptSubstitutions.AESCryptClass;
    }

    @MethodSubstitution(isStatic = false)
    static void encrypt(Object rcvr, byte[] in, int inOffset, int inLength, byte[] out, int outOffset) {
        Object embeddedCipher = UnsafeLoadNode.load(rcvr, embeddedCipherOffset, Kind.Object, LocationIdentity.ANY_LOCATION);
        if (getAESCryptClass().isInstance(embeddedCipher)) {
            crypt(rcvr, in, inOffset, inLength, out, outOffset, embeddedCipher, true);
        } else {
            encrypt(rcvr, in, inOffset, inLength, out, outOffset);
        }
    }

    @MethodSubstitution(isStatic = false)
    static void decrypt(Object rcvr, byte[] in, int inOffset, int inLength, byte[] out, int outOffset) {
        Object embeddedCipher = UnsafeLoadNode.load(rcvr, embeddedCipherOffset, Kind.Object, LocationIdentity.ANY_LOCATION);
        if (in != out && getAESCryptClass().isInstance(embeddedCipher)) {
            crypt(rcvr, in, inOffset, inLength, out, outOffset, embeddedCipher, false);
        } else {
            decrypt(rcvr, in, inOffset, inLength, out, outOffset);
        }
    }

    private static void crypt(Object rcvr, byte[] in, int inOffset, int inLength, byte[] out, int outOffset, Object embeddedCipher, boolean encrypt) {
        Object kObject = UnsafeLoadNode.load(embeddedCipher, AESCryptSubstitutions.kOffset, Kind.Object, AESCryptSubstitutions.kLocationIdentity);
        Object rObject = UnsafeLoadNode.load(rcvr, rOffset, Kind.Object, LocationIdentity.ANY_LOCATION);
        Word kAddr = (Word) Word.fromObject(kObject).add(arrayBaseOffset(Kind.Byte));
        Word rAddr = (Word) Word.fromObject(rObject).add(arrayBaseOffset(Kind.Byte));
        Word inAddr = Word.unsigned(GetObjectAddressNode.get(in) + arrayBaseOffset(Kind.Byte) + inOffset);
        Word outAddr = Word.unsigned(GetObjectAddressNode.get(out) + arrayBaseOffset(Kind.Byte) + outOffset);
        if (encrypt) {
            encryptAESCryptStub(ENCRYPT, inAddr, outAddr, kAddr, rAddr, inLength);
        } else {
            decryptAESCryptStub(DECRYPT, inAddr, outAddr, kAddr, rAddr, inLength);
        }
    }

    public static final ForeignCallDescriptor ENCRYPT = new ForeignCallDescriptor("encrypt", void.class, Word.class, Word.class, Word.class, Word.class, int.class);
    public static final ForeignCallDescriptor DECRYPT = new ForeignCallDescriptor("decrypt", void.class, Word.class, Word.class, Word.class, Word.class, int.class);

    @NodeIntrinsic(ForeignCallNode.class)
    public static native void encryptAESCryptStub(@ConstantNodeParameter ForeignCallDescriptor descriptor, Word in, Word out, Word key, Word r, int inLength);

    @NodeIntrinsic(ForeignCallNode.class)
    public static native void decryptAESCryptStub(@ConstantNodeParameter ForeignCallDescriptor descriptor, Word in, Word out, Word key, Word r, int inLength);
}
