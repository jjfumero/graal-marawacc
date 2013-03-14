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
package com.oracle.truffle.codegen.processor.node;

import java.lang.annotation.*;
import java.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.tools.Diagnostic.*;

import com.oracle.truffle.api.codegen.*;
import com.oracle.truffle.api.nodes.Node.Child;
import com.oracle.truffle.api.nodes.Node.Children;
import com.oracle.truffle.codegen.processor.*;
import com.oracle.truffle.codegen.processor.ast.*;
import com.oracle.truffle.codegen.processor.node.NodeFieldData.ExecutionKind;
import com.oracle.truffle.codegen.processor.node.NodeFieldData.FieldKind;
import com.oracle.truffle.codegen.processor.template.*;
import com.oracle.truffle.codegen.processor.typesystem.*;

public class NodeParser extends TemplateParser<NodeData> {

    public static final List<Class<? extends Annotation>> ANNOTATIONS = Arrays.asList(Generic.class, TypeSystemReference.class, ShortCircuit.class, Specialization.class, SpecializationListener.class);

    private Map<String, NodeData> parsedNodes;

    public NodeParser(ProcessorContext c) {
        super(c);
    }

    @Override
    protected NodeData parse(Element element, AnnotationMirror mirror) {
        assert element instanceof TypeElement;
        NodeData node = null;
        try {
            parsedNodes = new HashMap<>();
            node = resolveNode((TypeElement) element);
            if (Log.DEBUG) {
                NodeData parsed = parsedNodes.get(Utils.getQualifiedName((TypeElement) element));
                if (node != null) {
                    String dump = parsed.dump();
                    log.message(Kind.ERROR, null, null, null, dump);
                    System.out.println(dump);
                }
            }
        } finally {
            parsedNodes = null;
        }

        return node;
    }

    @Override
    protected NodeData filterErrorElements(NodeData model) {
        for (Iterator<NodeData> iterator = model.getDeclaredChildren().iterator(); iterator.hasNext();) {
            NodeData node = filterErrorElements(iterator.next());
            if (node == null) {
                iterator.remove();
            }
        }
        if (model.hasErrors()) {
            return null;
        }
        return model;
    }

    @Override
    public boolean isDelegateToRootDeclaredType() {
        return true;
    }

    private NodeData resolveNode(TypeElement rootType) {
        String typeName = Utils.getQualifiedName(rootType);
        if (parsedNodes.containsKey(typeName)) {
            return parsedNodes.get(typeName);
        }

        List<? extends TypeElement> types = ElementFilter.typesIn(rootType.getEnclosedElements());

        List<NodeData> children = new ArrayList<>();
        for (TypeElement childElement : types) {
            NodeData childNode = resolveNode(childElement);
            if (childNode != null) {
                children.add(childNode);
            }
        }

        NodeData rootNode = parseNode(rootType);
        boolean hasErrors = rootNode != null ? rootNode.hasErrors() : false;
        if ((rootNode == null || hasErrors) && children.size() > 0) {
            rootNode = new NodeData(rootType, rootType.getSimpleName().toString());
        }

        parsedNodes.put(typeName, rootNode);

        if (rootNode != null) {
            children.addAll(rootNode.getDeclaredChildren());
            rootNode.setDeclaredChildren(children);
        }

        return rootNode;
    }

    private NodeData parseNode(TypeElement type) {
        if (Utils.findAnnotationMirror(processingEnv, type, GeneratedBy.class) != null) {
            // generated nodes should not get called again.
            return null;
        }

        AnnotationMirror methodNodes = Utils.findAnnotationMirror(processingEnv, type, NodeClass.class);

        if (methodNodes == null && !Utils.isAssignable(type.asType(), context.getTruffleTypes().getNode())) {
            return null; // not a node
        }

        if (type.getModifiers().contains(Modifier.PRIVATE)) {
            // TODO error message here!?
            return null; // not visible, not a node
        }

        TypeElement nodeType;
        boolean needsSplit;
        if (methodNodes != null) {
            needsSplit = methodNodes != null;
            nodeType = Utils.fromTypeMirror(Utils.getAnnotationValue(TypeMirror.class, methodNodes, "value"));
        } else {
            needsSplit = false;
            nodeType = type;
        }

        NodeData nodeData = parseNodeData(type, nodeType);
        if (nodeData.hasErrors()) {
            return nodeData; // error sync point
        }

        List<Element> elements = new ArrayList<>(context.getEnvironment().getElementUtils().getAllMembers(type));
        nodeData.setExtensionElements(getExtensionParser().parseAll(nodeData, elements));
        if (nodeData.getExtensionElements() != null) {
            elements.addAll(nodeData.getExtensionElements());
        }
        parseMethods(nodeData, elements);

        if (nodeData.hasErrors()) {
            return nodeData;
        }

        List<NodeData> nodes;
        if (needsSplit) {
            nodes = splitNodeData(nodeData);
        } else {
            nodes = new ArrayList<>();
            nodes.add(nodeData);
        }

        for (NodeData splittedNode : nodes) {
            finalizeSpecializations(splittedNode);
            verifyNode(splittedNode);
        }

        if (needsSplit) {
            nodeData.setDeclaredChildren(nodes);
            nodeData.setSpecializationListeners(new ArrayList<SpecializationListenerData>());
            nodeData.setSpecializations(new ArrayList<SpecializationData>());
            return nodeData;
        } else {
            return nodeData;
        }
    }

