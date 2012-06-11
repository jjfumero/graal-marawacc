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
package com.oracle.graal.interpreter;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.api.interpreter.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.bytecode.*;

/**
 *
 * <h1>Implementation notes</h1>
 * <li>Native methods are invoked using standard java reflection.</li>
 *
 * <h1>Features:</h1>
 *
 *
 * <h1>Limitations:</h1>
 *
 * <li>The interpreter is not able to recognize a native method that calls a java method.
 *  In that case the java method will be interpreted directly on the vm interpreter.</li>
 */
@SuppressWarnings("static-method")
public final class BytecodeInterpreter implements Interpreter {

    private static final String OPTION_MAX_STACK_SIZE = "maxStackSize";
    private static final boolean TRACE = false;
    private static final boolean TRACE_BYTE_CODE = false;

    private static final int DEFAULT_MAX_STACK_SIZE = 200;

    private static final int NEXT = -1;
    private static final int BRANCH = -2;
    private static final int RETURN = -3;
    private static final int CALL = -4;

    private InterpreterFrame callFrame;

    //TODO try to remove this reference
    private ExceptionHandler catchAllExceptionHandler;

    private final Map<ResolvedJavaMethod, InterpreterCallable> methodDelegates = new HashMap<>();

    private int maxStackFrames = DEFAULT_MAX_STACK_SIZE;
    private int stackFrameDepth;

    private final ResolvedJavaMethod rootMethod;
    private final VMAdapter vm;

    public BytecodeInterpreter() {
        this(VMAdapter.getInstance());
    }

    public BytecodeInterpreter(VMAdapter accessor) {
        if (accessor == null) {
            throw new NullPointerException();
        }
        this.vm = accessor;
        this.rootMethod = resolveRootMethod();
        registerDelegates();
    }

    @Override
    public void setOption(String name, String value) {
        if (name != null && name.equals(OPTION_MAX_STACK_SIZE)) {
            this.maxStackFrames = Integer.parseInt(value);
        }
    }


    private void registerDelegates() {
       addDelegate(findMethod(Throwable.class, "fillInStackTrace"), new InterpreterCallable() {
        @Override
        public Object invoke(InterpreterFrame caller, ResolvedJavaMethod method, Object base, Object[] arguments) throws Throwable {
            setBackTrace(caller, (Throwable) base, createStackTraceElements(caller));
            return null;
        }
       });
       addDelegate(findMethod(Throwable.class, "getStackTraceDepth"), new InterpreterCallable() {
           @Override
           public Object invoke(InterpreterFrame caller, ResolvedJavaMethod method, Object base, Object[] arguments) throws Throwable {
               StackTraceElement[] elements = getBackTrace(caller, (Throwable) base);
               if (elements != null) {
                   return elements.length;
               }
               return 0;
           }
       });
       addDelegate(findMethod(Throwable.class, "getStackTraceElement", int.class), new InterpreterCallable() {
           @Override
           public Object invoke(InterpreterFrame caller, ResolvedJavaMethod method, Object base, Object[] arguments) throws Throwable {
               StackTraceElement[] elements = getBackTrace(caller, (Throwable) base);
               if (elements != null) {
                   Integer index = (Integer) arguments[0];
                   if (index != null) {
                       return elements[index];
                   }
               }
               return null;
           }
       });
    }

    public void setMaxStackFrames(int maxStackSize) {
        this.maxStackFrames = maxStackSize;
    }

    public int getMaxStackFrames() {
        return maxStackFrames;
    }

    public void addDelegate(Method method, InterpreterCallable callable) {
        ResolvedJavaMethod resolvedMethod = vm.getRuntime().getResolvedJavaMethod(method);
        if (methodDelegates.containsKey(resolvedMethod)) {
            throw new IllegalArgumentException("Delegate for method " + method + " already added.");
        }
        methodDelegates.put(resolvedMethod, callable);
    }

    public void removeDelegate(Method method) {
        methodDelegates.remove(vm.getRuntime().getResolvedJavaMethod(method));
    }

    @Override
    public Object execute(ResolvedJavaMethod method, Object... boxedArguments) {
        Signature signature = method.signature();
        assert boxedArguments != null;
        assert signature.argumentCount(false) == boxedArguments.length;

        if (!Modifier.isStatic(method.accessFlags())) {
            throw new UnsupportedOperationException("The interpreter can currently only be started with static methods.");
        }

        InterpreterFrame rootFrame = new InterpreterFrame(rootMethod, signature.argumentSlots(true));
        rootFrame.pushObject(this);
        rootFrame.pushObject(method);
        rootFrame.pushObject(boxedArguments);

        for (int i = 0; i < boxedArguments.length; i++) {
            pushAsObject(rootFrame, signature.argumentKindAt(i), boxedArguments[i]);
        }

        InterpreterFrame frame = rootFrame.create(method, false);

        try {
            executeRoot(rootFrame, frame);
        } catch (Throwable e) {
            // clear backtrace for compatibilty and store it in stacktrace
            StackTraceElement[] backtrace = getBackTrace(rootFrame, e);
            setBackTrace(rootFrame, e, null);
            setStackTrace(rootFrame, e, backtrace);
            throw new InterpreterException(e);
        }

        return popAsObject(rootFrame, signature.returnKind());
    }

    public Object execute(Method javaMethod, Object... boxedArguments) throws InterpreterException {
        return execute(vm.getRuntime().getResolvedJavaMethod(javaMethod), boxedArguments);
    }

    private void executeRoot(InterpreterFrame root, InterpreterFrame frame) throws Throwable {
        //TODO reflection redirection
        stackFrameDepth = 0;

        InterpreterFrame prevFrame = frame;
        InterpreterFrame currentFrame = frame;
        BytecodeStream bs = new BytecodeStream(currentFrame.getMethod().code());
        if (TRACE) {
            traceCall(frame, "Call");
        }
        while (currentFrame != root) {
            if (prevFrame != currentFrame) {
                bs = new BytecodeStream(currentFrame.getMethod().code());
            }
            bs.setBCI(currentFrame.getBCI());

            prevFrame = currentFrame;
            currentFrame = loop(root, prevFrame, bs);
        }
        assert callFrame == null;
    }

    private InterpreterFrame loop(InterpreterFrame root, final InterpreterFrame frame, final BytecodeStream bs) throws Throwable {
        try {
            while (true) {
                int result = executeInstruction(frame, bs);
                switch (result) {
                    case NEXT :
                        bs.next();
                        break;
                    case RETURN :
                        return popFrame(frame);
                    case CALL:
                        return allocateFrame(frame, bs);
                    case BRANCH :
                        bs.setBCI(bs.readBranchDest());
                        break;
                    default :
                        // the outcome depends on stack values
                        assert result >= 0 : "negative branch target";
                        bs.setBCI(result);
                        break;
                }
            }
        } catch (Throwable t) {
            if (TRACE) {
                traceOp("Exception " + t.toString());
            }
            updateStackTrace(frame, t);

            // frame bci needs to be in sync when handling exceptions
            frame.setBCI(bs.currentBCI());

            InterpreterFrame handlerFrame = handleThrowable(root, frame, t);
            if (handlerFrame == null) {
                // matched root we just throw it again.
                throw t;
            } else {
                if (TRACE) {
                    traceOp("Handler found " + handlerFrame.getMethod() + ":" + handlerFrame.getBCI());
                }
                // update bci from frame
                bs.setBCI(handlerFrame.getBCI());

                // continue execution on the found frame
                return handlerFrame;
            }
        } finally {
            // TODO may be not necessary.
            frame.setBCI(bs.currentBCI());
        }
    }

