package com.oracle.graal.java;

import static com.oracle.graal.bytecode.Bytecodes.*;
import static java.lang.reflect.Modifier.*;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.meta.ProfilingInfo.TriState;
import com.oracle.graal.api.meta.ResolvedJavaType.Representation;
import com.oracle.graal.bytecode.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.java.BciBlockMapping.BciBlock;
import com.oracle.graal.java.BciBlockMapping.ExceptionDispatchBlock;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.calc.FloatConvertNode.FloatConvert;
import com.oracle.graal.nodes.cfg.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.java.MethodCallTargetNode.InvokeKind;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.util.*;

public abstract class BytecodeParseHelper<T extends KindInterface> {

    private AbstractFrameStateBuilder<T> frameState;
    private BytecodeStream stream;           // the bytecode stream
    private GraphBuilderConfiguration graphBuilderConfig;
    private ResolvedJavaType method;
    private AbstractBlock<?> currentBlock;

    public BytecodeParseHelper() {
    }

    public BytecodeParseHelper(AbstractFrameStateBuilder<T> frameState) {
        this.frameState = frameState;
    }

    public void setCurrentFrameState(AbstractFrameStateBuilder<T> frameState) {
        this.frameState = frameState;
    }

    public final void setStream(BytecodeStream stream) {
        this.stream = stream;
    }

    private BytecodeStream getStream() {
        return stream;
    }

    public void loadLocal(int index, Kind kind) {
        frameState.push(kind, frameState.loadLocal(index));
    }

    private void storeLocal(Kind kind, int index) {
        T value;
        if (kind == Kind.Object) {
            value = frameState.xpop();
            // astore and astore_<n> may be used to store a returnAddress (jsr)
            assert value.getKind() == Kind.Object || value.getKind() == Kind.Int;
        } else {
            value = frameState.pop(kind);
        }
        frameState.storeLocal(index, value);
    }

    /**
     * @param type the unresolved type of the constant
     */
    protected abstract void handleUnresolvedLoadConstant(JavaType type);

    /**
     * @param type the unresolved type of the type check
     * @param object the object value whose type is being checked against {@code type}
     */
    protected abstract void handleUnresolvedCheckCast(JavaType type, T object);

    /**
     * @param type the unresolved type of the type check
     * @param object the object value whose type is being checked against {@code type}
     */
    protected abstract void handleUnresolvedInstanceOf(JavaType type, T object);

    /**
     * @param type the type being instantiated
     */
    protected abstract void handleUnresolvedNewInstance(JavaType type);

    /**
     * @param type the type of the array being instantiated
     * @param length the length of the array
     */
    protected abstract void handleUnresolvedNewObjectArray(JavaType type, T length);

    /**
     * @param type the type being instantiated
     * @param dims the dimensions for the multi-array
     */
    protected abstract void handleUnresolvedNewMultiArray(JavaType type, T[] dims);

    /**
     * @param field the unresolved field
     * @param receiver the object containing the field or {@code null} if {@code field} is static
     */
    protected abstract void handleUnresolvedLoadField(JavaField field, T receiver);

    /**
     * @param field the unresolved field
     * @param value the value being stored to the field
     * @param receiver the object containing the field or {@code null} if {@code field} is static
     */
    protected abstract void handleUnresolvedStoreField(JavaField field, T value, T receiver);

    /**
     * @param representation
     * @param type
     */
    protected abstract void handleUnresolvedExceptionType(Representation representation, JavaType type);

    // protected abstract void handleUnresolvedInvoke(JavaMethod javaMethod, InvokeKind invokeKind);

    // protected abstract DispatchBeginNode handleException(T exceptionObject, int bci);

    private void genLoadConstant(int cpi, int opcode) {
        Object con = lookupConstant(cpi, opcode);

        if (con instanceof JavaType) {
            // this is a load of class constant which might be unresolved
            JavaType type = (JavaType) con;
            if (type instanceof ResolvedJavaType) {
                frameState.push(Kind.Object, appendConstant(((ResolvedJavaType) type).getEncoding(Representation.JavaClass)));
            } else {
                handleUnresolvedLoadConstant(type);
            }
        } else if (con instanceof Constant) {
            Constant constant = (Constant) con;
            frameState.push(constant.getKind().getStackKind(), appendConstant(constant));
        } else {
            throw new Error("lookupConstant returned an object of incorrect type");
        }
    }

    protected abstract T genLoadIndexed(T index, T array, Kind kind);

    private void genLoadIndexed(Kind kind) {
        emitExplicitExceptions(frameState.peek(1), frameState.peek(0));

        T index = frameState.ipop();
        T array = frameState.apop();
        frameState.push(kind.getStackKind(), append(genLoadIndexed(array, index, kind)));
    }

    protected abstract T genStoreIndexed(T array, T index, Kind kind, T value);

    private void genStoreIndexed(Kind kind) {
        emitExplicitExceptions(frameState.peek(2), frameState.peek(1));

        T value = frameState.pop(kind.getStackKind());
        T index = frameState.ipop();
        T array = frameState.apop();
        append(genStoreIndexed(array, index, kind, value));
    }

