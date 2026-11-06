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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import org.netbeans.api.java.source.TreeMaker;

/**
 * Creates the AST members for the staged builder. Shared by the Insert Code
 * action and the editor hint that regenerates the builder.
 */
final class BuilderAst {

    private static final String BUILDER = CodeGeneratorSupport.BUILDER_NAME;
    private static final String CAN_BUILD = CodeGeneratorSupport.CAN_BUILD_NAME;

    private BuilderAst() {
    }

    static String stageName(String fieldName) {
        return CodeGeneratorSupport.STAGE_PREFIX
                + CodeGeneratorSupport.upperFirst(fieldName);
    }

    static String setterName(String fieldName) {
        return "set" + CodeGeneratorSupport.upperFirst(fieldName);
    }

    static List<String> stageNames(List<? extends VariableElement> fields) {
        List<String> result = new ArrayList<>(fields.size());
        for (VariableElement field : fields) {
            result.add(stageName(field.getSimpleName().toString()));
        }
        return result;
    }

    static boolean hasDuplicateSetter(List<? extends VariableElement> fields) {
        Set<String> names = new HashSet<>();
        for (VariableElement field : fields) {
            if (!names.add(setterName(field.getSimpleName().toString()))) {
                return true;
            }
        }
        return false;
    }

    static List<Tree> createMembers(TreeMaker make, TypeElement type,
            List<VariableElement> fields, int[] constructorMapping) {
        if (fields.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> stageNames = stageNames(fields);
        List<Tree> members = new ArrayList<>();
        members.add(createBuilderFactory(make, stageNames.get(0)));
        for (int i = 0; i < fields.size(); i++) {
            String next = i + 1 < fields.size()
                    ? stageNames.get(i + 1) : CAN_BUILD;
            members.add(createStageInterface(
                    make, fields.get(i), stageNames.get(i), next));
        }
        members.add(createCanBuildInterface(make, type));
        members.add(createBuilderClass(make, type, fields, constructorMapping, stageNames));
        return members;
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
                setterName(field.getSimpleName().toString()),
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
                Collections.emptyList(),
                Collections.singletonList(method));
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
                Collections.emptyList(),
                Collections.singletonList(method));
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
                Collections.emptyList(),
                members);
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
                setterName(field.getSimpleName().toString()),
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
}