    private int executeInstruction(InterpreterFrame frame, BytecodeStream bs) throws Throwable {
        if (TRACE_BYTE_CODE) {
            traceOp(bs.currentBCI() + ": " + Bytecodes.baseNameOf(bs.currentBC()));
        }
        switch(bs.currentBC()) {
            case Bytecodes.NOP            : break;
            case Bytecodes.ACONST_NULL    :
                frame.pushObject(null);
                break;
            case Bytecodes.ICONST_M1      :
                frame.pushInt(-1);
                break;
            case Bytecodes.ICONST_0       :
                frame.pushInt(0);
                break;
            case Bytecodes.ICONST_1       :
                frame.pushInt(1);
                break;
            case Bytecodes.ICONST_2       :
                frame.pushInt(2);
                break;
            case Bytecodes.ICONST_3       :
                frame.pushInt(3);
                break;
            case Bytecodes.ICONST_4       :
                frame.pushInt(4);
                break;
            case Bytecodes.ICONST_5       :
                frame.pushInt(5);
                break;
            case Bytecodes.LCONST_0       :
                frame.pushLong(0L);
                break;
            case Bytecodes.LCONST_1       :
                frame.pushLong(1L);
                break;
            case Bytecodes.FCONST_0       :
                frame.pushFloat(0.0F);
                break;
            case Bytecodes.FCONST_1       :
                frame.pushFloat(1.0F);
                break;
            case Bytecodes.FCONST_2       :
                frame.pushFloat(2.0F);
                break;
            case Bytecodes.DCONST_0       :
                frame.pushDouble(0.0D);
                break;
            case Bytecodes.DCONST_1       :
                frame.pushDouble(1.0D);
                break;
            case Bytecodes.BIPUSH         :
                frame.pushInt(bs.readByte());
                break;
            case Bytecodes.SIPUSH         :
                frame.pushInt(bs.readShort());
                break;
            case Bytecodes.LDC            :
            case Bytecodes.LDC_W          :
            case Bytecodes.LDC2_W         :
                pushCPConstant(frame, bs.readCPI());
                break;
            case Bytecodes.ILOAD          :
                frame.pushInt(frame.getInt(frame.resolveLocalIndex(bs.readLocalIndex())));
                break;
            case Bytecodes.LLOAD          :
                frame.pushLong(frame.getLong(frame.resolveLocalIndex(bs.readLocalIndex())));
                break;
            case Bytecodes.FLOAD          :
                frame.pushFloat(frame.getFloat(frame.resolveLocalIndex(bs.readLocalIndex())));
                break;
            case Bytecodes.DLOAD          :
                frame.pushDouble(frame.getDouble(frame.resolveLocalIndex(bs.readLocalIndex())));
                break;
            case Bytecodes.ALOAD          :
                frame.pushObject(frame.getObject(frame.resolveLocalIndex(bs.readLocalIndex())));
                break;
            case Bytecodes.ILOAD_0:
                frame.pushInt(frame.getInt(frame.resolveLocalIndex(0)));
                break;
            case Bytecodes.ILOAD_1:
                frame.pushInt(frame.getInt(frame.resolveLocalIndex(1)));
                break;
            case Bytecodes.ILOAD_2:
                frame.pushInt(frame.getInt(frame.resolveLocalIndex(2)));
                break;
            case Bytecodes.ILOAD_3:
                frame.pushInt(frame.getInt(frame.resolveLocalIndex(3)));
                break;
            case Bytecodes.LLOAD_0        :
                frame.pushLong(frame.getLong(frame.resolveLocalIndex(0)));
                break;
            case Bytecodes.LLOAD_1        :
                frame.pushLong(frame.getLong(frame.resolveLocalIndex(1)));
                break;
            case Bytecodes.LLOAD_2        :
                frame.pushLong(frame.getLong(frame.resolveLocalIndex(2)));
                break;
            case Bytecodes.LLOAD_3        :
                frame.pushLong(frame.getLong(frame.resolveLocalIndex(3)));
                break;
            case Bytecodes.FLOAD_0        :
                frame.pushFloat(frame.getFloat(frame.resolveLocalIndex(0)));
                break;
            case Bytecodes.FLOAD_1        :
                frame.pushFloat(frame.getFloat(frame.resolveLocalIndex(1)));
                break;
            case Bytecodes.FLOAD_2        :
                frame.pushFloat(frame.getFloat(frame.resolveLocalIndex(2)));
                break;
            case Bytecodes.FLOAD_3        :
                frame.pushFloat(frame.getFloat(frame.resolveLocalIndex(3)));
                break;
            case Bytecodes.DLOAD_0        :
                frame.pushDouble(frame.getDouble(frame.resolveLocalIndex(0)));
                break;
            case Bytecodes.DLOAD_1        :
                frame.pushDouble(frame.getDouble(frame.resolveLocalIndex(1)));
                break;
            case Bytecodes.DLOAD_2        :
                frame.pushDouble(frame.getDouble(frame.resolveLocalIndex(2)));
                break;
            case Bytecodes.DLOAD_3        :
                frame.pushDouble(frame.getDouble(frame.resolveLocalIndex(3)));
                break;
            case Bytecodes.ALOAD_0        :
                frame.pushObject(frame.getObject(frame.resolveLocalIndex(0)));
                break;
            case Bytecodes.ALOAD_1        :
                frame.pushObject(frame.getObject(frame.resolveLocalIndex(1)));
                break;
            case Bytecodes.ALOAD_2        :
                frame.pushObject(frame.getObject(frame.resolveLocalIndex(2)));
                break;
            case Bytecodes.ALOAD_3        :
                frame.pushObject(frame.getObject(frame.resolveLocalIndex(3)));
                break;
            case Bytecodes.IALOAD         :
                frame.pushInt(vm.getArrayInt(frame.popInt(), frame.popObject()));
                break;
            case Bytecodes.LALOAD         :
                frame.pushLong(vm.getArrayLong(frame.popInt(), frame.popObject()));
                break;
            case Bytecodes.FALOAD         :
                frame.pushFloat(vm.getArrayFloat(frame.popInt(), frame.popObject()));
                break;
            case Bytecodes.DALOAD         :
                frame.pushDouble(vm.getArrayDouble(frame.popInt(), frame.popObject()));
                break;
            case Bytecodes.AALOAD         :
                frame.pushObject(vm.getArrayObject(frame.popInt(), frame.popObject()));
                break;
            case Bytecodes.BALOAD         :
                frame.pushInt(vm.getArrayByte(frame.popInt(), frame.popObject()));
                break;
            case Bytecodes.CALOAD         :
                frame.pushInt(vm.getArrayChar(frame.popInt(), frame.popObject()));
                break;
             case Bytecodes.SALOAD:
                frame.pushInt(vm.getArrayShort(frame.popInt(), frame.popObject()));
                break;
            case Bytecodes.ISTORE:
                frame.setInt(frame.resolveLocalIndex(bs.readLocalIndex()), frame.popInt());
                break;
            case Bytecodes.LSTORE         :
                frame.setLong(frame.resolveLocalIndex(bs.readLocalIndex()), frame.popLong());
                break;
            case Bytecodes.FSTORE         :
                frame.setFloat(frame.resolveLocalIndex(bs.readLocalIndex()), frame.popFloat());
                break;
            case Bytecodes.DSTORE         :
                frame.setDouble(frame.resolveLocalIndex(bs.readLocalIndex()), frame.popDouble());
                break;
            case Bytecodes.ASTORE         :
                frame.setObject(frame.resolveLocalIndex(bs.readLocalIndex()), frame.popObject());
                break;
            case Bytecodes.ISTORE_0       :
                frame.setInt(frame.resolveLocalIndex(0), frame.popInt());
                break;
            case Bytecodes.ISTORE_1       :
                frame.setInt(frame.resolveLocalIndex(1), frame.popInt());
                break;
            case Bytecodes.ISTORE_2       :
                frame.setInt(frame.resolveLocalIndex(2), frame.popInt());
                break;
            case Bytecodes.ISTORE_3       :
                frame.setInt(frame.resolveLocalIndex(3), frame.popInt());
                break;
            case Bytecodes.LSTORE_0       :
                frame.setLong(frame.resolveLocalIndex(0), frame.popLong());
                break;
            case Bytecodes.LSTORE_1       :
                frame.setLong(frame.resolveLocalIndex(1), frame.popLong());
                break;
            case Bytecodes.LSTORE_2       :
                frame.setLong(frame.resolveLocalIndex(2), frame.popLong());
                break;
            case Bytecodes.LSTORE_3       :
                frame.setLong(frame.resolveLocalIndex(3), frame.popLong());
                break;
            case Bytecodes.FSTORE_0       :
                frame.setFloat(frame.resolveLocalIndex(0), frame.popFloat());
                break;
            case Bytecodes.FSTORE_1       :
                frame.setFloat(frame.resolveLocalIndex(1), frame.popFloat());
                break;
            case Bytecodes.FSTORE_2       :
                frame.setFloat(frame.resolveLocalIndex(2), frame.popFloat());
                break;
            case Bytecodes.FSTORE_3       :
                frame.setFloat(frame.resolveLocalIndex(3), frame.popFloat());
                break;
            case Bytecodes.DSTORE_0       :
                frame.setDouble(frame.resolveLocalIndex(0), frame.popDouble());
                break;
            case Bytecodes.DSTORE_1       :
                frame.setDouble(frame.resolveLocalIndex(1), frame.popDouble());
                break;
            case Bytecodes.DSTORE_2       :
                frame.setDouble(frame.resolveLocalIndex(2), frame.popDouble());
                break;
            case Bytecodes.DSTORE_3       :
                frame.setDouble(frame.resolveLocalIndex(3), frame.popDouble());
                break;
            case Bytecodes.ASTORE_0       :
                frame.setObject(frame.resolveLocalIndex(0), frame.popObject());
                break;
            case Bytecodes.ASTORE_1       :
                frame.setObject(frame.resolveLocalIndex(1), frame.popObject());
                break;
            case Bytecodes.ASTORE_2       :
                frame.setObject(frame.resolveLocalIndex(2), frame.popObject());
                break;
            case Bytecodes.ASTORE_3       :
                frame.setObject(frame.resolveLocalIndex(3), frame.popObject());
                break;
            case Bytecodes.IASTORE        :
                vm.setArrayInt(frame.popInt(), frame.popInt(), frame.popObject());
                break;
            case Bytecodes.LASTORE        :
                vm.setArrayLong(frame.popLong(), frame.popInt(), frame.popObject());
                break;
            case Bytecodes.FASTORE        :
                vm.setArrayFloat(frame.popFloat(), frame.popInt(), frame.popObject());
                break;
            case Bytecodes.DASTORE        :
                vm.setArrayDouble(frame.popDouble(), frame.popInt(), frame.popObject());
                break;
            case Bytecodes.AASTORE        :
                vm.setArrayObject(frame.popObject(), frame.popInt(), frame.popObject());
                break;
            case Bytecodes.BASTORE        :
                vm.setArrayByte((byte) frame.popInt(), frame.popInt(), frame.popObject());
                break;
            case Bytecodes.CASTORE        :
                vm.setArrayChar((char) frame.popInt(), frame.popInt(), frame.popObject());
                break;
            case Bytecodes.SASTORE        :
                vm.setArrayShort((short) frame.popInt(), frame.popInt(), frame.popObject());
                break;
            case Bytecodes.POP            :
                frame.popVoid(1);
                break;
            case Bytecodes.POP2           :
                frame.popVoid(2);
                break;
            case Bytecodes.DUP            :
                frame.dup(1);
                break;
            case Bytecodes.DUP_X1         :
                frame.dupx1();
                break;
            case Bytecodes.DUP_X2         :
                frame.dupx2();
                break;
            case Bytecodes.DUP2           :
                frame.dup(2);
                break;
            case Bytecodes.DUP2_X1        :
                frame.dup2x1();
                break;
            case Bytecodes.DUP2_X2        :
                frame.dup2x2();
                break;
            case Bytecodes.SWAP           :
                frame.swapSingle();
                break;
            case Bytecodes.IADD           :
                frame.pushInt(frame.popInt() + frame.popInt());
                break;
            case Bytecodes.LADD           :
                frame.pushLong(frame.popLong() + frame.popLong());
                break;
            case Bytecodes.FADD           :
                frame.pushFloat(frame.popFloat() + frame.popFloat());
                break;
            case Bytecodes.DADD           :
                frame.pushDouble(frame.popDouble() + frame.popDouble());
                break;
            case Bytecodes.ISUB           :
                frame.pushInt(-frame.popInt() + frame.popInt());
                break;
            case Bytecodes.LSUB           :
                frame.pushLong(-frame.popLong() + frame.popLong());
                break;
            case Bytecodes.FSUB           :
                frame.pushFloat(-frame.popFloat() + frame.popFloat());
                break;
            case Bytecodes.DSUB           :
                frame.pushDouble(-frame.popDouble() + frame.popDouble());
                break;
            case Bytecodes.IMUL           :
                frame.pushInt(frame.popInt() * frame.popInt());
                break;
            case Bytecodes.LMUL           :
                frame.pushLong(frame.popLong() * frame.popLong());
                break;
            case Bytecodes.FMUL           :
                frame.pushFloat(frame.popFloat() * frame.popFloat());
                break;
            case Bytecodes.DMUL           :
                frame.pushDouble(frame.popDouble() * frame.popDouble());
                break;
            case Bytecodes.IDIV           :
                divInt(frame);
                break;
            case Bytecodes.LDIV           :
                divLong(frame);
                break;
            case Bytecodes.FDIV           :
                divFloat(frame);
                break;
            case Bytecodes.DDIV           :
                divDouble(frame);
                break;
            case Bytecodes.IREM           :
                remInt(frame);
                break;
            case Bytecodes.LREM           :
                remLong(frame);
                break;
            case Bytecodes.FREM           :
                remFloat(frame);
                break;
            case Bytecodes.DREM           :
                remDouble(frame);
                break;
            case Bytecodes.INEG           :
                frame.pushInt(-frame.popInt());
                break;
            case Bytecodes.LNEG           :
                frame.pushLong(-frame.popLong());
                break;
            case Bytecodes.FNEG           :
                frame.pushFloat(-frame.popFloat());
                break;
            case Bytecodes.DNEG           :
                frame.pushDouble(-frame.popDouble());
                break;
            case Bytecodes.ISHL           :
                shiftLeftInt(frame);
                break;
            case Bytecodes.LSHL           :
                shiftLeftLong(frame);
                break;
            case Bytecodes.ISHR           :
                shiftRightSignedInt(frame);
                break;
            case Bytecodes.LSHR           :
                shiftRightSignedLong(frame);
                break;
            case Bytecodes.IUSHR          :
                shiftRightUnsignedInt(frame);
                break;
            case Bytecodes.LUSHR          :
                shiftRightUnsignedLong(frame);
                break;
            case Bytecodes.IAND           :
                frame.pushInt(frame.popInt() & frame.popInt());
                break;
            case Bytecodes.LAND           :
                frame.pushLong(frame.popLong() & frame.popLong());
                break;
            case Bytecodes.IOR            :
                frame.pushInt(frame.popInt() | frame.popInt());
                break;
            case Bytecodes.LOR            :
                frame.pushLong(frame.popLong() | frame.popLong());
                break;
            case Bytecodes.IXOR           :
                frame.pushInt(frame.popInt() ^ frame.popInt());
                break;
            case Bytecodes.LXOR           :
                frame.pushLong(frame.popLong() ^ frame.popLong());
                break;
            case Bytecodes.IINC           :
                iinc(frame, bs);
                break;
            case Bytecodes.I2L            :
                frame.pushLong(frame.popInt());
                break;
            case Bytecodes.I2F            :
                frame.pushFloat(frame.popInt());
                break;
            case Bytecodes.I2D            :
                frame.pushDouble(frame.popInt());
                break;
            case Bytecodes.L2I            :
                frame.pushInt((int) frame.popLong());
                break;
            case Bytecodes.L2F            :
                frame.pushFloat(frame.popLong());
                break;
            case Bytecodes.L2D            :
                frame.pushDouble(frame.popLong());
                break;
            case Bytecodes.F2I            :
                frame.pushInt((int) frame.popFloat());
                break;
            case Bytecodes.F2L            :
                frame.pushLong((long) frame.popFloat());
                break;
            case Bytecodes.F2D            :
                frame.pushDouble(frame.popFloat());
                break;
            case Bytecodes.D2I            :
                frame.pushInt((int) frame.popDouble());
                break;
            case Bytecodes.D2L            :
                frame.pushLong((long) frame.popDouble());
                break;
            case Bytecodes.D2F            :
                frame.pushFloat((float) frame.popDouble());
                break;
            case Bytecodes.I2B            :
                frame.pushInt((byte) frame.popInt());
                break;
            case Bytecodes.I2C            :
                frame.pushInt((char) frame.popInt());
                break;
            case Bytecodes.I2S            :
                frame.pushInt((short) frame.popInt());
                break;
            case Bytecodes.LCMP           :
                compareLong(frame);
                break;
            case Bytecodes.FCMPL          :
                compareFloatLess(frame);
                break;
            case Bytecodes.FCMPG          :
                compareFloatGreater(frame);
                break;
            case Bytecodes.DCMPL          :
                compareDoubleLess(frame);
                break;
            case Bytecodes.DCMPG          :
                compareDoubleGreater(frame);
                break;
            case Bytecodes.IFEQ           :
                if (frame.popInt() == 0) {
                    return BRANCH;
                }
                break;
            case Bytecodes.IFNE           :
                if (frame.popInt() != 0) {
                    return BRANCH;
                }
                break;
            case Bytecodes.IFLT           :
                if (frame.popInt() < 0) {
                    return BRANCH;
                }
                break;
            case Bytecodes.IFGE           :
                if (frame.popInt() >= 0) {
                    return BRANCH;
                }
                break;
            case Bytecodes.IFGT           :
                if (frame.popInt() > 0) {
                    return BRANCH;
                }
                break;
            case Bytecodes.IFLE           :
                if (frame.popInt() <= 0) {
                    return BRANCH;
                }
                break;
            case Bytecodes.IF_ICMPEQ      :
                if (frame.popInt() == frame.popInt()) {
                    return BRANCH;
                }
                break;
            case Bytecodes.IF_ICMPNE      :
                if (frame.popInt() != frame.popInt()) {
                    return BRANCH;
                }
                break;
            case Bytecodes.IF_ICMPLT      :
                if (frame.popInt() > frame.popInt()) {
                    return BRANCH;
                }
                break;
            case Bytecodes.IF_ICMPGE      :
                if (frame.popInt() <= frame.popInt()) {
                    return BRANCH;
                }
                break;
            case Bytecodes.IF_ICMPGT      :
                if (frame.popInt() < frame.popInt()) {
                    return BRANCH;
                }
                break;
            case Bytecodes.IF_ICMPLE      :
                if (frame.popInt() >= frame.popInt()) {
                    return BRANCH;
                }
                break;
            case Bytecodes.IF_ACMPEQ      :
                if (frame.popObject() == frame.popObject()) {
                    return BRANCH;
                }
                break;
            case Bytecodes.IF_ACMPNE      :
                if (frame.popObject() != frame.popObject()) {
                    return BRANCH;
                }
                break;
            case Bytecodes.GOTO:
            case Bytecodes.GOTO_W:
                return BRANCH;
            case Bytecodes.JSR            :
            case Bytecodes.JSR_W          :
                frame.pushInt(bs.currentBCI());
                return BRANCH;
            case Bytecodes.RET            :
                return frame.getInt(frame.resolveLocalIndex(bs.readLocalIndex()));
            case Bytecodes.TABLESWITCH    :
                return tableSwitch(frame, bs);
            case Bytecodes.LOOKUPSWITCH   :
                return lookupSwitch(frame, bs);
            case Bytecodes.IRETURN        :
                frame.getParentFrame().pushInt(frame.popInt());
                return RETURN;
            case Bytecodes.LRETURN        :
                frame.getParentFrame().pushLong(frame.popLong());
                return RETURN;
            case Bytecodes.FRETURN        :
                frame.getParentFrame().pushFloat(frame.popFloat());
                return RETURN;
            case Bytecodes.DRETURN        :
                frame.getParentFrame().pushDouble(frame.popDouble());
                return RETURN;
            case Bytecodes.ARETURN        :
                frame.getParentFrame().pushObject(frame.popObject());
                return RETURN;
            case Bytecodes.RETURN         :
                return RETURN;
            case Bytecodes.GETSTATIC      :
                getField(frame, null, bs.currentBC(), bs.readCPI());
                break;
            case Bytecodes.PUTSTATIC      :
                putStatic(frame, bs.readCPI());
                break;
            case Bytecodes.GETFIELD:
                getField(frame, nullCheck(frame.popObject()), bs.currentBC(), bs.readCPI());
                break;
            case Bytecodes.PUTFIELD       :
                putField(frame, bs.readCPI());
                break;
            case Bytecodes.INVOKEVIRTUAL  :
                callFrame = invokeVirtual(frame, bs.readCPI());
                if (callFrame == null) {
                    break;
                }
                return CALL;
            case Bytecodes.INVOKESPECIAL  :
                callFrame = invokeSpecial(frame, bs.readCPI());
                if (callFrame == null) {
                    break;
                }
                return CALL;
            case Bytecodes.INVOKESTATIC   :
                callFrame = invokeStatic(frame, bs.readCPI());
                if (callFrame == null) {
                    break;
                }
                return CALL;
            case Bytecodes.INVOKEINTERFACE:
                callFrame = invokeInterface(frame, bs.readCPI());
                if (callFrame == null) {
                    break;
                }
                return CALL;
            case Bytecodes.XXXUNUSEDXXX   :
                assert false : "unused bytecode used. behaviour unspecfied.";
                // nop
                break;
            case Bytecodes.NEW            :
                frame.pushObject(allocateInstance(frame, bs.readCPI()));
                break;
            case Bytecodes.NEWARRAY       :
                frame.pushObject(allocateNativeArray(frame, bs.readByte()));
                break;
            case Bytecodes.ANEWARRAY      :
                frame.pushObject(allocateArray(frame, bs.readCPI()));
                break;
            case Bytecodes.ARRAYLENGTH    :
                frame.pushInt(Array.getLength(nullCheck(frame.popObject())));
                break;
            case Bytecodes.ATHROW         :
                throw (Throwable) frame.popObject();
            case Bytecodes.CHECKCAST      :
                checkCast(frame, bs.readCPI());
                break;
            case Bytecodes.INSTANCEOF     :
                instanceOf(frame, bs.readCPI());
                break;
            case Bytecodes.MONITORENTER   :
                vm.monitorEnter(frame.popObject());
                break;
            case Bytecodes.MONITOREXIT    :
                vm.monitorExit(frame.popObject());
                break;
            case Bytecodes.WIDE           :
                assert false;
                break;
            case Bytecodes.MULTIANEWARRAY :
                frame.pushObject(allocateMultiArray(frame, bs.readCPI(), bs.readUByte(bs.currentBCI() + 3)));
                break;
            case Bytecodes.IFNULL         :
                if (frame.popObject() == null) {
                    return BRANCH;
                }
                break;
            case Bytecodes.IFNONNULL      :
                if (frame.popObject() != null) {
                    return BRANCH;
                }
                break;
            case Bytecodes.BREAKPOINT     :
                assert false : "no breakpoints supported at this time.";
                break; // nop
        }
        return NEXT;
    }

