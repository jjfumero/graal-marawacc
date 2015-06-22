/*
 * Copyright (c) 2012, 2015, Oracle and/or its affiliates. All rights reserved.
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

import jdk.internal.jvmci.common.*;
import jdk.internal.jvmci.meta.*;
import static com.oracle.graal.hotspot.HotSpotBackend.*;
import static com.oracle.graal.hotspot.replacements.HotSpotReplacementsUtil.*;
import sun.misc.*;

import com.oracle.graal.api.replacements.*;
import com.oracle.graal.graph.Node.ConstantNodeParameter;
import com.oracle.graal.graph.Node.NodeIntrinsic;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.word.*;

// JaCoCo Exclude

/**
 * Substitutions for {@code com.sun.crypto.provider.CipherBlockChaining} methods.
 */
public class CipherBlockChainingSubstitutions {

    private static final long embeddedCipherOffset;
    private static final long rOffset;
    private static final Class<?> cipherBlockChainingClass;
    private static final Class<?> feedbackCipherClass;
    static {
        try {
            // Need to use launcher class path as com.sun.crypto.provider.AESCrypt
            // is normally not on the boot class path
            ClassLoader cl = Launcher.getLauncher().getClassLoader();

            feedbackCipherClass = Class.forName("com.sun.crypto.provider.FeedbackCipher", true, cl);
            embeddedCipherOffset = UnsafeAccess.unsafe.objectFieldOffset(feedbackCipherClass.getDeclaredField("embeddedCipher"));

            cipherBlockChainingClass = Class.forName("com.sun.crypto.provider.CipherBlockChaining", true, cl);
            rOffset = UnsafeAccess.unsafe.objectFieldOffset(cipherBlockChainingClass.getDeclaredField("r"));
        } catch (Exception ex) {
            throw new JVMCIError(ex);
        }
    }

    @Fold
    private static Class<?> getAESCryptClass() {
        return AESCryptSubstitutions.AESCryptClass;
    }

    static int encrypt(Object rcvr, byte[] in, int inOffset, int inLength, byte[] out, int outOffset) {
        Object realReceiver = PiNode.piCastNonNull(rcvr, cipherBlockChainingClass);
        Object embeddedCipher = UnsafeLoadNode.load(realReceiver, embeddedCipherOffset, Kind.Object, LocationIdentity.any());
        if (getAESCryptClass().isInstance(embeddedCipher)) {
            Object aesCipher = PiNode.piCastNonNull(embeddedCipher, AESCryptSubstitutions.AESCryptClass);
            crypt(realReceiver, in, inOffset, inLength, out, outOffset, aesCipher, true);
            return inLength;
        } else {
            return encrypt(realReceiver, in, inOffset, inLength, out, outOffset);
        }
    }

    static int decrypt(Object rcvr, byte[] in, int inOffset, int inLength, byte[] out, int outOffset) {
        Object realReceiver = PiNode.piCastNonNull(rcvr, cipherBlockChainingClass);
        Object embeddedCipher = UnsafeLoadNode.load(realReceiver, embeddedCipherOffset, Kind.Object, LocationIdentity.any());
        if (in != out && getAESCryptClass().isInstance(embeddedCipher)) {
            Object aesCipher = PiNode.piCastNonNull(embeddedCipher, AESCryptSubstitutions.AESCryptClass);
            crypt(realReceiver, in, inOffset, inLength, out, outOffset, aesCipher, false);
            return inLength;
        } else {
            return decrypt(realReceiver, in, inOffset, inLength, out, outOffset);
        }
    }

    private static void crypt(Object rcvr, byte[] in, int inOffset, int inLength, byte[] out, int outOffset, Object embeddedCipher, boolean encrypt) {
        Object realReceiver = PiNode.piCastNonNull(rcvr, cipherBlockChainingClass);
        Object kObject = UnsafeLoadNode.load(embeddedCipher, AESCryptSubstitutions.kOffset, Kind.Object, LocationIdentity.any());
        Object rObject = UnsafeLoadNode.load(realReceiver, rOffset, Kind.Object, LocationIdentity.any());
        Word kAddr = Word.fromWordBase(Word.fromObject(kObject).add(arrayBaseOffset(Kind.Byte)));
        Word rAddr = Word.fromWordBase(Word.fromObject(rObject).add(arrayBaseOffset(Kind.Byte)));
        Word inAddr = Word.unsigned(ComputeObjectAddressNode.get(in, arrayBaseOffset(Kind.Byte) + inOffset));
        Word outAddr = Word.unsigned(ComputeObjectAddressNode.get(out, arrayBaseOffset(Kind.Byte) + outOffset));
        if (encrypt) {
            encryptAESCryptStub(ENCRYPT, inAddr, outAddr, kAddr, rAddr, inLength);
        } else {
            decryptAESCryptStub(DECRYPT, inAddr, outAddr, kAddr, rAddr, inLength);
        }
    }

    @NodeIntrinsic(ForeignCallNode.class)
    public static native void encryptAESCryptStub(@ConstantNodeParameter ForeignCallDescriptor descriptor, Word in, Word out, Word key, Word r, int inLength);

    @NodeIntrinsic(ForeignCallNode.class)
    public static native void decryptAESCryptStub(@ConstantNodeParameter ForeignCallDescriptor descriptor, Word in, Word out, Word key, Word r, int inLength);
}