    private static List<NodeData> splitNodeData(NodeData node) {
        SortedMap<String, List<SpecializationData>> groupedSpecializations = groupByNodeId(node.getSpecializations());
        SortedMap<String, List<SpecializationListenerData>> groupedListeners = groupByNodeId(node.getSpecializationListeners());

        Set<String> ids = new TreeSet<>();
        ids.addAll(groupedSpecializations.keySet());
        ids.addAll(groupedListeners.keySet());

        List<NodeData> splitted = new ArrayList<>();
        for (String id : ids) {
            List<SpecializationData> specializations = groupedSpecializations.get(id);
            List<SpecializationListenerData> listeners = groupedListeners.get(id);

            if (specializations == null) {
                specializations = new ArrayList<>();
            }

            if (listeners == null) {
                listeners = new ArrayList<>();
            }

            String nodeId = node.getNodeId();
            if (nodeId.endsWith("Node") && !nodeId.equals("Node")) {
                nodeId = nodeId.substring(0, nodeId.length() - 4);
            }
            String newNodeId = nodeId + Utils.firstLetterUpperCase(id);
            NodeData copy = new NodeData(node, id, newNodeId);

            copy.setSpecializations(specializations);
            copy.setSpecializationListeners(listeners);

            splitted.add(copy);
        }

        node.setSpecializations(new ArrayList<SpecializationData>());
        node.setSpecializationListeners(new ArrayList<SpecializationListenerData>());

        return splitted;
    }

    private static <M extends TemplateMethod> SortedMap<String, List<M>> groupByNodeId(List<M> methods) {
        SortedMap<String, List<M>> grouped = new TreeMap<>();
        for (M m : methods) {
            List<M> list = grouped.get(m.getId());
            if (list == null) {
                list = new ArrayList<>();
                grouped.put(m.getId(), list);
            }
            list.add(m);
        }
        return grouped;
    }

    private void parseMethods(final NodeData node, List<Element> elements) {
        node.setGuards(new GuardParser(context, node, node.getTypeSystem()).parse(elements));
        node.setShortCircuits(new ShortCircuitParser(context, node).parse(elements));
        node.setSpecializationListeners(new SpecializationListenerParser(context, node).parse(elements));
        List<SpecializationData> generics = new GenericParser(context, node).parse(elements);
        List<SpecializationData> specializations = new SpecializationMethodParser(context, node).parse(elements);

        List<SpecializationData> allSpecializations = new ArrayList<>();
        allSpecializations.addAll(generics);
        allSpecializations.addAll(specializations);

        node.setSpecializations(allSpecializations);
    }