    private InterpreterFrame handleThrowable(InterpreterFrame root, InterpreterFrame frame, Throwable t) {
        ExceptionHandler handler;
        InterpreterFrame currentFrame = frame;
        do {
            handler = resolveExceptionHandlers(currentFrame, currentFrame.getBCI(), t);
            if (handler == null) {
                // no handler found pop frame
                // and continue searching
                currentFrame = popFrame(currentFrame);
            } else {
                // found a handler -> execute it
                if (handler.isCatchAll()) {
                    catchAllExceptionHandler = handler;
                } else {
                    catchAllExceptionHandler = null;
                }
                currentFrame.setBCI(handler.handlerBCI());
                currentFrame.popStack();
                currentFrame.pushObject(t);
                return currentFrame;
            }
        } while (handler == null && currentFrame != root);

        // will throw exception up the interpreter
        return null;
    }

    private void updateStackTrace(InterpreterFrame frame, Throwable t) {
        StackTraceElement[] elements = getBackTrace(frame, t);
        if (elements != null) {
            setStackTrace(frame, t, elements);
            setBackTrace(frame, t, null);
        } else {
            setBackTrace(frame, t, createStackTraceElements(frame));
        }
    }


    private void setStackTrace(InterpreterFrame frame, Throwable t, StackTraceElement[] stackTrace) {
      vm.setField(stackTrace, t,  findThrowableField(frame, "stackTrace"));
    }

