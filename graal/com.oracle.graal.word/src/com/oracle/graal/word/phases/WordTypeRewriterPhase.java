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
package com.oracle.graal.word.phases;

import static com.oracle.graal.api.meta.LocationIdentity.*;

import java.lang.reflect.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.HeapAccess.BarrierType;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.word.*;
import com.oracle.graal.word.Word.Opcode;
import com.oracle.graal.word.Word.Operation;

/**
 * Transforms all uses of the {@link Word} class into unsigned operations on {@code int} or
 * {@code long} values, depending on the word kind of the underlying platform.
 */
public class WordTypeRewriterPhase extends Phase {

    protected final MetaAccessProvider metaAccess;
    protected final ResolvedJavaType wordBaseType;
    protected final ResolvedJavaType wordImplType;
    protected final Kind wordKind;

    public WordTypeRewriterPhase(MetaAccessProvider metaAccess, Kind wordKind) {
        this.metaAccess = metaAccess;
        this.wordKind = wordKind;
        this.wordBaseType = metaAccess.lookupJavaType(WordBase.class);
        this.wordImplType = metaAccess.lookupJavaType(Word.class);
    }

    @Override
    protected void run(StructuredGraph graph) {
        inferStamps(graph);

        for (Node n : graph.getNodes()) {
            if (n instanceof ValueNode) {
                changeToWord(graph, (ValueNode) n);
            }
        }

        for (Node node : graph.getNodes()) {
            rewriteNode(graph, node);
        }
    }

    /**
     * Infer the stamps for all Object nodes in the graph, to make the stamps as precise as
     * possible. For example, this propagates the word-type through phi functions. To handle phi
     * functions at loop headers, the stamp inference is called until a fix point is reached.
     * <p>
     * Note that we cannot rely on the normal canonicalizer to propagate stamps: The word type
     * rewriting must run before the first run of the canonicalizer because many nodes are not
     * prepared to see the word type during canonicalization.
     */
    protected void inferStamps(StructuredGraph graph) {
        boolean stampChanged;
        do {
            stampChanged = false;
            /*
             * We could use GraphOrder.forwardGraph() to process the nodes in a defined order and
             * propagate long def-use chains in fewer iterations. However, measurements showed that
             * we have few iterations anyway, and the overhead of computing the order is much higher
             * than the benefit.
             */
            for (Node n : graph.getNodes()) {
                if (n instanceof ValueNode) {
                    ValueNode node = (ValueNode) n;
                    if (node.kind() == Kind.Object) {
                        stampChanged |= node.inferStamp();
                    }
                }
            }
        } while (stampChanged);
    }

    /**
     * Change the stamp for word nodes from the object stamp ({@link WordBase} or anything extending
     * or implementing that interface) to the primitive word stamp.
     */
    protected void changeToWord(StructuredGraph graph, ValueNode node) {
        if (isWord(node)) {
            if (node.isConstant()) {
                ConstantNode oldConstant = (ConstantNode) node;
                assert oldConstant.value.getKind() == Kind.Object;
                WordBase value = (WordBase) oldConstant.value.asObject();
                ConstantNode newConstant = ConstantNode.forIntegerKind(wordKind, value.rawValue(), node.graph());
                graph.replaceFloating(oldConstant, newConstant);

            } else {
                node.setStamp(StampFactory.forKind(wordKind));
            }
        }
    }

    /**
     * Clean up nodes that are no longer necessary or valid after the stamp change, and perform
     * intrinsification of all methods called on word types.
     */
    protected void rewriteNode(StructuredGraph graph, Node node) {
        if (node instanceof CheckCastNode) {
            rewriteCheckCast(graph, (CheckCastNode) node);
        } else if (node instanceof UnsafeCastNode) {
            rewriteUnsafeCast(graph, (UnsafeCastNode) node);
        } else if (node instanceof LoadFieldNode) {
            rewriteLoadField(graph, (LoadFieldNode) node);
        } else if (node instanceof AccessIndexedNode) {
            rewriteAccessIndexed(graph, (AccessIndexedNode) node);
        } else if (node instanceof MethodCallTargetNode) {
            rewriteInvoke(graph, (MethodCallTargetNode) node);
        }
    }

