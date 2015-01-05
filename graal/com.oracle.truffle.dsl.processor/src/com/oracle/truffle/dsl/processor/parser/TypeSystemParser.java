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
package com.oracle.truffle.dsl.processor.parser;

import static com.oracle.truffle.dsl.processor.java.ElementUtils.*;

import java.lang.annotation.*;
import java.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;

import com.oracle.truffle.api.dsl.*;
import com.oracle.truffle.api.dsl.internal.*;
import com.oracle.truffle.dsl.processor.generator.*;
import com.oracle.truffle.dsl.processor.java.*;
import com.oracle.truffle.dsl.processor.model.*;

@DSLOptions
public class TypeSystemParser extends AbstractParser<TypeSystemData> {

    public static final List<Class<? extends Annotation>> ANNOTATIONS = Arrays.asList(TypeSystem.class, ExpectError.class);

    @Override
    public Class<? extends Annotation> getAnnotationType() {
        return TypeSystem.class;
    }

    @Override
    protected TypeSystemData parse(Element element, AnnotationMirror mirror) {
        TypeElement templateType = (TypeElement) element;
        AnnotationMirror templateTypeAnnotation = mirror;
        DSLOptions options = element.getAnnotation(DSLOptions.class);
        if (options == null) {
            options = TypeSystemParser.class.getAnnotation(DSLOptions.class);
        }
        assert options != null;

        TypeSystemData typeSystem = new TypeSystemData(context, templateType, templateTypeAnnotation, options);

        // annotation type on class path!?
        TypeElement annotationTypeElement = processingEnv.getElementUtils().getTypeElement(getAnnotationType().getCanonicalName());
        if (annotationTypeElement == null) {
            typeSystem.addError("Required class %s is not on the classpath.", getAnnotationType().getName());
        }
        if (templateType.getModifiers().contains(Modifier.PRIVATE)) {
            typeSystem.addError("A @%s must have at least package protected visibility.", getAnnotationType().getName());
        }

        if (templateType.getModifiers().contains(Modifier.FINAL)) {
            typeSystem.addError("The @%s must not be final.", getAnnotationType().getName());
        }
        if (typeSystem.hasErrors()) {
            return typeSystem;
        }

        List<TypeData> types = parseTypes(typeSystem);

        TypeMirror genericType = context.getType(Object.class);
        TypeData voidType = new TypeData(typeSystem, types.size(), null, context.getType(void.class), context.getType(Void.class));
        types.add(voidType);

        typeSystem.setTypes(types);
        if (typeSystem.hasErrors()) {
            return typeSystem;
        }
        typeSystem.setGenericType(genericType);
        typeSystem.setVoidType(voidType);

        TypeData booleanType = typeSystem.findTypeData(context.getType(boolean.class));
        if (booleanType == null) {
            booleanType = new TypeData(typeSystem, types.size(), null, context.getType(boolean.class), context.getType(Boolean.class));
        }
        typeSystem.setBooleanType(booleanType);

        verifyExclusiveMethodAnnotation(typeSystem, TypeCast.class, TypeCheck.class);

        List<Element> elements = new ArrayList<>(context.getEnvironment().getElementUtils().getAllMembers(templateType));
        List<ImplicitCastData> implicitCasts = new ImplicitCastParser(context, typeSystem).parse(elements);
        List<TypeCastData> casts = new TypeCastParser(context, typeSystem).parse(elements);
        List<TypeCheckData> checks = new TypeCheckParser(context, typeSystem).parse(elements);

        if (casts == null || checks == null || implicitCasts == null) {
            return typeSystem;
        }

        typeSystem.setImplicitCasts(implicitCasts);
        typeSystem.setCasts(casts);
        typeSystem.setChecks(checks);

        if (typeSystem.hasErrors()) {
            return typeSystem;
        }

        for (TypeCheckData check : checks) {
            check.getCheckedType().addTypeCheck(check);
        }

        for (TypeCastData cast : casts) {
            cast.getTargetType().addTypeCast(cast);
        }

        verifyMethodSignatures(typeSystem);
        verifyNamesUnique(typeSystem);

        return typeSystem;
    }

    private void verifyExclusiveMethodAnnotation(Template template, Class<?>... annotationTypes) {
        List<ExecutableElement> methods = ElementFilter.methodsIn(template.getTemplateType().getEnclosedElements());
        for (ExecutableElement method : methods) {
            List<AnnotationMirror> foundAnnotations = new ArrayList<>();
            for (int i = 0; i < annotationTypes.length; i++) {
                Class<?> annotationType = annotationTypes[i];
                AnnotationMirror mirror = ElementUtils.findAnnotationMirror(context.getEnvironment(), method, annotationType);
                if (mirror != null) {
                    foundAnnotations.add(mirror);
                }
            }
            if (foundAnnotations.size() > 1) {
                List<String> annotationNames = new ArrayList<>();
                for (AnnotationMirror mirror : foundAnnotations) {
                    annotationNames.add("@" + ElementUtils.getSimpleName(mirror.getAnnotationType()));
                }

                template.addError("Non exclusive usage of annotations %s.", annotationNames);
            }
        }
    }