    private StackTraceElement[] getBackTrace(InterpreterFrame frame, Throwable t) {
        Object value = vm.getField(t, findThrowableField(frame, "backtrace"));
        if (value instanceof StackTraceElement[]) {
            return (StackTraceElement[]) value;
        }
        return null;
    }

    private void setBackTrace(InterpreterFrame frame, Throwable t, StackTraceElement[] backtrace) {
        vm.setField(backtrace, t, findThrowableField(frame, "backtrace"));
    }

    private ExceptionHandler resolveExceptionHandlers(InterpreterFrame frame, int bci, Throwable t) {
        ExceptionHandler[] handlers = frame.getMethod().exceptionHandlers();
        for (int i = 0; i < handlers.length; i++) {
            ExceptionHandler handler = handlers[i];
            if (bci >= handler.startBCI()
                            && bci <= handler.endBCI()
                            && (catchAllExceptionHandler == null || !handler.isCatchAll())) {
                ResolvedJavaType catchType = null;
                if (!handler.isCatchAll()) {
                    catchType = resolveType(frame, bci, (char) handler.catchTypeCPI());
                }

                if (catchType == null
                                || catchType.toJava().isInstance(t)) {
                    // the first found exception handler is our exception handler
                   return handler;
                }
            }
        }
        return null;
    }