    /**
     * Remove casts between word types (which by now no longer have kind Object).
     */
    protected void rewriteCheckCast(StructuredGraph graph, CheckCastNode node) {
        if (node.kind() == wordKind) {
            node.replaceAtUsages(node.object());
            graph.removeFixed(node);
        }
    }

    /**
     * Remove unnecessary/redundant unsafe casts.
     */
    protected void rewriteUnsafeCast(StructuredGraph graph, UnsafeCastNode node) {
        if (node.object().stamp() == node.stamp()) {
            node.replaceAtUsages(node.object());
            graph.removeFloating(node);
        }
    }

    /**
     * Fold constant field reads, e.g. enum constants.
     */
    protected void rewriteLoadField(StructuredGraph graph, LoadFieldNode node) {
        ConstantNode constant = node.asConstant(metaAccess);
        if (constant != null) {
            node.replaceAtUsages(constant);
            graph.removeFixed(node);
        }
    }

    /**
     * Change loads and stores of word-arrays. Since the element kind is managed by the node on its
     * own and not in the stamp, {@link #changeToWord} does not perform all necessary changes.
     */
    protected void rewriteAccessIndexed(StructuredGraph graph, AccessIndexedNode node) {
        ResolvedJavaType arrayType = ObjectStamp.typeOrNull(node.array());
        /*
         * There are cases where the array does not have a known type yet, i.e., the type is null.
         * In that case we assume it is not a word type.
         */
        if (arrayType != null && isWord(arrayType.getComponentType()) && node.elementKind() != wordKind) {
            /*
             * The elementKind of the node is a final field, and other information such as the stamp
             * depends on elementKind. Therefore, just create a new node and replace the old one.
             */
            if (node instanceof LoadIndexedNode) {
                graph.replaceFixedWithFixed(node, graph.add(new LoadIndexedNode(node.array(), node.index(), wordKind)));
            } else if (node instanceof StoreIndexedNode) {
                graph.replaceFixedWithFixed(node, graph.add(new StoreIndexedNode(node.array(), node.index(), wordKind, ((StoreIndexedNode) node).value())));
            } else {
                throw GraalInternalError.shouldNotReachHere();
            }
        }
    }

