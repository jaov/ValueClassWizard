package dev.j8a.valuewizard;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import java.io.IOException;
import java.util.Collections;
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
            return addBuilder(source, classHandle);
        } catch (Exception ex) {
            Exceptions.printStackTrace(ex);
            return false;
        }
    }

    static boolean addBuilder(JavaSource source, ElementHandle<TypeElement> classHandle) {
        try {
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
                if (fields.isEmpty() || BuilderAst.hasDuplicateSetter(fields)) {
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

                List<String> stageNames = BuilderAst.stageNames(fields);
                if (conflictsWithEnclosingOrExistingTypes(type, stageNames)) {
                    return;
                }

                TreeMaker make = wc.getTreeMaker();
                List<Tree> members = BuilderAst.createMembers(make, type, fields, mapping);

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

                List<String> stageNames = BuilderAst.stageNames(fields);
                if (conflictsWithEnclosingOrExistingTypes(type, stageNames)) {
                    LOG.fine("Builder factory: generated type name conflict");
                    return error(component, type, "BuilderGenerator.error.nameConflict");
                }
                if (BuilderAst.hasDuplicateSetter(fields)) {
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
