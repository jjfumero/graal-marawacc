/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.truffle.substitutions;

import static java.lang.Character.*;

import java.util.concurrent.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.compiler.common.calc.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.java.*;
import com.oracle.graal.java.GraphBuilderPlugins.InvocationPlugin;
import com.oracle.graal.java.GraphBuilderPlugins.Registration;
import com.oracle.graal.java.GraphBuilderPlugins.Registration.Receiver;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.truffle.*;
import com.oracle.graal.truffle.nodes.*;
import com.oracle.graal.truffle.nodes.arithmetic.*;
import com.oracle.graal.truffle.nodes.frame.*;
import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;

/**
 * Provider of {@link GraphBuilderPlugin}s for Truffle classes.
 */
public class TruffleGraphBuilderPlugins {
    public static void registerPlugins(MetaAccessProvider metaAccess, GraphBuilderPlugins plugins) {

        // OptimizedAssumption.class
        Registration r = new Registration(plugins, metaAccess, OptimizedAssumption.class);
        r.register1("isValid", Receiver.class, new InvocationPlugin() {
            public boolean apply(GraphBuilderContext builder, ValueNode arg) {
                if (arg.isConstant()) {
                    Constant constant = arg.asConstant();
                    OptimizedAssumption assumption = builder.getSnippetReflection().asObject(OptimizedAssumption.class, (JavaConstant) constant);
                    builder.push(Kind.Boolean.getStackKind(), builder.append(ConstantNode.forBoolean(assumption.isValid())));
                    builder.getAssumptions().record(new AssumptionValidAssumption(assumption));
                } else {
                    throw new BailoutException("assumption could not be reduced to a constant");
                }
                return true;
            }
        });

        // ExactMath.class
        r = new Registration(plugins, metaAccess, ExactMath.class);
        r.register2("addExact", Integer.TYPE, Integer.TYPE, new InvocationPlugin() {
            public boolean apply(GraphBuilderContext builder, ValueNode x, ValueNode y) {
                builder.push(Kind.Int.getStackKind(), builder.append(new IntegerAddExactNode(x, y)));
                return true;
            }
        });

        // CompilerDirectives.class
        r = new Registration(plugins, metaAccess, CompilerDirectives.class);
        r.register0("inInterpreter", new InvocationPlugin() {
            public boolean apply(GraphBuilderContext builder) {
                builder.push(Kind.Boolean.getStackKind(), builder.append(ConstantNode.forBoolean(false)));
                return true;
            }
        });
        r.register0("inCompiledCode", new InvocationPlugin() {
            public boolean apply(GraphBuilderContext builder) {
                builder.push(Kind.Boolean.getStackKind(), builder.append(ConstantNode.forBoolean(true)));
                return true;
            }
        });
        r.register0("transferToInterpreter", new InvocationPlugin() {
            public boolean apply(GraphBuilderContext builder) {
                builder.append(new DeoptimizeNode(DeoptimizationAction.None, DeoptimizationReason.TransferToInterpreter));
                return true;
            }
        });
        r.register0("transferToInterpreterAndInvalidate", new InvocationPlugin() {
            public boolean apply(GraphBuilderContext builder) {
                builder.append(new DeoptimizeNode(DeoptimizationAction.InvalidateReprofile, DeoptimizationReason.TransferToInterpreter));
                return true;
            }
        });
        r.register1("interpreterOnly", Runnable.class, new InvocationPlugin() {
            public boolean apply(GraphBuilderContext builder, ValueNode arg) {
                return true;
            }
        });
        r.register1("interpreterOnly", Callable.class, new InvocationPlugin() {
            public boolean apply(GraphBuilderContext builder, ValueNode arg) {
                return true;
            }
        });
        r.register2("injectBranchProbability", double.class, boolean.class, new InvocationPlugin() {
            public boolean apply(GraphBuilderContext builder, ValueNode probability, ValueNode condition) {
                builder.push(Kind.Boolean.getStackKind(), builder.append(new BranchProbabilityNode(probability, condition)));
                return true;
            }
        });
        r.register1("bailout", String.class, new InvocationPlugin() {
            public boolean apply(GraphBuilderContext builder, ValueNode message) {
                if (message.isConstant()) {
                    throw new BailoutException(message.asConstant().toValueString());
                }
                throw new BailoutException("bailout (message is not compile-time constant, so no additional information is available)");
            }
        });
        r.register1("isCompilationConstant", Object.class, new InvocationPlugin() {
            public boolean apply(GraphBuilderContext builder, ValueNode value) {
                if ((value instanceof BoxNode ? ((BoxNode) value).getValue() : value).isConstant()) {
                    builder.push(Kind.Boolean.getStackKind(), builder.append(ConstantNode.forBoolean(true)));
                    return true;
                }
                return false;
            }
        });
        r.register1("materialize", Object.class, new InvocationPlugin() {
            public boolean apply(GraphBuilderContext builder, ValueNode value) {
                builder.append(new ForceMaterializeNode(value));
                return true;
            }
        });

        // OptimizedCallTarget.class
        r = new Registration(plugins, metaAccess, OptimizedCallTarget.class);
        r.register2("createFrame", FrameDescriptor.class, Object[].class, new InvocationPlugin() {
            public boolean apply(GraphBuilderContext builder, ValueNode arg1, ValueNode arg2) {
                builder.push(Kind.Object, builder.append(new NewFrameNode(StampFactory.exactNonNull(metaAccess.lookupJavaType(FrameWithoutBoxing.class)), arg1, arg2)));
                return true;
            }
        });

        // FrameWithoutBoxing.class
        r = new Registration(plugins, metaAccess, FrameWithoutBoxing.class);
        r.register1("materialize", Receiver.class, new InvocationPlugin() {
            public boolean apply(GraphBuilderContext builder, ValueNode frame) {
                builder.append(new MaterializeFrameNode(frame));
                return true;
            }
        });
        r.register4("unsafeCast", Object.class, Class.class, boolean.class, boolean.class, new InvocationPlugin() {
            public boolean apply(GraphBuilderContext builder, ValueNode object, ValueNode clazz, ValueNode condition, ValueNode nonNull) {
                System.out.println("unsafe cast with cond: " + condition + " clazz: " + clazz + " nonNull: " + nonNull);
                if (clazz.isConstant() && nonNull.isConstant()) {
                    ConstantReflectionProvider constantReflection = builder.getConstantReflection();
                    ResolvedJavaType javaType = constantReflection.asJavaType(clazz.asConstant());
                    if (javaType == null) {
                        builder.push(Kind.Object, object);
                    } else {
                        Stamp piStamp = StampFactory.declaredTrusted(javaType, nonNull.asJavaConstant().asInt() != 0);
                        LogicNode compareNode = CompareNode.createCompareNode(object.graph(), Condition.EQ, condition, ConstantNode.forBoolean(true, object.graph()), constantReflection);
                        boolean skipAnchor = false;
                        if (compareNode instanceof LogicConstantNode) {
                            LogicConstantNode logicConstantNode = (LogicConstantNode) compareNode;
                            if (logicConstantNode.getValue()) {
                                skipAnchor = true;
                            }
                        }
                        ConditionAnchorNode valueAnchorNode = null;
                        if (!skipAnchor) {
                            valueAnchorNode = builder.append(new ConditionAnchorNode(compareNode));
                        }
                        PiNode piCast = builder.append(new PiNode(object, piStamp, valueAnchorNode));
                        builder.push(Kind.Object, piCast);
                    }
                    return true;
                }
                throw GraalInternalError.shouldNotReachHere("unsafeCast arguments could not reduce to a constant: " + clazz + ", " + nonNull);
            }
        });
        for (Kind kind : new Kind[]{Kind.Boolean, Kind.Byte, Kind.Int, Kind.Long, Kind.Float, Kind.Double, Kind.Object}) {
            String kindName = kind.getJavaName();
            kindName = toUpperCase(kindName.charAt(0)) + kindName.substring(1);
            String getName = "unsafeGet" + kindName;
            String putName = "unsafePut" + kindName;
            r.register4(getName, Object.class, long.class, boolean.class, Object.class, new CustomizedUnsafeLoadPlugin(kind));
            r.register4(putName, Object.class, long.class, kind == Kind.Object ? Object.class : kind.toJavaClass(), Object.class, new CustomizedUnsafeStorePlugin(kind));
        }
    }

