package dev.j8a.valuewizard;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
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
 * Editor Insert Code action that adds a staged builder to an existing class.
 */
public final class BuilderGenerator implements CodeGenerator {

    private static final Logger LOG = Logger.getLogger(BuilderGenerator.class.getName());

    private static final String BUILDER = CodeGeneratorSupport.BUILDER_NAME;
    private static final String CAN_BUILD = CodeGeneratorSupport.CAN_BUILD_NAME;

    private final JTextComponent component;
    private final ElementHandle<TypeElement> classHandle;
    private final String error;

    private BuilderGenerator(JTextComponent component,
            ElementHandle<TypeElement> classHandle, String error) {
        this.component = component;
        this.classHandle = classHandle;
        this.error = error;
    }

    @Override
    public String getDisplayName() {
        return NbBundle.getMessage(BuilderGenerator.class, "BuilderGenerator.displayName");
    }

    @Override
    public void invoke() {
        LOG.fine("Builder action invoked");
        if (error != null) {
            LOG.fine("Builder action: " + error);
            DialogDisplayer.getDefault().notifyLater(new NotifyDescriptor.Message(
                    error, NotifyDescriptor.WARNING_MESSAGE));
            return;
        }

        if (!generate(component, classHandle)) {
            LOG.fine("Builder action: generation failed");
            DialogDisplayer.getDefault().notifyLater(new NotifyDescriptor.Message(
                    NbBundle.getMessage(BuilderGenerator.class, "BuilderGenerator.error.failed"),
                    NotifyDescriptor.WARNING_MESSAGE));
        }
    }