    private void finalizeSpecializations(final NodeData node) {
        List<SpecializationData> specializations = new ArrayList<>(node.getSpecializations());

        if (specializations.isEmpty()) {
            return;
        }

        List<SpecializationData> generics = new ArrayList<>();
        for (SpecializationData spec : specializations) {
            if (spec.isGeneric()) {
                generics.add(spec);
            }
        }

        if (generics.size() == 1 && specializations.size() == 1) {
            for (SpecializationData generic : generics) {
                generic.addError("@%s defined but no @%s.", Generic.class.getSimpleName(), Specialization.class.getSimpleName());
            }
        }

        SpecializationData genericSpecialization = null;
        if (generics.size() > 1) {
            for (SpecializationData generic : generics) {
                generic.addError("Only @%s is allowed per operation.", Generic.class.getSimpleName());
            }
            return;
        } else if (generics.size() == 1) {
            genericSpecialization = generics.get(0);
        } else if (node.needsRewrites(context)) {
            SpecializationData specialization = specializations.get(0);
            GenericParser parser = new GenericParser(context, node);
            MethodSpec specification = parser.createDefaultMethodSpec(specialization.getMethod(), null, null);

            ExecutableTypeData anyGenericReturnType = node.findAnyGenericExecutableType(context);
            assert anyGenericReturnType != null;

            ActualParameter returnType = new ActualParameter(specification.getReturnType(), anyGenericReturnType.getType().getPrimitiveType(), 0, false);
            List<ActualParameter> parameters = new ArrayList<>();
            for (ActualParameter specializationParameter : specialization.getParameters()) {
                ParameterSpec parameterSpec = specification.findParameterSpec(specializationParameter.getSpecification().getName());
                NodeFieldData field = node.findField(parameterSpec.getName());
                TypeMirror actualType;
                if (field == null) {
                    actualType = specializationParameter.getActualType();
                } else {
                    ExecutableTypeData paramType = field.getNodeData().findAnyGenericExecutableType(context);
                    assert paramType != null;
                    actualType = paramType.getType().getPrimitiveType();
                }
                parameters.add(new ActualParameter(parameterSpec, actualType, specializationParameter.getIndex(), specializationParameter.isHidden()));
            }
            TemplateMethod genericMethod = new TemplateMethod("Generic", node, specification, null, null, returnType, parameters);
            genericSpecialization = new SpecializationData(genericMethod, true, false);

            specializations.add(genericSpecialization);
        }

        if (genericSpecialization != null) {
            CodeExecutableElement uninitializedMethod = new CodeExecutableElement(Utils.modifiers(Modifier.PUBLIC), context.getType(void.class), "doUninitialized");
            TemplateMethod uninializedMethod = new TemplateMethod("Uninitialized", node, genericSpecialization.getSpecification(), uninitializedMethod, genericSpecialization.getMarkerAnnotation(),
                            genericSpecialization.getReturnType(), genericSpecialization.getParameters());
            specializations.add(new SpecializationData(uninializedMethod, false, true));
        }

        Collections.sort(specializations, new Comparator<SpecializationData>() {

            @Override
            public int compare(SpecializationData o1, SpecializationData o2) {
                return compareSpecialization(node.getTypeSystem(), o1, o2);
            }
        });

        node.setSpecializations(specializations);

        for (SpecializationData specialization : specializations) {
            specialization.setId(findUniqueSpecializationId(specialization));
        }
    }

    private static String findUniqueSpecializationId(SpecializationData specialization) {

        String name;
        if (specialization.isGeneric()) {
            name = "Generic";
        } else if (specialization.isUninitialized()) {
            name = "Uninitialized";
        } else {
            List<SpecializationData> specializations = new ArrayList<>(specialization.getNode().getSpecializations());
            for (ListIterator<SpecializationData> iterator = specializations.listIterator(); iterator.hasNext();) {
                SpecializationData data = iterator.next();
                if (data.isGeneric() || data.isUninitialized()) {
                    iterator.remove();
                }
            }

            Map<ParameterSpec, Set<String>> usedIds = new HashMap<>();
            for (SpecializationData other : specializations) {
                for (ActualParameter param : other.getReturnTypeAndParameters()) {
                    if (other.getNode().findField(param.getSpecification().getName()) == null) {
                        continue;
                    }

                    Set<String> types = usedIds.get(param.getSpecification());
                    if (types == null) {
                        types = new HashSet<>();
                        usedIds.put(param.getSpecification(), types);
                    }
                    types.add(Utils.getTypeId(param.getActualType()));
                }
            }

            List<ParameterSpec> ambiguousSpecs = new ArrayList<>();
            for (ActualParameter param : specialization.getReturnTypeAndParameters()) {
                Set<String> ids = usedIds.get(param.getSpecification());
                if (ids != null && ids.size() > 1) {
                    ambiguousSpecs.add(param.getSpecification());
                }
            }

            String specializationId = findSpecializationId(specialization, ambiguousSpecs);
            int specializationIndex = 0;
            int totalIndex = 0;

            for (SpecializationData other : specializations) {
                String id = findSpecializationId(other, ambiguousSpecs);
                if (id.equals(specializationId)) {
                    totalIndex++;
                    if (specialization == other) {
                        specializationIndex = totalIndex;
                    }
                }
            }

            if (specializationIndex != totalIndex) {
                name = specializationId + specializationIndex;
            } else {
                name = specializationId;
            }
        }
        return name;
    }

