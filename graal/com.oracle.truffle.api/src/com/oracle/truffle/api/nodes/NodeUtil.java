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
package com.oracle.truffle.api.nodes;

import java.io.*;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;

import sun.misc.*;

import com.oracle.truffle.api.nodes.Node.Child;
import com.oracle.truffle.api.nodes.Node.Children;

/**
 * Utility class that manages the special access methods for node instances.
 */
public class NodeUtil {

    /**
     * Interface that allows the customization of field offsets used for {@link Unsafe} field
     * accesses.
     */
    public interface FieldOffsetProvider {

        long objectFieldOffset(Field field);
    }

    private static final FieldOffsetProvider unsafeFieldOffsetProvider = new FieldOffsetProvider() {

        @Override
        public long objectFieldOffset(Field field) {
            return unsafe.objectFieldOffset(field);
        }
    };

    public static enum NodeFieldKind {
        /** The single {@link Node#getParent() parent} field. */
        PARENT,
        /** A field annotated with {@link Child}. */
        CHILD,
        /** A field annotated with {@link Children}. */
        CHILDREN,
        /** A normal non-child data field of the node. */
        DATA
    }

    /**
     * Information about a field in a {@link Node} class.
     */
    public static final class NodeField {

        private final NodeFieldKind kind;
        private final Class<?> type;
        private final String name;
        private long offset;

        protected NodeField(NodeFieldKind kind, Class<?> type, String name, long offset) {
            this.kind = kind;
            this.type = type;
            this.name = name;
            this.offset = offset;
        }

        public NodeFieldKind getKind() {
            return kind;
        }

        public Class<?> getType() {
            return type;
        }

        public String getName() {
            return name;
        }

        public long getOffset() {
            return offset;
        }

        public Object loadValue(Node node) {
            if (type == boolean.class) {
                return unsafe.getBoolean(node, offset);
            } else if (type == byte.class) {
                return unsafe.getByte(node, offset);
            } else if (type == short.class) {
                return unsafe.getShort(node, offset);
            } else if (type == char.class) {
                return unsafe.getChar(node, offset);
            } else if (type == int.class) {
                return unsafe.getInt(node, offset);
            } else if (type == long.class) {
                return unsafe.getLong(node, offset);
            } else if (type == float.class) {
                return unsafe.getFloat(node, offset);
            } else if (type == double.class) {
                return unsafe.getDouble(node, offset);
            } else {
                return unsafe.getObject(node, offset);
            }
        }
    }

    /**
     * Information about a {@link Node} class. A single instance of this class is allocated for
     * every subclass of {@link Node} that is used.
     */
    public static final class NodeClass {

        private static final Map<Class<?>, NodeClass> nodeClasses = new IdentityHashMap<>();

        // The comprehensive list of all fields.
        private final NodeField[] fields;
        // Separate arrays for the frequently accessed field offsets.
        private final long parentOffset;
        private final long[] childOffsets;
        private final long[] childrenOffsets;

        public static NodeClass get(Class<? extends Node> clazz) {
            NodeClass nodeClass = nodeClasses.get(clazz);
            if (nodeClass == null) {
                nodeClass = new NodeClass(clazz, unsafeFieldOffsetProvider);
                nodeClasses.put(clazz, nodeClass);
            }
            return nodeClass;
        }

        public NodeClass(Class<? extends Node> clazz, FieldOffsetProvider fieldOffsetProvider) {
            List<NodeField> fieldsList = new ArrayList<>();
            List<Long> parentOffsetsList = new ArrayList<>();
            List<Long> childOffsetsList = new ArrayList<>();
            List<Long> childrenOffsetsList = new ArrayList<>();

            for (Field field : getAllFields(clazz)) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }

