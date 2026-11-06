# LEARNINGS

Running notes from implementing the staged builder feature in the Value Class Wizard NetBeans module.

## Project facts

- The module has a flat source package: `dev.j8a.valuewizard`.
- The new-class path is template based:
  - `FieldsPanel` / `FieldsWizardPanel` collect fields.
  - `ValueClassWizardIterator` passes template parameters.
  - `resources/ValueClass.java.template` is a FreeMarker template.
- The Insert Code path is AST based:
  - `WithableGenerator` implements `CodeGenerator` and registers a nested `Factory`.
  - The factory is registered in `layer.xml` under `Editors/text/x-java/CodeGenerators`.
- Module spec version was `1.2.1`; bumped to `1.3` for the staged builder and `1.4.1` for the regeneration hint.
- Module test/build facts:
  - The module classes and generated code target Java 17.
  - The NetBeans 30 harness is compiled for Java 21+ (class file 65). Running Ant with JDK 17 fails before compilation with `UnsupportedClassVersionError` for `org.openide.xml.XMLUtil`.
  - The `JavaSource`/hint tests need a JDK with `SourceVersion.RELEASE_26`; the system JDK 25 makes `NoJavacHelper.hasWorkingJavac()` false, so `JavaSource.create(...)` returns null.
  - `test.nbjdk.home` makes the test JVM use the JDK bundled with NetBeans even when Ant runs on a newer JDK. Put it in `nbproject/private/platform-private.properties` or `~/.netbeans/30/build.properties`; `-jdk-init` runs before `project.properties` is loaded, so putting it there is too late.
  - Full test command: `JAVA_HOME=/usr/lib/apache-netbeans/jdk ant -f build.xml test`, or run Ant on JDK 21/25 and set `test.nbjdk.home` to the bundled JDK.
- `nbproject/genfiles.properties` was already modified before this work; the Ant build updates its CRC values automatically. Do not revert unrelated local changes there.

## Design decisions

- Keep the builder in this module, not a separate project. It shares wizard plumbing and AST analysis with the existing Withable action.
- Generate a **nested** builder, not a separate file.
- Builder setters use `setX(...)`, not `withX(...)`. The class's `withX(...)` returns a new instance; a builder's setter mutates the builder. Reusing the same name would be a semantic trap.
- Use a **staged builder** so every field must be set before `build()` is callable.
- Stage interfaces are named `HasX` / `CanBuild`.
- The Insert Code builder action requires an all-arguments constructor, matching the Withable action.

## Technical findings

### Cyclic inheritance with self-nested interfaces

This does **not** compile:

```java
class Builder implements Builder.HasName {
    interface HasName { ... }
}
```

`javac` reports `cyclic inheritance involving Builder`.

Working shape: the stage interfaces are siblings of the private builder implementation.

```java
public interface HasName { HasAge setName(String name); }
public interface HasAge { CanBuild setAge(int age); }
public interface CanBuild { Person build(); }

private static final class Builder implements HasName, HasAge, CanBuild { ... }
```

### Self-type generics are not a substitute

`SELF extends Builder<SELF>` (the `Comparable<V extends Comparable<V>>` style) is for inheritance-friendly fluent APIs. It does not enforce that every setter was called. If a stage setter returns `SELF` as the concrete builder, all setters become available again and the staged guarantee is lost. Since the generated value class and builder are final, `SELF` adds no value.

### TreeMaker API

Verified against `build/public-package-jars/org-netbeans-modules-java-source-base.jar`:

- `TreeMaker.Interface(ModifiersTree, CharSequence, List<? extends TypeParameterTree>, List<? extends Tree>, List<? extends Tree>, List<? extends Tree>)` is the non-deprecated overload. The parameter order is **extends clauses, permits clauses, member declarations**. The 5-argument version is deprecated; it forwards to the 6-argument version with an empty permits list.
- `TreeMaker.Class(...)` likewise has a deprecated 6-argument overload; the 7-argument version is `modifiers, name, type params, extends clause, implements clauses, permits clauses, member declarations`.
- Passing the member list in the **permits** position produces `ClassCastException: JCMethodDecl cannot be cast to JCExpression`. The integration test caught exactly this bug in `BuilderAst`; always pass the empty permits list before the members list.
- `TreeMaker.Method(...)` has two overloads whose last body parameter is either `BlockTree` or `String`. Passing a bare `null` for an abstract interface method is ambiguous; cast it: `(BlockTree) null`.
- `GeneratorUtilities.insertClassMembers(ClassTree, Iterable<? extends Tree>)` accepts a list containing interfaces, a nested class, and a method.
- `TreeMaker.Annotation(Tree, List<? extends ExpressionTree>)` is available for `@Override`.
- `Tree.Kind.RECORD` exists in the JDK used here (Java 17+), so enclosing-type detection can include it.