    private InterpreterFrame allocateFrame(InterpreterFrame frame, BytecodeStream bs) {
        try {
            stackFrameDepth++;

            InterpreterFrame nextFrame = this.callFrame;

            assert nextFrame != null;
            assert nextFrame.getParentFrame() == frame;

            // store bci when leaving method
            frame.setBCI(bs.currentBCI());

            if (TRACE) {
                traceCall(nextFrame, "Call");
            }
            if (Modifier.isSynchronized(nextFrame.getMethod().accessFlags())) {
                if (TRACE) {
                    traceOp("Method monitor enter");
                }
                if (Modifier.isStatic(nextFrame.getMethod().accessFlags())) {
                    vm.monitorEnter(nextFrame.getMethod().holder().toJava());
                } else {
                    Object enterObject = nextFrame.getObject(frame.resolveLocalIndex(0));
                    assert enterObject != null;
                    vm.monitorEnter(enterObject);
                }
            }

            return nextFrame;
        } finally {
            // catch all exception handlers are not allowed to call any method anyway.
            catchAllExceptionHandler = null;
            callFrame = null;
            bs.next();
        }
    }

    private InterpreterFrame popFrame(InterpreterFrame frame) {
        catchAllExceptionHandler = null;
        InterpreterFrame parent = frame.getParentFrame();
        if (Modifier.isSynchronized(frame.getMethod().accessFlags())) {
            if (TRACE) {
                traceOp("Method monitor exit");
            }
            if (Modifier.isStatic(frame.getMethod().accessFlags())) {
                vm.monitorExit(frame.getMethod().holder().toJava());
            } else {
                Object exitObject = frame.getObject(frame.resolveLocalIndex(0));
                if (exitObject != null) {
                    vm.monitorExit(exitObject);
                }
            }
        }
        if (TRACE) {
            traceCall(frame, "Ret");
        }
        stackFrameDepth--;

        frame.dispose();
        return parent;
    }

