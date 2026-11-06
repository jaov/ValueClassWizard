package dev.j8a.valuewizard;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import org.netbeans.api.java.source.CompilationInfo;
import org.netbeans.api.java.source.GeneratorUtilities;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.WorkingCopy;
import org.netbeans.spi.editor.hints.ErrorDescription;
import org.netbeans.spi.editor.hints.Fix;
import org.netbeans.spi.editor.hints.Severity;
import org.netbeans.spi.java.hints.ErrorDescriptionFactory;
import org.netbeans.spi.java.hints.Hint;
import org.netbeans.spi.java.hints.HintContext;
import org.netbeans.spi.java.hints.JavaFix;
import org.netbeans.spi.java.hints.TriggerTreeKind;
import org.openide.util.NbBundle;

/**
 * Editor hint that offers to regenerate the staged builder when the value
 * class fields change.
 */
@Hint(
        id = "dev.j8a.valuewizard.regenerate-builder",
        displayName = "#BuilderHint.displayName",
        description = "#BuilderHint.description",
        category = "java",
        severity = Severity.HINT,
        suppressWarnings = {"valuewizard-builder"})
public final class BuilderHint {

    private BuilderHint() {
    }

    @TriggerTreeKind(Tree.Kind.CLASS)
    public static ErrorDescription computeHint(HintContext ctx) {
        CompilationInfo info = ctx.getInfo();
        TreePath classPath = ctx.getPath();
        Element element = info.getTrees().getElement(classPath);
        if (!(element instanceof TypeElement)) {
            return null;
        }

        TypeElement type = (TypeElement) element;
        if (type.getKind() != ElementKind.CLASS
                || type.getSimpleName().contentEquals(CodeGeneratorSupport.BUILDER_NAME)) {
            return null;
        }

        GeneratedBuilder builder = GeneratedBuilder.find(info, type);
        if (builder == null) {
            return null;
        }

        List<VariableElement> fields = CodeGeneratorSupport.collectInstanceFields(type);
        if (!builder.isStale(fields, info.getTypes())) {
            return null;
        }

        TreePath builderPath = info.getTrees().getPath(builder.builderType);
        if (builderPath == null) {
            builderPath = classPath;
        }

        ExecutableElement constructor = CodeGeneratorSupport.findSuitableConstructor(
                type, fields, info.getTypes(), info.getElements());
        if (constructor == null) {
            return ErrorDescriptionFactory.forTree(ctx, builderPath,
                    NbBundle.getMessage(BuilderHint.class, "BuilderHint.noConstructor"));
        }

        Fix fix = new RegenerateBuilderFix(info, classPath).toEditorFix();
        return ErrorDescriptionFactory.forTree(ctx, builderPath,
                NbBundle.getMessage(BuilderHint.class, "BuilderHint.message"), fix);
    }

    private static final class RegenerateBuilderFix extends JavaFix {

        RegenerateBuilderFix(CompilationInfo info, TreePath path) {
            super(info, path);
        }

        @Override
        protected String getText() {
            return NbBundle.getMessage(BuilderHint.class, "BuilderHint.fix");
        }

        @Override
        protected void performRewrite(TransformationContext tc) throws Exception {
            WorkingCopy wc = tc.getWorkingCopy();
            TreePath classPath = tc.getPath();
            if (!(classPath.getLeaf() instanceof ClassTree)) {
                return;
            }

            wc.toPhase(JavaSource.Phase.RESOLVED);
            Element element = wc.getTrees().getElement(classPath);
            if (!(element instanceof TypeElement)) {
                return;
            }

            TypeElement type = (TypeElement) element;
            if (type.getKind() != ElementKind.CLASS) {
                return;
            }

            GeneratedBuilder builder = GeneratedBuilder.find(wc, type);
            if (builder == null) {
                return;
            }

            List<VariableElement> fields = CodeGeneratorSupport.collectInstanceFields(type);
            if (!builder.isStale(fields, wc.getTypes())) {
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

            ClassTree classTree = (ClassTree) classPath.getLeaf();
            List<Tree> oldMembers = builder.memberTrees(classTree);
            if (oldMembers.isEmpty()) {
                return;
            }

            ClassTree stripped = classTree;
            for (Tree oldMember : oldMembers) {
                stripped = wc.getTreeMaker().removeClassMember(stripped, oldMember);
            }

            List<Tree> newMembers = BuilderAst.createMembers(
                    wc.getTreeMaker(), type, fields, mapping);
            if (newMembers.isEmpty()) {
                return;
            }

            ClassTree rewritten = GeneratorUtilities.get(wc)
                    .insertClassMembers(stripped, newMembers);
            wc.rewrite(classTree, rewritten);
        }
    }
}