    private static String findSpecializationId(SpecializationData specialization, List<ParameterSpec> specs) {
        boolean allSame = true;
        ActualParameter prevParam = specialization.getReturnType();
        for (ParameterSpec spec : specs) {
            ActualParameter param = specialization.findParameter(spec);
            if (!Utils.typeEquals(prevParam.getActualType(), param.getActualType())) {
                allSame = false;
                break;
            }
            prevParam = param;
        }

        if (allSame) {
            return Utils.getTypeId(prevParam.getActualType());
        } else {
            StringBuilder nameBuilder = new StringBuilder();
            nameBuilder.append(Utils.getTypeId(prevParam.getActualType()));
            for (ParameterSpec spec : specs) {
                ActualParameter param = specialization.findParameter(spec);
                nameBuilder.append(Utils.getTypeId(param.getActualType()));
            }
            return nameBuilder.toString();
        }
    }

    private void verifyNode(NodeData nodeData) {
        // verify specialization parameter length
        verifySpecializationParameters(nodeData);

        // verify order is not ambiguous
        verifySpecializationOrder(nodeData);

        verifyMissingAbstractMethods(nodeData);

        assignShortCircuitsToSpecializations(nodeData);

        verifyConstructors(nodeData);

// if (!verifyNamingConvention(specializations, "do")) {
// return null;
// }
//
// if (!verifyNamesUnique(specializations)) {
// return null;
// }

        verifyNamingConvention(nodeData.getShortCircuits(), "needs");

        verifySpecializationThrows(nodeData);
    }

    private NodeData parseNodeData(TypeElement templateType, TypeElement nodeType) {
        List<Element> elements = new ArrayList<>(context.getEnvironment().getElementUtils().getAllMembers(nodeType));
        List<TypeElement> typeHierarchy = findSuperClasses(new ArrayList<TypeElement>(), nodeType);
        Collections.reverse(typeHierarchy);
        NodeData nodeData = new NodeData(templateType, templateType.getSimpleName().toString());

        AnnotationMirror typeSystemMirror = findFirstAnnotation(typeHierarchy, TypeSystemReference.class);
        if (typeSystemMirror == null) {
            nodeData.addError("No @%s annotation found in type hierarchy of %s.", TypeSystemReference.class.getSimpleName(), nodeType.getQualifiedName().toString());
            return nodeData;
        }

        TypeMirror typeSytemType = Utils.getAnnotationValue(TypeMirror.class, typeSystemMirror, "value");
        final TypeSystemData typeSystem = (TypeSystemData) context.getTemplate(typeSytemType, true);
        if (typeSystem == null) {
            nodeData.addError("The used type system '%s' is invalid.", Utils.getQualifiedName(typeSytemType));
            return nodeData;
        }

        nodeData.setNodeType(nodeType.asType());
        nodeData.setTypeSystem(typeSystem);

        List<ExecutableTypeData> executableTypes = filterExecutableTypes(new ExecutableTypeMethodParser(context, nodeData).parse(elements));
        nodeData.setExecutableTypes(executableTypes);
        parsedNodes.put(Utils.getQualifiedName(templateType), nodeData);
        nodeData.setFields(parseFields(nodeData, elements, typeHierarchy));

        return nodeData;
    }

    private static void verifySpecializationParameters(NodeData nodeData) {
        boolean valid = true;
        int args = -1;
        for (SpecializationData specializationData : nodeData.getSpecializations()) {
            int specializationArgs = 0;
            for (ActualParameter param : specializationData.getParameters()) {
                if (!param.getSpecification().isOptional()) {
                    specializationArgs++;
                }
            }
            if (args != -1 && args != specializationArgs) {
                valid = false;
                break;
            }
            args = specializationArgs;
        }
        if (!valid) {
            for (SpecializationData specialization : nodeData.getSpecializations()) {
                specialization.addError("All specializations must have the same number of arguments.");
            }
        }
    }

    private void verifyMissingAbstractMethods(NodeData nodeData) {
        if (!nodeData.needsFactory()) {
            // missing abstract methods only needs to be implemented
            // if we need go generate factory for it.
            return;
        }

        List<Element> elements = new ArrayList<>(context.getEnvironment().getElementUtils().getAllMembers(nodeData.getTemplateType()));

        Set<Element> unusedElements = new HashSet<>(elements);
        for (TemplateMethod method : nodeData.getAllTemplateMethods()) {
            unusedElements.remove(method.getMethod());
        }
        if (nodeData.getExtensionElements() != null) {
            unusedElements.removeAll(nodeData.getExtensionElements());
        }

        for (ExecutableElement unusedMethod : ElementFilter.methodsIn(unusedElements)) {
            if (unusedMethod.getModifiers().contains(Modifier.ABSTRACT)) {
                nodeData.addError("The type %s must implement the inherited abstract method %s.", Utils.getSimpleName(nodeData.getTemplateType()), Utils.getReadableSignature(unusedMethod));
            }
        }
    }