### FreeMarker template indexing

FreeMarker list loops expose `field_index` and `field_has_next`. The staged-builder template uses:

```ftl
<#if field_has_next>Has${fields[field_index + 1].name?cap_first}<#else>CanBuild</#if>
```

This rendered correctly for 1-, 2-, and 3-field classes. The 2-field output was compiled with `javac` to verify the staged builder shape. When the `builder` flag is false, the output is unchanged.

### Type-name collisions

- A nested type cannot have the same simple name as an enclosing class. The generated `Builder`, `HasX`, and `CanBuild` names must be checked against the value class name.
- `HasX` + `CanBuild` is a safe naming scheme: a field named `build` produces `HasBuild`, not `CanBuild`; a field named `canBuild` produces `HasCanBuild`, not `CanBuild`.
- Fields that differ only by case (`name` and `Name`) produce the same `setName`/`HasName` and must be rejected in both paths. The wizard's existing getter collision check already covers this; the Insert Code factory needs an explicit check.

### Java Hints SPI (1.4)

- The Alt+Enter hint is a Java Hint built with `org.netbeans.spi.java.hints`.
- `@Hint` is source-retained and processed by `org.netbeans.modules.java.hints.spiimpl.processor.JavaHintsAnnotationProcessor`.
- The annotation processor generates `META-INF/generated-layer.xml` with the hint registration and the options-dialog keywords.
- The module build prints `warning: [processing] No processor claimed any of these annotations: ...Hint,...TriggerTreeKind`, but the generated layer is still produced and included in the NBM. The warning is benign here.
- The hint class is registered under `org-netbeans-modules-java-hints/code-hints/dev-j8a-valuewizard-BuilderHint.class`.

### Hint and integration test infrastructure

- `org.netbeans.modules.java.hints.test.api.HintTest` is the right API for hint tests, but it replaces the global `Lookup` with its own `TestLookup`.
- Running those tests in the same JVM as the registration tests breaks them (`Lookups.forPath(...)` becomes empty, `No ActiveDocumentProvider`). Keep the hint/integration tests in a separate test type.
- The working setup uses the **qa-functional** test type:
  - test dependencies: `org.netbeans.modules.java.hints.test`, `org.netbeans.modules.parsing.nb`, `org.netbeans.modules.projectapi.nb`, `org.netbeans.modules.java.j2seplatform`, `org.netbeans.modules.nbjunit`, `org.netbeans.insane`, `org.netbeans.libs.freemarker`, `org.netbeans.modules.java.platform`.
  - `test.run.args` needs `--add-opens=java.base/java.net=ALL-UNNAMED`, `--add-opens=java.prefs/java.util.prefs=ALL-UNNAMED`, and the `jdk.compiler/com.sun.tools.javac.*` exports/opens used by nb-javac. `--add-opens=java.prefs/java.util.prefs` is required by `HintTest.HintWarning.applyFix()`'s preference update.
- Use the JDK bundled with the NetBeans platform for the test JVM. Set `test.nbjdk.home` in `nbproject/private/platform-private.properties` (loaded before `-jdk-init`) or `~/.netbeans/30/build.properties`; setting it in `project.properties` is too late.
  The Ant harness itself still needs Java 21+; the module/generated code remains Java 17.
  `NoJavacHelper.hasWorkingJavac()` checks `SourceVersion.RELEASE_26`. With the system JDK 25, `JavaSource.create(...)` would return null; the bundled JDK 26 makes it work.
- The integration test uses `HintTest.create().input(...)` to get the test harness's temp source root, then reflection on `HintTest.getSourceRoot()` to obtain the `FileObject`. That gives the JavaSource/parsing environment the harness already set up (MIME types, classpath, project manager, boot classpath).
- `BuilderGenerator.addBuilder(JavaSource, ElementHandle)` was extracted as a package-private test hook; the production `invoke()` calls it.
- The hint test's exact warning string is `28:4-28:77:hint:Builder does not match the current fields.`; the `HintTest` string format is `beginLine:beginColumn-endLine:endColumn:severity:description`.
- The tests found a real production bug: `BuilderAst` passed the member declarations as the `permits` argument to `TreeMaker.Interface`/`Class`. The fix is documented above.