    private List<TypeData> parseTypes(TypeSystemData typeSystem) {
        List<TypeData> types = new ArrayList<>();
        List<TypeMirror> typeMirrors = ElementUtils.getAnnotationValueList(TypeMirror.class, typeSystem.getTemplateTypeAnnotation(), "value");
        if (typeMirrors.isEmpty()) {
            typeSystem.addError("At least one type must be defined.");
            return types;
        }

        final AnnotationValue annotationValue = ElementUtils.getAnnotationValue(typeSystem.getTemplateTypeAnnotation(), "value");
        final TypeMirror objectType = context.getType(Object.class);

        int index = 0;
        for (TypeMirror primitiveType : typeMirrors) {
            TypeMirror primitive = ElementUtils.fillInGenericWildcards(primitiveType);

            TypeMirror boxedType = ElementUtils.boxType(context, primitive);
            TypeData typeData = new TypeData(typeSystem, index, annotationValue, primitive, boxedType);

            if (isPrimitiveWrapper(primitive)) {
                typeData.addError("Types must not contain primitive wrapper types.");
            }

            if (ElementUtils.typeEquals(boxedType, objectType)) {
                typeData.addError("Types must not contain the generic type java.lang.Object.");
            }

            types.add(typeData);
            index++;
        }

        verifyTypeOrder(types);

        types.add(new TypeData(typeSystem, index, annotationValue, objectType, objectType));
        return types;
    }

    private static void verifyTypeOrder(List<TypeData> types) {
        Map<String, List<String>> invalidTypes = new HashMap<>();

        for (int i = types.size() - 1; i >= 0; i--) {
            TypeData typeData = types.get(i);
            TypeMirror type = typeData.getBoxedType();
            if (invalidTypes.containsKey(ElementUtils.getQualifiedName(type))) {
                typeData.addError("Invalid type order. The type(s) %s are inherited from a earlier defined type %s.", invalidTypes.get(ElementUtils.getQualifiedName(type)),
                                ElementUtils.getQualifiedName(type));
            }
            TypeElement element = ElementUtils.fromTypeMirror(type);
            List<String> nextInvalidTypes = new ArrayList<>();
            if (element != null) {
                nextInvalidTypes.addAll(ElementUtils.getQualifiedSuperTypeNames(element));
            }
            nextInvalidTypes.add(getQualifiedName(type));

            for (String qualifiedName : nextInvalidTypes) {
                List<String> inheritedTypes = invalidTypes.get(qualifiedName);
                if (inheritedTypes == null) {
                    inheritedTypes = new ArrayList<>();
                    invalidTypes.put(qualifiedName, inheritedTypes);
                }
                inheritedTypes.add(ElementUtils.getQualifiedName(typeData.getBoxedType()));
            }
        }
    }

    private boolean isPrimitiveWrapper(TypeMirror type) {
        Types types = context.getEnvironment().getTypeUtils();
        for (TypeKind kind : TypeKind.values()) {
            if (!kind.isPrimitive()) {
                continue;
            }
            if (ElementUtils.typeEquals(type, types.boxedClass(types.getPrimitiveType(kind)).asType())) {
                return true;
            }
        }
        return false;
    }

    private void verifyMethodSignatures(TypeSystemData typeSystem) {
        Set<String> generatedIsMethodNames = new HashSet<>();
        Set<String> generatedAsMethodNames = new HashSet<>();
        Set<String> generatedExpectMethodNames = new HashSet<>();

        for (TypeData typeData : typeSystem.getTypes()) {
            generatedIsMethodNames.add(TypeSystemCodeGenerator.isTypeMethodName(typeData));
            generatedAsMethodNames.add(TypeSystemCodeGenerator.asTypeMethodName(typeData));
            generatedExpectMethodNames.add(TypeSystemCodeGenerator.expectTypeMethodName(typeData));
        }

        List<ExecutableElement> methods = ElementFilter.methodsIn(typeSystem.getTemplateType().getEnclosedElements());
        for (ExecutableElement method : methods) {
            if (method.getModifiers().contains(Modifier.PRIVATE)) {
                // will not conflict overridden methods
                continue;
            } else if (method.getParameters().size() != 1) {
                continue;
            }
            String methodName = method.getSimpleName().toString();
            if (generatedIsMethodNames.contains(methodName)) {
                verifyIsMethod(typeSystem, method);
            } else if (generatedAsMethodNames.contains(methodName)) {
                verifyAsMethod(typeSystem, method);
            } else if (generatedExpectMethodNames.contains(methodName)) {
                verifyExpectMethod(typeSystem);
            }
        }
    }

    private boolean verifyIsMethod(TypeSystemData typeSystem, ExecutableElement method) {
        AnnotationMirror mirror = ElementUtils.findAnnotationMirror(processingEnv, method, TypeCheck.class);
        if (mirror == null) {
            typeSystem.addError("Method starting with the pattern is${typeName} must be annotated with @%s.", TypeCheck.class.getSimpleName());
            return false;
        }
        return true;
    }

    private boolean verifyAsMethod(TypeSystemData typeSystem, ExecutableElement method) {
        AnnotationMirror mirror = ElementUtils.findAnnotationMirror(processingEnv, method, TypeCast.class);
        if (mirror == null) {
            typeSystem.addError("Method starting with the pattern as${typeName} must be annotated with @%s.", TypeCast.class.getSimpleName());
            return false;
        }
        return true;
    }

    private static boolean verifyExpectMethod(TypeSystemData typeSystem) {
        typeSystem.addError("Method starting with the pattern expect${typeName} must not be declared manually.");
        return false;
    }

    private static void verifyNamesUnique(TypeSystemData typeSystem) {
        Set<String> usedNames = new HashSet<>();
        for (TypeData type : typeSystem.getTypes()) {
            String boxedName = ElementUtils.getSimpleName(type.getBoxedType());
            String primitiveName = ElementUtils.getSimpleName(type.getPrimitiveType());
            if (usedNames.contains(boxedName)) {
                typeSystem.addError("Two types result in the same boxed name: %s.", boxedName);
            } else if (usedNames.contains(primitiveName)) {
                typeSystem.addError("Two types result in the same primitive name: %s.", primitiveName);
            }
            usedNames.add(boxedName);
            usedNames.add(primitiveName);
        }
    }
}