    private void verifyConstructors(NodeData nodeData) {
        if (!nodeData.needsRewrites(context)) {
            // no specialization constructor is needed if the node never rewrites.
            return;
        }

        TypeElement type = Utils.fromTypeMirror(nodeData.getNodeType());

        List<ExecutableElement> constructors = ElementFilter.constructorsIn(type.getEnclosedElements());
        for (ExecutableElement e : constructors) {
            if (e.getParameters().size() == 1) {
                TypeMirror firstArg = e.getParameters().get(0).asType();
                if (Utils.typeEquals(firstArg, nodeData.getNodeType())) {
                    if (e.getModifiers().contains(Modifier.PRIVATE)) {
                        nodeData.addError("The specialization constructor must not be private.");
                    } else if (constructors.size() <= 1) {
                        nodeData.addError("The specialization constructor must not be the only constructor. The definition of an alternative constructor is required.");
                    }
                    return;
                }
            }
        }

        // not found
        nodeData.addError("Specialization constructor '%s(%s previousNode) { this(...); }' is required.", Utils.getSimpleName(type), Utils.getSimpleName(type));
    }

    private static List<ExecutableTypeData> filterExecutableTypes(List<ExecutableTypeData> executableTypes) {
        List<ExecutableTypeData> filteredExecutableTypes = new ArrayList<>();
        for (ExecutableTypeData t1 : executableTypes) {
            boolean add = true;
            for (ExecutableTypeData t2 : executableTypes) {
                if (t1 == t2) {
                    continue;
                }
                if (Utils.typeEquals(t1.getType().getPrimitiveType(), t2.getType().getPrimitiveType())) {
                    if (t1.isFinal() && !t2.isFinal()) {
                        add = false;
                    }
                }
            }
            if (add) {
                filteredExecutableTypes.add(t1);
            }
        }

        Collections.sort(filteredExecutableTypes, new Comparator<ExecutableTypeData>() {

            @Override
            public int compare(ExecutableTypeData o1, ExecutableTypeData o2) {
                int index1 = o1.getTypeSystem().findType(o1.getType());
                int index2 = o2.getTypeSystem().findType(o2.getType());
                if (index1 == -1 || index2 == -1) {
                    return 0;
                }
                return index1 - index2;
            }
        });
        return filteredExecutableTypes;
    }

    private AnnotationMirror findFirstAnnotation(List<? extends Element> elements, Class<? extends Annotation> annotation) {
        for (Element element : elements) {
            AnnotationMirror mirror = Utils.findAnnotationMirror(processingEnv, element, annotation);
            if (mirror != null) {
                return mirror;
            }
        }
        return null;
    }

    private List<NodeFieldData> parseFields(NodeData nodeData, List<? extends Element> elements, final List<TypeElement> typeHierarchy) {
        AnnotationMirror executionOrderMirror = findFirstAnnotation(typeHierarchy, ExecuteChildren.class);
        List<String> executionDefinition = null;
        if (executionOrderMirror != null) {
            executionDefinition = new ArrayList<>();
            for (String object : Utils.getAnnotationValueList(String.class, executionOrderMirror, "value")) {
                executionDefinition.add(object);
            }
        }

        Set<String> shortCircuits = new HashSet<>();
        for (ExecutableElement method : ElementFilter.methodsIn(elements)) {
            AnnotationMirror mirror = Utils.findAnnotationMirror(processingEnv, method, ShortCircuit.class);
            if (mirror != null) {
                shortCircuits.add(Utils.getAnnotationValue(String.class, mirror, "value"));
            }
        }

        List<NodeFieldData> fields = new ArrayList<>();
        for (VariableElement var : ElementFilter.fieldsIn(elements)) {
            if (var.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }

            if (executionDefinition != null) {
                if (!executionDefinition.contains(var.getSimpleName().toString())) {
                    continue;
                }
            }

            NodeFieldData field = parseField(nodeData, var, shortCircuits);
            if (field.getExecutionKind() != ExecutionKind.IGNORE) {
                fields.add(field);
            }
        }
        sortByExecutionOrder(fields, executionDefinition == null ? Collections.<String> emptyList() : executionDefinition, typeHierarchy);
        return fields;
    }

