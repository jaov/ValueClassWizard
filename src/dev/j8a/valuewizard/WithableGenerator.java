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
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.swing.JOptionPane;
import javax.swing.text.JTextComponent;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.GeneratorUtilities;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.ModificationResult;
import org.netbeans.api.java.source.TreeMaker;
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

    private static final Logger LOG = Logger.getLogger(WithableGenerator.class.getName());

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
        LOG.fine("Withable action invoked");
        if (error != null) {
            LOG.fine("Withable action: " + error);
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
            LOG.fine("Withable action: generation failed for " + selected);
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

                List<VariableElement> currentFields = CodeGeneratorSupport.collectInstanceFields(type);
                ExecutableElement constructor = CodeGeneratorSupport.findSuitableConstructor(
                        type, currentFields, wc.getTypes(), wc.getElements());
                if (constructor == null) {
                    return;
                }
                int[] mapping = CodeGeneratorSupport.findConstructorMapping(
                        constructor, currentFields, wc.getTypes());
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
        String methodName = "with" + CodeGeneratorSupport.upperFirst(
                targetField.getSimpleName().toString());

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

    private static boolean hasWither(TypeElement type, String fieldName) {
        return CodeGeneratorSupport.hasMethod(type,
                "with" + CodeGeneratorSupport.upperFirst(fieldName), 1);
    }

    private static int indexOfField(List<VariableElement> fields, String name) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).getSimpleName().contentEquals(name)) {
                return i;
            }
        }
        return -1;
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
            if (component == null || controller == null) {
                LOG.fine("Withable factory: missing editor context (component="
                        + (component != null) + ", controller=" + (controller != null) + ")");
                return Collections.emptyList();
            }

            try {
                if (path == null) {
                    controller.toPhase(JavaSource.Phase.PARSED);
                    path = controller.getTreeUtilities().pathFor(component.getCaretPosition());
                    LOG.fine("Withable factory: recomputed caret path=" + (path != null));
                }
                if (path == null) {
                    path = CodeGeneratorSupport.firstClassPath(controller);
                    LOG.fine("Withable factory: using first class path=" + (path != null));
                }
                if (path == null) {
                    LOG.fine("Withable factory: no class path available");
                    return Collections.emptyList();
                }

                TreePath classPath = CodeGeneratorSupport.enclosingTypePath(controller, path);
                if (classPath == null) {
                    LOG.fine("Withable factory: caret is not inside a class");
                    return error(component, "WithableGenerator.error.notInClass");
                }

                controller.toPhase(JavaSource.Phase.ELEMENTS_RESOLVED);
                Element element = controller.getTrees().getElement(classPath);
                if (!(element instanceof TypeElement)) {
                    LOG.fine("Withable factory: caret element is not a type");
                    return error(component, "WithableGenerator.error.notClass");
                }

                TypeElement type = (TypeElement) element;
                LOG.fine("Withable factory: class=" + type.getQualifiedName()
                        + ", kind=" + type.getKind()
                        + ", nesting=" + type.getNestingKind());
                if (type.getNestingKind() == NestingKind.ANONYMOUS) {
                    LOG.fine("Withable factory: anonymous class ignored");
                    return error(component, type, "WithableGenerator.error.anonymous");
                }
                if (type.getKind() == ElementKind.RECORD) {
                    LOG.fine("Withable factory: record ignored");
                    return error(component, type, "WithableGenerator.error.record");
                }
                if (type.getKind() != ElementKind.CLASS) {
                    LOG.fine("Withable factory: non-class type ignored");
                    return error(component, type, "WithableGenerator.error.notClass");
                }

                List<VariableElement> declaredFields = CodeGeneratorSupport.collectInstanceFields(type);
                LOG.fine("Withable factory: instance fields=" + declaredFields.size());
                if (declaredFields.isEmpty()) {
                    return error(component, type, "WithableGenerator.error.noFields");
                }
                if (!type.getTypeParameters().isEmpty()) {
                    LOG.fine("Withable factory: generic class ignored");
                    return error(component, type, "WithableGenerator.error.generic");
                }
                if (type.getModifiers().contains(Modifier.ABSTRACT)) {
                    LOG.fine("Withable factory: abstract class ignored");
                    return error(component, type, "WithableGenerator.error.abstract");
                }
                if (CodeGeneratorSupport.findSuitableConstructor(
                        type, declaredFields, controller.getTypes(),
                        controller.getElements()) == null) {
                    LOG.fine("Withable factory: no suitable all-arguments constructor");
                    return error(component, type, "WithableGenerator.error.noConstructor");
                }

                List<WithableField> eligible = new ArrayList<>();
                for (VariableElement field : declaredFields) {
                    String name = field.getSimpleName().toString();
                    if (!hasWither(type, name)) {
                        eligible.add(new WithableField(name, field.asType().toString()));
                    }
                }
                LOG.fine("Withable factory: eligible fields=" + eligible.size());
                if (eligible.isEmpty()) {
                    return error(component, type, "WithableGenerator.error.none");
                }

                ElementHandle<TypeElement> classHandle = ElementHandle.create(type);
                LOG.fine("Withable factory: offering action for " + type.getQualifiedName());
                return Collections.singletonList(
                        new WithableGenerator(component, classHandle, eligible, null));
            } catch (IOException ex) {
                LOG.log(Level.FINE, "Withable factory: analysis failed", ex);
                return error(component, "WithableGenerator.error.analysisFailed");
            } catch (RuntimeException ex) {
                LOG.log(Level.FINE, "Withable factory: unexpected failure", ex);
                return error(component, "WithableGenerator.error.analysisFailed");
            }
        }

        private static List<? extends CodeGenerator> error(
                JTextComponent component, String key) {
            return Collections.singletonList(new WithableGenerator(
                    component, null, Collections.emptyList(),
                    NbBundle.getMessage(WithableGenerator.class, key)));
        }

        private static List<? extends CodeGenerator> error(
                JTextComponent component, TypeElement type, String key) {
            ElementHandle<TypeElement> handle;
            try {
                handle = ElementHandle.create(type);
            } catch (RuntimeException ex) {
                LOG.log(Level.FINE, "Withable factory: cannot create class handle", ex);
                handle = null;
            }
            return Collections.singletonList(new WithableGenerator(
                    component, handle, Collections.emptyList(),
                    NbBundle.getMessage(WithableGenerator.class, key)));
        }
    }
}
