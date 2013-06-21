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
package com.oracle.truffle.codegen.processor.typesystem;

import static com.oracle.truffle.codegen.processor.Utils.*;

import java.lang.annotation.*;
import java.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;

import com.oracle.truffle.api.codegen.*;
import com.oracle.truffle.codegen.processor.*;
import com.oracle.truffle.codegen.processor.template.*;

public class TypeSystemParser extends TemplateParser<TypeSystemData> {

    public static final List<Class<TypeSystem>> ANNOTATIONS = Arrays.asList(TypeSystem.class);

    public TypeSystemParser(ProcessorContext c) {
        super(c);
    }

    @Override
    public Class<? extends Annotation> getAnnotationType() {
        return TypeSystem.class;
    }

    @Override
    protected TypeSystemData parse(Element element, AnnotationMirror mirror) {
        TypeElement templateType = (TypeElement) element;
        AnnotationMirror templateTypeAnnotation = mirror;
        TypeSystemData typeSystem = new TypeSystemData(templateType, templateTypeAnnotation);

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

        typeSystem.setTypes(parseTypes(typeSystem));
        if (typeSystem.getTypes() == null) {
            return typeSystem;
        }

        TypeMirror genericType = context.getType(Object.class);
        TypeData voidType = new TypeData(typeSystem, null, context.getType(void.class), context.getType(Void.class));

        typeSystem.setGenericType(genericType);
        typeSystem.setVoidType(voidType);

        verifyExclusiveMethodAnnotation(typeSystem, TypeCast.class, TypeCheck.class);

        List<Element> elements = new ArrayList<>(context.getEnvironment().getElementUtils().getAllMembers(templateType));

        List<TypeCastData> casts = new TypeCastParser(context, typeSystem).parse(elements);
        List<TypeCheckData> checks = new TypeCheckParser(context, typeSystem).parse(elements);

        if (casts == null || checks == null) {
            return typeSystem;
        }

        for (TypeCheckData check : checks) {
            check.getCheckedType().addTypeCheck(check);
        }

        for (TypeCastData cast : casts) {
            cast.getTargetType().addTypeCast(cast);
        }

        verifyGenericTypeChecksAndCasts(typeSystem);
        verifyMethodSignatures(typeSystem);
        verifyNamesUnique(typeSystem);

        return typeSystem;
    }

    private static void verifyGenericTypeChecksAndCasts(TypeSystemData typeSystem) {
        for (TypeData type : typeSystem.getTypes()) {
            if (!type.getTypeChecks().isEmpty()) {
                boolean hasGeneric = false;
                for (TypeCheckData typeCheck : type.getTypeChecks()) {
                    if (typeCheck.isGeneric()) {
                        hasGeneric = true;
                        break;
                    }
                }
                if (!hasGeneric) {
                    type.addError("No generic but specific @%s method %s for type %s specified. " + "Specify a generic @%s method with parameter type %s to resolve this.",
                                    TypeCheck.class.getSimpleName(), TypeSystemCodeGenerator.isTypeMethodName(type), Utils.getSimpleName(type.getBoxedType()), TypeCheck.class.getSimpleName(),
                                    Object.class.getSimpleName());
                }
            }
            if (!type.getTypeCasts().isEmpty()) {
                boolean hasGeneric = false;
                for (TypeCastData typeCast : type.getTypeCasts()) {
                    if (typeCast.isGeneric()) {
                        hasGeneric = true;
                        break;
                    }
                }
                if (!hasGeneric) {
                    type.addError("No generic but specific @%s method %s for type %s specified. " + "Specify a generic @%s method with parameter type %s to resolve this.",
                                    TypeCast.class.getSimpleName(), TypeSystemCodeGenerator.asTypeMethodName(type), Utils.getSimpleName(type.getBoxedType()), TypeCast.class.getSimpleName(),
                                    Object.class.getSimpleName());
                }
            }
        }
    }

    private List<TypeData> parseTypes(TypeSystemData typeSystem) {
        List<TypeData> types = new ArrayList<>();
        List<TypeMirror> typeMirrors = Utils.getAnnotationValueList(TypeMirror.class, typeSystem.getTemplateTypeAnnotation(), "value");
        if (typeMirrors.isEmpty()) {
            typeSystem.addError("At least one type must be defined.");
            return types;
        }

        final AnnotationValue annotationValue = Utils.getAnnotationValue(typeSystem.getTemplateTypeAnnotation(), "value");
        final TypeMirror objectType = context.getType(Object.class);

        for (TypeMirror primitiveType : typeMirrors) {
            TypeMirror boxedType = Utils.boxType(context, primitiveType);
            TypeData typeData = new TypeData(typeSystem, annotationValue, primitiveType, boxedType);

            if (isPrimitiveWrapper(primitiveType)) {
                typeData.addError("Types must not contain primitive wrapper types.");
            }

            if (Utils.typeEquals(boxedType, objectType)) {
                typeData.addError("Types must not contain the generic type java.lang.Object.");
            }

            types.add(typeData);
        }

        verifyTypeOrder(types);

        types.add(new TypeData(typeSystem, annotationValue, objectType, objectType));

        return types;
    }

    private static void verifyTypeOrder(List<TypeData> types) {
        Map<String, List<String>> invalidTypes = new HashMap<>();

        for (int i = types.size() - 1; i >= 0; i--) {
            TypeData typeData = types.get(i);
            TypeMirror type = typeData.getBoxedType();
            if (invalidTypes.containsKey(Utils.getQualifiedName(type))) {
                typeData.addError("Invalid type order. The type(s) %s are inherited from a earlier defined type %s.", invalidTypes.get(Utils.getQualifiedName(type)), Utils.getQualifiedName(type));
            }
            List<String> nextInvalidTypes = Utils.getQualifiedSuperTypeNames(Utils.fromTypeMirror(type));
            nextInvalidTypes.add(getQualifiedName(type));

            for (String qualifiedName : nextInvalidTypes) {
                List<String> inheritedTypes = invalidTypes.get(qualifiedName);
                if (inheritedTypes == null) {
                    inheritedTypes = new ArrayList<>();
                    invalidTypes.put(qualifiedName, inheritedTypes);
                }
                inheritedTypes.add(Utils.getQualifiedName(typeData.getBoxedType()));
            }
        }
    }

    private boolean isPrimitiveWrapper(TypeMirror type) {
        Types types = context.getEnvironment().getTypeUtils();
        for (TypeKind kind : TypeKind.values()) {
            if (!kind.isPrimitive()) {
                continue;
            }
            if (Utils.typeEquals(type, types.boxedClass(types.getPrimitiveType(kind)).asType())) {
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
        AnnotationMirror mirror = Utils.findAnnotationMirror(processingEnv, method, TypeCheck.class);
        if (mirror == null) {
            typeSystem.addError("Method starting with the pattern is${typeName} must be annotated with @%s.", TypeCheck.class.getSimpleName());
            return false;
        }
        return true;
    }

    private boolean verifyAsMethod(TypeSystemData typeSystem, ExecutableElement method) {
        AnnotationMirror mirror = Utils.findAnnotationMirror(processingEnv, method, TypeCast.class);
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
        List<TypeData> types = typeSystem.getTypes();
        for (int i = 0; i < types.size(); i++) {
            for (int j = i + 1; j < types.size(); j++) {
                String name1 = Utils.getSimpleName(types.get(i).getBoxedType());
                String name2 = Utils.getSimpleName(types.get(j).getBoxedType());
                if (name1.equalsIgnoreCase(name2)) {
                    typeSystem.addError("Two types result in the same name: %s, %s.", name1, name2);
                }
            }
        }
    }
}
