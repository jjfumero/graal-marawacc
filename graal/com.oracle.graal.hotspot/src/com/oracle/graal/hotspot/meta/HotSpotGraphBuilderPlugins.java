/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.meta;

import static com.oracle.graal.hotspot.replacements.HotSpotReplacementsUtil.*;
import static com.oracle.graal.hotspot.replacements.SystemSubstitutions.*;
import static com.oracle.graal.java.BytecodeParser.Options.*;
import static sun.misc.Version.*;

import java.lang.invoke.*;
import java.util.zip.*;

import jdk.internal.jvmci.hotspot.*;
import jdk.internal.jvmci.meta.*;
import jdk.internal.jvmci.options.*;
import sun.reflect.*;

import com.oracle.graal.api.replacements.*;
import com.oracle.graal.compiler.common.spi.*;
import com.oracle.graal.graphbuilderconf.*;
import com.oracle.graal.graphbuilderconf.GraphBuilderConfiguration.Plugins;
import com.oracle.graal.graphbuilderconf.InvocationPlugin.Receiver;
import com.oracle.graal.graphbuilderconf.InvocationPlugins.Registration;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.hotspot.replacements.*;
import com.oracle.graal.hotspot.replacements.arraycopy.*;
import com.oracle.graal.hotspot.word.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.memory.HeapAccess.BarrierType;
import com.oracle.graal.nodes.memory.address.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.replacements.*;
import com.oracle.graal.word.*;

/**
 * Defines the {@link Plugins} used when running on HotSpot.
 */
public class HotSpotGraphBuilderPlugins {

    /**
     * Creates a {@link Plugins} object that should be used when running on HotSpot.
     *
     * @param constantReflection
     * @param snippetReflection
     * @param foreignCalls
     * @param stampProvider
     */
    public static Plugins create(HotSpotVMConfig config, HotSpotWordTypes wordTypes, MetaAccessProvider metaAccess, ConstantReflectionProvider constantReflection,
                    SnippetReflectionProvider snippetReflection, ForeignCallsProvider foreignCalls, StampProvider stampProvider, ReplacementsImpl replacements) {
        InvocationPlugins invocationPlugins = new HotSpotInvocationPlugins(config, metaAccess);

        Plugins plugins = new Plugins(invocationPlugins);
        NodeIntrinsificationPhase nodeIntrinsificationPhase = new NodeIntrinsificationPhase(metaAccess, constantReflection, snippetReflection, foreignCalls, stampProvider);
        NodeIntrinsificationPlugin nodeIntrinsificationPlugin = new NodeIntrinsificationPlugin(metaAccess, nodeIntrinsificationPhase, wordTypes, false);
        HotSpotWordOperationPlugin wordOperationPlugin = new HotSpotWordOperationPlugin(snippetReflection, wordTypes);
        HotSpotNodePlugin nodePlugin = new HotSpotNodePlugin(wordOperationPlugin, nodeIntrinsificationPlugin);

        plugins.appendParameterPlugin(nodePlugin);
        plugins.appendNodePlugin(nodePlugin);
        plugins.appendNodePlugin(new MethodHandlePlugin(constantReflection.getMethodHandleAccess(), true));

        plugins.appendInlineInvokePlugin(replacements);
        if (InlineDuringParsing.getValue()) {
            plugins.appendInlineInvokePlugin(new InlineDuringParsingPlugin());
        }

        registerObjectPlugins(invocationPlugins);
        registerClassPlugins(plugins);
        registerSystemPlugins(invocationPlugins, foreignCalls);
        registerThreadPlugins(invocationPlugins, metaAccess, wordTypes, config);
        registerCallSitePlugins(invocationPlugins);
        registerReflectionPlugins(invocationPlugins);
        registerStableOptionPlugins(invocationPlugins, snippetReflection);
        registerAESPlugins(invocationPlugins, config);
        registerCRC32Plugins(invocationPlugins, config);
        StandardGraphBuilderPlugins.registerInvocationPlugins(metaAccess, invocationPlugins);

        return plugins;
    }