    static class CustomizedUnsafeLoadPlugin implements InvocationPlugin {

        private final Kind returnKind;

        public CustomizedUnsafeLoadPlugin(Kind returnKind) {
            this.returnKind = returnKind;
        }

        public boolean apply(GraphBuilderContext builder, ValueNode object, ValueNode offset, ValueNode condition, ValueNode location) {
            if (location.isConstant()) {
                LocationIdentity locationIdentity;
                if (location.isNullConstant()) {
                    locationIdentity = LocationIdentity.ANY_LOCATION;
                } else {
                    locationIdentity = ObjectLocationIdentity.create(location.asJavaConstant());
                }
                LogicNode compare = builder.append(CompareNode.createCompareNode(Condition.EQ, condition, ConstantNode.forBoolean(true, object.graph()), builder.getConstantReflection()));
                builder.push(returnKind.getStackKind(), builder.append(new UnsafeLoadNode(object, offset, returnKind, locationIdentity, compare)));
                return true;
            }
            // TODO: should we throw GraalInternalError.shouldNotReachHere() here?
            return false;
        }
    }

    static class CustomizedUnsafeStorePlugin implements InvocationPlugin {

        private final Kind kind;

        public CustomizedUnsafeStorePlugin(Kind kind) {
            this.kind = kind;
        }

        public boolean apply(GraphBuilderContext builder, ValueNode object, ValueNode offset, ValueNode value, ValueNode location) {
            ValueNode locationArgument = location;
            if (locationArgument.isConstant()) {
                LocationIdentity locationIdentity;
                if (locationArgument.isNullConstant()) {
                    locationIdentity = LocationIdentity.ANY_LOCATION;
                } else {
                    locationIdentity = ObjectLocationIdentity.create(locationArgument.asJavaConstant());
                }

                builder.append(new UnsafeStoreNode(object, offset, value, kind, locationIdentity, null));
                return true;
            }
            // TODO: should we throw GraalInternalError.shouldNotReachHere() here?
            return false;
        }
    }
}