### Regeneration shape check

- `GeneratedBuilder` deliberately recognizes only the generated shape: private static final `Builder`, public `HasX` interfaces, public `CanBuild`, public static `builder()`, private fields, private no-arg constructor, and only `setX`/`build` methods.
- If the builder has extra interfaces, fields, or methods, the hint is skipped instead of risking clobbering handwritten code.
- The fix removes the old `Builder`, `HasX` interfaces, `CanBuild`, and `builder()` method, then rebuilds them through `BuilderAst`.

## Implementation log

- Added `CodeGeneratorSupport.java` with shared field collection, constructor matching, method/type lookups, and enclosing-type detection.
- Updated `WithableGenerator` to use the shared helper and the broader enclosing-type detection (now also recognizes interfaces, enums, and records as the nearest type).
- Added `BuilderGenerator.java` with the staged builder generation and the Insert Code factory.
- Added the **Generate builder** checkbox and name-conflict validation to the wizard.
- Added the staged builder block to `ValueClass.java.template`.
- Added bundle strings, the layer registration, `BuilderRegistrationTest`, `FieldsPanelTest`, and README updates.
- Bumped the module spec version to `1.3`.
- `ant -f build.xml clean nbm test` succeeds.
- Verified the template output for 1-, 2-, and 3-field classes and for builder disabled; compiled the 2-field generated class.
- The unit tests pass (builder registration, withable registration, and builder class-name conflict validation). The NetBeans test harness prints a SEVERE `InaccessibleObjectException` about `java.net.URL.handler` on this JDK, but no test fails.
- Tagged the staged builder release as `v1.3.0`.
- Tagged the release with the `j8a <me@j8a.dev>` git identity.

### 1.4: regeneration hint and tests

- Added `BuilderAst`, moving the staged builder AST construction out of `BuilderGenerator`.
- Added `GeneratedBuilder` to recognize the generated shape and compare the builder fields with the class fields.
- Added `BuilderHint` with `@Hint` / `@TriggerTreeKind(Tree.Kind.CLASS)` and a `JavaFix` that removes and rebuilds the generated builder members.
- Added the Java Hints SPI and editor hints dependencies, plus the hint bundle keys.
- Added a registration assertion to `BuilderRegistrationTest` for the generated hint layer entry.
- Added `ValueClassBuilderIntegrationTest`:
  - renders the real `ValueClass.java.template` without the builder,
  - runs `BuilderGenerator.addBuilder` through the `HintTest` source/classpath setup,
  - asserts the staged builder and compiles the result,
  - asserts a second add is rejected.
- Added `BuilderHintTest` (qa-functional, isolated from the unit tests):
  - asserts the stale-builder hint,
  - applies **Regenerate Builder**,
  - asserts the output compiles and contains the new stage/setter.
- Fixed a real `BuilderAst` bug found by the integration test: the member declarations were passed in the `permits` position of `TreeMaker.Interface`/`Class`.
- Bumped the module spec version to `1.4.1`.
- The test setup requires the bundled JDK 26 and the `test.run.args` JVM flags documented above.
- `JAVA_HOME=/usr/lib/apache-netbeans/jdk ant -f build.xml clean test` succeeds.
- The annotation processor emits a benign "No processor claimed" warning but generates `META-INF/generated-layer.xml`; the hint is present in the module jar.
- Remaining: a final IDE-level smoke test of the hint UI and the Insert Code menu.

## Known caveats

- The staged builder fixes the setter order to field declaration order. That is intentional but should be documented for users.
- `build()` does not reject `null` reference values. Add `Objects.requireNonNull` in setters or `build()` if non-null enforcement is wanted later.
- The wizard rejects class names that collide with generated types (`Builder`, `CanBuild`, `HasX`). The Insert Code action additionally rejects existing nested types with those names and duplicate `setX` names from case-differing fields.

## Constructor selection and checked exceptions

`CodeGeneratorSupport.findSuitableConstructor` chooses the first all-arguments constructor whose parameters match the fields and whose `throws` clause contains only unchecked types. The check uses `Elements.getTypeElement("java.lang.RuntimeException")` / `"java.lang.Error"` and `Types.isSubtype`. Both Insert Code actions use this helper, so they no longer generate code that calls a constructor throwing checked exceptions without a `throws` clause. If no suitable constructor exists, the actions report the `noConstructor` message instead of producing uncompilable code.