    private NodeFieldData parseField(NodeData parentNodeData, VariableElement var, Set<String> foundShortCircuits) {
        AnnotationMirror childMirror = Utils.findAnnotationMirror(processingEnv, var, Child.class);
        AnnotationMirror childrenMirror = Utils.findAnnotationMirror(processingEnv, var, Children.class);

        FieldKind kind;

        ExecutionKind execution;
        if (foundShortCircuits.contains(var.getSimpleName().toString())) {
            execution = ExecutionKind.SHORT_CIRCUIT;
        } else {
            execution = ExecutionKind.DEFAULT;
        }

        AnnotationMirror mirror;
        TypeMirror nodeType;

        if (childMirror != null) {
            mirror = childMirror;
            nodeType = var.asType();
            kind = FieldKind.CHILD;
        } else if (childrenMirror != null) {
            mirror = childrenMirror;
            nodeType = getComponentType(var.asType());
            kind = FieldKind.CHILDREN;
        } else {
            execution = ExecutionKind.IGNORE;
            nodeType = null;
            mirror = null;
            kind = null;
        }

        NodeFieldData fieldData = new NodeFieldData(var, findAccessElement(var), mirror, kind, execution);
        if (nodeType != null) {
            NodeData fieldNodeData = resolveNode(Utils.fromTypeMirror(nodeType));
            fieldData.setNode(fieldNodeData);

            if (fieldNodeData == null) {
                fieldData.addError("Node type '%s' is invalid.", Utils.getQualifiedName(nodeType));
            } else if (fieldNodeData.findGenericExecutableTypes(context).isEmpty()) {
                fieldData.addError("No executable generic types found for node '%s'.", Utils.getQualifiedName(nodeType));
            }

            // TODO correct handling of access elements
            if (var.getModifiers().contains(Modifier.PRIVATE) && Utils.typeEquals(var.getEnclosingElement().asType(), parentNodeData.getTemplateType().asType())) {
                execution = ExecutionKind.IGNORE;
            }
        }
        return fieldData;
    }

    private Element findAccessElement(VariableElement variableElement) {
        Element enclosed = variableElement.getEnclosingElement();
        if (!enclosed.getKind().isClass()) {
            throw new IllegalArgumentException("Field must be enclosed in a class.");
        }

        String methodName;
        if (Utils.typeEquals(variableElement.asType(), context.getType(boolean.class))) {
            methodName = "is" + Utils.firstLetterUpperCase(variableElement.getSimpleName().toString());
        } else {
            methodName = "get" + Utils.firstLetterUpperCase(variableElement.getSimpleName().toString());
        }

        ExecutableElement getter = null;
        for (ExecutableElement method : ElementFilter.methodsIn(enclosed.getEnclosedElements())) {
            if (method.getSimpleName().toString().equals(methodName) && method.getParameters().size() == 0 && !Utils.typeEquals(method.getReturnType(), context.getType(void.class))) {
                getter = method;
                break;
            }
        }

        if (getter != null) {
            return getter;
        } else {
            return variableElement;
        }
    }

    private static void sortByExecutionOrder(List<NodeFieldData> fields, final List<String> executionOrder, final List<TypeElement> typeHierarchy) {
        Collections.sort(fields, new Comparator<NodeFieldData>() {

            @Override
            public int compare(NodeFieldData o1, NodeFieldData o2) {
                // sort by execution order
                int index1 = executionOrder.indexOf(o1.getName());
                int index2 = executionOrder.indexOf(o2.getName());
                if (index1 == -1 || index2 == -1) {
                    // sort by type hierarchy
                    index1 = typeHierarchy.indexOf(o1.getFieldElement().getEnclosingElement());
                    index2 = typeHierarchy.indexOf(o2.getFieldElement().getEnclosingElement());

                    // finally sort by name (will emit warning)
                    if (index1 == -1 || index2 == -1) {
                        return o1.getName().compareTo(o2.getName());
                    }
                }
                return index1 - index2;
            }
        });
    }

