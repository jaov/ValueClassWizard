package dev.j8a.valuewizard;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;
import javax.swing.JOptionPane;
import javax.swing.text.JTextComponent;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.GeneratorUtilities;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.ModificationResult;
import org.netbeans.api.java.source.TreeMaker;
import org.netbeans.api.java.source.WorkingCopy;
import org.netbeans.spi.editor.codegen.CodeGenerator;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.util.Exceptions;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;

/**
 * Editor Insert Code action that adds withX(...) methods to an existing class.
 */
public final class WithableGenerator implements CodeGenerator {

    private final JTextComponent component;
    private final ElementHandle<TypeElement> classHandle;
    private final List<WithableField> fields;
    private final String error;

    private WithableGenerator(JTextComponent component,
            ElementHandle<TypeElement> classHandle, List<WithableField> fields, String error) {
        this.component = component;
        this.classHandle = classHandle;
        this.fields = fields;
        this.error = error;
    }

    @Override
    public String getDisplayName() {
        return NbBundle.getMessage(WithableGenerator.class, "WithableGenerator.displayName");
    }

    @Override
    public void invoke() {
        if (error != null) {
            DialogDisplayer.getDefault().notifyLater(new NotifyDescriptor.Message(
                    error, NotifyDescriptor.WARNING_MESSAGE));
            return;
        }

        WithablePanel panel = new WithablePanel(fields);
        int result = JOptionPane.showConfirmDialog(component, panel,
                NbBundle.getMessage(WithableGenerator.class, "WithableGenerator.dialogTitle"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }

        List<String> selected = panel.getSelectedNames();
        if (selected.isEmpty()) {
            return;
        }

        if (!generate(component, classHandle, selected)) {
            DialogDisplayer.getDefault().notifyLater(new NotifyDescriptor.Message(
                    NbBundle.getMessage(WithableGenerator.class, "WithableGenerator.error.failed"),
                    NotifyDescriptor.WARNING_MESSAGE));
        }
    }

    private static boolean generate(JTextComponent component,
            ElementHandle<TypeElement> classHandle, List<String> selectedNames) {
        try {
            JavaSource source = JavaSource.forDocument(component.getDocument());
            if (source == null) {
                return false;
            }

            boolean[] modified = new boolean[1];
            ModificationResult result = source.runModificationTask(wc -> {
                wc.toPhase(JavaSource.Phase.RESOLVED);

                TypeElement type = classHandle.resolve(wc);
                if (type == null) {
                    return;
                }
                TreePath typePath = wc.getTrees().getPath(type);
                if (typePath == null || !(typePath.getLeaf() instanceof ClassTree)) {
                    return;
                }
                ClassTree classTree = (ClassTree) typePath.getLeaf();

                List<VariableElement> currentFields = collectFieldElements(type);
                int[] mapping = null;
                for (ExecutableElement constructor
                        : ElementFilter.constructorsIn(type.getEnclosedElements())) {
                    mapping = findConstructorMapping(constructor, currentFields, wc.getTypes());
                    if (mapping != null) {
                        break;
                    }
                }
                if (mapping == null) {
                    return;
                }

                List<Tree> methods = new ArrayList<>();
                for (String name : selectedNames) {
                    int target = indexOfField(currentFields, name);
                    if (target < 0 || hasWither(type, name)) {
                        continue;
                    }
                    methods.add(createWither(wc.getTreeMaker(), type, currentFields, mapping, target));
                }
                if (methods.isEmpty()) {
                    return;
                }

                ClassTree newClass = GeneratorUtilities.get(wc)
                        .insertClassMembers(classTree, methods);
                wc.rewrite(classTree, newClass);
                modified[0] = true;
            });

            result.commit();
            return modified[0];
        } catch (Exception ex) {
            Exceptions.printStackTrace(ex);
            return false;
        }
    }

    private static MethodTree createWither(TreeMaker make, TypeElement type,
            List<VariableElement> fields, int[] constructorMapping, int targetIndex) {
        VariableElement targetField = fields.get(targetIndex);
        String methodName = "with" + upperFirst(targetField.getSimpleName().toString());

        Tree returnType = make.Identifier(type.getSimpleName());
        VariableTree parameter = make.Variable(
                make.Modifiers(Collections.emptySet()),
                targetField.getSimpleName(),
                make.Type(targetField.asType()),
                null);

        List<ExpressionTree> arguments = new ArrayList<>();
        for (int fieldIndex : constructorMapping) {
            if (fieldIndex == targetIndex) {
                arguments.add(make.Identifier(targetField.getSimpleName()));
            } else {
                arguments.add(make.MemberSelect(
                        make.Identifier("this"),
                        fields.get(fieldIndex).getSimpleName()));
            }
        }

        NewClassTree newClass = make.NewClass(null, Collections.emptyList(),
                make.Identifier(type.getSimpleName()), arguments, null);
        BlockTree body = make.Block(
                Collections.singletonList(make.Return(newClass)), false);

        return make.Method(make.Modifiers(EnumSet.of(Modifier.PUBLIC)), methodName,
                returnType, Collections.emptyList(),
                Collections.singletonList(parameter), Collections.emptyList(),
                body, null);
    }

    private static List<VariableElement> collectFieldElements(TypeElement type) {
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

    private static boolean hasMatchingConstructor(TypeElement type,
            List<VariableElement> fields, Types types) {
        for (ExecutableElement constructor
                : ElementFilter.constructorsIn(type.getEnclosedElements())) {
            if (findConstructorMapping(constructor, fields, types) != null) {
                return true;
            }
        }
        return false;
    }

    private static int[] findConstructorMapping(ExecutableElement constructor,
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

    private static boolean hasWither(TypeElement type, String fieldName) {
        String methodName = "with" + upperFirst(fieldName);
        for (ExecutableElement method : ElementFilter.methodsIn(type.getEnclosedElements())) {
            if (method.getSimpleName().contentEquals(methodName)
                    && method.getParameters().size() == 1) {
                return true;
            }
        }
        return false;
    }

    private static int indexOfField(List<VariableElement> fields, String name) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).getSimpleName().contentEquals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static String upperFirst(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /**
     * Factory registered in the layer under the Java editor CodeGenerators
     * folder.
     */
    public static final class Factory implements CodeGenerator.Factory {

        @Override
        public List<? extends CodeGenerator> create(Lookup context) {
            JTextComponent component = context.lookup(JTextComponent.class);
            CompilationController controller = context.lookup(CompilationController.class);
            TreePath path = context.lookup(TreePath.class);
            if (component == null || controller == null || path == null) {
                return Collections.emptyList();
            }

            try {
                TreePath classPath = controller.getTreeUtilities().getPathElementOfKind(
                        EnumSet.of(Tree.Kind.CLASS), path);
                if (classPath == null) {
                    return Collections.emptyList();
                }

                controller.toPhase(JavaSource.Phase.ELEMENTS_RESOLVED);
                Element element = controller.getTrees().getElement(classPath);
                if (!(element instanceof TypeElement)) {
                    return Collections.emptyList();
                }

                TypeElement type = (TypeElement) element;
                if (type.getNestingKind() == NestingKind.ANONYMOUS) {
                    return Collections.emptyList();
                }
                if (type.getKind() == ElementKind.RECORD) {
                    return error(component, type, "WithableGenerator.error.record");
                }
                if (type.getKind() != ElementKind.CLASS) {
                    return Collections.emptyList();
                }

                List<VariableElement> declaredFields = collectFieldElements(type);
                if (declaredFields.isEmpty()) {
                    return Collections.emptyList();
                }
                if (!type.getTypeParameters().isEmpty()) {
                    return error(component, type, "WithableGenerator.error.generic");
                }
                if (type.getModifiers().contains(Modifier.ABSTRACT)) {
                    return error(component, type, "WithableGenerator.error.abstract");
                }
                if (!hasMatchingConstructor(type, declaredFields, controller.getTypes())) {
                    return error(component, type, "WithableGenerator.error.noConstructor");
                }

                List<WithableField> eligible = new ArrayList<>();
                for (VariableElement field : declaredFields) {
                    String name = field.getSimpleName().toString();
                    if (!hasWither(type, name)) {
                        eligible.add(new WithableField(name, field.asType().toString()));
                    }
                }
                if (eligible.isEmpty()) {
                    return error(component, type, "WithableGenerator.error.none");
                }

                ElementHandle<TypeElement> classHandle = ElementHandle.create(type);
                return Collections.singletonList(
                        new WithableGenerator(component, classHandle, eligible, null));
            } catch (IOException ex) {
                Exceptions.printStackTrace(ex);
                return Collections.emptyList();
            } catch (RuntimeException ex) {
                Exceptions.printStackTrace(ex);
                return Collections.emptyList();
            }
        }

        private static List<? extends CodeGenerator> error(
                JTextComponent component, TypeElement type, String key) {
            return Collections.singletonList(new WithableGenerator(
                    component, ElementHandle.create(type), Collections.emptyList(),
                    NbBundle.getMessage(WithableGenerator.class, key)));
        }
    }
}
