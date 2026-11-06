package dev.j8a.valuewizard;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import freemarker.cache.StringTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import java.io.File;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.Test;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.modules.java.hints.test.api.HintTest;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ValueClassBuilderIntegrationTest {

    @Test
    public void generatedValueClassAcceptsBuilderAction() throws Exception {
        String valueClassSource = renderValueClass(false);
        assertFalse(valueClassSource.contains("interface HasName"));

        HintTest hintTest = HintTest.create().input(valueClassSource);
        Method getSourceRoot = HintTest.class.getDeclaredMethod("getSourceRoot");
        getSourceRoot.setAccessible(true);
        FileObject sourceRoot = (FileObject) getSourceRoot.invoke(hintTest);
        FileObject sourceFile = sourceRoot.getFileObject("test/Test.java");
        assertNotNull(sourceFile);
        assertTrue(sourceFile.asText().contains("class Test"));

        JavaSource javaSource = JavaSource.create(
                ClasspathInfo.create(sourceFile), sourceFile);
        assertNotNull("JavaSource was not created", javaSource);

        ElementHandle<TypeElement> handle = findClassHandle(javaSource);
        assertNotNull("The value class was not found", handle);
        assertTrue("Builder generation failed",
                BuilderGenerator.addBuilder(javaSource, handle));
        org.openide.LifecycleManager.getDefault().saveAll();

        String generated = sourceFile.asText();
        assertTrue(generated.contains("public static HasName builder()"));
        assertTrue(generated.contains("interface HasName"));
        assertTrue(generated.contains("interface HasAge"));
        assertTrue(generated.contains("interface HasCity"));
        assertTrue(generated.contains("setCity(String city)"));
        assertTrue(generated.contains("new Test(this.name, this.age, this.city)"));

        compile(FileUtil.toFile(sourceFile));

        JavaSource updatedSource = JavaSource.create(
                ClasspathInfo.create(sourceFile), sourceFile);
        assertNotNull(updatedSource);
        ElementHandle<TypeElement> updatedHandle = findClassHandle(updatedSource);
        assertNotNull(updatedHandle);
        assertFalse("A second builder must not be added",
                BuilderGenerator.addBuilder(updatedSource, updatedHandle));
    }

    private static ElementHandle<TypeElement> findClassHandle(JavaSource source)
            throws Exception {
        @SuppressWarnings("unchecked")
        ElementHandle<TypeElement>[] result = new ElementHandle[1];
        source.runUserActionTask(controller -> {
            controller.toPhase(JavaSource.Phase.RESOLVED);
            CompilationUnitTree unit = controller.getCompilationUnit();
            if (unit == null) {
                throw new AssertionError("No compilation unit");
            }
            for (Tree declaration : unit.getTypeDecls()) {
                if (!(declaration instanceof ClassTree)) {
                    continue;
                }
                TreePath path = TreePath.getPath(unit, declaration);
                Element element = controller.getTrees().getElement(path);
                if (element instanceof TypeElement) {
                    result[0] = ElementHandle.create((TypeElement) element);
                    return;
                }
            }
            throw new AssertionError("No type element; declarations="
                    + unit.getTypeDecls());
        }, true);
        return result[0];
    }

    private static void compile(File sourceFile) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("No system Java compiler", compiler);
        File classesDir;
        try {
            classesDir = java.nio.file.Files.createTempDirectory(
                    "value-wizard-classes").toFile();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        try (StandardJavaFileManager fileManager
                = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units
                    = fileManager.getJavaFileObjects(sourceFile);
            List<String> options = Arrays.asList(
                    "-d", classesDir.getAbsolutePath());
            boolean success = compiler.getTask(
                    null, fileManager, null, options, null, units).call();
            assertTrue("Generated value class did not compile", success);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static String renderValueClass(boolean builder) throws Exception {
        String templateText = readResource("resources/ValueClass.java.template");
        StringTemplateLoader loader = new StringTemplateLoader();
        loader.putTemplate("license.txt", "");
        loader.putTemplate("ValueClass.java.template", templateText);
        Configuration configuration = new Configuration(
                Configuration.VERSION_2_3_34);
        configuration.setTemplateLoader(loader);
        Template template = configuration.getTemplate("ValueClass.java.template");

        Map<String, Object> root = new HashMap<>();
        root.put("name", "Test");
        root.put("package", "test");
        root.put("user", "test");
        root.put("imports", new ArrayList<String>());

        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("String", "name"));
        fields.add(field("int", "age"));
        fields.add(field("String", "city"));
        root.put("fields", fields);
        root.put("builder", builder);

        Map<String, Object> project = new HashMap<>();
        project.put("licensePath", "license.txt");
        root.put("project", project);

        StringWriter writer = new StringWriter();
        template.process(root, writer);
        return writer.toString();
    }

    private static Map<String, Object> field(String type, String name) {
        Map<String, Object> field = new HashMap<>();
        field.put("type", type);
        field.put("name", name);
        field.put("wither", false);
        return field;
    }

    private static String readResource(String name) throws Exception {
        var stream = ValueClassBuilderIntegrationTest.class.getResourceAsStream(name);
        assertNotNull("Missing template resource: " + name, stream);
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
