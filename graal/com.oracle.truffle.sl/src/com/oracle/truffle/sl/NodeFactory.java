/*
 * Copyright (c) 2012, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.sl;

import java.io.*;
import java.math.*;
import java.util.*;

import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.sl.nodes.*;
import com.oracle.truffle.sl.nodes.ArithmeticNodeFactory.*;

public class NodeFactory {

    private final HashMap<String, FunctionDefinitionNode> functions;
    private final PrintStream printOutput;

    private FrameDescriptor frameDescriptor;
    private TypedNode returnValue;

    public NodeFactory(PrintStream printOutput) {
        this.functions = new HashMap<>();
        this.printOutput = printOutput;
    }

    public FunctionDefinitionNode findFunction(String name) {
        return functions.get(name);
    }

    public void startFunction() {
        frameDescriptor = new FrameDescriptor(SLTypesGen.SLTYPES);
    }

    public void createFunction(StatementNode body, String name) {
        functions.put(name, new FunctionDefinitionNode(body, frameDescriptor, name, returnValue));
    }

    public TypedNode createLocal(String name) {
        return ReadLocalNodeFactory.create(frameDescriptor.findOrAddFrameSlot(name));
    }

    public TypedNode createStringLiteral(String value) {
        return StringLiteralNodeFactory.create(value);
    }

    public StatementNode createAssignment(String name, TypedNode right) {
        return WriteLocalNodeFactory.create(frameDescriptor.findOrAddFrameSlot(name), right);
    }

    public StatementNode createPrint(List<TypedNode> expressions) {
        if (expressions.size() >= 1) {
            StatementNode[] nodes = new StatementNode[expressions.size() + 1];
            for (int i = 0; i < expressions.size(); i++) {
                nodes[i] = PrintNodeFactory.create(expressions.get(i), printOutput);
            }
            nodes[expressions.size()] = new PrintLineNode(printOutput);
            return new BlockNode(nodes);
        } else  {
            return new BlockNode(new StatementNode[]{new PrintLineNode(printOutput)});
        }
    }

    public StatementNode createWhile(ConditionNode condition, StatementNode body) {
        return new WhileNode(condition, body);
    }

    public StatementNode createBlock(List<StatementNode> statements) {
        return new BlockNode(statements.toArray(new StatementNode[statements.size()]));
    }

    public TypedNode createBinary(String operation, TypedNode left, TypedNode right) {
        switch (operation) {
            case "+":
                return AddNodeFactory.create(left, right);
            case "*":
                return MulNodeFactory.create(left, right);
            case "/":
                return DivNodeFactory.create(left, right);
            case "-":
                return SubNodeFactory.create(left, right);
            case "<":
                return LessThanNodeFactory.create(left, right);
            case "&&":
                return LogicalAndNodeFactory.create(left, right);
            default:
                throw new RuntimeException("unexpected operation: " + operation);
        }
    }

    public TypedNode createNumericLiteral(String value) {
        try {
            return IntegerLiteralNodeFactory.create(Integer.parseInt(value));
        } catch (NumberFormatException ex) {
            return BigIntegerLiteralNodeFactory.create(new BigInteger(value));
        }
    }

    public TypedNode createTime() {
        return TimeNodeFactory.create();
    }

    public StatementNode createReturn(TypedNode value) {
        FrameSlot slot = frameDescriptor.findOrAddFrameSlot("<retval>");
        if (returnValue == null) {
            returnValue = ReadLocalNodeFactory.create(slot);
        }
        StatementNode write = WriteLocalNodeFactory.create(slot, value);
        return new ReturnNode(write);
    }
}