    /**
     * Intrinsification of methods defined on the {@link Word} class that are annotated with
     * {@link Operation}.
     */
    protected void rewriteInvoke(StructuredGraph graph, MethodCallTargetNode callTargetNode) {
        ResolvedJavaMethod targetMethod = callTargetNode.targetMethod();
        if (!wordBaseType.isAssignableFrom(targetMethod.getDeclaringClass())) {
            /* Not a method defined on WordBase or a subclass / subinterface, so nothing to rewrite. */
            return;
        }

        if (!callTargetNode.isStatic()) {
            assert callTargetNode.receiver().kind() == wordKind : "changeToWord() missed the receiver";
            targetMethod = wordImplType.resolveMethod(targetMethod);
        }
        Operation operation = targetMethod.getAnnotation(Word.Operation.class);
        assert operation != null : targetMethod;

        NodeInputList<ValueNode> arguments = callTargetNode.arguments();
        Invoke invoke = callTargetNode.invoke();

        switch (operation.opcode()) {
            case NODE_CLASS:
                assert arguments.size() == 2;
                ValueNode left = arguments.get(0);
                ValueNode right = operation.rightOperandIsInt() ? toUnsigned(graph, arguments.get(1), Kind.Int) : fromSigned(graph, arguments.get(1));

                ValueNode replacement = graph.addOrUnique(createBinaryNodeInstance(operation.node(), wordKind, left, right));
                if (replacement instanceof FixedWithNextNode) {
                    graph.addBeforeFixed(invoke.asNode(), (FixedWithNextNode) replacement);
                }
                replace(invoke, replacement);
                break;

            case COMPARISON:
                assert arguments.size() == 2;
                replace(invoke, comparisonOp(graph, operation.condition(), arguments.get(0), fromSigned(graph, arguments.get(1))));
                break;

            case NOT:
                assert arguments.size() == 1;
                replace(invoke, graph.unique(new XorNode(wordKind, arguments.get(0), ConstantNode.forIntegerKind(wordKind, -1, graph))));
                break;

            case READ: {
                assert arguments.size() == 2 || arguments.size() == 3;
                Kind readKind = asKind(callTargetNode.returnType());
                LocationNode location;
                if (arguments.size() == 2) {
                    location = makeLocation(graph, arguments.get(1), readKind, ANY_LOCATION);
                } else {
                    location = makeLocation(graph, arguments.get(1), readKind, arguments.get(2));
                }
                replace(invoke, readOp(graph, arguments.get(0), invoke, location, BarrierType.NONE, false));
                break;
            }
            case READ_HEAP: {
                assert arguments.size() == 4;
                Kind readKind = asKind(callTargetNode.returnType());
                LocationNode location = makeLocation(graph, arguments.get(1), readKind, ANY_LOCATION);
                BarrierType barrierType = (BarrierType) arguments.get(2).asConstant().asObject();
                replace(invoke, readOp(graph, arguments.get(0), invoke, location, barrierType, arguments.get(3).asConstant().asInt() == 0 ? false : true));
                break;
            }
            case WRITE:
            case INITIALIZE: {
                assert arguments.size() == 3 || arguments.size() == 4;
                Kind writeKind = asKind(targetMethod.getSignature().getParameterType(1, targetMethod.getDeclaringClass()));
                LocationNode location;
                if (arguments.size() == 3) {
                    location = makeLocation(graph, arguments.get(1), writeKind, LocationIdentity.ANY_LOCATION);
                } else {
                    location = makeLocation(graph, arguments.get(1), writeKind, arguments.get(3));
                }
                replace(invoke, writeOp(graph, arguments.get(0), arguments.get(2), invoke, location, operation.opcode()));
                break;
            }
            case ZERO:
                assert arguments.size() == 0;
                replace(invoke, ConstantNode.forIntegerKind(wordKind, 0L, graph));
                break;

            case FROM_UNSIGNED:
                assert arguments.size() == 1;
                replace(invoke, fromUnsigned(graph, arguments.get(0)));
                break;

            case FROM_SIGNED:
                assert arguments.size() == 1;
                replace(invoke, fromSigned(graph, arguments.get(0)));
                break;

            case TO_RAW_VALUE:
                assert arguments.size() == 1;
                replace(invoke, toUnsigned(graph, arguments.get(0), Kind.Long));
                break;

            case FROM_OBJECT:
                assert arguments.size() == 1;
                replace(invoke, graph.unique(new UnsafeCastNode(arguments.get(0), StampFactory.forKind(wordKind))));
                break;

            case FROM_ARRAY:
                assert arguments.size() == 2;
                replace(invoke, graph.unique(new ComputeAddressNode(arguments.get(0), arguments.get(1), StampFactory.forKind(wordKind))));
                break;

            case TO_OBJECT:
                assert arguments.size() == 1;
                replace(invoke, graph.unique(new UnsafeCastNode(arguments.get(0), invoke.asNode().stamp())));
                break;

            default:
                throw new GraalInternalError("Unknown opcode: %s", operation.opcode());
        }
    }

    protected ValueNode fromUnsigned(StructuredGraph graph, ValueNode value) {
        return convert(graph, value, wordKind, ConvertNode.Op.L2I, ConvertNode.Op.UNSIGNED_I2L);
    }

    private ValueNode fromSigned(StructuredGraph graph, ValueNode value) {
        return convert(graph, value, wordKind, ConvertNode.Op.L2I, ConvertNode.Op.I2L);
    }

    protected ValueNode toUnsigned(StructuredGraph graph, ValueNode value, Kind toKind) {
        return convert(graph, value, toKind, ConvertNode.Op.L2I, ConvertNode.Op.UNSIGNED_I2L);
    }

    private static ValueNode convert(StructuredGraph graph, ValueNode value, Kind toKind, ConvertNode.Op longToIntOp, ConvertNode.Op intToLongOp) {
        assert longToIntOp.from == Kind.Long && longToIntOp.to == Kind.Int;
        assert intToLongOp.from == Kind.Int && intToLongOp.to == Kind.Long;
        if (value.kind() == toKind) {
            return value;
        }

        if (toKind == Kind.Int) {
            assert value.kind() == Kind.Long;
            return graph.unique(new ConvertNode(longToIntOp, value));
        } else {
            assert toKind == Kind.Long;
            assert value.kind().getStackKind() == Kind.Int;
            return graph.unique(new ConvertNode(intToLongOp, value));
        }
    }