    private void assignShortCircuitsToSpecializations(NodeData node) {
        Map<String, List<ShortCircuitData>> groupedShortCircuits = groupShortCircuits(node.getShortCircuits());

        boolean valid = true;
        for (NodeFieldData field : node.filterFields(null, ExecutionKind.SHORT_CIRCUIT)) {
            String valueName = field.getName();
            List<ShortCircuitData> availableCircuits = groupedShortCircuits.get(valueName);

            if (availableCircuits == null || availableCircuits.isEmpty()) {
                node.addError("@%s method for short cut value '%s' required.", ShortCircuit.class.getSimpleName(), valueName);
                valid = false;
                continue;
            }

            boolean sameMethodName = true;
            String methodName = availableCircuits.get(0).getMethodName();
            for (ShortCircuitData circuit : availableCircuits) {
                if (!circuit.getMethodName().equals(methodName)) {
                    sameMethodName = false;
                }
            }

            if (!sameMethodName) {
                for (ShortCircuitData circuit : availableCircuits) {
                    circuit.addError("All short circuits for short cut value '%s' must have the same method name.", valueName);
                }
                valid = false;
                continue;
            }

            ShortCircuitData genericCircuit = null;
            for (ShortCircuitData circuit : availableCircuits) {
                if (isGenericShortCutMethod(node, circuit)) {
                    genericCircuit = circuit;
                    break;
                }
            }

            if (genericCircuit == null) {
                node.addError("No generic @%s method available for short cut value '%s'.", ShortCircuit.class.getSimpleName(), valueName);
                valid = false;
                continue;
            }

            for (ShortCircuitData circuit : availableCircuits) {
                if (circuit != genericCircuit) {
                    circuit.setGenericShortCircuitMethod(genericCircuit);
                }
            }
        }

        if (!valid) {
            return;
        }

        NodeFieldData[] fields = node.filterFields(null, ExecutionKind.SHORT_CIRCUIT);
        for (SpecializationData specialization : node.getSpecializations()) {
            List<ShortCircuitData> assignedShortCuts = new ArrayList<>(fields.length);

            for (int i = 0; i < fields.length; i++) {
                List<ShortCircuitData> availableShortCuts = groupedShortCircuits.get(fields[i].getName());

                ShortCircuitData genericShortCircuit = null;
                ShortCircuitData compatibleShortCircuit = null;
                for (ShortCircuitData circuit : availableShortCuts) {
                    if (circuit.isGeneric()) {
                        genericShortCircuit = circuit;
                    } else if (circuit.isCompatibleTo(specialization)) {
                        compatibleShortCircuit = circuit;
                    }
                }

                if (compatibleShortCircuit == null) {
                    compatibleShortCircuit = genericShortCircuit;
                }
                assignedShortCuts.add(compatibleShortCircuit);
            }
            specialization.setShortCircuits(assignedShortCuts);
        }
    }

    private static void verifyNamingConvention(List<? extends TemplateMethod> methods, String prefix) {
        for (int i = 0; i < methods.size(); i++) {
            TemplateMethod m1 = methods.get(i);
            if (m1.getMethodName().length() < 3 || !m1.getMethodName().startsWith(prefix)) {
                m1.addError("Naming convention: method name must start with '%s'.", prefix);
            }
        }
    }

