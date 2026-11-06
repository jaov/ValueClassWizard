package dev.j8a.valuewizard;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.netbeans.api.java.source.CompilationController;

/**
 * Shared helpers for the Insert Code actions.
 */
final class CodeGeneratorSupport {

    static final String BUILDER_NAME = "Builder";
    static final String CAN_BUILD_NAME = "CanBuild";
    static final String STAGE_PREFIX = "Has";

    private CodeGeneratorSupport() {
    }

    static TreePath firstClassPath(CompilationController controller) {
        CompilationUnitTree unit = controller.getCompilationUnit();
        if (unit == null) {
            return null;
        }
        for (Tree declaration : unit.getTypeDecls()) {
            if (declaration instanceof ClassTree) {
                return TreePath.getPath(unit, declaration);
            }
        }
        return null;
    }

    static TreePath enclosingTypePath(CompilationController controller, TreePath path) {
        return controller.getTreeUtilities().getPathElementOfKind(
                EnumSet.of(Tree.Kind.CLASS, Tree.Kind.INTERFACE, Tree.Kind.ENUM,
                        Tree.Kind.ANNOTATION_TYPE, Tree.Kind.RECORD),
                path);
    }

    static List<VariableElement> collectInstanceFields(TypeElement type) {
        List<VariableElement> result = new ArrayList<>();
        for (VariableElement field : ElementFilter.fieldsIn(type.getEnclosedElements())) {
            if (field.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            String name = field.getSimpleName().toString();
            if (name.startsWith("this$") || name.startsWith("val$")) {
                continue;
            }
            if (field.asType().getKind() == TypeKind.ERROR) {
                continue;
            }
            result.add(field);
        }
        return result;
    }

    static ExecutableElement findSuitableConstructor(TypeElement type,
            List<VariableElement> fields, Types types, Elements elements) {
        for (ExecutableElement constructor
                : ElementFilter.constructorsIn(type.getEnclosedElements())) {
            if (findConstructorMapping(constructor, fields, types) != null
                    && !hasCheckedExceptions(constructor, types, elements)) {
                return constructor;
            }
        }
        return null;
    }

    private static boolean hasCheckedExceptions(ExecutableElement constructor,
            Types types, Elements elements) {
        if (constructor.getThrownTypes().isEmpty()) {
            return false;
        }
        TypeElement runtimeException = elements.getTypeElement("java.lang.RuntimeException");
        TypeElement error = elements.getTypeElement("java.lang.Error");
        for (TypeMirror thrown : constructor.getThrownTypes()) {
            try {
                if ((runtimeException != null
                        && types.isSubtype(thrown, runtimeException.asType()))
                        || (error != null
                        && types.isSubtype(thrown, error.asType()))) {
                    continue;
                }
            } catch (RuntimeException ex) {
                return true;
            }
            return true;
        }
        return false;
    }

    static int[] findConstructorMapping(ExecutableElement constructor,
            List<VariableElement> fields, Types types) {
        List<? extends VariableElement> parameters = constructor.getParameters();
        if (parameters.size() != fields.size()) {
            return null;
        }

        boolean[] used = new boolean[fields.size()];
        int[] mapping = new int[parameters.size()];
        for (int i = 0; i < parameters.size(); i++) {
            int match = findField(fields, used, parameters.get(i), types);
            if (match < 0) {
                return null;
            }
            used[match] = true;
            mapping[i] = match;
        }
        return mapping;
    }

    private static int findField(List<VariableElement> fields, boolean[] used,
            VariableElement parameter, Types types) {
        for (int i = 0; i < fields.size(); i++) {
            if (!used[i]
                    && fields.get(i).getSimpleName().contentEquals(parameter.getSimpleName())
                    && types.isSameType(fields.get(i).asType(), parameter.asType())) {
                return i;
            }
        }

        int candidate = -1;
        for (int i = 0; i < fields.size(); i++) {
            if (!used[i] && types.isSameType(fields.get(i).asType(), parameter.asType())) {
                if (candidate >= 0) {
                    return -1;
                }
                candidate = i;
            }
        }
        return candidate;
    }

    static boolean hasMethod(TypeElement type, String name, int parameterCount) {
        for (ExecutableElement method : ElementFilter.methodsIn(type.getEnclosedElements())) {
            if (method.getSimpleName().contentEquals(name)
                    && method.getParameters().size() == parameterCount) {
                return true;
            }
        }
        return false;
    }

    static boolean hasNestedType(TypeElement type, String name) {
        for (TypeElement nested : ElementFilter.typesIn(type.getEnclosedElements())) {
            if (nested.getSimpleName().contentEquals(name)) {
                return true;
            }
        }
        return false;
    }

    static String upperFirst(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