    private static void registerObjectPlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, Object.class);
        r.register1("clone", Receiver.class, new InvocationPlugin() {
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                ValueNode object = receiver.get();
                b.addPush(JavaKind.Object, new ObjectCloneNode(b.getInvokeKind(), targetMethod, b.bci(), b.getInvokeReturnType(), object));
                return true;
            }

            public boolean inlineOnly() {
                return true;
            }
        });
        r.registerMethodSubstitution(ObjectSubstitutions.class, "hashCode", Receiver.class);
    }

    private static void registerClassPlugins(Plugins plugins) {
        Registration r = new Registration(plugins.getInvocationPlugins(), Class.class);

        r.registerMethodSubstitution(HotSpotClassSubstitutions.class, "getModifiers", Receiver.class);
        r.registerMethodSubstitution(HotSpotClassSubstitutions.class, "isInterface", Receiver.class);
        r.registerMethodSubstitution(HotSpotClassSubstitutions.class, "isArray", Receiver.class);
        r.registerMethodSubstitution(HotSpotClassSubstitutions.class, "isPrimitive", Receiver.class);
        r.registerMethodSubstitution(HotSpotClassSubstitutions.class, "getSuperclass", Receiver.class);

        if (jdkMajorVersion() >= 1 && jdkMinorVersion() <= 8) {
            r.registerMethodSubstitution(HotSpotClassSubstitutions.class, "getComponentType", Receiver.class);
        }

        r.register2("cast", Receiver.class, Object.class, new InvocationPlugin() {
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode object) {
                ValueNode javaClass = receiver.get();
                ValueNode folded = ClassCastNode.tryFold(GraphUtil.originalValue(javaClass), object, b.getConstantReflection(), b.getAssumptions());
                if (folded != null) {
                    b.addPush(JavaKind.Object, folded);
                } else {
                    b.addPush(JavaKind.Object, new ClassCastNode(b.getInvokeKind(), targetMethod, b.bci(), b.getInvokeReturnType(), javaClass, object));
                }
                return true;
            }

            public boolean inlineOnly() {
                return true;
            }
        });
    }

    private static void registerCallSitePlugins(InvocationPlugins plugins) {
        InvocationPlugin plugin = new InvocationPlugin() {
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                ValueNode callSite = receiver.get();
                ValueNode folded = CallSiteTargetNode.tryFold(GraphUtil.originalValue(callSite), b.getMetaAccess(), b.getAssumptions());
                if (folded != null) {
                    b.addPush(JavaKind.Object, folded);
                } else {
                    b.addPush(JavaKind.Object, new CallSiteTargetNode(b.getInvokeKind(), targetMethod, b.bci(), b.getInvokeReturnType(), callSite));
                }
                return true;
            }

            public boolean inlineOnly() {
                return true;
            }
        };
        plugins.register(plugin, ConstantCallSite.class, "getTarget", Receiver.class);
        plugins.register(plugin, MutableCallSite.class, "getTarget", Receiver.class);
        plugins.register(plugin, VolatileCallSite.class, "getTarget", Receiver.class);
    }

    private static void registerReflectionPlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, Reflection.class);
        r.register0("getCallerClass", new InvocationPlugin() {
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                b.addPush(JavaKind.Object, new ReflectionGetCallerClassNode(b.getInvokeKind(), targetMethod, b.bci(), b.getInvokeReturnType()));
                return true;
            }

            public boolean inlineOnly() {
                return true;
            }
        });
    }

    private static void registerSystemPlugins(InvocationPlugins plugins, ForeignCallsProvider foreignCalls) {
        Registration r = new Registration(plugins, System.class);
        r.register0("currentTimeMillis", new ForeignCallPlugin(foreignCalls, JAVA_TIME_MILLIS));
        r.register0("nanoTime", new ForeignCallPlugin(foreignCalls, JAVA_TIME_NANOS));
        r.register1("identityHashCode", Object.class, new InvocationPlugin() {
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode object) {
                b.addPush(JavaKind.Int, new IdentityHashCodeNode(b.getInvokeKind(), targetMethod, b.bci(), b.getInvokeReturnType(), object));
                return true;
            }

            public boolean inlineOnly() {
                return true;
            }
        });
        r.register5("arraycopy", Object.class, int.class, Object.class, int.class, int.class, new InvocationPlugin() {
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode src, ValueNode srcPos, ValueNode dst, ValueNode dstPos, ValueNode length) {
                b.add(new ArrayCopyNode(b.bci(), src, srcPos, dst, dstPos, length));
                return true;
            }

            public boolean inlineOnly() {
                return true;
            }
        });
    }

    private static void registerThreadPlugins(InvocationPlugins plugins, MetaAccessProvider metaAccess, WordTypes wordTypes, HotSpotVMConfig config) {
        Registration r = new Registration(plugins, Thread.class);
        r.register0("currentThread", new InvocationPlugin() {
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                CurrentJavaThreadNode thread = b.add(new CurrentJavaThreadNode(wordTypes.getWordKind()));
                boolean compressible = false;
                ValueNode offset = b.add(ConstantNode.forLong(config.threadObjectOffset));
                AddressNode address = b.add(new OffsetAddressNode(thread, offset));
                ValueNode javaThread = WordOperationPlugin.readOp(b, JavaKind.Object, address, JAVA_THREAD_THREAD_OBJECT_LOCATION, BarrierType.NONE, compressible);
                boolean exactType = compressible;
                boolean nonNull = true;
                b.addPush(JavaKind.Object, new PiNode(javaThread, metaAccess.lookupJavaType(Thread.class), exactType, nonNull));
                return true;
            }
        });
    }

    private static void registerStableOptionPlugins(InvocationPlugins plugins, SnippetReflectionProvider snippetReflection) {
        Registration r = new Registration(plugins, StableOptionValue.class);
        r.register1("getValue", Receiver.class, new InvocationPlugin() {
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                if (receiver.isConstant()) {
                    StableOptionValue<?> option = snippetReflection.asObject(StableOptionValue.class, (JavaConstant) receiver.get().asConstant());
                    b.addPush(JavaKind.Object, ConstantNode.forConstant(HotSpotObjectConstantImpl.forObject(option.getValue()), b.getMetaAccess()));
                    return true;
                }
                return false;
            }
        });
    }

    private static void registerAESPlugins(InvocationPlugins plugins, HotSpotVMConfig config) {
        if (config.useAESIntrinsics) {
            assert config.aescryptEncryptBlockStub != 0L;
            assert config.aescryptDecryptBlockStub != 0L;
            assert config.cipherBlockChainingEncryptAESCryptStub != 0L;
            assert config.cipherBlockChainingDecryptAESCryptStub != 0L;
            Class<?> c = MethodSubstitutionPlugin.resolveClass("com.sun.crypto.provider.CipherBlockChaining", true);
            if (c != null) {
                Registration r = new Registration(plugins, c);
                r.registerMethodSubstitution(CipherBlockChainingSubstitutions.class, "encrypt", Receiver.class, byte[].class, int.class, int.class, byte[].class, int.class);
                r.registerMethodSubstitution(CipherBlockChainingSubstitutions.class, "decrypt", Receiver.class, byte[].class, int.class, int.class, byte[].class, int.class);
            }
            c = MethodSubstitutionPlugin.resolveClass("com.sun.crypto.provider.AESCrypt", true);
            if (c != null) {
                Registration r = new Registration(plugins, c);
                r.registerMethodSubstitution(AESCryptSubstitutions.class, "encryptBlock", Receiver.class, byte[].class, int.class, byte[].class, int.class);
                r.registerMethodSubstitution(AESCryptSubstitutions.class, "decryptBlock", Receiver.class, byte[].class, int.class, byte[].class, int.class);
            }
        }
    }

    private static void registerCRC32Plugins(InvocationPlugins plugins, HotSpotVMConfig config) {
        if (config.useCRC32Intrinsics) {
            assert config.aescryptEncryptBlockStub != 0L;
            assert config.aescryptDecryptBlockStub != 0L;
            assert config.cipherBlockChainingEncryptAESCryptStub != 0L;
            assert config.cipherBlockChainingDecryptAESCryptStub != 0L;
            Registration r = new Registration(plugins, CRC32.class);
            r.registerMethodSubstitution(CRC32Substitutions.class, "update", int.class, int.class);
            r.registerMethodSubstitution(CRC32Substitutions.class, "updateBytes", int.class, byte[].class, int.class, int.class);
            r.registerMethodSubstitution(CRC32Substitutions.class, "updateByteBuffer", int.class, long.class, int.class, int.class);
        }
    }
}