    private boolean isGenericShortCutMethod(NodeData node, TemplateMethod method) {
        for (ActualParameter parameter : method.getParameters()) {
            NodeFieldData field = node.findField(parameter.getSpecification().getName());
            if (field == null) {
                continue;
            }
            ExecutableTypeData found = null;
            List<ExecutableTypeData> executableElements = field.getNodeData().findGenericExecutableTypes(context);
            for (ExecutableTypeData executable : executableElements) {
                if (executable.getType().equalsType(parameter.getActualTypeData(node.getTypeSystem()))) {
                    found = executable;
                    break;
                }
            }
            if (found == null) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, List<ShortCircuitData>> groupShortCircuits(List<ShortCircuitData> shortCircuits) {
        Map<String, List<ShortCircuitData>> group = new HashMap<>();
        for (ShortCircuitData shortCircuit : shortCircuits) {
            List<ShortCircuitData> circuits = group.get(shortCircuit.getValueName());
            if (circuits == null) {
                circuits = new ArrayList<>();
                group.put(shortCircuit.getValueName(), circuits);
            }
            circuits.add(shortCircuit);
        }
        return group;
    }

    private TypeMirror getComponentType(TypeMirror type) {
        if (type instanceof ArrayType) {
            return getComponentType(((ArrayType) type).getComponentType());
        }
        return type;
    }

    private static List<TypeElement> findSuperClasses(List<TypeElement> collection, TypeElement element) {
        if (element.getSuperclass() != null) {
            TypeElement superElement = Utils.fromTypeMirror(element.getSuperclass());
            if (superElement != null) {
                findSuperClasses(collection, superElement);
            }
        }
        collection.add(element);
        return collection;
    }

    private static void verifySpecializationOrder(NodeData node) {
        TypeSystemData typeSystem = node.getTypeSystem();
        List<SpecializationData> specializations = node.getSpecializations();

        for (int i = 0; i < specializations.size(); i++) {
            SpecializationData m1 = specializations.get(i);
            for (int j = i + 1; j < specializations.size(); j++) {
                SpecializationData m2 = specializations.get(j);
                int inferredOrder = compareSpecializationWithoutOrder(typeSystem, m1, m2);

                if (m1.getOrder() != Specialization.DEFAULT_ORDER && m2.getOrder() != Specialization.DEFAULT_ORDER) {
                    int specOrder = m1.getOrder() - m2.getOrder();
                    if (specOrder == 0) {
                        m1.addError("Order value %d used multiple times", m1.getOrder());
                        m2.addError("Order value %d used multiple times", m1.getOrder());
                        return;
                    } else if ((specOrder < 0 && inferredOrder > 0) || (specOrder > 0 && inferredOrder < 0)) {
                        m1.addError("Explicit order values %d and %d are inconsistent with type lattice ordering.", m1.getOrder(), m2.getOrder());
                        m2.addError("Explicit order values %d and %d are inconsistent with type lattice ordering.", m1.getOrder(), m2.getOrder());
                        return;
                    }
                } else if (inferredOrder == 0) {
                    SpecializationData m = (m1.getOrder() == Specialization.DEFAULT_ORDER ? m1 : m2);
                    m.addError("Cannot calculate a consistent order for this specialization. Define the order attribute to resolve this.");
                    return;
                }
            }
        }
    }

    private static void verifySpecializationThrows(NodeData node) {
        Map<String, SpecializationData> specializationMap = new HashMap<>();
        for (SpecializationData spec : node.getSpecializations()) {
            specializationMap.put(spec.getMethodName(), spec);
        }
        for (SpecializationData sourceSpecialization : node.getSpecializations()) {
            if (sourceSpecialization.getExceptions() != null) {
                for (SpecializationThrowsData throwsData : sourceSpecialization.getExceptions()) {
                    for (SpecializationThrowsData otherThrowsData : sourceSpecialization.getExceptions()) {
                        if (otherThrowsData != throwsData && Utils.typeEquals(otherThrowsData.getJavaClass(), throwsData.getJavaClass())) {
                            throwsData.addError("Duplicate exception type.");
                        }
                    }
                }
            }
        }
    }

    private static int compareSpecialization(TypeSystemData typeSystem, SpecializationData m1, SpecializationData m2) {
        if (m1 == m2) {
            return 0;
        }
        int result = compareSpecializationWithoutOrder(typeSystem, m1, m2);
        if (result == 0) {
            if (m1.getOrder() != Specialization.DEFAULT_ORDER && m2.getOrder() != Specialization.DEFAULT_ORDER) {
                return m1.getOrder() - m2.getOrder();
            }
        }
        return result;
    }

    private static int compareSpecializationWithoutOrder(TypeSystemData typeSystem, SpecializationData m1, SpecializationData m2) {
        if (m1 == m2) {
            return 0;
        }

        if (m1.isUninitialized() && !m2.isUninitialized()) {
            return -1;
        } else if (!m1.isUninitialized() && m2.isUninitialized()) {
            return 1;
        } else if (m1.isGeneric() && !m2.isGeneric()) {
            return 1;
        } else if (!m1.isGeneric() && m2.isGeneric()) {
            return -1;
        }

        if (m1.getTemplate() != m2.getTemplate()) {
            throw new UnsupportedOperationException("Cannot compare two specializations with different templates.");
        }

        int result = compareActualParameter(typeSystem, m1.getReturnType(), m2.getReturnType());

        for (ParameterSpec spec : m1.getSpecification().getParameters()) {
            ActualParameter p1 = m1.findParameter(spec);
            ActualParameter p2 = m2.findParameter(spec);

            if (p1 != null && p2 != null && !Utils.typeEquals(p1.getActualType(), p2.getActualType())) {
                int typeResult = compareActualParameter(typeSystem, p1, p2);
                if (result == 0) {
                    result = typeResult;
                } else if (Math.signum(result) != Math.signum(typeResult)) {
                    // We cannot define an order.
                    return 0;
                }
            }
        }
        return result;
    }

    private static int compareActualParameter(TypeSystemData typeSystem, ActualParameter p1, ActualParameter p2) {
        int index1 = typeSystem.findType(p1.getActualType());
        int index2 = typeSystem.findType(p2.getActualType());

        assert index1 != index2;
        assert !(index1 == -1 ^ index2 == -1);

        return index1 - index2;
    }

    @Override
    public Class<? extends Annotation> getAnnotationType() {
        return null;
    }

    @Override
    public List<Class<? extends Annotation>> getTypeDelegatedAnnotationTypes() {
        return ANNOTATIONS;
    }

}