                NodeFieldKind kind;
                if (Node.class.isAssignableFrom(field.getType()) && field.getName().equals("parent") && field.getDeclaringClass() == Node.class) {
                    kind = NodeFieldKind.PARENT;
                    parentOffsetsList.add(fieldOffsetProvider.objectFieldOffset(field));
                } else if (Node.class.isAssignableFrom(field.getType()) && field.getAnnotation(Child.class) != null) {
                    kind = NodeFieldKind.CHILD;
                    childOffsetsList.add(fieldOffsetProvider.objectFieldOffset(field));
                } else if (field.getType().isArray() && Node.class.isAssignableFrom(field.getType().getComponentType()) && field.getAnnotation(Children.class) != null) {
                    kind = NodeFieldKind.CHILDREN;
                    childrenOffsetsList.add(fieldOffsetProvider.objectFieldOffset(field));
                } else {
                    kind = NodeFieldKind.DATA;
                }
                fieldsList.add(new NodeField(kind, field.getType(), field.getName(), fieldOffsetProvider.objectFieldOffset(field)));
            }
            this.fields = fieldsList.toArray(new NodeField[fieldsList.size()]);
            assert parentOffsetsList.size() == 1 : "must have exactly one parent field";
            this.parentOffset = parentOffsetsList.get(0);
            this.childOffsets = toLongArray(childOffsetsList);
            this.childrenOffsets = toLongArray(childrenOffsetsList);
        }

        public NodeField[] getFields() {
            return fields;
        }

        public long getParentOffset() {
            return parentOffset;
        }

        public long[] getChildOffsets() {
            return childOffsets;
        }

        public long[] getChildrenOffsets() {
            return childrenOffsets;
        }
    }

    static class NodeIterator implements Iterator<Node> {

        private final Node node;
        private final NodeClass nodeClass;
        private final int childrenCount;
        private int index;

        protected NodeIterator(Node node) {
            this.node = node;
            this.index = 0;
            this.nodeClass = NodeClass.get(node.getClass());
            this.childrenCount = childrenCount();
        }

        private int childrenCount() {
            int nodeCount = nodeClass.childOffsets.length;
            for (long fieldOffset : nodeClass.childrenOffsets) {
                Node[] children = ((Node[]) unsafe.getObject(node, fieldOffset));
                if (children != null) {
                    nodeCount += children.length;
                }
            }
            return nodeCount;
        }

        private Node nodeAt(int idx) {
            int nodeCount = nodeClass.childOffsets.length;
            if (idx < nodeCount) {
                return (Node) unsafe.getObject(node, nodeClass.childOffsets[idx]);
            } else {
                for (long fieldOffset : nodeClass.childrenOffsets) {
                    Node[] nodeArray = (Node[]) unsafe.getObject(node, fieldOffset);
                    if (idx < nodeCount + nodeArray.length) {
                        return nodeArray[idx - nodeCount];
                    }
                    nodeCount += nodeArray.length;
                }
            }
            return null;
        }

        private void forward() {
            if (index < childrenCount) {
                index++;
            }
        }

        @Override
        public boolean hasNext() {
            return index < childrenCount;
        }

        @Override
        public Node next() {
            try {
                return nodeAt(index);
            } finally {
                forward();
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private static long[] toLongArray(List<Long> list) {
        long[] array = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    protected static final Unsafe unsafe = getUnsafe();

    private static Unsafe getUnsafe() {
        try {
            return Unsafe.getUnsafe();
        } catch (SecurityException e) {
        }
        try {
            Field theUnsafeInstance = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeInstance.setAccessible(true);
            return (Unsafe) theUnsafeInstance.get(Unsafe.class);
        } catch (Exception e) {
            throw new RuntimeException("exception while trying to get Unsafe.theUnsafe via reflection:", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T extends Node> T cloneNode(T orig) {
        Class<? extends Node> clazz = orig.getClass();
        NodeClass nodeClass = NodeClass.get(clazz);
        Node clone = orig.copy();
        if (clone == null) {
            return null;
        }

        unsafe.putObject(clone, nodeClass.parentOffset, null);

        for (long fieldOffset : nodeClass.childOffsets) {
            Node child = (Node) unsafe.getObject(orig, fieldOffset);
            if (child != null) {
                Node clonedChild = cloneNode(child);
                if (clonedChild == null) {
                    return null;
                }

                unsafe.putObject(clonedChild, nodeClass.parentOffset, clone);
                unsafe.putObject(clone, fieldOffset, clonedChild);
            }
        }
        for (long fieldOffset : nodeClass.childrenOffsets) {
            Node[] children = (Node[]) unsafe.getObject(orig, fieldOffset);
            if (children != null) {
                Node[] clonedChildren = children.clone();
                Arrays.fill(clonedChildren, null);
                for (int i = 0; i < children.length; i++) {
                    Node clonedChild = cloneNode(children[i]);
                    if (clonedChild == null) {
                        return null;
                    }

                    clonedChildren[i] = clonedChild;
                    unsafe.putObject(clonedChild, nodeClass.parentOffset, clone);
                }
                unsafe.putObject(clone, fieldOffset, clonedChildren);
            }
        }
        return (T) clone;
    }

    public static List<Node> findNodeChildren(Node node) {
        List<Node> nodes = new ArrayList<>();
        NodeClass nodeClass = NodeClass.get(node.getClass());

        for (long fieldOffset : nodeClass.childOffsets) {
            Object child = unsafe.getObject(node, fieldOffset);
            if (child != null) {
                nodes.add((Node) child);
            }
        }
        for (long fieldOffset : nodeClass.childrenOffsets) {
            Node[] children = (Node[]) unsafe.getObject(node, fieldOffset);
            if (children != null) {
                nodes.addAll(Arrays.asList(children));
            }
        }

        return nodes;
    }

    public static void replaceChild(Node parent, Node oldChild, Node newChild) {
        NodeClass nodeClass = NodeClass.get(parent.getClass());

        long[] fieldOffsets = nodeClass.childOffsets;
        for (int i = 0; i < fieldOffsets.length; i++) {
            long fieldOffset = fieldOffsets[i];
            if (unsafe.getObject(parent, fieldOffset) == oldChild) {
                assert assertAssignable(nodeClass, parent, oldChild, newChild);
                unsafe.putObject(parent, fieldOffset, newChild);
            }
        }

        long[] childrenOffsets = nodeClass.childrenOffsets;
        for (int i = 0; i < childrenOffsets.length; i++) {
            long fieldOffset = childrenOffsets[i];
            Object arrayObject = unsafe.getObject(parent, fieldOffset);
            if (arrayObject != null) {
                assert arrayObject instanceof Node[] : "Children must be instanceof Node[] ";
                Node[] array = (Node[]) arrayObject;
                for (int j = 0; j < array.length; j++) {
                    if (array[j] == oldChild) {
                        assert newChild != null && array.getClass().getComponentType().isAssignableFrom(newChild.getClass()) : "Array type does not match";
                        array[j] = newChild;
                        return;
                    }
                }
            }
        }
    }

    private static boolean assertAssignable(NodeClass clazz, Node parent, Object oldValue, Object newValue) {
        if (newValue == null) {
            return true;
        }
        for (NodeField field : clazz.fields) {
            if (field.kind != NodeFieldKind.CHILD) {
                continue;
            }
            if (unsafe.getObject(parent, field.offset) == oldValue) {
                if (!field.type.isAssignableFrom(newValue.getClass())) {
                    assert false : "Child class " + newValue.getClass() + " is not assignable to field " + field.type.getName() + " at " + field.name + " in ";
                    return false;
                } else {
                    break;
                }
            }
        }
        return true;
    }

    /** Returns all declared fields in the class hierarchy. */
    private static Field[] getAllFields(Class<? extends Object> clazz) {
        Field[] declaredFields = clazz.getDeclaredFields();
        if (clazz.getSuperclass() != null) {
            return concat(getAllFields(clazz.getSuperclass()), declaredFields);
        }
        return declaredFields;
    }

    public static <T> T[] concat(T[] first, T[] second) {
        T[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /** find annotation in class/interface hierarchy. */
    public static <T extends Annotation> T findAnnotation(Class<?> clazz, Class<T> annotationClass) {
        if (clazz.getAnnotation(annotationClass) != null) {
            return clazz.getAnnotation(annotationClass);
        } else {
            for (Class<?> intf : clazz.getInterfaces()) {
                if (intf.getAnnotation(annotationClass) != null) {
                    return intf.getAnnotation(annotationClass);
                }
            }
            if (clazz.getSuperclass() != null) {
                return findAnnotation(clazz.getSuperclass(), annotationClass);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static <T extends Node> T findParent(final Node start, final Class<T> clazz) {
        assert start != null;
        if (clazz.isInstance(start.getParent())) {
            return (T) start.getParent();
        } else {
            return start.getParent() != null ? findParent(start.getParent(), clazz) : null;
        }
    }

    @SuppressWarnings("unchecked")
    public static <I> I findParentInterface(final Node start, final Class<I> clazz) {
        assert start != null;
        if (clazz.isInstance(start.getParent())) {
            return (I) start.getParent();
        } else {
            return (start.getParent() != null ? findParentInterface(start.getParent(), clazz) : null);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T findFirstNodeInstance(Node root, Class<T> clazz) {
        for (Node childNode : findNodeChildren(root)) {
            if (clazz.isInstance(childNode)) {
                return (T) childNode;
            } else {
                T node = findFirstNodeInstance(childNode, clazz);
                if (node != null) {
                    return node;
                }
            }
        }
        return null;
    }

    public static <T extends Node> List<T> findAllNodeInstances(final Node root, final Class<T> clazz) {
        final List<T> nodeList = new ArrayList<>();
        root.accept(new NodeVisitor() {

            @SuppressWarnings("unchecked")
            @Override
            public boolean visit(Node node) {
                if (clazz.isInstance(node)) {
                    nodeList.add((T) node);
                }
                return true;
            }
        });
        return nodeList;
    }

    // Don't visit found node instances.
    public static <T extends Node> List<T> findNodeInstancesShallow(final Node root, final Class<T> clazz) {
        final List<T> nodeList = new ArrayList<>();
        root.accept(new NodeVisitor() {

            @SuppressWarnings("unchecked")
            @Override
            public boolean visit(Node node) {
                if (clazz.isInstance(node)) {
                    nodeList.add((T) node);
                    return false;
                }
                return true;
            }
        });
        return nodeList;
    }

    /** Find node instances within current function only (not in nested functions). */
    public static <T extends Node> List<T> findNodeInstancesInFunction(final Node root, final Class<T> clazz) {
        final List<T> nodeList = new ArrayList<>();
        root.accept(new NodeVisitor() {

            @SuppressWarnings("unchecked")
            @Override
            public boolean visit(Node node) {
                if (clazz.isInstance(node)) {
                    nodeList.add((T) node);
                } else if (node instanceof RootNode && node != root) {
                    return false;
                }
                return true;
            }
        });
        return nodeList;
    }

    public static <I> List<I> findNodeInstancesInFunctionInterface(final Node root, final Class<I> clazz) {
        final List<I> nodeList = new ArrayList<>();
        root.accept(new NodeVisitor() {

            @SuppressWarnings("unchecked")
            @Override
            public boolean visit(Node node) {
                if (clazz.isInstance(node)) {
                    nodeList.add((I) node);
                } else if (node instanceof RootNode && node != root) {
                    return false;
                }
                return true;
            }
        });
        return nodeList;
    }

    public static int countNodes(Node root) {
        NodeCountVisitor nodeCount = new NodeCountVisitor();
        root.accept(nodeCount);
        return nodeCount.nodeCount;
    }

    private static class NodeCountVisitor implements NodeVisitor {

        int nodeCount;

        @Override
        public boolean visit(Node node) {
            if (node instanceof RootNode && nodeCount > 0) {
                return false;
            }
            nodeCount++;
            return true;
        }
    }

    public static String printCompactTreeToString(Node node) {
        StringWriter out = new StringWriter();
        printCompactTree(new PrintWriter(out), null, node, 1);
        return out.toString();
    }

    public static void printCompactTree(OutputStream out, Node node) {
        printCompactTree(new PrintWriter(out), null, node, 1);
    }

    private static void printCompactTree(PrintWriter p, Node parent, Node node, int level) {
        if (node == null) {
            return;
        }
        for (int i = 0; i < level; i++) {
            p.print("  ");
        }
        if (parent == null) {
            p.println(node.getClass().getSimpleName());
        } else {
            String fieldName = "unknownField";
            NodeField[] fields = NodeClass.get(parent.getClass()).fields;
            for (NodeField field : fields) {
                Object value = field.loadValue(parent);
                if (value == node) {
                    fieldName = field.getName();
                    break;
                } else if (value instanceof Node[]) {
                    int index = 0;
                    for (Node arrayNode : (Node[]) value) {
                        if (arrayNode == node) {
                            fieldName = field.getName() + "[" + index + "]";
                            break;
                        }
                        index++;
                    }
                }
            }
            p.print(fieldName);
            p.print(" = ");
            p.println(node.getClass().getSimpleName());
        }

        for (Node child : node.getChildren()) {
            printCompactTree(p, node, child, level + 1);
        }
        p.flush();
    }

    /**
     * Prints a human readable form of a {@link Node} AST to the given {@link PrintStream}. This
     * print method does not check for cycles in the node structure.
     * 
     * @param out the stream to print to.
     * @param node the root node to write
     */
    public static void printTree(OutputStream out, Node node) {
        printTree(new PrintWriter(out), node);
    }

    public static String printTreeToString(Node node) {
        StringWriter out = new StringWriter();
        printTree(new PrintWriter(out), node);
        return out.toString();
    }

    public static void printTree(PrintWriter p, Node node) {
        printTree(p, node, 1);
        p.println();
        p.flush();
    }

    private static void printTree(PrintWriter p, Node node, int level) {
        if (node == null) {
            p.print("null");
            return;
        }

        p.print(node.getClass().getSimpleName());

        ArrayList<NodeField> childFields = new ArrayList<>();
        String sep = "";
        p.print("(");
        for (NodeField field : NodeClass.get(node.getClass()).fields) {
            if (field.getKind() == NodeFieldKind.CHILD || field.getKind() == NodeFieldKind.CHILDREN) {
                childFields.add(field);
            } else if (field.getKind() == NodeFieldKind.DATA) {
                p.print(sep);
                sep = ", ";

                p.print(field.getName());
                p.print(" = ");
                p.print(field.loadValue(node));
            }
        }
        p.print(")");

        if (childFields.size() != 0) {
            p.print(" {");
            for (NodeField field : childFields) {
                printNewLine(p, level);
                p.print(field.getName());

                Object value = field.loadValue(node);
                if (value == null) {
                    p.print(" = null ");
                } else if (field.getKind() == NodeFieldKind.CHILD) {
                    p.print(" = ");
                    printTree(p, (Node) value, level + 1);
                } else if (field.getKind() == NodeFieldKind.CHILDREN) {
                    Node[] children = (Node[]) value;
                    p.print(" = [");
                    sep = "";
                    for (Node child : children) {
                        p.print(sep);
                        sep = ", ";
                        printTree(p, child, level + 1);
                    }
                    p.print("]");
                }
            }
            printNewLine(p, level - 1);
            p.print("}");
        }
    }

    private static void printNewLine(PrintWriter p, int level) {
        p.println();
        for (int i = 0; i < level; i++) {
            p.print("    ");
        }
    }

}