    private void stackOp(int opcode) {
        switch (opcode) {
            case POP: {
                frameState.xpop();
                break;
            }
            case POP2: {
                frameState.xpop();
                frameState.xpop();
                break;
            }
            case DUP: {
                T w = frameState.xpop();
                frameState.xpush(w);
                frameState.xpush(w);
                break;
            }
            case DUP_X1: {
                T w1 = frameState.xpop();
                T w2 = frameState.xpop();
                frameState.xpush(w1);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case DUP_X2: {
                T w1 = frameState.xpop();
                T w2 = frameState.xpop();
                T w3 = frameState.xpop();
                frameState.xpush(w1);
                frameState.xpush(w3);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case DUP2: {
                T w1 = frameState.xpop();
                T w2 = frameState.xpop();
                frameState.xpush(w2);
                frameState.xpush(w1);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case DUP2_X1: {
                T w1 = frameState.xpop();
                T w2 = frameState.xpop();
                T w3 = frameState.xpop();
                frameState.xpush(w2);
                frameState.xpush(w1);
                frameState.xpush(w3);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case DUP2_X2: {
                T w1 = frameState.xpop();
                T w2 = frameState.xpop();
                T w3 = frameState.xpop();
                T w4 = frameState.xpop();
                frameState.xpush(w2);
                frameState.xpush(w1);
                frameState.xpush(w4);
                frameState.xpush(w3);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case SWAP: {
                T w1 = frameState.xpop();
                T w2 = frameState.xpop();
                frameState.xpush(w1);
                frameState.xpush(w2);
                break;
            }
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    protected abstract T genIntegerAdd(Kind kind, T x, T y);

    protected abstract T genIntegerSub(Kind kind, T x, T y);

    protected abstract T genIntegerMul(Kind kind, T x, T y);

    protected abstract T genFloatAdd(Kind kind, T x, T y, boolean isStrictFP);

    protected abstract T genFloatSub(Kind kind, T x, T y, boolean isStrictFP);

    protected abstract T genFloatMul(Kind kind, T x, T y, boolean isStrictFP);

    protected abstract T genFloatDiv(Kind kind, T x, T y, boolean isStrictFP);

    protected abstract T genFloatRem(Kind kind, T x, T y, boolean isStrictFP);

    private void genArithmeticOp(Kind result, int opcode) {
        T y = frameState.pop(result);
        T x = frameState.pop(result);
        boolean isStrictFP = isStrict(method.getModifiers());
        T v;
        switch (opcode) {
            case IADD:
            case LADD:
                v = genIntegerAdd(result, x, y);
                break;
            case FADD:
            case DADD:
                v = genFloatAdd(result, x, y, isStrictFP);
                break;
            case ISUB:
            case LSUB:
                v = genIntegerSub(result, x, y);
                break;
            case FSUB:
            case DSUB:
                v = genFloatSub(result, x, y, isStrictFP);
                break;
            case IMUL:
            case LMUL:
                v = genIntegerMul(result, x, y);
                break;
            case FMUL:
            case DMUL:
                v = genFloatMul(result, x, y, isStrictFP);
                break;
            case FDIV:
            case DDIV:
                v = genFloatDiv(result, x, y, isStrictFP);
                break;
            case FREM:
            case DREM:
                v = genFloatRem(result, x, y, isStrictFP);
                break;
            default:
                throw new GraalInternalError("should not reach");
        }
        frameState.push(result, append(v));
    }

    protected abstract T genIntegerDiv(Kind kind, T x, T y);

    protected abstract T genIntegerRem(Kind kind, T x, T y);

    private void genIntegerDivOp(Kind result, int opcode) {
        T y = frameState.pop(result);
        T x = frameState.pop(result);
        T v;
        switch (opcode) {
            case IDIV:
            case LDIV:
                v = genIntegerDiv(result, x, y);
                break;
            case IREM:
            case LREM:
                v = genIntegerRem(result, x, y);
                break;
            default:
                throw new GraalInternalError("should not reach");
        }
        frameState.push(result, append(v));
    }

    protected abstract T genNegateOp(T x);

    private void genNegateOp(Kind kind) {
        frameState.push(kind, append(genNegateOp(frameState.pop(kind))));
    }

    protected abstract T genLeftShift(Kind kind, T x, T y);

    protected abstract T genRightShift(Kind kind, T x, T y);

    protected abstract T genUnsignedRightShift(Kind kind, T x, T y);

    private void genShiftOp(Kind kind, int opcode) {
        T s = frameState.ipop();
        T x = frameState.pop(kind);
        T v;
        switch (opcode) {
            case ISHL:
            case LSHL:
                v = genLeftShift(kind, x, s);
                break;
            case ISHR:
            case LSHR:
                v = genRightShift(kind, x, s);
                break;
            case IUSHR:
            case LUSHR:
                v = genUnsignedRightShift(kind, x, s);
                break;
            default:
                throw new GraalInternalError("should not reach");
        }
        frameState.push(kind, append(v));
    }

    protected abstract T genAnd(Kind kind, T x, T y);

    protected abstract T genOr(Kind kind, T x, T y);

    protected abstract T genXor(Kind kind, T x, T y);

    private void genLogicOp(Kind kind, int opcode) {
        T y = frameState.pop(kind);
        T x = frameState.pop(kind);
        T v;
        switch (opcode) {
            case IAND:
            case LAND:
                v = genAnd(kind, x, y);
                break;
            case IOR:
            case LOR:
                v = genOr(kind, x, y);
                break;
            case IXOR:
            case LXOR:
                v = genXor(kind, x, y);
                break;
            default:
                throw new GraalInternalError("should not reach");
        }
        frameState.push(kind, append(v));
    }

    protected abstract T genNormalizeCompare(T x, T y, boolean isUnorderedLess);

    private void genCompareOp(Kind kind, boolean isUnorderedLess) {
        T y = frameState.pop(kind);
        T x = frameState.pop(kind);
        frameState.ipush(append(genNormalizeCompare(x, y, isUnorderedLess)));
    }

    protected abstract T genFloatConvert(FloatConvert op, T input);

    private void genFloatConvert(FloatConvert op, Kind from, Kind to) {
        T input = frameState.pop(from.getStackKind());
        frameState.push(to.getStackKind(), append(genFloatConvert(op, input)));
    }

    protected abstract T genNarrow(T input, int bitCount);

    protected abstract T genSignExtend(T input, int bitCount);

    protected abstract T genZeroExtend(T input, int bitCount);

    private void genSignExtend(Kind from, Kind to) {
        T input = frameState.pop(from.getStackKind());
        if (from != from.getStackKind()) {
            input = append(genNarrow(input, from.getBitCount()));
        }
        frameState.push(to.getStackKind(), append(genSignExtend(input, to.getBitCount())));
    }

    private void genZeroExtend(Kind from, Kind to) {
        T input = frameState.pop(from.getStackKind());
        if (from != from.getStackKind()) {
            input = append(genNarrow(input, from.getBitCount()));
        }
        frameState.push(to.getStackKind(), append(genZeroExtend(input, to.getBitCount())));
    }

    private void genNarrow(Kind from, Kind to) {
        T input = frameState.pop(from.getStackKind());
        frameState.push(to.getStackKind(), append(genNarrow(input, to.getBitCount())));
    }

    private void genIncrement() {
        int index = getStream().readLocalIndex();
        int delta = getStream().readIncrement();
        T x = frameState.loadLocal(index);
        T y = appendConstant(Constant.forInt(delta));
        frameState.storeLocal(index, append(genIntegerAdd(Kind.Int, x, y)));
    }

    private void genGoto() {
        appendGoto(createTarget(currentBlock.getSuccessor(0), frameState));
        assert currentBlock.numNormalSuccessors() == 1;
    }

    private void ifNode(T x, Condition cond, T y) {
        assert !x.isDeleted() && !y.isDeleted();
        assert currentBlock.numNormalSuccessors() == 2;
        BciBlock trueBlock = currentBlock.getSuccessor(0);
        BciBlock falseBlock = currentBlock.getSuccessor(1);
        if (trueBlock == falseBlock) {
            appendGoto(createTarget(trueBlock, frameState));
            return;
        }

        double probability = profilingInfo.getBranchTakenProbability(bci());
        if (probability < 0) {
            assert probability == -1 : "invalid probability";
            Debug.log("missing probability in %s at bci %d", method, bci());
            probability = 0.5;
        }

        if (!optimisticOpts.removeNeverExecutedCode()) {
            if (probability == 0) {
                probability = 0.0000001;
            } else if (probability == 1) {
                probability = 0.999999;
            }
        }

        // the mirroring and negation operations get the condition into canonical form
        boolean mirror = cond.canonicalMirror();
        boolean negate = cond.canonicalNegate();

        T a = mirror ? y : x;
        T b = mirror ? x : y;

        CompareNode condition;
        assert !a.getKind().isNumericFloat();
        if (cond == Condition.EQ || cond == Condition.NE) {
            if (a.getKind() == Kind.Object) {
                condition = new ObjectEqualsNode(a, b);
            } else {
                condition = new IntegerEqualsNode(a, b);
            }
        } else {
            assert a.getKind() != Kind.Object && !cond.isUnsigned();
            condition = new IntegerLessThanNode(a, b);
        }
        condition = currentGraph.unique(condition);

        AbstractBeginNode trueSuccessor = createBlockTarget(probability, trueBlock, frameState);
        AbstractBeginNode falseSuccessor = createBlockTarget(1 - probability, falseBlock, frameState);

        IfNode ifNode = negate ? new IfNode(condition, falseSuccessor, trueSuccessor, 1 - probability) : new IfNode(condition, trueSuccessor, falseSuccessor, probability);
        append(ifNode);
    }

    private void genIfZero(Condition cond) {
        T y = appendConstant(Constant.INT_0);
        T x = frameState.ipop();
        ifNode(x, cond, y);
    }

    private void genIfNull(Condition cond) {
        T y = appendConstant(Constant.NULL_OBJECT);
        T x = frameState.apop();
        ifNode(x, cond, y);
    }

    private void genIfSame(Kind kind, Condition cond) {
        T y = frameState.pop(kind);
        T x = frameState.pop(kind);
        assert !x.isDeleted() && !y.isDeleted();
        ifNode(x, cond, y);
    }

    private void genThrow() {
        T exception = frameState.apop();
        append(new FixedGuardNode(currentGraph.unique(new IsNullNode(exception)), NullCheckException, InvalidateReprofile, true));
        lastInstr.setNext(handleException(exception, bci()));
    }

    private JavaType lookupType(int cpi, int bytecode) {
        eagerResolvingForSnippets(cpi, bytecode);
        JavaType result = constantPool.lookupType(cpi, bytecode);
        assert !graphBuilderConfig.unresolvedIsError() || result instanceof ResolvedJavaType;
        return result;
    }

    private JavaMethod lookupMethod(int cpi, int opcode) {
        eagerResolvingForSnippets(cpi, opcode);
        JavaMethod result = constantPool.lookupMethod(cpi, opcode);
        /*
         * In general, one cannot assume that the declaring class being initialized is useful, since
         * the actual concrete receiver may be a different class (except for static calls). Also,
         * interfaces are initialized only under special circumstances, so that this assertion would
         * often fail for interface calls.
         */
        assert !graphBuilderConfig.unresolvedIsError() || (result instanceof ResolvedJavaMethod && (opcode != INVOKESTATIC || ((ResolvedJavaMethod) result).getDeclaringClass().isInitialized())) : result;
        return result;
    }

    private JavaField lookupField(int cpi, int opcode) {
        eagerResolvingForSnippets(cpi, opcode);
        JavaField result = constantPool.lookupField(cpi, opcode);
        assert !graphBuilderConfig.unresolvedIsError() || (result instanceof ResolvedJavaField && ((ResolvedJavaField) result).getDeclaringClass().isInitialized()) : result;
        return result;
    }

    private Object lookupConstant(int cpi, int opcode) {
        eagerResolvingForSnippets(cpi, opcode);
        Object result = constantPool.lookupConstant(cpi);
        assert !graphBuilderConfig.eagerResolving() || !(result instanceof JavaType) || (result instanceof ResolvedJavaType) : result;
        return result;
    }

    private void eagerResolvingForSnippets(int cpi, int bytecode) {
        if (graphBuilderConfig.eagerResolving()) {
            constantPool.loadReferencedType(cpi, bytecode);
        }
    }

    private JavaTypeProfile getProfileForTypeCheck(ResolvedJavaType type) {
        if (!optimisticOpts.useTypeCheckHints() || !canHaveSubtype(type)) {
            return null;
        } else {
            return profilingInfo.getTypeProfile(bci());
        }
    }

    private void genCheckCast() {
        int cpi = getStream().readCPI();
        JavaType type = lookupType(cpi, CHECKCAST);
        T object = frameState.apop();
        if (type instanceof ResolvedJavaType) {
            JavaTypeProfile profileForTypeCheck = getProfileForTypeCheck((ResolvedJavaType) type);
            CheckCastNode checkCastNode = append(new CheckCastNode((ResolvedJavaType) type, object, profileForTypeCheck, false));
            frameState.apush(checkCastNode);
        } else {
            handleUnresolvedCheckCast(type, object);
        }
    }

    private void genInstanceOf() {
        int cpi = getStream().readCPI();
        JavaType type = lookupType(cpi, INSTANCEOF);
        T object = frameState.apop();
        if (type instanceof ResolvedJavaType) {
            ResolvedJavaType resolvedType = (ResolvedJavaType) type;
            InstanceOfNode instanceOfNode = new InstanceOfNode((ResolvedJavaType) type, object, getProfileForTypeCheck(resolvedType));
            frameState.ipush(append(new ConditionalNode(currentGraph.unique(instanceOfNode))));
        } else {
            handleUnresolvedInstanceOf(type, object);
        }
    }

    void genNewInstance(int cpi) {
        JavaType type = lookupType(cpi, NEW);
        if (type instanceof ResolvedJavaType && ((ResolvedJavaType) type).isInitialized()) {
            frameState.apush(append(createNewInstance((ResolvedJavaType) type, true)));
        } else {
            handleUnresolvedNewInstance(type);
        }
    }

    protected NewInstanceNode createNewInstance(ResolvedJavaType type, boolean fillContents) {
        return new NewInstanceNode(type, fillContents);
    }

    /**
     * Gets the kind of array elements for the array type code that appears in a
     * {@link Bytecodes#NEWARRAY} bytecode.
     * 
     * @param code the array type code
     * @return the kind from the array type code
     */
    public static Class<?> arrayTypeCodeToClass(int code) {
        // Checkstyle: stop
        switch (code) {
            case 4:
                return boolean.class;
            case 5:
                return char.class;
            case 6:
                return float.class;
            case 7:
                return double.class;
            case 8:
                return byte.class;
            case 9:
                return short.class;
            case 10:
                return int.class;
            case 11:
                return long.class;
            default:
                throw new IllegalArgumentException("unknown array type code: " + code);
        }
        // Checkstyle: resume
    }

    private void genNewPrimitiveArray(int typeCode) {
        Class<?> clazz = arrayTypeCodeToClass(typeCode);
        ResolvedJavaType elementType = metaAccess.lookupJavaType(clazz);
        frameState.apush(append(createNewArray(elementType, frameState.ipop(), true)));
    }

    private void genNewObjectArray(int cpi) {
        JavaType type = lookupType(cpi, ANEWARRAY);
        T length = frameState.ipop();
        if (type instanceof ResolvedJavaType) {
            frameState.apush(append(createNewArray((ResolvedJavaType) type, length, true)));
        } else {
            handleUnresolvedNewObjectArray(type, length);
        }

    }

    protected NewArrayNode createNewArray(ResolvedJavaType elementType, T length, boolean fillContents) {
        return new NewArrayNode(elementType, length, fillContents);
    }

    private void genNewMultiArray(int cpi) {
        JavaType type = lookupType(cpi, MULTIANEWARRAY);
        int rank = getStream().readUByte(bci() + 3);
        T[] dims = new T[rank];
        for (int i = rank - 1; i >= 0; i--) {
            dims[i] = frameState.ipop();
        }
        if (type instanceof ResolvedJavaType) {
            frameState.apush(append(createNewMultiArray((ResolvedJavaType) type, dims)));
        } else {
            handleUnresolvedNewMultiArray(type, dims);
        }
    }

    protected NewMultiArrayNode createNewMultiArray(ResolvedJavaType type, T[] dimensions) {
        return new NewMultiArrayNode(type, dimensions);
    }

    private void genGetField(JavaField field) {
        emitExplicitExceptions(frameState.peek(0), null);

        Kind kind = field.getKind();
        T receiver = frameState.apop();
        if ((field instanceof ResolvedJavaField) && ((ResolvedJavaField) field).getDeclaringClass().isInitialized()) {
            appendOptimizedLoadField(kind, new LoadFieldNode(receiver, (ResolvedJavaField) field));
        } else {
            handleUnresolvedLoadField(field, receiver);
        }
    }

    public static class ExceptionInfo {

        public final FixedWithNextNode exceptionEdge;
        public final T exception;

        public ExceptionInfo(FixedWithNextNode exceptionEdge, T exception) {
            this.exceptionEdge = exceptionEdge;
            this.exception = exception;
        }
    }

    private void emitNullCheck(T receiver) {
        if (ObjectStamp.isObjectNonNull(receiver.stamp())) {
            return;
        }
        BlockPlaceholderNode trueSucc = currentGraph.add(new BlockPlaceholderNode(this));
        BlockPlaceholderNode falseSucc = currentGraph.add(new BlockPlaceholderNode(this));
        append(new IfNode(currentGraph.unique(new IsNullNode(receiver)), trueSucc, falseSucc, 0.01));
        lastInstr = falseSucc;

        if (OmitHotExceptionStacktrace.getValue()) {
            T exception = ConstantNode.forObject(cachedNullPointerException, metaAccess, currentGraph);
            trueSucc.setNext(handleException(exception, bci()));
        } else {
            DeferredForeignCallNode call = currentGraph.add(new DeferredForeignCallNode(CREATE_NULL_POINTER_EXCEPTION));
            call.setStamp(StampFactory.exactNonNull(metaAccess.lookupJavaType(CREATE_NULL_POINTER_EXCEPTION.getResultType())));
            call.setStateAfter(frameState.create(bci()));
            trueSucc.setNext(call);
            call.setNext(handleException(call, bci()));
        }
    }

    private static final ArrayIndexOutOfBoundsException cachedArrayIndexOutOfBoundsException = new ArrayIndexOutOfBoundsException();
    private static final NullPointerException cachedNullPointerException = new NullPointerException();
    static {
        cachedArrayIndexOutOfBoundsException.setStackTrace(new StackTraceElement[0]);
        cachedNullPointerException.setStackTrace(new StackTraceElement[0]);
    }

    private void emitBoundsCheck(T index, T length) {
        BlockPlaceholderNode trueSucc = currentGraph.add(new BlockPlaceholderNode(this));
        BlockPlaceholderNode falseSucc = currentGraph.add(new BlockPlaceholderNode(this));
        append(new IfNode(currentGraph.unique(new IntegerBelowThanNode(index, length)), trueSucc, falseSucc, 0.99));
        lastInstr = trueSucc;

        if (OmitHotExceptionStacktrace.getValue()) {
            T exception = ConstantNode.forObject(cachedArrayIndexOutOfBoundsException, metaAccess, currentGraph);
            falseSucc.setNext(handleException(exception, bci()));
        } else {
            DeferredForeignCallNode call = currentGraph.add(new DeferredForeignCallNode(CREATE_OUT_OF_BOUNDS_EXCEPTION, index));
            call.setStamp(StampFactory.exactNonNull(metaAccess.lookupJavaType(CREATE_OUT_OF_BOUNDS_EXCEPTION.getResultType())));
            call.setStateAfter(frameState.create(bci()));
            falseSucc.setNext(call);
            call.setNext(handleException(call, bci()));
        }
    }

    private static final DebugMetric EXPLICIT_EXCEPTIONS = Debug.metric("ExplicitExceptions");

    protected void emitExplicitExceptions(T receiver, T outOfBoundsIndex) {
        assert receiver != null;
        if (graphBuilderConfig.omitAllExceptionEdges() || (optimisticOpts.useExceptionProbabilityForOperations() && profilingInfo.getExceptionSeen(bci()) == TriState.FALSE)) {
            return;
        }

        emitNullCheck(receiver);
        if (outOfBoundsIndex != null) {
            T length = append(new ArrayLengthNode(receiver));
            emitBoundsCheck(outOfBoundsIndex, length);
        }
        EXPLICIT_EXCEPTIONS.increment();
    }

    private void genPutField(JavaField field) {
        emitExplicitExceptions(frameState.peek(1), null);

        T value = frameState.pop(field.getKind().getStackKind());
        T receiver = frameState.apop();
        if (field instanceof ResolvedJavaField && ((ResolvedJavaField) field).getDeclaringClass().isInitialized()) {
            appendOptimizedStoreField(new StoreFieldNode(receiver, (ResolvedJavaField) field, value));
        } else {
            handleUnresolvedStoreField(field, value, receiver);
        }
    }

    private void genGetStatic(JavaField field) {
        Kind kind = field.getKind();
        if (field instanceof ResolvedJavaField && ((ResolvedJavaType) field.getDeclaringClass()).isInitialized()) {
            appendOptimizedLoadField(kind, new LoadFieldNode(null, (ResolvedJavaField) field));
        } else {
            handleUnresolvedLoadField(field, null);
        }
    }

    private void genPutStatic(JavaField field) {
        T value = frameState.pop(field.getKind().getStackKind());
        if (field instanceof ResolvedJavaField && ((ResolvedJavaType) field.getDeclaringClass()).isInitialized()) {
            appendOptimizedStoreField(new StoreFieldNode(null, (ResolvedJavaField) field, value));
        } else {
            handleUnresolvedStoreField(field, value, null);
        }
    }

    private void appendOptimizedStoreField(StoreFieldNode store) {
        append(store);
    }

    private void appendOptimizedLoadField(Kind kind, LoadFieldNode load) {
        // append the load to the instruction
        T optimized = append(load);
        frameState.push(kind.getStackKind(), optimized);
    }

    private void genInvokeStatic(JavaMethod target) {
        if (target instanceof ResolvedJavaMethod) {
            ResolvedJavaMethod resolvedTarget = (ResolvedJavaMethod) target;
            ResolvedJavaType holder = resolvedTarget.getDeclaringClass();
            if (!holder.isInitialized() && ResolveClassBeforeStaticInvoke.getValue()) {
                handleUnresolvedInvoke(target, InvokeKind.Static);
            } else {
                T[] args = frameState.popArguments(resolvedTarget.getSignature().getParameterSlots(false), resolvedTarget.getSignature().getParameterCount(false));
                appendInvoke(InvokeKind.Static, resolvedTarget, args);
            }
        } else {
            handleUnresolvedInvoke(target, InvokeKind.Static);
        }
    }

    private void genInvokeInterface(JavaMethod target) {
        if (target instanceof ResolvedJavaMethod) {
            T[] args = frameState.popArguments(target.getSignature().getParameterSlots(true), target.getSignature().getParameterCount(true));
            genInvokeIndirect(InvokeKind.Interface, (ResolvedJavaMethod) target, args);
        } else {
            handleUnresolvedInvoke(target, InvokeKind.Interface);
        }
    }

    private void genInvokeDynamic(JavaMethod target) {
        if (target instanceof ResolvedJavaMethod) {
            Object appendix = constantPool.lookupAppendix(stream.readCPI4(), Bytecodes.INVOKEDYNAMIC);
            if (appendix != null) {
                frameState.apush(ConstantNode.forObject(appendix, metaAccess, currentGraph));
            }
            T[] args = frameState.popArguments(target.getSignature().getParameterSlots(false), target.getSignature().getParameterCount(false));
            appendInvoke(InvokeKind.Static, (ResolvedJavaMethod) target, args);
        } else {
            handleUnresolvedInvoke(target, InvokeKind.Static);
        }
    }

    private void genInvokeVirtual(JavaMethod target) {
        if (target instanceof ResolvedJavaMethod) {
            /*
             * Special handling for runtimes that rewrite an invocation of MethodHandle.invoke(...)
             * or MethodHandle.invokeExact(...) to a static adapter. HotSpot does this - see
             * https://wikis.oracle.com/display/HotSpotInternals/Method+handles +and+invokedynamic
             */
            boolean hasReceiver = !isStatic(((ResolvedJavaMethod) target).getModifiers());
            Object appendix = constantPool.lookupAppendix(stream.readCPI(), Bytecodes.INVOKEVIRTUAL);
            if (appendix != null) {
                frameState.apush(ConstantNode.forObject(appendix, metaAccess, currentGraph));
            }
            T[] args = frameState.popArguments(target.getSignature().getParameterSlots(hasReceiver), target.getSignature().getParameterCount(hasReceiver));
            if (hasReceiver) {
                genInvokeIndirect(InvokeKind.Virtual, (ResolvedJavaMethod) target, args);
            } else {
                appendInvoke(InvokeKind.Static, (ResolvedJavaMethod) target, args);
            }
        } else {
            handleUnresolvedInvoke(target, InvokeKind.Virtual);
        }

    }

    private void genInvokeSpecial(JavaMethod target) {
        if (target instanceof ResolvedJavaMethod) {
            assert target != null;
            assert target.getSignature() != null;
            T[] args = frameState.popArguments(target.getSignature().getParameterSlots(true), target.getSignature().getParameterCount(true));
            invokeDirect((ResolvedJavaMethod) target, args);
        } else {
            handleUnresolvedInvoke(target, InvokeKind.Special);
        }
    }

    private void genInvokeIndirect(InvokeKind invokeKind, ResolvedJavaMethod target, T[] args) {
        T receiver = args[0];
        // attempt to devirtualize the call
        ResolvedJavaType klass = target.getDeclaringClass();

        // 0. check for trivial cases
        if (target.canBeStaticallyBound()) {
            // check for trivial cases (e.g. final methods, nonvirtual methods)
            invokeDirect(target, args);
            return;
        }
        // 1. check if the exact type of the receiver can be determined
        ResolvedJavaType exact = klass.asExactType();
        if (exact == null && receiver.stamp() instanceof ObjectStamp) {
            ObjectStamp receiverStamp = (ObjectStamp) receiver.stamp();
            if (receiverStamp.isExactType()) {
                exact = receiverStamp.type();
            }
        }
        if (exact != null) {
            // either the holder class is exact, or the receiver object has an exact type
            ResolvedJavaMethod exactMethod = exact.resolveMethod(target);
            if (exactMethod != null) {
                invokeDirect(exactMethod, args);
                return;
            }
        }
        // devirtualization failed, produce an actual invokevirtual
        appendInvoke(invokeKind, target, args);
    }

    private void invokeDirect(ResolvedJavaMethod target, T[] args) {
        appendInvoke(InvokeKind.Special, target, args);
    }

    private void appendInvoke(InvokeKind invokeKind, ResolvedJavaMethod targetMethod, T[] args) {
        Kind resultType = targetMethod.getSignature().getReturnKind();
        if (DeoptALot.getValue()) {
            append(new DeoptimizeNode(DeoptimizationAction.None, RuntimeConstraint));
            frameState.pushReturn(resultType, ConstantNode.defaultForKind(resultType, currentGraph));
            return;
        }

        JavaType returnType = targetMethod.getSignature().getReturnType(method.getDeclaringClass());
        if (graphBuilderConfig.eagerResolving()) {
            returnType = returnType.resolve(targetMethod.getDeclaringClass());
        }
        if (invokeKind != InvokeKind.Static) {
            emitExplicitExceptions(args[0], null);
            if (invokeKind != InvokeKind.Special && this.optimisticOpts.useTypeCheckHints()) {
                JavaTypeProfile profile = profilingInfo.getTypeProfile(bci());
                args[0] = TypeProfileProxyNode.create(args[0], profile);
            }
        }
        MethodCallTargetNode callTarget = currentGraph.add(createMethodCallTarget(invokeKind, targetMethod, args, returnType));

        // be conservative if information was not recorded (could result in endless recompiles
        // otherwise)
        if (graphBuilderConfig.omitAllExceptionEdges() || (optimisticOpts.useExceptionProbability() && profilingInfo.getExceptionSeen(bci()) == TriState.FALSE)) {
            createInvoke(callTarget, resultType);
        } else {
            assert bci() == currentBlock.endBci;
            frameState.clearNonLiveLocals(currentBlock, liveness, false);

            InvokeWithExceptionNode invoke = createInvokeWithException(callTarget, resultType);

            BciBlock nextBlock = currentBlock.getSuccessor(0);
            invoke.setNext(createTarget(nextBlock, frameState));
        }
    }

    protected MethodCallTargetNode createMethodCallTarget(InvokeKind invokeKind, ResolvedJavaMethod targetMethod, T[] args, JavaType returnType) {
        return new MethodCallTargetNode(invokeKind, targetMethod, args, returnType);
    }

    protected InvokeNode createInvoke(CallTargetNode callTarget, Kind resultType) {
        InvokeNode invoke = append(new InvokeNode(callTarget, bci()));
        frameState.pushReturn(resultType, invoke);
        return invoke;
    }

    protected InvokeWithExceptionNode createInvokeWithException(CallTargetNode callTarget, Kind resultType) {
        DispatchBeginNode exceptionEdge = handleException(null, bci());
        InvokeWithExceptionNode invoke = append(new InvokeWithExceptionNode(callTarget, exceptionEdge, bci()));
        frameState.pushReturn(resultType, invoke);
        BciBlock nextBlock = currentBlock.getSuccessor(0);
        invoke.setStateAfter(frameState.create(nextBlock.startBci));
        return invoke;
    }

    private void genReturn(T x) {
        frameState.setRethrowException(false);
        frameState.clearStack();
        if (graphBuilderConfig.eagerInfopointMode()) {
            append(new InfopointNode(InfopointReason.METHOD_END, frameState.create(bci())));
        }

        synchronizedEpilogue(FrameState.AFTER_BCI, x);
        if (frameState.lockDepth() != 0) {
            throw new BailoutException("unbalanced monitors");
        }

        append(new ReturnNode(x));
    }

    private MonitorEnterNode genMonitorEnter(T x) {
        MonitorIdNode monitorId = currentGraph.add(new MonitorIdNode(frameState.lockDepth()));
        MonitorEnterNode monitorEnter = append(new MonitorEnterNode(x, monitorId));
        frameState.pushLock(x, monitorId);
        return monitorEnter;
    }

    private MonitorExitNode genMonitorExit(T x, T returnValue) {
        MonitorIdNode monitorId = frameState.peekMonitorId();
        T lockedObject = frameState.popLock();
        if (GraphUtil.originalValue(lockedObject) != GraphUtil.originalValue(x)) {
            throw new BailoutException("unbalanced monitors: mismatch at monitorexit, %s != %s", GraphUtil.originalValue(x), GraphUtil.originalValue(lockedObject));
        }
        MonitorExitNode monitorExit = append(new MonitorExitNode(x, monitorId, returnValue));
        return monitorExit;
    }

    private void genJsr(int dest) {
        BciBlock successor = currentBlock.jsrSuccessor;
        assert successor.startBci == dest : successor.startBci + " != " + dest + " @" + bci();
        JsrScope scope = currentBlock.jsrScope;
        if (!successor.jsrScope.pop().equals(scope)) {
            throw new JsrNotSupportedBailout("unstructured control flow (internal limitation)");
        }
        if (successor.jsrScope.nextReturnAddress() != stream().nextBCI()) {
            throw new JsrNotSupportedBailout("unstructured control flow (internal limitation)");
        }
        frameState.push(Kind.Int, ConstantNode.forInt(stream().nextBCI(), currentGraph));
        appendGoto(createTarget(successor, frameState));
    }

    private void genRet(int localIndex) {
        BciBlock successor = currentBlock.retSuccessor;
        T local = frameState.loadLocal(localIndex);
        JsrScope scope = currentBlock.jsrScope;
        int retAddress = scope.nextReturnAddress();
        append(new FixedGuardNode(currentGraph.unique(new IntegerEqualsNode(local, ConstantNode.forInt(retAddress, currentGraph))), JavaSubroutineMismatch, InvalidateReprofile));
        if (!successor.jsrScope.equals(scope.pop())) {
            throw new JsrNotSupportedBailout("unstructured control flow (ret leaves more than one scope)");
        }
        appendGoto(createTarget(successor, frameState));
    }

    private double[] switchProbability(int numberOfCases, int bci) {
        double[] prob = profilingInfo.getSwitchProbabilities(bci);
        if (prob != null) {
            assert prob.length == numberOfCases;
        } else {
            Debug.log("Missing probability (switch) in %s at bci %d", method, bci);
            prob = new double[numberOfCases];
            for (int i = 0; i < numberOfCases; i++) {
                prob[i] = 1.0d / numberOfCases;
            }
        }
        assert allPositive(prob);
        return prob;
    }

    private static boolean allPositive(double[] a) {
        for (double d : a) {
            if (d < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Helper function that sums up the probabilities of all keys that lead to a specific successor.
     * 
     * @return an array of size successorCount with the accumulated probability for each successor.
     */
    private static double[] successorProbabilites(int successorCount, int[] keySuccessors, double[] keyProbabilities) {
        double[] probability = new double[successorCount];
        for (int i = 0; i < keySuccessors.length; i++) {
            probability[keySuccessors[i]] += keyProbabilities[i];
        }
        return probability;
    }

    private void genSwitch(BytecodeSwitch bs) {
        int bci = bci();
        T value = frameState.ipop();

        int nofCases = bs.numberOfCases();
        double[] keyProbabilities = switchProbability(nofCases + 1, bci);

        Map<Integer, SuccessorInfo> bciToBlockSuccessorIndex = new HashMap<>();
        for (int i = 0; i < currentBlock.getSuccessorCount(); i++) {
            assert !bciToBlockSuccessorIndex.containsKey(currentBlock.getSuccessor(i).startBci);
            if (!bciToBlockSuccessorIndex.containsKey(currentBlock.getSuccessor(i).startBci)) {
                bciToBlockSuccessorIndex.put(currentBlock.getSuccessor(i).startBci, new SuccessorInfo(i));
            }
        }

        ArrayList<BciBlock> actualSuccessors = new ArrayList<>();
        int[] keys = new int[nofCases];
        int[] keySuccessors = new int[nofCases + 1];
        int deoptSuccessorIndex = -1;
        int nextSuccessorIndex = 0;
        for (int i = 0; i < nofCases + 1; i++) {
            if (i < nofCases) {
                keys[i] = bs.keyAt(i);
            }

            if (isNeverExecutedCode(keyProbabilities[i])) {
                if (deoptSuccessorIndex < 0) {
                    deoptSuccessorIndex = nextSuccessorIndex++;
                    actualSuccessors.add(null);
                }
                keySuccessors[i] = deoptSuccessorIndex;
            } else {
                int targetBci = i >= nofCases ? bs.defaultTarget() : bs.targetAt(i);
                SuccessorInfo info = bciToBlockSuccessorIndex.get(targetBci);
                if (info.actualIndex < 0) {
                    info.actualIndex = nextSuccessorIndex++;
                    actualSuccessors.add(currentBlock.getSuccessor(info.blockIndex));
                }
                keySuccessors[i] = info.actualIndex;
            }
        }

        double[] successorProbabilities = successorProbabilites(actualSuccessors.size(), keySuccessors, keyProbabilities);
        IntegerSwitchNode switchNode = append(new IntegerSwitchNode(value, actualSuccessors.size(), keys, keyProbabilities, keySuccessors));
        for (int i = 0; i < actualSuccessors.size(); i++) {
            switchNode.setBlockSuccessor(i, createBlockTarget(successorProbabilities[i], actualSuccessors.get(i), frameState));
        }

    }

    private static class SuccessorInfo {

        int blockIndex;
        int actualIndex;

        public SuccessorInfo(int blockSuccessorIndex) {
            this.blockIndex = blockSuccessorIndex;
            actualIndex = -1;
        }
    }

    protected abstract T appendConstant(Constant constant);

    protected abstract T append(T v);

    private static class Target {

        FixedNode fixed;
        HIRFrameStateBuilder state;

        public Target(FixedNode fixed, HIRFrameStateBuilder state) {
            this.fixed = fixed;
            this.state = state;
        }
    }

    private Target checkLoopExit(FixedNode target, BciBlock targetBlock, HIRFrameStateBuilder state) {
        if (currentBlock != null) {
            long exits = currentBlock.loops & ~targetBlock.loops;
            if (exits != 0) {
                LoopExitNode firstLoopExit = null;
                LoopExitNode lastLoopExit = null;

                int pos = 0;
                ArrayList<BciBlock> exitLoops = new ArrayList<>(Long.bitCount(exits));
                do {
                    long lMask = 1L << pos;
                    if ((exits & lMask) != 0) {
                        exitLoops.add(loopHeaders[pos]);
                        exits &= ~lMask;
                    }
                    pos++;
                } while (exits != 0);

                Collections.sort(exitLoops, new Comparator<BciBlock>() {

                    @Override
                    public int compare(BciBlock o1, BciBlock o2) {
                        return Long.bitCount(o2.loops) - Long.bitCount(o1.loops);
                    }
                });

                int bci = targetBlock.startBci;
                if (targetBlock instanceof ExceptionDispatchBlock) {
                    bci = ((ExceptionDispatchBlock) targetBlock).deoptBci;
                }
                HIRFrameStateBuilder newState = state.copy();
                for (BciBlock loop : exitLoops) {
                    LoopBeginNode loopBegin = (LoopBeginNode) loop.firstInstruction;
                    LoopExitNode loopExit = currentGraph.add(new LoopExitNode(loopBegin));
                    if (lastLoopExit != null) {
                        lastLoopExit.setNext(loopExit);
                    }
                    if (firstLoopExit == null) {
                        firstLoopExit = loopExit;
                    }
                    lastLoopExit = loopExit;
                    Debug.log("Target %s (%s) Exits %s, scanning framestates...", targetBlock, target, loop);
                    newState.insertLoopProxies(loopExit, loop.entryState);
                    loopExit.setStateAfter(newState.create(bci));
                }

                lastLoopExit.setNext(target);
                return new Target(firstLoopExit, newState);
            }
        }
        return new Target(target, state);
    }

    private FixedNode createTarget(double probability, BciBlock block, HIRFrameStateBuilder stateAfter) {
        assert probability >= 0 && probability <= 1.01 : probability;
        if (isNeverExecutedCode(probability)) {
            return currentGraph.add(new DeoptimizeNode(InvalidateReprofile, UnreachedCode));
        } else {
            assert block != null;
            return createTarget(block, stateAfter);
        }
    }

    private boolean isNeverExecutedCode(double probability) {
        return probability == 0 && optimisticOpts.removeNeverExecutedCode() && entryBCI == StructuredGraph.INVOCATION_ENTRY_BCI;
    }

    private abstract T createTarget(BciBlock block, HIRFrameStateBuilder state) {
        assert block != null && state != null;
        assert !block.isExceptionEntry || state.stackSize() == 1;

        if (block.firstInstruction == null) {
            /*
             * This is the first time we see this block as a branch target. Create and return a
             * placeholder that later can be replaced with a MergeNode when we see this block again.
             */
            block.firstInstruction = currentGraph.add(new BlockPlaceholderNode(this));
            Target target = checkLoopExit(block.firstInstruction, block, state);
            FixedNode result = target.fixed;
            block.entryState = target.state == state ? state.copy() : target.state;
            block.entryState.clearNonLiveLocals(block, liveness, true);

            Debug.log("createTarget %s: first visit, result: %s", block, block.firstInstruction);
            return result;
        }

        // We already saw this block before, so we have to merge states.
        if (!block.entryState.isCompatibleWith(state)) {
            throw new BailoutException("stacks do not match; bytecodes would not verify");
        }

        if (block.firstInstruction instanceof LoopBeginNode) {
            assert block.isLoopHeader && currentBlock.getId() >= block.getId() : "must be backward branch";
            /*
             * Backward loop edge. We need to create a special LoopEndNode and merge with the loop
             * begin node created before.
             */
            LoopBeginNode loopBegin = (LoopBeginNode) block.firstInstruction;
            Target target = checkLoopExit(currentGraph.add(new LoopEndNode(loopBegin)), block, state);
            FixedNode result = target.fixed;
            block.entryState.merge(loopBegin, target.state);

            Debug.log("createTarget %s: merging backward branch to loop header %s, result: %s", block, loopBegin, result);
            return result;
        }
        assert currentBlock == null || currentBlock.getId() < block.getId() : "must not be backward branch";
        assert block.firstInstruction.next() == null : "bytecodes already parsed for block";

        if (block.firstInstruction instanceof BlockPlaceholderNode) {
            /*
             * This is the second time we see this block. Create the actual MergeNode and the End
             * Node for the already existing edge. For simplicity, we leave the placeholder in the
             * graph and just append the new nodes after the placeholder.
             */
            BlockPlaceholderNode placeholder = (BlockPlaceholderNode) block.firstInstruction;

            // The EndNode for the already existing edge.
            AbstractEndNode end = currentGraph.add(new EndNode());
            // The MergeNode that replaces the placeholder.
            MergeNode mergeNode = currentGraph.add(new MergeNode());
            FixedNode next = placeholder.next();

            placeholder.setNext(end);
            mergeNode.addForwardEnd(end);
            mergeNode.setNext(next);

            block.firstInstruction = mergeNode;
        }

        MergeNode mergeNode = (MergeNode) block.firstInstruction;

        // The EndNode for the newly merged edge.
        AbstractEndNode newEnd = currentGraph.add(new EndNode());
        Target target = checkLoopExit(newEnd, block, state);
        FixedNode result = target.fixed;
        block.entryState.merge(mergeNode, target.state);
        mergeNode.addForwardEnd(newEnd);

        Debug.log("createTarget %s: merging state, result: %s", block, result);
        return result;
    }

    /**
     * Returns a block begin node with the specified state. If the specified probability is 0, the
     * block deoptimizes immediately.
     */
    private AbstractBeginNode createBlockTarget(double probability, BciBlock block, HIRFrameStateBuilder stateAfter) {
        FixedNode target = createTarget(probability, block, stateAfter);
        AbstractBeginNode begin = AbstractBeginNode.begin(target);

        assert !(target instanceof DeoptimizeNode && begin.stateAfter() != null) : "We are not allowed to set the stateAfter of the begin node, because we have to deoptimize "
                        + "to a bci _before_ the actual if, so that the interpreter can update the profiling information.";
        return begin;
    }

    private T synchronizedObject(HIRFrameStateBuilder state, ResolvedJavaMethod target) {
        if (isStatic(target.getModifiers())) {
            return appendConstant(target.getDeclaringClass().getEncoding(Representation.JavaClass));
        } else {
            return state.loadLocal(0);
        }
    }

    private void processBlock(BciBlock block) {
        // Ignore blocks that have no predecessors by the time their bytecodes are parsed
        if (block == null || block.firstInstruction == null) {
            Debug.log("Ignoring block %s", block);
            return;
        }
        Indent indent = Debug.logAndIndent("Parsing block %s  firstInstruction: %s  loopHeader: %b", block, block.firstInstruction, block.isLoopHeader);

        lastInstr = block.firstInstruction;
        frameState = block.entryState;
        parseHelper.setCurrentFrameState(frameState);
        currentBlock = block;

        frameState.cleanupDeletedPhis();
        if (lastInstr instanceof MergeNode) {
            int bci = block.startBci;
            if (block instanceof ExceptionDispatchBlock) {
                bci = ((ExceptionDispatchBlock) block).deoptBci;
            }
            ((MergeNode) lastInstr).setStateAfter(frameState.create(bci));
        }

        if (block == unwindBlock) {
            frameState.setRethrowException(false);
            createUnwind();
        } else if (block instanceof ExceptionDispatchBlock) {
            createExceptionDispatch((ExceptionDispatchBlock) block);
        } else {
            frameState.setRethrowException(false);
            iterateBytecodesForBlock(block);
        }
        indent.outdent();
    }

    private void connectLoopEndToBegin() {
        for (LoopBeginNode begin : currentGraph.getNodes(LoopBeginNode.class)) {
            if (begin.loopEnds().isEmpty()) {
                // @formatter:off
                // Remove loop header without loop ends.
                // This can happen with degenerated loops like this one:
                // for (;;) {
                //     try {
                //         break;
                //     } catch (UnresolvedException iioe) {
                //     }
                // }
                // @formatter:on
                assert begin.forwardEndCount() == 1;
                currentGraph.reduceDegenerateLoopBegin(begin);
            } else {
                GraphUtil.normalizeLoopBegin(begin);
            }
        }
    }

    private void createUnwind() {
        assert frameState.stackSize() == 1 : frameState;
        T exception = frameState.apop();
        append(new FixedGuardNode(currentGraph.unique(new IsNullNode(exception)), NullCheckException, InvalidateReprofile, true));
        synchronizedEpilogue(FrameState.AFTER_EXCEPTION_BCI, null);
        append(new UnwindNode(exception));
    }

    private void synchronizedEpilogue(int bci, T returnValue) {
        if (Modifier.isSynchronized(method.getModifiers())) {
            MonitorExitNode monitorExit = genMonitorExit(methodSynchronizedObject, returnValue);
            if (returnValue != null) {
                frameState.push(returnValue.getKind(), returnValue);
            }
            monitorExit.setStateAfter(frameState.create(bci));
            assert !frameState.rethrowException();
        }
    }

    private void createExceptionDispatch(ExceptionDispatchBlock block) {
        assert frameState.stackSize() == 1 : frameState;
        if (block.handler.isCatchAll()) {
            assert block.getSuccessorCount() == 1;
            appendGoto(createTarget(block.getSuccessor(0), frameState));
            return;
        }

        JavaType catchType = block.handler.getCatchType();
        if (graphBuilderConfig.eagerResolving()) {
            catchType = lookupType(block.handler.catchTypeCPI(), INSTANCEOF);
        }
        boolean initialized = (catchType instanceof ResolvedJavaType);
        if (initialized && graphBuilderConfig.getSkippedExceptionTypes() != null) {
            ResolvedJavaType resolvedCatchType = (ResolvedJavaType) catchType;
            for (ResolvedJavaType skippedType : graphBuilderConfig.getSkippedExceptionTypes()) {
                if (skippedType.isAssignableFrom(resolvedCatchType)) {
                    BciBlock nextBlock = block.getSuccessorCount() == 1 ? unwindBlock(block.deoptBci) : block.getSuccessor(1);
                    T exception = frameState.stackAt(0);
                    FixedNode trueSuccessor = currentGraph.add(new DeoptimizeNode(InvalidateReprofile, UnreachedCode));
                    FixedNode nextDispatch = createTarget(nextBlock, frameState);
                    append(new IfNode(currentGraph.unique(new InstanceOfNode((ResolvedJavaType) catchType, exception, null)), trueSuccessor, nextDispatch, 0));
                    return;
                }
            }
        }

        if (initialized) {
            BciBlock nextBlock = block.getSuccessorCount() == 1 ? unwindBlock(block.deoptBci) : block.getSuccessor(1);
            T exception = frameState.stackAt(0);
            CheckCastNode checkCast = currentGraph.add(new CheckCastNode((ResolvedJavaType) catchType, exception, null, false));
            frameState.apop();
            frameState.push(Kind.Object, checkCast);
            FixedNode catchSuccessor = createTarget(block.getSuccessor(0), frameState);
            frameState.apop();
            frameState.push(Kind.Object, exception);
            FixedNode nextDispatch = createTarget(nextBlock, frameState);
            checkCast.setNext(catchSuccessor);
            append(new IfNode(currentGraph.unique(new InstanceOfNode((ResolvedJavaType) catchType, exception, null)), checkCast, nextDispatch, 0.5));
        } else {
            handleUnresolvedExceptionType(Representation.ObjectHub, catchType);
        }
    }

    private void appendGoto(FixedNode target) {
        if (lastInstr != null) {
            lastInstr.setNext(target);
        }
    }

    private static boolean isBlockEnd(Node n) {
        return n instanceof ControlSplitNode || n instanceof ControlSinkNode;
    }

    private void iterateBytecodesForBlock(BciBlock block) {
        if (block.isLoopHeader) {
            // Create the loop header block, which later will merge the backward branches of the
            // loop.
            AbstractEndNode preLoopEnd = currentGraph.add(new EndNode());
            LoopBeginNode loopBegin = currentGraph.add(new LoopBeginNode());
            lastInstr.setNext(preLoopEnd);
            // Add the single non-loop predecessor of the loop header.
            loopBegin.addForwardEnd(preLoopEnd);
            lastInstr = loopBegin;

            // Create phi functions for all local variables and operand stack slots.
            frameState.insertLoopPhis(loopBegin);
            loopBegin.setStateAfter(frameState.create(block.startBci));

            /*
             * We have seen all forward branches. All subsequent backward branches will merge to the
             * loop header. This ensures that the loop header has exactly one non-loop predecessor.
             */
            block.firstInstruction = loopBegin;
            /*
             * We need to preserve the frame state builder of the loop header so that we can merge
             * values for phi functions, so make a copy of it.
             */
            block.entryState = frameState.copy();

            Debug.log("  created loop header %s", loopBegin);
        }
        assert lastInstr.next() == null : "instructions already appended at block " + block;
        Debug.log("  frameState: %s", frameState);

        int endBCI = stream.endBCI();

        stream.setBCI(block.startBci);
        int bci = block.startBci;
        BytecodesParsed.add(block.endBci - bci);

        while (bci < endBCI) {
            if (graphBuilderConfig.eagerInfopointMode() && lnt != null) {
                currentLineNumber = lnt.getLineNumber(bci);
                if (currentLineNumber != previousLineNumber) {
                    append(new InfopointNode(InfopointReason.LINE_NUMBER, frameState.create(bci)));
                    previousLineNumber = currentLineNumber;
                }
            }

            // read the opcode
            int opcode = stream.currentBC();
            traceState();
            traceInstruction(bci, opcode, bci == block.startBci);
            if (bci == entryBCI) {
                if (block.jsrScope != JsrScope.EMPTY_SCOPE) {
                    throw new BailoutException("OSR into a JSR scope is not supported");
                }
                EntryMarkerNode x = append(new EntryMarkerNode());
                frameState.insertProxies(x);
                x.setStateAfter(frameState.create(bci));
            }
            parseHelper.processBytecode(bci, opcode);

            if (lastInstr == null || isBlockEnd(lastInstr) || lastInstr.next() != null) {
                break;
            }

            stream.next();
            bci = stream.currentBCI();

            if (bci > block.endBci) {
                frameState.clearNonLiveLocals(currentBlock, liveness, false);
            }
            if (lastInstr instanceof StateSplit) {
                if (lastInstr.getClass() == AbstractBeginNode.class) {
                    // BeginNodes do not need a frame state
                } else {
                    StateSplit stateSplit = (StateSplit) lastInstr;
                    if (stateSplit.stateAfter() == null) {
                        stateSplit.setStateAfter(frameState.create(bci));
                    }
                }
            }
            if (bci < endBCI) {
                if (bci > block.endBci) {
                    assert !block.getSuccessor(0).isExceptionEntry;
                    assert block.numNormalSuccessors() == 1;
                    // we fell through to the next block, add a goto and break
                    appendGoto(createTarget(block.getSuccessor(0), frameState));
                    break;
                }
            }
        }
    }

// private final int traceLevel = Options.TraceBytecodeParserLevel.getValue();
//
// private void traceState() {
// if (traceLevel >= TRACELEVEL_STATE && Debug.isLogEnabled()) {
// Debug.log(String.format("|   state [nr locals = %d, stack depth = %d, method = %s]",
// frameState.localsSize(), frameState.stackSize(), method));
// for (int i = 0; i < frameState.localsSize(); ++i) {
// T value = frameState.localAt(i);
// Debug.log(String.format("|   local[%d] = %-8s : %s", i, value == null ? "bogus" :
// value.getKind().getJavaName(), value));
// }
// for (int i = 0; i < frameState.stackSize(); ++i) {
// T value = frameState.stackAt(i);
// Debug.log(String.format("|   stack[%d] = %-8s : %s", i, value == null ? "bogus" :
// value.getKind().getJavaName(), value));
// }
// }
// }

    public void processBytecode(int bci, int opcode) {
        int cpi;

        // Checkstyle: stop
        // @formatter:off
    switch (opcode) {
        case NOP            : /* nothing to do */ break;
        case ACONST_NULL    : frameState.apush(appendConstant(Constant.NULL_OBJECT)); break;
        case ICONST_M1      : frameState.ipush(appendConstant(Constant.INT_MINUS_1)); break;
        case ICONST_0       : frameState.ipush(appendConstant(Constant.INT_0)); break;
        case ICONST_1       : frameState.ipush(appendConstant(Constant.INT_1)); break;
        case ICONST_2       : frameState.ipush(appendConstant(Constant.INT_2)); break;
        case ICONST_3       : frameState.ipush(appendConstant(Constant.INT_3)); break;
        case ICONST_4       : frameState.ipush(appendConstant(Constant.INT_4)); break;
        case ICONST_5       : frameState.ipush(appendConstant(Constant.INT_5)); break;
        case LCONST_0       : frameState.lpush(appendConstant(Constant.LONG_0)); break;
        case LCONST_1       : frameState.lpush(appendConstant(Constant.LONG_1)); break;
        case FCONST_0       : frameState.fpush(appendConstant(Constant.FLOAT_0)); break;
        case FCONST_1       : frameState.fpush(appendConstant(Constant.FLOAT_1)); break;
        case FCONST_2       : frameState.fpush(appendConstant(Constant.FLOAT_2)); break;
        case DCONST_0       : frameState.dpush(appendConstant(Constant.DOUBLE_0)); break;
        case DCONST_1       : frameState.dpush(appendConstant(Constant.DOUBLE_1)); break;
        case BIPUSH         : frameState.ipush(appendConstant(Constant.forInt(stream.readByte()))); break;
        case SIPUSH         : frameState.ipush(appendConstant(Constant.forInt(stream.readShort()))); break;
        case LDC            : // fall through
        case LDC_W          : // fall through
        case LDC2_W         : genLoadConstant(stream.readCPI(), opcode); break;
        case ILOAD          : loadLocal(stream.readLocalIndex(), Kind.Int); break;
        case LLOAD          : loadLocal(stream.readLocalIndex(), Kind.Long); break;
        case FLOAD          : loadLocal(stream.readLocalIndex(), Kind.Float); break;
        case DLOAD          : loadLocal(stream.readLocalIndex(), Kind.Double); break;
        case ALOAD          : loadLocal(stream.readLocalIndex(), Kind.Object); break;
        case ILOAD_0        : // fall through
        case ILOAD_1        : // fall through
        case ILOAD_2        : // fall through
        case ILOAD_3        : loadLocal(opcode - ILOAD_0, Kind.Int); break;
        case LLOAD_0        : // fall through
        case LLOAD_1        : // fall through
        case LLOAD_2        : // fall through
        case LLOAD_3        : loadLocal(opcode - LLOAD_0, Kind.Long); break;
        case FLOAD_0        : // fall through
        case FLOAD_1        : // fall through
        case FLOAD_2        : // fall through
        case FLOAD_3        : loadLocal(opcode - FLOAD_0, Kind.Float); break;
        case DLOAD_0        : // fall through
        case DLOAD_1        : // fall through
        case DLOAD_2        : // fall through
        case DLOAD_3        : loadLocal(opcode - DLOAD_0, Kind.Double); break;
        case ALOAD_0        : // fall through
        case ALOAD_1        : // fall through
        case ALOAD_2        : // fall through
        case ALOAD_3        : loadLocal(opcode - ALOAD_0, Kind.Object); break;
        case IALOAD         : genLoadIndexed(Kind.Int   ); break;
        case LALOAD         : genLoadIndexed(Kind.Long  ); break;
        case FALOAD         : genLoadIndexed(Kind.Float ); break;
        case DALOAD         : genLoadIndexed(Kind.Double); break;
        case AALOAD         : genLoadIndexed(Kind.Object); break;
        case BALOAD         : genLoadIndexed(Kind.Byte  ); break;
        case CALOAD         : genLoadIndexed(Kind.Char  ); break;
        case SALOAD         : genLoadIndexed(Kind.Short ); break;
        case ISTORE         : storeLocal(Kind.Int, stream.readLocalIndex()); break;
        case LSTORE         : storeLocal(Kind.Long, stream.readLocalIndex()); break;
        case FSTORE         : storeLocal(Kind.Float, stream.readLocalIndex()); break;
        case DSTORE         : storeLocal(Kind.Double, stream.readLocalIndex()); break;
        case ASTORE         : storeLocal(Kind.Object, stream.readLocalIndex()); break;
        case ISTORE_0       : // fall through
        case ISTORE_1       : // fall through
        case ISTORE_2       : // fall through
        case ISTORE_3       : storeLocal(Kind.Int, opcode - ISTORE_0); break;
        case LSTORE_0       : // fall through
        case LSTORE_1       : // fall through
        case LSTORE_2       : // fall through
        case LSTORE_3       : storeLocal(Kind.Long, opcode - LSTORE_0); break;
        case FSTORE_0       : // fall through
        case FSTORE_1       : // fall through
        case FSTORE_2       : // fall through
        case FSTORE_3       : storeLocal(Kind.Float, opcode - FSTORE_0); break;
        case DSTORE_0       : // fall through
        case DSTORE_1       : // fall through
        case DSTORE_2       : // fall through
        case DSTORE_3       : storeLocal(Kind.Double, opcode - DSTORE_0); break;
        case ASTORE_0       : // fall through
        case ASTORE_1       : // fall through
        case ASTORE_2       : // fall through
        case ASTORE_3       : storeLocal(Kind.Object, opcode - ASTORE_0); break;
        case IASTORE        : genStoreIndexed(Kind.Int   ); break;
        case LASTORE        : genStoreIndexed(Kind.Long  ); break;
        case FASTORE        : genStoreIndexed(Kind.Float ); break;
        case DASTORE        : genStoreIndexed(Kind.Double); break;
        case AASTORE        : genStoreIndexed(Kind.Object); break;
        case BASTORE        : genStoreIndexed(Kind.Byte  ); break;
        case CASTORE        : genStoreIndexed(Kind.Char  ); break;
        case SASTORE        : genStoreIndexed(Kind.Short ); break;
        case POP            : // fall through
        case POP2           : // fall through
        case DUP            : // fall through
        case DUP_X1         : // fall through
        case DUP_X2         : // fall through
        case DUP2           : // fall through
        case DUP2_X1        : // fall through
        case DUP2_X2        : // fall through
        case SWAP           : stackOp(opcode); break;
        case IADD           : // fall through
        case ISUB           : // fall through
        case IMUL           : genArithmeticOp(Kind.Int, opcode); break;
        case IDIV           : // fall through
        case IREM           : genIntegerDivOp(Kind.Int, opcode); break;
        case LADD           : // fall through
        case LSUB           : // fall through
        case LMUL           : genArithmeticOp(Kind.Long, opcode); break;
        case LDIV           : // fall through
        case LREM           : genIntegerDivOp(Kind.Long, opcode); break;
        case FADD           : // fall through
        case FSUB           : // fall through
        case FMUL           : // fall through
        case FDIV           : // fall through
        case FREM           : genArithmeticOp(Kind.Float, opcode); break;
        case DADD           : // fall through
        case DSUB           : // fall through
        case DMUL           : // fall through
        case DDIV           : // fall through
        case DREM           : genArithmeticOp(Kind.Double, opcode); break;
        case INEG           : genNegateOp(Kind.Int); break;
        case LNEG           : genNegateOp(Kind.Long); break;
        case FNEG           : genNegateOp(Kind.Float); break;
        case DNEG           : genNegateOp(Kind.Double); break;
        case ISHL           : // fall through
        case ISHR           : // fall through
        case IUSHR          : genShiftOp(Kind.Int, opcode); break;
        case IAND           : // fall through
        case IOR            : // fall through
        case IXOR           : genLogicOp(Kind.Int, opcode); break;
        case LSHL           : // fall through
        case LSHR           : // fall through
        case LUSHR          : genShiftOp(Kind.Long, opcode); break;
        case LAND           : // fall through
        case LOR            : // fall through
        case LXOR           : genLogicOp(Kind.Long, opcode); break;
        case IINC           : genIncrement(); break;
        case I2F            : genFloatConvert(FloatConvert.I2F, Kind.Int, Kind.Float); break;
        case I2D            : genFloatConvert(FloatConvert.I2D, Kind.Int, Kind.Double); break;
        case L2F            : genFloatConvert(FloatConvert.L2F, Kind.Long, Kind.Float); break;
        case L2D            : genFloatConvert(FloatConvert.L2D, Kind.Long, Kind.Double); break;
        case F2I            : genFloatConvert(FloatConvert.F2I, Kind.Float, Kind.Int); break;
        case F2L            : genFloatConvert(FloatConvert.F2L, Kind.Float, Kind.Long); break;
        case F2D            : genFloatConvert(FloatConvert.F2D, Kind.Float, Kind.Double); break;
        case D2I            : genFloatConvert(FloatConvert.D2I, Kind.Double, Kind.Int); break;
        case D2L            : genFloatConvert(FloatConvert.D2L, Kind.Double, Kind.Long); break;
        case D2F            : genFloatConvert(FloatConvert.D2F, Kind.Double, Kind.Float); break;
        case L2I            : genNarrow(Kind.Long, Kind.Int); break;
        case I2L            : genSignExtend(Kind.Int, Kind.Long); break;
        case I2B            : genSignExtend(Kind.Byte, Kind.Int); break;
        case I2S            : genSignExtend(Kind.Short, Kind.Int); break;
        case I2C            : genZeroExtend(Kind.Char, Kind.Int); break;
        case LCMP           : genCompareOp(Kind.Long, false); break;
        case FCMPL          : genCompareOp(Kind.Float, true); break;
        case FCMPG          : genCompareOp(Kind.Float, false); break;
        case DCMPL          : genCompareOp(Kind.Double, true); break;
        case DCMPG          : genCompareOp(Kind.Double, false); break;
        case IFEQ           : genIfZero(Condition.EQ); break;
        case IFNE           : genIfZero(Condition.NE); break;
        case IFLT           : genIfZero(Condition.LT); break;
        case IFGE           : genIfZero(Condition.GE); break;
        case IFGT           : genIfZero(Condition.GT); break;
        case IFLE           : genIfZero(Condition.LE); break;
        case IF_ICMPEQ      : genIfSame(Kind.Int, Condition.EQ); break;
        case IF_ICMPNE      : genIfSame(Kind.Int, Condition.NE); break;
        case IF_ICMPLT      : genIfSame(Kind.Int, Condition.LT); break;
        case IF_ICMPGE      : genIfSame(Kind.Int, Condition.GE); break;
        case IF_ICMPGT      : genIfSame(Kind.Int, Condition.GT); break;
        case IF_ICMPLE      : genIfSame(Kind.Int, Condition.LE); break;
        case IF_ACMPEQ      : genIfSame(Kind.Object, Condition.EQ); break;
        case IF_ACMPNE      : genIfSame(Kind.Object, Condition.NE); break;
        case GOTO           : genGoto(); break;
        case JSR            : genJsr(stream.readBranchDest()); break;
        case RET            : genRet(stream.readLocalIndex()); break;
        case TABLESWITCH    : genSwitch(new BytecodeTableSwitch(getStream(), bci())); break;
        case LOOKUPSWITCH   : genSwitch(new BytecodeLookupSwitch(stream(), bci())); break;
        case IRETURN        : genReturn(frameState.ipop()); break;
        case LRETURN        : genReturn(frameState.lpop()); break;
        case FRETURN        : genReturn(frameState.fpop()); break;
        case DRETURN        : genReturn(frameState.dpop()); break;
        case ARETURN        : genReturn(frameState.apop()); break;
        case RETURN         : genReturn(null); break;
        case GETSTATIC      : cpi = stream.readCPI(); genGetStatic(lookupField(cpi, opcode)); break;
        case PUTSTATIC      : cpi = stream.readCPI(); genPutStatic(lookupField(cpi, opcode)); break;
        case GETFIELD       : cpi = stream.readCPI(); genGetField(lookupField(cpi, opcode)); break;
        case PUTFIELD       : cpi = stream.readCPI(); genPutField(lookupField(cpi, opcode)); break;
        case INVOKEVIRTUAL  : cpi = stream.readCPI(); genInvokeVirtual(lookupMethod(cpi, opcode)); break;
        case INVOKESPECIAL  : cpi = stream.readCPI(); genInvokeSpecial(lookupMethod(cpi, opcode)); break;
        case INVOKESTATIC   : cpi = stream.readCPI(); genInvokeStatic(lookupMethod(cpi, opcode)); break;
        case INVOKEINTERFACE: cpi = stream.readCPI(); genInvokeInterface(lookupMethod(cpi, opcode)); break;
        case INVOKEDYNAMIC  : cpi = stream.readCPI4(); genInvokeDynamic(lookupMethod(cpi, opcode)); break;
        case NEW            : genNewInstance(stream.readCPI()); break;
        case NEWARRAY       : genNewPrimitiveArray(stream.readLocalIndex()); break;
        case ANEWARRAY      : genNewObjectArray(stream.readCPI()); break;
        case ARRAYLENGTH    : genArrayLength(); break;
        case ATHROW         : genThrow(); break;
        case CHECKCAST      : genCheckCast(); break;
        case INSTANCEOF     : genInstanceOf(); break;
        case MONITORENTER   : genMonitorEnter(frameState.apop()); break;
        case MONITOREXIT    : genMonitorExit(frameState.apop(), null); break;
        case MULTIANEWARRAY : genNewMultiArray(stream.readCPI()); break;
        case IFNULL         : genIfNull(Condition.EQ); break;
        case IFNONNULL      : genIfNull(Condition.NE); break;
        case GOTO_W         : genGoto(); break;
        case JSR_W          : genJsr(stream.readBranchDest()); break;
        case BREAKPOINT:
            throw new BailoutException("concurrent setting of breakpoint");
        default:
            throw new BailoutException("Unsupported opcode " + opcode + " (" + nameOf(opcode) + ") [bci=" + bci + "]");
    }
    // @formatter:on
        // Checkstyle: resume
    }
}