    private void traceOp(String opName) {
        trace(stackFrameDepth + 1, opName);
    }

    private void traceCall(InterpreterFrame frame, String type) {
        trace(stackFrameDepth, type + " " +
                        frame.getMethod() + " - " + frame.getMethod().signature().asString());
    }

    private void trace(int level, String message) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < level; i++) {
            builder.append("  ");
        }
        builder.append(message);
        System.out.println(builder);
    }


    private void divInt(InterpreterFrame frame) {
        int dividend = frame.popInt();
        int divisor = frame.popInt();
        frame.pushInt(divisor / dividend);
    }

    private void divLong(InterpreterFrame frame) {
        long dividend = frame.popLong();
        long divisor = frame.popLong();
        frame.pushLong(divisor / dividend);
    }

    private void divFloat(InterpreterFrame frame) {
        float dividend = frame.popFloat();
        float divisor = frame.popFloat();
        frame.pushFloat(divisor / dividend);
    }

    private void divDouble(InterpreterFrame frame) {
        double dividend = frame.popDouble();
        double divisor = frame.popDouble();
        frame.pushDouble(divisor / dividend);
    }


    private void remInt(InterpreterFrame frame) {
        int dividend = frame.popInt();
        int divisor = frame.popInt();
        frame.pushInt(divisor % dividend);
    }

    private void remLong(InterpreterFrame frame) {
        long dividend = frame.popLong();
        long divisor = frame.popLong();
        frame.pushLong(divisor % dividend);
    }

    private void remFloat(InterpreterFrame frame) {
        float dividend = frame.popFloat();
        float divisor = frame.popFloat();
        frame.pushFloat(divisor % dividend);
    }

    private void remDouble(InterpreterFrame frame) {
        double dividend = frame.popDouble();
        double divisor = frame.popDouble();
        frame.pushDouble(divisor % dividend);
    }

    private void shiftLeftInt(InterpreterFrame frame) {
        int bits = frame.popInt();
        int value = frame.popInt();
        frame.pushInt(value << bits);
    }

    private void shiftLeftLong(InterpreterFrame frame) {
        int bits = frame.popInt();
        long value = frame.popLong();
        frame.pushLong(value << bits);
    }

    private void shiftRightSignedInt(InterpreterFrame frame) {
        int bits = frame.popInt();
        int value = frame.popInt();
        frame.pushInt(value >> bits);
    }

    private void shiftRightSignedLong(InterpreterFrame frame) {
        int bits = frame.popInt();
        long value = frame.popLong();
        frame.pushLong(value >> bits);
    }

    private void shiftRightUnsignedInt(InterpreterFrame frame) {
        int bits = frame.popInt();
        int value = frame.popInt();
        frame.pushInt(value >>> bits);
    }

    private void shiftRightUnsignedLong(InterpreterFrame frame) {
        int bits = frame.popInt();
        long value = frame.popLong();
        frame.pushLong(value >>> bits);
    }

    private int lookupSwitch(InterpreterFrame frame, BytecodeStream bs) {
        return lookupSearch(
                        new BytecodeLookupSwitch(bs, bs.currentBCI()), frame.popInt());
    }

    /**
     * Binary search implementation for the lookup switch.
     */
    private int lookupSearch(BytecodeLookupSwitch switchHelper, int key) {
        int low = 0;
        int high = switchHelper.numberOfCases() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int midVal = switchHelper.keyAt(mid);

            if (midVal < key) {
                low = mid + 1;
            } else if (midVal > key) {
                high = mid - 1;
            } else {
                return switchHelper.bci() + switchHelper.offsetAt(mid); // key found
            }
        }
        return switchHelper.defaultTarget();  // key not found.
    }

    private int tableSwitch(InterpreterFrame frame, BytecodeStream bs) {
        BytecodeTableSwitch switchHelper = new BytecodeTableSwitch(bs, bs.currentBCI());

        int low = switchHelper.lowKey();
        int high = switchHelper.highKey();

        assert low <= high;

        int index = frame.popInt();
        if (index < low || index > high) {
            return switchHelper.defaultTarget();
        } else {
            return switchHelper.targetAt(index - low);
        }
    }

    private void checkCast(InterpreterFrame frame, char cpi) {
        frame.pushObject(resolveType(frame, Bytecodes.CHECKCAST, cpi).toJava().cast(frame.popObject()));
    }

    private ResolvedJavaType resolveType(InterpreterFrame frame, int opcode, char cpi) {
        ConstantPool constantPool = frame.getConstantPool();
        constantPool.loadReferencedType(cpi, opcode);
        return constantPool.lookupType(cpi, opcode).resolve(frame.getMethod().holder());
    }

    private ResolvedJavaType resolveType(InterpreterFrame frame, Class<?> javaClass) {
        return vm.getRuntime().getResolvedJavaType(javaClass).resolve(frame.getMethod().holder());
    }

    private ResolvedJavaMethod resolveMethod(InterpreterFrame frame, int opcode, char cpi) {
        ConstantPool constantPool = frame.getConstantPool();
        constantPool.loadReferencedType(cpi, opcode);
        return (ResolvedJavaMethod) constantPool.lookupMethod(cpi, opcode);
    }

    private ResolvedJavaField resolveField(InterpreterFrame frame, int opcode, char cpi) {
        ConstantPool constantPool = frame.getConstantPool();
        constantPool.loadReferencedType(cpi, opcode);
        return (ResolvedJavaField) constantPool.lookupField(cpi, opcode);
    }

    private void instanceOf(InterpreterFrame frame, char cpi) {
        frame.pushInt(resolveType(frame, Bytecodes.INSTANCEOF, cpi).
                        toJava().isInstance(frame.popObject())
                        ? 1 : 0);
    }

    private void pushCPConstant(InterpreterFrame frame, char cpi) {
        ResolvedJavaMethod method = frame.getMethod();
        Object constant = method.getConstantPool().lookupConstant(cpi);

        if (constant instanceof Constant) {
            Constant c = ((Constant) constant);
            switch (c.kind) {
                case Int :
                    frame.pushInt(c.asInt());
                    break;
                case Float:
                    frame.pushFloat(c.asFloat());
                    break;
                case Object:
                    frame.pushObject(c.asObject());
                    break;
                case Double :
                    frame.pushDouble(c.asDouble());
                    break;
                case Long :
                    frame.pushLong(c.asLong());
                    break;
                default:
                    assert false : "unspecified case";
            }
        } else if (constant instanceof JavaType) {
            frame.pushObject(((JavaType) constant).resolve(method.holder()).toJava());
        } else {
            assert false : "unexpected case";
        }
    }

    private void compareLong(InterpreterFrame frame) {
        long y = frame.popLong();
        long x = frame.popLong();
        frame.pushInt((x < y) ? -1 : ((x == y) ? 0 : 1));
    }

    private void compareDoubleGreater(InterpreterFrame frame) {
        double y = frame.popDouble();
        double x = frame.popDouble();
        frame.pushInt(x < y ? -1 : ((x == y) ? 0 : 1));
    }

    private void compareDoubleLess(InterpreterFrame frame) {
        double y = frame.popDouble();
        double x = frame.popDouble();
        frame.pushInt(x > y ? 1 : ((x == y) ? 0 : -1));
    }

    private void compareFloatGreater(InterpreterFrame frame) {
        float y = frame.popFloat();
        float x = frame.popFloat();
        frame.pushInt(x < y ? -1 : ((x == y) ? 0 : 1));
    }

    private void compareFloatLess(InterpreterFrame frame) {
        float y = frame.popFloat();
        float x = frame.popFloat();
        frame.pushInt(x > y ? 1 : ((x == y) ? 0 : -1));
    }

    private Object nullCheck(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
        return value;
    }
    private InterpreterFrame invokeStatic(InterpreterFrame frame, char cpi) throws Throwable {
        return invoke(frame, resolveMethod(frame, Bytecodes.INVOKESTATIC, cpi), null);
    }

    private InterpreterFrame invokeInterface(InterpreterFrame frame, char cpi) throws Throwable {
        return resolveAndInvoke(frame, resolveMethod(frame, Bytecodes.INVOKEINTERFACE, cpi));
    }

    private InterpreterFrame resolveAndInvoke(InterpreterFrame parent, ResolvedJavaMethod m) throws Throwable {
        Object receiver = nullCheck(parent.peekReceiver(m));

        ResolvedJavaMethod method = resolveType(parent, receiver.getClass()).resolveMethodImpl(m);

        if (method == null) {
            throw new AbstractMethodError();
        }

        return invoke(parent, method, receiver);
    }

    private InterpreterFrame invokeVirtual(InterpreterFrame frame, char cpi) throws Throwable {
        ResolvedJavaMethod m = resolveMethod(frame, Bytecodes.INVOKEVIRTUAL, cpi);
        if (Modifier.isFinal(m.accessFlags())) {
            return invoke(frame, m, nullCheck(frame.peekReceiver(m)));
        } else {
            return resolveAndInvoke(frame, m);
        }
    }

    private InterpreterFrame invokeSpecial(InterpreterFrame frame, char cpi) throws Throwable {
        ResolvedJavaMethod m = resolveMethod(frame, Bytecodes.INVOKESPECIAL, cpi);
        return invoke(frame, m, nullCheck(frame.peekReceiver(m)));
    }

    private void invokeReflective(InterpreterFrame frame, ResolvedJavaMethod method, boolean hasReceiver) throws Throwable {
        Class<?>[] parameterTypes = resolveMethodArguments(method);
        Object[] parameters = popArgumentsAsObject(frame, method);
        Class<?> javaClass = method.holder().toJava();

        Object parentObject = null;
        if (hasReceiver) {
            parentObject = frame.popObject();
            nullCheck(parentObject);
        }
        Object returnValue = null;

        if (redirect(method)) {
            if (TRACE) {
                traceCall(frame, "Delegate " + method);
            }
            try {
                returnValue = methodDelegates.get(method).invoke(frame, method, parentObject, parameters);
            } catch (Throwable e) {
                throw e;
            }
        } else {
            if (TRACE) {
                traceCall(frame, "Reflective " + method);
            }
            try {
                Method javaMethod = javaClass.getDeclaredMethod(method.name(), parameterTypes);
                javaMethod.setAccessible(true);
                returnValue = javaMethod.invoke(parentObject, parameters);
            } catch (InvocationTargetException e) {
                e.printStackTrace(System.out);
                throw e.getTargetException();
            } catch (NoSuchMethodException | SecurityException e) {
                throw new AbstractMethodError();
            }
        }

        pushAsObject(frame, method.signature().returnKind(), returnValue);
    }

    private Object[] popArgumentsAsObject(InterpreterFrame frame, ResolvedJavaMethod method) {
        Signature signature = method.signature();
        int argumentCount = method.signature().argumentCount(false);
        Object[] parameters = new Object[argumentCount];
        for (int i = argumentCount - 1; i >= 0; i--) {
            ResolvedJavaType type = signature.argumentTypeAt(i, method.holder()).resolve(method.holder());
            parameters[i] =  popAsObject(frame, type.kind());
        }
        return parameters;
    }

    private Class<?>[] resolveMethodArguments(ResolvedJavaMethod method) {
        Signature signature = method.signature();
        int argumentCount = signature.argumentCount(false);
        Class<?>[] parameterTypes = new Class[argumentCount];

        for (int i = 0; i < argumentCount; i++) {
            JavaType type = signature.argumentTypeAt(i, method.holder());
            ResolvedJavaType resolvedType = type.resolve(method.holder());
            parameterTypes[i] = resolvedType.toJava();
        }
        return parameterTypes;
    }

    private InterpreterFrame invoke(InterpreterFrame parent, ResolvedJavaMethod method, Object receiver) throws Throwable {
        if (stackFrameDepth >= maxStackFrames) {
            throw new StackOverflowError("Maximum callstack of " + maxStackFrames + " exceeded.");
        }
        if (redirect(method)) {
            invokeReflective(parent, method, receiver != null);

            // returning null results in a jump to the next instruction
            // since the method is already successfully invoked.
            return null;
        } else {
            if (Modifier.isNative(method.accessFlags())) {
                invokeReflective(parent, method, receiver != null);
                return null;
            } else {
                return parent.create(method, receiver != null);
            }
        }
    }

    private boolean redirect(ResolvedJavaMethod method) {
        return methodDelegates.containsKey(method);
    }


    private Object allocateMultiArray(InterpreterFrame frame, char cpi, int dimension) {
        ResolvedJavaType type = resolveType(frame, Bytecodes.MULTIANEWARRAY, cpi);
        int[] dimensions = new int[dimension];
        for (int i = 0; i < dimension; i++) {
            dimensions[i] = frame.popInt();
        }
        return Array.newInstance(type.toJava(), dimensions);
    }

    private Object allocateArray(InterpreterFrame frame, char cpi) {
        ResolvedJavaType type = resolveType(frame, Bytecodes.ANEWARRAY, cpi);
        return Array.newInstance(type.toJava(), frame.popInt());
    }

    private Object allocateNativeArray(InterpreterFrame frame, byte cpi) {
        // the constants for the cpi are loosely defined and no real cpi indices.
        switch (cpi) {
            case 4 :
                return new byte[frame.popInt()];
            case 8 :
                return new byte[frame.popInt()];
            case 5 :
                return new char[frame.popInt()];
            case 7 :
                return new double[frame.popInt()];
            case 6 :
                return new float[frame.popInt()];
            case 10 :
                return new int[frame.popInt()];
            case 11 :
                return new long[frame.popInt()];
            case 9 :
                return new short[frame.popInt()];
            default:
                assert false : "unexpected case";
                return null;
        }
    }

    private Object allocateInstance(InterpreterFrame frame, char cpi) throws InstantiationException {
        return vm.newObject(resolveType(frame, Bytecodes.NEW, cpi));
    }

    private void iinc(InterpreterFrame frame, BytecodeStream bs) {
        int index = frame.resolveLocalIndex(bs.readLocalIndex());
        frame.setInt(index, frame.getInt(index) + bs.readIncrement());
    }

    private void putStatic(InterpreterFrame frame, char cpi) {
        putFieldStatic(frame, resolveField(frame, Bytecodes.PUTSTATIC, cpi));
    }

    private void putField(InterpreterFrame frame, char cpi) {
        putFieldVirtual(frame, resolveField(frame, Bytecodes.PUTFIELD, cpi));
    }

    private void putFieldStatic(InterpreterFrame frame, ResolvedJavaField field) {
        switch (field.kind()) {
            case Boolean :
            case Byte :
            case Char :
            case Short :
            case Int :
                vm.setFieldInt(frame.popInt(), null, field);
                break;
            case Double :
                vm.setFieldDouble(frame.popDouble(), null, field);
                break;
            case Float :
                vm.setFieldFloat(frame.popFloat(), null, field);
                break;
            case Long :
                vm.setFieldLong(frame.popLong(), null, field);
                break;
            case Object :
                vm.setField(frame.popObject(), null, field);
                break;
            default :
                assert false : "unexpected case";
        }
    }

    private void putFieldVirtual(InterpreterFrame frame, ResolvedJavaField field) {
        switch (field.kind()) {
            case Boolean :
            case Byte :
            case Char :
            case Short :
            case Int :
                vm.setFieldInt(frame.popInt(), nullCheck(frame.popObject()), field);
                break;
            case Double :
                vm.setFieldDouble(frame.popDouble(), nullCheck(frame.popObject()), field);
                break;
            case Float :
                vm.setFieldFloat(frame.popFloat(), nullCheck(frame.popObject()), field);
                break;
            case Long :
                vm.setFieldLong(frame.popLong(), nullCheck(frame.popObject()), field);
                break;
            case Object :
                vm.setField(frame.popObject(), nullCheck(frame.popObject()), field);
                break;
            default :
                assert false : "unexpected case";
        }
    }

    private void getField(InterpreterFrame frame, Object base, int opcode, char cpi) {
        ResolvedJavaField field = resolveField(frame, opcode, cpi);
        switch (field.kind()) {
            case Boolean :
            case Byte :
            case Char :
            case Short :
            case Int :
                frame.pushInt(vm.getFieldInt(base, field));
                break;
            case Double :
                frame.pushDouble(vm.getFieldDouble(base, field));
                break;
            case Float :
                frame.pushFloat(vm.getFieldFloat(base, field));
                break;
            case Long :
                frame.pushLong(vm.getFieldLong(base, field));
                break;
            case Object :
                frame.pushObject(vm.getField(base, field));
                break;
            default :
                assert false : "unexpected case";
        }
    }


    private int pushAsObject(InterpreterFrame frame, Kind typeKind, Object value) {
        switch(typeKind) {
            case Int :
                frame.pushInt((int) value);
                break;
            case Long :
                frame.pushLong((long) value);
                return 2;
            case Boolean :
                frame.pushInt(((boolean) value) ? 1 : 0);
                break;
            case Byte :
                frame.pushInt((byte) value);
                break;
            case Char :
                frame.pushInt((char) value);
                break;
            case Double :
                frame.pushDouble((double) value);
                return 2;
            case Float :
                frame.pushFloat((float) value);
                break;
            case Short :
                frame.pushInt((short) value);
                break;
            case Object :
                frame.pushObject(value);
                break;
            case Void :
                return 0;
            default :
                assert false : "case not specified";
        }
        return 1;
    }

    private Object popAsObject(InterpreterFrame frame, Kind typeKind) {
        switch (typeKind) {
            case Boolean :
                return frame.popInt() == 1 ? true : false;
            case Byte :
                return (byte) frame.popInt();
            case Char :
                return (char) frame.popInt();
            case Double :
                return frame.popDouble();
            case Int :
                return frame.popInt();
            case Float :
                return frame.popFloat();
            case Long :
                return frame.popLong();
            case Short :
                return (short) frame.popInt();
            case Object :
                return frame.popObject();
            case Void :
                return null;
            default :
                assert false : "unexpected case";
        }
        return null;
    }

    private ResolvedJavaMethod resolveRootMethod() {
        try {
            return vm.getRuntime().getResolvedJavaMethod(BytecodeInterpreter.class.getDeclaredMethod("execute", Method.class, Object[].class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?> ... parameters) {
        try {
            return clazz.getDeclaredMethod(name, parameters);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static StackTraceElement[] createStackTraceElements(InterpreterFrame frame) {
        InterpreterFrame tmp = frame;
        List<StackTraceElement> elements = new ArrayList<>();
        boolean first = false; // filter only first stack elements
        while (tmp != null) {
            if (first || !filterStackElement(tmp)) {
                first = true;
                elements.add(tmp.getMethod().toStackTraceElement(tmp.getBCI()));
            }
            tmp = tmp.getParentFrame();
        }
        return elements.toArray(new StackTraceElement[elements.size()]);
    }

    private static boolean filterStackElement(InterpreterFrame frame) {
        return Throwable.class.isAssignableFrom(frame.getMethod().holder().toJava());
    }

    private ResolvedJavaField findThrowableField(InterpreterFrame frame, String name) {
        ResolvedJavaType throwableType = resolveType(frame, Throwable.class);
        ResolvedJavaField[] fields = throwableType.declaredFields();
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].name().equals(name)) {
                return fields[i];
            }
        }
        assert false;
        return null;
    }

}