    /**
     * Create an instance of a binary node which is used to lower Word operations. This method is
     * called for all Word operations which are annotated with @Operation(node = ...) and
     * encapsulates the reflective allocation of the node.
     */
    private static ValueNode createBinaryNodeInstance(Class<? extends ValueNode> nodeClass, Kind kind, ValueNode left, ValueNode right) {
        try {
            Constructor<? extends ValueNode> constructor = nodeClass.getConstructor(Kind.class, ValueNode.class, ValueNode.class);
            return constructor.newInstance(kind, left, right);
        } catch (Throwable ex) {
            throw new GraalInternalError(ex).addContext(nodeClass.getName());
        }
    }

    private ValueNode comparisonOp(StructuredGraph graph, Condition condition, ValueNode left, ValueNode right) {
        assert left.kind() == wordKind && right.kind() == wordKind;

        // mirroring gets the condition into canonical form
        boolean mirror = condition.canonicalMirror();

        ValueNode a = mirror ? right : left;
        ValueNode b = mirror ? left : right;

        CompareNode comparison;
        if (condition == Condition.EQ || condition == Condition.NE) {
            comparison = new IntegerEqualsNode(a, b);
        } else if (condition.isUnsigned()) {
            comparison = new IntegerBelowThanNode(a, b);
        } else {
            comparison = new IntegerLessThanNode(a, b);
        }

        ConstantNode trueValue = ConstantNode.forInt(1, graph);
        ConstantNode falseValue = ConstantNode.forInt(0, graph);

        if (condition.canonicalNegate()) {
            ConstantNode temp = trueValue;
            trueValue = falseValue;
            falseValue = temp;
        }
        ConditionalNode materialize = graph.unique(new ConditionalNode(graph.unique(comparison), trueValue, falseValue));
        return materialize;
    }

    private LocationNode makeLocation(StructuredGraph graph, ValueNode offset, Kind readKind, ValueNode locationIdentity) {
        if (locationIdentity.isConstant()) {
            return makeLocation(graph, offset, readKind, (LocationIdentity) locationIdentity.asConstant().asObject());
        }
        return SnippetLocationNode.create(locationIdentity, ConstantNode.forObject(readKind, metaAccess, graph), ConstantNode.forLong(0, graph), offset, ConstantNode.forInt(1, graph), graph);
    }

    protected LocationNode makeLocation(StructuredGraph graph, ValueNode offset, Kind readKind, LocationIdentity locationIdentity) {
        return IndexedLocationNode.create(locationIdentity, readKind, 0, offset, graph, 1);
    }

    protected ValueNode readOp(StructuredGraph graph, ValueNode base, Invoke invoke, LocationNode location, BarrierType barrierType, boolean compressible) {
        ReadNode read = graph.add(new ReadNode(base, location, invoke.asNode().stamp(), barrierType, compressible));
        graph.addBeforeFixed(invoke.asNode(), read);
        /*
         * The read must not float outside its block otherwise it may float above an explicit zero
         * check on its base address.
         */
        read.setGuard(AbstractBeginNode.prevBegin(invoke.asNode()));
        return read;
    }

    protected ValueNode writeOp(StructuredGraph graph, ValueNode base, ValueNode value, Invoke invoke, LocationNode location, Opcode op) {
        assert op == Opcode.WRITE || op == Opcode.INITIALIZE;
        WriteNode write = graph.add(new WriteNode(base, value, location, BarrierType.NONE, false, op == Opcode.INITIALIZE));
        write.setStateAfter(invoke.stateAfter());
        graph.addBeforeFixed(invoke.asNode(), write);
        return write;
    }

    protected void replace(Invoke invoke, ValueNode value) {
        FixedNode next = invoke.next();
        invoke.setNext(null);
        invoke.asNode().replaceAtPredecessor(next);
        invoke.asNode().replaceAtUsages(value);
        GraphUtil.killCFG(invoke.asNode());
    }

    protected boolean isWord(ValueNode node) {
        return isWord(ObjectStamp.typeOrNull(node));
    }

    protected boolean isWord(ResolvedJavaType type) {
        return type != null && wordBaseType.isAssignableFrom(type);
    }

    protected Kind asKind(JavaType type) {
        if (type instanceof ResolvedJavaType && isWord((ResolvedJavaType) type)) {
            return wordKind;
        } else {
            return type.getKind();
        }
    }
}
