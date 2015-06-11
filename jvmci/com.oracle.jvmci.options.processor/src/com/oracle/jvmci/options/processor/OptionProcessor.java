/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.jvmci.options.processor;

import java.io.*;
import java.util.*;

import javax.annotation.processing.*;
import javax.lang.model.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.tools.Diagnostic.Kind;
import javax.tools.*;

import com.oracle.jvmci.options.*;

/**
 * Processes static fields annotated with {@link Option}. An {@link Options} service is generated
 * for each top level class containing at least one such field. The name of the generated class for
 * top level class {@code com.foo.Bar} is {@code com.foo.Bar_Options}.
 *
 * The build system is expected to create the appropriate entries in {@code META-INF/services/} such
 * that these service objects can be retrieved as follows:
 *
 * <pre>
 * ServiceLoader&lt;Options&gt; sl = ServiceLoader.load(Options.class);
 * for (Options opts : sl) {
 *     for (OptionDescriptor desc : sl) {
 *         // use desc
 *     }
 * }
 * </pre>
 */
@SupportedAnnotationTypes({"com.oracle.jvmci.options.Option"})
public class OptionProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    private final Set<Element> processed = new HashSet<>();

    private void processElement(Element element, OptionsInfo info) {

        if (!element.getModifiers().contains(Modifier.STATIC)) {
            processingEnv.getMessager().printMessage(Kind.ERROR, "Option field must be static", element);
            return;
        }

        Option annotation = element.getAnnotation(Option.class);
        assert annotation != null;
        assert element instanceof VariableElement;
        assert element.getKind() == ElementKind.FIELD;
        VariableElement field = (VariableElement) element;
        String fieldName = field.getSimpleName().toString();

        Elements elements = processingEnv.getElementUtils();
        Types types = processingEnv.getTypeUtils();

        TypeMirror fieldType = field.asType();
        if (fieldType.getKind() != TypeKind.DECLARED) {
            processingEnv.getMessager().printMessage(Kind.ERROR, "Option field must be of type " + OptionValue.class.getName(), element);
            return;
        }
        DeclaredType declaredFieldType = (DeclaredType) fieldType;

        TypeMirror optionValueType = elements.getTypeElement(OptionValue.class.getName()).asType();
        if (!types.isSubtype(fieldType, types.erasure(optionValueType))) {
            String msg = String.format("Option field type %s is not a subclass of %s", fieldType, optionValueType);
            processingEnv.getMessager().printMessage(Kind.ERROR, msg, element);
            return;
        }

        if (!field.getModifiers().contains(Modifier.STATIC)) {
            processingEnv.getMessager().printMessage(Kind.ERROR, "Option field must be static", element);
            return;
        }

        String help = annotation.help();
        if (help.length() != 0) {
            char firstChar = help.charAt(0);
            if (!Character.isUpperCase(firstChar)) {
                processingEnv.getMessager().printMessage(Kind.ERROR, "Option help text must start with upper case letter", element);
                return;
            }
        }

        String optionName = annotation.name();
        if (optionName.equals("")) {
            optionName = fieldName;
        }

        DeclaredType declaredOptionValueType = declaredFieldType;
        while (!types.isSameType(types.erasure(declaredOptionValueType), types.erasure(optionValueType))) {
            List<? extends TypeMirror> directSupertypes = types.directSupertypes(declaredFieldType);
            assert !directSupertypes.isEmpty();
            declaredOptionValueType = (DeclaredType) directSupertypes.get(0);
        }

        assert !declaredOptionValueType.getTypeArguments().isEmpty();
        String optionType = declaredOptionValueType.getTypeArguments().get(0).toString();
        if (optionType.startsWith("java.lang.")) {
            optionType = optionType.substring("java.lang.".length());
        }

        Element enclosing = element.getEnclosingElement();
        String declaringClass = "";
        String separator = "";
        Set<Element> originatingElementsList = info.originatingElements;
        originatingElementsList.add(field);
        while (enclosing != null) {
            if (enclosing.getKind() == ElementKind.CLASS || enclosing.getKind() == ElementKind.INTERFACE) {
                if (enclosing.getModifiers().contains(Modifier.PRIVATE)) {
                    String msg = String.format("Option field cannot be declared in a private %s %s", enclosing.getKind().name().toLowerCase(), enclosing);
                    processingEnv.getMessager().printMessage(Kind.ERROR, msg, element);
                    return;
                }
                originatingElementsList.add(enclosing);
                declaringClass = enclosing.getSimpleName() + separator + declaringClass;
                separator = ".";
            } else {
                assert enclosing.getKind() == ElementKind.PACKAGE;
            }
            enclosing = enclosing.getEnclosingElement();
        }

        info.options.add(new OptionInfo(optionName, help, optionType, declaringClass, field));
    }

    private void createFiles(OptionsInfo info) {
        String pkg = ((PackageElement) info.topDeclaringType.getEnclosingElement()).getQualifiedName().toString();
        Name topDeclaringClass = info.topDeclaringType.getSimpleName();

        String optionsClassName = topDeclaringClass + "_" + Options.class.getSimpleName();
        Element[] originatingElements = info.originatingElements.toArray(new Element[info.originatingElements.size()]);

        Filer filer = processingEnv.getFiler();
        try (PrintWriter out = createSourceFile(pkg, optionsClassName, filer, originatingElements)) {

            out.println("// CheckStyle: stop header check");
            out.println("// GENERATED CONTENT - DO NOT EDIT");
            out.println("// Source: " + topDeclaringClass + ".java");
            out.println("package " + pkg + ";");
            out.println("");
            out.println("import java.util.*;");
            out.println("import " + Options.class.getPackage().getName() + ".*;");
            out.println("");
            out.println("public class " + optionsClassName + " implements " + Options.class.getSimpleName() + " {");
            out.println("    @Override");
            String desc = OptionDescriptor.class.getSimpleName();
            out.println("    public Iterator<" + desc + "> iterator() {");
            out.println("        // CheckStyle: stop line length check");
            out.println("        List<" + desc + "> options = Arrays.asList(");

            boolean needPrivateFieldAccessor = false;
            int i = 0;
            Collections.sort(info.options);
            for (OptionInfo option : info.options) {
                String optionValue;
                if (option.field.getModifiers().contains(Modifier.PRIVATE)) {
                    needPrivateFieldAccessor = true;
                    optionValue = "field(" + option.declaringClass + ".class, \"" + option.field.getSimpleName() + "\")";
                } else {
                    optionValue = option.declaringClass + "." + option.field.getSimpleName();
                }
                String name = option.name;
                String type = option.type;
                String help = option.help;
                String declaringClass = option.declaringClass;
                Name fieldName = option.field.getSimpleName();
                String comma = i == info.options.size() - 1 ? "" : ",";
                out.printf("            new %s(\"%s\", %s.class, \"%s\", %s.class, \"%s\", %s)%s\n", desc, name, type, help, declaringClass, fieldName, optionValue, comma);
                i++;
            }
            out.println("        );");
            out.println("        // CheckStyle: resume line length check");
            out.println("        return options.iterator();");
            out.println("    }");
            if (needPrivateFieldAccessor) {
                out.println("    private static " + OptionValue.class.getSimpleName() + "<?> field(Class<?> declaringClass, String fieldName) {");
                out.println("        try {");
                out.println("            java.lang.reflect.Field field = declaringClass.getDeclaredField(fieldName);");
                out.println("            field.setAccessible(true);");
                out.println("            return (" + OptionValue.class.getSimpleName() + "<?>) field.get(null);");
                out.println("        } catch (Exception e) {");
                out.println("            throw (InternalError) new InternalError().initCause(e);");
                out.println("        }");
                out.println("    }");
            }
            out.println("}");
        }

        try {
            createOptionsFile(info, pkg, topDeclaringClass.toString(), originatingElements);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Kind.ERROR, e.getMessage(), info.topDeclaringType);
        }
    }

    private void createOptionsFile(OptionsInfo info, String pkg, String relativeName, Element... originatingElements) throws IOException {
        String filename = "META-INF/jvmci.options/" + pkg + "." + relativeName;
        FileObject file = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", filename, originatingElements);
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(file.openOutputStream(), "UTF-8"));
        Types types = processingEnv.getTypeUtils();
        for (OptionInfo option : info.options) {
            String help = option.help;
            if (help.indexOf('\t') >= 0 || help.indexOf('\r') >= 0 || help.indexOf('\n') >= 0) {
                processingEnv.getMessager().printMessage(Kind.WARNING, "Option help should not contain '\\t', '\\r' or '\\n'", option.field);
                help = help.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
            }
            try {
                char optionTypeToChar = optionTypeToChar(option);
                String fqDeclaringClass = className(types.erasure(option.field.getEnclosingElement().asType()));
                String fqFieldType = className(types.erasure(option.field.asType()));
                writer.printf("%s\t%s\t%s\t%s\t%s%n", option.name, optionTypeToChar, help, fqDeclaringClass, fqFieldType);
            } catch (IllegalArgumentException iae) {
            }
        }
        writer.close();
    }

    private String className(TypeMirror t) {
        DeclaredType dt = (DeclaredType) t;
        return processingEnv.getElementUtils().getBinaryName((TypeElement) dt.asElement()).toString();
    }

    private char optionTypeToChar(OptionInfo option) {
        switch (option.type) {
            case "Boolean":
                return 'z';
            case "Integer":
                return 'i';
            case "Long":
                return 'j';
            case "Float":
                return 'f';
            case "Double":
                return 'd';
            case "String":
                return 's';
            default:
                processingEnv.getMessager().printMessage(Kind.ERROR, "Unsupported option type: " + option.type, option.field);
                throw new IllegalArgumentException();
        }
    }

    protected PrintWriter createSourceFile(String pkg, String relativeName, Filer filer, Element... originatingElements) {
        try {
            // Ensure Unix line endings to comply with code style guide checked by Checkstyle
            JavaFileObject sourceFile = filer.createSourceFile(pkg + "." + relativeName, originatingElements);
            return new PrintWriter(sourceFile.openWriter()) {

                @Override
                public void println() {
                    print("\n");
                }
            };
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static class OptionInfo implements Comparable<OptionInfo> {

        final String name;
        final String help;
        final String type;
        final String declaringClass;
        final VariableElement field;

        public OptionInfo(String name, String help, String type, String declaringClass, VariableElement field) {
            this.name = name;
            this.help = help;
            this.type = type;
            this.declaringClass = declaringClass;
            this.field = field;
        }

        @Override
        public int compareTo(OptionInfo other) {
            return name.compareTo(other.name);
        }

        @Override
        public String toString() {
            return declaringClass + "." + field;
        }
    }

    static class OptionsInfo {

        final Element topDeclaringType;
        final List<OptionInfo> options = new ArrayList<>();
        final Set<Element> originatingElements = new HashSet<>();

        public OptionsInfo(Element topDeclaringType) {
            this.topDeclaringType = topDeclaringType;
        }
    }

    private static Element topDeclaringType(Element element) {
        Element enclosing = element.getEnclosingElement();
        if (enclosing == null || enclosing.getKind() == ElementKind.PACKAGE) {
            assert element.getKind() == ElementKind.CLASS || element.getKind() == ElementKind.INTERFACE;
            return element;
        }
        return topDeclaringType(enclosing);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return true;
        }

        Map<Element, OptionsInfo> map = new HashMap<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(Option.class)) {
            if (!processed.contains(element)) {
                processed.add(element);
                Element topDeclaringType = topDeclaringType(element);
                OptionsInfo options = map.get(topDeclaringType);
                if (options == null) {
                    options = new OptionsInfo(topDeclaringType);
                    map.put(topDeclaringType, options);
                }
                processElement(element, options);
            }
        }

        boolean ok = true;
        Map<String, OptionInfo> uniqueness = new HashMap<>();
        for (OptionsInfo info : map.values()) {
            for (OptionInfo option : info.options) {
                OptionInfo conflict = uniqueness.put(option.name, option);
                if (conflict != null) {
                    processingEnv.getMessager().printMessage(Kind.ERROR, "Duplicate option names for " + option + " and " + conflict, option.field);
                    ok = false;
                }
            }
        }

        if (ok) {
            for (OptionsInfo info : map.values()) {
                createFiles(info);
            }
        }

        return true;
    }
}
