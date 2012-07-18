/*
 * Copyright (c) 2012, 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package com.sun.hotspot.igv.data.serialization;

import com.sun.hotspot.igv.data.*;
import com.sun.hotspot.igv.data.Properties;
import com.sun.hotspot.igv.data.services.GroupCallback;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;

public class BinaryParser implements GraphParser {
    private static final int BEGIN_GROUP = 0x00;
    private static final int BEGIN_GRAPH = 0x01;
    private static final int CLOSE_GROUP = 0x02;

    private static final int POOL_NEW = 0x00;
    private static final int POOL_STRING = 0x01;
    private static final int POOL_ENUM = 0x02;
    private static final int POOL_CLASS = 0x03;
    private static final int POOL_METHOD = 0x04;
    private static final int POOL_NULL = 0x05;
    private static final int POOL_NODE_CLASS = 0x06;
    private static final int POOL_FIELD = 0x07;
    private static final int POOL_SIGNATURE = 0x08;
    
    private static final int KLASS = 0x00;
    private static final int ENUM_KLASS = 0x01;
    
    private static final int PROPERTY_POOL = 0x00;
    private static final int PROPERTY_INT = 0x01;
    private static final int PROPERTY_LONG = 0x02;
    private static final int PROPERTY_DOUBLE = 0x03;
    private static final int PROPERTY_FLOAT = 0x04;
    private static final int PROPERTY_TRUE = 0x05;
    private static final int PROPERTY_FALSE = 0x06;
    private static final int PROPERTY_ARRAY = 0x07;
    
    private static final String NO_BLOCK = "noBlock";
    
    private final GroupCallback callback;
    private final List<Object> constantPool;
    private final ByteBuffer buffer;
    private final ReadableByteChannel channel;
    private final Deque<Folder> folderStack;
    private final ParseMonitor monitor;
    
    private enum Length {
        S,
        M,
        L
    }
    
    private interface LengthToString {
        String toString(Length l);
    }
    
    private static abstract class Member implements LengthToString {
        public final Klass holder;
        public final int accessFlags;
        public final String name;
        public Member(Klass holder, String name, int accessFlags) {
            this.holder = holder;
            this.accessFlags = accessFlags;
            this.name = name;
        }
    }
    
    private static class Method extends Member {
        public final Signature signature;
        public final byte[] code;
        public Method(String name, Signature signature, byte[] code, Klass holder, int accessFlags) {
            super(holder, name, accessFlags);
            this.signature = signature;
            this.code = code;
        }
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(holder).append('.').append(name).append('(');
            for (int i = 0; i < signature.argTypes.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(signature.argTypes[i]);
            }
            sb.append(')');
            return sb.toString();
        }
        @Override
        public String toString(Length l) {
            switch(l) {
                case M:
                    return holder.toString(Length.L) + "." + name;
                case S:
                    return holder.toString(Length.S) + "." + name;
                default:
                case L:
                    return toString();
            }
        }
    }
    
    private static class Signature {
        public final String returnType;
        public final String[] argTypes;
        public Signature(String returnType, String[] argTypes) {
            this.returnType = returnType;
            this.argTypes = argTypes;
        }
    }
    
    private static class Field extends Member {
        public final String type;
        public Field(String type, Klass holder, String name, int accessFlags) {
            super(holder, name, accessFlags);
            this.type = type;
        }
        @Override
        public String toString() {
            return holder + "." + name;
        }
        @Override
        public String toString(Length l) {
            switch(l) {
                case M:
                    return holder.toString(Length.L) + "." + name;
                case S:
                    return holder.toString(Length.S) + "." + name;
                default:
                case L:
                    return toString();
            }
        }
    }
    
    private static class Klass implements LengthToString {
        public final String name;
        public final String simpleName;
        public Klass(String name) {
            this.name = name;
            String simple;
            try {
                simple = name.substring(name.lastIndexOf('.') + 1);
            } catch (IndexOutOfBoundsException ioobe) {
                simple = name;
            }
            this.simpleName = simple;
        }
        @Override
        public String toString() {
            return name;
        }
        @Override
        public String toString(Length l) {
            switch(l) {
                case S:
                    return simpleName;
                default:
                case L:
                case M:
                    return toString();
            }
        }
    }
    
    private static class EnumKlass extends Klass {
        public final String[] values;
        public EnumKlass(String name, String[] values) {
            super(name);
            this.values = values;
        }
    }
    
    private static class NodeClass {
        public final String className;
        public final String nameTemplate;
        public final List<String> inputs;
        public final List<String> sux;
        private NodeClass(String className, String nameTemplate, List<String> inputs, List<String> sux) {
            this.className = className;
            this.nameTemplate = nameTemplate;
            this.inputs = inputs;
            this.sux = sux;
        }
        @Override
        public String toString() {
            return className;
        }
    }
    
    private static class EnumValue implements LengthToString {
        public EnumKlass enumKlass;
        public int ordinal;
        public EnumValue(EnumKlass enumKlass, int ordinal) {
            this.enumKlass = enumKlass;
            this.ordinal = ordinal;
        }
        @Override
        public String toString() {
            return enumKlass.simpleName + "." + enumKlass.values[ordinal];
        }
        @Override
        public String toString(Length l) {
            switch(l) {
                case S:
                    return enumKlass.values[ordinal];
                default:
                case M:
                case L:
                    return toString();
            }
        }
    }

    public BinaryParser(ReadableByteChannel channel, ParseMonitor monitor, GroupCallback callback) {
        this.callback = callback;
        constantPool = new ArrayList<>();
        buffer = ByteBuffer.allocateDirect(256 * 1024);
        buffer.flip();
        this.channel = channel;
        folderStack = new LinkedList<>();
        this.monitor = monitor;
    }
    
    private void fill() throws IOException {
        buffer.compact();
        if (channel.read(buffer) < 0) {
            throw new EOFException();
        }
        buffer.flip();
    }
    
    private void ensureAvailable(int i) throws IOException {
        while (buffer.remaining() < i) {
            fill();
        }
    }
    
    private int readByte() throws IOException {
        ensureAvailable(1);
        return ((int)buffer.get()) & 0xff;
    }

    private int readInt() throws IOException {
        ensureAvailable(4);
        return buffer.getInt();
    }
    
    private char readShort() throws IOException {
        ensureAvailable(2);
        return buffer.getChar();
    }
    
    private long readLong() throws IOException {
        ensureAvailable(8);
        return buffer.getLong();
    }
    
    private double readDouble() throws IOException {
        ensureAvailable(8);
        return buffer.getDouble();
    }
    
    private float readFloat() throws IOException {
        ensureAvailable(4);
        return buffer.getFloat();
    }

    private String readString() throws IOException {
        int len = readInt();
        ensureAvailable(len * 2);
        char[] chars = new char[len];
        for (int i = 0; i < len; i++) {
            chars[i] = buffer.getChar();
        }
        return new String(chars);
    }

    private byte[] readBytes() throws IOException {
        int len = readInt();
        if (len < 0) {
            return null;
        }
        ensureAvailable(len);
        byte[] data = new byte[len];
        buffer.get(data);
        return data;
    }
    
    private String readIntsToString() throws IOException {
        int len = readInt();
        if (len < 0) {
            return "null";
        }
        ensureAvailable(len * 4);
        StringBuilder sb = new StringBuilder().append('[');
        for (int i = 0; i < len; i++) {
            sb.append(buffer.getInt());
            if (i < len - 1) {
                sb.append(", ");
            }
        }
        sb.append(']');
        return sb.toString();
    }
    
    private String readDoublesToString() throws IOException {
        int len = readInt();
        if (len < 0) {
            return "null";
        }
        ensureAvailable(len * 8);
        StringBuilder sb = new StringBuilder().append('[');
        for (int i = 0; i < len; i++) {
            sb.append(buffer.getDouble());
            if (i < len - 1) {
                sb.append(", ");
            }
        }
        sb.append(']');
        return sb.toString();
    }
    
    private String readPoolObjectsToString() throws IOException {
        int len = readInt();
        if (len < 0) {
            return "null";
        }
        StringBuilder sb = new StringBuilder().append('[');
        for (int i = 0; i < len; i++) {
            sb.append(readPoolObject(Object.class));
            if (i < len - 1) {
                sb.append(", ");
            }
        }
        sb.append(']');
        return sb.toString();
    }
    
    private <T> T readPoolObject(Class<T> klass) throws IOException {
        int type = readByte();
        if (type == POOL_NULL) {
            return null;
        }
        if (type == POOL_NEW) {
            return (T) addPoolEntry(klass);
        }
        assert assertObjectType(klass, type);
        int index = readInt();
        if (index < 0 || index >= constantPool.size()) {
            throw new IOException("Invalid constant pool index : " + index);
        }
        Object obj = constantPool.get(index);
        return (T) obj;
    }
    
    private boolean assertObjectType(Class<?> klass, int type) {
        switch(type) {
            case POOL_CLASS:
                return klass.isAssignableFrom(EnumKlass.class);
            case POOL_ENUM:
                return klass.isAssignableFrom(EnumValue.class);
            case POOL_METHOD:
                return klass.isAssignableFrom(Method.class);
            case POOL_STRING:
                return klass.isAssignableFrom(String.class);
            case POOL_NODE_CLASS:
                return klass.isAssignableFrom(NodeClass.class);
            case POOL_FIELD:
                return klass.isAssignableFrom(Field.class);
            case POOL_SIGNATURE:
                return klass.isAssignableFrom(Signature.class);
            case POOL_NULL:
                return true;
            default:
                return false;
        }
    }

    private Object addPoolEntry(Class<?> klass) throws IOException {
        int index = readInt();
        int type = readByte();
        assert assertObjectType(klass, type) : "Wrong object type : " + klass + " != " + type;
        Object obj;
        switch(type) {
            case POOL_CLASS: {
                String name = readString();
                int klasstype = readByte();
                if (klasstype == ENUM_KLASS) {
                    int len = readInt();
                    String[] values = new String[len];
                    for (int i = 0; i < len; i++) {
                        values[i] = readPoolObject(String.class);
                    }
                    obj = new EnumKlass(name, values);
                } else if (klasstype == KLASS) {
                    obj = new Klass(name);
                } else {
                    throw new IOException("unknown klass type : " + klasstype);
                }
                break;
            }
            case POOL_ENUM: {
                EnumKlass enumClass = readPoolObject(EnumKlass.class);
                int ordinal = readInt();
                obj = new EnumValue(enumClass, ordinal);
                break;
            }
            case POOL_NODE_CLASS: {
                String className = readString();
                String nameTemplate = readString();
                int inputCount = readShort();
                List<String> inputs = new ArrayList<>(inputCount);
                for (int i = 0; i < inputCount; i++) {
                    inputs.add(readPoolObject(String.class));
                }
                int suxCount = readShort();
                List<String> sux = new ArrayList<>(suxCount);
                for (int i = 0; i < suxCount; i++) {
                    sux.add(readPoolObject(String.class));
                }
                obj = new NodeClass(className, nameTemplate, inputs, sux);
                break;
            }
            case POOL_METHOD: {
                Klass holder = readPoolObject(Klass.class);
                String name = readPoolObject(String.class);
                Signature sign = readPoolObject(Signature.class);
                int flags = readInt();
                byte[] code = readBytes();
                obj = new Method(name, sign, code, holder, flags);
                break;
            }
            case POOL_FIELD: {
                Klass holder = readPoolObject(Klass.class);
                String name = readPoolObject(String.class);
                String fType = readPoolObject(String.class);
                int flags = readInt();
                obj = new Field(fType, holder, name, flags);
                break;
            }
            case POOL_SIGNATURE: {
                int argc = readShort();
                String[] args = new String[argc];
                for (int i = 0; i < argc; i++) {
                    args[i] = readPoolObject(String.class);
                }
                String returnType = readPoolObject(String.class);
                obj = new Signature(returnType, args);
                break;
            }
            case POOL_STRING: {
                obj = readString();
                break;
            }
            default:
                throw new IOException("unknown pool type");
        }
        while (constantPool.size() <= index) {
            constantPool.add(null);
        }
        constantPool.set(index, obj);
        return obj;
    }
    
    private Object readPropertyObject() throws IOException {
        int type = readByte();
        switch (type) {
            case PROPERTY_INT:
                return readInt();
            case PROPERTY_LONG:
                return readLong();
            case PROPERTY_FLOAT:
                return readFloat();
            case PROPERTY_DOUBLE:
                return readDouble();
            case PROPERTY_TRUE:
                return Boolean.TRUE;
            case PROPERTY_FALSE:
                return Boolean.FALSE;
            case PROPERTY_POOL:
                return readPoolObject(Object.class);
            case PROPERTY_ARRAY:
                int subType = readByte();
                switch(subType) {
                    case PROPERTY_INT:
                        return readIntsToString();
                    case PROPERTY_DOUBLE:
                        return readDoublesToString();
                    case PROPERTY_POOL:
                        return readPoolObjectsToString();
                    default:
                        throw new IOException("Unknown type");
                }
            default:
                throw new IOException("Unknown type");
        }
    }

    public GraphDocument parse() throws IOException {
        GraphDocument doc = new GraphDocument();
        folderStack.push(doc);
        if (monitor != null) {
            monitor.setState("Starting parsing");
        }
        try {
            while(true) {
                parseRoot();
            }
        } catch (EOFException e) {
            
        }
        if (monitor != null) {
            monitor.setState("Finished parsing");
        }
        return doc;
    }

    private void parseRoot() throws IOException {
        int type = readByte();
        switch(type) {
            case BEGIN_GRAPH: {
                final Folder parent = folderStack.peek();
                final InputGraph graph = parseGraph();
                SwingUtilities.invokeLater(new Runnable(){
                    @Override
                    public void run() {
                        parent.addElement(graph);
                    }
                });
                break;
            }
            case BEGIN_GROUP: {
                final Folder parent = folderStack.peek();
                final Group group = parseGroup(parent);
                if (callback == null || parent instanceof Group) {
                    SwingUtilities.invokeLater(new Runnable(){
                        @Override
                        public void run() {
                            parent.addElement(group);
                        }
                    });
                }
                folderStack.push(group);
                if (callback != null && parent instanceof GraphDocument) {
                    callback.started(group);
                }
                break;
            }
            case CLOSE_GROUP: {
                if (folderStack.isEmpty()) {
                    throw new IOException("Unbalanced groups");
                }
                folderStack.pop();
                break;
            }
            default:
                throw new IOException("unknown root : " + type);
        }
    }

    private Group parseGroup(Folder parent) throws IOException {
        String name = readPoolObject(String.class);
        String shortName = readPoolObject(String.class);
        if (monitor != null) {
            monitor.setState(shortName);
        }
        Method method = readPoolObject(Method.class);
        int bci = readInt();
        Group group = new Group(parent);
        group.getProperties().setProperty("name", name);
        if (method != null) {
            InputMethod inMethod = new InputMethod(group, method.name, shortName, bci);
            inMethod.setBytecodes("TODO");
            group.setMethod(inMethod);
        }
        return group;
    }
    
    private InputGraph parseGraph() throws IOException {
        if (monitor != null) {
            monitor.updateProgress();
        }
        String title = readPoolObject(String.class);
        InputGraph graph = new InputGraph(title);
        parseNodes(graph);
        parseBlocks(graph);
        graph.ensureNodesInBlocks();
        return graph;
    }
    
    private void parseBlocks(InputGraph graph) throws IOException {
        int blockCount = readInt();
        List<Edge> edges = new LinkedList<>();
        for (int i = 0; i < blockCount; i++) {
            int id = readInt();
            String name = id >= 0 ? Integer.toString(id) : NO_BLOCK;
            InputBlock block = graph.addBlock(name);
            int nodeCount = readInt();
            for (int j = 0; j < nodeCount; j++) {
                int nodeId = readInt();
                block.addNode(nodeId);
                graph.getNode(nodeId).getProperties().setProperty("block", name);
            }
            int edgeCount = readInt();
            for (int j = 0; j < edgeCount; j++) {
                int to = readInt();
                edges.add(new Edge(id, to));
            }
        }
        for (Edge e : edges) {
            String fromName = e.from >= 0 ? Integer.toString(e.from) : NO_BLOCK;
            String toName = e.to >= 0 ? Integer.toString(e.to) : NO_BLOCK;
            graph.addBlockEdge(graph.getBlock(fromName), graph.getBlock(toName));
        }
    }

    private void parseNodes(InputGraph graph) throws IOException {
        int count = readInt();
        Map<String, Object> props = new HashMap<>();
        List<Edge> edges = new LinkedList<>();
        for (int i = 0; i < count; i++) {
            int id = readInt();
            InputNode node = new InputNode(id);
            final Properties properties = node.getProperties();
            NodeClass nodeClass = readPoolObject(NodeClass.class);
            int preds = readByte();
            if (preds > 0) {
                properties.setProperty("hasPredecessor", "true");
            }
            int propCount = readShort();
            for (int j = 0; j < propCount; j++) {
                String key = readPoolObject(String.class);
                Object value = readPropertyObject();
                properties.setProperty(key, value != null ? value.toString() : "null");
                props.put(key, value);
            }
            int edgesStart = edges.size();
            int suxCount = readShort();
            for (int j = 0; j < suxCount; j++) {
                int sux = readInt();
                int index = readShort();
                edges.add(new Edge(id, sux, (char) j, nodeClass.sux.get(index), false));
            }
            int inputCount = readShort();
            for (int j = 0; j < inputCount; j++) {
                int in = readInt();
                int index = readShort();
                edges.add(new Edge(in, id, (char) (preds + j), nodeClass.inputs.get(index), true));
            }
            properties.setProperty("name", createName(edges.subList(edgesStart, edges.size()), props, nodeClass.nameTemplate));
            properties.setProperty("class", nodeClass.className);
            switch (nodeClass.className) {
                case "BeginNode":
                    properties.setProperty("shortName", "B");
                    break;
                case "EndNode":
                    properties.setProperty("shortName", "E");
                    break;
            }
            graph.addNode(node);
            props.clear();
        }
        for (Edge e : edges) {
            char fromIndex = e.input ? 1 : e.num;
            char toIndex = e.input ? e.num : 0;
            graph.addEdge(new InputEdge(fromIndex, toIndex, e.from, e.to, e.label));
        }
    }
    
    private String createName(List<Edge> edges, Map<String, Object> properties, String template) {
        Pattern p = Pattern.compile("\\{(p|i)#([a-zA-Z0-9$_]+)(/(l|m|s))?\\}");
        Matcher m = p.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group(2);
            String type = m.group(1);
            String result;
            switch (type) {
                case "i":
                    StringBuilder inputString = new StringBuilder();
                    for(Edge edge : edges) {
                        if (name.equals(edge.label)) {
                            if (inputString.length() > 0) {
                                inputString.append(", ");
                            }
                            inputString.append(edge.from);
                        }
                    }
                    result = inputString.toString();
                    break;
                case "p":
                    Object prop = properties.get(name);
                    String length = m.group(4);
                    if (prop == null) {
                        result = "?";
                    } else if (length != null && prop instanceof LengthToString) {
                        LengthToString lengthProp = (LengthToString) prop;
                        switch(length) {
                            default:
                            case "l":
                                result = lengthProp.toString(Length.L);
                                break;
                            case "m":
                                result = lengthProp.toString(Length.M);
                                break;
                            case "s":
                                result = lengthProp.toString(Length.S);
                                break;
                        }
                    } else {
                        result = prop.toString();
                    }
                    break;
                default:
                    result = "#?#";
                    break;
            }
            result = result.replace("\\", "\\\\");
            result = result.replace("$", "\\$");
            m.appendReplacement(sb, result);
        }
        m.appendTail(sb);
        return sb.toString();
    }
    
    private static class Edge {
        final int from;
        final int to;
        final char num;
        final String label;
        final boolean input;
        public Edge(int from, int to) {
            this(from, to, (char) 0, null, false);
        }
        public Edge(int from, int to, char num, String label, boolean input) {
            this.from = from;
            this.to = to;
            this.label = label;
            this.num = num;
            this.input = input;
        }
    }
}