    private static boolean generate(JTextComponent component,
            ElementHandle<TypeElement> classHandle) {
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

                if (type.getSimpleName().contentEquals(BUILDER)
                        || CodeGeneratorSupport.hasNestedType(type, BUILDER)
                        || CodeGeneratorSupport.hasMethod(type, "builder", 0)) {
                    return;
                }

                List<VariableElement> fields = CodeGeneratorSupport.collectInstanceFields(type);
                if (fields.isEmpty() || hasDuplicateSetter(fields)) {
                    return;
                }
                ExecutableElement constructor = CodeGeneratorSupport.findSuitableConstructor(
                        type, fields, wc.getTypes(), wc.getElements());
                if (constructor == null) {
                    return;
                }
                int[] mapping = CodeGeneratorSupport.findConstructorMapping(
                        constructor, fields, wc.getTypes());
                if (mapping == null) {
                    return;
                }

                List<String> stageNames = stageNames(fields);
                if (conflictsWithEnclosingOrExistingTypes(type, stageNames)) {
                    return;
                }

                TreeMaker make = wc.getTreeMaker();
                List<Tree> members = new ArrayList<>();
                members.add(createBuilderFactory(make, stageNames.get(0)));
                for (int i = 0; i < fields.size(); i++) {
                    String next = i + 1 < fields.size()
                            ? stageNames.get(i + 1) : CAN_BUILD;
                    members.add(createStageInterface(
                            make, fields.get(i), stageNames.get(i), next));
                }
                members.add(createCanBuildInterface(make, type));
                members.add(createBuilderClass(make, type, fields, mapping, stageNames));

                ClassTree newClass = GeneratorUtilities.get(wc)
                        .insertClassMembers(classTree, members);
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

    private static List<String> stageNames(List<VariableElement> fields) {
        List<String> result = new ArrayList<>(fields.size());
        for (VariableElement field : fields) {
            result.add(CodeGeneratorSupport.STAGE_PREFIX
                    + CodeGeneratorSupport.upperFirst(
                            field.getSimpleName().toString()));
        }
        return result;
    }

    private static boolean conflictsWithEnclosingOrExistingTypes(
            TypeElement type, List<String> stageNames) {
        String typeName = type.getSimpleName().toString();
        if (typeName.equals(CAN_BUILD)
                || CodeGeneratorSupport.hasNestedType(type, CAN_BUILD)) {
            return true;
        }
        for (String stageName : stageNames) {
            if (typeName.equals(stageName)
                    || CodeGeneratorSupport.hasNestedType(type, stageName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDuplicateSetter(List<VariableElement> fields) {
        Set<String> names = new HashSet<>();
        for (VariableElement field : fields) {
            String name = "set" + CodeGeneratorSupport.upperFirst(
                    field.getSimpleName().toString());
            if (!names.add(name)) {
                return true;
            }
        }
        return false;
    }

    private static MethodTree createBuilderFactory(TreeMaker make, String firstStage) {
        NewClassTree newClass = make.NewClass(null, Collections.emptyList(),
                make.Identifier(BUILDER), Collections.emptyList(), null);
        BlockTree body = make.Block(
                Collections.singletonList(make.Return(newClass)), false);
        return make.Method(
                make.Modifiers(EnumSet.of(Modifier.PUBLIC, Modifier.STATIC)),
                "builder",
                make.Identifier(firstStage),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                body, null);
    }

    private static ClassTree createStageInterface(TreeMaker make,
            VariableElement field, String stageName, String nextStage) {
        VariableTree parameter = make.Variable(
                make.Modifiers(Collections.emptySet()),
                field.getSimpleName(),
                make.Type(field.asType()),
                null);
        MethodTree method = make.Method(
                make.Modifiers(Collections.emptySet()),
                "set" + CodeGeneratorSupport.upperFirst(field.getSimpleName().toString()),
                make.Identifier(nextStage),
                Collections.emptyList(),
                Collections.singletonList(parameter),
                Collections.emptyList(),
                (BlockTree) null, null);
        return make.Interface(
                make.Modifiers(EnumSet.of(Modifier.PUBLIC)),
                stageName,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.singletonList(method),
                Collections.emptyList());
    }

    private static ClassTree createCanBuildInterface(TreeMaker make, TypeElement type) {
        MethodTree method = make.Method(
                make.Modifiers(Collections.emptySet()),
                "build",
                make.Identifier(type.getSimpleName()),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                (BlockTree) null, null);
        return make.Interface(
                make.Modifiers(EnumSet.of(Modifier.PUBLIC)),
                CAN_BUILD,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.singletonList(method),
                Collections.emptyList());
    }

    private static ClassTree createBuilderClass(TreeMaker make, TypeElement type,
            List<VariableElement> fields, int[] constructorMapping,
            List<String> stageNames) {
        List<Tree> implementsList = new ArrayList<>(stageNames.size() + 1);
        for (String stageName : stageNames) {
            implementsList.add(make.Identifier(stageName));
        }
        implementsList.add(make.Identifier(CAN_BUILD));

        List<Tree> members = new ArrayList<>();
        members.add(createBuilderConstructor(make));
        for (VariableElement field : fields) {
            members.add(createBuilderField(make, field));
        }
        for (int i = 0; i < fields.size(); i++) {
            String next = i + 1 < fields.size()
                    ? stageNames.get(i + 1) : CAN_BUILD;
            members.add(createBuilderSetter(make, fields.get(i), next));
        }
        members.add(createBuildMethod(make, type, fields, constructorMapping));

        return make.Class(
                make.Modifiers(EnumSet.of(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)),
                BUILDER,
                Collections.emptyList(),
                null,
                implementsList,
                members,
                Collections.emptyList());
    }

    private static MethodTree createBuilderConstructor(TreeMaker make) {
        return make.Method(
                make.Modifiers(EnumSet.of(Modifier.PRIVATE)),
                BUILDER,
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                make.Block(Collections.emptyList(), false),
                null);
    }

    private static VariableTree createBuilderField(TreeMaker make, VariableElement field) {
        return make.Variable(
                make.Modifiers(EnumSet.of(Modifier.PRIVATE)),
                field.getSimpleName(),
                make.Type(field.asType()),
                null);
    }

    private static MethodTree createBuilderSetter(TreeMaker make,
            VariableElement field, String nextStage) {
        VariableTree parameter = make.Variable(
                make.Modifiers(Collections.emptySet()),
                field.getSimpleName(),
                make.Type(field.asType()),
                null);
        ExpressionTree assignment = make.Assignment(
                make.MemberSelect(make.Identifier("this"), field.getSimpleName()),
                make.Identifier(field.getSimpleName()));
        List<StatementTree> statements = new ArrayList<>();
        statements.add(make.ExpressionStatement(assignment));
        statements.add(make.Return(make.Identifier("this")));
        BlockTree body = make.Block(statements, false);
        return make.Method(
                overrideModifiers(make),
                "set" + CodeGeneratorSupport.upperFirst(field.getSimpleName().toString()),
                make.Identifier(nextStage),
                Collections.emptyList(),
                Collections.singletonList(parameter),
                Collections.emptyList(),
                body, null);
    }

    private static MethodTree createBuildMethod(TreeMaker make, TypeElement type,
            List<VariableElement> fields, int[] constructorMapping) {
        List<ExpressionTree> arguments = new ArrayList<>();
        for (int fieldIndex : constructorMapping) {
            arguments.add(make.MemberSelect(
                    make.Identifier("this"),
                    fields.get(fieldIndex).getSimpleName()));
        }
        NewClassTree newClass = make.NewClass(null, Collections.emptyList(),
                make.Identifier(type.getSimpleName()), arguments, null);
        BlockTree body = make.Block(
                Collections.singletonList(make.Return(newClass)), false);
        return make.Method(
                overrideModifiers(make),
                "build",
                make.Identifier(type.getSimpleName()),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                body, null);
    }

    private static ModifiersTree overrideModifiers(TreeMaker make) {
        AnnotationTree override = make.Annotation(
                make.Identifier("Override"), Collections.emptyList());
        return make.Modifiers(EnumSet.of(Modifier.PUBLIC),
                Collections.singletonList(override));
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
                LOG.fine("Builder factory: missing editor context (component="
                        + (component != null) + ", controller=" + (controller != null) + ")");
                return Collections.emptyList();
            }

            try {
                if (path == null) {
                    controller.toPhase(JavaSource.Phase.PARSED);
                    path = controller.getTreeUtilities().pathFor(component.getCaretPosition());
                    LOG.fine("Builder factory: recomputed caret path=" + (path != null));
                }
                if (path == null) {
                    path = CodeGeneratorSupport.firstClassPath(controller);
                    LOG.fine("Builder factory: using first class path=" + (path != null));
                }
                if (path == null) {
                    LOG.fine("Builder factory: no class path available");
                    return Collections.emptyList();
                }

                TreePath classPath = CodeGeneratorSupport.enclosingTypePath(controller, path);
                if (classPath == null) {
                    LOG.fine("Builder factory: caret is not inside a class");
                    return error(component, "BuilderGenerator.error.notInClass");
                }

                controller.toPhase(JavaSource.Phase.ELEMENTS_RESOLVED);
                Element element = controller.getTrees().getElement(classPath);
                if (!(element instanceof TypeElement)) {
                    LOG.fine("Builder factory: caret element is not a type");
                    return error(component, "BuilderGenerator.error.notClass");
                }

                TypeElement type = (TypeElement) element;
                LOG.fine("Builder factory: class=" + type.getQualifiedName()
                        + ", kind=" + type.getKind()
                        + ", nesting=" + type.getNestingKind());
                if (type.getNestingKind() == NestingKind.ANONYMOUS) {
                    LOG.fine("Builder factory: anonymous class ignored");
                    return error(component, type, "BuilderGenerator.error.anonymous");
                }
                if (type.getNestingKind() == NestingKind.LOCAL) {
                    LOG.fine("Builder factory: local class ignored");
                    return error(component, type, "BuilderGenerator.error.local");
                }
                if (type.getKind() == ElementKind.RECORD) {
                    LOG.fine("Builder factory: record ignored");
                    return error(component, type, "BuilderGenerator.error.record");
                }
                if (type.getKind() != ElementKind.CLASS) {
                    LOG.fine("Builder factory: non-class type ignored");
                    return error(component, type, "BuilderGenerator.error.notClass");
                }

                List<VariableElement> fields = CodeGeneratorSupport.collectInstanceFields(type);
                LOG.fine("Builder factory: instance fields=" + fields.size());
                if (fields.isEmpty()) {
                    return error(component, type, "BuilderGenerator.error.noFields");
                }
                if (!type.getTypeParameters().isEmpty()) {
                    LOG.fine("Builder factory: generic class ignored");
                    return error(component, type, "BuilderGenerator.error.generic");
                }
                if (type.getModifiers().contains(Modifier.ABSTRACT)) {
                    LOG.fine("Builder factory: abstract class ignored");
                    return error(component, type, "BuilderGenerator.error.abstract");
                }
                if (CodeGeneratorSupport.findSuitableConstructor(
                        type, fields, controller.getTypes(),
                        controller.getElements()) == null) {
                    LOG.fine("Builder factory: no suitable all-arguments constructor");
                    return error(component, type, "BuilderGenerator.error.noConstructor");
                }
                if (type.getSimpleName().contentEquals(BUILDER)) {
                    LOG.fine("Builder factory: class is named Builder");
                    return error(component, type, "BuilderGenerator.error.className");
                }
                if (CodeGeneratorSupport.hasNestedType(type, BUILDER)
                        || CodeGeneratorSupport.hasMethod(type, "builder", 0)) {
                    LOG.fine("Builder factory: builder type or method already exists");
                    return error(component, type, "BuilderGenerator.error.exists");
                }

                List<String> stageNames = stageNames(fields);
                if (conflictsWithEnclosingOrExistingTypes(type, stageNames)) {
                    LOG.fine("Builder factory: generated type name conflict");
                    return error(component, type, "BuilderGenerator.error.nameConflict");
                }
                if (hasDuplicateSetter(fields)) {
                    LOG.fine("Builder factory: duplicate setter names");
                    return error(component, type, "BuilderGenerator.error.duplicateSetter");
                }

                ElementHandle<TypeElement> classHandle = ElementHandle.create(type);
                LOG.fine("Builder factory: offering action for " + type.getQualifiedName());
                return Collections.singletonList(
                        new BuilderGenerator(component, classHandle, null));
            } catch (IOException ex) {
                LOG.log(Level.FINE, "Builder factory: analysis failed", ex);
                return error(component, "BuilderGenerator.error.analysisFailed");
            } catch (RuntimeException ex) {
                LOG.log(Level.FINE, "Builder factory: unexpected failure", ex);
                return error(component, "BuilderGenerator.error.analysisFailed");
            }
        }

        private static List<? extends CodeGenerator> error(
                JTextComponent component, String key) {
            return Collections.singletonList(new BuilderGenerator(
                    component, null,
                    NbBundle.getMessage(BuilderGenerator.class, key)));
        }

        private static List<? extends CodeGenerator> error(
                JTextComponent component, TypeElement type, String key) {
            ElementHandle<TypeElement> handle;
            try {
                handle = ElementHandle.create(type);
            } catch (RuntimeException ex) {
                LOG.log(Level.FINE, "Builder factory: cannot create class handle", ex);
                handle = null;
            }
            return Collections.singletonList(new BuilderGenerator(
                    component, handle,
                    NbBundle.getMessage(BuilderGenerator.class, key)));
        }
    }
}
